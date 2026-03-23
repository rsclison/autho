(ns autho.kafka-invalidation
  "Kafka-based cache invalidation for multi-instance deployments.
   Ensures cache consistency across all autho instances."
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as json])
  (:import (org.apache.kafka.clients.producer KafkaProducer ProducerConfig ProducerRecord)
           (org.apache.kafka.clients.consumer ConsumerConfig KafkaConsumer)
           (org.apache.kafka.common.serialization StringSerializer StringDeserializer)
           (java.util Properties ArrayList)
           (java.time Duration)))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- get-env-or-default
  "Get environment variable or return default value."
  [env-key default-value]
  (or (System/getenv env-key) default-value))

;; =============================================================================
;; Configuration
;; =============================================================================

(defonce invalidation-state (atom {
                                   :producer nil
                                   :consumer nil
                                   :stop-flag false
                                   :consumer-thread nil}))

(def invalidation-config
  (atom {
         ;; Kafka configuration
         :bootstrap-servers (get-env-or-default "KAFKA_BOOTSTRAP_SERVERS" "localhost:9092")

         ;; Topic for cache invalidation messages
         :topic (get-env-or-default "KAFKA_INVALIDATION_TOPIC" "autho-cache-invalidation")

         ;; Producer configuration
         :producer-opts {
                        :acks "all"           ; Wait for all replicas
                        :retries 3            ; Retry on failure
                        :compression-type "snappy"  ; Compress messages
                        :linger-ms 10         ; Batch messages for 10ms
                        :batch-size 16384     ; 16KB batch size
                        :buffer-memory 67108864}  ; 64MB buffer

         ;; Consumer configuration
         :group-id "autho-cache-invalidation"
         :auto-offset-reset "latest"  ; Start from latest messages

         ;; Enable/disable Kafka invalidation
         :enabled (Boolean/parseBoolean (get-env-or-default "KAFKA_INVALIDATION_ENABLED" "true"))}))

;; =============================================================================
;; Producer API
;; =============================================================================

(defn- create-producer
  "Create Kafka producer for invalidation messages."
  []
  (let [props (Properties.)
        conf @invalidation-config]
    (doto props
      (.put ProducerConfig/BOOTSTRAP_SERVERS_CONFIG (:bootstrap-servers conf))
      (.put ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG StringSerializer)
      (.put ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG StringSerializer)
      (.put ProducerConfig/ACKS_CONFIG (get-in conf [:producer-opts :acks]))
      (.put ProducerConfig/RETRIES_CONFIG (get-in conf [:producer-opts :retries]))
      (.put ProducerConfig/COMPRESSION_TYPE_CONFIG (get-in conf [:producer-opts :compression-type]))
      (.put ProducerConfig/LINGER_MS_CONFIG (get-in conf [:producer-opts :linger-ms]))
      (.put ProducerConfig/BATCH_SIZE_CONFIG (get-in conf [:producer-opts :batch-size]))
      (.put ProducerConfig/BUFFER_MEMORY_CONFIG (get-in conf [:producer-opts :buffer-memory]))
      (.put ProducerConfig/ENABLE_IDEMPOTENCE_CONFIG true))  ; Exactly-once semantics
    (KafkaProducer. props)))

