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

(defn upsert-profile!
  [scope-type scope-key profile updated-by]
  (validate-scope! scope-type scope-key)
  (let [scope-key (normalized-scope-key scope-type scope-key)
        profile-json (json/write-str (or profile {}))
        updated-at (java.sql.Timestamp/from (Instant/now))]
    (jdbc/with-db-transaction [tx db]
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
                                  (update % :profile_json jdbc-utils/clob->string))}))))

(defn delete-profile!
  [scope-type scope-key]
  (validate-scope! scope-type scope-key)
  (let [scope-key (normalized-scope-key scope-type scope-key)
        result (jdbc/delete! db :policy_risk_profiles
                             ["scope_type = ? AND scope_key = ?" scope-type scope-key])]
    (pos? (first result))))

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
