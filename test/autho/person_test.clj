(ns autho.person-test
  (:require [clojure.test :refer :all]
            [autho.person :as person]
            [autho.prp :as prp]
            [autho.ldap :as ldap]))

(deftest loadPersons-ldap-test
  (testing "loadPersons with :ldap type"
    (let [ldap-props {:ldap.basedn "dc=example,dc=com"
                      :ldap.filter "(objectClass=person)"
                      :ldap.attributes "cn,sn,mail"}
          dummy-persons [{:dn "cn=user1,dc=example,dc=com" :attributes {:cn ["User1"] :sn ["Test"] :mail ["user1@example.com"]}}
                         {:dn "cn=user2,dc=example,dc=com" :attributes {:cn ["User2"] :sn ["Test"] :mail ["user2@example.com"]}}]]
      (with-redefs [ldap/search (fn [base-dn opts] dummy-persons)]
        (person/loadPersons {:type :ldap :props ldap-props})
        (let [loaded-persons @prp/personSingleton]
          (is (= 2 (count loaded-persons)))
          (is (= {:cn "User1" :sn "Test" :mail "user1@example.com"} (first loaded-persons))))))))
