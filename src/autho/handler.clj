(ns autho.handler
  (:require [autho.pdp :as pdp]
            [autho.prp :as prp]
            [autho.pip :as pip]
            [autho.kafka-pip :as kpip]
            [autho.journal :as jrnl]
            [autho.person :as person]
            [compojure.core :refer :all]
            [com.appsflyer.donkey.core :refer [create-donkey create-server]]
            [com.appsflyer.donkey.server :refer [start]]
            [com.appsflyer.donkey.result :refer [on-success]]
            [compojure.route :as route]
            [jsonista.core :as json]
            [autho.auth :as auth]
            [clojure.string :as str])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.handler"))

;; Maximum request body size in bytes (default: 1MB)
;; Can be overridden with MAX_REQUEST_SIZE environment variable
(def max-request-size
  (if-let [env-size (System/getenv "MAX_REQUEST_SIZE")]
    (try
      (Long/parseLong env-size)
      (catch NumberFormatException _
        (* 1024 1024)))
    (* 1024 1024)))

;; Rate limiting configuration
;; RATE_LIMIT_REQUESTS_PER_MINUTE: number of requests allowed per minute per IP (default: 100)
;; RATE_LIMIT_ENABLED: enable/disable rate limiting (default: true)
(def rate-limit-enabled
  (if-let [env-enabled (System/getenv "RATE_LIMIT_ENABLED")]
    (Boolean/parseBoolean env-enabled)
    true))

(def rate-limit-requests-per-minute
  (if-let [env-limit (System/getenv "RATE_LIMIT_REQUESTS_PER_MINUTE")]
    (try
      (Long/parseLong env-limit)
      (catch NumberFormatException _
        100))
    100))

;; Store request timestamps per IP address
;; Structure: {ip-address [timestamp1 timestamp2 ...]}
(defonce rate-limit-store (atom {}))

(defn- get-client-ip
  "Extracts the client IP address from the request.
  Checks X-Forwarded-For header first (for proxies), then falls back to remote-addr."
  [request]
  (or (first (str/split (or (get-in request [:headers "x-forwarded-for"]) "") #","))
      (get request :remote-addr)
      "unknown"))

(defn- clean-old-timestamps
  "Removes timestamps older than 1 minute from the list."
  [timestamps]
  (let [one-minute-ago (- (System/currentTimeMillis) 60000)]
    (filter #(> % one-minute-ago) timestamps)))

(defn wrap-rate-limit [handler]
  (fn [request]
    (if-not rate-limit-enabled
      (handler request)
      (let [client-ip (get-client-ip request)
            current-time (System/currentTimeMillis)
            ;; Update the store atomically
            updated-timestamps (swap! rate-limit-store
                                      (fn [store]
                                        (let [current-timestamps (get store client-ip [])
                                              cleaned-timestamps (clean-old-timestamps current-timestamps)
                                              new-timestamps (conj cleaned-timestamps current-time)]
                                          (assoc store client-ip new-timestamps))))
            request-count (count (get updated-timestamps client-ip))]

        (if (> request-count rate-limit-requests-per-minute)
          (do
            (.warn logger "Rate limit exceeded for IP: {} ({} requests in the last minute)" client-ip request-count)
            (error-response "RATE_LIMIT_EXCEEDED"
                            (str "Rate limit exceeded. Maximum " rate-limit-requests-per-minute " requests per minute allowed.")
                            429))
          (handler request))))))

(defn wrap-request-size-limit [handler]
  (fn [request]
    (if-let [content-length (get-in request [:headers "content-length"])]
      (let [size (try
                   (Long/parseLong content-length)
                   (catch NumberFormatException _ 0))]
        (if (> size max-request-size)
          (error-response "REQUEST_TOO_LARGE"
                          (str "Request body too large. Maximum size is " max-request-size " bytes.")
                          413)
          (handler request)))
      (handler request))))

(defn wrap-logging [handler]
  (fn [request]
    (let [response (handler request)]
      (jrnl/logRequest request response)
      response)))

(defn wrap-error-handling [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (.error logger "An unexpected error occurred while processing a request." e)
        (let [error-data (ex-data e)
              status (or (:status error-data) 500)
              message (if (instance? clojure.lang.ExceptionInfo e)
                        (.getMessage e)
                        "An unexpected error occurred.")
              error-code (or (:error-code error-data) "INTERNAL_ERROR")]
          (error-response error-code message status))))))

(defn destroy []
  (.info logger "autho is shutting down"))

(defn json-response [data & [status]]
  {:status (or status 200)
   :headers {"Content-Type" "application/json"}
   :body (json/write-value-as-string data)})

(defn error-response
  "Creates a standardized error response with code, message, and timestamp."
  [error-code message status]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-value-as-string
          {:error {:code error-code
                   :message message
                   :timestamp (str (java.time.Instant/now))}})})

(defn- rules-not-loaded-response []
  (error-response "RULES_NOT_LOADED"
                  "Rule repository is not loaded. Please check server logs."
                  503))

;; Server start time for uptime calculation
(defonce server-start-time (atom nil))

(defroutes public-routes
           (route/resources "/")

           ;; Health and monitoring endpoints
           (GET "/health" []
                (json-response {:status "ok"
                                :service "autho"
                                :timestamp (str (java.time.Instant/now))}))

           (GET "/readiness" []
                (let [rules-status (prp/get-rules-repository-status)]
                  (if (= :failed rules-status)
                    (json-response {:status "not_ready"
                                    :reason "Rule repository not loaded"
                                    :timestamp (str (java.time.Instant/now))}
                                   503)
                    (json-response {:status "ready"
                                    :rulesRepository "loaded"
                                    :timestamp (str (java.time.Instant/now))}))))

           (GET "/status" []
                (let [uptime-ms (if @server-start-time
                                  (- (System/currentTimeMillis) @server-start-time)
                                  0)
                      uptime-seconds (quot uptime-ms 1000)
                      uptime-minutes (quot uptime-seconds 60)
                      uptime-hours (quot uptime-minutes 60)
                      rules-status (prp/get-rules-repository-status)]
                  (json-response {:service "autho"
                                  :version "0.1.0-SNAPSHOT"
                                  :status "running"
                                  :uptime {:milliseconds uptime-ms
                                           :seconds uptime-seconds
                                           :minutes uptime-minutes
                                           :hours uptime-hours
                                           :formatted (format "%dh %dm %ds"
                                                              uptime-hours
                                                              (mod uptime-minutes 60)
                                                              (mod uptime-seconds 60))}
                                  :rulesRepository (name rules-status)
                                  :rateLimit {:enabled rate-limit-enabled
                                              :requestsPerMinute rate-limit-requests-per-minute}
                                  :timestamp (str (java.time.Instant/now))})))

           (GET "/test" req
                (do (.debug logger "HEAD: {}" (:headers req))
                    (.debug logger "GET /test body: {}" (slurp (:body req)))
                    (json-response {"coucou" "lala"})))
           (GET "/init" []
                (pdp/init))
           (POST "/astro" {body :body}
                 (.debug logger "POST /astro body: {}" (slurp body))
                 (json-response {:signe "poisson" :ascendant "poisson"})))

(defroutes protected-routes
           (GET "/policies" []
                (json-response (prp/get-policies)))
           (GET "/pips/test" []
                (let [pips (prp/get-pips)
                      rest-pips (filter #(= :rest (get-in % [:pip :type])) pips)
                      test-results (map (fn [pip-decl]
                                          {:pip pip-decl
                                           :result (pip/callPip pip-decl nil {:id "dummy-id"})})
                                        rest-pips)]
                  (json-response test-results)))
           (GET "/policy/:resourceClass" [resourceClass]
                (json-response (prp/getPolicy resourceClass nil)))
           (GET "/whoAuthorized/:resourceClass" [resourceClass]
                (cond
                  (= :failed (prp/get-rules-repository-status))
                  (rules-not-loaded-response)

                  :else
                  (json-response (pdp/whoAuthorizedByClass resourceClass))))

           (POST "/isAuthorized" request
                 (cond
                   (= :failed (prp/get-rules-repository-status))
                   (rules-not-loaded-response)

                   (nil? (:body request))
                   (error-response "EMPTY_REQUEST_BODY"
                                   "Request body is empty."
                                   400)

                   :else
                   (let [body (json/read-value (slurp (:body request)) json/keyword-keys-object-mapper)]
                     (json-response (pdp/isAuthorized request body)))))

           (POST "/whoAuthorized" request
                 (cond
                   (= :failed (prp/get-rules-repository-status))
                   (rules-not-loaded-response)

                   (nil? (:body request))
                   (error-response "EMPTY_REQUEST_BODY"
                                   "Request body is empty."
                                   400)

                   :else
                   (let [body (json/read-value (slurp (:body request)) json/keyword-keys-object-mapper)]
                     (json-response (pdp/whoAuthorized request body)))))

           (POST "/whichAuthorized" request
                 (cond
                   (= :failed (prp/get-rules-repository-status))
                   (rules-not-loaded-response)

                   (nil? (:body request))
                   (error-response "EMPTY_REQUEST_BODY"
                                   "Request body is empty."
                                   400)

                   :else
                   (let [body (json/read-value (slurp (:body request)) json/keyword-keys-object-mapper)]
                     (json-response (pdp/whichAuthorized request body)))))

           (PUT "/policy/:resourceClass"
                {params :params body :body}
                (json-response (prp/submit-policy (:resourceClass params) (slurp body))))
           (DELETE "/policy/:resourceClass" [resourceClass]
                   (json-response (prp/delete-policy resourceClass)))
           (POST "/explain" request
                 (cond
                   (= :failed (prp/get-rules-repository-status))
                   (rules-not-loaded-response)

                   (nil? (:body request))
                   (error-response "EMPTY_REQUEST_BODY"
                                   "Request body is empty."
                                   400)

                   :else
                   (let [body (json/read-value (slurp (:body request)) json/keyword-keys-object-mapper)]
                     (json-response (pdp/explain request body)))))
           (context "/admin" []
                    (GET "/listRDB" []
                         (json-response (kpip/list-column-families)))
                    (DELETE "/clearRDB/:class-name" [class-name]
                            (kpip/clear-column-family class-name)
                            (json-response {:status "ok" :message (str "Column family " class-name " cleared.")}))
                    (POST "/reinit" []
                          (pdp/init)
                          (json-response {:status "ok" :message "PDP reinitialized."}))
                    (POST "/reload_rules" []
                          (prp/initf (pdp/get-rules-repository-path))
                          (json-response {:status "ok" :message "Rule repository reloaded."}))
                    (POST "/reload_persons" []
                          (try
                            (let [person-source (pdp/getProperty :person.source)
                                  person-type (if person-source (keyword person-source) :ldap)]
                              (person/loadPersons {:type person-type :props @pdp/properties})
                              (.info logger "Person cache reloaded successfully from source: {}" person-type)
                              (json-response {:status "ok"
                                              :message "Person cache reloaded successfully."
                                              :source (name person-type)
                                              :personCount (count @prp/personSingleton)}))
                            (catch Exception e
                              (.error logger "Failed to reload person cache" e)
                              (error-response "PERSON_RELOAD_FAILED"
                                              (str "Failed to reload person cache: " (.getMessage e))
                                              500))))))

(def app-routes
  (routes
   public-routes
   (-> protected-routes
       (auth/wrap-authentication))
   (route/not-found "Not Found")))


(defn init []
  (.info logger "autho is starting")
  (reset! server-start-time (System/currentTimeMillis))
  (.info logger "Rate limiting: {} (max {} requests/minute per IP)"
         (if rate-limit-enabled "enabled" "disabled")
         rate-limit-requests-per-minute)
  (->
   (create-donkey)
   (create-server {:port   8080
                   :routes  [{:handler (-> app-routes
                                           wrap-logging
                                           wrap-rate-limit
                                           wrap-request-size-limit
                                           wrap-error-handling)
                              :handler-mode :blocking}]})
   start
   (on-success (fn [_] (.info logger "Server started listening on port 8080")))))
