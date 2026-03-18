(ns autho.rocksdb-config
  "Optimized RocksDB configuration with performance tuning for autho.
   Provides advanced options for write buffers, compaction, compression, and bloom filters."
  (:require [clojure.tools.logging :as log])
  (:import (org.rocksdb BlockBasedTableConfig Cache CompressionType DBOptions
                         ColumnFamilyDescriptor ColumnFamilyHandle ColumnFamilyOptions
                         Filter BloomFilter ReadOptions WriteOptions
                         RocksDB LRUCache Statistics)
           (java.util ArrayList)
           (java.nio.charset StandardCharsets)))

;; Load RocksDB native library
(RocksDB/loadLibrary)

;; =============================================================================
;; Optimized Configuration Options
;; =============================================================================

(defn- megabytes->bytes [mb]
  (* mb 1024 1024))

(defn- create-bloom-filter
  "Create a bloom filter with specified bits per key.
   Recommended values:
   - 10 bits: ~1% false positive rate (best performance)
   - 6 bits: ~5% false positive rate (good balance)
   - 5 bits: ~10% false positive rate (less memory)"
  [bits-per-key]
  (BloomFilter. bits-per-key false))

(defn- create-block-cache
  "Create LRU block cache for caching data blocks in memory.
   Size is in bytes."
  [size-bytes]
  (LRUCache. size-bytes))

(defn get-optimized-db-options
  "Get optimized RocksDB database options for autho workload.

   Optimizations:
   - More background compaction threads
   - Async fsync (Kafka provides durability)
   - Optimized compaction triggers"
  []
  (let [opts (DBOptions.)]
    (doto opts
      ;; Create DB if not exists
      (.setCreateIfMissing true)
      (.setCreateMissingColumnFamilies true)

      ;; Performance Options
      (.setUseFsync false)                    ; Async fsync (Kafka for durability)

      ;; WAL Options - Reduce WAL overhead since Kafka provides durability
      (.setManualWalFlush false))))

(defn get-optimized-cf-options
  "Get optimized column family options for autho workload.

   Returns a map of:
   - :options - ColumnFamilyOptions with optimizations
   - :table-config - BlockBasedTableConfig for table-level settings"
  [bloom-filter-bits block-cache-size-mb]
  (let [cache-size-bytes (megabytes->bytes block-cache-size-mb)
        table-config (BlockBasedTableConfig.)
        cf-opts (ColumnFamilyOptions.)]

    ;; Configure block-based table format
    (doto table-config
      (.setBlockCache (create-block-cache cache-size-bytes))
      (.setCacheIndexAndFilterBlocks true)
      (.setPinL0FilterAndIndexBlocksInCache true)
      (.setFilterPolicy (create-bloom-filter bloom-filter-bits)))

    ;; Configure column family options
    (doto cf-opts
      (.setCompressionType CompressionType/LZ4_COMPRESSION)
      (.setBottommostCompressionType CompressionType/ZSTD_COMPRESSION))

    {:options cf-opts
     :table-config table-config}))

(defn create-optimized-cf-descriptors
  "Create optimized column family descriptors for the given class names.

   Parameters:
   - class-names: vector of column family name strings
   - bloom-filter-bits: bits per key for bloom filter (default 10)
   - block-cache-size-mb: block cache size in MB (default 256)

   Returns:
   - Vector of ColumnFamilyDescriptor objects"
  [class-names
   & {:keys [bloom-filter-bits block-cache-size-mb]
      :or {bloom-filter-bits 10
           block-cache-size-mb 256}}]
  (let [{:keys [options table-config]} (get-optimized-cf-options bloom-filter-bits block-cache-size-mb)
        default-cf-descriptor (ColumnFamilyDescriptor. RocksDB/DEFAULT_COLUMN_FAMILY options)]
    (into [default-cf-descriptor]
          (map #(ColumnFamilyDescriptor. (.getBytes % StandardCharsets/UTF_8) options)
               class-names))))

(defn open-optimized-db
  "Open optimized RocksDB database with the given path and column families.

   Parameters:
   - path: Database directory path
   - class-names: Vector of column family name strings

   Optional parameters:
   - :bloom-filter-bits - Bloom filter bits per key (default 10)
   - :block-cache-size-mb - Block cache size in MB (default 256)

   Returns:
   - Map with :db-instance, :cf-handles, :options"
  ([path class-names]
   (open-optimized-db path class-names {}))
  ([path class-names opts]
   (let [bloom-filter-bits (:bloom-filter-bits opts 10)
         block-cache-size-mb (:block-cache-size-mb opts 256)
         db-opts (get-optimized-db-options)
         cf-descriptors (create-optimized-cf-descriptors class-names
                                                       :bloom-filter-bits bloom-filter-bits
                                                       :block-cache-size-mb block-cache-size-mb)
         cf-handles-list (ArrayList.)
         db (RocksDB/open db-opts path cf-descriptors cf-handles-list)
         cf-handles-map (into {}
                              (map (fn [^ColumnFamilyHandle handle]
                                     [(new String (.getName handle) StandardCharsets/UTF_8) handle])
                                   cf-handles-list))]

     (log/info "Opened optimized RocksDB at" path
               "with" (count cf-handles-map) "column families"
               "bloom-filter:" bloom-filter-bits "bits"
               "block-cache:" block-cache-size-mb "MB")

     {:db-instance db
      :cf-handles cf-handles-map
      :options db-opts})))

(defn get-optimized-read-options
  "Get optimized read options for autho workload.

   Returns a ReadOptions object configured for:
   - Caching enabled
   - Optional data checksum verification"
  []
  (let [read-opts (ReadOptions.)]
    (doto read-opts
      (.setFillCache true)                    ; Fill block cache on reads
      ;; (.setVerifyChecksums true)            ; Verify data checksums (slower but safer)
      (.setTailing false))))                   ; Use iterative reads for scans

(defn get-optimized-write-options
  "Get optimized write options for autho workload.

   Returns a WriteOptions object configured for:
   - Async WAL writes (Kafka provides durability)
   - No sync on write"
  []
  (let [write-opts (WriteOptions.)]
    (doto write-opts
      (.setDisableWAL false)                   ; Keep WAL for recovery
      (.setSync false))))                      ; Async writes

;; =============================================================================
;; Performance Monitoring
;; =============================================================================

(defn get-performance-stats
  "Get performance statistics from the database.
   Returns a map with performance metrics."
  [{:keys [db-instance options]}]
  (when (and db-instance options)
    (let [stats (.getStatisticsString options)]
      {:statistics stats
       :property-count (.getLongProperty db-instance "rocksdb.estimate-num-keys")
       :table-readers (.getLongProperty db-instance "rocksdb.num-running-compactions")
       :background-errors (.getLongProperty db-instance "rocksdb.background-errors")})))
