(ns autho.existing-jsonrule-test
  (:require [clojure.test :refer :all]
            [autho.jsonrule :as jsonrule]))

;; Test exactement comme dans rule_test.clj
(deftest jsonrule-evalClause-test
  (is (true? (jsonrule/evalClause ["=" "2" "2"] {:class :test} :subject))))

;; Test avec sujet et ressource
(deftest jsonrule-with-subject-resource-test
  (testing "With subject and resource"
    (let [ctxt {:subject {:id "002"} :resource {} :operation "" :context {}}]
      (is (true? (jsonrule/evalClause ["=" "2" "2"] ctxt :subject))))))

;; Test pour voir si resolve fonctionne
(deftest resolve-operator-test
  (testing "Check if resolve works for operators"
    (let [func (resolve(symbol "autho.attfun" "="))]
      (is (some? func) "resolve should find the = function in autho.attfun"))))

;; Test pour voir si apply fonctionne avec func
(deftest apply-operator-test
  (testing "Check if apply works with resolved function"
    (let [func (resolve(symbol "autho.attfun" "="))
          result (apply func ["2" "2"])]
      (is (true? result) "apply should work with resolved function"))))
