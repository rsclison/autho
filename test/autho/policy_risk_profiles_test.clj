(ns autho.policy-risk-profiles-test
  (:require [clojure.test :refer :all]
            [autho.policy-risk-profiles :as risk-profiles]
            [clojure.java.jdbc :as jdbc]))

(def ^:private test-db
  {:classname "org.h2.Driver"
   :subprotocol "h2:mem"
   :subname "testriskprofiles;DB_CLOSE_DELAY=-1"
   :user "sa"
   :password ""})

(defn- with-clean-db
  [f]
  (with-redefs-fn {#'autho.policy-risk-profiles/db test-db}
    (fn []
      (try (jdbc/execute! test-db ["DROP TABLE IF EXISTS POLICY_RISK_PROFILES"])
           (catch Exception _))
      (risk-profiles/init!)
      (f))))

(use-fixtures :each with-clean-db)

(deftest upsert-list-and-delete-risk-profiles-test
  (risk-profiles/upsert-profile! "default" "*" {:maxRevokes 0} "tester")
  (risk-profiles/upsert-profile! "environment" "prod" {:maxRevokes 1} "tester")
  (risk-profiles/upsert-profile! "resource_class" "Document" {:allowSensitiveResourceChanges true} "tester")
  (let [profiles (risk-profiles/list-profiles)
        records (risk-profiles/list-profile-records)]
    (is (= 3 (count records)))
    (is (= 0 (get-in profiles [:default :maxRevokes])))
    (is (= 1 (get-in profiles [:environments "prod" :maxRevokes])))
    (is (= true (get-in profiles [:resourceClasses "Document" :allowSensitiveResourceChanges]))))
  (is (true? (risk-profiles/delete-profile! "environment" "prod")))
  (is (nil? (get-in (risk-profiles/list-profiles) [:environments "prod"]))))

(deftest upsert-replaces-existing-risk-profile-test
  (risk-profiles/upsert-profile! "environment" "prod" {:maxRevokes 1} "tester")
  (risk-profiles/upsert-profile! "environment" "prod" {:maxRevokes 2} "tester")
  (is (= 2 (get-in (risk-profiles/list-profiles) [:environments "prod" :maxRevokes]))))

(deftest invalid-risk-profile-scope-is-rejected-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Invalid risk profile scope type"
       (risk-profiles/upsert-profile! "tenant" "acme" {:maxRevokes 1} "tester"))))
