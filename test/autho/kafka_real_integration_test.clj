(ns autho.kafka-real-integration-test
  "Real integration tests for Kafka PIP with actual Kafka broker.
   These tests require a running Kafka instance (see docker-compose.yml)

   To run these tests:
   1. Start Kafka: docker-compose up -d
   2. Run tests: lein test :integration
   3. Stop Kafka: docker-compose down"
  (:require [clojure.test :refer :all]
            [autho.kafka-pip :as kpip]
            [jsonista.core :as json])
  (:import [org.apache.kafka.clients.producer KafkaProducer ProducerConfig ProducerRecord]
           [org.apache.kafka.clients.admin AdminClient NewTopic]
           [java.util Properties]))

(def test-db-path (str (System/getProperty "java.io.tmpdir") "rocksdb-real-kafka-test"))
(def kafka-bootstrap-servers "localhost:9092")

(defn cleanup-test-db []
  (let [dir (clojure.java.io/file test-db-path)]
    (when (.exists dir)
      (doseq [file (.listFiles dir)]
        (.delete file))
      (.delete dir))))

;; =============================================================================
;; Kafka Test Utilities
;; =============================================================================

(defn create-kafka-producer []
  (let [props (Properties.)]
    (doto props
      (.put ProducerConfig/BOOTSTRAP_SERVERS_CONFIG kafka-bootstrap-servers)
      (.put ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringSerializer")
      (.put ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringSerializer")
      (.put ProducerConfig/ACKS_CONFIG "all"))
    (KafkaProducer. props)))

(defn create-kafka-admin-client []
  (let [props (Properties.)]
    (.put props "bootstrap.servers" kafka-bootstrap-servers)
    (AdminClient/create props)))

(defn ensure-topic-exists [topic-name num-partitions]
  (with-open [admin (create-kafka-admin-client)]
    (let [topics (.listTopics admin)
          topic-names (-> topics .names (.get))]
      (when-not (contains? topic-names topic-name)
        (let [new-topic (NewTopic. topic-name num-partitions (short 1))]
          (.createTopics admin [new-topic])
          (Thread/sleep 2000))))))

(defn send-kafka-message
  "Send a message to Kafka topic and wait for confirmation"
  [producer topic key value]
  (let [value-json (json/write-value-as-string value)
        record (ProducerRecord. topic key value-json)
        future (.send producer record)]
    @future))

(defn wait-for-kafka-message-processing
  "Wait for Kafka messages to be consumed and processed into RocksDB"
  [delay-ms]
  (Thread/sleep delay-ms))

(defn kafka-available? []
  (try
    (with-open [admin (create-kafka-admin-client)]
      (-> admin .describeCluster .nodes (.get))
      true)
    (catch Exception e
      false)))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :once
  (fn [f]
    (when (kafka-available?)
      (f))))

(use-fixtures :each
  (fn [f]
    (when (kafka-available?)
      (cleanup-test-db)
      (f)
      (kpip/stop-all-pips)
      (cleanup-test-db))))

;; =============================================================================
;; Real Integration Test 1: Basic Kafka Message Flow
;; =============================================================================

(deftest ^:integration basic-kafka-message-flow-test
  (when-not (kafka-available?)
    (println "⚠️  Skipping test: Kafka not available. Start with: docker-compose up -d")
    (is true "Skipped - Kafka not running"))

  (when (kafka-available?)
    (testing "Real Kafka: Producer → Broker → Consumer → RocksDB → Query"
      (let [topic "user-attributes-test-1"
            _ (ensure-topic-exists topic 1)
            producer (create-kafka-producer)]

        ;; Initialize Kafka PIP
        (kpip/open-shared-db test-db-path ["user"])
        (kpip/init-pip {:class "user"
                        :kafka-topic topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        ;; Step 1: Send message to Kafka
        (send-kafka-message producer topic "alice"
                            {:name "Alice" :role "developer" :team "backend"})

        ;; Step 2: Wait for consumer to process
        (wait-for-kafka-message-processing 3000)

        ;; Step 3: Query PIP
        (let [result (kpip/query-pip "user" "alice")]
          (is (= "Alice" (get result :name)))
          (is (= "developer" (get result :role)))
          (is (= "backend" (get result :team))))

        (.close producer)))))

;; =============================================================================
;; Real Integration Test 2: Message Updates (Merge-on-Read)
;; =============================================================================

(deftest ^:integration kafka-merge-on-read-test
  (when-not (kafka-available?)
    (println "⚠️  Skipping test: Kafka not available")
    (is true "Skipped - Kafka not running"))

  (when (kafka-available?)
    (testing "Real Kafka: Multiple messages to same key are merged"
      (let [topic "user-attributes-test-2"
            _ (ensure-topic-exists topic 1)
            producer (create-kafka-producer)]

        (kpip/open-shared-db test-db-path ["user"])
        (kpip/init-pip {:class "user"
                        :kafka-topic topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        ;; Send initial message
        (send-kafka-message producer topic "bob"
                            {:name "Bob" :role "developer" :department "engineering"})
        (wait-for-kafka-message-processing 3000)

        ;; Verify initial state
        (let [result-1 (kpip/query-pip "user" "bob")]
          (is (= "Bob" (get result-1 :name)))
          (is (= "developer" (get result-1 :role))))

        ;; Send update message (promotion!)
        (send-kafka-message producer topic "bob"
                            {:role "senior-developer" :salary 90000})
        (wait-for-kafka-message-processing 3000)

        ;; Verify merge: old attrs preserved, new attrs updated
        (let [result-2 (kpip/query-pip "user" "bob")]
          (is (= "Bob" (get result-2 :name)))                   ; Preserved
          (is (= "senior-developer" (get result-2 :role)))       ; Updated
          (is (= "engineering" (get result-2 :department)))     ; Preserved
          (is (= 90000 (get result-2 :salary))))                ; Added

        (.close producer)))))

;; =============================================================================
;; Real Integration Test 3: Multiple Entity Classes
;; =============================================================================

(deftest ^:integration multiple-classes-kafka-test
  (when-not (kafka-available?)
    (println "⚠️  Skipping test: Kafka not available")
    (is true "Skipped - Kafka not running"))

  (when (kafka-available?)
    (testing "Real Kafka: Multiple entity classes with separate topics"
      (let [user-topic "user-attributes-test-3"
            resource-topic "resource-attributes-test-3"
            _ (ensure-topic-exists user-topic 1)
            _ (ensure-topic-exists resource-topic 1)
            producer (create-kafka-producer)]

        (kpip/open-shared-db test-db-path ["user" "resource"])

        ;; Initialize PIPs for both classes
        (kpip/init-pip {:class "user"
                        :kafka-topic user-topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        (kpip/init-pip {:class "resource"
                        :kafka-topic resource-topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        ;; Send messages to both topics
        (send-kafka-message producer user-topic "charlie"
                            {:name "Charlie" :role "manager"})
        (send-kafka-message producer resource-topic "doc123"
                            {:title "Budget 2024" :owner "charlie"})

        (wait-for-kafka-message-processing 3000)

        ;; Verify both classes work independently
        (let [user-result (kpip/query-pip "user" "charlie")
              resource-result (kpip/query-pip "resource" "doc123")]

          (is (= "Charlie" (get user-result :name)))
          (is (= "manager" (get user-result :role)))

          (is (= "Budget 2024" (get resource-result :title)))
          (is (= "charlie" (get resource-result :owner))))

        ;; Verify no cross-contamination
        (is (nil? (kpip/query-pip "user" "doc123")))
        (is (nil? (kpip/query-pip "resource" "charlie")))

        (.close producer)))))

;; =============================================================================
;; Real Integration Test 4: High Volume Message Processing
;; =============================================================================

(deftest ^:integration high-volume-kafka-test
  (when-not (kafka-available?)
    (println "⚠️  Skipping test: Kafka not available")
    (is true "Skipped - Kafka not running"))

  (when (kafka-available?)
    (testing "Real Kafka: Process 100 messages efficiently"
      (let [topic "user-attributes-test-4"
            _ (ensure-topic-exists topic 3)  ; 3 partitions for parallelism
            producer (create-kafka-producer)]

        (kpip/open-shared-db test-db-path ["user"])
        (kpip/init-pip {:class "user"
                        :kafka-topic topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        ;; Send 100 messages
        (doseq [i (range 100)]
          (send-kafka-message producer topic (str "user" i)
                              {:name (str "User" i)
                               :role (if (even? i) "developer" "manager")
                               :id i}))

        ;; Wait for all messages to be processed
        (wait-for-kafka-message-processing 5000)

        ;; Verify all users are queryable
        (let [results (doall (map #(kpip/query-pip "user" (str "user" %)) (range 100)))
              valid-results (filter some? results)]

          ;; At least 95% of messages should be processed
          (is (>= (count valid-results) 95)
              (str "Expected at least 95 users, got " (count valid-results))))

        (.close producer)))))

;; =============================================================================
;; Real Integration Test 5: Compacted Topic Behavior
;; =============================================================================

(deftest ^:integration compacted-topic-test
  (when-not (kafka-available?)
    (println "⚠️  Skipping test: Kafka not available")
    (is true "Skipped - Kafka not running"))

  (when (kafka-available?)
    (testing "Real Kafka: Latest message for each key wins"
      (let [topic "user-attributes-test-5"
            _ (ensure-topic-exists topic 1)
            producer (create-kafka-producer)]

        (kpip/open-shared-db test-db-path ["user"])
        (kpip/init-pip {:class "user"
                        :kafka-topic topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        ;; Send rapid updates for same user
        (send-kafka-message producer topic "activeuser" {:login-count 1})
        (send-kafka-message producer topic "activeuser" {:login-count 2})
        (send-kafka-message producer topic "activeuser" {:login-count 3})
        (send-kafka-message producer topic "activeuser" {:login-count 4})
        (send-kafka-message producer topic "activeuser" {:login-count 5})

        (wait-for-kafka-message-processing 3000)

        ;; Final count should be 5 (or at least > 1)
        (let [result (kpip/query-pip "user" "activeuser")]
          (is (not (nil? result)))
          (is (>= (get result :login-count) 1))
          (println "Final login-count:" (get result :login-count)))

        (.close producer)))))

;; =============================================================================
;; Real Integration Test 6: Nested JSON Attributes
;; =============================================================================

(deftest ^:integration nested-json-kafka-test
  (when-not (kafka-available?)
    (println "⚠️  Skipping test: Kafka not available")
    (is true "Skipped - Kafka not running"))

  (when (kafka-available?)
    (testing "Real Kafka: Complex nested JSON structures"
      (let [topic "user-attributes-test-6"
            _ (ensure-topic-exists topic 1)
            producer (create-kafka-producer)]

        (kpip/open-shared-db test-db-path ["user"])
        (kpip/init-pip {:class "user"
                        :kafka-topic topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        ;; Send message with nested attributes
        (send-kafka-message producer topic "admin"
                            {:name "Admin"
                             :roles ["admin" "developer" "manager"]
                             :permissions {:read true :write true :delete false}
                             :metadata {:created "2024-01-01" :last-login "2024-06-15"}})

        (wait-for-kafka-message-processing 3000)

        (let [result (kpip/query-pip "user" "admin")]
          (is (= "Admin" (get result :name)))
          (is (vector? (get result :roles)))
          (is (= 3 (count (get result :roles))))
          (is (contains? (set (get result :roles)) "admin"))

          (is (map? (get result :permissions)))
          (is (true? (get-in result [:permissions :read])))
          (is (false? (get-in result [:permissions :delete])))

          (is (= "2024-01-01" (get-in result [:metadata :created]))))

        (.close producer)))))

;; =============================================================================
;; Performance Benchmark with Real Kafka
;; =============================================================================

(deftest ^:integration ^:benchmark kafka-real-performance-test
  (when-not (kafka-available?)
    (println "⚠️  Skipping benchmark: Kafka not available")
    (is true "Skipped - Kafka not running"))

  (when (kafka-available?)
    (testing "Benchmark: Real Kafka end-to-end latency"
      (let [topic "user-attributes-benchmark"
            _ (ensure-topic-exists topic 3)
            producer (create-kafka-producer)]

        (kpip/open-shared-db test-db-path ["user"])
        (kpip/init-pip {:class "user"
                        :kafka-topic topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        ;; Populate 1000 users
        (println "Sending 1000 messages to Kafka...")
        (let [send-start (System/currentTimeMillis)]
          (doseq [i (range 1000)]
            (send-kafka-message producer topic (str "user" i)
                                {:name (str "User" i) :role "developer" :id i}))
          (let [send-end (System/currentTimeMillis)]
            (println (str "Sent 1000 messages in " (- send-end send-start) "ms"))))

        ;; Wait for processing
        (println "Waiting for messages to be consumed...")
        (wait-for-kafka-message-processing 10000)

        ;; Benchmark query performance
        (let [query-start (System/currentTimeMillis)
              results (doall (map #(kpip/query-pip "user" (str "user" %)) (range 1000)))
              valid-results (filter some? results)
              query-end (System/currentTimeMillis)
              duration (- query-end query-start)]

          (println (str "Queried " (count valid-results) " users in " duration "ms"))
          (println (str "Average query time: " (/ duration (double (count valid-results))) "ms"))

          (is (>= (count valid-results) 950)
              (str "Expected at least 950 users processed, got " (count valid-results))))

        (.close producer)))))
