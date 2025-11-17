(ns autho.kafka-business-producer
  "Producer for business object test data (invoices, legal commitments, contracts).
   User attributes should come from LDAP in production.

   This producer generates realistic business object data for testing authorization rules."
  (:require [jsonista.core :as json])
  (:import [org.apache.kafka.clients.producer KafkaProducer ProducerConfig ProducerRecord]
           [org.apache.kafka.common.serialization StringSerializer]
           [java.util Properties]
           [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Kafka Producer Configuration
;; =============================================================================

(defn create-business-producer
  "Creates a Kafka producer for business objects"
  [bootstrap-servers]
  (let [props (Properties.)]
    (.put props ProducerConfig/BOOTSTRAP_SERVERS_CONFIG bootstrap-servers)
    (.put props ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG StringSerializer)
    (.put props ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG StringSerializer)
    (.put props ProducerConfig/ACKS_CONFIG "all")
    (.put props ProducerConfig/RETRIES_CONFIG (int 3))
    (KafkaProducer. props)))

;; =============================================================================
;; Date Utilities
;; =============================================================================

(defn today-str []
  (.format (LocalDate/now) DateTimeFormatter/ISO_LOCAL_DATE))

(defn date-plus-days [days]
  (.format (.plusDays (LocalDate/now) days) DateTimeFormatter/ISO_LOCAL_DATE))

(defn date-minus-days [days]
  (.format (.minusDays (LocalDate/now) days) DateTimeFormatter/ISO_LOCAL_DATE))

;; =============================================================================
;; Test Data - Invoices
;; =============================================================================

(def test-invoices
  [{:id "INV-2024-001"
    :attrs {:invoice-number "INV-2024-001"
            :amount 5000.00
            :currency "EUR"
            :supplier "Acme Corp"
            :supplier-id "SUP-001"
            :status "pending"
            :created-date (date-minus-days 5)
            :due-date (date-plus-days 25)
            :department "finance"
            :category "office-supplies"
            :payment-terms "NET30"
            :requires-approval true}}

   {:id "INV-2024-002"
    :attrs {:invoice-number "INV-2024-002"
            :amount 75000.00
            :currency "EUR"
            :supplier "Global Industries"
            :supplier-id "SUP-045"
            :status "pending"
            :created-date (date-minus-days 3)
            :due-date (date-plus-days 27)
            :department "procurement"
            :category "equipment"
            :payment-terms "NET45"
            :requires-cfo-approval true
            :requires-approval true
            :business-justification "Annual equipment renewal"}}

   {:id "INV-2024-003"
    :attrs {:invoice-number "INV-2024-003"
            :amount 2500.50
            :currency "EUR"
            :supplier "Tech Services Ltd"
            :supplier-id "SUP-112"
            :status "approved"
            :created-date (date-minus-days 15)
            :approved-date (date-minus-days 10)
            :approved-by "manager@company.com"
            :due-date (date-plus-days 15)
            :department "IT"
            :category "services"
            :payment-terms "NET30"
            :requires-approval false}}

   {:id "INV-2024-004"
    :attrs {:invoice-number "INV-2024-004"
            :amount 125000.00
            :currency "EUR"
            :supplier "Consulting Partners"
            :supplier-id "SUP-203"
            :status "pending"
            :created-date (date-minus-days 1)
            :due-date (date-plus-days 29)
            :department "operations"
            :category "consulting"
            :payment-terms "NET60"
            :requires-board-approval true
            :requires-cfo-approval true
            :requires-approval true
            :contract-reference "CT-2024-CONS-001"}}])

;; =============================================================================
;; Test Data - Legal Commitments
;; =============================================================================

(def test-legal-commitments
  [{:id "LC-2024-NDA-001"
    :attrs {:commitment-id "LC-2024-NDA-001"
            :type "NDA"
            :classification "confidential"
            :parties ["Company A" "TechPartner Corp"]
            :signed-date (date-minus-days 90)
            :expiry-date (date-plus-days 275)
            :status "active"
            :required-clearance-level 2
            :responsible-department "legal"
            :business-unit "partnerships"
            :counterparty-type "technology-vendor"
            :mutual-agreement true}}

   {:id "LC-2024-NDA-002"
    :attrs {:commitment-id "LC-2024-NDA-002"
            :type "NDA"
            :classification "highly-confidential"
            :parties ["Company A" "Strategic Partner Inc"]
            :signed-date (date-minus-days 30)
            :expiry-date (date-plus-days 335)
            :status "active"
            :required-clearance-level 3
            :responsible-department "legal"
            :business-unit "strategic-initiatives"
            :counterparty-type "strategic-partner"
            :mutual-agreement true
            :board-approved true}}

   {:id "LC-2024-MOU-001"
    :attrs {:commitment-id "LC-2024-MOU-001"
            :type "MOU"
            :classification "internal"
            :parties ["Company A" "Subsidiary B"]
            :signed-date (date-minus-days 60)
            :expiry-date (date-plus-days 305)
            :status "active"
            :required-clearance-level 1
            :responsible-department "legal"
            :business-unit "corporate"
            :binding false}}

   {:id "LC-2024-PARTNERSHIP-001"
    :attrs {:commitment-id "LC-2024-PARTNERSHIP-001"
            :type "partnership-agreement"
            :classification "confidential"
            :parties ["Company A" "Alliance Corp" "Third Partner Ltd"]
            :signed-date (date-minus-days 120)
            :expiry-date (date-plus-days 610)
            :status "active"
            :required-clearance-level 3
            :responsible-department "legal"
            :business-unit "partnerships"
            :revenue-sharing true
            :intellectual-property-provisions true
            :requires-legal-review true
            :annual-review-required true}}])

;; =============================================================================
;; Test Data - Contracts
;; =============================================================================

(def test-contracts
  [{:id "CT-2024-SOFT-001"
    :attrs {:contract-id "CT-2024-SOFT-001"
            :type "software-license"
            :vendor "TechVendor Inc"
            :vendor-id "VEN-1001"
            :value 50000.00
            :currency "EUR"
            :start-date (date-minus-days 300)
            :end-date (date-plus-days 65)
            :status "active"
            :owner-department "IT"
            :business-critical true
            :data-classification "internal"
            :contains-pii false
            :renewal-required true
            :renewal-date (date-minus-days 25)
            :users-count 150
            :license-type "concurrent"}}

   {:id "CT-2024-SERV-015"
    :attrs {:contract-id "CT-2024-SERV-015"
            :type "service-agreement"
            :vendor "CloudProvider Corp"
            :vendor-id "VEN-2045"
            :value 120000.00
            :currency "EUR"
            :start-date (date-minus-days 180)
            :end-date (date-plus-days 550)
            :status "active"
            :owner-department "operations"
            :business-critical true
            :data-classification "confidential"
            :contains-pii true
            :gdpr-compliant true
            :sla-level "premium"
            :uptime-guarantee 99.9
            :support-level "24x7"
            :auto-renewal true}}

   {:id "CT-2024-CONS-001"
    :attrs {:contract-id "CT-2024-CONS-001"
            :type "consulting-services"
            :vendor "Consulting Partners"
            :vendor-id "VEN-3012"
            :value 250000.00
            :currency "EUR"
            :start-date (date-minus-days 90)
            :end-date (date-plus-days 275)
            :status "active"
            :owner-department "operations"
            :business-critical false
            :data-classification "confidential"
            :contains-pii false
            :deliverables ["Strategic analysis" "Implementation roadmap" "Change management"]
            :milestone-based-payment true
            :requires-board-approval true}}

   {:id "CT-2024-LEASE-001"
    :attrs {:contract-id "CT-2024-LEASE-001"
            :type "office-lease"
            :vendor "Property Management LLC"
            :vendor-id "VEN-5001"
            :value 500000.00
            :currency "EUR"
            :start-date (date-minus-days 1095)
            :end-date (date-plus-days 730)
            :status "active"
            :owner-department "facilities"
            :business-critical true
            :location "Paris La Défense"
            :square-meters 850
            :monthly-payment 41666.67
            :escalation-clause true
            :break-clause-date (date-plus-days 365)}}])

;; =============================================================================
;; Message Publishing Functions
;; =============================================================================

(defn publish-message
  "Publishes a single message to Kafka topic"
  [producer topic key value-map]
  (let [value-str (json/write-value-as-string value-map)
        record (ProducerRecord. topic key value-str)]
    (.send producer record)
    (println (str "✓ Published " key " to " topic))))

(defn publish-test-invoices
  "Publishes all test invoices"
  [producer topic]
  (println (str "\n=== Publishing " (count test-invoices) " test invoices to " topic " ==="))
  (doseq [{:keys [id attrs]} test-invoices]
    (publish-message producer topic id attrs))
  (.flush producer)
  (println "✓ Invoice publishing complete"))

(defn publish-test-legal-commitments
  "Publishes all test legal commitments"
  [producer topic]
  (println (str "\n=== Publishing " (count test-legal-commitments) " test legal commitments to " topic " ==="))
  (doseq [{:keys [id attrs]} test-legal-commitments]
    (publish-message producer topic id attrs))
  (.flush producer)
  (println "✓ Legal commitment publishing complete"))

(defn publish-test-contracts
  "Publishes all test contracts"
  [producer topic]
  (println (str "\n=== Publishing " (count test-contracts) " test contracts to " topic " ==="))
  (doseq [{:keys [id attrs]} test-contracts]
    (publish-message producer topic id attrs))
  (.flush producer)
  (println "✓ Contract publishing complete"))

;; =============================================================================
;; Scenario Functions
;; =============================================================================

(defn simulate-invoice-approval
  "Simulates invoice approval workflow"
  [producer topic invoice-id approver-email]
  (println (str "\n=== Simulating invoice approval: " invoice-id " by " approver-email " ==="))
  (publish-message producer topic invoice-id
                   {:status "approved"
                    :approved-by approver-email
                    :approved-date (today-str)})
  (.flush producer)
  (println "✓ Approval published"))

(defn simulate-invoice-rejection
  "Simulates invoice rejection"
  [producer topic invoice-id reason]
  (println (str "\n=== Simulating invoice rejection: " invoice-id " ==="))
  (publish-message producer topic invoice-id
                   {:status "rejected"
                    :rejected-date (today-str)
                    :rejection-reason reason})
  (.flush producer)
  (println "✓ Rejection published"))

(defn simulate-contract-renewal
  "Simulates contract renewal"
  [producer topic contract-id new-end-date new-value]
  (println (str "\n=== Simulating contract renewal: " contract-id " ==="))
  (publish-message producer topic contract-id
                   {:status "renewed"
                    :renewed-date (today-str)
                    :new-end-date new-end-date
                    :value new-value
                    :previous-value-archived true})
  (.flush producer)
  (println "✓ Renewal published"))

(defn simulate-commitment-expiry
  "Simulates legal commitment expiration"
  [producer topic commitment-id]
  (println (str "\n=== Simulating commitment expiry: " commitment-id " ==="))
  (publish-message producer topic commitment-id
                   {:status "expired"
                    :expired-date (today-str)
                    :archived true})
  (.flush producer)
  (println "✓ Expiry published"))

;; =============================================================================
;; Main Scenarios
;; =============================================================================

(defn run-basic-business-scenario
  "Publishes basic test data for all business object types"
  [bootstrap-servers]
  (println "\n╔══════════════════════════════════════════════════════════╗")
  (println "║      Kafka Business Producer - Basic Scenario           ║")
  (println "╚══════════════════════════════════════════════════════════╝")

  (let [producer (create-business-producer bootstrap-servers)]
    (try
      (publish-test-invoices producer "invoices-compacted")
      (publish-test-contracts producer "contracts-compacted")
      (publish-test-legal-commitments producer "legal-commitments-compacted")

      (println "\n✓ Basic business scenario complete!")
      (println "  - Invoices: invoices-compacted")
      (println "  - Contracts: contracts-compacted")
      (println "  - Legal Commitments: legal-commitments-compacted")

      (finally
        (.close producer)))))

(defn run-workflow-scenario
  "Simulates a complete business workflow"
  [bootstrap-servers]
  (println "\n╔══════════════════════════════════════════════════════════╗")
  (println "║      Kafka Business Producer - Workflow Scenario        ║")
  (println "╚══════════════════════════════════════════════════════════╝")

  (let [producer (create-business-producer bootstrap-servers)]
    (try
      ;; Initial data
      (publish-test-invoices producer "invoices-compacted")
      (publish-test-contracts producer "contracts-compacted")
      (Thread/sleep 2000)

      ;; Simulate approvals
      (simulate-invoice-approval producer "invoices-compacted"
                                 "INV-2024-001" "manager@company.com")
      (Thread/sleep 1000)

      (simulate-invoice-approval producer "invoices-compacted"
                                 "INV-2024-002" "cfo@company.com")
      (Thread/sleep 1000)

      ;; Simulate rejection
      (simulate-invoice-rejection producer "invoices-compacted"
                                  "INV-2024-004" "Budget exceeded for this quarter")
      (Thread/sleep 1000)

      ;; Simulate contract renewal
      (simulate-contract-renewal producer "contracts-compacted"
                                 "CT-2024-SOFT-001" (date-plus-days 430) 55000.00)

      (println "\n✓ Workflow scenario complete!")

      (finally
        (.close producer)))))

;; =============================================================================
;; CLI Entry Point
;; =============================================================================

(defn -main
  "Main entry point for business object producer"
  [& args]
  (let [bootstrap-servers (or (first args) "localhost:9092")
        scenario (or (second args) "basic")]

    (case scenario
      "basic"
      (run-basic-business-scenario bootstrap-servers)

      "workflow"
      (run-workflow-scenario bootstrap-servers)

      (do
        (println "Unknown scenario. Available scenarios:")
        (println "  basic    - Publish initial business object test data")
        (println "  workflow - Simulate complete business workflow")))))

(comment
  ;; REPL usage examples

  ;; Basic scenario - publish all test data
  (run-basic-business-scenario "localhost:9092")

  ;; Workflow scenario
  (run-workflow-scenario "localhost:9092")

  ;; Manual operations
  (let [p (create-business-producer "localhost:9092")]
    (publish-test-invoices p "invoices-compacted")
    (simulate-invoice-approval p "invoices-compacted" "INV-2024-001" "alice@company.com")
    (.close p))
  )
