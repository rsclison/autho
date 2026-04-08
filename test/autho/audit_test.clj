(ns autho.audit-test
  "Unit tests for the tamper-evident HMAC-SHA256 audit chain.

   Prerequisites: AUDIT_HMAC_SECRET env var must be set (≥ 32 chars) so that
   the autho.audit namespace loads. Tests then override both the database
   (in-memory H2) and the HMAC secret via with-redefs.

   Run with:
     AUDIT_HMAC_SECRET=$(openssl rand -hex 32) \\
     JWT_SECRET=$(openssl rand -hex 32) \\
     API_KEY=$(openssl rand -hex 32) \\
     lein test :only autho.audit-test"
  (:require [clojure.test :refer :all]
            [autho.audit :as audit]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str])
  (:import (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

;; ---------------------------------------------------------------------------
;; Test infrastructure
;; ---------------------------------------------------------------------------

(def ^:private test-db
  {:classname   "org.h2.Driver"
   :subprotocol "h2:mem"
   :subname     "testaudit;DB_CLOSE_DELAY=-1"
   :user        "sa"
   :password    ""})

;; 44-character test secret (well above the 32-char minimum)
(def ^:private test-secret "audit-test-hmac-secret-32-chars-min-ok!!")

(def ^:private zero-hash
  "0000000000000000000000000000000000000000000000000000000000000000")

(defn- await-agent []
  (await-for 5000 @#'autho.audit/audit-agent))

(defn- reset-chain! []
  (reset! @#'autho.audit/last-hash zero-hash))

(defn- with-clean-test-db [f]
  ;; with-redefs-fn accepts a map of Var→value, allowing access to private vars.
  (with-redefs-fn {#'autho.audit/audit-db    test-db
                   #'autho.audit/hmac-secret test-secret}
    (fn []
      (reset-chain!)
      (try (jdbc/execute! test-db ["DROP TABLE IF EXISTS AUDIT_LOG"])
           (catch Exception _))
      (audit/init!)
      (f))))

(use-fixtures :each with-clean-test-db)

;; ---------------------------------------------------------------------------
;; Helper — direct HMAC computation for assertion
;; ---------------------------------------------------------------------------

(defn- hmac-sha256 [^String data ^String key]
  (let [mac      (Mac/getInstance "HmacSHA256")
        key-spec (SecretKeySpec. (.getBytes key "UTF-8") "HmacSHA256")]
    (.init mac key-spec)
    (apply str (map #(format "%02x" %) (.doFinal mac (.getBytes data "UTF-8"))))))

;; ---------------------------------------------------------------------------
;; init! tests
;; ---------------------------------------------------------------------------

(deftest init-creates-table-test
  (testing "init! creates the AUDIT_LOG table"
    (let [rows (jdbc/query test-db ["SELECT COUNT(*) AS n FROM AUDIT_LOG"])]
      (is (= 0 (:n (first rows)))))))

(deftest init-loads-zero-hash-on-empty-db-test
  (testing "last-hash is the zero sentinel when the table is empty"
    (is (= zero-hash @@#'autho.audit/last-hash))))

;; ---------------------------------------------------------------------------
;; log-decision! and single-entry chain tests
;; ---------------------------------------------------------------------------

(deftest single-entry-chain-valid-test
  (testing "A single logged decision produces a valid chain"
    (audit/log-decision! {:request-id     "req-001"
                          :subject-id     "alice"
                          :resource-class "Document"
                          :resource-id    "doc-1"
                          :operation      "read"
                          :decision       :permit
                          :matched-rules  ["rule-read-all"]})
    (await-agent)
    (let [result (audit/verify-chain)]
      (is (true? (:valid result)))
      (is (= 1 (:total result))))))

(deftest single-entry-hmac-is-correct-test
  (testing "The stored HMAC equals the expected HMAC-SHA256 computation"
    (audit/log-decision! {:request-id     "req-002"
                          :subject-id     "bob"
                          :resource-class "Invoice"
                          :resource-id    "inv-42"
                          :operation      "write"
                          :decision       :deny
                          :matched-rules  []})
    (await-agent)
    (let [row      (first (jdbc/query test-db ["SELECT * FROM AUDIT_LOG ORDER BY id ASC"]))
          expected (hmac-sha256 (str zero-hash (:payload_hash row)) test-secret)]
      (is (= expected (:hmac row)))
      (is (= zero-hash (:previous_hash row))))))

;; ---------------------------------------------------------------------------
;; Multi-entry chain tests
;; ---------------------------------------------------------------------------

(deftest multi-entry-chain-valid-test
  (testing "Three consecutive entries form a valid chain"
    (doseq [i (range 3)]
      (audit/log-decision! {:request-id     (str "req-" i)
                            :subject-id     "charlie"
                            :resource-class "Report"
                            :resource-id    (str "r-" i)
                            :operation      "read"
                            :decision       :permit
                            :matched-rules  [(str "R" i)]}))
    (await-agent)
    (let [result (audit/verify-chain)]
      (is (true? (:valid result)))
      (is (= 3 (:total result))))))

(deftest multi-entry-previous-hash-links-test
  (testing "Each entry's previous_hash equals the prior entry's payload_hash"
    (doseq [i (range 3)]
      (audit/log-decision! {:request-id     (str "req-link-" i)
                            :subject-id     "diana"
                            :resource-class "File"
                            :resource-id    (str "f-" i)
                            :operation      "delete"
                            :decision       :deny
                            :matched-rules  []}))
    (await-agent)
    (let [rows (jdbc/query test-db ["SELECT * FROM AUDIT_LOG ORDER BY id ASC"])]
      (is (= 3 (count rows)))
      ;; Row 0: previous_hash must be zero-hash
      (is (= zero-hash (:previous_hash (first rows))))
      ;; Row 1: previous_hash must equal row 0's payload_hash
      (is (= (:payload_hash (first rows)) (:previous_hash (second rows))))
      ;; Row 2: previous_hash must equal row 1's payload_hash
      (is (= (:payload_hash (second rows)) (:previous_hash (nth rows 2)))))))

;; ---------------------------------------------------------------------------
;; Alteration detection tests
;; ---------------------------------------------------------------------------

(deftest detect-tampered-hmac-test
  (testing "verify-chain detects a tampered HMAC on a single entry"
    (audit/log-decision! {:request-id     "req-tamper-1"
                          :subject-id     "eve"
                          :resource-class "Secret"
                          :resource-id    "s-1"
                          :operation      "read"
                          :decision       :permit
                          :matched-rules  ["R-secret"]})
    (await-agent)
    ;; Corrupt the HMAC directly in the database
    (jdbc/execute! test-db ["UPDATE AUDIT_LOG SET hmac = '0000000000000000000000000000000000000000000000000000000000000000'"])
    (let [result (audit/verify-chain)]
      (is (false? (:valid result)))
      (is (= 1 (:broken-at result)))
      (is (string? (:reason result))))))

(deftest detect-tampered-payload-hash-test
  (testing "verify-chain detects a tampered payload_hash"
    (audit/log-decision! {:request-id     "req-tamper-2"
                          :subject-id     "mallory"
                          :resource-class "Admin"
                          :resource-id    "cfg-1"
                          :operation      "write"
                          :decision       :permit
                          :matched-rules  ["R-admin"]})
    (await-agent)
    ;; Corrupt the payload_hash — breaks the HMAC verification
    (jdbc/execute! test-db ["UPDATE AUDIT_LOG SET payload_hash = 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef'"])
    (let [result (audit/verify-chain)]
      (is (false? (:valid result))))))

(deftest detect-tampered-middle-entry-test
  (testing "verify-chain identifies the correct broken-at entry in a multi-entry chain"
    (doseq [i (range 3)]
      (audit/log-decision! {:request-id     (str "req-mid-" i)
                            :subject-id     "frank"
                            :resource-class "Doc"
                            :resource-id    (str "d-" i)
                            :operation      "read"
                            :decision       :permit
                            :matched-rules  []}))
    (await-agent)
    (let [rows    (jdbc/query test-db ["SELECT id FROM AUDIT_LOG ORDER BY id ASC"])
          id-mid  (:id (second rows))]
      ;; Corrupt only the second entry's HMAC
      (jdbc/execute! test-db [(str "UPDATE AUDIT_LOG SET hmac = 'badbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadb' WHERE id = " id-mid)])
      (let [result (audit/verify-chain)]
        (is (false? (:valid result)))
        (is (= id-mid (:broken-at result)))))))

;; ---------------------------------------------------------------------------
;; search tests
;; ---------------------------------------------------------------------------

(deftest search-returns-all-entries-test
  (testing "search with no filters returns all entries"
    (doseq [i (range 5)]
      (audit/log-decision! {:request-id     (str "req-s-" i)
                            :subject-id     (if (even? i) "user-a" "user-b")
                            :resource-class "Resource"
                            :resource-id    (str "res-" i)
                            :operation      "read"
                            :decision       (if (even? i) :permit :deny)
                            :matched-rules  []}))
    (await-agent)
    (let [result (audit/search {})]
      (is (= 5 (:total result)))
      (is (= 5 (count (:items result)))))))

(deftest search-filters-by-subject-test
  (testing "search with subject-id filter returns only matching entries"
    (doseq [i (range 4)]
      (audit/log-decision! {:request-id     (str "req-subj-" i)
                            :subject-id     (if (< i 3) "user-x" "user-y")
                            :resource-class "Doc"
                            :resource-id    (str "d-" i)
                            :operation      "read"
                            :decision       :permit
                            :matched-rules  []}))
    (await-agent)
    (let [result (audit/search {:subject-id "user-x"})]
      (is (= 3 (:total result)))
      (is (every? #(= "user-x" (:subject_id %)) (:items result))))))

(deftest search-filters-by-decision-test
  (testing "search with decision filter returns only matching entries"
    (doseq [decision [:permit :permit :deny]]
      (audit/log-decision! {:request-id     (str "req-dec-" (name decision))
                            :subject-id     "user-z"
                            :resource-class "File"
                            :resource-id    "f-1"
                            :operation      "read"
                            :decision       decision
                            :matched-rules  []}))
    (await-agent)
    (let [result (audit/search {:decision :permit})]
      (is (= 2 (:total result)))
      (is (every? #(= "permit" (:decision %)) (:items result))))))

(deftest search-paginates-correctly-test
  (testing "search respects page-size and page offset"
    (doseq [i (range 7)]
      (audit/log-decision! {:request-id     (str "req-page-" i)
                            :subject-id     "pager"
                            :resource-class "X"
                            :resource-id    (str i)
                            :operation      "read"
                            :decision       :permit
                            :matched-rules  []}))
    (await-agent)
    (let [page1 (audit/search {:page 1 :page-size 3})
          page2 (audit/search {:page 2 :page-size 3})
          page3 (audit/search {:page 3 :page-size 3})]
      (is (= 7 (:total page1)))
      (is (= 3 (count (:items page1))))
      (is (= 3 (count (:items page2))))
      (is (= 1 (count (:items page3)))))))

;; ---------------------------------------------------------------------------
;; Empty chain test
;; ---------------------------------------------------------------------------

(deftest empty-chain-is-valid-test
  (testing "An empty audit log reports valid with total 0"
    (let [result (audit/verify-chain)]
      (is (true? (:valid result)))
      (is (= 0 (:total result))))))
