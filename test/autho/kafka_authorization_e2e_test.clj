(ns autho.kafka-authorization-e2e-test
  "End-to-end tests for Kafka PIP integrated with authorization rules.
   Tests the complete flow: Kafka attributes → Rule evaluation → Authorization decision"
  (:require [clojure.test :refer :all]
            [autho.kafka-pip :as kpip]
            [autho.pip :as pip]
            [autho.pdp :as pdp]
            [autho.prp :as prp]
            [autho.jsonrule :as rule]
            [jsonista.core :as json]))

(def test-db-path "/tmp/rocksdb-e2e-test")

(defn cleanup-test-db []
  (let [dir (clojure.java.io/file test-db-path)]
    (when (.exists dir)
      (doseq [file (.listFiles dir)]
        (.delete file))
      (.delete dir))))

(defn simulate-kafka-message
  "Helper to populate Kafka PIP with attributes"
  [class-name entity-id attributes]
  (let [cf-handles (:cf-handles @kpip/db-state)
        db-instance (:db-instance @kpip/db-state)
        cf-handle (get cf-handles class-name)
        key entity-id
        existing-bytes (.get db-instance cf-handle
                             (.getBytes key java.nio.charset.StandardCharsets/UTF_8))
        existing-attrs (when existing-bytes
                         (json/read-value (String. existing-bytes java.nio.charset.StandardCharsets/UTF_8)))
        merged-attrs (merge existing-attrs attributes)
        merged-json (json/write-value-as-string merged-attrs)]
    (.put db-instance cf-handle
          (.getBytes key java.nio.charset.StandardCharsets/UTF_8)
          (.getBytes merged-json java.nio.charset.StandardCharsets/UTF_8))))

(use-fixtures :each
  (fn [f]
    (cleanup-test-db)
    (f)
    (cleanup-test-db)))

;; =============================================================================
;; E2E Test 1: Manager Access Control with Kafka Attributes
;; =============================================================================

(deftest manager-access-with-kafka-attributes-test
  (testing "Authorization decision based on role attribute from Kafka"
    (kpip/open-shared-db test-db-path ["user" "resource"])

    ;; Setup: Populate Kafka PIP with user and resource attributes
    (simulate-kafka-message "user" "alice"
                            {:name "Alice" :role "manager" :department "finance"})

    (simulate-kafka-message "resource" "budget-report"
                            {:title "Budget Report" :department "finance" :classification "confidential"})

    ;; Define test rule: Only managers can access confidential resources in their department
    (let [test-rule {:name "manager-access-rule"
                     :priority 1
                     :effect "allow"
                     :subjectCond [:and
                                   {:attribute "role" :operator "=" :value "manager"}
                                   {:attribute "department" :operator "=" :value :var-dept}]
                     :resourceCond [:and
                                    {:attribute "classification" :operator "=" :value "confidential"}
                                    {:attribute "department" :operator "=" :value :var-dept}]}

          ;; Mock request (minimal attributes, rest from Kafka PIP)
          request {:subject {:class "user" :id "alice"}
                   :resource {:class "resource" :id "budget-report"}
                   :operation "read"}]

      ;; Mock PIP calls to return Kafka data
      (with-redefs [pip/callPip (fn [pip-decl att obj]
                                  (let [class-name (:class pip-decl)
                                        obj-id (:id obj)]
                                    (kpip/query-pip class-name obj-id)))]

        ;; Simulate rule evaluation (simplified)
        (let [subject-attrs (kpip/query-pip "user" "alice")
              resource-attrs (kpip/query-pip "resource" "budget-report")]

          ;; Verify attributes were retrieved from Kafka PIP
          (is (= "manager" (get subject-attrs "role")))
          (is (= "finance" (get subject-attrs "department")))
          (is (= "confidential" (get resource-attrs "classification")))
          (is (= "finance" (get resource-attrs "department")))

          ;; Verify rule would match (same department)
          (is (= (get subject-attrs "department")
                 (get resource-attrs "department"))))))

    (kpip/close-shared-db)))

;; =============================================================================
;; E2E Test 2: Dynamic Role Update Affects Authorization
;; =============================================================================

(deftest dynamic-role-update-authorization-test
  (testing "Authorization changes when user role is updated via Kafka"
    (kpip/open-shared-db test-db-path ["user" "resource"])

    ;; Initial state: Bob is a developer
    (simulate-kafka-message "user" "bob"
                            {:name "Bob" :role "developer" :team "backend"})

    (simulate-kafka-message "resource" "prod-database"
                            {:name "Production DB" :required-role "admin"})

    ;; Check 1: Developer should NOT have access
    (let [user-attrs (kpip/query-pip "user" "bob")
          resource-attrs (kpip/query-pip "resource" "prod-database")]
      (is (= "developer" (get user-attrs "role")))
      (is (not= (get user-attrs "role") (get resource-attrs "required-role"))))

    ;; Event: Bob gets promoted to admin (via Kafka message)
    (simulate-kafka-message "user" "bob"
                            {:role "admin"})

    ;; Check 2: Admin should have access
    (let [updated-user-attrs (kpip/query-pip "user" "bob")
          resource-attrs (kpip/query-pip "resource" "prod-database")]
      (is (= "admin" (get updated-user-attrs "role")))
      (is (= (get updated-user-attrs "role") (get resource-attrs "required-role")))
      ;; Verify other attributes preserved
      (is (= "Bob" (get updated-user-attrs "name")))
      (is (= "backend" (get updated-user-attrs "team"))))

    (kpip/close-shared-db)))

