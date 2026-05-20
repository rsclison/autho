(ns autho.policy-tests-test
  (:require [clojure.test :refer :all]
            [autho.policy-tests :as policy-tests]))

(def policy-with-tests
  {:strategy :almost_one_allow_no_deny
   :rules [{:name "allow-admin-read"
            :priority 10
            :effect "allow"
            :resourceClass "Document"
            :operation "read"
            :conditions [["=" ["Person" "$s" "role"] "admin"]]}
           {:name "deny-archived"
            :priority 20
            :effect "deny"
            :resourceClass "Document"
            :operation "read"
            :conditions [["=" ["Document" "$r" "status"] "archived"]]}]
   :tests [{:name "admin can read active document"
            :subject {:id "alice" :class "Person" :role "admin"}
            :resource {:id "doc-1" :class "Document" :status "active"}
            :operation "read"
            :expect "allow"}
           {:name "archived document is denied"
            :subject {:id "alice" :class "Person" :role "admin"}
            :resource {:id "doc-2" :class "Document" :status "archived"}
            :operation "read"
            :expect "deny"}]})

(deftest run-policy-tests-passes-declarative-scenarios-test
  (let [result (policy-tests/run-policy-tests policy-with-tests)]
    (is (= 2 (:count result)))
    (is (= 2 (:passed result)))
    (is (= 0 (:failed result)))
    (is (= [] (:errors result)))))

(deftest run-policy-tests-accepts-string-strategy-test
  (let [result (policy-tests/run-policy-tests
                (assoc policy-with-tests :strategy "almost_one_allow_no_deny"))]
    (is (= 2 (:passed result)))
    (is (= 0 (:failed result)))))

(deftest validate-policy-tests-rejects-failing-scenario-test
  (let [policy (assoc policy-with-tests
                      :tests [{:name "wrong expectation"
                               :subject {:id "alice" :class "Person" :role "admin"}
                               :resource {:id "doc-1" :class "Document" :status "active"}
                               :operation "read"
                               :expect "deny"}])]
    (try
      (policy-tests/validate-policy-tests! policy)
      (is false "Expected policy test failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= 400 (:status (ex-data e))))
        (is (= "POLICY_TESTS_FAILED" (:error-code (ex-data e))))
        (is (= "POLICY_TEST_FAILED" (get-in (ex-data e) [:issues 0 :code])))
        (is (= "allow" (get-in (ex-data e) [:issues 0 :actual])))))))

(deftest validate-policy-tests-rejects-invalid-scenario-shape-test
  (let [policy (assoc policy-with-tests :tests [{:name "missing expectation"
                                                 :subject {:id "alice"}
                                                 :resource {:id "doc-1" :class "Document"}
                                                 :operation "read"}])]
    (try
      (policy-tests/validate-policy-tests! policy)
      (is false "Expected invalid policy test failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= "INVALID_POLICY_TEST" (get-in (ex-data e) [:issues 0 :code])))))))

(deftest validate-policy-tests-wraps-evaluation-errors-test
  (let [policy (-> policy-with-tests
                   (assoc-in [:rules 0 :conditions] [["=" ["Person" "$s"] "admin"]])
                   (assoc :tests [{:name "malformed condition"
                                   :subject {:id "alice" :class "Person" :role "admin"}
                                   :resource {:id "doc-1" :class "Document"}
                                   :operation "read"
                                   :expect "allow"}]))]
    (try
      (policy-tests/validate-policy-tests! policy)
      (is false "Expected evaluation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= "POLICY_TEST_EVALUATION_ERROR"
               (get-in (ex-data e) [:issues 0 :code])))))))
