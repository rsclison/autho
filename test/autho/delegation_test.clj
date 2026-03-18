(ns autho.delegation-test
  (:require [clojure.test :refer :all]
            [autho.delegation :as delegation]
            [autho.jsonrule :as jsonrule]))

;; --- Tests for Debugging jsonrule ---

(deftest jsonrule-simple-equality-test
  (testing "Debug jsonrule simple equality"
    (let [person {:id "002" :name "John" :role "admin"}
          ctxt {:subject person :resource {} :operation "" :context {}}]
      (is (true? (jsonrule/evalClause ["=" "2" "2"] ctxt :subject))))))

(deftest jsonrule-path-equality-test
  (testing "Debug jsonrule path equality"
    (let [person {:id "002" :name "John" :role "admin"}
          ctxt {:subject person :resource {} :operation "" :context {}}]
      (is (true? (jsonrule/evalClause ["=" "$.id" "002"] ctxt :subject))))))

;; --- Tests for Clause Conversion ---

(deftest convert-clause-to-jsonrule-simple-equality-test
  (testing "Simple equality clause conversion"
    (let [clause '(= id "002")
          result (delegation/convert-clause-to-jsonrule clause)]
      (is (= ["=" "$.id" "002"] result)))))

(deftest convert-clause-to-jsonrule-numeric-comparison-test
  (testing "Numeric comparison clause conversion"
    (let [clause '(< age 30)
          result (delegation/convert-clause-to-jsonrule clause)]
      (is (= ["<" "$.age" "30"] result)))))

(deftest convert-clause-to-jsonrule-string-attribute-test
  (testing "String attribute clause conversion"
    (let [clause '(= role "chef_de_service")
          result (delegation/convert-clause-to-jsonrule clause)]
      (is (= ["=" "$.role" "chef_de_service"] result)))))

;; --- Tests for Condition Evaluation ---

(deftest eval-conditions-simple-equality-test
  (testing "Simple equality condition evaluation"
    (let [conditions ['(= id "002")]
          person {:id "002" :name "John" :role "admin"}]
      (is (true? (delegation/eval-conditions conditions person))))))

(deftest eval-conditions-failed-equality-test
  (testing "Failed equality condition evaluation"
    (let [conditions ['(= id "003")]
          person {:id "002" :name "John" :role "admin"}]
      (is (false? (delegation/eval-conditions conditions person))))))

(deftest eval-conditions-role-check-test
  (testing "Role check condition evaluation"
    (let [conditions ['(= role "chef_de_service")]
          person {:id "001" :name "Jane" :role "chef_de_service"}]
      (is (true? (delegation/eval-conditions conditions person))))))

(deftest eval-conditions-numeric-comparison-test
  (testing "Numeric comparison condition evaluation"
    (let [conditions ['(< age 30)]
          person {:id "001" :name "Jane" :age 25}]
      (is (true? (delegation/eval-conditions conditions person))))))

(deftest eval-conditions-failed-numeric-comparison-test
  (testing "Failed numeric comparison condition evaluation"
    (let [conditions ['(< age 30)]
          person {:id "001" :name "Jane" :age 35}]
      (is (false? (delegation/eval-conditions conditions person))))))

(deftest eval-conditions-multiple-conditions-test
  (testing "Multiple conditions evaluation (all must match)"
    (let [conditions ['(= role "chef_de_service") '(< age 50)]
          person {:id "001" :name "Jane" :role "chef_de_service" :age 45}]
      (is (true? (delegation/eval-conditions conditions person))))))

(deftest eval-conditions-multiple-failed-one-test
  (testing "Multiple conditions with one failing"
    (let [conditions ['(= role "chef_de_service") '(< age 30)]
          person {:id "001" :name "Jane" :role "chef_de_service" :age 45}]
      (is (false? (delegation/eval-conditions conditions person))))))

;; --- Tests for Edge Cases ---

(deftest eval-conditions-empty-conditions-test
  (testing "Empty conditions should match"
    (let [conditions []
          person {:id "001" :name "Jane"}]
      (is (true? (delegation/eval-conditions conditions person))))))

(deftest eval-conditions-missing-attribute-test
  (testing "Missing attribute should handle gracefully"
    (let [conditions ['(= department "IT")]
          person {:id "001" :name "Jane" :role "admin"}]
      (is (false? (delegation/eval-conditions conditions person))))))

;; --- Tests for Real-world Scenarios ---

(deftest eval-conditions-delegation-scenario-1-test
  (testing "Real delegation scenario: ID-based delegation"
    (let [conditions ['(= id "002")]
          persons [{:id "001" :name "Alice" :role "admin"}
                   {:id "002" :name "Bob" :role "user"}
                   {:id "003" :name "Charlie" :role "user"}]]
      (is (= 1 (count (filter #(delegation/eval-conditions conditions %) persons))))
      (is (= "002" (:id (first (filter #(delegation/eval-conditions conditions %) persons))))))))

(deftest eval-conditions-delegation-scenario-2-test
  (testing "Real delegation scenario: Role-based delegation"
    (let [conditions ['(= role "chef_de_service")]
          persons [{:id "001" :name "Alice" :role "admin"}
                   {:id "002" :name "Bob" :role "chef_de_service"}
                   {:id "003" :name "Charlie" :role "chef_de_service"}]]
      (is (= 2 (count (filter #(delegation/eval-conditions conditions %) persons)))))))

(deftest eval-conditions-delegation-scenario-3-test
  (testing "Real delegation scenario: Combined role and age criteria"
    (let [conditions ['(= role "chef_de_service") '(< age 50)]
          persons [{:id "001" :name "Alice" :role "chef_de_service" :age 45}
                   {:id "002" :name "Bob" :role "chef_de_service" :age 55}
                   {:id "003" :name "Charlie" :role "admin" :age 35}]]
      (is (= 1 (count (filter #(delegation/eval-conditions conditions %) persons))))
      (is (= "001" (:id (first (filter #(delegation/eval-conditions conditions %) persons))))))))
