(ns autho.audit
  "Immutable audit log for authorization decisions.
   Each entry is HMAC-SHA256 signed and chained via hash for tamper detection.
   Since hash_version 2 the payload hash is computed from the stored column
   values, so verification detects any altered column, not only altered hashes.
   Storage: H2 file database (separate from the policy store) or PostgreSQL.
   Writes are async via a Clojure agent to stay off the critical path.

   The chain head lives in AUDIT_CHAIN_HEAD and is locked (SELECT ... FOR UPDATE)
   for every append, so several instances sharing one database keep a single
   linear chain. Purged prefixes are replaced by an HMAC-signed checkpoint
   (AUDIT_CHECKPOINT) from which verification resumes."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [autho.database :as database]
            [autho.metrics :as metrics]
            [com.brunobonacci.mulog :as u])
  (:import (java.time Instant LocalDate LocalDateTime OffsetDateTime)
           (java.time.temporal ChronoUnit)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (java.security MessageDigest)
           (java.util.concurrent Executors ScheduledExecutorService TimeUnit)
           (org.slf4j LoggerFactory)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.audit"))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private h2-audit-cipher-key (System/getenv "H2_AUDIT_CIPHER_KEY"))
(def ^:private audit-db (database/audit-db))

;; hmac-secret is kept as a var for compatibility with audit tests that
;; override private vars via with-redefs. Its value is resolved lazily to avoid
;; failing namespace loading when the environment is not configured yet.
(def ^:private hmac-secret nil)

(defn- require-hmac-secret!
  []
  (let [secret (or hmac-secret (System/getenv "AUDIT_HMAC_SECRET"))]
    (cond
      (nil? secret)
      (throw (ex-info "AUDIT_HMAC_SECRET environment variable must be set"
                      {:type ::missing-config}))
      (< (count secret) 32)
      (throw (ex-info "AUDIT_HMAC_SECRET must be at least 32 characters (256 bits)"
                      {:type ::weak-config :length (count secret) :minimum 32}))
      :else secret)))

;; ---------------------------------------------------------------------------
;; Crypto helpers
;; ---------------------------------------------------------------------------

(defn- sha256 ^String [^String s]
  (let [md     (MessageDigest/getInstance "SHA-256")
        digest (.digest md (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) digest))))

(defn- hmac-sha256 ^String [^String data ^String key]
  (let [mac     (Mac/getInstance "HmacSHA256")
        key-spec (SecretKeySpec. (.getBytes key "UTF-8") "HmacSHA256")]
    (.init mac key-spec)
    (apply str (map #(format "%02x" %) (.doFinal mac (.getBytes data "UTF-8"))))))

;; ---------------------------------------------------------------------------
;; Database initialisation
;; ---------------------------------------------------------------------------

(defn- create-table-if-absent! []
  (jdbc/execute! audit-db
    [(format "CREATE TABLE IF NOT EXISTS AUDIT_LOG (
        id            %s,
        ts            TIMESTAMP    NOT NULL,
        request_id    VARCHAR(36),
        subject_id    VARCHAR(255),
        resource_class VARCHAR(255),
        resource_id   VARCHAR(255),
        operation     VARCHAR(100),
        decision      VARCHAR(10),
        matched_rules VARCHAR(2048),
        request_snapshot %s,
        decision_snapshot %s,
        payload_hash  CHAR(64)     NOT NULL,
        previous_hash CHAR(64)     NOT NULL,
        hmac          CHAR(64)     NOT NULL,
        hash_version  INT
     )" (database/identity-column) (database/text-column) (database/text-column))])
  (doseq [[column ddl] {"request_snapshot" (str "ALTER TABLE AUDIT_LOG ADD COLUMN request_snapshot " (database/text-column))
                        "decision_snapshot" (str "ALTER TABLE AUDIT_LOG ADD COLUMN decision_snapshot " (database/text-column))
                        "hash_version" "ALTER TABLE AUDIT_LOG ADD COLUMN hash_version INT"}]
    (try
      (jdbc/execute! audit-db [ddl])
      (catch Exception e
        (when-not (let [msg (str/lower-case (str (.getMessage e)))]
                    (or (str/includes? msg "duplicate") (str/includes? msg "already exists")))
          (.warn logger "Could not add audit column {}: {}" column (.getMessage e))))))
  (jdbc/execute! audit-db
    ["CREATE TABLE IF NOT EXISTS AUDIT_CHAIN_HEAD (
        id        INT      PRIMARY KEY,
        last_hash CHAR(64) NOT NULL
     )"])
  (jdbc/execute! audit-db
    ["CREATE TABLE IF NOT EXISTS AUDIT_CHECKPOINT (
        id               INT       PRIMARY KEY,
        last_purged_id   BIGINT    NOT NULL,
        last_purged_hash CHAR(64)  NOT NULL,
        purged_entries   BIGINT    NOT NULL,
        purged_at        TIMESTAMP NOT NULL,
        hmac             CHAR(64)  NOT NULL
     )"]))

;; Holds the hash of the last inserted row for chain continuity
(def ^:private zero-hash "0000000000000000000000000000000000000000000000000000000000000000")

;; Last chain hash written (or loaded at startup) by this instance. Used during
;; verification: it must still be present in the chain.
(defonce ^:private last-hash (atom zero-hash))

(defn- read-checkpoint
  [db]
  (first (jdbc/query db ["SELECT * FROM AUDIT_CHECKPOINT WHERE id = 1"])))

(defn- ensure-chain-head!
  "Seeds AUDIT_CHAIN_HEAD from the last entry (tables created before the head
   table existed) and returns the current head hash."
  [db]
  (or (:last_hash (first (jdbc/query db ["SELECT last_hash FROM AUDIT_CHAIN_HEAD WHERE id = 1"])))
      (let [seed (or (:payload_hash (first (jdbc/query db ["SELECT payload_hash FROM AUDIT_LOG ORDER BY id DESC LIMIT 1"])))
                     (:last_purged_hash (read-checkpoint db))
                     zero-hash)]
        (try
          (jdbc/insert! db :audit_chain_head {:id 1 :last_hash seed})
          seed
          (catch Exception e
            ;; another instance seeded it concurrently
            (if (database/unique-violation? e)
              (:last_hash (first (jdbc/query db ["SELECT last_hash FROM AUDIT_CHAIN_HEAD WHERE id = 1"])))
              (throw e)))))))

(defn- lock-chain-head!
  "Locks the chain head row for the current transaction and returns its hash."
  [tx]
  (ensure-chain-head! tx)
  (:last_hash (first (jdbc/query tx ["SELECT last_hash FROM AUDIT_CHAIN_HEAD WHERE id = 1 FOR UPDATE"]))))

(defn- load-last-hash! []
  (reset! last-hash (ensure-chain-head! audit-db)))

;; ---------------------------------------------------------------------------
;; Write health — failed writes must be visible (metrics, /status, /readiness)
;; ---------------------------------------------------------------------------

(def ^:private initial-health
  {:initialized false :writesSucceeded 0 :writesFailed 0
   :lastWriteAt nil :lastFailureAt nil :lastFailure nil})

(defonce ^:private health (atom initial-health))

(defn- record-write-success! []
  (metrics/record-audit-write! :success)
  (swap! health #(-> % (update :writesSucceeded inc) (assoc :lastWriteAt (str (Instant/now))))))

(defn- record-write-failure! [^Exception e]
  (metrics/record-audit-write! :failure)
  (swap! health #(-> %
                     (update :writesFailed inc)
                     (assoc :lastFailureAt (str (Instant/now))
                            :lastFailure (.getMessage e)))))

(defn required?
  "When AUDIT_REQUIRED=true, an unhealthy audit log makes the instance not ready."
  []
  (= "true" (some-> (System/getenv "AUDIT_REQUIRED") str/trim str/lower-case)))

;; ---------------------------------------------------------------------------
;; Async agent — all DB writes are serialised through this agent
;; ---------------------------------------------------------------------------

(defonce ^:private audit-agent (agent nil :error-handler
                                 (fn [_ e]
                                   (.error logger "Audit agent error: {}" (.getMessage e) e))))

(defn health-status
  "Audit write health. Unhealthy when not initialised or when the most recent
   write attempt failed."
  []
  (let [{:keys [initialized lastWriteAt lastFailureAt] :as h} @health
        recovered? (or (nil? lastFailureAt)
                       (and lastWriteAt (pos? (compare lastWriteAt lastFailureAt))))]
    (assoc h
           :healthy (boolean (and initialized recovered?))
           :pendingWrites (.getQueueCount ^clojure.lang.Agent audit-agent)
           :required (required?))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(declare schedule-retention!)

(defn init!
  "Create the audit table and load the last chain hash. Call from pdp/init."
  []
  (when (and (database/h2?) (not h2-audit-cipher-key))
    (.warn logger "H2_AUDIT_CIPHER_KEY is not set — audit database is stored UNENCRYPTED. Set this variable in production."))
  (try
    (require-hmac-secret!)
    (create-table-if-absent!)
    (load-last-hash!)
    (reset! health (assoc initial-health :initialized true))
    (.info logger "Audit log initialised (storage: {})"
           (if (database/postgres?) "PostgreSQL" (if h2-audit-cipher-key "H2 AES/CIPHER" "H2")))
    (catch Exception e
      (reset! health (assoc initial-health
                            :lastFailureAt (str (Instant/now))
                            :lastFailure (str "Initialisation failed: " (.getMessage e))))
      (.error logger "Failed to initialise audit log: {}" (.getMessage e) e)))
  (schedule-retention!))

(defn- json-safe
  "Serialises snapshots; values JSON does not know (Instant, UUID...) are written
   as strings so that a snapshot never prevents an audit write."
  [value]
  (when value
    (json/write-str value :default-write-fn (fn [x _out _options] (json/write (str x) _out)))))

(def ^:private current-hash-version 2)

(defn- sql-value->str
  [v]
  (cond
    (nil? v) nil
    (instance? java.sql.Clob v)
    (with-open [reader (.getCharacterStream ^java.sql.Clob v)]
      (slurp reader))
    :else (str v)))

(defn- column-str
  [v]
  (cond
    (nil? v) nil
    (keyword? v) (name v)
    :else (str v)))

(defn- row-payload-hash
  "SHA-256 of the audited columns exactly as stored. The timestamp is hashed as
   the stored local date-time so it survives the database round trip."
  [{:keys [ts request_id subject_id resource_class resource_id operation decision
           matched_rules request_snapshot decision_snapshot]}]
  (sha256 (json/write-str
           [current-hash-version
            (when ts (str (.toLocalDateTime ^java.sql.Timestamp ts)))
            (sql-value->str request_id)
            (sql-value->str subject_id)
            (sql-value->str resource_class)
            (sql-value->str resource_id)
            (sql-value->str operation)
            (sql-value->str decision)
            (sql-value->str matched_rules)
            (sql-value->str request_snapshot)
            (sql-value->str decision_snapshot)])))

(defn- chain-hmac
  "Version 2 binds the hash version into the MAC so a row cannot be downgraded
   to the legacy (hash-only) verification."
  [hash-version prev-hash payload-hash secret]
  (if (= 2 hash-version)
    (hmac-sha256 (str "v2|" prev-hash "|" payload-hash) secret)
    (hmac-sha256 (str prev-hash payload-hash) secret)))

(defn log-decision!
  "Asynchronously records an authorization decision in the audit log.
   Non-blocking: returns immediately; actual insert happens via the audit-agent."
  [{:keys [request-id subject-id resource-class resource-id operation decision matched-rules
           request-snapshot decision-snapshot]}]
  (send audit-agent
        (fn [_]
          (try
            (let [hmac-secret  (require-hmac-secret!)
                  row          {:ts             (java.sql.Timestamp/from
                                                 (.truncatedTo (Instant/now) ChronoUnit/MILLIS))
                                :request_id     (column-str request-id)
                                :subject_id     (column-str subject-id)
                                :resource_class (column-str resource-class)
                                :resource_id    (column-str resource-id)
                                :operation      (column-str operation)
                                :decision       (column-str decision)
                                :matched_rules  (str matched-rules)
                                :request_snapshot (json-safe request-snapshot)
                                :decision_snapshot (json-safe decision-snapshot)}
                  payload-hash (row-payload-hash row)]
              (jdbc/with-db-transaction [tx audit-db]
                (let [prev-hash (lock-chain-head! tx)]
                  (jdbc/insert! tx :audit_log
                                (assoc row
                                       :payload_hash  payload-hash
                                       :previous_hash prev-hash
                                       :hash_version  current-hash-version
                                       :hmac          (chain-hmac current-hash-version prev-hash
                                                                  payload-hash hmac-secret)))
                  (jdbc/execute! tx ["UPDATE AUDIT_CHAIN_HEAD SET last_hash = ? WHERE id = 1" payload-hash])))
              (reset! last-hash payload-hash)
              (record-write-success!)
              (u/log ::decision-logged :decision decision :subject-id subject-id))
            (catch Exception e
              (record-write-failure! e)
              (.error logger "Failed to write audit entry: {}" (.getMessage e) e)))))
  nil)

(defn- normalize-row
  "Convert JDBC row to a JSON-safe map:
   - java.sql.Timestamp → ISO-8601 string
   - matched_rules string → vector of rule names
   - request/decision snapshots → maps when present"
  [row]
  (letfn [(parse-json-value [v]
            (when-let [s (sql-value->str v)]
              (json/read-str s :key-fn keyword)))]
    (-> row
        (update :ts #(when % (.toString ^java.sql.Timestamp %)))
        (update :request_snapshot parse-json-value)
        (update :decision_snapshot parse-json-value)
        (update :matched_rules
                (fn [v]
                  (when v
                    (try (json/read-str (str v))
                         (catch Exception _
                           ;; Clojure repr like [R1 R2] — split on whitespace/brackets
                           (-> (str v)
                               (str/replace #"[\[\]\"]" "")
                               (str/split #"\s+")
                               (->> (remove str/blank?)
                                    vec))))))))))

(def ^:private max-page-size 1000)

(defn- invalid-filter!
  [message data]
  (throw (ex-info message (merge {:type ::invalid-filter
                                  :status 400
                                  :error-code "INVALID_AUDIT_FILTER"}
                                 data))))

(defn- ->long
  [field v default]
  (cond
    (nil? v) default
    (integer? v) (long v)
    (str/blank? (str v)) default
    :else (try (Long/parseLong (str/trim (str v)))
               (catch NumberFormatException _
                 (invalid-filter! (str "Invalid " field ": must be an integer") {:field field})))))

(defn- ->timestamp
  "Accepts ISO-8601 instants (2026-01-01T10:00:00Z), offset date-times,
   local date-times (as sent by datetime-local inputs) and plain dates."
  [field v]
  (when-not (str/blank? (str v))
    (let [s (str/trim (str v))]
      (or (some (fn [parse]
                  (try (parse s) (catch Exception _ nil)))
                [#(java.sql.Timestamp/from (Instant/parse %))
                 #(java.sql.Timestamp/from (.toInstant (OffsetDateTime/parse %)))
                 #(java.sql.Timestamp/valueOf (LocalDateTime/parse %))
                 #(java.sql.Timestamp/valueOf (.atStartOfDay (LocalDate/parse %)))])
          (invalid-filter! (str "Invalid " field ": expected an ISO-8601 date or date-time")
                           {:field field})))))

(defn search
  "Query the audit log with optional filters.
   Returns {:items [...] :total N :page N :pageSize N}."
  [{:keys [subject-id resource-class decision from to page page-size plane]}]
  (let [page      (max 1 (->long "page" page 1))
        page-size (-> (->long "pageSize" page-size 20) (max 1) (min max-page-size))
        filters   (cond-> []
                    subject-id     (conj ["subject_id = ?" (str subject-id)])
                    resource-class (conj ["resource_class = ?" (str resource-class)])
                    decision       (conj ["decision = ?" (column-str decision)])
                    from           (conj ["ts >= ?" (->timestamp "from" from)])
                    to             (conj ["ts <= ?" (->timestamp "to" to)])
                    ;; ReBAC checks are logged with a "relation:<name>" operation
                    (= "rebac" (column-str plane)) (conj ["operation LIKE ?" "relation:%"])
                    (= "abac" (column-str plane))  (conj ["(operation IS NULL OR operation NOT LIKE ?)" "relation:%"]))
        where      (str/join " AND " (cons "1=1" (map first filters)))
        params     (mapv second filters)
        offset     (* (dec page) page-size)
        count-sql  (str "SELECT COUNT(*) AS n FROM AUDIT_LOG WHERE " where)
        data-sql   (str "SELECT * FROM AUDIT_LOG WHERE " where
                        " ORDER BY id DESC LIMIT ? OFFSET ?")
        total      (or (:n (first (jdbc/query audit-db (into [count-sql] params)))) 0)
        rows       (jdbc/query audit-db (into [data-sql] (conj params page-size offset))
                               {:row-fn normalize-row})]
    {:items    (vec rows)
     :total    total
     :page     page
     :pageSize page-size}))

(defn- audit-row->authz-request
  [row]
  (let [snapshot (:request_snapshot row)
        base-request (if (map? snapshot)
                       snapshot
                       {:subject {:id (:subject_id row)}
                        :resource {:class (:resource_class row)
                                   :id (:resource_id row)}
                        :operation (:operation row)})
        audit-context {:auditReplay true
                       :auditId (:id row)
                       :auditRequestId (:request_id row)
                       :auditDecision (:decision row)
                       :auditTimestamp (:ts row)
                       :auditMatchedRules (:matched_rules row)
                       :auditDecisionSnapshot (:decision_snapshot row)}]
    (update base-request :context merge audit-context)))

(defn replay-requests
  "Build authorization request bodies from audit rows.
   These replay requests contain the audited identifiers and operation; PIPs can
   enrich attributes during simulation."
  [{:keys [limit] :as filters}]
  (let [page-size (or limit (:page-size filters) 100)
        ;; only ABAC decisions can be replayed as authorization requests
        result (search (assoc filters :page 1 :page-size page-size :plane "abac"))]
    {:requests (mapv audit-row->authz-request (:items result))
     :total (:total result)
     :returned (count (:items result))
     :filters (select-keys filters [:subject-id :resource-class :decision :from :to :limit])}))

(defonce ^:private retention-executor (atom nil))

(defn shutdown!
  "Flush all pending async audit writes before JVM exits.
   Waits up to 10 seconds for the audit agent to drain."
  []
  (when-let [^ScheduledExecutorService ex @retention-executor]
    (.shutdownNow ex)
    (reset! retention-executor nil))
  (await-for 10000 audit-agent)
  (.info logger "Audit agent flushed"))

(defn- checkpoint-hmac
  [{:keys [last_purged_id last_purged_hash purged_entries purged_at]} secret]
  (hmac-sha256 (str "checkpoint|v1|" last_purged_id "|" last_purged_hash "|" purged_entries "|"
                    (str (.toLocalDateTime ^java.sql.Timestamp purged_at)))
               secret))

(defn- checkpoint->api
  [checkpoint]
  (when checkpoint
    {:lastPurgedId (:last_purged_id checkpoint)
     :lastPurgedHash (:last_purged_hash checkpoint)
     :purgedEntries (:purged_entries checkpoint)
     :purgedAt (str (.toInstant ^java.sql.Timestamp (:purged_at checkpoint)))}))

(defn- verify-rows
  "Reduces over the chain rows (streamed) starting from start-hash. Records which
   of the `watched` hashes and whether the [id hash] `anchor` occur in the chain."
  [rows start-hash hmac-secret watched anchor]
  (reduce
   (fn [state row]
     (let [version  (some-> (:hash_version row) long)
           prev     (:prev state)
           broken   (fn [reason] (reduced (assoc state :broken {:valid false :broken-at (:id row) :reason reason})))]
       (cond
         (and (nil? version) (:versioned? state))
         (broken "Legacy entry found after versioned entries")

         (and version (not= 2 version))
         (broken (str "Unknown hash version " version))

         (and version (not= (row-payload-hash row) (:payload_hash row)))
         (broken "Payload hash mismatch (entry content altered)")

         (not= prev (:previous_hash row))
         (broken "Previous hash mismatch (entry removed or reordered)")

         (not= (chain-hmac version prev (:payload_hash row) hmac-secret) (:hmac row))
         (broken "HMAC mismatch")

         :else
         (cond-> (-> state
                     (assoc :prev (:payload_hash row) :head-id (:id row))
                     (update :total inc))
           (contains? watched (:payload_hash row)) (update :found conj (:payload_hash row))
           (= anchor [(:id row) (:payload_hash row)]) (assoc :anchor-found? true)
           (nil? version) (update :legacy inc)
           version (assoc :versioned? true)))))
   {:prev start-hash :total 0 :legacy 0 :versioned? false :head-id nil
    :found (if (contains? watched start-hash) #{start-hash} #{}) :anchor-found? false}
   rows))

(defn verify-chain
  "Re-reads all audit entries in order and verifies, for each one, that its
   content still matches payload_hash (hash_version 2), that it links to the
   previous entry and that its HMAC is valid. Verification starts from the
   signed purge checkpoint when one exists.

   Truncation of the most recent entries is detected in three ways:
   - the head recorded in AUDIT_CHAIN_HEAD must be present in the chain;
   - the head last written by this instance must be present in the chain;
   - an optional external anchor {:anchor-id :anchor-hash} (e.g. headId/headHash
     from an earlier verification or a signed evidence bundle) must be present.

   Returns {:valid true :total N :headId .. :headHash .. :legacyEntries N
            :checkpoint {..}} or {:valid false :broken-at id :reason reason}."
  ([] (verify-chain {}))
  ([{:keys [anchor-id anchor-hash]}]
   (let [hmac-secret (require-hmac-secret!)
         anchor-id   (->long "anchorId" anchor-id nil)
         _           (await-for 10000 audit-agent)
         known-head  @last-hash]
     (jdbc/with-db-transaction [tx audit-db]
       (let [stored-head (:last_hash (first (jdbc/query tx ["SELECT last_hash FROM AUDIT_CHAIN_HEAD WHERE id = 1"])))
             checkpoint  (read-checkpoint tx)]
         (if (and checkpoint (not= (checkpoint-hmac checkpoint hmac-secret) (:hmac checkpoint)))
           {:valid false :broken-at nil :reason "Purge checkpoint HMAC mismatch"}
           (let [start  (or (:last_purged_hash checkpoint) zero-hash)
                 state  (jdbc/query tx ["SELECT * FROM AUDIT_LOG ORDER BY id ASC"]
                                    {:fetch-size 500
                                     :row-fn #(-> %
                                                  (update :request_snapshot sql-value->str)
                                                  (update :decision_snapshot sql-value->str))
                                     :result-set-fn #(verify-rows % start hmac-secret
                                                                  (set (remove nil? [stored-head known-head]))
                                                                  (when anchor-id [anchor-id anchor-hash]))})
                 anchor-purged? (and anchor-id checkpoint (<= anchor-id (:last_purged_id checkpoint)))]
             (cond
               (:broken state)
               (:broken state)

               (and stored-head (not (contains? (:found state) stored-head)))
               {:valid false :broken-at nil
                :reason "Recorded chain head is missing from the chain (latest entries removed)"}

               (not (contains? (:found state) known-head))
               {:valid false :broken-at nil
                :reason "Chain head written by this instance is missing (latest entries removed)"}

               (and anchor-id (not anchor-purged?)
                    (not (:anchor-found? state)))
               {:valid false :broken-at anchor-id
                :reason "Anchor entry not found in chain (entries removed or rewritten)"}

               :else
               (cond-> {:valid true
                        :total (:total state)
                        :headId (:head-id state)
                        :headHash (:prev state)
                        :legacyEntries (:legacy state)}
                 checkpoint (assoc :checkpoint (checkpoint->api checkpoint))
                 anchor-purged? (assoc :anchorPurged true))))))))))

(defn purge-before!
  "Deletes the oldest entries (a contiguous prefix of the chain) whose timestamp
   is before `before`, and records an HMAC-signed checkpoint holding the hash of
   the last purged entry so that verification of the remaining chain resumes
   from it. Refused when the chain does not verify, so that a purge cannot
   launder tampered entries."
  [before]
  (let [cutoff-ts   (or (->timestamp "before" before)
                        (invalid-filter! "Missing before: expected an ISO-8601 date or date-time"
                                         {:field "before"}))
        hmac-secret (require-hmac-secret!)
        check       (verify-chain)]
    (when-not (:valid check)
      (throw (ex-info "Audit chain does not verify; purge refused"
                      {:status 409 :error-code "AUDIT_CHAIN_INVALID" :verification check})))
    (jdbc/with-db-transaction [tx audit-db]
      (lock-chain-head! tx)
      (if-let [cutoff (first (jdbc/query tx [(str "SELECT id, payload_hash FROM AUDIT_LOG WHERE ts < ?"
                                                    " ORDER BY id DESC LIMIT 1")
                                               cutoff-ts]))]
        (let [purged     (first (jdbc/execute! tx ["DELETE FROM AUDIT_LOG WHERE id <= ?" (:id cutoff)]))
              previous   (read-checkpoint tx)
              checkpoint {:id               1
                          :last_purged_id   (:id cutoff)
                          :last_purged_hash (:payload_hash cutoff)
                          :purged_entries   (+ (or (:purged_entries previous) 0) purged)
                          :purged_at        (java.sql.Timestamp/from
                                             (.truncatedTo (Instant/now) ChronoUnit/MILLIS))}
              checkpoint (assoc checkpoint :hmac (checkpoint-hmac checkpoint hmac-secret))]
          (jdbc/execute! tx ["DELETE FROM AUDIT_CHECKPOINT WHERE id = 1"])
          (jdbc/insert! tx :audit_checkpoint checkpoint)
          (.info logger "Audit log purged: {} entries before {}" purged (str cutoff-ts))
          {:purged purged :checkpoint (checkpoint->api checkpoint)})
        {:purged 0 :checkpoint (checkpoint->api (read-checkpoint tx))}))))

(defn- retention-days []
  (some-> (System/getenv "AUDIT_RETENTION_DAYS") str/trim not-empty Long/parseLong))

(defn- schedule-retention!
  "When AUDIT_RETENTION_DAYS is set, purges entries older than that many days
   every 24 hours (first run 5 minutes after startup)."
  []
  (try
    (when-let [days (retention-days)]
      (when (and (pos? days) (nil? @retention-executor))
        (let [ex (Executors/newSingleThreadScheduledExecutor
                  (reify java.util.concurrent.ThreadFactory
                    (newThread [_ r] (doto (Thread. ^Runnable r "autho-audit-retention")
                                       (.setDaemon true)))))]
          (reset! retention-executor ex)
          (.scheduleWithFixedDelay ex
                                   (fn []
                                     (try
                                       (purge-before! (str (.minus (Instant/now) days ChronoUnit/DAYS)))
                                       (catch Throwable e
                                         (.error logger "Scheduled audit purge failed: {}" (.getMessage e) e))))
                                   5 (* 24 60) TimeUnit/MINUTES)
          (.info logger "Audit retention enabled: {} days" days))))
    (catch Exception e
      (.error logger "Invalid AUDIT_RETENTION_DAYS: {}" (.getMessage e)))))
