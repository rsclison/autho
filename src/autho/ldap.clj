(ns autho.ldap
  (:require [clj-ldap.client :as ldap]
            )
    )



(def ldap-server (atom {}))

(defn init [props]
  (swap! ldap-server (fn [ld]
                       (ldap/connect {:host (:ldap.server props)
                                     :bind-dn ()}
                                     ))))



(defn groupMember [uid groupname]
  (let [group (ldap/get @ldap-server groupname)]
    )
  )


(defn findUser [id]
  (ldap/get ldap-server (str "uid=" id ",ou=users,ou=system"))
  )