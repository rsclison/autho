(ns autho.jsonrule-test
  (:require [clojure.test :refer :all]
            [autho.jsonrule :as jsonrule]))

;; ==============================================================================
;; Tests pour evalOperand
;; ==============================================================================

(deftest evalOperand-scalar-value-test
  (testing "evalOperand returns scalar values as-is"
    (let [ctxt {:subject {:id "user1"} :resource {:class "Document"}}]
      (is (= "hello" (jsonrule/evalOperand "hello" :subject ctxt)))
      (is (= "42" (jsonrule/evalOperand "42" :subject ctxt)))
      (is (= "true" (jsonrule/evalOperand "true" :subject ctxt))))))

(deftest evalOperand-dollar-path-subject-test
  (testing "evalOperand with $. path on subject"
    (let [ctxt {:subject {:id "user1" :role "admin" :age 30}
                :resource {:class "Document"}}]
      (is (= "user1" (jsonrule/evalOperand "$.id" :subject ctxt)))
      (is (= "admin" (jsonrule/evalOperand "$.role" :subject ctxt)))
      (is (= "30" (jsonrule/evalOperand "$.age" :subject ctxt))))))

(deftest evalOperand-dollar-path-resource-test
  (testing "evalOperand with $. path on resource"
    (let [ctxt {:subject {:id "user1"}
                :resource {:class "Document" :id "doc123" :owner "user2"}}]
      (is (= "Document" (jsonrule/evalOperand "$.class" :resource ctxt)))
      (is (= "doc123" (jsonrule/evalOperand "$.id" :resource ctxt)))
      (is (= "user2" (jsonrule/evalOperand "$.owner" :resource ctxt))))))

(deftest evalOperand-dollar-r-path-test
  (testing "evalOperand with $r path for resource"
    (let [ctxt {:subject {:id "user1"}
                :resource {:class "Document" :id "doc123"}}]
      (is (= "Document" (jsonrule/evalOperand "$r.class" :subject ctxt)))
      (is (= "doc123" (jsonrule/evalOperand "$r.id" :subject ctxt))))))

(deftest evalOperand-dollar-s-path-test
  (testing "evalOperand with $s path for subject"
    (let [ctxt {:subject {:id "user1" :role "admin"}
                :resource {:class "Document"}}]
      (is (= "user1" (jsonrule/evalOperand "$s.id" :resource ctxt)))
      (is (= "admin" (jsonrule/evalOperand "$s.role" :resource ctxt))))))

(deftest evalOperand-nested-path-test
  (testing "evalOperand with nested JSONPath"
    (let [ctxt {:subject {:id "user1" :metadata {:department "IT" :level 5}}
                :resource {}}]
      (is (= "IT" (jsonrule/evalOperand "$.metadata.department" :subject ctxt)))
      (is (= "5" (jsonrule/evalOperand "$.metadata.level" :subject ctxt))))))

(deftest evalOperand-numeric-conversion-test
  (testing "evalOperand converts numbers to strings"
    (let [ctxt {:subject {:age 30 :score 95.5 :count 0}
                :resource {}}]
      (is (= "30" (jsonrule/evalOperand "$.age" :subject ctxt)))
      (is (= "95.5" (jsonrule/evalOperand "$.score" :subject ctxt)))
      (is (= "0" (jsonrule/evalOperand "$.count" :subject ctxt))))))

;; ==============================================================================
;; Tests pour evalClause - Operators
;; ==============================================================================

(deftest evalClause-equality-operator-test
  (testing "evalClause with = operator"
    (let [ctxt {:subject {:role "admin"} :resource {}}]
      (is (true? (jsonrule/evalClause ["=" "admin" "admin"] ctxt :subject)))
      (is (true? (jsonrule/evalClause ["=" "$.role" "admin"] ctxt :subject)))
      (is (false? (jsonrule/evalClause ["=" "$.role" "user"] ctxt :subject))))))

