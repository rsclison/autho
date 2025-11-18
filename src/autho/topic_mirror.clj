(ns autho.topic-mirror
  "Kafka Streams application to mirror compacted topics to retention-based history topics.
   This runs as a separate process and is transparent to producers."
  (:require [clojure.tools.logging :as log]
            [clojure.edn :as edn])
  (:import (org.apache.kafka.streams KafkaStreams StreamsBuilder StreamsConfig)
           (org.apache.kafka.streams.kstream KStream)
           (org.apache.kafka.common.serialization Serdes)
           (java.util Properties)
           (java.time Instant)))

;; Configuration for topic mirroring
(def mirror-config
  "Maps compacted topics to their history equivalents"
  {"invoices-compacted" "invoices-history"
   "contracts-compacted" "contracts-history"
   "legal-commitments-compacted" "legal-commitments-history"})

(defn enrich-with-timestamp
  "Adds processing timestamp to the message for time-travel queries"
  [key value]
  (let [timestamp (str (Instant/now))
        enriched-value (str "{\"_timestamp\":\"" timestamp "\",\"_data\":" value "}")]
    enriched-value))

(defn create-streams-config [bootstrap-servers app-id]
  (doto (Properties.)
    (.put StreamsConfig/APPLICATION_ID_CONFIG app-id)
    (.put StreamsConfig/BOOTSTRAP_SERVERS_CONFIG bootstrap-servers)
    (.put StreamsConfig/DEFAULT_KEY_SERDE_CLASS_CONFIG (.getName (.getClass (Serdes/String))))
    (.put StreamsConfig/DEFAULT_VALUE_SERDE_CLASS_CONFIG (.getName (.getClass (Serdes/String))))))

(defn build-mirror-topology [builder source-topic target-topic]
  "Builds a Kafka Streams topology that mirrors source to target with timestamp enrichment"
  (log/info "Building mirror topology:" source-topic "->" target-topic)
  (let [^KStream source-stream (.stream builder (into-array String [source-topic]))]
    (-> source-stream
        ;; Enrich each message with timestamp
        (.mapValues (reify org.apache.kafka.streams.kstream.ValueMapper
                      (apply [_ value]
                        (enrich-with-timestamp nil value))))
        ;; Send to history topic
        (.to target-topic))))

(defn start-mirror-service
  "Starts the Kafka Streams mirror service for all configured topic pairs"
  [{:keys [bootstrap-servers] :or {bootstrap-servers "localhost:9092"}}]
  (log/info "Starting Topic Mirror Service...")
  (log/info "Bootstrap servers:" bootstrap-servers)
  (log/info "Mirror mappings:" mirror-config)

  (let [builder (StreamsBuilder.)]
    ;; Create topology for each topic pair
    (doseq [[source target] mirror-config]
      (build-mirror-topology builder source target))

    (let [topology (.build builder)
          config (create-streams-config bootstrap-servers "autho-topic-mirror")
          streams (KafkaStreams. topology config)]

      ;; Add shutdown hook
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (log/info "Shutting down Topic Mirror Service...")
                                   (.close streams))))

      ;; Start streaming
      (.start streams)
      (log/info "Topic Mirror Service started successfully")
      (log/info "Topology:" (.describe topology))

      streams)))

(defn stop-mirror-service [^KafkaStreams streams]
  "Stops the mirror service gracefully"
  (when streams
    (log/info "Stopping Topic Mirror Service...")
    (.close streams)
    (log/info "Topic Mirror Service stopped")))

;; For standalone execution
(defn -main [& args]
  (log/info "=== Autho Topic Mirror Service ===")
  (let [bootstrap-servers (or (System/getenv "KAFKA_BOOTSTRAP_SERVERS") "localhost:9092")]
    (start-mirror-service {:bootstrap-servers bootstrap-servers})
    ;; Keep main thread alive
    @(promise)))
