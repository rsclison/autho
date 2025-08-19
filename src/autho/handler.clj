(ns autho.handler
  (:require [autho.pdp :as pdp]
            [autho.prp :as prp]
            [autho.journal :as jrnl]
            [compojure.core :refer :all]
            [com.appsflyer.donkey.core :refer [create-donkey create-server]]
            [com.appsflyer.donkey.server :refer [start]]
            [com.appsflyer.donkey.result :refer [on-success]]
            [compojure.route :as route]
            [clj-json.core :as json-core]
            [clojure.data.json :as json])
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
           :body (json-core/generate-string {:error {:message message}})})))))

(defn destroy []
  (println "autho is shutting down"))

(defn json-response [data & [status]]
  {:status (or status 200)
   :headers {"Content-Type" "application/json"}
   :body (json-core/generate-string data)})

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
               (json-response (pdp/isAuthorized (json/read-str (slurp body) :key-fn keyword)))
               )

           (POST "/whoAuthorized" {body :body}
             (let [input-json (json/read-str (slurp body) :key-fn keyword)
                   result (pdp/whoAuthorized input-json)
                   ]
               (json-response result)
               ))

           (POST "/whichAuthorized" {body :body}
               (json-response (pdp/whichAuthorized (json/read-str (slurp body) :key-fn keyword)))
               )


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
