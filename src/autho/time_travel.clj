(ns autho.time-travel
  "Time-travel authorization queries: answer 'who was authorized at time T?'"
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [autho.pdp :as pdp])
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

(defn build-historical-snapshot
  "Builds a complete snapshot of all business objects at the specified timestamp"
  [timestamp {:keys [bootstrap-servers] :or {bootstrap-servers "localhost:9092"}}]
  (log/info "Building historical snapshot for timestamp:" timestamp)

  (let [consumer (create-history-consumer bootstrap-servers)
        history-topics ["invoices-history" "contracts-history" "legal-commitments-history"]
        snapshot (atom {})]

    (try
      (doseq [topic history-topics]
        (let [class-name (case topic
                          "invoices-history" "Facture"
                          "contracts-history" "Contrat"
                          "legal-commitments-history" "EngagementJuridique")
              topic-state (replay-topic-until consumer topic timestamp)]
          (swap! snapshot assoc class-name topic-state)))

      (finally
        (.close consumer)))

    @snapshot))

(defn query-historical-attributes
  "Retrieves attributes for a specific object at a historical timestamp"
  [class-name object-id timestamp config]
  (let [snapshot (build-historical-snapshot timestamp config)
        class-data (get snapshot class-name)
        object-data (get class-data object-id)]
    (log/debug "Historical query:" class-name object-id "at" timestamp "=" object-data)
    object-data))

(defn is-authorized-at-time
  "Evaluates authorization at a specific point in time using historical data"
  [request timestamp config]
  (log/info "Time-travel authorization query at" timestamp)
  (log/debug "Request:" request)

  ;; Build complete historical snapshot
  (let [snapshot (build-historical-snapshot timestamp config)]

    ;; Create a temporary PIP resolver that uses the snapshot instead of live data
    (letfn [(historical-pip-resolver [class-name object-id]
              (get-in snapshot [class-name object-id]))]

      ;; Evaluate the authorization request with historical data
      ;; This would need integration with the main PDP
      ;; For now, return the snapshot for inspection
      {:timestamp timestamp
       :snapshot-size (reduce + (map count (vals snapshot)))
       :decision "EVALUATION_PENDING"
       :note "Historical snapshot built successfully. PDP integration required."})))

;; Helper functions for common time-travel queries

(defn who-was-authorized-at
  "Returns all subjects who were authorized to access a resource at time T"
  [resource-class resource-id action timestamp config]
  (log/info "Query: Who was authorized to" action resource-class resource-id "at" timestamp "?")
  ;; This would iterate through all subjects and check authorization
  ;; Using the historical snapshot
  {:query "who-was-authorized-at"
   :resource {:class resource-class :id resource-id}
   :action action
   :timestamp timestamp
   :authorized-subjects []  ;; To be implemented with PDP integration
   })

(defn what-could-access-at
  "Returns all resources a subject could access at time T"
  [subject-id action timestamp config]
  (log/info "Query: What could" subject-id action "at" timestamp "?")
  {:query "what-could-access-at"
   :subject subject-id
   :action action
   :timestamp timestamp
   :accessible-resources []  ;; To be implemented with PDP integration
   })

(defn audit-trail
  "Returns complete access history for a resource over a time range"
  [resource-class resource-id start-time end-time config]
  (log/info "Audit trail for" resource-class resource-id "from" start-time "to" end-time)
  {:query "audit-trail"
   :resource {:class resource-class :id resource-id}
   :time-range {:start start-time :end end-time}
   :events []  ;; To be implemented - requires decision logging
   })
