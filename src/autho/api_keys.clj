(ns autho.api-keys
  "Persistent API-key registry.

   Keys are returned exactly once at creation time. The database only stores
   a SHA-256 digest of the secret portion of a key in the form
   `ak_<key-id>_<secret>`. The environment API_KEY remains a bootstrap
   credential and is intentionally handled by autho.auth as a fallback."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [autho.jdbc-utils :as jdbc-utils]
            [autho.prp :as prp])
  (:import (java.math BigInteger)
           (java.security MessageDigest SecureRandom)
           (java.time Instant)
           (java.util Base64 UUID)
           (org.slf4j LoggerFactory)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.api-keys"))
(def ^:private random (SecureRandom.))

(defn- now [] (str (Instant/now)))

(defn- csv-values [value]
  (->> (cond
         (nil? value) []
         (string? value) (str/split value #",")
         (sequential? value) value
         (set? value) value
         :else [value])
       (map str)
       (map str/trim)
       (remove str/blank?)
       distinct
       vec))

(defn- csv [value]
  (let [values (csv-values value)]
    (when (seq values) (str/join "," values))))

(defn- sha256 [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest (.getBytes ^String value "UTF-8"))))))

(defn- constant-time-equals? [a b]
  (and a b (MessageDigest/isEqual (.getBytes ^String a "UTF-8")
                                  (.getBytes ^String b "UTF-8"))))

(defn- random-secret []
  (let [bytes (byte-array 32)]
    (.nextBytes random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- normalize-row [row]
  (-> row
      (update :roles #(csv-values (jdbc-utils/clob->string %)))
      (update :tenants #(csv-values (jdbc-utils/clob->string %)))
      (update :organizations #(csv-values (jdbc-utils/clob->string %)))
      (update :projects #(csv-values (jdbc-utils/clob->string %)))
      (update :environments #(csv-values (jdbc-utils/clob->string %)))
      (update :created_at #(some-> % str))
      (update :last_used_at #(some-> % str))
      (update :expires_at #(some-> % str))
      (update :revoked_at #(some-> % str))))

(defn- public-key [row]
  (select-keys (normalize-row row)
               [:key_id :name :client_id :client_class :roles :tenants
                :organizations :projects :environments :created_at :last_used_at
                :expires_at :revoked_at]))

(defn- parse-expiry
  [value]
  (when value
    (try
      (java.sql.Timestamp/valueOf (str/replace (str value) "T" " "))
      (catch IllegalArgumentException _
        (throw (ex-info "expiresAt must be an ISO-8601 timestamp"
                        {:status 400 :error-code "INVALID_API_KEY"}))))))

(defn- expired? [timestamp]
  (and timestamp
       (not (.isAfter (.toInstant ^java.sql.Timestamp timestamp) (Instant/now)))))

(defn init!
  []
  (jdbc/execute! prp/h2db
                 ["CREATE TABLE IF NOT EXISTS API_KEYS (
                     key_id VARCHAR(64) PRIMARY KEY,
                     name VARCHAR(255) NOT NULL,
                     secret_hash VARCHAR(128) NOT NULL,
                     client_id VARCHAR(255) NOT NULL,
                     client_class VARCHAR(255) NOT NULL,
                     roles CLOB,
                     tenants CLOB,
                     organizations CLOB,
                     projects CLOB,
                     environments CLOB,
                     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                     last_used_at TIMESTAMP,
                     expires_at TIMESTAMP,
                     revoked_at TIMESTAMP
                   )"])
  (jdbc/execute! prp/h2db ["CREATE INDEX IF NOT EXISTS IDX_API_KEYS_ACTIVE ON API_KEYS (key_id, revoked_at)"])
  (.info logger "API_KEYS table ready"))

(defn create-key!
  [{:keys [name clientId clientClass roles tenants organizations projects environments expiresAt]}]
  (when (str/blank? (str name))
    (throw (ex-info "API key name is required" {:status 400 :error-code "INVALID_API_KEY"})))
  (when (str/blank? (str clientId))
    (throw (ex-info "API key clientId is required" {:status 400 :error-code "INVALID_API_KEY"})))
  (let [key-id (str (UUID/randomUUID))
        secret (random-secret)
        raw-key (str "ak_" key-id "_" secret)
        row {:key_id key-id
             :name (str name)
             :secret_hash (sha256 secret)
             :client_id (str clientId)
             :client_class (str (or clientClass "Application"))
             :roles (csv roles)
             :tenants (csv tenants)
             :organizations (csv organizations)
             :projects (csv projects)
             :environments (csv environments)
             :expires_at (parse-expiry expiresAt)}]
    (jdbc/insert! prp/h2db :api_keys row)
    (assoc (public-key row) :apiKey raw-key)))

(def ^:private readable-columns
  "key_id, name, secret_hash, client_id, client_class,
   CAST(roles AS VARCHAR) AS roles,
   CAST(tenants AS VARCHAR) AS tenants,
   CAST(organizations AS VARCHAR) AS organizations,
   CAST(projects AS VARCHAR) AS projects,
   CAST(environments AS VARCHAR) AS environments,
   created_at, last_used_at, expires_at, revoked_at")

(defn list-keys []
  (mapv public-key (jdbc/query prp/h2db [(str "SELECT " readable-columns " FROM API_KEYS ORDER BY created_at DESC")])))

(defn get-key [key-id]
  (some-> (first (jdbc/query prp/h2db [(str "SELECT " readable-columns " FROM API_KEYS WHERE key_id = ?") key-id])) public-key))

(defn revoke-key!
  [key-id]
  (let [updated (jdbc/update! prp/h2db :api_keys {:revoked_at (now)} ["key_id = ? AND revoked_at IS NULL" key-id])]
    (when-not (pos? (first updated))
      (throw (ex-info "API key not found or already revoked" {:status 404 :error-code "API_KEY_NOT_FOUND"})))
    (get-key key-id)))

(defn authenticate
  "Returns the API-key identity for a valid registry key, otherwise nil.
   A revoked or expired key deliberately has the same external behavior as an
   unknown key; callers must not learn which key ids exist."
  [token]
  (when (and (string? token) (str/starts-with? token "ak_"))
    (let [[_ key-id secret] (re-matches #"ak_([^_]+)_([A-Za-z0-9_-]+)" token)
          row (when key-id
                (first (jdbc/query prp/h2db [(str "SELECT " readable-columns " FROM API_KEYS WHERE key_id = ?") key-id])))
          raw-row row
          row (when row (normalize-row row))
          active? (and row
                       (nil? (:revoked_at row))
                       (not (expired? (:expires_at raw-row)))
                       (constant-time-equals? (:secret_hash row) (sha256 secret)))]
      (when active?
        (jdbc/update! prp/h2db :api_keys {:last_used_at (now)} ["key_id = ?" key-id])
        {:auth-method :api-key-registry
         :api-key-id key-id
         :client-id (:client_id row)
         :roles (:roles row)
         :tenants (:tenants row)
         :organizations (:organizations row)
         :projects (:projects row)
         :environments (:environments row)
         :subject {:id (:client_id row)
                   :class (:client_class row)
                   :client-id (:client_id row)
                   :roles (:roles row)}}))))

(defn clear-keys!
  "Test/support helper: removes all persistent keys. Never exposed as an API."
  []
  (jdbc/execute! prp/h2db ["DELETE FROM API_KEYS"]))
