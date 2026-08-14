(ns autho.usage
  "Durable, observation-only decision metering.

   This first increment records production authorization calls after their
   result is available. It deliberately does not enforce quotas yet: metering
   must be observed and reconciled before it can affect authorization traffic."
  (:require [clojure.java.jdbc :as jdbc]
            [autho.prp :as prp]
            [autho.database :as database])
  (:import (java.time YearMonth)
           (org.slf4j LoggerFactory)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.usage"))
(def ^:private unscoped "__unscoped__")

(defn- scope-value [request key]
  (or (get request key) unscoped))

(defn- usage-key [request month]
  {:usage_month month
   :tenant_id (scope-value request :tenantId)
   :organization_id (scope-value request :organizationId)
   :project_id (scope-value request :projectId)
   :environment (scope-value request :environment)})

(defn current-month [] (str (YearMonth/now)))

(defn- validated-month [month]
  (try
    (str (YearMonth/parse (str month)))
    (catch Exception _
      (throw (ex-info "month must use YYYY-MM format"
                      {:status 400 :error-code "INVALID_USAGE_MONTH"})))))

(defn init!
  []
  (jdbc/execute! prp/h2db
                 ["CREATE TABLE IF NOT EXISTS DECISION_USAGE (
                     usage_month VARCHAR(7) NOT NULL,
                     tenant_id VARCHAR(255) NOT NULL,
                     organization_id VARCHAR(255) NOT NULL,
                     project_id VARCHAR(255) NOT NULL,
                     environment VARCHAR(255) NOT NULL,
                     decision_count BIGINT NOT NULL DEFAULT 0,
                     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                     PRIMARY KEY (usage_month, tenant_id, organization_id, project_id, environment)
                   )"])
  (jdbc/execute! prp/h2db
                 ["CREATE TABLE IF NOT EXISTS DECISION_USAGE_TOTAL (
                     usage_month VARCHAR(7) PRIMARY KEY,
                     decision_count BIGINT NOT NULL DEFAULT 0,
                     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                   )"])
  ;; Reconcile the total table at startup so installations upgraded from the
  ;; scope-only meter retain their already-recorded consumption.
  (jdbc/execute! prp/h2db
                 [(if (database/postgres?)
                    "INSERT INTO DECISION_USAGE_TOTAL (usage_month, decision_count, updated_at)
                       SELECT usage_month, SUM(decision_count), CURRENT_TIMESTAMP
                       FROM DECISION_USAGE GROUP BY usage_month
                       ON CONFLICT (usage_month) DO UPDATE
                       SET decision_count = EXCLUDED.decision_count, updated_at = EXCLUDED.updated_at"
                    "MERGE INTO DECISION_USAGE_TOTAL (usage_month, decision_count, updated_at)
                       KEY (usage_month)
                       SELECT usage_month, SUM(decision_count), CURRENT_TIMESTAMP
                       FROM DECISION_USAGE GROUP BY usage_month")])
  (.info logger "DECISION_USAGE table ready"))

(defn- increment-scope!
  [key]
  (let [{:keys [usage_month tenant_id organization_id project_id environment]} key
        where ["usage_month = ? AND tenant_id = ? AND organization_id = ? AND project_id = ? AND environment = ?"
               usage_month tenant_id organization_id project_id environment]
        updated (first (jdbc/execute! prp/h2db
                                      (into ["UPDATE DECISION_USAGE
                                              SET decision_count = decision_count + 1,
                                                  updated_at = CURRENT_TIMESTAMP
                                              WHERE usage_month = ? AND tenant_id = ?
                                                AND organization_id = ? AND project_id = ?
                                                AND environment = ?"]
                                            (rest where))))]
    (when (zero? updated)
      (try
        (jdbc/insert! prp/h2db :decision_usage (assoc key :decision_count 1))
        (catch Exception _
          ;; Another worker may have inserted this primary key between update
          ;; and insert. Retrying the atomic update preserves the increment.
          (jdbc/execute! prp/h2db
                         (into ["UPDATE DECISION_USAGE
                                 SET decision_count = decision_count + 1,
                                     updated_at = CURRENT_TIMESTAMP
                                 WHERE usage_month = ? AND tenant_id = ?
                                   AND organization_id = ? AND project_id = ?
                                   AND environment = ?"]
                               (rest where))))))))

(defn- increment-total!
  [month]
  (let [updated (first (jdbc/execute! prp/h2db
                                      ["UPDATE DECISION_USAGE_TOTAL
                                        SET decision_count = decision_count + 1,
                                            updated_at = CURRENT_TIMESTAMP
                                        WHERE usage_month = ?" month]))]
    (when (zero? updated)
      (try
        (jdbc/insert! prp/h2db :decision_usage_total {:usage_month month :decision_count 1})
        (catch Exception _
          (jdbc/execute! prp/h2db
                         ["UPDATE DECISION_USAGE_TOTAL
                           SET decision_count = decision_count + 1,
                               updated_at = CURRENT_TIMESTAMP
                           WHERE usage_month = ?" month]))))))

(defn- reserve-total!
  "Atomically increments a monthly total only when it remains below limit."
  [month limit]
  (cond
    (not (pos? limit)) false
    :else
    (let [updated (first (jdbc/execute! prp/h2db
                                        ["UPDATE DECISION_USAGE_TOTAL
                                          SET decision_count = decision_count + 1,
                                              updated_at = CURRENT_TIMESTAMP
                                          WHERE usage_month = ? AND decision_count < ?"
                                         month limit]))]
      (if (pos? updated)
        true
        (try
          ;; A new month has no row yet. Inserting one reservation is atomic;
          ;; a concurrent insert falls through to the conditional update.
          (jdbc/insert! prp/h2db :decision_usage_total {:usage_month month :decision_count 1})
          true
          (catch Exception _
            (pos? (first (jdbc/execute! prp/h2db
                                       ["UPDATE DECISION_USAGE_TOTAL
                                         SET decision_count = decision_count + 1,
                                             updated_at = CURRENT_TIMESTAMP
                                         WHERE usage_month = ? AND decision_count < ?"
                                        month limit])))))))))

(defn record-decision!
  "Records one successful call to the public authorization endpoint.
   Metering errors are intentionally fail-open until quota enforcement exists."
  [authz-request]
  (try
    (let [month (current-month)]
      (increment-total! month)
      (increment-scope! (usage-key authz-request month)))
    (catch Exception e
      (.error logger "Failed to record decision usage: {}" (.getMessage e)))))

(defn reserve-decision!
  "Reserves one decision under a hard monthly deployment limit.
   Returns true when reserved, false when exhausted. Database failures are
   propagated so callers can fail closed rather than silently exceed a paid
   entitlement."
  [authz-request limit]
  (let [month (current-month)
        limit (long limit)]
    (if (reserve-total! month limit)
      (do
        (increment-scope! (usage-key authz-request month))
        true)
      false)))

(defn usage
  "Returns a monthly decision count for a resolved product scope. Missing
   scope values select the unscoped bucket and never aggregate other scopes."
  ([scope] (usage scope (current-month)))
  ([scope month]
   (let [month (validated-month month)
         key (usage-key scope month)
         row (first (jdbc/query prp/h2db
                                ["SELECT decision_count, updated_at FROM DECISION_USAGE
                                  WHERE usage_month = ? AND tenant_id = ? AND organization_id = ?
                                    AND project_id = ? AND environment = ?"
                                 (:usage_month key) (:tenant_id key) (:organization_id key)
                                 (:project_id key) (:environment key)]))]
     {:month month
      :tenantId (when-not (= unscoped (:tenant_id key)) (:tenant_id key))
      :organizationId (when-not (= unscoped (:organization_id key)) (:organization_id key))
      :projectId (when-not (= unscoped (:project_id key)) (:project_id key))
      :environment (when-not (= unscoped (:environment key)) (:environment key))
      :decisionCount (long (or (:decision_count row) 0))
      :updatedAt (some-> (:updated_at row) str)})))

(defn total-usage
  "Returns the deployment-wide count for one month. This is the value that
   must be compared to a signed licence decision limit; a scope-specific count
   is not sufficient to enforce a deployment entitlement."
  ([] (total-usage (current-month)))
  ([month]
   (let [month (validated-month month)
         row (first (jdbc/query prp/h2db
                                ["SELECT decision_count FROM DECISION_USAGE_TOTAL
                                  WHERE usage_month = ?" month]))]
     {:month month
      :decisionCount (long (or (:decision_count row) 0))})))

(defn quota-status
  "Combines scope usage with a deployment-wide entitlement. The status is
   informational until quota enforcement is explicitly enabled."
  ([scope decision-limit] (quota-status scope (current-month) decision-limit))
  ([scope month decision-limit]
   (let [scoped (usage scope month)
         total (total-usage month)
         limit (when (some? decision-limit) (long decision-limit))
         count (:decisionCount total)
         ratio (when (and limit (pos? limit)) (/ (double count) limit))
         status (cond
                  (nil? limit) "unlimited"
                  (zero? limit) (if (pos? count) "exceeded" "normal")
                  (>= count limit) "exceeded"
                  (>= ratio 0.8) "warning"
                  :else "normal")]
     {:month (:month total)
      :enforcement "observation"
      :status status
      :monthlyDecisionLimit limit
      :deploymentDecisionCount count
      :scopeDecisionCount (:decisionCount scoped)
      :remaining (when limit (max 0 (- limit count)))
      :percentUsed (when ratio (long (Math/floor (* ratio 100))))})))

(defn clear-usage!
  "Test/support helper: removes usage records. Never exposed as an API."
  []
  (jdbc/execute! prp/h2db ["DELETE FROM DECISION_USAGE"])
  (jdbc/execute! prp/h2db ["DELETE FROM DECISION_USAGE_TOTAL"]))
