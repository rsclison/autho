(ns autho.time-travel
  "Time-travel authorization queries: answer 'who was authorized at time T?'

   Business object attributes at T are rebuilt by replaying the Kafka history
   topic; the policy in force at T is the last deployed version created before T
   (POLICY_VERSIONS). Decisions are evaluated with those attributes only: no
   live PIP, filler or cache value is used. Relations and delegations have no
   history: relation clauses are evaluated on the current relation store
   (flagged with a warning) and delegations are not applied."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [jsonista.core :as json]
            [autho.pdp :as pdp]
            [autho.prp :as prp]
            [autho.policy-versions :as pv]
            [autho.audit :as audit])
  (:import (org.apache.kafka.clients.consumer KafkaConsumer ConsumerConfig)
           (org.apache.kafka.common TopicPartition)
           (java.util Properties)
           (java.time Instant Duration)
           (java.time.format DateTimeFormatter)))

(def object-mapper (json/object-mapper {:decode-key-fn true}))

(defn create-history-consumer [bootstrap-servers]
  "Creates a Kafka consumer for reading history topics"
  (let [props (Properties.)]
    (doto props
      (.put ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG bootstrap-servers)
      (.put ConsumerConfig/GROUP_ID_CONFIG (str "autho-time-travel-" (System/currentTimeMillis)))
      (.put ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringDeserializer")
      (.put ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringDeserializer")
      (.put ConsumerConfig/ENABLE_AUTO_COMMIT_CONFIG "false")
      (.put ConsumerConfig/AUTO_OFFSET_RESET_CONFIG "earliest"))
    (KafkaConsumer. props)))

(defn parse-timestamp [timestamp-str]
  "Parses ISO-8601 timestamp string to Instant"
  (try
    (Instant/parse timestamp-str)
    (catch Exception e
      (log/error e "Failed to parse timestamp:" timestamp-str)
      nil)))

(defn replay-topic-until
  "Replays a history topic until the specified timestamp, building state snapshot"
  [consumer topic target-timestamp]
  (log/info "Replaying topic" topic "until" target-timestamp)

  (let [partitions (.partitionsFor consumer topic)
        _ (when (empty? partitions)
            (throw (ex-info (str "History topic " topic " not found")
                            {:status 503 :error-code "HISTORY_TOPIC_NOT_FOUND"})))
        topic-partitions (map #(TopicPartition. topic (.partition %)) partitions)
        state (atom {})]

    ;; Assign all partitions
    (.assign consumer topic-partitions)

    ;; Seek to beginning
    (.seekToBeginning consumer topic-partitions)

    (let [target-instant (parse-timestamp target-timestamp)]
      (when target-instant
        (loop []
          (let [records (.poll consumer (Duration/ofMillis 1000))]
            (when-not (.isEmpty records)
              (doseq [record records]
                (try
                  (let [key (.key record)
                        value-str (.value record)
                        value-map (json/read-value value-str object-mapper)
                        msg-timestamp (:_timestamp value-map)
                        msg-instant (parse-timestamp msg-timestamp)]

                    ;; Only process messages before target time
                    (when (and msg-instant (.isBefore msg-instant target-instant))
                      (let [data (:_data value-map)
                            existing (get @state key {})
                            merged (merge existing data)]
                        (swap! state assoc key merged))))

                  (catch Exception e
                    (log/error e "Error processing record from" topic))))

              ;; Continue polling until we reach target time
              (recur))))))

    (log/info "Replay complete. State contains" (count @state) "objects")
    @state))

(defn extract-class-from-message
  "Extracts class name from enriched history message"
  [value-map]
  (let [data (:_data value-map)]
    (or (:class data) (get data "class"))))

(defn replay-unified-topic-until
  "Replays the unified history topic, building state snapshot organized by class"
  [consumer topic target-timestamp]
  (log/info "Replaying unified topic" topic "until" target-timestamp)

  (let [partitions (.partitionsFor consumer topic)
        _ (when (empty? partitions)
            (throw (ex-info (str "History topic " topic " not found")
                            {:status 503 :error-code "HISTORY_TOPIC_NOT_FOUND"})))
        topic-partitions (map #(TopicPartition. topic (.partition %)) partitions)
        state (atom {})] ;; Will be organized as {class-name {object-id {...}}}

    ;; Assign all partitions
    (.assign consumer topic-partitions)

    ;; Seek to beginning
    (.seekToBeginning consumer topic-partitions)

    (let [target-instant (parse-timestamp target-timestamp)]
      (when target-instant
        (loop []
          (let [records (.poll consumer (Duration/ofMillis 1000))]
            (when-not (.isEmpty records)
              (doseq [record records]
                (try
                  (let [value-str (.value record)
                        value-map (json/read-value value-str object-mapper)
                        msg-timestamp (:_timestamp value-map)
                        msg-instant (parse-timestamp msg-timestamp)]

                    ;; Only process messages before target time
                    (when (and msg-instant (.isBefore msg-instant target-instant))
                      (let [data (:_data value-map)
                            class-name (or (:class data) (get data "class"))
                            object-id (some-> (or (:id data) (get data "id") (.key record)) str)]
                        (when (and class-name object-id)
                          (let [clean-data (dissoc data :class "class" :id "id")
                                existing (get-in @state [class-name object-id] {})
                                merged (merge existing clean-data)]
                            (swap! state assoc-in [class-name object-id] merged))))))

                  (catch Exception e
                    (log/error e "Error processing record from" topic))))

              ;; Continue polling until we reach target time
              (recur))))))

    (log/info "Replay complete. Classes:" (keys @state)
              "Total objects:" (reduce + (map count (vals @state))))
    @state))

(defn build-historical-snapshot
  "Builds a complete snapshot of all business objects at the specified timestamp"
  [timestamp {:keys [bootstrap-servers history-topic]
              :or {bootstrap-servers "localhost:9092"
                   history-topic "business-objects-history"}}]
  (log/info "Building historical snapshot for timestamp:" timestamp)

  (let [consumer (create-history-consumer bootstrap-servers)]
    (try
      (replay-unified-topic-until consumer history-topic timestamp)
      (catch org.apache.kafka.common.errors.TimeoutException e
        (throw (ex-info (str "Kafka history unavailable: " (.getMessage e))
                        {:status 503 :error-code "HISTORY_UNAVAILABLE"} e)))
      (finally
        (.close consumer)))))

(defn query-historical-attributes
  "Retrieves attributes for a specific object at a historical timestamp"
  [class-name object-id timestamp config]
  (let [snapshot (build-historical-snapshot timestamp config)
        class-data (get snapshot class-name)
        object-data (get class-data object-id)]
    (log/debug "Historical query:" class-name object-id "at" timestamp "=" object-data)
    object-data))

;; ---------------------------------------------------------------------------
;; Historical evaluation
;; ---------------------------------------------------------------------------

(def ^:private default-candidate-limit 1000)
(def ^:private max-candidate-limit 10000)

(defn- bad-request!
  [code message]
  (throw (ex-info message {:status 400 :error-code code})))

(defn- parse-instant!
  [timestamp]
  (or (when (string? timestamp) (parse-timestamp timestamp))
      (bad-request! "INVALID_TIMESTAMP" "timestamp must be an ISO-8601 instant (e.g. 2026-03-15T10:30:00Z)")))

(defn- candidate-limit
  [{:keys [limit]}]
  (let [n (cond
            (nil? limit) default-candidate-limit
            (integer? limit) limit
            :else (try (Long/parseLong (str limit))
                       (catch NumberFormatException _
                         (bad-request! "INVALID_LIMIT" "limit must be an integer"))))]
    (-> n (max 1) (min max-candidate-limit))))

(defn- environment-of
  [config]
  (or (some-> (:environment config) str not-empty) prp/default-policy-environment))

(defn policy-at
  "Policy in force for resource-class at instant in the given environment:
   the last version created at or before instant. Falls back to the current
   policy when no version has ever been recorded (policies loaded from files).
   Returns {:source \"version\"|\"current\"|\"none\" :policy .. :version .. :createdAt ..}."
  [resource-class ^Instant instant environment]
  (let [versions (pv/list-versions resource-class)]
    (if (empty? versions)
      (if-let [policy (prp/getGlobalPolicy resource-class environment)]
        {:source "current" :policy policy}
        {:source "none" :policy nil})
      (or (some (fn [{:keys [version created_at]}]
                  (let [created (.toInstant ^java.sql.Timestamp created_at)]
                    (when-not (.isAfter created instant)
                      (let [policy (pv/get-version resource-class version)]
                        (when (= environment (or (:environment policy) prp/default-policy-environment))
                          {:source "version" :policy policy :version version
                           :createdAt (str created)})))))
                versions)
          {:source "none" :policy nil}))))

(defn- policy-summary
  [policy-info]
  (select-keys policy-info [:source :version :createdAt]))

(defn- conditions-of
  [policy]
  (mapcat :conditions (:rules policy)))

(defn- relation-clause?
  [clause]
  (and (sequential? clause)
       (contains? #{"relation" "related" "has-relation"} (some-> (first clause) name))))

(defn- subject-classes
  "Classes the policy reads subject attributes from ([Class \"$s\" attribute])."
  [policy]
  (set (concat
        (for [clause (conditions-of policy)
              operand (rest clause)
              :when (and (sequential? operand) (= "$s" (str (second operand))))]
          (name (first operand)))
        (keep #(some-> (:subjectCond %) first name) (:rules policy)))))

(defn- snapshot-entity
  [snapshot class-name id]
  (when-let [attributes (get-in snapshot [class-name (str id)])]
    (assoc attributes :class class-name :id (str id))))

(defn- snapshot-resolver
  "Attribute resolver backed by the snapshot only."
  [snapshot]
  (fn [attribute obj]
    (get-in snapshot [(some-> (:class obj) name) (some-> (:id obj) str) (keyword attribute)])))

(defn- resolve-subject-class
  [snapshot subject preferred-classes]
  (or (some-> (:class subject) name)
      (let [found (filter #(contains? (get snapshot %) (str (:id subject))) (keys snapshot))
            preferred (filter preferred-classes found)]
        (cond
          (= 1 (count preferred)) (first preferred)
          (= 1 (count found)) (first found)
          (empty? found) nil
          :else (throw (ex-info (str "Subject " (:id subject) " exists in several classes "
                                     (vec (sort found)) "; specify subject.class")
                                {:status 400 :error-code "AMBIGUOUS_SUBJECT_CLASS"}))))))

(defn- historical-entity
  "Snapshot attributes override caller-supplied ones: the caller may only
   identify the object."
  [snapshot entity class-name]
  (merge (dissoc entity :class)
         (when class-name {:class class-name})
         (when class-name (snapshot-entity snapshot class-name (:id entity)))))

(defn- relation-warnings
  [policy]
  (when (some relation-clause? (conditions-of policy))
    ["RELATIONS_EVALUATED_ON_CURRENT_STATE"]))

(defn- evaluate
  [policy request snapshot]
  (if policy
    (pdp/evaluate-with-attributes policy request (snapshot-resolver snapshot))
    {:allowed? false :matched-rule-names []}))

(defn- policy-warnings
  [policy-info]
  (case (:source policy-info)
    "current" ["POLICY_VERSION_UNAVAILABLE"]
    "none" ["NO_POLICY_AT_TIME"]
    []))

(defn is-authorized-at-time
  "Evaluates an authorization request as it would have been decided at
   `timestamp`, with the business objects and the policy of that time."
  [request timestamp config]
  (log/info "Time-travel authorization query at" timestamp)
  (let [instant (parse-instant! timestamp)
        operation (or (:operation request) (:action request))
        resource-class (some-> (get-in request [:resource :class]) name)
        environment (environment-of config)]
    (when-not (and (map? (:subject request)) (some? (get-in request [:subject :id])))
      (bad-request! "MISSING_SUBJECT" "Request must include subject.id"))
    (when-not (and resource-class (some? (get-in request [:resource :id])))
      (bad-request! "MISSING_RESOURCE" "Request must include resource.class and resource.id"))
    (let [snapshot (build-historical-snapshot timestamp config)
          policy-info (policy-at resource-class instant environment)
          policy (:policy policy-info)
          subject-class (resolve-subject-class snapshot (:subject request) (subject-classes policy))
          subject (historical-entity snapshot (:subject request) subject-class)
          resource (historical-entity snapshot (:resource request) resource-class)
          result (evaluate policy (assoc request :subject subject :resource resource :operation operation)
                           snapshot)]
      {:timestamp timestamp
       :decision (if (:allowed? result) "allow" "deny")
       :allowed (:allowed? result)
       :matchedRules (:matched-rule-names result)
       :subject subject
       :resource resource
       :operation operation
       :policy (policy-summary policy-info)
       :snapshot-size (reduce + (map count (vals snapshot)))
       :warnings (vec (concat (policy-warnings policy-info)
                              (when-not (and subject-class (snapshot-entity snapshot subject-class (get-in request [:subject :id])))
                                ["SUBJECT_NOT_IN_SNAPSHOT"])
                              (when-not (snapshot-entity snapshot resource-class (get-in request [:resource :id]))
                                ["RESOURCE_NOT_IN_SNAPSHOT"])
                              (relation-warnings policy)))})))

(defn- take-candidates
  [limit candidates]
  (let [taken (vec (take (inc limit) candidates))]
    [(vec (take limit taken)) (> (count taken) limit)]))

(defn who-was-authorized-at
  "Returns the subjects of the snapshot that were authorized to perform
   `action` on the resource at time T. Candidates are the snapshot objects of
   the classes the policy reads subject attributes from, or of config
   :subject-class."
  [resource-class resource-id action timestamp config]
  (log/info "Query: Who was authorized to" action resource-class resource-id "at" timestamp "?")
  (let [instant (parse-instant! timestamp)
        environment (environment-of config)
        snapshot (build-historical-snapshot timestamp config)
        policy-info (policy-at resource-class instant environment)
        policy (:policy policy-info)
        classes (if-let [c (:subject-class config)] #{(name c)} (subject-classes policy))
        _ (when (and policy (empty? classes))
            (bad-request! "SUBJECT_CLASS_REQUIRED"
                          "The policy does not reference subject attributes by class; specify subjectClass"))
        resource (historical-entity snapshot {:id resource-id} resource-class)
        limit (candidate-limit config)
        [candidates truncated?] (take-candidates limit
                                                 (for [c (sort classes)
                                                       id (sort (keys (get snapshot c)))]
                                                   (snapshot-entity snapshot c id)))
        authorized (vec (keep (fn [subject]
                                (let [r (evaluate policy {:subject subject :resource resource :operation action} snapshot)]
                                  (when (:allowed? r)
                                    {:class (:class subject) :id (:id subject)
                                     :matchedRules (:matched-rule-names r)})))
                              candidates))]
    {:query "who-was-authorized-at"
     :resource {:class resource-class :id resource-id}
     :action action
     :timestamp timestamp
     :policy (policy-summary policy-info)
     :authorized-subjects authorized
     :evaluated (count candidates)
     :truncated truncated?
     :warnings (vec (concat (policy-warnings policy-info)
                            (when-not (snapshot-entity snapshot resource-class resource-id)
                              ["RESOURCE_NOT_IN_SNAPSHOT"])
                            (relation-warnings policy)))}))

(defn what-could-access-at
  "Returns the snapshot objects the subject could perform `action` on at time T.
   Candidates are the snapshot objects of every class that had a policy at T,
   or only config :resource-class."
  [subject-id action timestamp config]
  (log/info "Query: What could" subject-id action "at" timestamp "?")
  (let [instant (parse-instant! timestamp)
        environment (environment-of config)
        snapshot (build-historical-snapshot timestamp config)
        policies (into {}
                       (keep (fn [c]
                               (let [info (policy-at c instant environment)]
                                 (when (:policy info) [c info]))))
                       (if-let [rc (:resource-class config)] [(name rc)] (sort (keys snapshot))))
        preferred (set (mapcat (comp subject-classes :policy) (vals policies)))
        subject-class (resolve-subject-class snapshot {:id subject-id :class (:subject-class config)} preferred)
        subject (historical-entity snapshot {:id subject-id} subject-class)
        limit (candidate-limit config)
        [candidates truncated?] (take-candidates limit
                                                 (for [c (sort (keys policies))
                                                       id (sort (keys (get snapshot c)))]
                                                   (snapshot-entity snapshot c id)))
        accessible (vec (keep (fn [resource]
                                (let [r (evaluate (get-in policies [(:class resource) :policy])
                                                  {:subject subject :resource resource :operation action}
                                                  snapshot)]
                                  (when (:allowed? r)
                                    {:class (:class resource) :id (:id resource)
                                     :matchedRules (:matched-rule-names r)})))
                              candidates))]
    {:query "what-could-access-at"
     :subject subject-id
     :action action
     :timestamp timestamp
     :policies (into {} (map (fn [[c info]] [c (policy-summary info)]) policies))
     :accessible-resources accessible
     :evaluated (count candidates)
     :truncated truncated?
     :warnings (vec (distinct
                     (concat (when-not (and subject-class (snapshot-entity snapshot subject-class subject-id))
                               ["SUBJECT_NOT_IN_SNAPSHOT"])
                             (when (some #(= "current" (:source %)) (vals policies))
                               ["POLICY_VERSION_UNAVAILABLE"])
                             (mapcat (comp relation-warnings :policy) (vals policies)))))}))

(defn- audit-row->trail-event
  [{:keys [id ts subject_id operation decision matched_rules decision_snapshot]}]
  (cond-> {:auditId id
           :timestamp ts
           :subject subject_id
           :action operation
           :decision decision
           :plane (or (:plane decision_snapshot) "abac")
           :matchedRules matched_rules}
    (:delegationChain decision_snapshot)
    (assoc :delegationChain (:delegationChain decision_snapshot)
           :grantedVia (:grantedVia decision_snapshot))))

(defn audit-trail
  "Returns the recorded access history (ABAC decisions and ReBAC checks) for a
   resource over a time range, oldest first, read from the audit log.
   config may carry :page and :page-size."
  [resource-class resource-id start-time end-time config]
  (log/info "Audit trail for" resource-class resource-id "from" start-time "to" end-time)
  (let [result (audit/search {:resource-class resource-class
                              :resource-id resource-id
                              :from start-time
                              :to end-time
                              :order "asc"
                              :page (:page config)
                              :page-size (:page-size config)})]
    {:query "audit-trail"
     :source "audit-log"
     :resource {:class resource-class :id resource-id}
     :time-range {:start start-time :end end-time}
     :total (:total result)
     :page (:page result)
     :pageSize (:pageSize result)
     :events (mapv audit-row->trail-event (:items result))}))
