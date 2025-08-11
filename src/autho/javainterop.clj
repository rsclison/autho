(ns autho.javainterop
  (:import
           (org.cocktail Pip))
  (:gen-class
    :name autho.javainterop
    :methods [#^{:static true} [isAuthorized [String] String]
              #^{:static true} [getPolicies [] String]
              #^{:static true} [getPolicy [String] String]
              #^{:static true} [updatePolicy [String String] void]
              #^{:static true} [deletePolicy [String] void]
              #^{:static true} [init [org.cocktail.Pip] void]
              ]
    )
  (:require [autho.prp :as prp]
            [autho.pdp :as pdp]
            [autho.journal :as jrnl]
            [clojure.data.json :as json]
            )
  )

(defn -isAuthorized [req]
  (println "isAuthorized")
  (if (:result(pdp/evalRequest (json/read-str req :key-fn keyword)))
    "allow"
    "deny")
  )

(defn -getPolicies []
  (json/write-str (prp/get-policies)))

(defn -getPolicy [resource-class]
  (json/write-str (prp/getPolicy resource-class nil)))

(defn -updatePolicy [resource-class policy]
  (prp/submit-policy resource-class policy))

(defn -deletePolicy [resource-class]
  (prp/delete-policy resource-class))

(defn -init [pip]
  (let [attributes (.getAttributes pip)]
    (pdp/init)
    (prp/init)
    (prp/addOrReplaceJavaPip attributes pip)
    (jrnl/logClient pip)
  ))