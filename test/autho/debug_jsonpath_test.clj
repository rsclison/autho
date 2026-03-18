(ns autho.debug-jsonpath-test
  (:require [clojure.test :refer :all]
            [autho.jsonrule :as jsonrule]
            [autho.jsonpath :as js]))

(deftest test-jsonpath-simple-attribute
  (testing "JSONPath with simple attribute"
    (let [person {:id "002" :name "John" :role "admin"}]
      (is (= "002" (js/at-path "$.id" person))))))

(deftest test-jsonpath-nested-attribute
  (testing "JSONPath with nested attribute"
    (let [person {:id "002" :details {:name "John" :role "admin"}}]
      (is (= "John" (js/at-path "$.details.name" person))))))

(deftest test-jsonrule-with-jsonpath
  (testing "jsonrule with jsonpath attribute access"
    (let [person {:id "002" :name "John" :role "admin"}
          ctxt {:subject person :resource {} :operation "" :context {}}]
      (is (true? (jsonrule/evalClause ["=" "$.id" "002"] ctxt :subject))))))

(deftest test-jsonrule-with-role
  (testing "jsonrule with role attribute"
    (let [person {:id "002" :name "John" :role "admin"}
          ctxt {:subject person :resource {} :operation "" :context {}}]
      (is (true? (jsonrule/evalClause ["=" "$.role" "admin"] ctxt :subject))))))
