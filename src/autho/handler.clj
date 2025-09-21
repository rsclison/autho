(ns autho.handler
  (:require [autho.pdp :as pdp]
            [autho.prp :as prp]
            [autho.pip :as pip]
            [autho.kafka-pip :as kpip]
            [autho.journal :as jrnl]
            [compojure.core :refer :all]
            [com.appsflyer.donkey.core :refer [create-donkey create-server]]
            [com.appsflyer.donkey.server :refer [start]]
            [com.appsflyer.donkey.result :refer [on-success]]
            [compojure.route :as route]
            [jsonista.core :as json]
            [autho.auth :as auth])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.handler"))

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
                        "An unexpected error occurred.")]
          {:status status
           :headers {"Content-Type" "application/json"}
           :body (json/write-value-as-string {:error {:message message}})})))))

(defn destroy []
  (println "autho is shutting down"))

(defn json-response [data & [status]]
  {:status (or status 200)
   :headers {"Content-Type" "application/json"}
   :body (json/write-value-as-string data)})

(defn- rules-not-loaded-response []
  (json-response
    {:error {:message "Rule repository is not loaded. Please check server logs."}}
    503))

(defroutes public-routes
           (route/resources "/")
           (GET "/test" req
                (do (println "HEAD" (:headers req))
                    (println "GET /test " (slurp (:body req)))
                    (json-response {"coucou" "lala"})))
           (GET "/init" []
                (pdp/init))
           (POST "/astro" {body :body}
                 (println (slurp body))
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
                (json-response {:error "Not Implemented"} 501))

           (POST "/isAuthorized" request
                 (cond
                   (= :failed (prp/get-rules-repository-status))
                   (rules-not-loaded-response)

                   (nil? (:body request))
                   (json-response
                    {:error {:message "Request body is empty."}} 400)

                   :else
                   (let [body (json/read-value (slurp (:body request)) json/keyword-keys-object-mapper)]
                     (json-response (pdp/isAuthorized request body)))))

           (POST "/whoAuthorized" request
                 (cond
                   (= :failed (prp/get-rules-repository-status))
                   (rules-not-loaded-response)

                   (nil? (:body request))
                   (json-response
                    {:error {:message "Request body is empty."}} 400)

                   :else
                   (let [body (json/read-value (slurp (:body request)) json/keyword-keys-object-mapper)]
                     (json-response (pdp/whoAuthorized request body)))))

           (POST "/whichAuthorized" request
                 (cond
                   (= :failed (prp/get-rules-repository-status))
                   (rules-not-loaded-response)

                   (nil? (:body request))
                   (json-response
                    {:error {:message "Request body is empty."}} 400)

                   :else
                   (let [body (json/read-value (slurp (:body request)) json/keyword-keys-object-mapper)]
                     (json-response (pdp/whichAuthorized request body)))))

           (PUT "/policy/:resourceClass"
                {params :params body :body}
                (json-response (prp/submit-policy (:resourceClass params) (slurp body))))
           (DELETE "/policy/:resourceClass" [resourceClass]
                   (json-response (prp/delete-policy resourceClass)))
           (GET "/explain" {body :body}
                (println "TODO") ;; //TODO
                )
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
                          (json-response {:status "ok" :message "Rule repository reloaded."}))))

(def app-routes
  (routes
   public-routes
   (-> protected-routes
       (auth/wrap-authentication))
   (route/not-found "Not Found")))


(defn init []
  (.info logger "autho is starting")
  (->
   (create-donkey)
   (create-server {:port   8080
                   :routes  [{:handler (-> app-routes
                                           wrap-logging
                                           wrap-error-handling)
                              :handler-mode :blocking}]})
   start
   (on-success (fn [_] (println "Server started listening on port 8080")))))
