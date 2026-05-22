(ns autho.policy-safety-test
  (:require [clojure.test :refer :all]
            [jsonista.core :as json]
            [autho.policy-safety :as safety]
            [autho.policy-tests :as policy-tests]
            [autho.prp :as prp]
            [autho.policy-versions :as pv]
            [autho.local-cache :as local-cache]))

(def valid-policy
  {:strategy "almost_one_allow_no_deny"
   :rules [{:name "allow-admin-read"
            :priority 10
            :effect "allow"
            :resourceClass "Document"
            :operation "read"
            :conditions [["=" ["Person" "$s" "role"] "admin"]]}]})

(deftest validate-policy-accepts-well-formed-policy-test
  (let [analysis (safety/validate-policy! "Document" valid-policy)]
    (is (= [] (:errors analysis)))
    (is (= [] (:warnings analysis)))))

(deftest validate-policy-accepts-context-references-test
  (let [policy {:strategy "almost_one_allow_no_deny"
                :schema {:subjects {:Application ["client-id"]}
                         :resources {:Facture ["id"]}
                         :contexts {:Context ["purpose" "requestingUser"]}
                         :operations ["process"]}
                :rules [{:name "allow-app-purpose"
                         :priority 10
                         :effect "allow"
                         :resourceClass "Facture"
                         :operation "process"
                         :conditions [["=" ["Application" "$s" "client-id"] "app-demo"]
                                      ["=" ["Context" "$c" "purpose"] "aggregate_invoice_total"]]}]}]
    (let [analysis (safety/validate-policy! "Facture" policy)]
      (is (= [] (:errors analysis)))
      (is (= [] (:warnings analysis))))))

(deftest validate-policy-detects-duplicate-rule-names-test
  (let [policy (assoc valid-policy
                      :rules [(first (:rules valid-policy))
                              {:name "allow-admin-read"
                               :priority 5
                               :effect "deny"
                               :resourceClass "Document"
                               :operation "read"
                               :conditions [["diff" ["Person" "$s" "status"] "suspended"]]}])]
    (try
      (safety/validate-policy! "Document" policy)
      (is false "Expected duplicate rule name validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= 400 (:status (ex-data e))))
        (is (= "INVALID_POLICY_SAFETY" (:error-code (ex-data e))))
        (is (= "DUPLICATE_RULE_NAME" (get-in (ex-data e) [:issues 0 :code])))))))

