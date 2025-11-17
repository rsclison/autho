(ns autho.kafka-test-producer
  "Utility for producing test messages to Kafka for integration testing.
   Use this to populate Kafka topics with realistic test data."
  (:require [jsonista.core :as json])
  (:import [org.apache.kafka.clients.producer KafkaProducer ProducerConfig ProducerRecord]
           [org.apache.kafka.common.serialization StringSerializer]
           [java.util Properties]))

;; =============================================================================
;; Kafka Producer Configuration
;; =============================================================================

(defn create-test-producer
  "Creates a Kafka producer for testing with string serializers"
  [bootstrap-servers]
  (let [props (Properties.)]
    (.put props ProducerConfig/BOOTSTRAP_SERVERS_CONFIG bootstrap-servers)
    (.put props ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG StringSerializer)
    (.put props ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG StringSerializer)
    (.put props ProducerConfig/ACKS_CONFIG "all")
    (.put props ProducerConfig/RETRIES_CONFIG (int 3))
    (KafkaProducer. props)))

;; =============================================================================
;; Test Data Generators
;; =============================================================================

(def test-users
  "Sample user test data"
  [{:id "alice"
    :attrs {:name "Alice Smith" :role "developer" :team "backend" :department "engineering"
            :clearance-level 2 :location "paris" :salary 65000}}

   {:id "bob"
    :attrs {:name "Bob Johnson" :role "manager" :team "product" :department "product"
            :clearance-level 3 :location "london" :salary 85000 :approval-limit 10000}}

   {:id "charlie"
    :attrs {:name "Charlie Davis" :role "admin" :team "ops" :department "operations"
            :clearance-level 5 :location "paris" :salary 95000 :can-delegate true}}

   {:id "diana"
    :attrs {:name "Diana Martinez" :role "contractor" :team "backend" :department "engineering"
            :clearance-level 1 :location "madrid" :access-expiry "2024-12-31" :status "active"}}

   {:id "eve"
    :attrs {:name "Eve Wilson" :role "senior-manager" :team "product" :department "product"
            :clearance-level 4 :location "london" :salary 120000 :approval-limit 50000
            :can-delegate true :max-delegation-level "manager"}}])

(def test-resources
  "Sample resource test data"
  [{:id "doc-001"
    :attrs {:title "Q1 Financial Report" :type "document" :department "finance"
            :classification "confidential" :owner "bob" :required-clearance 3}}

   {:id "db-prod"
    :attrs {:name "Production Database" :type "database" :department "operations"
            :required-role "admin" :sensitivity "critical" :requires-vpn true}}

   {:id "project-alpha"
    :attrs {:name "Project Alpha" :type "project" :team "product" :department "product"
            :required-clearance 2 :allowed-locations ["paris" "london"]}}

   {:id "purchase-req-5k"
    :attrs {:type "purchase-request" :amount 5000 :department "product" :status "pending"}}

   {:id "purchase-req-50k"
    :attrs {:type "purchase-request" :amount 50000 :department "operations" :status "pending"}}

   {:id "patient-record-123"
    :attrs {:type "patient-record" :department "cardiology" :hospital "central-hospital"
            :sensitivity "high" :requires-verification true}}])

;; =============================================================================
;; Message Publishing Functions
;; =============================================================================

(defn publish-message
  "Publishes a single message to Kafka topic"
  [producer topic key value-map]
  (let [value-str (json/write-value-as-string value-map)
        record (ProducerRecord. topic key value-str)]
    (.send producer record)
    (println (str "Published: " key " -> " value-str))))

(defn publish-test-users
  "Publishes all test users to user-attributes topic"
  [producer topic]
  (println (str "\n=== Publishing " (count test-users) " test users to " topic " ==="))
  (doseq [{:keys [id attrs]} test-users]
    (publish-message producer topic id attrs))
  (.flush producer)
  (println "✓ User publishing complete"))

(defn publish-test-resources
  "Publishes all test resources to resource-attributes topic"
  [producer topic]
  (println (str "\n=== Publishing " (count test-resources) " test resources to " topic " ==="))
  (doseq [{:keys [id attrs]} test-resources]
    (publish-message producer topic id attrs))
  (.flush producer)
  (println "✓ Resource publishing complete"))

;; =============================================================================
;; Scenario Generators
;; =============================================================================

(defn simulate-user-promotion
  "Simulates a user promotion scenario"
  [producer topic user-id new-role new-salary]
  (println (str "\n=== Simulating promotion: " user-id " -> " new-role " ==="))
  (publish-message producer topic user-id {:role new-role :salary new-salary})
  (.flush producer)
  (println "✓ Promotion published"))

(defn simulate-access-revocation
  "Simulates revoking user access"
  [producer topic user-id]
  (println (str "\n=== Simulating access revocation: " user-id " ==="))
  (publish-message producer topic user-id {:status "revoked" :access-expiry "2024-06-01"})
  (.flush producer)
  (println "✓ Revocation published"))

(defn simulate-team-reorganization
  "Simulates team reorganization (multiple users change teams)"
  [producer topic user-team-map]
  (println "\n=== Simulating team reorganization ===")
  (doseq [[user-id new-team] user-team-map]
    (publish-message producer topic user-id {:team new-team}))
  (.flush producer)
  (println "✓ Reorganization published"))

(defn simulate-clearance-upgrade
  "Simulates clearance level upgrade"
  [producer topic user-id new-clearance]
  (println (str "\n=== Simulating clearance upgrade: " user-id " -> Level " new-clearance " ==="))
  (publish-message producer topic user-id {:clearance-level new-clearance})
  (.flush producer)
  (println "✓ Clearance upgrade published"))

