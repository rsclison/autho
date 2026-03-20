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
  (let [props     (:props config)
        base-dn   (:ldap.basedn props)
        filter    (:ldap.filter props)
        attrs-str (:ldap.attributes props)
        attributes (when attrs-str (clojure.string/split attrs-str #",\s*"))]
    (let [persons    (ldap/search base-dn {:filter filter :attributes attributes})
          ;; clj-ldap returns {:dn "...", :uid ["001"], :cn ["Paul"], ...}
          ;; We flatten single-value lists and use :uid as :id
          person-map (map (fn [p]
                            (-> (dissoc p :dn)
                                (update-keys keyword)
                                (update-vals #(if (sequential? %) (first %) %))
                                (as-> m (if (:uid m) (assoc m :id (:uid m)) m))))
                          persons)]
      (reset! prp/personSingleton (vec person-map)))))

