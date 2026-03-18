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

(def ^:private audit-db
  {:classname   "org.h2.Driver"
   :subprotocol "h2"
   :subname     "./resources/auditdb;AUTO_SERVER=TRUE"
   :user        "sa"
   :password    ""})

;; HMAC secret loaded once at startup — rotate by restarting the server.
;; For chain verification across restarts the same secret must be used.
(defonce ^:private hmac-secret
  (or (System/getenv "AUDIT_HMAC_SECRET") "default-dev-secret-change-in-prod"))

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
  (try
    (create-table-if-absent!)
    (load-last-hash!)
    (.info logger "Audit log initialised")
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

(defn search
  "Query the audit log with optional filters.
   Params map keys: :subject-id :resource-class :decision :from (Instant) :to (Instant) :page :page-size"
  [{:keys [subject-id resource-class decision from to page page-size]
    :or   {page 1 page-size 20}}]
  (let [conditions (cond-> ["1=1"]
                     subject-id     (conj (str "subject_id = '" subject-id "'"))
                     resource-class (conj (str "resource_class = '" resource-class "'"))
                     decision       (conj (str "decision = '" (name decision) "'"))
                     from           (conj (str "ts >= '" from "'"))
                     to             (conj (str "ts <= '" to "'")))
        where      (str/join " AND " conditions)
        offset     (* (dec page) page-size)
        sql        (str "SELECT * FROM AUDIT_LOG WHERE " where
                        " ORDER BY id DESC LIMIT " page-size " OFFSET " offset)]
    (jdbc/query audit-db [sql])))

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
  (let [rows (jdbc/query audit-db ["SELECT * FROM AUDIT_LOG ORDER BY id ASC"])]
    (loop [remaining rows
           prev-hash  "0000000000000000000000000000000000000000000000000000000000000000"]
      (if-let [row (first remaining)]
        (let [expected-hmac (hmac-sha256 (str prev-hash (:payload_hash row)) hmac-secret)]
          (if (= expected-hmac (:hmac row))
            (recur (rest remaining) (:payload_hash row))
            {:valid false :broken-at (:id row) :reason "HMAC mismatch"}))
        {:valid true :total (count rows)}))))