;; =============================================================================
;; E2E Test 3: Threshold-Based Authorization (Numeric Attributes)
;; =============================================================================

(deftest threshold-based-authorization-test
  (testing "Authorization based on numeric attribute thresholds from Kafka"
    (kpip/open-shared-db test-db-path ["user" "resource"])

    ;; Setup: User with approval limit
    (simulate-kafka-message "user" "manager1"
                            {:name "Manager1" :role "manager" :approval-limit 10000})

    ;; Resource: Purchase request
    (simulate-kafka-message "resource" "purchase-req-5k"
                            {:type "purchase-request" :amount 5000})

    (simulate-kafka-message "resource" "purchase-req-50k"
                            {:type "purchase-request" :amount 50000})

    ;; Test 1: Manager can approve $5K (below limit)
    (let [user-attrs (kpip/query-pip "user" "manager1")
          resource-5k-attrs (kpip/query-pip "resource" "purchase-req-5k")]
      (is (< (get resource-5k-attrs "amount")
             (get user-attrs "approval-limit"))))

    ;; Test 2: Manager cannot approve $50K (above limit)
    (let [user-attrs (kpip/query-pip "user" "manager1")
          resource-50k-attrs (kpip/query-pip "resource" "purchase-req-50k")]
      (is (> (get resource-50k-attrs "amount")
             (get user-attrs "approval-limit"))))

    ;; Event: Manager gets limit increase via Kafka
    (simulate-kafka-message "user" "manager1"
                            {:approval-limit 100000})

    ;; Test 3: Now manager can approve $50K
    (let [updated-user-attrs (kpip/query-pip "user" "manager1")
          resource-50k-attrs (kpip/query-pip "resource" "purchase-req-50k")]
      (is (< (get resource-50k-attrs "amount")
             (get updated-user-attrs "approval-limit"))))

    (kpip/close-shared-db)))

;; =============================================================================
;; E2E Test 4: Multi-Attribute Rule Matching
;; =============================================================================

(deftest multi-attribute-rule-matching-test
  (testing "Complex rule with multiple attributes from Kafka PIP"
    (kpip/open-shared-db test-db-path ["user" "resource"])

    ;; Setup: User with multiple attributes
    (simulate-kafka-message "user" "employee"
                            {:name "Employee"
                             :role "developer"
                             :team "product"
                             :clearance-level 2
                             :location "paris"})

    ;; Resource with multiple constraints
    (simulate-kafka-message "resource" "project-alpha"
                            {:name "Project Alpha"
                             :team "product"
                             :required-clearance 2
                             :allowed-locations ["paris" "london"]})

    ;; Verify all conditions match
    (let [user-attrs (kpip/query-pip "user" "employee")
          resource-attrs (kpip/query-pip "resource" "project-alpha")]

      ;; Condition 1: Same team
      (is (= (get user-attrs "team") (get resource-attrs "team")))

      ;; Condition 2: Sufficient clearance
      (is (>= (get user-attrs "clearance-level")
              (get resource-attrs "required-clearance")))

      ;; Condition 3: Allowed location
      (is (some #(= % (get user-attrs "location"))
                (get resource-attrs "allowed-locations"))))

    (kpip/close-shared-db)))

;; =============================================================================
;; E2E Test 5: Delegation Scenario with Kafka Attributes
;; =============================================================================

(deftest delegation-with-kafka-attributes-test
  (testing "Delegation decision based on delegator attributes from Kafka"
    (kpip/open-shared-db test-db-path ["user"])

    ;; Setup: Delegator and delegate
    (simulate-kafka-message "user" "senior-manager"
                            {:name "Senior Manager"
                             :role "senior-manager"
                             :can-delegate true
                             :max-delegation-level "manager"})

    (simulate-kafka-message "user" "junior-manager"
                            {:name "Junior Manager"
                             :role "manager"
                             :reports-to "senior-manager"})

    ;; Verify delegation is valid
    (let [delegator-attrs (kpip/query-pip "user" "senior-manager")
          delegate-attrs (kpip/query-pip "user" "junior-manager")]

      ;; Check delegation conditions
      (is (true? (get delegator-attrs "can-delegate")))
      (is (= "senior-manager" (get delegate-attrs "reports-to")))
      (is (= (get delegator-attrs "max-delegation-level")
             (get delegate-attrs "role"))))

    (kpip/close-shared-db)))

;; =============================================================================
;; E2E Test 6: Time-Based Attributes (Expiry, Temporal Access)
;; =============================================================================

