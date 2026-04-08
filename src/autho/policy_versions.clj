(ns autho.policy-versions
  "Persistent policy version history stored in H2.
   Every call to submit-policy appends a new version row.
   Supports list, get, diff, and rollback operations."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.data.json :as json]
            [autho.jdbc-utils :as jdbc-utils]
            [autho.policy-format :as policy-format]
            [clojure.set :as set]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u])
  (:import (org.slf4j LoggerFactory)
           (java.time Instant)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.policy-versions"))

;; ---------------------------------------------------------------------------
;; DB -- shares the PRP H2 database
;; ---------------------------------------------------------------------------

(def ^:private h2-policy-cipher-key (System/getenv "H2_POLICY_CIPHER_KEY"))

(def ^:private db
  (merge
   {:classname "org.h2.Driver"
    :subprotocol "h2"
    :user "sa"}
   (if h2-policy-cipher-key
     {:subname "./resources/h2db;CIPHER=AES"
      :password (str h2-policy-cipher-key " ")}
     {:subname "./resources/h2db"
      :password ""})))

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
         source_analysis_id BIGINT,
         deployment_kind VARCHAR(50),
         source_candidate_version INT,
         created_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
         UNIQUE (resource_class, version)
       )"])
    (jdbc/execute! db ["ALTER TABLE POLICY_VERSIONS ADD COLUMN IF NOT EXISTS source_analysis_id BIGINT"])
    (jdbc/execute! db ["ALTER TABLE POLICY_VERSIONS ADD COLUMN IF NOT EXISTS deployment_kind VARCHAR(50)"])
    (jdbc/execute! db ["ALTER TABLE POLICY_VERSIONS ADD COLUMN IF NOT EXISTS source_candidate_version INT"])
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

(defn- index-rules
  [policy]
  (into {} (map (juxt :name identity) (:rules policy))))

(defn- ordered-rule-names
  [rules]
  (->> rules (map :name) sort vec))

