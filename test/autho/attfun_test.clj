(ns autho.attfun-test
  (:require [clojure.test :refer :all]
            [autho.attfun :as sut]))

(deftest logical-operators-test
  (testing "Test for the 'et' (AND) function"
    (is (sut/et true true))
    (is (not (sut/et true false)))
    (is (not (sut/et false false)))
    (is (sut/et true true true))
    (is (not (sut/et true false true)))
    (is (sut/et)))

  (testing "Test for the 'non' (NOT) function"
    (is (not (sut/non true)))
    (is (sut/non false))))

(deftest equality-operators-test
  (testing "Test for the '=' (equality) function"
    (is (sut/= 1 1))
    (is (not (sut/= 1 2)))
    (is (sut/= "a" "a"))
    (is (not (sut/= "a" "b")))
    (is (not (sut/= 1 "1")))
    (is (sut/= nil nil))
    (is (sut/= 1 1 1))
    (is (not (sut/= 1 1 2))))

  (testing "Test for the 'diff' (inequality) function"
    (is (not (sut/diff 1 1)))
    (is (sut/diff 1 2))
    (is (not (sut/diff "a" "a")))
    (is (sut/diff "a" "b"))
    (is (sut/diff 1 "1"))
    (is (sut/diff 1 2 1))))

(deftest collection-operators-test
  (let [test-set #{"a" "b" "c" 1 2 nil}]
    (testing "Test for the 'in' function"
      (is (sut/in "a" test-set))
      (is (not (sut/in "d" test-set)))
      (is (sut/in nil test-set))
      (is (sut/in 1 test-set))
      (is (not (sut/in 4 test-set))))

    (testing "Test for the 'notin' function"
      (is (not (sut/notin "a" test-set)))
      (is (sut/notin "d" test-set))
      (is (not (sut/notin nil test-set)))
      (is (not (sut/notin 1 test-set)))
      (is (sut/notin 4 test-set))))

  (testing "Test collection operators with boundary cases"
    (is (not (sut/in "a" #{})))
    (is (sut/notin "a" #{}))
    (is (thrown? Exception (sut/in "a" "not-a-collection")))
    (is (thrown? Exception (sut/notin "a" "not-a-collection")))))

(deftest comparison-operators-test
  (testing "Test for the '>' function"
    (is (sut/> "2" "1"))
    (is (not (sut/> "1" "2")))
    (is (not (sut/> "1" "1")))
    (is (sut/> "1.1" "1.0")))

  (testing "Test for the '>=' function"
    (is (sut/>= "2" "1"))
    (is (not (sut/>= "1" "2")))
    (is (sut/>= "1" "1"))
    (is (sut/>= "1.1" "1.0")))

  (testing "Test for the '<' function"
    (is (sut/< "1" "2"))
    (is (not (sut/< "2" "1")))
    (is (not (sut/< "1" "1")))
    (is (sut/< "1.0" "1.1")))

  (testing "Test for the '<=' function"
    (is (sut/<= "1" "2"))
    (is (not (sut/<= "2" "1")))
    (is (sut/<= "1" "1"))
    (is (sut/<= "1.0" "1.1")))

  (testing "Test comparison operators with invalid inputs"
    (is (thrown? Exception (sut/> "a" "1")))
    (is (thrown? Exception (sut/> "1" "a")))
    (is (thrown? Exception (sut/>= "a" "1")))
    (is (thrown? Exception (sut/>= "1" "a")))
    (is (thrown? Exception (sut/< "a" "1")))
    (is (thrown? Exception (sut/< "1" "a")))
    (is (thrown? Exception (sut/<= "a" "1")))
    (is (thrown? Exception (sut/<= "1" "a")))))

(deftest date-comparison-test
  (testing "Test for the 'date>' function"
    (is (sut/date> "2023-01-02" "2023-01-01"))
    (is (not (sut/date> "2023-01-01" "2023-01-02")))
    (is (not (sut/date> "2023-01-01" "2023-01-01")))
    (is (sut/date> "2024-01-01" "2023-12-31")))

  (testing "Test 'date>' with invalid date formats"
    (is (thrown? Exception (sut/date> "not-a-date" "2023-01-01")))
    (is (thrown? Exception (sut/date> "2023-01-01" "not-a-date")))
    (is (thrown? Exception (sut/date> "2023-13-01" "2023-01-01")))
    (is (thrown? Exception (sut/date> "2023-01-32" "2023-01-01")))
    (is (thrown? Exception (sut/date> "2023/01/01" "2023-01-01")))))

(deftest inverse-op-test
  (testing "Test for the 'inverseOp' function"
    (is (= '>= (sut/inverseOp '<)))
    (is (= '<= (sut/inverseOp '>)))
    (is (= '= (sut/inverseOp 'diff)))
    (is (= 'diff (sut/inverseOp '=)))
    (is (nil? (sut/inverseOp 'something-else)))))

(deftest csv-pip-test
  (testing "Test for the 'csvPip' function through 'att'"
    (let [user-obj {:class :user :id "user1"}]
      (is (= {:id "user1", :name "Alice", :email "alice@example.com", :city "Paris"}
             (sut/att "name" user-obj))))
    (let [user-obj {:class :user :id "user2"}]
      (is (= {:id "user2", :name "Bob", :email "bob@example.com", :city "London"}
             (sut/att "email" user-obj))))
    (let [user-obj {:class :user :id "user4"}]
      (is (nil? (sut/att "name" user-obj))))))