(deftest evalClause-diff-operator-test
  (testing "evalClause with diff (!=) operator"
    (let [ctxt {:subject {:role "admin"} :resource {}}]
      (is (true? (jsonrule/evalClause ["diff" "admin" "user"] ctxt :subject)))
      (is (true? (jsonrule/evalClause ["diff" "$.role" "user"] ctxt :subject)))
      (is (false? (jsonrule/evalClause ["diff" "$.role" "admin"] ctxt :subject))))))

(deftest evalClause-greater-than-operator-test
  (testing "evalClause with > operator"
    (let [ctxt {:subject {:age "30"} :resource {}}]
      (is (true? (jsonrule/evalClause [">" "30" "20"] ctxt :subject)))
      (is (true? (jsonrule/evalClause [">" "$.age" "25"] ctxt :subject)))
      (is (false? (jsonrule/evalClause [">" "$.age" "35"] ctxt :subject))))))

(deftest evalClause-less-than-operator-test
  (testing "evalClause with < operator"
    (let [ctxt {:subject {:age "30"} :resource {}}]
      (is (true? (jsonrule/evalClause ["<" "30" "40"] ctxt :subject)))
      (is (true? (jsonrule/evalClause ["<" "$.age" "35"] ctxt :subject)))
      (is (false? (jsonrule/evalClause ["<" "$.age" "25"] ctxt :subject))))))

(deftest evalClause-greater-equal-operator-test
  (testing "evalClause with >= operator"
    (let [ctxt {:subject {:age "30"} :resource {}}]
      (is (true? (jsonrule/evalClause [">=" "30" "30"] ctxt :subject)))
      (is (true? (jsonrule/evalClause [">=" "$.age" "30"] ctxt :subject)))
      (is (true? (jsonrule/evalClause [">=" "$.age" "25"] ctxt :subject)))
      (is (false? (jsonrule/evalClause [">=" "$.age" "35"] ctxt :subject))))))

(deftest evalClause-less-equal-operator-test
  (testing "evalClause with <= operator"
    (let [ctxt {:subject {:age "30"} :resource {}}]
      (is (true? (jsonrule/evalClause ["<=" "30" "30"] ctxt :subject)))
      (is (true? (jsonrule/evalClause ["<=" "$.age" "30"] ctxt :subject)))
      (is (true? (jsonrule/evalClause ["<=" "$.age" "35"] ctxt :subject)))
      (is (false? (jsonrule/evalClause ["<=" "$.age" "25"] ctxt :subject))))))

(deftest evalClause-unknown-operator-test
  (testing "evalClause throws error for unknown operator"
    (let [ctxt {:subject {:role "admin"} :resource {}}]
      (is (thrown? Exception
                   (jsonrule/evalClause ["unknown_op" "$.role" "admin"] ctxt :subject))))))

;; ==============================================================================
;; Tests pour evalClause - Cross-context comparisons
;; ==============================================================================

(deftest evalClause-subject-vs-resource-test
  (testing "evalClause comparing subject and resource attributes"
    (let [ctxt {:subject {:id "user1" :department "IT"}
                :resource {:class "Document" :owner "user1" :department "IT"}}]
      (is (true? (jsonrule/evalClause ["=" "$.id" "$r.owner"] ctxt :subject)))
      (is (true? (jsonrule/evalClause ["=" "$.department" "$r.department"] ctxt :subject)))
      (is (false? (jsonrule/evalClause ["=" "$.id" "$r.class"] ctxt :subject))))))

(deftest evalClause-resource-vs-subject-test
  (testing "evalClause comparing resource and subject attributes"
    (let [ctxt {:subject {:id "user1" :role "admin"}
                :resource {:class "Document" :required-role "admin"}}]
      (is (true? (jsonrule/evalClause ["=" "$r.required-role" "$s.role"] ctxt :resource)))
      (is (false? (jsonrule/evalClause ["=" "$r.class" "$s.role"] ctxt :resource))))))

;; ==============================================================================
;; Tests pour evaluateRule
;; ==============================================================================

(deftest evaluateRule-subject-only-test
  (testing "evaluateRule with only subject conditions"
    (let [rule {:subjectCond [:and ["=" "$.role" "admin"] [">" "$.level" "5"]]
                :resourceCond [:and]}
          request {:subject {:role "admin" :level "10"}
                   :resource {:class "Document"}
                   :operation "read"
                   :context {}}]
      (is (true? (:value (jsonrule/evaluateRule rule request)))))))