(defn publish-invalidation
  "Publish cache invalidation message to Kafka.

   Parameters:
   - cache-type: :subject, :resource, or :policy
   - cache-key: The cache key to invalidate

   Message format: {:type \"subject\" :key \"user123\" :timestamp 1234567890}"
  [cache-type cache-key]
  (if-not (:enabled @invalidation-config)
    (do (log/trace "Kafka invalidation disabled, skipping") nil)
    (try
      (let [producer (:producer @invalidation-state)]
        (if-not producer
          (do (log/warn "Invalidation producer not initialized") nil)
          (let [message {:type (name cache-type)
                         :key cache-key
                         :timestamp (System/currentTimeMillis)}
                message-json (json/write-value-as-string message)
                record (ProducerRecord. (:topic @invalidation-config)
                                        cache-key
                                        message-json)]
            (.send producer
                   (fn [metadata]
                     (when metadata
                       (log/trace "Published invalidation:" cache-type ":" cache-key
                                 "to partition" (.partition metadata)
                                 "offset" (.offset metadata))))
                   (fn [e]
                     (log/error e "Failed to publish invalidation:" cache-type ":" cache-key)))
            (log/trace "Queued invalidation message:" cache-type ":" cache-key)
            true)))
      (catch Exception e
        (log/error e "Error publishing invalidation:" cache-type ":" cache-key)
        false))))

(defn publish-batch-invalidation
  "Publish multiple invalidation messages in a single batch.
   More efficient than calling publish-invalidation multiple times."
  [invalidations]
  ;; invalidations is a vector of [cache-type cache-key] pairs
  (if-not (:enabled @invalidation-config)
    (do (log/trace "Kafka invalidation disabled, skipping batch") nil)
    (try
      (let [producer (:producer @invalidation-state)]
        (if-not producer
          (do (log/warn "Invalidation producer not initialized") nil)
          (do
            (doseq [[cache-type cache-key] invalidations]
              (let [message {:type (name cache-type)
                             :key cache-key
                             :timestamp (System/currentTimeMillis)}
                    message-json (json/write-value-as-string message)
                    record (ProducerRecord. (:topic @invalidation-config)
                                            cache-key
                                            message-json)]
                (.send producer record)))
            (.flush producer)
            (log/debug "Published batch of" (count invalidations) "invalidations")
            true)))
      (catch Exception e
        (log/error e "Error publishing batch invalidations")
        false))))

;; =============================================================================
;; Consumer API
;; =============================================================================

(defn- create-consumer
  "Create Kafka consumer for invalidation messages."
  []
  (let [props (Properties.)
        conf @invalidation-config]
    (doto props
      (.put ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG (:bootstrap-servers conf))
      (.put ConsumerConfig/GROUP_ID_CONFIG (:group-id conf))
      (.put ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG StringDeserializer)
      (.put ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG StringDeserializer)
      (.put ConsumerConfig/AUTO_OFFSET_RESET_CONFIG (:auto-offset-reset conf))
      (.put ConsumerConfig/ENABLE_AUTO_COMMIT_CONFIG true)  ; Auto-commit offsets
      (.put ConsumerConfig/MAX_POLL_RECORDS_CONFIG 100)  ; Batch size
      (.put ConsumerConfig/MAX_POLL_INTERVAL_MS_CONFIG 300000)  ; 5 minutes
      (.put ConsumerConfig/SESSION_TIMEOUT_MS_CONFIG 30000))  ; 30 seconds
    (KafkaConsumer. props)))

(defn- process-invalidation-record
  "Process a single invalidation record from Kafka."
  [^org.apache.kafka.clients.consumer.ConsumerRecord record handler-fn]
  (try
    (let [key (.key record)
          value (.value record)
          message (json/read-value value json/object-mapper)]
      (log/trace "Processing invalidation:" message)
      (handler-fn message))
    (catch Exception e
      (log/error e "Error processing invalidation record:" record))))

(defn- invalidation-consumer-loop
  "Main loop for invalidation consumer."
  [consumer topic handler-fn stop-flag]
  (log/info "Invalidation consumer loop started for topic:" topic)
  (while (not @stop-flag)
    (try
      (let [records (.poll consumer (Duration/ofMillis 1000))]
        (when-not (.isEmpty records)
          (log/debug "Processing" (.count records) "invalidation messages")
          (doseq [record records]
            (process-invalidation-record record handler-fn))))
      (catch Exception e
        (log/error e "Error in invalidation consumer loop")
        (Thread/sleep 1000))))  ; Wait before retrying
  (log/info "Invalidation consumer loop stopped"))

(defn start-invalidation-listener
  "Start the Kafka invalidation listener.
   Returns a stop-fn that can be called to stop the listener.

   Parameters:
   - handler-fn: Function to handle invalidation messages
                  Receives: {:type \"subject\" :key \"user123\" :timestamp ...}

   Example:
   (def stop-fn (start-invalidation-listener
                  (fn [msg] (log/info \"Invalidation:\" msg))))
   (stop-fn)  ; Stop when done"
  [handler-fn]
  (if-not (:enabled @invalidation-config)
    (do (log/info "Kafka invalidation disabled, not starting listener")
        (constantly false))
    (try
      (let [consumer (create-consumer)
            topic (:topic @invalidation-config)
            stop-flag (atom false)
            consumer-thread (future
                               (.subscribe consumer [topic])
                               (invalidation-consumer-loop consumer topic handler-fn stop-flag))]
        (swap! invalidation-state assoc
               :consumer consumer
               :consumer-thread consumer-thread
               :stop-flag stop-flag)
        (log/info "Kafka invalidation listener started for topic:" topic)
        (fn []
          (log/info "Stopping invalidation listener...")
          (reset! stop-flag true)
          (.close consumer)
          (log/info "Invalidation listener stopped")
          true))
      (catch Exception e
        (log/error e "Failed to start invalidation listener")
        (constantly false)))))

(defn start-invalidation-listener!
  "Start the Kafka invalidation listener and store state.
   Convenience function that calls start-invalidation-listener."
  [handler-fn]
  (let [stop-fn (start-invalidation-listener handler-fn)]
    (swap! invalidation-state assoc :stop-fn stop-fn)
    stop-fn))

(defn stop-invalidation-listener
  "Stop the Kafka invalidation listener if running."
  []
  (when-let [stop-fn (:stop-fn @invalidation-state)]
    (stop-fn))
  (swap! invalidation-state assoc
         :consumer nil
         :consumer-thread nil
         :stop-flag nil
         :stop-fn nil)
  (log/info "Invalidation listener stopped and state cleared"))

;; =============================================================================
;; Lifecycle Management
;; =============================================================================

(defn init-invalidation
  "Initialize Kafka invalidation producer.
   Should be called during application startup."
  []
  (when (:enabled @invalidation-config)
    (log/info "Initializing Kafka cache invalidation...")
    (try
      (let [producer (create-producer)]
        (swap! invalidation-state assoc :producer producer)
        (log/info "Kafka invalidation producer initialized"
                  "topic:" (:topic @invalidation-config)))
      (catch Exception e
        (log/error e "Failed to initialize Kafka invalidation producer")
        (log/warn "Cache invalidation will be local only")))))

(defn shutdown-invalidation
  "Shutdown Kafka invalidation producer and consumer.
   Should be called during application shutdown."
  []
  (log/info "Shutting down Kafka cache invalidation...")
  (stop-invalidation-listener)
  (when-let [producer (:producer @invalidation-state)]
    (.close producer)
    (swap! invalidation-state assoc :producer nil))
  (log/info "Kafka cache invalidation shutdown complete"))
