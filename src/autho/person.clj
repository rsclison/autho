(ns autho.person
  (:require [autho.utils :as utl]
            [autho.prp :as prp]
            [autho.ldap :as ldap]))


(defmulti loadPersons (fn [config] (:type config)))

(defmethod loadPersons :file [config]
  ;; TODO: Implement file-based person loading
  ;; Expected to read persons from a file specified in config
  []
  )

(defmethod loadPersons :ldap [config]
  (let [props (:props config)
        base-dn (:ldap.basedn props)
        filter (:ldap.filter props)
        attributes (clojure.string/split (:ldap.attributes props) #",")]
    (let [persons (ldap/search base-dn {:filter filter :attributes attributes})
          person-map (map (fn [p] (into {} (for [[k v] (:attributes p)] [(keyword k) (first v)]))) persons)]
      (reset! prp/personSingleton person-map))))

