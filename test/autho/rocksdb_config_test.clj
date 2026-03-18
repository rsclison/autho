(ns autho.rocksdb-config-test
  (:require [clojure.test :refer :all]
            [autho.rocksdb-config :as rocksdb])
  (:import (java.io File)))

;; =============================================================================
;; Configuration Tests
;; =============================================================================

(deftest get-optimized-db-options-test
  (testing "Get optimized database options"
    (let [opts (rocksdb/get-optimized-db-options)]
      (is (some? opts))
      (is (instance? org.rocksdb.DBOptions opts)))))

(deftest get-optimized-cf-options-test
  (testing "Get optimized column family options"
    (let [config (rocksdb/get-optimized-cf-options 10 256)]
      (is (map? config))
      (is (contains? config :options))
      (is (contains? config :table-config))
      (is (instance? org.rocksdb.ColumnFamilyOptions (:options config)))
      (is (instance? org.rocksdb.BlockBasedTableConfig (:table-config config))))))

(deftest create-optimized-cf-descriptors-test
  (testing "Create optimized column family descriptors"
    (let [descriptors (rocksdb/create-optimized-cf-descriptors ["Subject" "Resource"])]
      (is (vector? descriptors))
      (is (= 3 (count descriptors)))  ; default + 2 custom
      (doseq [desc descriptors]
        (is (instance? org.rocksdb.ColumnFamilyDescriptor desc))))))

;; =============================================================================
;; Database Open/Close Tests
;; =============================================================================

(deftest open-optimized-db-test
  (testing "Open optimized RocksDB database"
    (let [test-path "/tmp/test-rocksdb-autho"]
      ;; Clean up any existing test database
      (when (.exists (File. test-path))
        (doseq [file (.listFiles (File. test-path))]
          (.delete file))
        (.delete (File. test-path)))

      (try
        (let [db-result (rocksdb/open-optimized-db test-path ["Subject" "Resource" "Policy"])]
          (is (map? db-result))
          (is (contains? db-result :db-instance))
          (is (contains? db-result :cf-handles))
          (is (contains? db-result :options))
          (is (instance? org.rocksdb.RocksDB (:db-instance db-result)))
          (is (map? (:cf-handles db-result)))
          (is (= 4 (count (:cf-handles db-result)))))  ; default + 3 custom

        ;; Clean up
        (finally
          (when (.exists (File. test-path))
            (doseq [file (.listFiles (File. test-path))]
              (.delete file))
            (.delete (File. test-path))))))))

;; =============================================================================
;; Read/Write Options Tests
;; =============================================================================

(deftest get-optimized-read-options-test
  (testing "Get optimized read options"
    (let [opts (rocksdb/get-optimized-read-options)]
      (is (some? opts))
      (is (instance? org.rocksdb.ReadOptions opts)))))

(deftest get-optimized-write-options-test
  (testing "Get optimized write options"
    (let [opts (rocksdb/get-optimized-write-options)]
      (is (some? opts))
      (is (instance? org.rocksdb.WriteOptions opts)))))

;; =============================================================================
;; Performance Stats Tests
;; =============================================================================

(deftest get-performance-stats-no-db-test
  (testing "Get performance stats without database"
    (let [stats (rocksdb/get-performance-stats {})]
      (is (nil? stats)))))
