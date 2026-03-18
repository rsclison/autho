(ns autho.debug-numeric-test
  (:require [clojure.test :refer :all]
            [autho.jsonrule :as jsonrule]))

(deftest test-numeric-comparison-with-strings
  (testing "Numeric comparison with string values"
    (let [person {:age 25}
          ctxt {:subject person :resource {} :operation "" :context {}}]
      (is (true? (jsonrule/evalClause ["<" "25" "30"] ctxt :subject))))))

(deftest test-numeric-comparison-with-path
  (testing "Numeric comparison with jsonpath"
    (let [person {:age 25}
          ctxt {:subject person :resource {} :operation "" :context {}}]
      (is (true? (jsonrule/evalClause ["<" "$.age" "30"] ctxt :subject))))))
