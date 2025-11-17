(ns autho.kafka-pip-test
  (:require [clojure.test :refer :all]
            [autho.kafka-pip :as sut]
            [jsonista.core :as json])
  (:import [org.rocksdb RocksDB Options ColumnFamilyDescriptor ColumnFamilyHandle ColumnFamilyOptions]
           [java.nio.charset StandardCharsets]
           [java.util ArrayList]))

(def test-db-path "/tmp/rocksdb-test")

(defn cleanup-test-db []
  (let [dir (clojure.java.io/file test-db-path)]
    (when (.exists dir)
      (doseq [file (.listFiles dir)]
        (.delete file))
      (.delete dir))))

(use-fixtures :each
  (fn [f]
    (cleanup-test-db)
    (f)
    (cleanup-test-db)))

;; =============================================================================
;; Unit Tests - RocksDB State Management
;; =============================================================================

(deftest rocksdb-initialization-test
  (testing "RocksDB database opens successfully with multiple column families"
    (let [class-names ["user" "resource"]]
      (sut/open-shared-db test-db-path class-names)
      (is (not (nil? (:db-instance @sut/db-state))))
      (is (= 2 (count (:cf-handles @sut/db-state))))
      (sut/close-shared-db))))

(deftest rocksdb-column-family-operations-test
  (testing "Can write and read from column families"
    (sut/open-shared-db test-db-path ["user"])
    (let [db-instance (:db-instance @sut/db-state)
          cf-handle (get (:cf-handles @sut/db-state) "user")
          test-key "user123"
          test-value (json/write-value-as-string {:name "Alice" :role "manager"})]

      ;; Write to RocksDB
      (.put db-instance cf-handle
            (.getBytes test-key StandardCharsets/UTF_8)
            (.getBytes test-value StandardCharsets/UTF_8))

      ;; Read from RocksDB
      (let [retrieved (.get db-instance cf-handle (.getBytes test-key StandardCharsets/UTF_8))
            retrieved-str (String. retrieved StandardCharsets/UTF_8)
            retrieved-data (json/read-value retrieved-str)]
        (is (= "Alice" (get retrieved-data :name)))
        (is (= "manager" (get retrieved-data :role)))))
    (sut/close-shared-db)))

;; =============================================================================
;; Unit Tests - JSON Merge Logic
;; =============================================================================

(deftest json-merge-logic-test
  (testing "New attributes overwrite existing ones (merge-on-read)"
    (sut/open-shared-db test-db-path ["user"])
    (let [db-instance (:db-instance @sut/db-state)
          cf-handle (get (:cf-handles @sut/db-state) "user")
          key "user456"]

      ;; Initial state
      (let [initial-attrs {:name "Bob" :role "developer" :team "backend"}
            initial-json (json/write-value-as-string initial-attrs)]
        (.put db-instance cf-handle
              (.getBytes key StandardCharsets/UTF_8)
              (.getBytes initial-json StandardCharsets/UTF_8)))

      ;; Simulate update from Kafka (role changed, new attribute added)
      (let [existing-bytes (.get db-instance cf-handle (.getBytes key StandardCharsets/UTF_8))
            existing-str (String. existing-bytes StandardCharsets/UTF_8)
            existing-attrs (json/read-value existing-str)

            new-attrs {:role "senior-developer" :location "Paris"}
            merged-attrs (merge existing-attrs new-attrs)
            merged-json (json/write-value-as-string merged-attrs)]

        ;; Write merged result
        (.put db-instance cf-handle
              (.getBytes key StandardCharsets/UTF_8)
              (.getBytes merged-json StandardCharsets/UTF_8))

        ;; Verify merge
        (let [final-bytes (.get db-instance cf-handle (.getBytes key StandardCharsets/UTF_8))
              final-str (String. final-bytes StandardCharsets/UTF_8)
              final-attrs (json/read-value final-str)]
          (is (= "Bob" (get final-attrs :name)))          ;; Preserved
          (is (= "senior-developer" (get final-attrs :role)))  ;; Updated
          (is (= "backend" (get final-attrs :team)))       ;; Preserved
          (is (= "Paris" (get final-attrs :location))))))  ;; Added
    (sut/close-shared-db)))

