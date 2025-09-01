(ns autho.kafka-pip
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as json])
  (:import (org.rocksdb RocksDB Options ColumnFamilyDescriptor ColumnFamilyHandle)
           (org.apache.kafka.clients.consumer KafkaConsumer ConsumerConfig)
           (java.util Properties ArrayList List)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

;; State Management for the Shared DB and Consumers
(defonce db-state (atom nil)) ; Will hold {:db-instance ... :cf-handles {...}}
(defonce consumer-handles (atom []))

(defn- get-cf-handle [class-name]
  (get-in @db-state [:cf-handles class-name]))

;; --- RocksDB Core Functions with Column Families ---

(defn open-shared-db [path class-names]
  (log/info "Opening shared RocksDB at" path "for classes:" class-names)
  (let [default-cf-descriptor (ColumnFamilyDescriptor. RocksDB/DEFAULT_COLUMN_FAMILY)
        cf-descriptors (into [default-cf-descriptor]
                             (map #(ColumnFamilyDescriptor. (.getBytes % StandardCharsets/UTF_8)) class-names))
        cf-handles-list (ArrayList.)
        db-opts (-> (Options.) (.setCreateIfMissing true) (.setCreateMissingColumnFamilies true))
        db (RocksDB/open db-opts path cf-descriptors cf-handles-list)]

    (let [cf-handles-map (into {}
                               (map (fn [^ColumnFamilyHandle handle]
                                      [(new String (.getName handle) StandardCharsets/UTF_8) handle])
                                    cf-handles-list))]
      (reset! db-state {:db-instance db :cf-handles cf-handles-map})
      (log/info "Successfully opened shared RocksDB with" (count cf-handles-map) "column families."))))

(defn db-get [^ColumnFamilyHandle cf-handle key]
  (when-let [db (:db-instance @db-state)]
    (when-let [value (.get db cf-handle (.getBytes key "UTF-8"))]
      (String. value "UTF-8"))))

(defn- db-put [^ColumnFamilyHandle cf-handle key value]
  (when-let [db (:db-instance @db-state)]
    (.put db cf-handle (.getBytes key "UTF-8") (.getBytes value "UTF-8"))))

(defn close-shared-db []
  (when-let [{:keys [db-instance cf-handles]} @db-state]
    (log/info "Closing shared RocksDB...")
    (doseq [[_ handle] cf-handles]
      (.close handle))
    (.close db-instance)
    (reset! db-state nil)
    (log/info "Shared RocksDB closed.")))

;; --- Kafka Consumer Logic ---

(defn create-kafka-consumer [{:keys [kafka-bootstrap-servers kafka-topic]}]
  (let [props (Properties.)]
    (doto props
      (.put ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG kafka-bootstrap-servers)
      (.put ConsumerConfig/GROUP_ID_CONFIG (str "autho-pip-" kafka-topic))
      (.put ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringDeserializer")
      (.put ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringDeserializer")
      (.put ConsumerConfig/AUTO_OFFSET_RESET_CONFIG "earliest"))
    (KafkaConsumer. props)))

(defn start-consumer-thread [consumer topic cf-handle]
  (let [stop-atom (atom false)
        consumer-thread (future
                          (.subscribe consumer [topic])
                          (log/info "Kafka consumer started for topic" topic)
                          (while (not @stop-atom)
                            (try
                              (let [records (.poll consumer (Duration/ofMillis 1000))]
                                (doseq [record records]
                                  (log/debug "Consumed record for topic" topic ":" (.key record) "=>" (.value record))
                                  (db-put cf-handle (.key record) (.value record))))
                              (catch Exception e
                                (log/error e "Error in Kafka consumer loop for topic" topic))))
                          (log/info "Kafka consumer stopped for topic" topic)
                          (.close consumer))]
    {:thread consumer-thread
     :stop-fn #(reset! stop-atom true)}))

;; --- Public PIP Interface ---

(defn init-pip [config]
  (let [class-name (:class config)]
    (if-let [cf-handle (get-cf-handle class-name)]
      (let [consumer (create-kafka-consumer config)
            consumer-handle (start-consumer-thread consumer (:kafka-topic config) cf-handle)]
        (swap! consumer-handles conj consumer-handle)
        (log/info "Initialized Kafka PIP consumer for class" class-name))
      (log/error "Could not initialize Kafka PIP for class" class-name " - Column Family handle not found."))))

(defn query-pip [class-name object-id]
  (if-let [cf-handle (get-cf-handle class-name)]
    (when-let [json-str (db-get cf-handle object-id)]
      (json/read-value json-str json/keyword-keys-object-mapper))
    (do
      (log/warn "Query for unknown class or uninitialized PIP:" class-name)
      nil)))

(defn stop-all-pips []
  (doseq [handle @consumer-handles]
    (when-let [stop-fn (:stop-fn handle)]
      (stop-fn)))
  (doseq [handle @consumer-handles]
    (when-let [thread (:thread handle)]
      @thread))
  (reset! consumer-handles [])
  (close-shared-db))
