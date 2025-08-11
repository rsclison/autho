(ns autho.ldap
  (:require [clj-ldap.client :as ldap]
            )
    )



(def ldap-server (atom nil))

(defn init [props]
  (let [conn (ldap/connect {:host (:ldap.server props)
                            :port (:ldap.port props)
                            :bind-dn (:ldap.connectstring props)
                            :password (:ldap.password props)})]
    (reset! ldap-server conn)))

(defn search [base-dn opts]
  (when @ldap-server
    (ldap/search @ldap-server base-dn opts)))

(defn groupMember [uid groupname]
  (let [group (ldap/get @ldap-server groupname)]
    )
  )


(defn findUser [id]
  (ldap/get @ldap-server (str "uid=" id ",ou=users,ou=system"))
  )