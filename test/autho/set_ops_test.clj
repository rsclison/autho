(ns autho.set-ops-test
  (:require [clojure.test :refer :all]
            [autho.set-ops :as set-ops]))

;; =============================================================================
;; Parse Set Tests
;; =============================================================================

(deftest parse-set-valid-test
  (testing "parse-set with valid comma-separated string"
    (let [result (set-ops/parse-set "admin,user,mod")]
      (is (set? result))
      (is (= 3 (count result))))))

(deftest parse-set-nil-test
  (testing "parse-set with nil input"
    (let [result (set-ops/parse-set nil)]
      (is (nil? result)))))

(deftest parse-set-empty-test
  (testing "parse-set with empty string"
    (let [result (set-ops/parse-set "")]
      (is (nil? result)))))

;; =============================================================================
;; Intersect Tests
;; =============================================================================

(deftest intersect-true-test
  (testing "intersect with common elements"
    (is (true? (set-ops/intersect "admin,user" "user,mod")))))

(deftest intersect-false-test
  (testing "intersect with no common elements"
    (is (false? (set-ops/intersect "admin" "user,mod")))))

(deftest intersect-multiple-common-test
  (testing "intersect with multiple common elements"
    (is (true? (set-ops/intersect "admin,user,mod" "user,mod,owner")))))

(deftest intersect-empty-set-test
  (testing "intersect with empty set"
    (is (false? (set-ops/intersect "" "user,mod")))))

;; =============================================================================
;; IsSubset Tests
;; =============================================================================

(deftest isSubset-true-test
  (testing "isSubset with valid subset"
    (is (true? (set-ops/isSubset "admin" "admin,user,mod")))))

(deftest isSubset-multiple-true-test
  (testing "isSubset with multiple elements"
    (is (true? (set-ops/isSubset "admin,user" "admin,user,mod,owner")))))

(deftest isSubset-false-test
  (testing "isSubset with element not in superset"
    (is (false? (set-ops/isSubset "admin,owner" "admin,user")))))

(deftest isSubset-equal-sets-test
  (testing "isSubset with equal sets"
    (is (true? (set-ops/isSubset "admin,user" "user,admin")))))

;; =============================================================================
;; IsSuperset Tests
;; =============================================================================

(deftest isSuperset-true-test
  (testing "isSuperset with valid superset"
    (is (true? (set-ops/isSuperset "admin,user,mod" "admin")))))

(deftest isSuperset-multiple-true-test
  (testing "isSuperset with multiple elements"
    (is (true? (set-ops/isSuperset "admin,user,mod,owner" "admin,user")))))

(deftest isSuperset-false-test
  (testing "isSuperset with element not in set"
    (is (false? (set-ops/isSuperset "admin,user" "admin,owner")))))

(deftest isSuperset-equal-sets-test
  (testing "isSuperset with equal sets"
    (is (true? (set-ops/isSuperset "admin,user" "user,admin")))))

;; =============================================================================
;; Equals Tests
;; =============================================================================

(deftest equals-true-test
  (testing "equals with same elements different order"
    (is (true? (set-ops/equals "admin,user" "user,admin")))))

(deftest equals-false-test
  (testing "equals with different elements"
    (is (false? (set-ops/equals "admin,user" "admin")))))

(deftest equals-empty-sets-test
  (testing "equals with empty strings"
    (is (true? (set-ops/equals "" "")))))
