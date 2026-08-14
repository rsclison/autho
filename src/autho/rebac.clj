(ns autho.rebac
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.set :as set]
            [autho.database :as database])
  (:import (org.slf4j LoggerFactory)
           (java.time Instant)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.rebac"))

(def default-max-depth 8)

(def ^:dynamic *tenant-id*
  "Tenant scope applied to relation graph operations. Nil is the legacy,
   unscoped compatibility partition; PDP/API callers must bind a real tenant."
  nil)

(defmacro with-tenant
  [tenant-id & body]
  `(binding [*tenant-id* ~tenant-id]
     ~@body))

(def ^:private db (database/policy-db))

(defonce relation-tuples
  (atom {:tuples #{}
         :by-subject-relation {}
         :by-resource-relation {}
         :parents-by-child {}
         :children-by-parent {}
         :memberships-by-member {}
         :members-by-group {}}))

(defonce relation-rewrites
  (atom {}))

(defonce persistence-enabled?
  (atom false))

(defonce ^:private processed-event-ids
  (atom #{}))

(defonce ^:private projection-versions
  (atom {}))

(defonce ^:private quarantined-projection-event-store
  (atom []))

(defonce ^:private reconciliation-reports
  (atom []))

(defonce ^:private projection-audit-events
  (atom []))

(declare add-relation! remove-relation! list-relations)
(declare record-projection-audit!)

(defn- entity-key
  [entity]
  (cond-> {:class (or (:class entity) (:resourceClass entity))
           :id (:id entity)}
    (or *tenant-id* (:tenantId entity) (:tenant-id entity))
    (assoc :tenantId (or *tenant-id* (:tenantId entity) (:tenant-id entity)))))

(defn- projection-metadata
  [metadata]
  (let [value (fn [key]
                (or (get metadata key) (get metadata (name key))))]
    (cond-> {}
      (value :source) (assoc :source (str (value :source)))
      (value :sourceEventId) (assoc :sourceEventId (str (value :sourceEventId)))
      (some? (value :sourceVersion)) (assoc :sourceVersion (str (value :sourceVersion)))
      (value :occurredAt) (assoc :occurredAt (str (value :occurredAt)))
      (value :receivedAt) (assoc :receivedAt (str (value :receivedAt)))
      (value :expiresAt) (assoc :expiresAt (str (value :expiresAt))))))

(defn- relation-tuple
  ([subject relation resource]
   (relation-tuple subject relation resource nil))
  ([subject relation resource metadata]
   (let [projection (projection-metadata metadata)]
     (cond-> {:subject (entity-key subject)
              :relation (name relation)
              :resource (entity-key resource)}
       (seq projection) (assoc :projection projection)))))

(defn- tuple-identity
  [tuple]
  (dissoc tuple :projection))

(defn- tuple->row
  [{:keys [subject relation resource projection]}]
  {:subject_class (:class subject)
   :subject_id (:id subject)
   :tenant_id (or (:tenantId subject) "__legacy__")
   :relation relation
   :resource_class (:class resource)
   :resource_id (:id resource)
   :source (:source projection)
   :source_event_id (:sourceEventId projection)
   :source_version (:sourceVersion projection)
   :occurred_at (:occurredAt projection)
   :received_at (:receivedAt projection)
   :expires_at (:expiresAt projection)})

(defn- rewrite-row
  [relation rewritten-relation]
  {:tenant_id (or *tenant-id* "__legacy__")
   :relation (name relation)
   :rewritten_relation (name rewritten-relation)})

(defn- row->tuple
  [row]
  (let [tenant-id (when-not (= "__legacy__" (:tenant_id row)) (:tenant_id row))
        projection (projection-metadata {:source (:source row)
                                         :sourceEventId (:source_event_id row)
                                         :sourceVersion (:source_version row)
                                         :occurredAt (:occurred_at row)
                                         :receivedAt (:received_at row)
                                         :expiresAt (:expires_at row)})]
    (cond-> {:subject (cond-> {:class (:subject_class row)
                       :id (:subject_id row)}
                tenant-id (assoc :tenantId tenant-id))
     :relation (:relation row)
     :resource (cond-> {:class (:resource_class row)
                         :id (:resource_id row)}
                 tenant-id (assoc :tenantId tenant-id))}
      (seq projection) (assoc :projection projection))))

(defn- rows->rewrites
  [rows]
  (reduce (fn [rewrites {:keys [tenant_id relation rewritten_relation]}]
            (update-in rewrites [(or tenant_id "__legacy__") relation]
                       (fnil conj []) rewritten_relation))
          {}
          rows))

(defn- add-to-index
  [store tuple]
  (cond-> (-> store
              (update :tuples conj tuple)
              (update-in [:by-subject-relation [(:subject tuple) (:relation tuple)]]
                         (fnil conj #{})
                         (:resource tuple))
              (update-in [:by-resource-relation [(:resource tuple) (:relation tuple)]]
                         (fnil conj #{})
                         (:subject tuple)))
    (= "parent" (:relation tuple))
    (update-in [:parents-by-child (:subject tuple)]
               (fnil conj #{})
               (:resource tuple))
    (= "parent" (:relation tuple))
    (update-in [:children-by-parent (:resource tuple)]
               (fnil conj #{})
               (:subject tuple))
    (= "member" (:relation tuple))
    (update-in [:memberships-by-member (:subject tuple)]
               (fnil conj #{})
               (:resource tuple))
    (= "member" (:relation tuple))
    (update-in [:members-by-group (:resource tuple)]
               (fnil conj #{})
               (:subject tuple))))

(declare remove-from-index)

(defn- identity-matches?
  [candidate tuple]
  (= (tuple-identity candidate) (tuple-identity tuple)))

(defn- remove-identity-from-index
  [store tuple]
  (reduce remove-from-index
          store
          (filter #(identity-matches? % tuple) (:tuples store))))

(defn- remove-from-index
  [store tuple]
  (cond-> (-> store
              (update :tuples disj tuple)
              (update-in [:by-subject-relation [(:subject tuple) (:relation tuple)]]
                         (fnil disj #{})
                         (:resource tuple))
              (update-in [:by-resource-relation [(:resource tuple) (:relation tuple)]]
                         (fnil disj #{})
                         (:subject tuple)))
    (= "parent" (:relation tuple))
    (update-in [:parents-by-child (:subject tuple)]
               (fnil disj #{})
               (:resource tuple))
    (= "parent" (:relation tuple))
    (update-in [:children-by-parent (:resource tuple)]
               (fnil disj #{})
               (:subject tuple))
    (= "member" (:relation tuple))
    (update-in [:memberships-by-member (:subject tuple)]
               (fnil disj #{})
               (:resource tuple))
    (= "member" (:relation tuple))
    (update-in [:members-by-group (:resource tuple)]
               (fnil disj #{})
               (:subject tuple))))

(defn- build-index
  [tuples]
  (reduce add-to-index
          {:tuples #{}
           :by-subject-relation {}
           :by-resource-relation {}
           :parents-by-child {}
           :children-by-parent {}
           :memberships-by-member {}
           :members-by-group {}}
          tuples))

(defn init!
  "Create the durable ReBAC tuple table and load the in-memory indexes."
  []
  (try
    (jdbc/execute! db
                   [(format "CREATE TABLE IF NOT EXISTS REBAC_RELATIONS (
         id             %s,
         tenant_id      VARCHAR(255) NOT NULL DEFAULT '__legacy__',
         subject_class  VARCHAR(255) NOT NULL,
         subject_id     VARCHAR(255) NOT NULL,
         relation       VARCHAR(255) NOT NULL,
         resource_class VARCHAR(255) NOT NULL,
         resource_id    VARCHAR(255) NOT NULL,
         source         VARCHAR(255),
         source_event_id VARCHAR(255),
         source_version VARCHAR(255),
         occurred_at    VARCHAR(64),
         received_at    VARCHAR(64),
         expires_at     VARCHAR(64),
         created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
         UNIQUE (tenant_id, subject_class, subject_id, relation, resource_class, resource_id)
       )" (database/identity-column))])
    ;; Existing installations used an unscoped table. Keep their tuples in the
    ;; legacy partition while new writes carry a tenant key.
    (jdbc/execute! db
                   ["ALTER TABLE REBAC_RELATIONS ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(255) NOT NULL DEFAULT '__legacy__'"])
    (doseq [column ["source VARCHAR(255)"
                    "source_event_id VARCHAR(255)"
                    "source_version VARCHAR(255)"
                    "occurred_at VARCHAR(64)"
                    "received_at VARCHAR(64)"
                    "expires_at VARCHAR(64)"]]
      (jdbc/execute! db [(str "ALTER TABLE REBAC_RELATIONS ADD COLUMN IF NOT EXISTS " column)]))
    ;; Replace the pre-tenant uniqueness constraint when upgrading an existing
    ;; H2 store. Without this migration the same business tuple could not be
    ;; represented independently in two tenants after a restart.
    (when (database/h2?)
      (doseq [{:keys [constraint_name]}
              (jdbc/query db
                        ["SELECT constraint_name
                            FROM information_schema.table_constraints
                           WHERE table_name = 'REBAC_RELATIONS'
                             AND constraint_type = 'UNIQUE'"])]
        (jdbc/execute! db [(str "ALTER TABLE REBAC_RELATIONS DROP CONSTRAINT " constraint_name)])))
    (jdbc/execute! db
                   ["CREATE UNIQUE INDEX IF NOT EXISTS UX_REBAC_RELATIONS_TENANT_TUPLE
                       ON REBAC_RELATIONS
                          (tenant_id, subject_class, subject_id, relation, resource_class, resource_id)"])
    (jdbc/execute! db
                   ["CREATE TABLE IF NOT EXISTS REBAC_PROJECTION_EVENTS (
         event_id       VARCHAR(255) PRIMARY KEY,
         tenant_id      VARCHAR(255) NOT NULL,
         source         VARCHAR(255),
         event_type     VARCHAR(255) NOT NULL,
         occurred_at    VARCHAR(64),
         processed_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
       )"])
    (jdbc/execute! db
                   ["CREATE TABLE IF NOT EXISTS REBAC_PROJECTION_STATE (
         tuple_key      VARCHAR(2048) PRIMARY KEY,
         source_version VARCHAR(255),
         updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
       )"])
    (jdbc/execute! db
                   [(format "CREATE TABLE IF NOT EXISTS REBAC_PROJECTION_QUARANTINE (
         id             VARCHAR(255) PRIMARY KEY,
         event_value    %s NOT NULL,
         reason         VARCHAR(2000),
         created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
       )" (database/text-column))])
    (jdbc/execute! db
                   ["CREATE TABLE IF NOT EXISTS REBAC_RECONCILIATION_REPORTS (
         id              VARCHAR(255) PRIMARY KEY,
         tenant_id       VARCHAR(255) NOT NULL,
         source          VARCHAR(255) NOT NULL,
         expected_count  BIGINT NOT NULL,
         projected_count BIGINT NOT NULL,
         missing_count   BIGINT NOT NULL,
         obsolete_count  BIGINT NOT NULL,
         created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
       )"])
    (jdbc/execute! db
                   [(format "CREATE TABLE IF NOT EXISTS REBAC_PROJECTION_AUDIT (
         id         VARCHAR(255) PRIMARY KEY,
         action     VARCHAR(255) NOT NULL,
         tenant_id  VARCHAR(255),
         source     VARCHAR(255),
         event_id   VARCHAR(255),
         details    %s,
         created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
       )" (database/text-column))])
    (jdbc/execute! db
                   [(format "CREATE TABLE IF NOT EXISTS REBAC_RELATION_REWRITES (
         id                 %s,
         tenant_id          VARCHAR(255) NOT NULL DEFAULT '__legacy__',
         relation           VARCHAR(255) NOT NULL,
         rewritten_relation VARCHAR(255) NOT NULL,
         created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
         UNIQUE (tenant_id, relation, rewritten_relation)
       )" (database/identity-column))])
    ;; Rewrites affect the meaning of derived relations and therefore must be
    ;; isolated just like tuples. Pre-tenant rewrites remain available only in
    ;; the explicit legacy partition.
    (jdbc/execute! db
                   ["ALTER TABLE REBAC_RELATION_REWRITES ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(255) NOT NULL DEFAULT '__legacy__'"])
    (when (database/h2?)
      (doseq [{:keys [constraint_name]}
              (jdbc/query db
                        ["SELECT constraint_name
                            FROM information_schema.table_constraints
                           WHERE table_name = 'REBAC_RELATION_REWRITES'
                             AND constraint_type = 'UNIQUE'"])]
        (jdbc/execute! db [(str "ALTER TABLE REBAC_RELATION_REWRITES DROP CONSTRAINT " constraint_name)])))
    (jdbc/execute! db
                   ["CREATE UNIQUE INDEX IF NOT EXISTS UX_REBAC_REWRITES_TENANT_RELATION
                       ON REBAC_RELATION_REWRITES
                          (tenant_id, relation, rewritten_relation)"])
    (let [tuples (mapv row->tuple
                       (jdbc/query db
                                   ["SELECT tenant_id, subject_class, subject_id, relation,
                                            resource_class, resource_id, source, source_event_id,
                                            source_version, occurred_at, received_at, expires_at
                                       FROM REBAC_RELATIONS"]))
          rewrites (rows->rewrites
                    (jdbc/query db
                                ["SELECT tenant_id, relation, rewritten_relation
                                    FROM REBAC_RELATION_REWRITES
                                ORDER BY tenant_id ASC, relation ASC, rewritten_relation ASC"]))]
      (reset! relation-tuples (build-index tuples))
      (reset! processed-event-ids
              (set (map :event_id
                        (jdbc/query db ["SELECT event_id FROM REBAC_PROJECTION_EVENTS"]))))
      (reset! projection-versions
              (into {} (map (juxt :tuple_key :source_version)
                            (jdbc/query db ["SELECT tuple_key, source_version FROM REBAC_PROJECTION_STATE"]))))
      (reset! quarantined-projection-event-store
              (vec (jdbc/query db ["SELECT id, event_value, reason FROM REBAC_PROJECTION_QUARANTINE ORDER BY created_at ASC"])))
      (reset! reconciliation-reports
              (vec (jdbc/query db ["SELECT id, tenant_id, source, expected_count, projected_count, missing_count, obsolete_count, created_at FROM REBAC_RECONCILIATION_REPORTS ORDER BY created_at DESC"])))
      (reset! projection-audit-events
              (vec (jdbc/query db ["SELECT id, action, tenant_id, source, event_id, details, created_at FROM REBAC_PROJECTION_AUDIT ORDER BY created_at DESC"])))
      (reset! relation-rewrites rewrites)
      (reset! persistence-enabled? true)
      (.info logger "REBAC_RELATIONS table ready with {} tuple(s) and {} rewrite(s)"
             (count tuples)
             (reduce + 0 (map count (vals rewrites)))))
    (catch Exception e
      (.error logger "Failed to initialize REBAC_RELATIONS: {}" (.getMessage e) e))))

(defn quarantine-projection-event!
  [value reason]
  (let [entry {:id (str (java.util.UUID/randomUUID))
               :event_value (str value)
               :reason (str reason)}]
    (when @persistence-enabled?
      (jdbc/insert! db :rebac_projection_quarantine entry))
    (swap! quarantined-projection-event-store conj entry)
    (record-projection-audit! :quarantined {:details {:reason reason}})
    entry))

(defn- record-projection-audit!
  [action {:keys [source eventId details]}]
  (let [entry {:id (str (java.util.UUID/randomUUID))
               :action (name action)
               :tenant_id (or *tenant-id* "__legacy__")
               :source source
               :event_id eventId
               :details (pr-str details)}]
    (when @persistence-enabled?
      (jdbc/insert! db :rebac_projection_audit entry))
    (swap! projection-audit-events conj entry)
    entry))

(defn list-projection-audit-events []
  (let [entries @projection-audit-events]
    (vec (if *tenant-id*
           (filter #(= *tenant-id* (:tenant_id %)) entries)
           entries))))

(defn quarantined-projection-events [] @quarantined-projection-event-store)

(defn remove-quarantined-projection-event!
  [id]
  (when @persistence-enabled?
    (jdbc/delete! db :rebac_projection_quarantine ["id = ?" id]))
  (swap! quarantined-projection-event-store
         #(vec (remove (fn [entry] (= id (:id entry))) %)))
  true)

(defn- mark-event-processed!
  [event]
  (let [event-id (some-> (:eventId event) str)]
    (when-not (str/blank? event-id)
      (if (contains? @processed-event-ids event-id)
        false
        (let [inserted? (if @persistence-enabled?
                          (try
                            (jdbc/insert! db :rebac_projection_events
                                          {:event_id event-id
                                           :tenant_id (:tenantId event)
                                           :source (:source event)
                                           :event_type (:eventType event)
                                           :occurred_at (:occurredAt event)})
                            true
                            (catch Exception e
                              (if (database/unique-violation? e) false (throw e))))
                          true)]
          (when inserted?
            (swap! processed-event-ids conj event-id))
          inserted?)))))

(defn- version-key
  [tenant-id tuple]
  (pr-str (assoc (tuple-identity tuple) :tenantId tenant-id)))

(defn- compare-versions
  [left right]
  (try
    (compare (bigint (str left)) (bigint (str right)))
    (catch Exception _ (compare (str left) (str right)))))

(defn- stale-version?
  [tuple-key version]
  (when (some? version)
    (when-let [current (get @projection-versions tuple-key)]
      (not (pos? (compare-versions version current))))) )

(defn- store-version!
  [tuple-key version]
  (when (some? version)
    (when @persistence-enabled?
      (jdbc/update! db :rebac_projection_state
                    {:source_version (str version)}
                    ["tuple_key = ?" tuple-key])
      (when-not (contains? @projection-versions tuple-key)
        (try
          (jdbc/insert! db :rebac_projection_state {:tuple_key tuple-key
                                                     :source_version (str version)})
          (catch Exception e
            (when-not (database/unique-violation? e) (throw e))))))
    (swap! projection-versions assoc tuple-key (str version))))

(defn apply-projection-event!
  "Applies one source-of-truth relationship event exactly once.

   Supported eventType values include relationship upsert/delete and subject or
   resource deletion. Kafka/outbox consumers call this function; it is
   intentionally transport-agnostic."
  [{:keys [eventId eventType tenantId source version occurredAt tuple subject resource] :as event}]
  (when (or (str/blank? (str eventId))
            (str/blank? (str eventType))
            (str/blank? (str tenantId)))
    (throw (ex-info "Projection event requires eventId, eventType and tenantId"
                    {:event event})))
  (when-not (contains? #{"authorization.relationship.upserted"
                        "authorization.relationship.deleted"
                        "authorization.subject.deleted"
                        "authorization.resource.deleted"}
                      eventType)
    (throw (ex-info "Unsupported relationship event type"
                    {:eventType eventType :eventId eventId})))
  (when (and (contains? #{"authorization.relationship.upserted"
                         "authorization.relationship.deleted"} eventType)
             (not (map? tuple)))
    (throw (ex-info "Relationship event requires tuple" {:event event})))
  (when (and (= eventType "authorization.subject.deleted") (not (map? subject)))
    (throw (ex-info "Subject deletion event requires subject" {:event event})))
  (when (and (= eventType "authorization.resource.deleted") (not (map? resource)))
    (throw (ex-info "Resource deletion event requires resource" {:event event})))
  (let [tuple-key (when tuple
                    (with-tenant tenantId
                      (version-key tenantId (relation-tuple (:subject tuple) (:relation tuple) (:resource tuple)))))]
    (cond
      (contains? @processed-event-ids (str eventId))
      {:status :duplicate :eventId eventId}

      (and tuple-key (stale-version? tuple-key version))
      {:status :stale :eventId eventId :version version}

      (not (mark-event-processed! event))
      {:status :duplicate :eventId eventId}

      :else
      (if tuple
        (let [{:keys [subject relation resource]} tuple
              metadata {:source source
                        :sourceEventId eventId
                        :sourceVersion version
                        :occurredAt occurredAt
                        :receivedAt (.toString (Instant/now))}
              result (with-tenant tenantId
                       (case eventType
                         "authorization.relationship.upserted"
                         (add-relation! subject relation resource metadata)

                         "authorization.relationship.deleted"
                         (remove-relation! subject relation resource)))]
          (store-version! tuple-key version)
          (record-projection-audit! (if (= eventType "authorization.relationship.deleted") :deleted :upserted)
                                    {:source source :eventId eventId
                                     :details {:tuple tuple :version version}})
          {:status :applied :eventId eventId :eventType eventType :tuple result})
        (let [target (with-tenant tenantId (entity-key (or subject resource)))
              matches? (if (= eventType "authorization.subject.deleted")
                         #(= target (:subject %))
                         #(= target (:resource %)))
              affected (with-tenant tenantId (filter matches? (list-relations)))
              current (remove #(stale-version? (version-key tenantId %) version) affected)]
          (doseq [relation-tuple current]
            (with-tenant tenantId
              (remove-relation! (:subject relation-tuple) (:relation relation-tuple) (:resource relation-tuple)))
            (store-version! (version-key tenantId relation-tuple) version))
          (record-projection-audit! (if (= eventType "authorization.subject.deleted") :subject-deleted :resource-deleted)
                                    {:source source :eventId eventId
                                     :details {:target target :version version :deletedCount (count current)}})
          {:status :applied :eventId eventId :eventType eventType :deletedCount (count current)})))))

(defn add-relation!
  "Adds or updates an authorization projection tuple.
   The optional metadata identifies its business source and lifecycle."
  ([subject relation resource]
   (add-relation! subject relation resource {}))
  ([subject relation resource metadata]
   (let [tuple (relation-tuple subject relation resource metadata)
         row (tuple->row tuple)]
     (when @persistence-enabled?
       ;; The tuple identity is unique. Updating it is idempotent for a source
       ;; that replays the same event or refreshes projection metadata.
       (let [updated (jdbc/update! db :rebac_relations row
                                   ["tenant_id = ? AND subject_class = ? AND subject_id = ? AND relation = ? AND resource_class = ? AND resource_id = ?"
                                    (:tenant_id row) (:subject_class row) (:subject_id row)
                                    (:relation row) (:resource_class row) (:resource_id row)])]
         (when (zero? (first updated))
           (jdbc/insert! db :rebac_relations row))))
     (swap! relation-tuples #(add-to-index (remove-identity-from-index % tuple) tuple))
     tuple)))

(defn remove-relation!
  "Removes a subject-relation-resource tuple from the in-memory relation graph."
  [subject relation resource]
  (let [tuple (relation-tuple subject relation resource)]
    (when @persistence-enabled?
      (jdbc/delete! db :rebac_relations
                    ["tenant_id = ? AND subject_class = ? AND subject_id = ? AND relation = ? AND resource_class = ? AND resource_id = ?"
                     (or (get-in tuple [:subject :tenantId]) "__legacy__")
                     (get-in tuple [:subject :class])
                     (get-in tuple [:subject :id])
                     (:relation tuple)
                     (get-in tuple [:resource :class])
                     (get-in tuple [:resource :id])]))
    (swap! relation-tuples #(remove-identity-from-index % tuple))
    tuple))

(defn clear-relations!
  "Clears in-memory relation indexes. Pass {:persist true} to also delete
   durable tuples after init! has enabled persistence."
  ([] (clear-relations! {}))
  ([{:keys [persist]}]
   (reset! relation-tuples {:tuples #{}
                            :by-subject-relation {}
                            :by-resource-relation {}
                            :parents-by-child {}
                            :children-by-parent {}
                            :memberships-by-member {}
                            :members-by-group {}})
   (when (and persist @persistence-enabled?)
     (jdbc/delete! db :rebac_relations ["1 = 1"]))
   true))

(defn list-relations
  []
  (let [tuples (:tuples @relation-tuples)]
    (vec (if *tenant-id*
           (filter #(= *tenant-id* (get-in % [:subject :tenantId])) tuples)
           tuples))))

(defn reconcile-snapshot
  "Compares a source snapshot with the current tenant projection.
   This operation is deliberately read-only: callers review the report before
   publishing corrective source events."
  [source expected-tuples]
  (let [source (str source)
        identity-set (fn [tuples] (set (map tuple-identity tuples)))
        expected (mapv #(relation-tuple (:subject %) (:relation %) (:resource %)
                                        (select-keys % [:source :sourceEventId :sourceVersion
                                                        :occurredAt :receivedAt :expiresAt]))
                       expected-tuples)
        projected (->> (list-relations)
                       (filter #(= source (get-in % [:projection :source])))
                       vec)
        expected-by-id (into {} (map (juxt tuple-identity identity) expected))
        projected-by-id (into {} (map (juxt tuple-identity identity) projected))
        expected-ids (identity-set expected)
        projected-ids (identity-set projected)]
    (let [report {:id (str (java.util.UUID/randomUUID))
                  :tenantId (or *tenant-id* "__legacy__")
                  :source source
                  :expectedCount (count expected)
                  :projectedCount (count projected)
                  :missing (mapv expected-by-id (sort-by pr-str (set/difference expected-ids projected-ids)))
                  :obsolete (mapv projected-by-id (sort-by pr-str (set/difference projected-ids expected-ids)))
                  :conflicts (->> (set/intersection expected-ids projected-ids)
                                  (keep (fn [identity]
                                          (let [expected-version (get-in (expected-by-id identity) [:projection :sourceVersion])
                                                projected-version (get-in (projected-by-id identity) [:projection :sourceVersion])]
                                            (when (and expected-version projected-version
                                                       (not= expected-version projected-version))
                                              {:expected (expected-by-id identity)
                                               :projected (projected-by-id identity)}))))
                                  vec)
                  :errors []}
          summary {:id (:id report) :tenant_id (:tenantId report) :source source
                   :expected_count (:expectedCount report) :projected_count (:projectedCount report)
                   :missing_count (count (:missing report)) :obsolete_count (count (:obsolete report))}]
      (when @persistence-enabled?
        (jdbc/insert! db :rebac_reconciliation_reports summary))
      (swap! reconciliation-reports conj summary)
      (record-projection-audit! :reconciled {:source source
                                              :details (select-keys report [:expectedCount :projectedCount])})
      report)))

(defn list-reconciliation-reports []
  (let [reports @reconciliation-reports]
    (vec (if *tenant-id*
           (filter #(= *tenant-id* (:tenant_id %)) reports)
           reports))))

(defn set-relation-rewrite!
  "Defines a userset rewrite for a derived relation.
   Example: (set-relation-rewrite! \"can-read\" [\"viewer\" \"editor\"])."
  [relation derived-relations]
  (let [relation (name relation)
        derived-relations (vec (distinct (map name derived-relations)))
        tenant-id (or *tenant-id* "__legacy__")]
    (when @persistence-enabled?
      (jdbc/with-db-transaction [tx db]
        (jdbc/delete! tx :rebac_relation_rewrites ["tenant_id = ? AND relation = ?" tenant-id relation])
        (doseq [derived-relation derived-relations]
          (jdbc/insert! tx :rebac_relation_rewrites (rewrite-row relation derived-relation)))))
    (swap! relation-rewrites assoc-in [tenant-id relation] derived-relations)
    (get-in @relation-rewrites [tenant-id relation])))

(defn delete-relation-rewrite!
  [relation]
  (let [relation (name relation)
        tenant-id (or *tenant-id* "__legacy__")]
    (when @persistence-enabled?
      (jdbc/delete! db :rebac_relation_rewrites ["tenant_id = ? AND relation = ?" tenant-id relation]))
    (swap! relation-rewrites update tenant-id dissoc relation)
    true))

(defn clear-relation-rewrites!
  ([] (clear-relation-rewrites! {}))
  ([{:keys [persist]}]
   (reset! relation-rewrites {})
   (when (and persist @persistence-enabled?)
     (jdbc/delete! db :rebac_relation_rewrites ["1 = 1"]))
   true))

(defn list-relation-rewrites
  []
  (get @relation-rewrites (or *tenant-id* "__legacy__") {}))

(defn- expired?
  [tuple]
  (when-let [expires-at (get-in tuple [:projection :expiresAt])]
    (try
      (.isAfter (Instant/now) (Instant/parse expires-at))
      (catch Exception _ false))))

(defn- direct-relation?
  [tuples subject relation resource]
  (let [identity (relation-tuple subject relation resource)]
    (boolean (some #(and (not (expired? %))
                         (= identity (tuple-identity %)))
                   tuples))))

(defn- parent-resources
  [store resource-key]
  (get-in store [:parents-by-child resource-key] #{}))

(defn- member-groups
  [store member-key]
  (get-in store [:memberships-by-member member-key] #{}))

(defn- child-resources
  [store parent-key]
  (get-in store [:children-by-parent parent-key] #{}))

(defn- group-members
  [store group-key]
  (get-in store [:members-by-group group-key] #{}))

(defn- entity-matches-class?
  [entity class-name]
  (or (nil? class-name)
      (= class-name (:class entity))))

(defn- sort-entities
  [entities]
  (->> entities
       distinct
       (sort-by (juxt :class :id))
       vec))

(defn- ancestor-paths
  [store resource-key max-depth]
  (loop [frontier [{:resource resource-key
                    :path [resource-key]}]
         visited #{}
         paths []
         depth 0]
    (cond
      (empty? frontier)
      paths

      (> depth max-depth)
      paths

      :else
      (let [{:keys [resource path] :as current} (first frontier)
            remaining (subvec (vec frontier) 1)]
        (if (contains? visited resource)
          (recur remaining visited paths depth)
          (let [parents (remove visited (parent-resources store resource))
                parent-paths (mapv (fn [parent]
                                     {:resource parent
                                      :path (conj path parent)})
                                   parents)]
            (recur (vec (concat remaining parent-paths))
                   (conj visited resource)
                   (conj paths current)
                   (inc depth))))))))

(defn- subject-paths
  [store subject-key max-depth]
  (loop [frontier [{:subject subject-key
                    :path [subject-key]}]
         visited #{}
         paths []
         depth 0]
    (cond
      (empty? frontier)
      paths

      (> depth max-depth)
      paths

      :else
      (let [{:keys [subject path] :as current} (first frontier)
            remaining (subvec (vec frontier) 1)]
        (if (contains? visited subject)
          (recur remaining visited paths depth)
          (let [groups (remove visited (member-groups store subject))
                group-paths (mapv (fn [group]
                                    {:subject group
                                     :path (conj path group)})
                                  groups)]
            (recur (vec (concat remaining group-paths))
                   (conj visited subject)
                   (conj paths current)
                   (inc depth))))))))

(defn- descendant-resource-paths
  [store resource-key max-depth]
  (loop [frontier [{:resource resource-key
                    :path [resource-key]}]
         visited #{}
         paths []
         depth 0]
    (cond
      (empty? frontier)
      paths

      (> depth max-depth)
      paths

      :else
      (let [{:keys [resource path] :as current} (first frontier)
            remaining (subvec (vec frontier) 1)]
        (if (contains? visited resource)
          (recur remaining visited paths depth)
          (let [children (remove visited (child-resources store resource))
                child-paths (mapv (fn [child]
                                    {:resource child
                                     :path (conj path child)})
                                  children)]
            (recur (vec (concat remaining child-paths))
                   (conj visited resource)
                   (conj paths current)
                   (inc depth))))))))

(defn- descendant-subject-paths
  [store subject-key max-depth]
  (loop [frontier [{:subject subject-key
                    :path [subject-key]}]
         visited #{}
         paths []
         depth 0]
    (cond
      (empty? frontier)
      paths

      (> depth max-depth)
      paths

      :else
      (let [{:keys [subject path] :as current} (first frontier)
            remaining (subvec (vec frontier) 1)]
        (if (contains? visited subject)
          (recur remaining visited paths depth)
          (let [members (remove visited (group-members store subject))
                member-paths (mapv (fn [member]
                                     {:subject member
                                      :path (conj path member)})
                                   members)]
            (recur (vec (concat remaining member-paths))
                   (conj visited subject)
                   (conj paths current)
                   (inc depth))))))))

(defn- relation-paths
  [relation max-depth]
  (let [root (name relation)]
    (loop [frontier [{:relation root
                      :path [root]}]
           visited #{}
           paths []
           depth 0]
      (cond
        (empty? frontier)
        paths

        (> depth max-depth)
        paths

        :else
        (let [{:keys [relation path] :as current} (first frontier)
              remaining (subvec (vec frontier) 1)]
          (if (contains? visited relation)
            (recur remaining visited paths depth)
            (let [derived (remove visited (get-in @relation-rewrites [(or *tenant-id* "__legacy__") relation] []))
                  derived-paths (mapv (fn [derived-relation]
                                        {:relation derived-relation
                                         :path (conj path derived-relation)})
                                      derived)]
	              (recur (vec (concat remaining derived-paths))
	                     (conj visited relation)
	                     (conj paths current)
	                     (inc depth)))))))))

(defn- normalized-traversal-step
  [step]
  (if (map? step)
    {:relation (name (:relation step))
     :direction (or (:direction step) "out")}
    {:relation (name step)
     :direction "out"}))

(defn- relation-names-for-step
  [relation expand-rewrites max-depth]
  (if expand-rewrites
    (mapv :relation (relation-paths relation max-depth))
    [(name relation)]))

(defn- tuple-neighbors
  [store entity relation direction]
  (let [inbound? (contains? #{"in" "inbound"} (name direction))
        neighbors (if inbound?
                    (get-in store [:by-resource-relation [entity relation]] #{})
                    (get-in store [:by-subject-relation [entity relation]] #{}))]
    (filter (fn [neighbor]
              (some #(and (not (expired? %))
                          (= relation (:relation %))
                          (if inbound?
                            (and (= entity (:resource %)) (= neighbor (:subject %)))
                            (and (= entity (:subject %)) (= neighbor (:resource %)))))
                    (:tuples store)))
            neighbors)))

(defn traverse-relations
  "Traverses a relation path from a starting entity.
   Steps can be relation names or maps like {:relation \"parent\" :direction \"out\"}.
   Directions are out/outbound for subject->resource and in/inbound for resource->subject."
  ([start steps]
   (traverse-relations start steps {}))
  ([start steps {:keys [target-class expand-rewrites max-depth]
                 :or {expand-rewrites true
                      max-depth default-max-depth}}]
   (let [store @relation-tuples
         normalized-steps (mapv normalized-traversal-step steps)
         start-key (entity-key start)
         final-paths (reduce
                      (fn [paths {:keys [relation direction]}]
                        (vec
                         (for [{:keys [entity path]} paths
                               relation-name (relation-names-for-step relation expand-rewrites max-depth)
                               neighbor (tuple-neighbors store entity relation-name direction)]
                           {:entity neighbor
                            :path (conj path {:relation relation-name
                                              :direction (name direction)
                                              :entity neighbor})})))
                      [{:entity start-key
                        :path [{:entity start-key}]}]
                      normalized-steps)
         filtered-paths (filter #(entity-matches-class? (:entity %) target-class) final-paths)
         entities (sort-entities (map :entity filtered-paths))]
     {:start start-key
      :steps normalized-steps
      :entities entities
      :count (count entities)
      :paths (vec (sort-by pr-str filtered-paths))})))

(defn list-accessible-resources
  "Lists resources for which subject has relation.
   Rewrites, group membership and resource inheritance are applied by default.
   Pass {:resource-class \"Document\"} to filter returned resources."
  ([subject relation]
   (list-accessible-resources subject relation {}))
  ([subject relation {:keys [resource-class inherited max-depth]
                      :or {inherited true
                           max-depth default-max-depth}}]
   (let [store @relation-tuples
         tuples (:tuples store)
         subject-key (entity-key subject)
         subject-candidates (set (map :subject
                                      (if inherited
                                        (subject-paths store subject-key max-depth)
                                        [{:subject subject-key
                                          :path [subject-key]}])))
         relation-candidates (set (map :relation (relation-paths relation max-depth)))
         matched-resources (for [{:keys [subject relation resource] :as tuple} tuples
                                 :when (and (not (expired? tuple))
                                            (contains? subject-candidates subject)
                                            (contains? relation-candidates relation))]
                             resource)
         resources (if inherited
                     (mapcat #(map :resource
                                    (descendant-resource-paths store % max-depth))
                             matched-resources)
                     matched-resources)]
     (->> resources
          (filter #(entity-matches-class? % resource-class))
          sort-entities))))

(defn list-authorized-subjects
  "Lists subjects that have relation to resource.
   Rewrites, nested group membership and resource inheritance are applied by default.
   Pass {:subject-class \"Person\"} to filter returned subjects."
  ([resource relation]
   (list-authorized-subjects resource relation {}))
  ([resource relation {:keys [subject-class inherited max-depth]
                       :or {inherited true
                            max-depth default-max-depth}}]
   (let [store @relation-tuples
         tuples (:tuples store)
         resource-key (entity-key resource)
         resource-candidates (set (map :resource
                                       (if inherited
                                         (ancestor-paths store resource-key max-depth)
                                         [{:resource resource-key
                                           :path [resource-key]}])))
         relation-candidates (set (map :relation (relation-paths relation max-depth)))
         matched-subjects (for [{:keys [subject relation resource] :as tuple} tuples
                                :when (and (not (expired? tuple))
                                           (contains? resource-candidates resource)
                                           (contains? relation-candidates relation))]
                            subject)
         subjects (if inherited
                    (mapcat #(map :subject
                                   (descendant-subject-paths store % max-depth))
                            matched-subjects)
                    matched-subjects)]
     (->> subjects
          (filter #(entity-matches-class? % subject-class))
          sort-entities))))

(defn explain-relation
  "Explains whether subject has relation to resource.
   The explanation includes the matched resource and the parent path when a
   relation is inherited from an ancestor."
  ([subject relation resource]
   (explain-relation subject relation resource {}))
  ([subject relation resource {:keys [inherited max-depth]
                               :or {inherited true
                                    max-depth default-max-depth}}]
   (let [store @relation-tuples
         tuples (:tuples store)
         subject-key (entity-key subject)
         resource-key (entity-key resource)
         subject-candidates (if inherited
                              (subject-paths store subject-key max-depth)
                              [{:subject subject-key
                                :path [subject-key]}])
         resource-candidates (if inherited
                               (ancestor-paths store resource-key max-depth)
                               [{:resource resource-key
                                 :path [resource-key]}])
         match (first
                (for [relation-candidate (relation-paths relation max-depth)
                      subject-candidate subject-candidates
                      resource-candidate resource-candidates
                      tuple tuples
                      :when (and (not (expired? tuple))
                                 (= (relation-tuple (:subject subject-candidate)
                                                    (:relation relation-candidate)
                                                    (:resource resource-candidate))
                                    (tuple-identity tuple)))]
                  {:relation-candidate relation-candidate
                   :subject-candidate subject-candidate
                   :resource-candidate resource-candidate
                   :tuple tuple}))]
     (cond-> {:allowed (boolean match)
              :subject subject-key
              :relation (name relation)
              :resource resource-key}
       match
       (assoc :matchedSubject (get-in match [:subject-candidate :subject])
              :matchedRelation (get-in match [:relation-candidate :relation])
              :matchedResource (get-in match [:resource-candidate :resource])
              :inherited (or (not= subject-key (get-in match [:subject-candidate :subject]))
                             (not= resource-key (get-in match [:resource-candidate :resource]))
                             (not= (name relation) (get-in match [:relation-candidate :relation])))
              :path (get-in match [:resource-candidate :path]))
       (get-in match [:tuple :projection])
       (assoc :projection (get-in match [:tuple :projection]))
       (and match (not= subject-key (get-in match [:subject-candidate :subject])))
       (assoc :subjectPath (get-in match [:subject-candidate :path]))
       (and match (not= (name relation) (get-in match [:relation-candidate :relation])))
       (assoc :relationPath (get-in match [:relation-candidate :path]))))))

(defn has-relation?
  "Returns true when subject has relation to resource.
   Direct tuples are always checked. By default, the check also walks resource
   ancestry through `parent` tuples, so a relation granted on a parent resource
   applies to its descendants."
  ([subject relation resource]
   (has-relation? subject relation resource {}))
  ([subject relation resource {:keys [inherited max-depth]
                               :or {inherited true
                                    max-depth default-max-depth}}]
   (:allowed (explain-relation subject relation resource {:inherited inherited
                                                          :max-depth max-depth}))))
