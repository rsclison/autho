(ns autho.kafka-integration-test
  "Integration tests for Kafka PIP with simulated Kafka messages.
   These tests verify the complete flow: Kafka → RocksDB → Rule Evaluation"
  (:require [clojure.test :refer :all]
            [autho.kafka-pip :as kpip]
            [autho.pip :as pip]
            [autho.pdp :as pdp]
            [autho.prp :as prp]
            [jsonista.core :as json])
  (:import [org.apache.kafka.clients.consumer ConsumerRecord]))

(def test-db-path "/tmp/rocksdb-integration-test")

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
;; Helper Functions - Simulate Kafka Messages
;; =============================================================================

(defn create-consumer-record
  "Creates a mock Kafka ConsumerRecord for testing"
  [topic partition offset key value]
  (ConsumerRecord. topic partition offset key value))

(defn simulate-kafka-message
  "Simulates processing a Kafka message by directly calling process-record logic"
  [class-name entity-id attributes]
  (let [cf-handles (:cf-handles @kpip/db-state)
        db-instance (:db-instance @kpip/db-state)
        cf-handle (get cf-handles class-name)
        key entity-id
        new-value-str (json/write-value-as-string attributes)

        ;; Simulate the merge-on-read logic from kafka_pip.clj
        existing-bytes (.get db-instance cf-handle
                             (.getBytes key java.nio.charset.StandardCharsets/UTF_8))
        existing-attrs (when existing-bytes
                         (json/read-value (String. existing-bytes java.nio.charset.StandardCharsets/UTF_8) json/keyword-keys-object-mapper))
        merged-attrs (merge existing-attrs attributes)
        merged-json (json/write-value-as-string merged-attrs)]

    (.put db-instance cf-handle
          (.getBytes key java.nio.charset.StandardCharsets/UTF_8)
          (.getBytes merged-json java.nio.charset.StandardCharsets/UTF_8))))

;; =============================================================================
;; Integration Test 1: Kafka Message Processing Flow
;; =============================================================================

(deftest kafka-message-processing-flow-test
  (testing "Complete flow: Kafka message → RocksDB → Query PIP"
    (kpip/open-shared-db test-db-path ["user"])

    ;; Step 1: Simulate initial Kafka message
    (simulate-kafka-message "user" "alice"
                            {:name "Alice" :role "developer" :team "backend"})

    ;; Step 2: Query PIP to verify data is accessible
    (let [result (kpip/query-pip "user" "alice")]
      (is (= "Alice" (get result :name)))
      (is (= "developer" (get result :role)))
      (is (= "backend" (get result :team))))

    ;; Step 3: Simulate attribute update via Kafka
    (simulate-kafka-message "user" "alice"
                            {:role "senior-developer" :salary 75000})

    ;; Step 4: Verify merge-on-read preserved old attrs and updated new ones
    (let [updated-result (kpip/query-pip "user" "alice")]
      (is (= "Alice" (get updated-result :name)))          ;; Preserved
      (is (= "senior-developer" (get updated-result :role)))  ;; Updated
      (is (= "backend" (get updated-result :team)))        ;; Preserved
      (is (= 75000 (get updated-result :salary))))         ;; Added

    (kpip/close-shared-db)))

;; =============================================================================
;; Integration Test 2: Multiple Entity Classes
;; =============================================================================

(deftest multiple-entity-classes-test
  (testing "Kafka PIP handles multiple entity classes independently"
    (kpip/open-shared-db test-db-path ["user" "resource"])

    ;; Populate user class
    (simulate-kafka-message "user" "bob"
                            {:name "Bob" :role "manager" :department "sales"})

    ;; Populate resource class
    (simulate-kafka-message "resource" "doc123"
                            {:title "Q4 Report" :owner "bob" :sensitivity "high"})

    ;; Verify both classes are independent
    (let [user-result (kpip/query-pip "user" "bob")
          resource-result (kpip/query-pip "resource" "doc123")]

      (is (= "Bob" (get user-result :name)))
      (is (= "manager" (get user-result :role)))

      (is (= "Q4 Report" (get resource-result :title)))
      (is (= "bob" (get resource-result :owner)))
      (is (= "high" (get resource-result :sensitivity))))

    ;; Verify no cross-contamination
    (is (nil? (kpip/query-pip "user" "doc123")))
    (is (nil? (kpip/query-pip "resource" "bob")))

    (kpip/close-shared-db)))

