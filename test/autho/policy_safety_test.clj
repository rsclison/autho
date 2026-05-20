(ns autho.policy-safety-test
  (:require [clojure.test :refer :all]
            [jsonista.core :as json]
            [autho.policy-safety :as safety]
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
                  local-cache/invalidate-decisions-for-class! (fn [& _] nil)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Policy safety validation failed"
           (prp/submit-policy "Document" payload))))))



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
