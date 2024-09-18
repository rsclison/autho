(ns autho.person
  (:require [hyauth.utils :as utl]
            [hyauth.prp :as prp]))


(defmulti loadPersons (fn [personSingleton] (:type personSingleton)))
(defmethod loadPersons :file
  (swap! prp/personSingleton (utl/load-edn (:path prp/personSingleton)))
  )
(defmethod loadPersons :grhum)
(defmethod loadPersons :ldap)

