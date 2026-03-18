(ns autho.policy-versions
  "Persistent policy version history stored in H2.
   Every call to submit-policy appends a new version row.
   Supports list, get, diff, and rollback operations."
  (:require [clojure.java.jdbc  :as jdbc]
            [clojure.data.json  :as json]
            [clojure.string     :as str]
            [com.brunobonacci.mulog :as u])
  (:import (org.slf4j LoggerFactory)
           (java.time Instant)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.policy-versions"))

;; ---------------------------------------------------------------------------
;; DB — shares the PRP H2 database
;; ---------------------------------------------------------------------------

(def ^:private db
  {:classname   "org.h2.Driver"
   :subprotocol "h2"
   :subname     "./resources/h2db"
   :user        "sa"
   :password    ""})

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(defn init!
  "Create POLICY_VERSIONS table if it does not exist. Called from pdp/init."
  []
  (try
    (jdbc/execute! db
     ["CREATE TABLE IF NOT EXISTS POLICY_VERSIONS (
         id             BIGINT AUTO_INCREMENT PRIMARY KEY,
         resource_class VARCHAR(255) NOT NULL,
         version        INT          NOT NULL,
         policy_json    CLOB         NOT NULL,
         author         VARCHAR(255),
         comment        VARCHAR(500),
         created_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
         UNIQUE (resource_class, version)
       )"])
    (.info logger "POLICY_VERSIONS table ready")
    (catch Exception e
      (.error logger "Failed to create POLICY_VERSIONS table: {}" (.getMessage e) e))))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- next-version
  "Returns the next version number for the given resource class (1 if first)."
  [resource-class]
  (let [row (first (jdbc/query db
               ["SELECT MAX(version) AS v FROM POLICY_VERSIONS WHERE resource_class = ?"
                resource-class]))]
    (inc (or (:v row) 0))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn save-version!
  "Record a new version of the policy for resource-class.
   author and comment are optional strings from the request context."
  [resource-class policy-map author comment]
  (try
    (let [v          (next-version resource-class)
          policy-str (json/write-str policy-map)]
      (jdbc/insert! db :policy_versions
                    {:resource_class resource-class
                     :version        v
                     :policy_json    policy-str
                     :author         (or author "system")
                     :comment        (or comment "")
                     :created_at     (java.sql.Timestamp/from (Instant/now))})
      (u/log ::policy-versioned :resource-class resource-class :version v)
      (.info logger "Saved policy version {} for {}" v resource-class)
      v)
    (catch Exception e
      (.error logger "Failed to save policy version for {}: {}" resource-class (.getMessage e) e)
      nil)))

(defn list-versions
  "Returns all versions for a resource class, newest first.
   Each entry: {:id :resource_class :version :author :comment :created_at} (no body)."
  [resource-class]
  (jdbc/query db
    ["SELECT id, resource_class, version, author, comment, created_at
        FROM POLICY_VERSIONS
       WHERE resource_class = ?
       ORDER BY version DESC"
     resource-class]))

(defn get-version
  "Returns the full policy map for a specific version, or nil if not found."
  [resource-class version]
  (when-let [row (first (jdbc/query db
                   ["SELECT policy_json FROM POLICY_VERSIONS
                      WHERE resource_class = ? AND version = ?"
                    resource-class (int version)]))]
    (json/read-str (:policy_json row) :key-fn keyword)))

(defn latest-version-number
  "Returns the current (highest) version number for a resource class, or 0."
  [resource-class]
  (let [row (first (jdbc/query db
               ["SELECT MAX(version) AS v FROM POLICY_VERSIONS WHERE resource_class = ?"
                resource-class]))]
    (or (:v row) 0)))

(defn diff-versions
  "Returns a map {:added [...] :removed [...] :changed [...]} comparing two policy versions.
   Compares rule names at the top level (structural diff of the :rules vector)."
  [resource-class v-from v-to]
  (let [pol-from (get-version resource-class v-from)
        pol-to   (get-version resource-class v-to)]
    (when (and pol-from pol-to)
      (let [rules-from (set (map :name (:rules pol-from)))
            rules-to   (set (map :name (:rules pol-to)))
            added      (vec (clojure.set/difference rules-to   rules-from))
            removed    (vec (clojure.set/difference rules-from rules-to))
            ;; Changed: same name but different content
            common     (clojure.set/intersection rules-from rules-to)
            idx-from   (into {} (map (juxt :name identity) (:rules pol-from)))
            idx-to     (into {} (map (juxt :name identity) (:rules pol-to)))
            changed    (vec (filter #(not= (get idx-from %) (get idx-to %)) common))]
        {:from     v-from
         :to       v-to
         :strategy {:from (:strategy pol-from) :to (:strategy pol-to)}
         :rules    {:added added :removed removed :changed changed}}))))
