(ns autho.javainterop
  (:import
           (org.cocktail Pip))
  (:gen-class
    :name hyauth.javainterop
    :methods [#^{:static true} [hello [org.cocktail.Pip] String]
              #^{:static true} [isAuthorized [String org.cocktail.Pip] String]
              #^{:static true} [init [org.cocktail.Pip] void]
              ]
    )
  (:require [hyauth.prp :as prp]
            [hyauth.pdp :as pdp]
            [hyauth.journal :as jrnl]
            [clojure.data.json :as json]
            )
  )


(defn hello [nn]
  (println "Hello Ray")
  (println nn)
  (let [res (.resolveAttribute nn "My" "God")]
    (println "IN CLOJ " res)
    (str "RESULT from CLOJ " res)
  ))

(defn -hello [nn]
  (println "hello-")
  (hello nn))

(defn -isAuthorized [req pip]
  (println "isAuthorized")
  (if (:result(pdp/evalRequest (json/read-str req :key-fn keyword)))
    "allow"
    "deny")
  )

(defn -init [pip]
  (let [attributes (.getAttributes pip)]
    (pdp/init)
    (prp/init)
    (prp/addOrReplaceJavaPip attributes pip)
    (jrnl/logClient pip)
  ))