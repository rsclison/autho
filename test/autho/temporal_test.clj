(ns autho.temporal-test
  (:require [clojure.test :refer :all]
            [autho.temporal :as temporal]))

;; =============================================================================
;; Date Parsing Tests
;; =============================================================================

(deftest parse-date-safe-valid-test
  (testing "parse-date-safe with valid date"
    (let [result (temporal/parse-date-safe "2025-01-15")]
      (is (some? result)))))

(deftest parse-date-safe-invalid-test
  (testing "parse-date-safe with invalid date"
    (let [result (temporal/parse-date-safe "invalid-date")]
      (is (nil? result)))))

;; =============================================================================
;; Comparison Operators Tests
;; =============================================================================

(deftest after-true-test
  (testing "after with date1 after date2"
    (is (true? (temporal/after "2025-02-01" "2025-01-01")))))

(deftest after-false-test
  (testing "after with date1 before date2"
    (is (false? (temporal/after "2025-01-01" "2025-02-01")))))

(deftest before-true-test
  (testing "before with date1 before date2"
    (is (true? (temporal/before "2025-01-01" "2025-02-01")))))

(deftest before-false-test
  (testing "before with date1 after date2"
    (is (false? (temporal/before "2025-02-01" "2025-01-01")))))

(deftest between-true-test
  (testing "between with date in range"
    (is (true? (temporal/between "2025-06-15" "2025-01-01" "2025-12-31")))))

(deftest between-false-test
  (testing "between with date outside range"
    (is (false? (temporal/between "2025-06-15" "2025-01-01" "2025-05-31")))))

(deftest between-equal-edges-test
  (testing "between with date equal to start"
    (is (true? (temporal/between "2025-01-01" "2025-01-01" "2025-12-31")))))

;; =============================================================================
;; Duration Parsing Tests
;; =============================================================================

(deftest parse-days-duration-positive-test
  (testing "parse-days-duration with positive duration"
    (let [result (temporal/parse-days-duration "7d")]
      (is (some? result)))))

(deftest parse-days-duration-negative-test
  (testing "parse-days-duration with negative duration"
    (let [result (temporal/parse-days-duration "-30d")]
      (is (some? result)))))

(deftest parse-days-duration-invalid-test
  (testing "parse-days-duration with invalid duration"
    (let [result (temporal/parse-days-duration "invalid")]
      (is (nil? result)))))

;; =============================================================================
;; Relative Time Operations Tests
;; =============================================================================

(deftest older-true-test
  (testing "older with date older than duration"
    (is (true? (temporal/older "2024-01-01" "365d")))))

(deftest older-false-test
  (testing "older with recent date"
    (is (false? (temporal/older "2026-01-01" "365d")))))

;; =============================================================================
;; Business Time Helpers Tests
;; =============================================================================

(deftest is-weekend-saturday-test
  (testing "is-weekend with Saturday"
    (is (true? (temporal/is-weekend "2025-01-04")))))

(deftest is-weekend-sunday-test
  (testing "is-weekend with Sunday"
    (is (true? (temporal/is-weekend "2025-01-05")))))

(deftest is-weekend-monday-test
  (testing "is-weekend with Monday"
    (is (false? (temporal/is-weekend "2025-01-06")))))

(deftest is-business-day-monday-test
  (testing "is-business-day with Monday"
    (is (true? (temporal/is-business-day "2025-01-06")))))

(deftest is-business-day-saturday-test
  (testing "is-business-day with Saturday"
    (is (false? (temporal/is-business-day "2025-01-04")))))

(deftest get-day-of-week-monday-test
  (testing "get-day-of-week with Monday"
    (is (= 1 (temporal/get-day-of-week "2025-01-06")))))

(deftest get-day-of-week-saturday-test
  (testing "get-day-of-week with Saturday"
    (is (= 6 (temporal/get-day-of-week "2025-01-04")))))