(deftest validate-policy-detects-resource-class-mismatch-test
  (let [policy (assoc valid-policy
                      :rules [{:name "allow-admin-read"
                               :priority 10
                               :effect "allow"
                               :resourceClass "Invoice"
                               :operation "read"
                               :conditions [["=" ["Person" "$s" "role"] "admin"]]}])]
    (try
      (safety/validate-policy! "Document" policy)
      (is false "Expected resourceClass mismatch validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= "RESOURCE_CLASS_MISMATCH" (get-in (ex-data e) [:issues 0 :code])))))))

(deftest validate-policy-rejects-missing-strategy-test
  (let [policy (dissoc valid-policy :strategy)]
    (try
      (safety/validate-policy! "Document" policy)
      (is false "Expected missing strategy validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= "MISSING_STRATEGY" (get-in (ex-data e) [:issues 0 :code])))))))

(deftest validate-policy-rejects-unsupported-strategy-test
  (let [policy (assoc valid-policy :strategy "permit-unless-deny")]
    (try
      (safety/validate-policy! "Document" policy)
      (is false "Expected unsupported strategy validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= "UNSUPPORTED_STRATEGY" (get-in (ex-data e) [:issues 0 :code])))
        (is (= ["almost_one_allow_no_deny"]
               (get-in (ex-data e) [:issues 0 :supported-strategies])))))))

(deftest validate-policy-accepts-schema-declared-references-test
  (let [policy (assoc valid-policy
                      :schema {:subjects {:Person ["role" "status"]}
                               :resources {:Document ["classification"]}
                               :operations ["read"]})]
    (let [analysis (safety/validate-policy! "Document" policy)]
      (is (= [] (:errors analysis))))))

(deftest validate-policy-detects-unknown-operation-from-schema-test
  (let [policy (assoc valid-policy
                      :schema {:subjects {:Person ["role"]}
                               :resources {:Document ["classification"]}
                               :operations ["write"]})]
    (try
      (safety/validate-policy! "Document" policy)
      (is false "Expected unknown operation validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= "UNKNOWN_OPERATION" (get-in (ex-data e) [:issues 0 :code])))))))

(deftest validate-policy-detects-unknown-attribute-from-schema-test
  (let [policy (assoc valid-policy
                      :schema {:subjects {:Person ["department"]}
                               :resources {:Document ["classification"]}
                               :operations ["read"]})]
    (try
      (safety/validate-policy! "Document" policy)
      (is false "Expected unknown attribute validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= "UNKNOWN_SUBJECT_ATTRIBUTE" (get-in (ex-data e) [:issues 0 :code])))
        (is (= "role" (get-in (ex-data e) [:issues 0 :attribute])))))))

(deftest validate-policy-detects-unknown-class-from-schema-test
  (let [policy (assoc valid-policy
                      :schema {:subjects {:Account ["role"]}
                               :resources {:Document ["classification"]}
                               :operations ["read"]})]
    (try
      (safety/validate-policy! "Document" policy)
      (is false "Expected unknown class validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= "UNKNOWN_SUBJECT_CLASS" (get-in (ex-data e) [:issues 0 :code])))
        (is (= "Person" (get-in (ex-data e) [:issues 0 :class])))))))

(deftest validate-policy-detects-contradictory-equality-test
  (let [policy {:strategy "almost_one_allow_no_deny"
                :rules [{:name "contradictory"
                         :priority 1
                         :effect "allow"
                         :resourceClass "Document"
                         :operation "read"
                         :conditions [["=" ["Person" "$s" "role"] "admin"]
                                      ["diff" ["Person" "$s" "role"] "admin"]]}]}]
    (try
      (safety/validate-policy! "Document" policy)
      (is false "Expected contradictory equality validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= "CONTRADICTORY_EQUALITY" (get-in (ex-data e) [:issues 0 :code])))))))

(deftest submit-policy-applies-policy-safety-test
  (let [invalid-policy {:strategy "almost_one_allow_no_deny"
                        :rules [{:name "duplicate"
                                 :priority 10
                                 :effect "allow"
                                 :resourceClass "Document"
                                 :operation "read"
                                 :conditions [["=" ["Person" "$s" "role"] "admin"]]}
                                {:name "duplicate"
                                 :priority 5
                                 :effect "deny"
                                 :resourceClass "Document"
                                 :operation "read"
                                 :conditions [["diff" ["Person" "$s" "status"] "suspended"]]}]}
        payload (json/write-value-as-string invalid-policy)]
    (with-redefs [pv/save-version! (fn [& _] nil)
                  local-cache/invalidate-decisions-for-class! (fn [& _] nil)
                  policy-tests/validate-policy-tests! (fn [_] {:count 0 :errors []})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Policy safety validation failed"
           (prp/submit-policy "Document" payload))))))

(deftest submit-policy-applies-declarative-policy-tests-test
  (let [policy {:strategy "almost_one_allow_no_deny"
                :rules [{:name "allow-admin-read"
                         :priority 10
                         :effect "allow"
                         :resourceClass "Document"
                         :operation "read"
                         :conditions [["=" ["Person" "$s" "role"] "admin"]]}]
                :tests [{:name "wrong expectation"
                         :subject {:id "alice" :class "Person" :role "admin"}
                         :resource {:id "doc-1" :class "Document"}
                         :operation "read"
                         :expect "deny"}]}
        payload (json/write-value-as-string policy)]
    (with-redefs [pv/save-version! (fn [& _] nil)
                  local-cache/invalidate-decisions-for-class! (fn [& _] nil)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Policy tests failed"
           (prp/submit-policy "Document" payload))))))

(deftest validate-policy-submission-runs-all-checks-without-persistence-test
  (let [policy {:resourceClass "Document"
                :strategy "almost_one_allow_no_deny"
                :rules [{:name "allow-admin-read"
                         :priority 10
                         :effect "allow"
                         :resourceClass "Document"
                         :operation "read"
                         :conditions [["=" ["Person" "$s" "role"] "admin"]]}]
                :tests [{:name "admin can read"
                         :subject {:id "alice" :class "Person" :role "admin"}
                         :resource {:id "doc-1" :class "Document"}
                         :operation "read"
                         :expect "allow"}]}
        payload (json/write-value-as-string policy)]
    (with-redefs [prp/insert-policy (fn [& _]
                                      (throw (ex-info "should not persist" {})))
                  pv/save-version! (fn [& _]
                                     (throw (ex-info "should not version" {})))
                  local-cache/invalidate-decisions-for-class! (fn [& _]
                                                                (throw (ex-info "should not invalidate" {})))]
      (let [analysis (prp/validate-policy-submission "Document" payload)]
        (is (= true (:valid analysis)))
        (is (= "passed" (get-in analysis [:report :status])))
        (is (= [] (:errors analysis)))
        (is (= 1 (get-in analysis [:tests :count])))
        (is (= 1 (get-in analysis [:tests :passed])))
        (is (= 1 (get-in analysis [:report :summary :policyTests :passed])))))))

(deftest validate-policy-submission-reports-warnings-test
  (let [policy {:resourceClass "Document"
                :strategy "almost_one_allow_no_deny"
                :rules [{:name "broad-allow"
                         :priority 10
                         :effect "allow"
                         :resourceClass "Document"
                         :conditions []}]}
        analysis (prp/validate-policy-submission "Document"
                                                 (json/write-value-as-string policy))]
    (is (= "passed_with_warnings" (get-in analysis [:report :status])))
    (is (= 2 (get-in analysis [:report :summary :warnings])))
    (is (= "warning"
           (some #(when (= "policy-safety" (:name %)) (:status %))
                 (get-in analysis [:report :gates]))))))

(deftest submit-policy-stores-isolated-policy-environments-test
  (let [base-policy {:resourceClass "Document"
                     :strategy "almost_one_allow_no_deny"
                     :rules [{:name "allow-read"
                              :priority 10
                              :effect "allow"
                              :resourceClass "Document"
                              :operation "read"
                              :conditions []}]}
        dev-policy (assoc base-policy
                          :environment "dev"
                          :rules [(assoc (first (:rules base-policy))
                                         :name "dev-allow-read")])
        prod-policy (assoc base-policy :environment "prod")]
    (with-redefs [pv/save-version! (fn [& _] nil)
                  local-cache/invalidate-decisions-for-class! (fn [& _] nil)]
      (prp/delete-policy "Document")
      (try
        (prp/submit-policy "Document" (json/write-value-as-string dev-policy))
        (prp/submit-policy "Document" (json/write-value-as-string prod-policy))
        (is (= "dev"
               (:environment (prp/getGlobalPolicy "Document" "dev"))))
        (is (= "prod"
               (:environment (prp/getGlobalPolicy "Document"))))
        (is (= "dev-allow-read"
               (get-in (prp/getGlobalPolicy "Document" "dev") [:rules 0 :name])))
        (is (= "allow-read"
               (get-in (prp/getGlobalPolicy "Document" "prod") [:rules 0 :name])))
        (finally
          (prp/delete-policy "Document"))))))

(deftest insert-policy-preserves-legacy-global-wrapper-test
  (let [legacy-policy {:global {:strategy :almost_one_allow_no_deny
                                :rules [{:name "legacy-rule"}]}}]
    (prp/delete-policy "LegacyDocument")
    (try
      (prp/insert-policy "LegacyDocument" legacy-policy)
      (is (= {:strategy :almost_one_allow_no_deny
              :rules [{:name "legacy-rule"}]}
             (prp/getGlobalPolicy "LegacyDocument")))
      (finally
        (prp/delete-policy "LegacyDocument")))))

(deftest validate-policy-submission-rejects-unknown-environment-test
  (let [policy (assoc valid-policy :environment "qa")
        payload (json/write-value-as-string policy)]
    (try
      (prp/validate-policy-submission "Document" payload)
      (is false "Expected invalid policy environment")
      (catch clojure.lang.ExceptionInfo e
        (is (= "INVALID_POLICY_ENVIRONMENT" (:error-code (ex-data e))))
        (is (= ["dev" "prod" "staging"]
               (get-in (ex-data e) [:issues 0 :supported-environments])))))))



(deftest analyze-policy-emits-warnings-for-broad-and-shadowed-rules-test
  (let [policy {:strategy "almost_one_allow_no_deny"
                :rules [{:name "broad-allow"
                         :priority 100
                         :effect "allow"
                         :resourceClass "Document"
                         :conditions []}
                        {:name "shadowed-allow"
                         :priority 10
                         :effect "allow"
                         :resourceClass "Document"
                         :conditions []}]}
        analysis (safety/analyze-policy "Document" policy)
        warning-codes (set (map :code (:warnings analysis)))]
    (is (contains? warning-codes "UNCONDITIONAL_RULE"))
    (is (contains? warning-codes "MISSING_OPERATION"))
    (is (contains? warning-codes "SHADOWED_RULE"))))
