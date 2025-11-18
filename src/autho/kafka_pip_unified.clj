(ns autho.kafka-pip-unified
  "Simplified Kafka PIP with a single unified topic for all business objects.
   Messages are routed to appropriate RocksDB Column Families based on 'class' field."
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as json])
  (:import (org.rocksdb RocksDB Options DBOptions ColumnFamilyDescriptor ColumnFamilyHandle ColumnFamilyOptions)
           (org.apache.kafka.clients.consumer KafkaConsumer ConsumerConfig)
           (java.util Properties ArrayList List)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

;; Load RocksDB native library
(RocksDB/loadLibrary)

;; State Management for the Shared DB and Consumer
(defonce db-state (atom nil)) ; Will hold {:db-instance ... :cf-handles {...}}
(defonce consumer-handle (atom nil))

(defn- get-cf-handle [class-name]
  (get-in @db-state [:cf-handles class-name]))

;; --- RocksDB Core Functions with Column Families ---

(defn open-shared-db [path class-names]
  (log/info "Opening shared RocksDB at" path "for classes:" class-names)
  (let [db-opts (-> (DBOptions.) (.setCreateIfMissing true) (.setCreateMissingColumnFamilies true))
        cf-opts (ColumnFamilyOptions.)
        default-cf-descriptor (ColumnFamilyDescriptor. RocksDB/DEFAULT_COLUMN_FAMILY cf-opts)
        cf-descriptors (into [default-cf-descriptor]
                             (map #(ColumnFamilyDescriptor. (.getBytes % StandardCharsets/UTF_8) (ColumnFamilyOptions.)) class-names))
        cf-handles-list (ArrayList.)
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

;; --- Unified Kafka Consumer Logic ---

(defn create-kafka-consumer [{:keys [kafka-bootstrap-servers kafka-topic group-id]}]
  (let [props (Properties.)]
    (doto props
      (.put ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG kafka-bootstrap-servers)
      (.put ConsumerConfig/GROUP_ID_CONFIG (or group-id "autho-pip-unified"))
      (.put ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringDeserializer")
      (.put ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG "org.apache.kafka.common.serialization.StringDeserializer")
      (.put ConsumerConfig/AUTO_OFFSET_RESET_CONFIG "earliest"))
    (KafkaConsumer. props)))

(def object-mapper (json/object-mapper {:decode-key-fn true}))

(defn- extract-class-and-id
  "Extracts the class name and object ID from a message.
   Expected format: {\"class\": \"Facture\", \"id\": \"INV-123\", ...}
   or uses key as ID if no 'id' field in message."
  [key value-str]
  (try
    (let [value-map (json/read-value value-str object-mapper)
          class-name (or (:class value-map) (get value-map "class"))
          object-id (or (:id value-map) (get value-map "id") key)]
      [class-name object-id value-map])
    (catch Exception e
      (log/error e "Failed to extract class and id from message with key" key)
      [nil nil nil])))

(defn process-unified-record
  "Processes a record from the unified topic, routing to appropriate Column Family"
  [record]
  (let [key (.key record)
        value-str (.value record)
        [class-name object-id value-map] (extract-class-and-id key value-str)]

    (if (and class-name object-id)
      (if-let [cf-handle (get-cf-handle class-name)]
        (try
          (log/debug "Processing" class-name "object" object-id)
          (let [existing-value-str (db-get cf-handle object-id)
                existing-attrs (if (and existing-value-str (not-empty existing-value-str))
                                 (json/read-value existing-value-str object-mapper)
                                 {})
                ;; Remove metadata fields before merging
                clean-value-map (dissoc value-map :class "class" :id "id")
                merged-attrs (merge existing-attrs clean-value-map)
                merged-value-str (json/write-value-as-string merged-attrs)]
            (db-put cf-handle object-id merged-value-str)
            (log/debug "Stored" class-name object-id "in RocksDB"))
          (catch Exception e
            (log/error e "Failed to process record for" class-name object-id)))
        (log/warn "No Column Family found for class:" class-name "- ignoring message"))
      (log/warn "Invalid message: missing class or id field. Key:" key))))

(defn start-unified-consumer
  "Starts a single consumer for the unified business objects topic"
  [{:keys [kafka-bootstrap-servers kafka-topic]
    :or {kafka-topic "business-objects-compacted"
         kafka-bootstrap-servers "localhost:9092"}}]
  (let [stop-atom (atom false)
        consumer (create-kafka-consumer {:kafka-bootstrap-servers kafka-bootstrap-servers
                                        :kafka-topic kafka-topic
                                        :group-id "autho-pip-unified"})
        consumer-thread (future
                          (.subscribe consumer [kafka-topic])
                          (log/info "Unified Kafka consumer started for topic:" kafka-topic)
                          (while (not @stop-atom)
                            (try
                              (let [records (.poll consumer (Duration/ofMillis 1000))]
                                (doseq [record records]
                                  (process-unified-record record)))
                              (catch Exception e
                                (log/error e "Error in unified Kafka consumer loop"))))
                          (log/info "Unified Kafka consumer stopped")
                          (.close consumer))]

    (reset! consumer-handle {:thread consumer-thread
                            :stop-fn #(reset! stop-atom true)
                            :consumer consumer})
    (log/info "Unified consumer initialized successfully")))

;; --- Admin Functions ---

(defn list-column-families []
  (when-let [cf-handles (:cf-handles @db-state)]
    (->> (keys cf-handles)
         (filter #(not= % "default"))
         (into []))))

(defn clear-column-family [class-name]
  (log/info "Clearing column family:" class-name)
  (if-let [db (:db-instance @db-state)]
    (if-let [cf-handle (get-cf-handle class-name)]
      (with-open [iter (.newIterator db cf-handle)]
        (.seekToFirst iter)
        (while (.isValid iter)
          (let [key-bytes (.key iter)]
            (.delete db cf-handle key-bytes))
          (.next iter)))
      (log/warn "Attempted to clear non-existent column family:" class-name))
    (log/error "RocksDB not initialized, cannot clear column family.")))

;; --- Public PIP Interface ---

(defn init-unified-pip
  "Initializes the unified PIP with a single topic for all business objects.
   Config should specify: {:kafka-bootstrap-servers ... :kafka-topic ...}
   Class names are extracted from message content."
  [config]
  (log/info "Initializing unified Kafka PIP with config:" config)
  (start-unified-consumer config))

(defn query-pip [class-name object-id]
  (if-let [cf-handle (get-cf-handle class-name)]
    (when-let [json-str (db-get cf-handle object-id)]
      (json/read-value json-str json/keyword-keys-object-mapper))
    (do
      (log/warn "Query for unknown class or uninitialized PIP:" class-name)
      nil)))

(defn stop-unified-pip []
  (when-let [handle @consumer-handle]
    (when-let [stop-fn (:stop-fn handle)]
      (log/info "Stopping unified consumer...")
      (stop-fn))
    (when-let [thread (:thread handle)]
      @thread)
    (reset! consumer-handle nil))
  (close-shared-db))
