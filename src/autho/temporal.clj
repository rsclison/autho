(ns autho.temporal
  "Temporal operations for policy evaluation"
  (:require [clojure.string :as str])
  (:import (org.slf4j LoggerFactory)
          (java.time LocalDate Period)))

(defonce logger (LoggerFactory/getLogger "autho.temporal"))

;; ==============================================================================
;; Date Parsing
;; ==============================================================================

(defn parse-date-safe
  "Safely parse a date string (yyyy-MM-dd format).
   Returns LocalDate or nil if parsing fails."
  [date-str]
  (try
    (LocalDate/parse date-str)
    (catch Exception e
      (.warn logger (str "Failed to parse date '" date-str "' - " (.getMessage e)))
      nil)))

(defn get-current-date
  "Get current date as LocalDate"
  []
  (LocalDate/now))

;; ==============================================================================
;; Temporal Operators
;; ==============================================================================

(defn after
  "Check if date1 is after date2. Returns true if date1 > date2."
  [date1 date2]
  (try
    (let [d1 (parse-date-safe date1)
          d2 (parse-date-safe date2)]
      (and d1 d2 (.isAfter d1 d2)))
    (catch Exception e
      (.error logger (str "Error in 'after' comparison - " (.getMessage e)))
      false)))

(defn before
  "Check if date1 is before date2. Returns true if date1 < date2."
  [date1 date2]
  (try
    (let [d1 (parse-date-safe date1)
          d2 (parse-date-safe date2)]
      (and d1 d2 (.isBefore d1 d2)))
    (catch Exception e
      (.error logger (str "Error in 'before' comparison - " (.getMessage e)))
      false)))

(defn between
  "Check if date is between start and end (inclusive). Returns true if start <= date <= end."
  [date start end]
  (try
    (let [d (parse-date-safe date)
          s (parse-date-safe start)
          e (parse-date-safe end)]
      (and d s e (not (.isBefore d s)) (not (.isAfter d e))))
    (catch Exception e
      (.error logger (str "Error in 'between' comparison - " (.getMessage e)))
      false)))

;; ==============================================================================
;; Relative Time Operations
;; ==============================================================================

(defn parse-days-duration
  "Parse a days duration string like '7d', '-30d', '90d'. Returns a Period object."
  [duration-str]
  (try
    (when (and (string? duration-str)
               (re-matches #"-?\d+d" duration-str))
      (let [days (Integer/parseInt (str/replace duration-str "d" ""))]
        (Period/ofDays days)))
    (catch Exception e
      (.error logger (str "Failed to parse duration '" duration-str "' - " (.getMessage e)))
      nil)))

(defn within
  "Check if a date is within a time window from now. Returns true if the date is within the specified duration from the current time."
  [date duration]
  (try
    (let [d (parse-date-safe date)
          parsed-duration (parse-days-duration duration)
          now (get-current-date)
          ;; Calculate target date based on duration
          target-date (if (.startsWith duration "-")
                        (.minus now parsed-duration)
                        (.plus now parsed-duration))]
      (and d target-date (.isEqual d target-date)))
    (catch Exception e
      (.error logger (str "Error in 'within' check - " (.getMessage e)))
      false)))

(defn older
  "Check if a date is older than a specified duration. Returns true if the date is older than the specified duration from now."
  [date duration]
  (try
    (let [d (parse-date-safe date)
          parsed-duration (parse-days-duration duration)
          now (get-current-date)
          cutoff-date (.minus now parsed-duration)]
      (and d (.isBefore d cutoff-date)))
    (catch Exception e
      (.error logger (str "Error in 'older' check - " (.getMessage e)))
      false)))

;; ==============================================================================
;; Business Time Helpers
;; ==============================================================================

(defn is-weekend
  "Check if a date falls on a weekend (Saturday or Sunday). Returns true if the date is a weekend day."
  [date-str]
  (try
    (let [date (parse-date-safe date-str)
          day-of-week (.getDayOfWeek date)]
      (or (= day-of-week java.time.DayOfWeek/SATURDAY)
          (= day-of-week java.time.DayOfWeek/SUNDAY)))
    (catch Exception e
      (.error logger (str "Error checking if date is weekend - " (.getMessage e)))
      false)))

(defn days-between
  "Calculate days between two date strings. Returns the number of days as a long."
  [start-date end-date]
  (try
    (let [start (parse-date-safe start-date)
          end (parse-date-safe end-date)]
      (when (and start end)
        (.until start end java.time.temporal.ChronoUnit/DAYS)))
    (catch Exception e
      (.error logger (str "Error calculating days between - " (.getMessage e)))
      0)))

(defn is-weekday-in-range
  "Check if a date is a weekday within the specified range (Monday=1, Friday=5). Returns true if the day is Monday-Friday and within range."
  [date min-day max-day]
  (try
    (let [d (parse-date-safe date)
          day-of-week (.getDayOfWeek d)
          day-value (.getValue day-of-week)]
      (and (not (is-weekend date))
           (>= day-value min-day)
           (<= day-value max-day)))
    (catch Exception e
      (.error logger (str "Error checking weekday range - " (.getMessage e)))
      false)))

(defn get-day-of-week
  "Get the day of week as a number (1=Monday, 7=Sunday)."
  [date-str]
  (try
    (let [date (parse-date-safe date-str)
          day-of-week (.getDayOfWeek date)]
      (.getValue day-of-week))
    (catch Exception e
      (.error logger (str "Error getting day of week - " (.getMessage e)))
      0)))

(defn is-business-day
  "Check if a date is a business day (Monday-Friday, excluding holidays). Returns true if the date is Monday-Friday."
  [date-str]
  (try
    (not (is-weekend date-str))
    (catch Exception e
      (.error logger (str "Error checking if business day - " (.getMessage e)))
      false)))

(defn add-days
  "Add specified number of days to a date. Returns new date as string in yyyy-MM-dd format."
  [date-str days]
  (try
    (let [date (parse-date-safe date-str)]
      (when date
        (.format date (.plus date (Period/ofDays days)))))
    (catch Exception e
      (.error logger (str "Error adding days to date - " (.getMessage e)))
      nil)))
