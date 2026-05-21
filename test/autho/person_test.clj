(ns autho.person-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [autho.person :as person]
            [autho.prp :as prp]
            [autho.ldap :as ldap]))

(deftest loadPersons-file-test
  (testing "loadPersons with :file type"
    (let [tmp-file (java.io.File/createTempFile "autho-persons" ".edn")]
      (try
        (spit tmp-file "[{:id \"001\" :name \"Paul\" :role \"chef_de_service\"}]")
        (person/loadPersons {:type :file
                             :props {:person.file (.getPath tmp-file)}})
        (is (= [{:id "001" :name "Paul" :role "chef_de_service"}]
               @prp/personSingleton))
        (finally
          (io/delete-file tmp-file true))))))

(deftest loadPersons-ldap-test
  (testing "loadPersons with :ldap type"
    (let [ldap-props {:ldap.basedn "dc=example,dc=com"
                      :ldap.filter "(objectClass=person)"
                      :ldap.attributes "cn,sn,mail"}
          dummy-persons [{:dn "cn=user1,dc=example,dc=com" :cn ["User1"] :sn ["Test"] :mail ["user1@example.com"]}
                         {:dn "cn=user2,dc=example,dc=com" :cn ["User2"] :sn ["Test"] :mail ["user2@example.com"]}]]
      (with-redefs [ldap/search (fn [base-dn opts] dummy-persons)]
        (person/loadPersons {:type :ldap :props ldap-props})
        (let [loaded-persons @prp/personSingleton]
          (is (= 2 (count loaded-persons)))
          (is (= {:cn "User1" :sn "Test" :mail "user1@example.com"} (first loaded-persons))))))))
