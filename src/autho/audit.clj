(ns autho.audit
  "Immutable audit log for authorization decisions.
   Each entry is HMAC-SHA256 signed and chained via hash for tamper detection.
   Storage: H2 file database (separate from the policy store).
   Writes are async via a Clojure agent to stay off the critical path."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u])
  (:import (java.time Instant)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (java.security MessageDigest)
           (org.slf4j LoggerFactory)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.audit"))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

;; H2_AUDIT_CIPHER_KEY enables AES-128 at-rest encryption of the audit database.
;; If set (≥ 32 chars recommended), the database file is encrypted transparently.
;; WARNING: changing this key on an existing unencrypted database requires migration
;; (see docs/SECURITY_ADMIN_GUIDE.md § "Migration vers le chiffrement H2").
;; If unset, the database is stored unencrypted — acceptable for development only.
(def ^:private h2-audit-cipher-key (System/getenv "H2_AUDIT_CIPHER_KEY"))

(def ^:private audit-db
  (merge
   {:classname   "org.h2.Driver"
    :subprotocol "h2"
    :user        "sa"}
   (if h2-audit-cipher-key
     {:subname  "./resources/auditdb;AUTO_SERVER=TRUE;CIPHER=AES"
      :password (str h2-audit-cipher-key " ")}
     {:subname  "./resources/auditdb;AUTO_SERVER=TRUE"
      :password ""})))

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
    ["CREATE TABLE IF NOT EXISTS AUDIT_LOG (
        id            BIGINT AUTO_INCREMENT PRIMARY KEY,
        ts            TIMESTAMP    NOT NULL,
        request_id    VARCHAR(36),
        subject_id    VARCHAR(255),
        resource_class VARCHAR(255),
        resource_id   VARCHAR(255),
        operation     VARCHAR(100),
        decision      VARCHAR(10),
        matched_rules VARCHAR(2048),
        payload_hash  CHAR(64)     NOT NULL,
        previous_hash CHAR(64)     NOT NULL,
        hmac          CHAR(64)     NOT NULL
      )"]))

;; Holds the hash of the last inserted row for chain continuity
(defonce ^:private last-hash (atom "0000000000000000000000000000000000000000000000000000000000000000"))

(defn- load-last-hash! []
  (let [row (first (jdbc/query audit-db
                     ["SELECT payload_hash FROM AUDIT_LOG ORDER BY id DESC LIMIT 1"]))]
    (when row
      (reset! last-hash (:payload_hash row)))))

;; ---------------------------------------------------------------------------
;; Async agent — all DB writes are serialised through this agent
;; ---------------------------------------------------------------------------

(defonce ^:private audit-agent (agent nil :error-handler
                                 (fn [_ e]
                                   (.error logger "Audit agent error: {}" (.getMessage e) e))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn init!
  "Create the audit table and load the last chain hash. Call from pdp/init."
  []
  (when-not h2-audit-cipher-key
    (.warn logger "H2_AUDIT_CIPHER_KEY is not set — audit database is stored UNENCRYPTED. Set this variable in production."))
  (try
    (require-hmac-secret!)
    (create-table-if-absent!)
    (load-last-hash!)
    (.info logger "Audit log initialised (encryption: {})"
           (if h2-audit-cipher-key "AES/CIPHER" "none"))
    (catch Exception e
      (.error logger "Failed to initialise audit log: {}" (.getMessage e) e))))

(defn log-decision!
  "Asynchronously records an authorization decision in the audit log.
   Non-blocking: returns immediately; actual insert happens via the audit-agent."
  [{:keys [request-id subject-id resource-class resource-id operation decision matched-rules]}]
  (send audit-agent
        (fn [_]
          (try
            (let [payload      (json/write-str
                                {:ts            (str (Instant/now))
                                 :request-id    request-id
                                 :subject-id    subject-id
                                 :resource-class resource-class
                                 :resource-id   resource-id
                                 :operation     operation
                                 :decision      decision
                                 :matched-rules matched-rules})
                  payload-hash (sha256 payload)
                  prev-hash    @last-hash
                  hmac-secret  (require-hmac-secret!)
                  hmac         (hmac-sha256 (str prev-hash payload-hash) hmac-secret)]
              (jdbc/insert! audit-db :audit_log
                            {:ts             (java.sql.Timestamp/from (Instant/now))
                             :request_id     request-id
                             :subject_id     subject-id
                             :resource_class resource-class
                             :resource_id    resource-id
                             :operation      operation
                             :decision       (name decision)
                             :matched_rules  (str matched-rules)
                             :payload_hash   payload-hash
                             :previous_hash  prev-hash
                             :hmac           hmac})
              (reset! last-hash payload-hash)
              (u/log ::decision-logged :decision decision :subject-id subject-id))
            (catch Exception e
              (.error logger "Failed to write audit entry: {}" (.getMessage e) e)))))
  nil)

(defn- normalize-row
  "Convert JDBC row to a JSON-safe map:
   - java.sql.Timestamp → ISO-8601 string
   - matched_rules string → vector of rule names"
  [row]
  (-> row
      (update :ts #(when % (.toString ^java.sql.Timestamp %)))
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
                                  vec)))))))))

(defn search
  "Query the audit log with optional filters.
   Returns {:items [...] :total N :page N :pageSize N}."
  [{:keys [subject-id resource-class decision from to page page-size]}]
  (let [page      (or page 1)
        page-size (or page-size 20)
        conditions (cond-> ["1=1"]
                     subject-id     (conj (str "subject_id = '" subject-id "'"))
                     resource-class (conj (str "resource_class = '" resource-class "'"))
                     decision       (conj (str "decision = '" (name decision) "'"))
                     from           (conj (str "ts >= '" from "'"))
                     to             (conj (str "ts <= '" to "'")))
        where      (str/join " AND " conditions)
        offset     (* (dec page) page-size)
        count-sql  (str "SELECT COUNT(*) AS n FROM AUDIT_LOG WHERE " where)
        data-sql   (str "SELECT * FROM AUDIT_LOG WHERE " where
                        " ORDER BY id DESC LIMIT " page-size " OFFSET " offset)
        total      (or (:n (first (jdbc/query audit-db [count-sql]))) 0)
        rows       (map normalize-row (jdbc/query audit-db [data-sql]))]
    {:items    (vec rows)
     :total    total
     :page     page
     :pageSize page-size}))

(defn shutdown!
  "Flush all pending async audit writes before JVM exits.
   Waits up to 10 seconds for the audit agent to drain."
  []
  (await-for 10000 audit-agent)
  (.info logger "Audit agent flushed"))

(defn verify-chain
  "Re-reads all audit entries in order and verifies the HMAC chain.
   Returns {:valid true} or {:valid false :broken-at id :reason reason}."
  []
  (let [hmac-secret (require-hmac-secret!)
        rows (jdbc/query audit-db ["SELECT * FROM AUDIT_LOG ORDER BY id ASC"])]
    (loop [remaining rows
           prev-hash  "0000000000000000000000000000000000000000000000000000000000000000"]
      (if-let [row (first remaining)]
        (let [expected-hmac (hmac-sha256 (str prev-hash (:payload_hash row)) hmac-secret)]
          (if (= expected-hmac (:hmac row))
            (recur (rest remaining) (:payload_hash row))
            {:valid false :broken-at (:id row) :reason "HMAC mismatch"}))
        {:valid true :total (count rows)}))))