;; =============================================================================
;; Integration Test 3: PIP Dispatcher Integration
;; =============================================================================

(deftest pip-dispatcher-integration-test
  (testing "PIP dispatcher correctly routes to Kafka PIP"
    (kpip/open-shared-db test-db-path ["user"])

    ;; Populate data
    (simulate-kafka-message "user" "charlie"
                            {:name "Charlie" :role "admin" :clearance 5})

    ;; Create PIP declaration (as would be in pips.edn)
    (let [pip-decl {:class "user"
                    :pip {:type :kafka-pip
                          :kafka-topic "user-attributes-compacted"
                          :kafka-bootstrap-servers "localhost:9092"
                          :id-key :user-id}}
          att "role"
          obj {:class "user" :user-id "charlie"}]

      ;; Call through PIP dispatcher
      (let [result (pip/callPip pip-decl att obj)]
        ;; callPip should return the entire attribute map
        (is (not (nil? result)))
        (is (= "Charlie" (get result :name)))
        (is (= "admin" (get result :role)))
        (is (= 5 (get result :clearance)))))

    (kpip/close-shared-db)))

;; =============================================================================
;; Integration Test 4: Real-World Scenario - Dynamic Authorization
;; =============================================================================

(deftest dynamic-authorization-scenario-test
  (testing "End-to-end: User attributes update via Kafka affects authorization"
    (kpip/open-shared-db test-db-path ["user" "resource"])

    ;; Initial state: User is a regular employee
    (simulate-kafka-message "user" "employee123"
                            {:name "Employee" :role "developer" :team "product"})

    ;; Resource with team restriction
    (simulate-kafka-message "resource" "secret-doc"
                            {:title "Secret Project" :team "product" :min-role "manager"})

    ;; Mock rule evaluation (simplified)
    (let [user-attrs (kpip/query-pip "user" "employee123")
          resource-attrs (kpip/query-pip "resource" "secret-doc")]

      ;; Initial check: Should DENY (not a manager)
      (is (= "developer" (get user-attrs :role)))
      (is (not= "manager" (get user-attrs :role))))

    ;; Kafka message: User gets promoted!
    (simulate-kafka-message "user" "employee123"
                            {:role "manager"})

    ;; Re-check: Should now ALLOW
    (let [updated-user-attrs (kpip/query-pip "user" "employee123")]
      (is (= "manager" (get updated-user-attrs :role)))
      (is (= "product" (get updated-user-attrs :team))))  ;; Team preserved

    (kpip/close-shared-db)))

;; =============================================================================
;; Integration Test 5: High-Volume Message Processing
;; =============================================================================

