(ns autho.policy-impact-history
  "Persistent storage for policy impact previews in H2."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.data.json :as json]
            [autho.jdbc-utils :as jdbc-utils])
  (:import (org.slf4j LoggerFactory)
           (java.time Instant)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.policy-impact-history"))

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

(def allowed-review-statuses #{"draft" "reviewed" "approved" "rejected"})
(def allowed-rollout-statuses #{"not_deployed" "deployed"})

(defn- row->history-entry
  [row]
  {:id (:id row)
   :resourceClass (:resource_class row)
   :baselineVersion (:baseline_version row)
   :candidateVersion (:candidate_version row)
   :candidateSource (:candidate_source row)
   :author (:author row)
   :requestCount (:request_count row)
   :changedDecisions (:changed_decisions row)
   :revokeCount (:revoke_count row)
   :highRisk (:high_risk row)
   :reviewStatus (:review_status row)
   :reviewedBy (:reviewed_by row)
   :reviewNote (:review_note row)
   :reviewedAt (:reviewed_at row)
   :rolloutStatus (:rollout_status row)
   :deployedVersion (:deployed_version row)
   :deployedBy (:deployed_by row)
   :deployedAt (:deployed_at row)
   :createdAt (:created_at row)})

(defn init!
  []
  (try
    (jdbc/execute! db
                   ["CREATE TABLE IF NOT EXISTS POLICY_IMPACT_HISTORY (
         id                BIGINT AUTO_INCREMENT PRIMARY KEY,
         resource_class    VARCHAR(255) NOT NULL,
         baseline_version  INT,
         candidate_version INT,
         candidate_source  VARCHAR(50),
         author            VARCHAR(255),
         request_count     INT,
         changed_decisions INT,
         revoke_count      INT,
         high_risk         BOOLEAN,
         review_status     VARCHAR(50) DEFAULT 'draft',
         reviewed_by       VARCHAR(255),
         review_note       VARCHAR(2000),
         reviewed_at       TIMESTAMP,
         rollout_status    VARCHAR(50) DEFAULT 'not_deployed',
         deployed_version  INT,
         deployed_by       VARCHAR(255),
         deployed_at       TIMESTAMP,
         candidate_policy_json CLOB,
         analysis_json     CLOB NOT NULL,
         created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
       )"])
    (jdbc/execute! db ["ALTER TABLE POLICY_IMPACT_HISTORY ADD COLUMN IF NOT EXISTS review_status VARCHAR(50) DEFAULT 'draft'"])
    (jdbc/execute! db ["ALTER TABLE POLICY_IMPACT_HISTORY ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(255)"])
    (jdbc/execute! db ["ALTER TABLE POLICY_IMPACT_HISTORY ADD COLUMN IF NOT EXISTS review_note VARCHAR(2000)"])
    (jdbc/execute! db ["ALTER TABLE POLICY_IMPACT_HISTORY ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP"])
    (jdbc/execute! db ["ALTER TABLE POLICY_IMPACT_HISTORY ADD COLUMN IF NOT EXISTS rollout_status VARCHAR(50) DEFAULT 'not_deployed'"])
    (jdbc/execute! db ["ALTER TABLE POLICY_IMPACT_HISTORY ADD COLUMN IF NOT EXISTS deployed_version INT"])
    (jdbc/execute! db ["ALTER TABLE POLICY_IMPACT_HISTORY ADD COLUMN IF NOT EXISTS deployed_by VARCHAR(255)"])
    (jdbc/execute! db ["ALTER TABLE POLICY_IMPACT_HISTORY ADD COLUMN IF NOT EXISTS deployed_at TIMESTAMP"])
    (jdbc/execute! db ["ALTER TABLE POLICY_IMPACT_HISTORY ADD COLUMN IF NOT EXISTS candidate_policy_json CLOB"])
    (.info logger "POLICY_IMPACT_HISTORY table ready")
    (catch Exception e
      (.error logger "Failed to create POLICY_IMPACT_HISTORY table: {}" (.getMessage e) e))))

(defn save-analysis!
  [resource-class body analysis author]
  (try
    (let [record {:resource_class resource-class
                  :baseline_version (:baselineVersion body)
                  :candidate_version (:candidateVersion body)
                  :candidate_source (get-in analysis [:candidate :source])
                  :author (or author "system")
                  :request_count (get-in analysis [:summary :totalRequests])
                  :changed_decisions (get-in analysis [:summary :changedDecisions])
                  :revoke_count (get-in analysis [:riskSignals :revokeCount] 0)
                  :high_risk (boolean (get-in analysis [:riskSignals :highRisk]))
                  :review_status "draft"
                  :rollout_status "not_deployed"
                  :candidate_policy_json (when-let [candidate-policy (:candidatePolicy body)]
                                           (json/write-str candidate-policy))
                  :analysis_json (json/write-str analysis)
                  :created_at (java.sql.Timestamp/from (Instant/now))}
          result (jdbc/insert! db :policy_impact_history record)
          saved-id (or (:generated_key (first result))
                       (:scope_identity (first result))
                       (:id (first result)))]
      (.info logger "Saved policy impact analysis {} for {}" saved-id resource-class)
      saved-id)
    (catch Exception e
      (.error logger "Failed to save policy impact analysis for {}: {}" resource-class (.getMessage e) e)
      nil)))

(defn list-analyses
  [resource-class]
  (mapv row->history-entry
        (jdbc/query db
                    ["SELECT id, resource_class, baseline_version, candidate_version, candidate_source,
                             author, request_count, changed_decisions, revoke_count, high_risk,
                             review_status, reviewed_by, review_note, reviewed_at,
                             rollout_status, deployed_version, deployed_by, deployed_at, created_at
                        FROM POLICY_IMPACT_HISTORY
                       WHERE resource_class = ?
                    ORDER BY created_at DESC, id DESC"
                     resource-class])))

(defn get-analysis
  [resource-class analysis-id]
  (when-let [row (first (jdbc/query db
                                    ["SELECT id, resource_class, baseline_version, candidate_version, candidate_source,
                                             author, request_count, changed_decisions, revoke_count, high_risk,
                                             review_status, reviewed_by, review_note, reviewed_at,
                                             rollout_status, deployed_version, deployed_by, deployed_at,
                                             candidate_policy_json,
                                             analysis_json, created_at
                                        FROM POLICY_IMPACT_HISTORY
                                       WHERE resource_class = ? AND id = ?"
                                     resource-class (long analysis-id)]
                                    {:row-fn #(-> %
                                                  (update :analysis_json jdbc-utils/clob->string)
                                                  (update :candidate_policy_json jdbc-utils/clob->string))}))]
    (cond-> (assoc (row->history-entry row)
                   :analysis (json/read-str (:analysis_json row) :key-fn keyword))
      (:candidate_policy_json row)
      (assoc :candidatePolicy (json/read-str (:candidate_policy_json row) :key-fn keyword)))))

(defn update-review!
  [resource-class analysis-id {:keys [status reviewedBy reviewNote]}]
  (when-not (contains? allowed-review-statuses status)
    (throw (ex-info "Invalid review status"
                    {:status 400
                     :error-code "INVALID_REVIEW_STATUS"
                     :allowedStatuses (sort allowed-review-statuses)})))
  (let [existing (get-analysis resource-class analysis-id)]
    (when existing
      (jdbc/update! db :policy_impact_history
                    {:review_status status
                     :reviewed_by reviewedBy
                     :review_note reviewNote
                     :reviewed_at (java.sql.Timestamp/from (Instant/now))}
                    ["resource_class = ? AND id = ?" resource-class (long analysis-id)])
      (get-analysis resource-class analysis-id))))

(defn mark-deployed!
  [resource-class analysis-id {:keys [deployedVersion deployedBy]}]
  (when-not (contains? allowed-rollout-statuses "deployed")
    (throw (ex-info "Invalid rollout status configuration"
                    {:status 500
                     :error-code "INVALID_ROLLOUT_STATUS_CONFIGURATION"})))
  (let [existing (get-analysis resource-class analysis-id)]
    (when existing
      (jdbc/update! db :policy_impact_history
                    {:rollout_status "deployed"
                     :deployed_version deployedVersion
                     :deployed_by deployedBy
                     :deployed_at (java.sql.Timestamp/from (Instant/now))}
                    ["resource_class = ? AND id = ?" resource-class (long analysis-id)])
      (get-analysis resource-class analysis-id))))