(deftest evaluateRule-resource-only-test
  (testing "evaluateRule with only resource conditions"
    (let [rule {:subjectCond [:and]
                :resourceCond [:and ["=" "$.class" "Document"] ["=" "$.public" "true"]]}
          request {:subject {:id "user1"}
                   :resource {:class "Document" :public "true"}
                   :operation "read"
                   :context {}}]
      (is (true? (:value (jsonrule/evaluateRule rule request)))))))

(deftest evaluateRule-both-subject-and-resource-test
  (testing "evaluateRule with both subject and resource conditions"
    (let [rule {:subjectCond [:and ["=" "$.role" "admin"]]
                :resourceCond [:and ["=" "$.class" "Document"]]}
          request {:subject {:role "admin"}
                   :resource {:class "Document"}
                   :operation "read"
                   :context {}}]
      (is (true? (:value (jsonrule/evaluateRule rule request)))))))

(deftest evaluateRule-failing-subject-condition-test
  (testing "evaluateRule fails when subject condition doesn't match"
    (let [rule {:subjectCond [:and ["=" "$.role" "admin"]]
                :resourceCond [:and ["=" "$.class" "Document"]]}
          request {:subject {:role "user"}
                   :resource {:class "Document"}
                   :operation "read"
                   :context {}}]
      (is (false? (:value (jsonrule/evaluateRule rule request)))))))

(deftest evaluateRule-failing-resource-condition-test
  (testing "evaluateRule fails when resource condition doesn't match"
    (let [rule {:subjectCond [:and ["=" "$.role" "admin"]]
                :resourceCond [:and ["=" "$.class" "Document"]]}
          request {:subject {:role "admin"}
                   :resource {:class "Image"}
                   :operation "read"
                   :context {}}]
      (is (false? (:value (jsonrule/evaluateRule rule request)))))))

(deftest evaluateRule-complex-conditions-test
  (testing "evaluateRule with complex multi-condition rule"
    (let [rule {:subjectCond [:and
                              ["=" "$.role" "admin"]
                              [">" "$.level" "5"]
                              ["=" "$.department" "IT"]]
                :resourceCond [:and
                               ["=" "$.class" "Document"]
                               ["=" "$.status" "active"]
                               ["<=" "$.classification" "3"]]}
          request {:subject {:role "admin" :level "7" :department "IT"}
                   :resource {:class "Document" :status "active" :classification "2"}
                   :operation "read"
                   :context {}}]
      (is (true? (:value (jsonrule/evaluateRule rule request)))))))

(deftest evaluateRule-owner-access-test
  (testing "evaluateRule for owner-based access control"
    (let [rule {:subjectCond [:and ["=" "$.id" "$r.owner"]]
                :resourceCond [:and ["=" "$.class" "Document"]]}
          request {:subject {:id "user123"}
                   :resource {:class "Document" :owner "user123"}
                   :operation "write"
                   :context {}}]
      (is (true? (:value (jsonrule/evaluateRule rule request)))))))

(deftest evaluateRule-non-owner-denied-test
  (testing "evaluateRule denies access when user is not owner"
    (let [rule {:subjectCond [:and ["=" "$.id" "$r.owner"]]
                :resourceCond [:and ["=" "$.class" "Document"]]}
          request {:subject {:id "user456"}
                   :resource {:class "Document" :owner "user123"}
                   :operation "write"
                   :context {}}]
      (is (false? (:value (jsonrule/evaluateRule rule request)))))))

;; ==============================================================================
;; Tests pour evalRuleWithResource
;; ==============================================================================

(deftest evalRuleWithResource-matching-conditions-test
  (testing "evalRuleWithResource with matching resource conditions"
    (let [rule {:resourceCond [:and ["=" "$.class" "Document"] ["=" "$.public" "true"]]}
          request {:subject {:id "user1"}
                   :resource {:class "Document" :public "true"}
                   :operation "read"
                   :context {}}]
      (is (true? (jsonrule/evalRuleWithResource rule request))))))

