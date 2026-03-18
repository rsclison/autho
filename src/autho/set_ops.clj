(ns autho.set-ops
  "Set operations for policy evaluation"
  (:require [clojure.string :as str]
            [clojure.set :as set])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.set-ops"))

;; =============================================================================
;; Set Operations
;; =============================================================================

(defn parse-set
  "Parse a comma-separated string into a set of strings. Returns nil if input is nil or empty."
  [set-str]
  (when (and set-str (not (str/blank? set-str)))
    (into #{} (str/split set-str #"\s*,\s*"))))

(defn intersect
  "Check if two sets have any common elements. Returns true if there is at least one element in common."
  [set1 set2]
  (try
    (let [s1 (if (string? set1) (parse-set set1) set1)
          s2 (if (string? set2) (parse-set set2) set2)]
      (if (and s1 s2)
        (not (empty? (set/intersection s1 s2)))
        false))
    (catch Exception e
      (.error logger (str "Error in 'intersect' - " (.getMessage e)))
      false)))

(defn isSubset
  "Check if set1 is a subset of set2. Returns true if all elements of set1 are in set2."
  [set1 set2]
  (try
    (let [s1 (if (string? set1) (parse-set set1) set1)
          s2 (if (string? set2) (parse-set set2) set2)]
      (if (and s1 s2)
        (set/subset? s1 s2)
        false))
    (catch Exception e
      (.error logger (str "Error in 'isSubset' - " (.getMessage e)))
      false)))

(defn isSuperset
  "Check if set1 is a superset of set2. Returns true if all elements of set2 are in set1."
  [set1 set2]
  (try
    (let [s1 (if (string? set1) (parse-set set1) set1)
          s2 (if (string? set2) (parse-set set2) set2)]
      (if (and s1 s2)
        (set/superset? s1 s2)
        false))
    (catch Exception e
      (.error logger (str "Error in 'isSuperset' - " (.getMessage e)))
      false)))

(defn equals
  "Check if two sets are equal (contain the same elements). Returns true if both sets have exactly the same elements."
  [set1 set2]
  (try
    (let [s1 (if (string? set1)
               (if (str/blank? set1) #{} (parse-set set1))
               set1)
          s2 (if (string? set2)
               (if (str/blank? set2) #{} (parse-set set2))
               set2)]
      (if (and s1 s2)
        (= s1 s2)
        false))
    (catch Exception e
      (.error logger (str "Error in 'equals' - " (.getMessage e)))
      false)))