(defn- changed-rule-fields
  [before after]
  (->> (set/union (set (keys before)) (set (keys after)))
       (remove #{:name})
       sort
       (filter #(not= (get before %) (get after %)))
       (mapv name)))

(defn- build-diff-summary
  [strategy-changed? added removed changed unchanged]
  {:strategyChanged strategy-changed?
   :addedRules (count added)
   :removedRules (count removed)
   :changedRules (count changed)
   :unchangedRules (count unchanged)
   :totalRuleChanges (+ (count added) (count removed) (count changed))})

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn save-version!
  "Record a new version of the policy for resource-class.
   author and comment are optional strings from the request context."
  [resource-class policy-map author comment]
  (try
    (let [v (next-version resource-class)
          policy-str (json/write-str policy-map)]
      (jdbc/insert! db :policy_versions
                    {:resource_class resource-class
                     :version v
                     :policy_json policy-str
                     :author (or author "system")
                     :comment (or comment "")
                     :created_at (java.sql.Timestamp/from (Instant/now))})
      (u/log ::policy-versioned :resource-class resource-class :version v)
      (.info logger "Saved policy version {} for {}" v resource-class)
      v)
    (catch Exception e
      (.error logger "Failed to save policy version for {}: {}" resource-class (.getMessage e) e)
      nil)))

(defn annotate-version!
  "Attach rollout metadata to an existing version row."
  [resource-class version {:keys [sourceAnalysisId deploymentKind sourceCandidateVersion]}]
  (jdbc/update! db :policy_versions
                {:source_analysis_id sourceAnalysisId
                 :deployment_kind deploymentKind
                 :source_candidate_version sourceCandidateVersion}
                ["resource_class = ? AND version = ?" resource-class (int version)])
  (first (jdbc/query db
                     ["SELECT id, resource_class, version, author, comment,
                              source_analysis_id, deployment_kind, source_candidate_version, created_at
                         FROM POLICY_VERSIONS
                        WHERE resource_class = ? AND version = ?"
                      resource-class (int version)])))

(defn list-versions
  "Returns all versions for a resource class, newest first.
   Includes rollout linkage metadata when available."
  [resource-class]
  (jdbc/query db
              ["SELECT id, resource_class, version, author, comment,
                       source_analysis_id, deployment_kind, source_candidate_version, created_at
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
                                     resource-class (int version)]
                                    {:row-fn #(update % :policy_json jdbc-utils/clob->string)}))]
    (-> (:policy_json row)
        (json/read-str :key-fn keyword)
        (policy-format/normalize-policy))))

(defn list-versions-by-analysis
  "Returns deployed versions linked to a source impact analysis."
  [resource-class analysis-id]
  (jdbc/query db
              ["SELECT id, resource_class, version, author, comment,
                       source_analysis_id, deployment_kind, source_candidate_version, created_at
                  FROM POLICY_VERSIONS
                 WHERE resource_class = ? AND source_analysis_id = ?
              ORDER BY version DESC"
               resource-class (long analysis-id)]))
(defn get-version-details
  "Returns policy version metadata together with the policy body."
  [resource-class version]
  (when-let [row (first (jdbc/query db
                                    ["SELECT id, resource_class, version, policy_json, author, comment,
                                             source_analysis_id, deployment_kind, source_candidate_version, created_at
                                        FROM POLICY_VERSIONS
                                       WHERE resource_class = ? AND version = ?"
                                     resource-class (int version)]
                                    {:row-fn #(update % :policy_json jdbc-utils/clob->string)}))]
    {:id (:id row)
     :resourceClass (:resource_class row)
     :version (:version row)
     :author (:author row)
     :comment (:comment row)
     :sourceAnalysisId (:source_analysis_id row)
     :deploymentKind (:deployment_kind row)
     :sourceCandidateVersion (:source_candidate_version row)
     :createdAt (:created_at row)
     :policy (-> (:policy_json row)
                 (json/read-str :key-fn keyword)
                 (policy-format/normalize-policy))}))

(defn latest-version-number
  "Returns the current (highest) version number for a resource class, or 0."
  [resource-class]
  (let [row (first (jdbc/query db
                               ["SELECT MAX(version) AS v FROM POLICY_VERSIONS WHERE resource_class = ?"
                                resource-class]))]
    (or (:v row) 0)))

(defn diff-versions
  "Returns a richer map comparing two policy versions.
   Includes strategy change, per-rule additions/removals, and detailed changed rules."
  [resource-class v-from v-to]
  (let [pol-from (get-version resource-class v-from)
        pol-to (get-version resource-class v-to)]
    (when (and pol-from pol-to)
      (let [idx-from (index-rules pol-from)
            idx-to (index-rules pol-to)
            rules-from (set (keys idx-from))
            rules-to (set (keys idx-to))
            added-names (sort (set/difference rules-to rules-from))
            removed-names (sort (set/difference rules-from rules-to))
            common-names (sort (set/intersection rules-from rules-to))
            changed-names (filter #(not= (get idx-from %) (get idx-to %)) common-names)
            unchanged-names (filter #(= (get idx-from %) (get idx-to %)) common-names)
            added (mapv idx-to added-names)
            removed (mapv idx-from removed-names)
            changed (mapv (fn [rule-name]
                            {:name rule-name
                             :changedFields (changed-rule-fields (get idx-from rule-name)
                                                                 (get idx-to rule-name))
                             :before (get idx-from rule-name)
                             :after (get idx-to rule-name)})
                          changed-names)
            strategy-changed? (not= (:strategy pol-from) (:strategy pol-to))]
        {:resourceClass resource-class
         :from v-from
         :to v-to
         :strategy {:from (:strategy pol-from)
                    :to (:strategy pol-to)
                    :changed strategy-changed?}
         :summary (build-diff-summary strategy-changed? added removed changed unchanged-names)
         :rules {:added added
                 :removed removed
                 :changed changed
                 :unchanged (vec unchanged-names)}}))))