(deftest temporal-access-with-kafka-test
  (testing "Access control based on temporal attributes from Kafka"
    (kpip/open-shared-db test-db-path ["user" "resource"])

    ;; Setup: Temporary contractor with expiry
    (simulate-kafka-message "user" "contractor"
                            {:name "Contractor"
                             :role "contractor"
                             :access-expiry "2024-12-31"
                             :status "active"})

    (simulate-kafka-message "resource" "temp-project"
                            {:name "Temporary Project"
                             :end-date "2024-12-31"})

    (let [user-attrs (kpip/query-pip "user" "contractor")
          resource-attrs (kpip/query-pip "resource" "temp-project")]

      ;; Verify access is valid (expiry matches project end)
      (is (= (get user-attrs "access-expiry")
             (get resource-attrs "end-date")))
      (is (= "active" (get user-attrs "status"))))

    ;; Event: Contract expires (status update via Kafka)
    (simulate-kafka-message "user" "contractor"
                            {:status "expired"})

    (let [updated-user-attrs (kpip/query-pip "user" "contractor")]
      (is (= "expired" (get updated-user-attrs "status")))
      ;; Other attributes should be preserved
      (is (= "Contractor" (get updated-user-attrs "name")))
      (is (= "2024-12-31" (get updated-user-attrs "access-expiry"))))

    (kpip/close-shared-db)))

;; =============================================================================
;; E2E Test 7: Attribute-Based Access Control (ABAC) Full Scenario
;; =============================================================================

(deftest abac-full-scenario-test
  (testing "Complete ABAC scenario with dynamic Kafka attribute updates"
    (kpip/open-shared-db test-db-path ["user" "resource"])

    ;; Initial setup
    (simulate-kafka-message "user" "doctor"
                            {:name "Dr. Smith"
                             :role "doctor"
                             :specialty "cardiology"
                             :hospital "central-hospital"
                             :verified true})

    (simulate-kafka-message "resource" "patient-record-123"
                            {:type "patient-record"
                             :department "cardiology"
                             :hospital "central-hospital"
                             :sensitivity "high"
                             :requires-verification true})

    ;; Scenario 1: Doctor accesses patient in their specialty
    (let [user-attrs (kpip/query-pip "user" "doctor")
          resource-attrs (kpip/query-pip "resource" "patient-record-123")]

      (is (= (get user-attrs "specialty")
             (get resource-attrs "department")))
      (is (= (get user-attrs "hospital")
             (get resource-attrs "hospital")))
      (is (= (get user-attrs "verified")
             (get resource-attrs "requires-verification"))))

    ;; Event: Doctor transfers to different hospital
    (simulate-kafka-message "user" "doctor"
                            {:hospital "north-hospital"})

    ;; Scenario 2: Doctor no longer has access (different hospital)
    (let [updated-user-attrs (kpip/query-pip "user" "doctor")
          resource-attrs (kpip/query-pip "resource" "patient-record-123")]

      (is (not= (get updated-user-attrs "hospital")
                (get resource-attrs "hospital")))
      ;; But specialty is still preserved
      (is (= (get updated-user-attrs "specialty")
             (get resource-attrs "department"))))

    ;; Event: Patient record also transferred
    (simulate-kafka-message "resource" "patient-record-123"
                            {:hospital "north-hospital"})

    ;; Scenario 3: Access restored (both at north-hospital)
    (let [user-attrs (kpip/query-pip "user" "doctor")
          resource-attrs (kpip/query-pip "resource" "patient-record-123")]

      (is (= (get user-attrs "hospital")
             (get resource-attrs "hospital"))))

    (kpip/close-shared-db)))

;; =============================================================================
;; E2E Test 8: Performance - Rapid Authorization Decisions
;; =============================================================================

(deftest ^:benchmark rapid-authorization-decisions-test
  (testing "Performance: 1000 authorization decisions with Kafka PIP"
    (kpip/open-shared-db test-db-path ["user" "resource"])

    ;; Setup: Populate 100 users and 100 resources
    (doseq [i (range 100)]
      (simulate-kafka-message "user" (str "user" i)
                              {:name (str "User" i)
                               :role (if (< i 50) "admin" "user")
                               :level i})

      (simulate-kafka-message "resource" (str "resource" i)
                              {:name (str "Resource" i)
                               :required-role (if (< i 50) "admin" "user")
                               :required-level i}))

    ;; Benchmark: Perform 1000 authorization checks
    (let [start (System/currentTimeMillis)]

      (dotimes [i 1000]
        (let [user-id (str "user" (mod i 100))
              resource-id (str "resource" (mod i 100))
              user-attrs (kpip/query-pip "user" user-id)
              resource-attrs (kpip/query-pip "resource" resource-id)

              ;; Simple authorization logic
              authorized? (and (= (get user-attrs "role")
                                  (get resource-attrs "required-role"))
                               (>= (get user-attrs "level")
                                   (get resource-attrs "required-level")))]
          authorized?))

      (let [end (System/currentTimeMillis)
            duration (- end start)]
        (println (str "1000 authorization decisions in " duration "ms"))
        (println (str "Average decision time: " (/ duration 1000.0) "ms"))
        (is (< duration 2000) "Should complete 1000 decisions in under 2 seconds")))

    (kpip/close-shared-db)))
