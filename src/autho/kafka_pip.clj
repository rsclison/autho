(ns autho.kafka-pip
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as json])
  (:import (org.rocksdb RocksDB Options)
           (org.apache.kafka.clients.consumer KafkaConsumer ConsumerConfig)
           (java.util Properties)
           (java.time Duration)))

;; Atom to hold the single, shared RocksDB instance
(defonce shared-db (atom nil))
;; Atom to hold the handles of the running consumer threads
(defonce consumer-handles (atom []))

(defn- make-key [class-name object-id]
  (str class-name ":" object-id))

(defn open-shared-db [path]
  (log/info "Opening shared RocksDB at" path)
  (let [opts (-> (Options.)
                 (.setCreateIfMissing true))
        db (RocksDB/open opts path)]
    (reset! shared-db db)))

(defn db-get [key]
  (when-let [db @shared-db]
    (when-let [value (.get db (.getBytes key "UTF-8"))]
      (String. value "UTF-8"))))

(defn- db-put [key value]
  (when-let [db @shared-db]
    (.put db (.getBytes key "UTF-8") (.getBytes value "UTF-8"))))

(defn close-shared-db []
  (when-let [db @shared-db]
    (log/info "Closing shared RocksDB")
    (.close db)
    (reset! shared-db nil)))

(defn create-kafka-consumer [{:keys [kafka-bootstrap-servers kafka-topic]}]
  (let [props (Properties.)]
    (doto props
      (.put ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG kafka-bootstrap-servers)
      (.put ConsumerConfig/GROUP_ID_CONFIG (str "autho-pip-" kafka-topic))
      (.put ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringDeserializer")
      (.put ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringDeserializer")
      (.put ConsumerConfig/AUTO_OFFSET_RESET_CONFIG "earliest"))
    (KafkaConsumer. props)))

(defn start-consumer-thread [consumer class-name topic]
  (let [stop-atom (atom false)
        consumer-thread (future
                          (.subscribe consumer [topic])
                          (log/info "Kafka consumer started for topic" topic "and class" class-name)
                          (while (not @stop-atom)
                            (try
                              (let [records (.poll consumer (Duration/ofMillis 1000))]
                                (doseq [record records]
                                  (log/debug "Consumed record for class" class-name ":" (.key record) "=>" (.value record))
                                  (let [composite-key (make-key class-name (.key record))]
                                    (db-put composite-key (.value record)))))
                              (catch Exception e
                                (log/error e "Error in Kafka consumer loop for topic" topic))))
                          (log/info "Kafka consumer stopped for topic" topic)
                          (.close consumer))]
    {:thread consumer-thread
     :stop-fn #(reset! stop-atom true)}))

(defn init-pip [config]
  (let [class-name (:class config)
        consumer (create-kafka-consumer config)
        consumer-handle (start-consumer-thread consumer class-name (:kafka-topic config))]
    (swap! consumer-handles conj consumer-handle)
    (log/info "Initialized Kafka PIP consumer for class" class-name)))

(defn query-pip [class-name object-id]
  (let [composite-key (make-key class-name object-id)]
    (when-let [json-str (db-get composite-key)]
      (json/read-value json-str json/keyword-keys-object-mapper))))

(defn stop-all-pips []
  (doseq [handle @consumer-handles]
    (when-let [stop-fn (:stop-fn handle)]
      (stop-fn)))
  (doseq [handle @consumer-handles]
    (when-let [thread (:thread handle)]
      @thread))
  (reset! consumer-handles [])
  (close-shared-db))
