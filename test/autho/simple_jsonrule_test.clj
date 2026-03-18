(ns autho.simple-jsonrule-test
  (:require [clojure.test :refer :all]
            [autho.jsonrule :as jsonrule]))

(deftest test-simple-equality-with-simple-context
  (testing "Very simple equality with simple context like existing test"
    (let [ctxt {:class :test}]
      (is (true? (jsonrule/evalClause ["=" "2" "2"] ctxt :subject))))))

(deftest test-path-equality-with-full-context
  (testing "Path equality with full context"
    (let [ctxt {:subject {:id "002"} :resource {} :operation "" :context {}}]
      (is (true? (jsonrule/evalClause ["=" "$.id" "002"] ctxt :subject))))))

(deftest test-role-equality-with-full-context
  (testing "Role equality with full context"
    (let [ctxt {:subject {:id "002" :role "admin"} :resource {} :operation "" :context {}}]
      (is (true? (jsonrule/evalClause ["=" "$.role" "admin"] ctxt :subject))))))
