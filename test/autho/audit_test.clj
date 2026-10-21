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

;; Set AUDIT_TEST_POSTGRES_URL (and AUTHO_DB_KIND=postgres) to run the same
;; tests against PostgreSQL, e.g. jdbc:postgresql://localhost:5432/autho?user=..&password=..
(def ^:private test-db
  (if-let [url (System/getenv "AUDIT_TEST_POSTGRES_URL")]
    {:connection-uri url}
    {:classname   "org.h2.Driver"
     :subprotocol "h2:mem"
     :subname     "testaudit;DB_CLOSE_DELAY=-1"
     :user        "sa"
     :password    ""}))

;; 44-character test secret (well above the 32-char minimum)
(def ^:private test-secret "audit-test-hmac-secret-32-chars-min-ok!!")

(def ^:private zero-hash
  "0000000000000000000000000000000000000000000000000000000000000000")

(defn- await-agent []
  (await-for 5000 @#'autho.audit/audit-agent))

(defn- reset-chain! []
  (reset! @#'autho.audit/last-hash zero-hash))

(defn- with-clean-test-db [f]
  ;; Writes queued by other test namespaces must finish against their own
  ;; database before the test database is swapped in.
  (await-agent)
  ;; with-redefs-fn accepts a map of Var→value, allowing access to private vars.
  (with-redefs-fn {#'autho.audit/audit-db    test-db
                   #'autho.audit/hmac-secret test-secret}
    (fn []
      (reset-chain!)
      (doseq [table ["AUDIT_LOG" "AUDIT_CHAIN_HEAD" "AUDIT_CHECKPOINT"]]
        (try (jdbc/execute! test-db [(str "DROP TABLE IF EXISTS " table)])
             (catch Exception _)))
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
          expected (hmac-sha256 (str "v2|" zero-hash "|" (:payload_hash row)) test-secret)]
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

(deftest replay-requests-builds-authz-requests-from-audit-test
  (audit/log-decision! {:request-id     "req-replay-1"
                        :subject-id     "alice"
                        :resource-class "Document"
                        :resource-id    "doc-1"
                        :operation      "read"
                        :decision       :deny
                        :matched-rules  ["deny-old"]})
  (await-agent)
  (let [result (audit/replay-requests {:resource-class "Document" :limit 10})
        replay-request (first (:requests result))]
    (is (= 1 (:returned result)))
    (is (= {:id "alice"} (:subject replay-request)))
    (is (= {:class "Document" :id "doc-1"} (:resource replay-request)))
    (is (= "read" (:operation replay-request)))
    (is (= true (get-in replay-request [:context :auditReplay])))
    (is (= "req-replay-1" (get-in replay-request [:context :auditRequestId])))))

(deftest replay-requests-prefers-full-request-snapshot-test
  (audit/log-decision! {:request-id     "req-replay-snapshot"
                        :subject-id     "alice"
                        :resource-class "Document"
                        :resource-id    "doc-1"
                        :operation      "read"
                        :decision       :allow
                        :matched-rules  ["allow-new"]
                        :request-snapshot {:subject {:id "alice"
                                                     :class "Person"
                                                     :department "legal"}
                                           :resource {:class "Document"
                                                      :id "doc-1"
                                                      :classification "secret"}
                                           :operation "read"
                                           :context {:source "unit-test"}}
                        :decision-snapshot {:allowed? true
                                            :decisionType "allow"
                                            :matchedRuleNames ["allow-new"]}})
  (await-agent)
  (let [result (audit/replay-requests {:resource-class "Document" :limit 10})
        replay-request (first (:requests result))]
    (is (= "legal" (get-in replay-request [:subject :department])))
    (is (= "secret" (get-in replay-request [:resource :classification])))
    (is (= "unit-test" (get-in replay-request [:context :source])))
    (is (= true (get-in replay-request [:context :auditReplay])))
    (is (= "allow" (get-in replay-request [:context :auditDecisionSnapshot :decisionType])))))

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

;; ---------------------------------------------------------------------------
;; Content tampering, truncation and injection
;; ---------------------------------------------------------------------------

(defn- log-n! [n subject]
  (doseq [i (range n)]
    (audit/log-decision! {:request-id     (str "req-" subject "-" i)
                          :subject-id     subject
                          :resource-class "Doc"
                          :resource-id    (str "d-" i)
                          :operation      "read"
                          :decision       :deny
                          :matched-rules  ["R-deny"]
                          :request-snapshot {:subject {:id subject}}}))
  (await-agent))

(deftest detect-altered-decision-column-test
  (testing "verify-chain detects a row whose content changed while hashes were left intact"
    (log-n! 3 "grace")
    (let [id-mid (:id (second (jdbc/query test-db ["SELECT id FROM AUDIT_LOG ORDER BY id ASC"])))]
      (jdbc/execute! test-db ["UPDATE AUDIT_LOG SET decision = 'allow' WHERE id = ?" id-mid])
      (let [result (audit/verify-chain)]
        (is (false? (:valid result)))
        (is (= id-mid (:broken-at result)))))))

(deftest detect-altered-snapshot-test
  (log-n! 2 "heidi")
  (jdbc/execute! test-db ["UPDATE AUDIT_LOG SET request_snapshot = '{}' WHERE id = (SELECT MIN(id) FROM AUDIT_LOG)"])
  (is (= "Payload hash mismatch (entry content altered)" (:reason (audit/verify-chain)))))

(deftest detect-altered-timestamp-test
  (log-n! 1 "ivan")
  (jdbc/execute! test-db ["UPDATE AUDIT_LOG SET ts = TIMESTAMP '2001-01-01 00:00:00'"])
  (is (= "Payload hash mismatch (entry content altered)" (:reason (audit/verify-chain)))))

(deftest downgrading-hash-version-is-detected-test
  (log-n! 1 "judy")
  (jdbc/execute! test-db ["UPDATE AUDIT_LOG SET hash_version = NULL, decision = 'allow'"])
  (is (false? (:valid (audit/verify-chain)))))

(deftest verify-reports-chain-head-test
  (log-n! 2 "ken")
  (let [last-row (first (jdbc/query test-db ["SELECT * FROM AUDIT_LOG ORDER BY id DESC LIMIT 1"]))
        result   (audit/verify-chain)]
    (is (true? (:valid result)))
    (is (= (:id last-row) (:headId result)))
    (is (= (:payload_hash last-row) (:headHash result)))
    (is (= 0 (:legacyEntries result)))))

(deftest detect-truncated-tail-test
  (testing "removing the latest entries is detected against the head known by this instance"
    (log-n! 3 "leo")
    (jdbc/execute! test-db ["DELETE FROM AUDIT_LOG WHERE id = (SELECT MAX(id) FROM AUDIT_LOG)"])
    (let [result (audit/verify-chain)]
      (is (false? (:valid result)))
      (is (string? (:reason result))))))

(deftest detect-truncation-against-external-anchor-test
  (testing "an anchor saved earlier (id + hash) must still be present in the chain"
    (log-n! 3 "mia")
    (let [{:keys [headId headHash]} (audit/verify-chain)]
      (is (true? (:valid (audit/verify-chain {:anchor-id headId :anchor-hash headHash}))))
      ;; attacker removes the last entry and rewrites the head record, then the
      ;; instance restarts and reloads its head from the database
      (jdbc/execute! test-db ["DELETE FROM AUDIT_LOG WHERE id = ?" headId])
      (let [new-head (:payload_hash (first (jdbc/query test-db ["SELECT payload_hash FROM AUDIT_LOG ORDER BY id DESC LIMIT 1"])))]
        (jdbc/execute! test-db ["UPDATE AUDIT_CHAIN_HEAD SET last_hash = ?" new-head])
        (reset! @#'autho.audit/last-hash new-head))
      (is (true? (:valid (audit/verify-chain))))
      (is (false? (:valid (audit/verify-chain {:anchor-id headId :anchor-hash headHash})))))))

(deftest search-is-not-injectable-test
  (log-n! 2 "nina")
  (let [result (audit/search {:subject-id "x' OR '1'='1"})]
    (is (= 0 (:total result))))
  (is (= 0 (:total (audit/search {:resource-class "Doc' OR 1=1 --x"}))))
  (is (thrown? Exception (audit/search {:from "2020-01-01' OR '1'='1"}))))

(deftest search-accepts-date-formats-and-string-paging-test
  (log-n! 3 "olga")
  (is (= 3 (:total (audit/search {:from "2000-01-01"}))))
  (is (= 3 (:total (audit/search {:from "2000-01-01T00:00"}))))
  (is (= 3 (:total (audit/search {:from "2000-01-01T00:00:00Z"}))))
  (is (= 0 (:total (audit/search {:to "2000-01-01T00:00:00Z"}))))
  (let [page (audit/search {:page "2" :page-size "2"})]
    (is (= 1 (count (:items page))))
    (is (= 2 (:pageSize page))))
  (is (= 2 (:returned (audit/replay-requests {:limit "2"})))))

;; ---------------------------------------------------------------------------
;; Shared chain head, write health, purge
;; ---------------------------------------------------------------------------

(deftest truncation-detected-after-restart-test
  (testing "the head stored in AUDIT_CHAIN_HEAD reveals truncation even after a restart"
    (log-n! 3 "pia")
    (jdbc/execute! test-db ["DELETE FROM AUDIT_LOG WHERE id = (SELECT MAX(id) FROM AUDIT_LOG)"])
    (reset-chain!)
    (let [result (audit/verify-chain)]
      (is (false? (:valid result)))
      (is (re-find #"Recorded chain head" (:reason result))))))

(deftest stale-instance-does-not-fork-chain-test
  (testing "a second instance with a stale in-memory head still appends after the real head"
    (log-n! 2 "quentin")
    ;; another instance that loaded its head before those writes
    (reset-chain!)
    (log-n! 2 "rose")
    (let [result (audit/verify-chain)]
      (is (true? (:valid result)))
      (is (= 4 (:total result))))))

(deftest legacy-table-seeds-chain-head-test
  (log-n! 2 "sam")
  (jdbc/execute! test-db ["DROP TABLE AUDIT_CHAIN_HEAD"])
  (audit/init!)
  (log-n! 1 "sam")
  (is (= 3 (:total (audit/verify-chain)))))

(deftest health-reports-write-failures-test
  (log-n! 1 "tom")
  (is (true? (:healthy (audit/health-status))))
  (with-redefs [jdbc/insert! (fn [& _] (throw (ex-info "disk full" {})))]
    (log-n! 1 "tom"))
  (let [h (audit/health-status)]
    (is (false? (:healthy h)))
    (is (= 1 (:writesFailed h)))
    (is (= "disk full" (:lastFailure h))))
  (Thread/sleep 5)
  (log-n! 1 "tom")
  (is (true? (:healthy (audit/health-status))))
  (is (true? (:valid (audit/verify-chain)))))

(deftest purge-keeps-remaining-chain-verifiable-test
  (log-n! 2 "uma")
  (Thread/sleep 5)
  (let [cut (str (.toInstant ^java.sql.Timestamp
                             (:ts (first (jdbc/query test-db ["SELECT MAX(ts) AS ts FROM AUDIT_LOG"])))))]
    (Thread/sleep 5)
    (log-n! 2 "uma")
    (let [result (audit/purge-before! (str (.plusMillis (java.time.Instant/parse cut) 1)))]
      (is (= 2 (:purged result)))
      (is (= 2 (get-in result [:checkpoint :purgedEntries]))))
    (let [verify (audit/verify-chain)]
      (is (true? (:valid verify)))
      (is (= 2 (:total verify)))
      (is (= 2 (get-in verify [:checkpoint :purgedEntries]))))
    (log-n! 1 "uma")
    (is (= 3 (:total (audit/verify-chain))))
    (audit/purge-before! "2999-01-01")
    (log-n! 1 "uma")
    (let [verify (audit/verify-chain)]
      (is (true? (:valid verify)))
      (is (= 1 (:total verify)))
      (is (= 5 (get-in verify [:checkpoint :purgedEntries]))))))

(deftest purge-refused-on-tampered-chain-test
  (log-n! 2 "vera")
  (jdbc/execute! test-db ["UPDATE AUDIT_LOG SET decision = 'allow'"])
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"purge refused"
                        (audit/purge-before! "2999-01-01")))
  (is (= 2 (:n (first (jdbc/query test-db ["SELECT COUNT(*) AS n FROM AUDIT_LOG"]))))))

(deftest tampered-checkpoint-is-detected-test
  (log-n! 3 "will")
  (audit/purge-before! "2999-01-01")
  (jdbc/execute! test-db ["UPDATE AUDIT_CHECKPOINT SET purged_entries = 0"])
  (is (= "Purge checkpoint HMAC mismatch" (:reason (audit/verify-chain)))))

(deftest deleting-checkpoint-breaks-chain-test
  (log-n! 3 "xena")
  (Thread/sleep 5)
  (let [cut (str (java.time.Instant/now))]
    (Thread/sleep 5)
    (log-n! 1 "xena")
    (audit/purge-before! cut))
  (jdbc/execute! test-db ["DELETE FROM AUDIT_CHECKPOINT"])
  (is (false? (:valid (audit/verify-chain)))))

(deftest anchor-older-than-checkpoint-is-reported-test
  (log-n! 2 "yan")
  (let [{:keys [headId headHash]} (audit/verify-chain)]
    (audit/purge-before! "2999-01-01")
    (let [result (audit/verify-chain {:anchor-id headId :anchor-hash headHash})]
      (is (true? (:valid result)))
      (is (true? (:anchorPurged result))))))

(deftest replay-excludes-rebac-checks-test
  (log-n! 1 "zoe")
  (audit/log-decision! {:subject-id "zoe" :resource-class "Doc" :resource-id "d-9"
                        :operation "relation:viewer" :decision :allow :matched-rules []
                        :request-snapshot {:plane "rebac" :at (java.time.Instant/now)}})
  (await-agent)
  (is (= 2 (:total (audit/search {}))))
  (is (= 1 (:total (audit/search {:plane "rebac"}))))
  (is (= 1 (:total (audit/search {:plane "abac"}))))
  (is (= 1 (:returned (audit/replay-requests {:resource-class "Doc"}))))
  (is (true? (:valid (audit/verify-chain)))))

(deftest search-filters-by-resource-id-and-orders-test
  (log-n! 3 "alba")
  (is (= 1 (:total (audit/search {:resource-class "Doc" :resource-id "d-1"}))))
  (is (= ["d-0" "d-1" "d-2"] (map :resource_id (:items (audit/search {:order "asc"})))))
  (is (= ["d-2" "d-1" "d-0"] (map :resource_id (:items (audit/search {})))))
  (is (thrown? clojure.lang.ExceptionInfo (audit/search {:order "id; DROP TABLE AUDIT_LOG"}))))

(deftest missing-chain-head-row-is-reseeded-test
  (log-n! 2 "bruno")
  (jdbc/execute! test-db ["DELETE FROM AUDIT_CHAIN_HEAD"])
  (log-n! 1 "bruno")
  (let [result (audit/verify-chain)]
    (is (true? (:valid result)))
    (is (= 3 (:total result))))
  (is (= 0 (:writesFailed (audit/health-status)))))

(deftest search-returns-iso-8601-timestamps-test
  (log-n! 1 "carla")
  (let [ts (:ts (first (:items (audit/search {}))))]
    (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z" ts))))