(deftest json-merge-null-handling-test
  (testing "First message creates initial state (no existing value)"
    (sut/open-shared-db test-db-path ["user"])
    (let [db-instance (:db-instance @sut/db-state)
          cf-handle (get (:cf-handles @sut/db-state) "user")
          key "user789"
          new-attrs {:name "Charlie" :role "manager"}

          ;; Simulate process-record logic for first message
          existing-bytes (.get db-instance cf-handle (.getBytes key StandardCharsets/UTF_8))
          merged-attrs (if (nil? existing-bytes)
                         new-attrs
                         (merge (json/read-value (String. existing-bytes StandardCharsets/UTF_8)) new-attrs))
          merged-json (json/write-value-as-string merged-attrs)]

      (.put db-instance cf-handle
            (.getBytes key StandardCharsets/UTF_8)
            (.getBytes merged-json StandardCharsets/UTF_8))

      ;; Verify
      (let [final-bytes (.get db-instance cf-handle (.getBytes key StandardCharsets/UTF_8))
            final-str (String. final-bytes StandardCharsets/UTF_8)
            final-attrs (json/read-value final-str)]
        (is (= "Charlie" (get final-attrs :name)))
        (is (= "manager" (get final-attrs :role)))))
    (sut/close-shared-db)))

;; =============================================================================
;; Unit Tests - Query PIP
;; =============================================================================

(deftest query-pip-test
  (testing "query-pip retrieves attributes from RocksDB"
    (sut/open-shared-db test-db-path ["user"])
    (let [db-instance (:db-instance @sut/db-state)
          cf-handle (get (:cf-handles @sut/db-state) "user")
          user-id "user999"
          attrs {:name "Diana" :role "admin" :department "IT"}
          attrs-json (json/write-value-as-string attrs)]

      ;; Populate RocksDB
      (.put db-instance cf-handle
            (.getBytes user-id StandardCharsets/UTF_8)
            (.getBytes attrs-json StandardCharsets/UTF_8))

      ;; Query via PIP
      (let [result (sut/query-pip "user" user-id)]
        (is (not (nil? result)))
        (is (= "Diana" (get result :name)))
        (is (= "admin" (get result :role)))
        (is (= "IT" (get result :department)))))
    (sut/close-shared-db)))

(deftest query-pip-nonexistent-key-test
  (testing "query-pip returns nil for non-existent key"
    (sut/open-shared-db test-db-path ["user"])
    (let [result (sut/query-pip "user" "nonexistent-user")]
      (is (nil? result)))
    (sut/close-shared-db)))

(deftest query-pip-invalid-class-test
  (testing "query-pip returns nil for invalid class name"
    (sut/open-shared-db test-db-path ["user"])
    (let [result (sut/query-pip "invalid-class" "user123")]
      (is (nil? result)))
    (sut/close-shared-db)))

;; =============================================================================
;; Unit Tests - Column Family Management
;; =============================================================================

(deftest list-column-families-test
  (testing "list-column-families returns all class names"
    (sut/open-shared-db test-db-path ["user" "resource" "organization"])
    (let [cf-list (sut/list-column-families)]
      (is (= 3 (count cf-list)))
      (is (contains? (set cf-list) "user"))
      (is (contains? (set cf-list) "resource"))
      (is (contains? (set cf-list) "organization")))
    (sut/close-shared-db)))

(deftest clear-column-family-test
  (testing "clear-column-family removes all entries for a class"
    (sut/open-shared-db test-db-path ["user"])
    (let [db-instance (:db-instance @sut/db-state)
          cf-handle (get (:cf-handles @sut/db-state) "user")]

      ;; Populate with multiple entries
      (doseq [i (range 5)]
        (let [key (str "user" i)
              attrs {:name (str "User" i) :id i}
              attrs-json (json/write-value-as-string attrs)]
          (.put db-instance cf-handle
                (.getBytes key StandardCharsets/UTF_8)
                (.getBytes attrs-json StandardCharsets/UTF_8))))

      ;; Verify data exists
      (is (not (nil? (sut/query-pip "user" "user2"))))

      ;; Clear column family
      (sut/clear-column-family "user")

      ;; Verify all data cleared
      (doseq [i (range 5)]
        (is (nil? (sut/query-pip "user" (str "user" i))))))
    (sut/close-shared-db)))

;; =============================================================================
;; Unit Tests - Error Handling
;; =============================================================================

(deftest malformed-json-handling-test
  (testing "Handles malformed JSON gracefully"
    (sut/open-shared-db test-db-path ["user"])
    (let [db-instance (:db-instance @sut/db-state)
          cf-handle (get (:cf-handles @sut/db-state) "user")
          key "user-malformed"
          malformed-json "{ invalid json }"]

      ;; Write malformed JSON directly to RocksDB
      (.put db-instance cf-handle
            (.getBytes key StandardCharsets/UTF_8)
            (.getBytes malformed-json StandardCharsets/UTF_8))

      ;; Attempt to query - should handle error gracefully
      (let [result (try
                     (sut/query-pip "user" key)
                     (catch Exception e
                       :error))]
        ;; Depending on implementation, should either return nil or throw
        ;; Current implementation will throw, which is acceptable
        (is (or (nil? result) (= :error result)))))
    (sut/close-shared-db)))
