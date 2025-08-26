(ns autho.kafka-pip
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as json])
  (:import (org.rocksdb RocksDB Options)
           (org.apache.kafka.clients.consumer KafkaConsumer ConsumerConfig)
           (java.util Properties)
           (java.time Duration)))

(defonce pip-instances (atom {}))

(defn open-db [path]
  (log/info "Opening RocksDB at" path)
  (let [opts (-> (Options.)
                 (.setCreateIfMissing true))]
    (RocksDB/open opts path)))

(defn db-get [^RocksDB db key]
  (when-let [value (.get db (.getBytes key "UTF-8"))]
    (String. value "UTF-8")))

(defn- db-put [^RocksDB db key value]
  (.put db (.getBytes key "UTF-8") (.getBytes value "UTF-8")))

(defn close-db [^RocksDB db]
  (when db
    (.close db)))

(defn create-kafka-consumer [{:keys [kafka-bootstrap-servers kafka-topic]}]
  (let [props (Properties.)]
    (doto props
      (.put ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG kafka-bootstrap-servers)
      (.put ConsumerConfig/GROUP_ID_CONFIG (str "autho-pip-" kafka-topic))
      (.put ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringDeserializer")
      (.put ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringDeserializer")
      (.put ConsumerConfig/AUTO_OFFSET_RESET_CONFIG "earliest"))
    (KafkaConsumer. props)))

(defn start-consumer-thread [consumer topic db]
  (let [stop-atom (atom false)
        consumer-thread (future
                          (.subscribe consumer [topic])
                          (log/info "Kafka consumer started for topic" topic)
                          (while (not @stop-atom)
                            (try
                              (let [records (.poll consumer (Duration/ofMillis 1000))]
                                (doseq [record records]
                                  (log/debug "Consumed record:" (.key record) "=>" (.value record))
                                  (db-put db (.key record) (.value record))))
                              (catch Exception e
                                (log/error e "Error in Kafka consumer loop for topic" topic))))
                          (log/info "Kafka consumer stopped for topic" topic)
                          (.close consumer))]
    {:thread consumer-thread
     :stop-fn #(reset! stop-atom true)}))

(defn init-pip [config]
  (let [class-name (:class config)
        db-path (:rocksdb-path config)
        db (open-db db-path)
        consumer (create-kafka-consumer config)
        consumer-handle (start-consumer-thread consumer (:kafka-topic config) db)]
    (swap! pip-instances assoc class-name
           {:db db
            :consumer-handle consumer-handle
            :config config})
    (log/info "Initialized Kafka PIP for class" class-name)))

(defn query-pip [class-name object-id]
  (when-let [instance (get @pip-instances class-name)]
    (when-let [json-str (db-get (:db instance) object-id)]
      (json/read-value json-str json/keyword-keys-object-mapper))))

(defn stop-all-pips []
  (doseq [[class-name instance] @pip-instances]
    (log/info "Stopping Kafka PIP for class" class-name)
    (when-let [stop-fn (get-in instance [:consumer-handle :stop-fn])]
      (stop-fn))
    (when-let [thread (get-in instance [:consumer-handle :thread])]
      @thread)
    (close-db (:db instance)))
  (reset! pip-instances {}))
