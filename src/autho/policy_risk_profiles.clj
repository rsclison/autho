(ns autho.policy-risk-profiles
  "Persistent risk profiles for policy impact analysis."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.data.json :as json]
            [autho.jdbc-utils :as jdbc-utils])
  (:import (org.slf4j LoggerFactory)
           (java.time Instant)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.policy-risk-profiles"))

(def ^:private h2-policy-cipher-key (System/getenv "H2_POLICY_CIPHER_KEY"))
(def ^:private h2-policy-db-path
  (or (System/getenv "AUTHO_POLICY_DB_PATH")
      (System/getProperty "autho.policy.db.path")
      "./resources/h2db"))

(def ^:private db
  (merge
   {:classname "org.h2.Driver"
    :subprotocol "h2"
    :user "sa"}
   (if h2-policy-cipher-key
     {:subname (str h2-policy-db-path ";CIPHER=AES")
      :password (str h2-policy-cipher-key " ")}
     {:subname h2-policy-db-path
      :password ""})))

(def allowed-scope-types #{"default" "environment" "resource_class"})

(defn init!
  []
  (try
    (jdbc/execute! db
                   ["CREATE TABLE IF NOT EXISTS POLICY_RISK_PROFILES (
         id            BIGINT AUTO_INCREMENT PRIMARY KEY,
         scope_type    VARCHAR(50)  NOT NULL,
         scope_key     VARCHAR(255) NOT NULL,
         profile_json  CLOB         NOT NULL,
         updated_by    VARCHAR(255),
         updated_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
         UNIQUE (scope_type, scope_key)
       )"])
    (jdbc/execute! db
                   ["CREATE TABLE IF NOT EXISTS POLICY_RISK_PROFILE_REVISIONS (
         id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
         scope_type          VARCHAR(50)  NOT NULL,
         scope_key           VARCHAR(255) NOT NULL,
         action              VARCHAR(50)  NOT NULL,
         previous_profile_json CLOB,
         new_profile_json    CLOB,
         approval_required   BOOLEAN      DEFAULT FALSE,
         approved_by         VARCHAR(255),
         approval_note       VARCHAR(2000),
         changed_by          VARCHAR(255),
         changed_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
       )"])
    (jdbc/execute! db ["ALTER TABLE POLICY_RISK_PROFILE_REVISIONS ADD COLUMN IF NOT EXISTS approval_required BOOLEAN DEFAULT FALSE"])
    (jdbc/execute! db ["ALTER TABLE POLICY_RISK_PROFILE_REVISIONS ADD COLUMN IF NOT EXISTS approved_by VARCHAR(255)"])
    (jdbc/execute! db ["ALTER TABLE POLICY_RISK_PROFILE_REVISIONS ADD COLUMN IF NOT EXISTS approval_note VARCHAR(2000)"])
    (.info logger "POLICY_RISK_PROFILES table ready")
    (catch Exception e
      (.error logger "Failed to create POLICY_RISK_PROFILES table: {}" (.getMessage e) e))))

(defn- validate-scope!
  [scope-type scope-key]
  (when-not (contains? allowed-scope-types scope-type)
    (throw (ex-info "Invalid risk profile scope type"
                    {:status 400
                     :error-code "INVALID_RISK_PROFILE_SCOPE"
                     :allowedScopes (sort allowed-scope-types)})))
  (when (and (not= "default" scope-type) (empty? (str scope-key)))
    (throw (ex-info "Risk profile scope key is required"
                    {:status 400
                     :error-code "MISSING_RISK_PROFILE_SCOPE_KEY"}))))

(defn- normalized-scope-key
  [scope-type scope-key]
  (if (= "default" scope-type)
    "*"
    (str scope-key)))

(defn- row->profile
  [row]
  {:scopeType (:scope_type row)
   :scopeKey (:scope_key row)
   :profile (json/read-str (:profile_json row) :key-fn keyword)
   :updatedBy (:updated_by row)
   :updatedAt (:updated_at row)})

(defn- row->revision
  [row]
  {:id (:id row)
   :scopeType (:scope_type row)
   :scopeKey (:scope_key row)
   :action (:action row)
   :previousProfile (when-let [profile-json (:previous_profile_json row)]
                      (json/read-str profile-json :key-fn keyword))
   :newProfile (when-let [profile-json (:new_profile_json row)]
                 (json/read-str profile-json :key-fn keyword))
   :approvalRequired (boolean (:approval_required row))
   :approvedBy (:approved_by row)
   :approvalNote (:approval_note row)
   :changedBy (:changed_by row)
   :changedAt (:changed_at row)})

(defn- parse-profile-json
  [profile-json]
  (when profile-json
    (json/read-str profile-json :key-fn keyword)))

(defn- higher-threshold?
  [previous-profile new-profile key]
  (let [previous-value (get previous-profile key)
        new-value (get new-profile key)]
    (and (number? previous-value)
         (number? new-value)
         (> new-value previous-value))))

(defn- loosens-sensitive-resource-gate?
  [previous-profile new-profile]
  (and (false? (get previous-profile :allowSensitiveResourceChanges))
       (true? (get new-profile :allowSensitiveResourceChanges))))

(defn- critical-change?
  [action previous-profile-json new-profile-json]
  (let [previous-profile (parse-profile-json previous-profile-json)
        new-profile (parse-profile-json new-profile-json)]
    (boolean
     (case action
       "delete" previous-profile
       "update" (or (higher-threshold? previous-profile new-profile :maxRevokes)
                    (higher-threshold? previous-profile new-profile :maxChangedDecisions)
                    (loosens-sensitive-resource-gate? previous-profile new-profile))
       false))))

(defn- require-approval!
  [approval-required? {:keys [approved? approvedBy]}]
  (when (and approval-required? (not approved?))
    (throw (ex-info "Critical risk profile changes require approval"
                    {:status 409
                     :error-code "RISK_PROFILE_APPROVAL_REQUIRED"})))
  (when (and approval-required? (empty? (str approvedBy)))
    (throw (ex-info "Critical risk profile approval requires approvedBy"
                    {:status 400
                     :error-code "MISSING_RISK_PROFILE_APPROVER"}))))

(defn- current-profile-row
  [conn scope-type scope-key]
  (first (jdbc/query conn
                     ["SELECT scope_type, scope_key, profile_json, updated_by, updated_at
                         FROM POLICY_RISK_PROFILES
                        WHERE scope_type = ? AND scope_key = ?"
                      scope-type scope-key]
                     {:row-fn #(update % :profile_json jdbc-utils/clob->string)})))

(defn- insert-revision!
  [conn scope-type scope-key action previous-profile-json new-profile-json changed-by changed-at approval]
  (let [approval-required? (critical-change? action previous-profile-json new-profile-json)]
    (require-approval! approval-required? approval)
  (jdbc/insert! conn :policy_risk_profile_revisions
                {:scope_type scope-type
                 :scope_key scope-key
                 :action action
                 :previous_profile_json previous-profile-json
                 :new_profile_json new-profile-json
                 :approval_required approval-required?
                 :approved_by (when approval-required? (:approvedBy approval))
                 :approval_note (when approval-required? (:approvalNote approval))
                 :changed_by (or changed-by "system")
                 :changed_at changed-at})))

(defn upsert-profile!
  ([scope-type scope-key profile updated-by]
   (upsert-profile! scope-type scope-key profile updated-by {}))
  ([scope-type scope-key profile updated-by approval]
   (validate-scope! scope-type scope-key)
   (let [scope-key (normalized-scope-key scope-type scope-key)
         profile-json (json/write-str (or profile {}))
         updated-at (java.sql.Timestamp/from (Instant/now))]
     (jdbc/with-db-transaction [tx db]
       (let [previous-profile-json (:profile_json (current-profile-row tx scope-type scope-key))
             action (if previous-profile-json "update" "create")]
         (insert-revision! tx scope-type scope-key action
                           previous-profile-json profile-json updated-by updated-at approval))
       (jdbc/delete! tx :policy_risk_profiles
                     ["scope_type = ? AND scope_key = ?" scope-type scope-key])
       (jdbc/insert! tx :policy_risk_profiles
                     {:scope_type scope-type
                      :scope_key scope-key
                      :profile_json profile-json
                      :updated_by (or updated-by "system")
                      :updated_at updated-at}))
     (first (jdbc/query db
                        ["SELECT scope_type, scope_key, profile_json, updated_by, updated_at
                            FROM POLICY_RISK_PROFILES
                           WHERE scope_type = ? AND scope_key = ?"
                         scope-type scope-key]
                        {:row-fn #(row->profile
                                   (update % :profile_json jdbc-utils/clob->string))})))))

(defn delete-profile!
  ([scope-type scope-key]
   (delete-profile! scope-type scope-key "system" {}))
  ([scope-type scope-key deleted-by]
   (delete-profile! scope-type scope-key deleted-by {}))
  ([scope-type scope-key deleted-by approval]
   (validate-scope! scope-type scope-key)
   (let [scope-key (normalized-scope-key scope-type scope-key)
         changed-at (java.sql.Timestamp/from (Instant/now))
         result (jdbc/with-db-transaction [tx db]
                  (let [previous-profile-json (:profile_json (current-profile-row tx scope-type scope-key))
                        delete-result (jdbc/delete! tx :policy_risk_profiles
                                                    ["scope_type = ? AND scope_key = ?" scope-type scope-key])]
                    (when previous-profile-json
                      (insert-revision! tx scope-type scope-key "delete"
                                        previous-profile-json nil deleted-by changed-at approval))
                    delete-result))]
     (pos? (first result)))))

(defn list-profile-records
  []
  (mapv row->profile
        (jdbc/query db
                    ["SELECT scope_type, scope_key, profile_json, updated_by, updated_at
                        FROM POLICY_RISK_PROFILES
                    ORDER BY scope_type ASC, scope_key ASC"]
                    {:row-fn #(update % :profile_json jdbc-utils/clob->string)})))

(defn list-profiles
  []
  (reduce (fn [profiles {:keys [scopeType scopeKey profile]}]
            (case scopeType
              "default" (assoc profiles :default profile)
              "environment" (assoc-in profiles [:environments scopeKey] profile)
              "resource_class" (assoc-in profiles [:resourceClasses scopeKey] profile)
              profiles))
          {}
          (list-profile-records)))

(defn list-revisions
  []
  (mapv row->revision
        (jdbc/query db
                    ["SELECT id, scope_type, scope_key, action,
                             previous_profile_json, new_profile_json,
                             approval_required, approved_by, approval_note,
                             changed_by, changed_at
                        FROM POLICY_RISK_PROFILE_REVISIONS
                    ORDER BY changed_at DESC, id DESC"]
                    {:row-fn #(-> %
                                  (update :previous_profile_json jdbc-utils/clob->string)
                                  (update :new_profile_json jdbc-utils/clob->string))})))
