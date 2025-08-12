(ns autho.handler
  (:require [autho.pdp :as pdp])
  (:require [autho.prp :as prp])
  (:require [compojure.core :refer :all]
            [com.appsflyer.donkey.core :refer [create-donkey create-server]]
            [com.appsflyer.donkey.server :refer [start]]
            [com.appsflyer.donkey.result :refer [on-success]]
            [compojure.route :as route]
            [clj-json.core :as json-core]
            [clojure.data.json :as json]
            [com.brunobonacci.mulog :as u]
            ))


(defn destroy []
  (println "autho is shutting down"))

(defn json-response [data & [status]]
  {:status (or status (:code data))
   :headers {"Content-Type" "application/json"}
   :body (json-core/generate-string data)})

(defmacro format-response [resp]
  `(try (let [res# ~resp]
          {:status 200 :headers {"Content-Type" "application/json"} :body res#}
          )
          (catch Exception e# (println (.getMessage e#)) {:status 500 :body (.getMessage e#)}))
  )

(defroutes app-routes
           (route/resources "/")
           (GET "/test" req
             (do (println "HEAD" (:headers req))(println "GET /test " (slurp(:body req)))(json-response {"coucou" "lala"})))
           (GET "/init" []
             (pdp/init)
             )
           (GET "/policies" []
             (format-response (json/write-str (prp/get-policies)))
             )
           (GET "/policy/:resourceClass" [resourceClass]
             (format-response (json/write-str (prp/getPolicy resourceClass nil))))

           (GET "/whoAuthorized/:resourceClass" [resourceClass]
             (do (format-response (json/write-str (pdp/whoAuthorized {:resource {:class resourceClass}}))))
             )

           (POST "/isAuthorized" {body :body}
               (format-response (json/write-str (pdp/isAuthorized (json/read-str (slurp body) :key-fn keyword))))
               )

           (POST "/whoAuthorized" {body :body}
             (let [input-json (json/read-str (slurp body) :key-fn keyword)
                   result (pdp/whoAuthorized input-json)
                   ]
               (format-response (json/write-str result))
               ))

           (POST "/whichAuthorized" {body :body}
               (format-response (json/write-str (pdp/whichAuthorized (json/read-str (slurp body) :key-fn keyword))))
               )


           (PUT "/policy/:resourceClass"
             {params :params body :body}
             (format-response (json/write-str (prp/submit-policy (:resourceClass params) (slurp body))))
                                                         )
           (DELETE "/policy/:resourceClass" [resourceClass]
             (format-response (json/write-str (prp/delete-policy resourceClass)))
                   )

           (POST "/astro" {body :body}
             (println (slurp body))
             (format-response (json/write-str {:signe "poisson" :ascendant "poisson"}))
             )

           (GET "/explain" {body :body}
             (println "TODO") ;; //TODO
             )
           (route/not-found "Not Found"))


(defn init []
  (u/log "autho is starting")
  (->
   (create-donkey)
   (create-server {:port   8080
                   :routes  [{:handler app-routes
                              :handler-mode :blocking}]})
   start
   (on-success (fn [_] (println "Server started listening on port 8080")))))

