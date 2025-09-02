(ns autho.handler
  (:require [autho.pdp :as pdp]
            [autho.prp :as prp]
            [autho.pip :as pip]
            [autho.journal :as jrnl]
            [compojure.core :refer :all]
            [com.appsflyer.donkey.core :refer [create-donkey create-server]]
            [com.appsflyer.donkey.server :refer [start]]
            [com.appsflyer.donkey.result :refer [on-success]]
            [compojure.route :as route]
            [jsonista.core :as json])
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

(defroutes app-routes
           (route/resources "/")
           (GET "/test" req
             (do (println "HEAD" (:headers req))(println "GET /test " (slurp(:body req)))(json-response {"coucou" "lala"})))
           (GET "/init" []
             (pdp/init)
             )
           (GET "/policies" []
             (json-response (prp/get-policies))
             )
           (GET "/policy/:resourceClass" [resourceClass]
             (json-response (prp/getPolicy resourceClass nil)))

           (GET "/whoAuthorized/:resourceClass" [resourceClass]
             (json-response (pdp/whoAuthorized {:resource {:class resourceClass}}))
             )

           (POST "/isAuthorized" {body :body}
             (cond
               (= :failed (prp/get-rules-repository-status))
               (rules-not-loaded-response)

               (nil? body)
               (json-response
                 {:error {:message "Request body is empty."
                          :example {:subject {:id "user1"}
                                    :resource {:class "doc" :id "doc1"}
                                    :operation "read"}}}
                 400)

               :else
               (json-response (pdp/isAuthorized (json/read-value (slurp body) json/keyword-keys-object-mapper)))))

           (POST "/whoAuthorized" {body :body}
             (cond
               (= :failed (prp/get-rules-repository-status))
               (rules-not-loaded-response)

               (nil? body)
               (json-response
                 {:error {:message "Request body is empty."
                          :example {:resource {:class "doc"}
                                    :operation "read"}}}
                 400)

               :else
               (let [input-json (json/read-value (slurp body) json/keyword-keys-object-mapper)
                     result (pdp/whoAuthorized input-json)]
                 (json-response result))))

           (POST "/whichAuthorized" {body :body}
             (cond
               (= :failed (prp/get-rules-repository-status))
               (rules-not-loaded-response)

               (nil? body)
               (json-response
                 {:error {:message "Request body is empty."
                          :example {:subject {:id "user1"}
                                    :operation "read"}}}
                 400)

               :else
               (json-response (pdp/whichAuthorized (json/read-value (slurp body) json/keyword-keys-object-mapper)))))


           (PUT "/policy/:resourceClass"
             {params :params body :body}
             (json-response (prp/submit-policy (:resourceClass params) (slurp body)))
                                                         )
           (DELETE "/policy/:resourceClass" [resourceClass]
             (json-response (prp/delete-policy resourceClass))
                   )

           (POST "/astro" {body :body}
             (println (slurp body))
             (json-response {:signe "poisson" :ascendant "poisson"})
             )

           (GET "/explain" {body :body}
             (println "TODO") ;; //TODO
             )
           (context "/admin" []
             (POST "/reinit" []
               (pdp/init)
               (json-response {:status "ok" :message "PDP reinitialized."}))
             (POST "/reload_rules" []
               (prp/initf (pdp/get-rules-repository-path))
               (json-response {:status "ok" :message "Rule repository reloaded."}))
             (GET "/testpips" []
               (let [pips (prp/get-pips)
                     results (map (fn [pip-config]
                                    (let [pip-class (:class pip-config "unknown")]
                                      (try
                                        ;; We don't care about the result, just that it doesn't throw.
                                        (pip/callPip {:pip pip-config} :test {:id "test-id"})
                                        {:class pip-class :status "ok"}
                                        (catch Exception e
                                          {:class pip-class :status "error" :message (.getMessage e)}))))
                                  pips)]
                 (json-response results))))
           (route/not-found "Not Found"))


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