(deftest evalRuleWithResource-non-matching-conditions-test
  (testing "evalRuleWithResource with non-matching resource conditions"
    (let [rule {:resourceCond [:and ["=" "$.class" "Document"] ["=" "$.public" "true"]]}
          request {:subject {:id "user1"}
                   :resource {:class "Document" :public "false"}
                   :operation "read"
                   :context {}}]
      (is (false? (jsonrule/evalRuleWithResource rule request))))))

;; ==============================================================================
;; Tests pour evalRuleWithSubject
;; ==============================================================================

(deftest evalRuleWithSubject-matching-conditions-test
  (testing "evalRuleWithSubject with matching subject conditions"
    (let [rule {:subjectCond [:and ["=" "$.role" "admin"] [">" "$.level" "5"]]}
          request {:subject {:role "admin" :level "10"}
                   :resource {:class "Document"}
                   :operation "read"
                   :context {}}]
      (is (true? (jsonrule/evalRuleWithSubject rule request))))))

(deftest evalRuleWithSubject-non-matching-conditions-test
  (testing "evalRuleWithSubject with non-matching subject conditions"
    (let [rule {:subjectCond [:and ["=" "$.role" "admin"] [">" "$.level" "5"]]}
          request {:subject {:role "admin" :level "3"}
                   :resource {:class "Document"}
                   :operation "read"
                   :context {}}]
      (is (false? (jsonrule/evalRuleWithSubject rule request))))))

;; ==============================================================================
;; Tests d'intégration - Scénarios réels
;; ==============================================================================

(deftest integration-abac-scenario-test
  (testing "Complete ABAC scenario with multiple attributes"
    (let [rule {:subjectCond [:and
                              ["=" "$.role" "manager"]
                              [">=" "$.level" "3"]]
                :resourceCond [:and
                               ["=" "$.class" "Report"]
                               ["=" "$.type" "quarterly"]]}
          request {:subject {:id "mgr1" :role "manager" :level "4"}
                   :resource {:class "Report" :type "quarterly"}
                   :operation "read"
                   :context {}}]
      (is (true? (:value (jsonrule/evaluateRule rule request)))))))

(deftest integration-multi-tenant-scenario-test
  (testing "Multi-tenant isolation scenario"
    (let [rule {:subjectCond [:and ["=" "$.tenant-id" "$r.tenant-id"]]
                :resourceCond [:and ["=" "$.class" "Data"]]}
          request {:subject {:id "user1" :tenant-id "acme-corp"}
                   :resource {:class "Data" :tenant-id "acme-corp"}
                   :operation "read"
                   :context {}}]
      (is (true? (:value (jsonrule/evaluateRule rule request)))))))

(deftest integration-multi-tenant-isolation-fail-test
  (testing "Multi-tenant isolation denies cross-tenant access"
    (let [rule {:subjectCond [:and ["=" "$.tenant-id" "$r.tenant-id"]]
                :resourceCond [:and ["=" "$.class" "Data"]]}
          request {:subject {:id "user1" :tenant-id "acme-corp"}
                   :resource {:class "Data" :tenant-id "globex-inc"}
                   :operation "read"
                   :context {}}]
      (is (false? (:value (jsonrule/evaluateRule rule request)))))))

;; ==============================================================================
;; Tests pour les cas limites (edge cases)
;; ==============================================================================

(deftest edge-case-special-characters-test
  (testing "evalClause handles special characters in values"
    (let [ctxt {:subject {:email "user+test@example.com" :name "O'Brien"}
               :resource {}}]
      (is (= "user+test@example.com" (jsonrule/evalOperand "$.email" :subject ctxt)))
      (is (= "O'Brien" (jsonrule/evalOperand "$.name" :subject ctxt))))))

(deftest edge-case-unicode-characters-test
  (testing "evalClause handles unicode characters"
    (let [ctxt {:subject {:name "José García" :city "São Paulo"}
               :resource {}}]
      (is (= "José García" (jsonrule/evalOperand "$.name" :subject ctxt)))
      (is (= "São Paulo" (jsonrule/evalOperand "$.city" :subject ctxt))))))