;; =============================================================================
;; Load Testing
;; =============================================================================

(defn generate-bulk-users
  "Generates N user records for load testing"
  [n]
  (for [i (range n)]
    {:id (str "user" i)
     :attrs {:name (str "User" i)
             :role (rand-nth ["developer" "manager" "admin" "contractor"])
             :team (rand-nth ["backend" "frontend" "product" "ops"])
             :department (rand-nth ["engineering" "product" "operations" "finance"])
             :clearance-level (rand-int 5)
             :location (rand-nth ["paris" "london" "madrid" "berlin"])
             :salary (+ 50000 (rand-int 100000))}}))

(defn publish-bulk-data
  "Publishes bulk data for load testing"
  [producer topic num-records]
  (println (str "\n=== Publishing " num-records " bulk records to " topic " ==="))
  (let [bulk-users (generate-bulk-users num-records)
        start (System/currentTimeMillis)]

    (doseq [{:keys [id attrs]} bulk-users]
      (publish-message producer topic id attrs)
      (when (zero? (mod (Integer/parseInt (subs id 4)) 100))
        (println (str "Progress: " id))))

    (.flush producer)
    (let [end (System/currentTimeMillis)
          duration (- end start)]
      (println (str "✓ Published " num-records " records in " duration "ms"))
      (println (str "Throughput: " (/ num-records (/ duration 1000.0)) " msgs/sec")))))

;; =============================================================================
;; Main Test Scenarios
;; =============================================================================

(defn run-basic-test-scenario
  "Runs a basic test scenario with initial data"
  [bootstrap-servers user-topic resource-topic]
  (println "\n╔══════════════════════════════════════════════════════════╗")
  (println "║         Kafka Test Producer - Basic Scenario            ║")
  (println "╚══════════════════════════════════════════════════════════╝")

  (let [producer (create-test-producer bootstrap-servers)]
    (try
      (publish-test-users producer user-topic)
      (publish-test-resources producer resource-topic)

      (println "\n✓ Basic test scenario complete!")
      (println (str "Users published to: " user-topic))
      (println (str "Resources published to: " resource-topic))

      (finally
        (.close producer)))))

(defn run-dynamic-update-scenario
  "Runs a scenario with dynamic attribute updates"
  [bootstrap-servers user-topic]
  (println "\n╔══════════════════════════════════════════════════════════╗")
  (println "║      Kafka Test Producer - Dynamic Update Scenario      ║")
  (println "╚══════════════════════════════════════════════════════════╝")

  (let [producer (create-test-producer bootstrap-servers)]
    (try
      ;; Initial data
      (publish-test-users producer user-topic)

      (Thread/sleep 2000)

      ;; Simulate various updates
      (simulate-user-promotion producer user-topic "alice" "senior-developer" 75000)
      (Thread/sleep 1000)

      (simulate-clearance-upgrade producer user-topic "alice" 3)
      (Thread/sleep 1000)

      (simulate-team-reorganization producer user-topic
                                    {"bob" "engineering"
                                     "diana" "product"})
      (Thread/sleep 1000)

      (simulate-access-revocation producer user-topic "diana")

      (println "\n✓ Dynamic update scenario complete!")

      (finally
        (.close producer)))))

(defn run-load-test-scenario
  "Runs a load test scenario with bulk data"
  [bootstrap-servers user-topic num-records]
  (println "\n╔══════════════════════════════════════════════════════════╗")
  (println "║        Kafka Test Producer - Load Test Scenario         ║")
  (println "╚══════════════════════════════════════════════════════════╝")

  (let [producer (create-test-producer bootstrap-servers)]
    (try
      (publish-bulk-data producer user-topic num-records)

      (println (str "\n✓ Load test complete! Published " num-records " records."))

      (finally
        (.close producer)))))

;; =============================================================================
;; CLI Entry Points
;; =============================================================================

(defn -main
  "Main entry point for test data producer"
  [& args]
  (let [bootstrap-servers (or (first args) "localhost:9092")
        user-topic (or (second args) "user-attributes-compacted")
        resource-topic (or (nth args 2 nil) "resource-attributes-compacted")
        scenario (or (nth args 3 nil) "basic")]

    (case scenario
      "basic"
      (run-basic-test-scenario bootstrap-servers user-topic resource-topic)

      "dynamic"
      (run-dynamic-update-scenario bootstrap-servers user-topic)

      "load"
      (let [num-records (Integer/parseInt (or (nth args 4 nil) "1000"))]
        (run-load-test-scenario bootstrap-servers user-topic num-records))

      (do
        (println "Unknown scenario. Available scenarios:")
        (println "  basic   - Publish initial test data")
        (println "  dynamic - Simulate dynamic attribute updates")
        (println "  load    - Load test with bulk data")))))

(comment
  ;; REPL usage examples

  ;; Basic scenario
  (run-basic-test-scenario "localhost:9092"
                           "user-attributes-compacted"
                           "resource-attributes-compacted")

  ;; Dynamic updates
  (run-dynamic-update-scenario "localhost:9092"
                               "user-attributes-compacted")

  ;; Load test with 10,000 records
  (run-load-test-scenario "localhost:9092"
                          "user-attributes-compacted"
                          10000)

  ;; Manual message publishing
  (let [producer (create-test-producer "localhost:9092")]
    (publish-message producer "user-attributes-compacted"
                     "test-user"
                     {:name "Test User" :role "tester"})
    (.close producer))
  )
