(ns autho.kafka-business-objects-test
  "Integration tests for Kafka PIP with real business objects (invoices, legal commitments, contracts).
   User attributes should come from LDAP in production, but for testing we simulate them.

   Business objects are streamed from Kafka to enable real-time authorization decisions.

   To run these tests:
   1. Start Kafka: docker-compose up -d
   2. Run tests: lein test :integration
   3. Stop Kafka: docker-compose down"
  (:require [clojure.test :refer :all]
            [autho.kafka-pip :as kpip]
            [autho.pip :as pip]
            [jsonista.core :as json])
  (:import [org.apache.kafka.clients.producer KafkaProducer ProducerConfig ProducerRecord]
           [org.apache.kafka.clients.admin AdminClient NewTopic]
           [java.util Properties]))

(def test-db-path (str (System/getProperty "java.io.tmpdir") "rocksdb-business-objects-test"))
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
;; Test 1: Invoice Authorization - Amount-Based Access Control
;; =============================================================================

(deftest ^:integration invoice-authorization-test
  (when-not (kafka-available?)
    (println "⚠️  Skipping test: Kafka not available")
    (is true "Skipped - Kafka not running"))

  (when (kafka-available?)
    (testing "Real Kafka: Invoice access based on amount and approval limits"
      (let [topic "invoice-events"
            _ (ensure-topic-exists topic 1)
            producer (create-kafka-producer)]

        (kpip/open-shared-db test-db-path ["invoice"])
        (kpip/init-pip {:class "invoice"
                        :kafka-topic topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        ;; Publish invoices with different amounts
        (send-kafka-message producer topic "INV-2024-001"
                            {:invoice-number "INV-2024-001"
                             :amount 5000.00
                             :currency "EUR"
                             :supplier "Acme Corp"
                             :status "pending"
                             :created-date "2024-11-15"
                             :department "finance"})

        (send-kafka-message producer topic "INV-2024-002"
                            {:invoice-number "INV-2024-002"
                             :amount 75000.00
                             :currency "EUR"
                             :supplier "Global Industries"
                             :status "pending"
                             :created-date "2024-11-16"
                             :department "procurement"
                             :requires-cfo-approval true})

        (wait-for-kafka-message-processing 3000)

        ;; Verify invoices are queryable
        (let [invoice1 (kpip/query-pip "invoice" "INV-2024-001")
              invoice2 (kpip/query-pip "invoice" "INV-2024-002")]

          (is (= 5000.00 (:amount invoice1)))
          (is (= "pending" (:status invoice1)))

          (is (= 75000.00 (:amount invoice2)))
          (is (true? (:requires-cfo-approval invoice2))))

        ;; Simulate invoice approval (status update)
        (send-kafka-message producer topic "INV-2024-001"
                            {:status "approved"
                             :approved-by "manager@company.com"
                             :approved-date "2024-11-17"})

        (wait-for-kafka-message-processing 2000)

        (let [updated-invoice (kpip/query-pip "invoice" "INV-2024-001")]
          (is (= "approved" (:status updated-invoice)))
          (is (= "manager@company.com" (:approved-by updated-invoice)))
          ;; Original attributes should be preserved
          (is (= 5000.00 (:amount updated-invoice)))
          (is (= "Acme Corp" (:supplier updated-invoice))))

        (.close producer)))))

;; =============================================================================
;; Test 2: Legal Commitment Authorization
;; =============================================================================

(deftest ^:integration legal-commitment-authorization-test
  (when-not (kafka-available?)
    (println "⚠️  Skipping test: Kafka not available")
    (is true "Skipped - Kafka not running"))

  (when (kafka-available?)
    (testing "Real Kafka: Legal commitment access based on classification and clearance"
      (let [topic "legal-commitment-events"
            _ (ensure-topic-exists topic 1)
            producer (create-kafka-producer)]

        (kpip/open-shared-db test-db-path ["legal-commitment"])
        (kpip/init-pip {:class "legal-commitment"
                        :kafka-topic topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        ;; Publish legal commitments
        (send-kafka-message producer topic "LC-2024-NDA-001"
                            {:commitment-id "LC-2024-NDA-001"
                             :type "NDA"
                             :classification "confidential"
                             :parties ["Company A" "Company B"]
                             :signed-date "2024-10-01"
                             :expiry-date "2025-10-01"
                             :status "active"
                             :required-clearance-level 2
                             :responsible-department "legal"})

        (send-kafka-message producer topic "LC-2024-CONTRACT-042"
                            {:commitment-id "LC-2024-CONTRACT-042"
                             :type "service-contract"
                             :classification "restricted"
                             :parties ["Company A" "Supplier XYZ"]
                             :signed-date "2024-11-01"
                             :contract-value 250000.00
                             :currency "EUR"
                             :status "active"
                             :required-clearance-level 3
                             :responsible-department "procurement"
                             :requires-legal-review true})

        (wait-for-kafka-message-processing 3000)

        ;; Verify commitments are accessible
        (let [nda (kpip/query-pip "legal-commitment" "LC-2024-NDA-001")
              contract (kpip/query-pip "legal-commitment" "LC-2024-CONTRACT-042")]

          (is (= "NDA" (:type nda)))
          (is (= "confidential" (:classification nda)))
          (is (= 2 (:required-clearance-level nda)))

          (is (= "service-contract" (:type contract)))
          (is (= "restricted" (:classification contract)))
          (is (= 3 (:required-clearance-level contract)))
          (is (= 250000.00 (:contract-value contract))))

        ;; Simulate commitment expiry
        (send-kafka-message producer topic "LC-2024-NDA-001"
                            {:status "expired"
                             :expired-date "2024-11-17"})

        (wait-for-kafka-message-processing 2000)

        (let [expired-nda (kpip/query-pip "legal-commitment" "LC-2024-NDA-001")]
          (is (= "expired" (:status expired-nda)))
          ;; Original attributes preserved
          (is (= "NDA" (:type expired-nda)))
          (is (= "confidential" (:classification expired-nda))))

        (.close producer)))))

;; =============================================================================
;; Test 3: Contract Authorization - Multi-Attribute Rules
;; =============================================================================

(deftest ^:integration contract-authorization-test
  (when-not (kafka-available?)
    (println "⚠️  Skipping test: Kafka not available")
    (is true "Skipped - Kafka not running"))

  (when (kafka-available?)
    (testing "Real Kafka: Contract access with multiple authorization attributes"
      (let [topic "contract-events"
            _ (ensure-topic-exists topic 1)
            producer (create-kafka-producer)]

        (kpip/open-shared-db test-db-path ["contract"])
        (kpip/init-pip {:class "contract"
                        :kafka-topic topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        ;; Publish contracts
        (send-kafka-message producer topic "CT-2024-SOFT-001"
                            {:contract-id "CT-2024-SOFT-001"
                             :type "software-license"
                             :vendor "TechVendor Inc"
                             :value 50000.00
                             :currency "EUR"
                             :start-date "2024-01-01"
                             :end-date "2025-12-31"
                             :status "active"
                             :owner-department "IT"
                             :business-critical true
                             :data-classification "internal"
                             :contains-pii false
                             :renewal-required true
                             :renewal-date "2025-10-01"})

        (send-kafka-message producer topic "CT-2024-SERV-015"
                            {:contract-id "CT-2024-SERV-015"
                             :type "service-agreement"
                             :vendor "CloudProvider Corp"
                             :value 120000.00
                             :currency "EUR"
                             :start-date "2024-06-01"
                             :end-date "2026-05-31"
                             :status "active"
                             :owner-department "operations"
                             :business-critical true
                             :data-classification "confidential"
                             :contains-pii true
                             :gdpr-compliant true
                             :sla-level "premium"})

        (wait-for-kafka-message-processing 3000)

        ;; Verify contracts
        (let [software-contract (kpip/query-pip "contract" "CT-2024-SOFT-001")
              service-contract (kpip/query-pip "contract" "CT-2024-SERV-015")]

          ;; Software license contract
          (is (= "software-license" (:type software-contract)))
          (is (= 50000.00 (:value software-contract)))
          (is (true? (:business-critical software-contract)))
          (is (false? (:contains-pii software-contract)))

          ;; Service agreement
          (is (= "service-agreement" (:type service-contract)))
          (is (= 120000.00 (:value service-contract)))
          (is (true? (:contains-pii service-contract)))
          (is (true? (:gdpr-compliant service-contract))))

        ;; Simulate contract renewal
        (send-kafka-message producer topic "CT-2024-SOFT-001"
                            {:status "renewed"
                             :renewed-date "2024-11-17"
                             :new-end-date "2026-12-31"
                             :value 55000.00})

        (wait-for-kafka-message-processing 2000)

        (let [renewed-contract (kpip/query-pip "contract" "CT-2024-SOFT-001")]
          (is (= "renewed" (:status renewed-contract)))
          (is (= 55000.00 (:value renewed-contract)))
          ;; Original attributes preserved
          (is (= "software-license" (:type renewed-contract)))
          (is (= "TechVendor Inc" (:vendor renewed-contract))))

        (.close producer)))))

;; =============================================================================
;; Test 4: Mixed Business Objects - Multiple Entity Classes
;; =============================================================================

(deftest ^:integration mixed-business-objects-test
  (when-not (kafka-available?)
    (println "⚠️  Skipping test: Kafka not available")
    (is true "Skipped - Kafka not running"))

  (when (kafka-available?)
    (testing "Real Kafka: Multiple business object types in separate topics"
      (let [invoice-topic "invoices-compacted"
            contract-topic "contracts-compacted"
            commitment-topic "legal-commitments-compacted"
            _ (ensure-topic-exists invoice-topic 1)
            _ (ensure-topic-exists contract-topic 1)
            _ (ensure-topic-exists commitment-topic 1)
            producer (create-kafka-producer)]

        (kpip/open-shared-db test-db-path ["invoice" "contract" "legal-commitment"])

        ;; Initialize PIPs for all business object types
        (kpip/init-pip {:class "invoice"
                        :kafka-topic invoice-topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        (kpip/init-pip {:class "contract"
                        :kafka-topic contract-topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        (kpip/init-pip {:class "legal-commitment"
                        :kafka-topic commitment-topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        ;; Publish different business objects
        (send-kafka-message producer invoice-topic "INV-001"
                            {:amount 10000.00 :status "pending"})

        (send-kafka-message producer contract-topic "CT-001"
                            {:value 50000.00 :status "active"})

        (send-kafka-message producer commitment-topic "LC-001"
                            {:type "NDA" :classification "confidential"})

        (wait-for-kafka-message-processing 3000)

        ;; Verify all objects are independently accessible
        (let [invoice (kpip/query-pip "invoice" "INV-001")
              contract (kpip/query-pip "contract" "CT-001")
              commitment (kpip/query-pip "legal-commitment" "LC-001")]

          (is (= 10000.00 (:amount invoice)))
          (is (= 50000.00 (:value contract)))
          (is (= "NDA" (:type commitment))))

        ;; Verify no cross-contamination between classes
        (is (nil? (kpip/query-pip "invoice" "CT-001")))
        (is (nil? (kpip/query-pip "contract" "INV-001")))
        (is (nil? (kpip/query-pip "legal-commitment" "INV-001")))

        (.close producer)))))

;; =============================================================================
;; Test 5: Real-World Authorization Scenario
;; =============================================================================

(deftest ^:integration real-world-authorization-scenario-test
  (when-not (kafka-available?)
    (println "⚠️  Skipping test: Kafka not available")
    (is true "Skipped - Kafka not running"))

  (when (kafka-available?)
    (testing "Real Kafka: Complete authorization scenario with business objects"
      (let [topic "invoices-auth-test"
            _ (ensure-topic-exists topic 1)
            producer (create-kafka-producer)]

        (kpip/open-shared-db test-db-path ["invoice"])
        (kpip/init-pip {:class "invoice"
                        :kafka-topic topic
                        :kafka-bootstrap-servers kafka-bootstrap-servers})

        ;; Scenario: Invoice requires approval based on amount
        ;; In LDAP: alice (manager, approval-limit: 10000)
        ;; In LDAP: bob (CFO, approval-limit: 100000)

        ;; Invoice within manager's limit
        (send-kafka-message producer topic "INV-SMALL"
                            {:invoice-number "INV-SMALL"
                             :amount 8000.00
                             :status "pending"
                             :department "finance"})

        ;; Invoice requiring CFO approval
        (send-kafka-message producer topic "INV-LARGE"
                            {:invoice-number "INV-LARGE"
                             :amount 85000.00
                             :status "pending"
                             :department "finance"
                             :requires-cfo-approval true})

        (wait-for-kafka-message-processing 3000)

        ;; Authorization logic would check:
        ;; - For INV-SMALL: user.approval-limit >= invoice.amount
        ;; - For INV-LARGE: user.role == "CFO" AND user.department == invoice.department

        (let [small-invoice (kpip/query-pip "invoice" "INV-SMALL")
              large-invoice (kpip/query-pip "invoice" "INV-LARGE")]

          ;; Manager can approve small invoice
          (is (< (:amount small-invoice) 10000))

          ;; Large invoice requires CFO
          (is (> (:amount large-invoice) 10000))
          (is (true? (:requires-cfo-approval large-invoice))))

        ;; Simulate manager approval of small invoice
        (send-kafka-message producer topic "INV-SMALL"
                            {:status "approved"
                             :approved-by "alice@company.com"
                             :approved-date "2024-11-17"})

        (wait-for-kafka-message-processing 2000)

        (let [approved-invoice (kpip/query-pip "invoice" "INV-SMALL")]
          (is (= "approved" (:status approved-invoice)))
          (is (= "alice@company.com" (:approved-by approved-invoice))))

        (.close producer)))))
