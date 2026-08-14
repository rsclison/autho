(ns autho.rebac-kafka
  "Kafka transport for authorization-relationship projections.
   The consumer delegates all semantics (deduplication, ordering, persistence)
   to autho.rebac/apply-projection-event!."
  (:require [autho.rebac :as rebac]
            [autho.metrics :as metrics]
            [clojure.tools.logging :as log]
            [jsonista.core :as json])
  (:import (org.apache.kafka.clients.consumer KafkaConsumer ConsumerConfig)
           (java.util Properties)
           (java.time Duration)))

(defonce consumer-handle (atom nil))
(def object-mapper (json/object-mapper {:decode-key-fn true}))

(defn process-event-value!
  "Processes one JSON event. Invalid messages are quarantined and do not stop
   the consumer; valid messages are delegated to the idempotent ReBAC core."
  [value]
  (try
    (let [event (json/read-value value object-mapper)
          result (rebac/apply-projection-event! event)]
      (metrics/record-rebac-projection-event! (:status result) (:source event))
      (metrics/record-rebac-projection-observed!)
      result)
    (catch Exception e
      (let [entry (rebac/quarantine-projection-event! value (.getMessage e))]
        (metrics/record-rebac-projection-event! :quarantined "unknown")
        (metrics/record-rebac-quarantine! :created)
        (log/warn e "Quarantined invalid authorization relationship event")
        {:status :quarantined :reason (:reason entry)}))))

(defn create-consumer
  [{:keys [bootstrap-servers group-id]}]
  (let [props (Properties.)]
    (doto props
      (.put ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG bootstrap-servers)
      (.put ConsumerConfig/GROUP_ID_CONFIG (or group-id "autho-rebac-projection"))
      (.put ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringDeserializer")
      (.put ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringDeserializer")
      (.put ConsumerConfig/AUTO_OFFSET_RESET_CONFIG "earliest"))
    (KafkaConsumer. props)))

(defn- record-consumer-lag!
  [consumer]
  (let [assignments (.assignment consumer)]
    (when (seq assignments)
      (let [end-offsets (.endOffsets consumer assignments)]
        (doseq [partition assignments]
          (let [end-offset (long (.get end-offsets partition))
                position (.position consumer partition)]
            (metrics/record-rebac-kafka-lag! (.topic partition)
                                            (.partition partition)
                                            (- end-offset position))))))))

(defn start!
  [{:keys [bootstrap-servers topic group-id]
    :or {bootstrap-servers "localhost:9092"
         topic "authorization-relationships"}}]
  (when-not @consumer-handle
    (let [stop? (atom false)
          consumer (create-consumer {:bootstrap-servers bootstrap-servers :group-id group-id})
          worker (future
                   (.subscribe consumer [topic])
                   (while (not @stop?)
                     (try
                       (let [records (.poll consumer (Duration/ofMillis 1000))]
                         (metrics/record-rebac-kafka-poll!)
                         (doseq [record records]
                           (process-event-value! (.value record)))
                         (record-consumer-lag! consumer))
                       (catch Exception e
                         (log/error e "ReBAC Kafka consumer loop failed"))))
                   (.close consumer))]
      (reset! consumer-handle {:consumer consumer :stop? stop? :worker worker})
      (log/info "Started ReBAC projection consumer for" topic))))

(defn stop! []
  (when-let [{:keys [stop? consumer]} @consumer-handle]
    (reset! stop? true)
    (.wakeup consumer)
    (reset! consumer-handle nil)))

(defn quarantined [] (rebac/quarantined-projection-events))
(defn clear-quarantine! []
  (doseq [entry (quarantined)]
    (rebac/remove-quarantined-projection-event! (:id entry))))

(defn replay-quarantined!
  [id]
  (if-let [entry (first (filter #(= id (:id %)) (quarantined)))]
    (let [result (process-event-value! (:event_value entry))]
      (when (not= :quarantined (:status result))
        (rebac/remove-quarantined-projection-event! id)
        (metrics/record-rebac-quarantine! :replayed))
      result)
    {:status :not-found :id id}))

(defn- env-long [name default]
  (try (Long/parseLong (or (System/getenv name) (str default)))
       (catch Exception _ default)))

(defn status []
  (let [now (System/currentTimeMillis)
        last-event (.get metrics/rebac-last-event-ms)
        last-poll (.get metrics/rebac-last-kafka-poll-ms)
        max-idle (env-long "REBAC_KAFKA_MAX_IDLE_MS" 300000)
        max-lag (env-long "REBAC_KAFKA_MAX_LAG_RECORDS" 10000)
        lags (metrics/rebac-kafka-lags)
        current-lag (if (seq lags) (apply max (vals lags)) 0)
        age (when (pos? last-event) (- now last-event))]
    {:running (boolean @consumer-handle)
     :quarantineCount (count (quarantined))
     :lastEventTimestampMs last-event
     :lastKafkaPollTimestampMs last-poll
     :projectionAgeMs age
     :maxLagRecords current-lag
     :lagByPartition (into {} (map (fn [[[topic partition] lag]] [(str topic ":" partition) lag]) lags))
     :healthy (and (or (zero? last-poll) (<= (- now last-poll) max-idle))
                   (or (nil? age) (<= age max-idle))
                   (<= current-lag max-lag))
     :thresholds {:maxIdleMs max-idle :maxLagRecords max-lag}}))
