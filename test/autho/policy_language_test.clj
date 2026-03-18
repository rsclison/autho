(ns autho.policy-language-test
  (:require [clojure.test :refer :all]
            [autho.policy-language :as pl]))

(deftest eval-or-basic-test
  (testing "eval-or with true result"
    (let [ctxt {:subject {:role "admin"} :resource {}}]
      (is (true? (pl/eval-or ["=" "$.role" "admin"] ["=" "$.role" "superadmin"] ctxt :subject))))))

(deftest eval-or-false-test
  (testing "eval-or with false result"
    (let [ctxt {:subject {:role "user"} :resource {}}]
      (is (false? (pl/eval-or ["=" "$.role" "admin"] ["=" "$.role" "superadmin"] ctxt :subject))))))

(deftest eval-and-true-test
  (testing "eval-and-extended with true result"
    (let [ctxt {:subject {:role "admin" :level "7"} :resource {}}]
      (is (true? (pl/eval-and-extended [["=" "$.role" "admin"] [">" "$.level" "5"]] ctxt :subject))))))

(deftest eval-and-false-test
  (testing "eval-and-extended with false result"
    (let [ctxt {:subject {:role "admin" :level "3"} :resource {}}]
      (is (false? (pl/eval-and-extended [["=" "$.role" "admin"] [">" "$.level" "5"]] ctxt :subject))))))

(deftest eval-clause-extended-or-test
  (testing "eval-clause-extended with OR"
    (let [ctxt {:subject {:role "superadmin"} :resource {}}]
      (is (true? (pl/eval-clause-extended "or" ["=" "$.role" "admin"] ["=" "$.role" "superadmin"] ctxt :subject))))))

(deftest eval-clause-extended-and-test
  (testing "eval-clause-extended with AND"
    (let [ctxt {:subject {:role "admin" :level "7"} :resource {}}]
      (is (true? (pl/eval-clause-extended "and" ["=" "$.role" "admin"] [">" "$.level" "5"] ctxt :subject))))))

(deftest eval-clause-extended-standard-test
  (testing "eval-clause-extended with standard operator"
    (let [ctxt {:subject {:role "admin"} :resource {}}]
      (is (true? (pl/eval-clause-extended "=" "$.role" "admin" ctxt :subject))))))