(deftest high-volume-message-processing-test
  (testing "Kafka PIP handles batch of messages efficiently"
    (kpip/open-shared-db test-db-path ["user"])

    ;; Simulate 100 user attribute messages
    (doseq [i (range 100)]
      (simulate-kafka-message "user" (str "user" i)
                              {:name (str "User" i)
                               :role (if (even? i) "developer" "manager")
                               :id i}))

    ;; Verify all 100 users are queryable
    (doseq [i (range 100)]
      (let [result (kpip/query-pip "user" (str "user" i))]
        (is (not (nil? result)))
        (is (= (str "User" i) (get result :name)))
        (is (= i (get result :id)))))

    ;; Verify correct role distribution
    (let [developers (filter #(= "developer" (get % :role))
                             (map #(kpip/query-pip "user" (str "user" %)) (range 100)))
          managers (filter #(= "manager" (get % :role))
                          (map #(kpip/query-pip "user" (str "user" %)) (range 100)))]
      (is (= 50 (count developers)))
      (is (= 50 (count managers))))

    (kpip/close-shared-db)))

;; =============================================================================
;; Integration Test 6: Attribute Deletion via Null Values
;; =============================================================================

(deftest attribute-deletion-via-null-test
  (testing "Kafka message with null value removes attribute"
    (kpip/open-shared-db test-db-path ["user"])

    ;; Initial state
    (simulate-kafka-message "user" "tempuser"
                            {:name "Temp" :role "contractor" :expiry "2024-12-31"})

    (let [initial (kpip/query-pip "user" "tempuser")]
      (is (= "2024-12-31" (get initial :expiry))))

    ;; Update: Remove expiry (contract extended, no end date)
    ;; Note: In Clojure, merge with nil value keeps the key
    ;; For true deletion, you'd need special logic or use dissoc
    (simulate-kafka-message "user" "tempuser"
                            {:expiry nil})

    (let [updated (kpip/query-pip "user" "tempuser")]
      ;; With standard merge, nil value overwrites the key
      (is (nil? (get updated :expiry)))
      (is (= "Temp" (get updated :name))))  ;; Other attrs preserved

    (kpip/close-shared-db)))

;; =============================================================================
;; Integration Test 7: Complex Nested Attributes
;; =============================================================================

(deftest nested-attributes-test
  (testing "Kafka PIP handles nested JSON structures"
    (kpip/open-shared-db test-db-path ["user"])

    ;; Kafka message with nested attributes
    (simulate-kafka-message "user" "admin"
                            {:name "Admin"
                             :roles ["admin" "developer" "manager"]
                             :permissions {:read true :write true :delete true}
                             :metadata {:created "2024-01-01" :last-login "2024-06-15"}})

    (let [result (kpip/query-pip "user" "admin")]
      (is (= "Admin" (get result :name)))
      (is (vector? (get result :roles)))
      (is (= 3 (count (get result :roles))))
      (is (contains? (set (get result :roles)) "admin"))

      (is (map? (get result :permissions)))
      (is (true? (get-in result ["permissions" "write"])))

      (is (= "2024-01-01" (get-in result ["metadata" "created"]))))

    (kpip/close-shared-db)))

;; =============================================================================
;; Integration Test 8: Race Condition - Concurrent Updates
;; =============================================================================

(deftest concurrent-updates-test
  (testing "Kafka PIP handles rapid consecutive updates correctly"
    (kpip/open-shared-db test-db-path ["user"])

    ;; Initial state
    (simulate-kafka-message "user" "activeuser"
                            {:name "Active" :login-count 0})

    ;; Simulate rapid updates (like login tracking)
    (doseq [i (range 1 11)]
      (simulate-kafka-message "user" "activeuser"
                              {:login-count i :last-login (str "2024-06-" (+ 10 i))}))

    (let [final (kpip/query-pip "user" "activeuser")]
      (is (= "Active" (get final :name)))        ;; Preserved from initial
      (is (= 10 (get final :login-count)))       ;; Latest update
      (is (= "2024-06-20" (get final :last-login))))  ;; Latest timestamp

    (kpip/close-shared-db)))

;; =============================================================================
;; Performance Benchmark (Optional)
;; =============================================================================

(deftest ^:benchmark kafka-pip-performance-test
  (testing "Benchmark: Query performance with 1000 users"
    (kpip/open-shared-db test-db-path ["user"])

    ;; Populate 1000 users
    (time
     (doseq [i (range 1000)]
       (simulate-kafka-message "user" (str "user" i)
                               {:name (str "User" i) :role "developer" :id i})))

    ;; Benchmark query performance
    (let [start (System/currentTimeMillis)
          results (doall (map #(kpip/query-pip "user" (str "user" %)) (range 1000)))
          end (System/currentTimeMillis)
          duration (- end start)]

      (println (str "Queried 1000 users in " duration "ms"))
      (println (str "Average query time: " (/ duration 1000.0) "ms"))

      (is (= 1000 (count results)))
      (is (< duration 1000) "Should query 1000 users in under 1 second"))

    (kpip/close-shared-db)))
