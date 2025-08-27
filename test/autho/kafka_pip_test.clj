(ns autho.kafka-pip-test
  (:require [clojure.test :refer :all]
            [autho.kafka-pip :as kafka-pip]
            [jsonista.core :as json])
  (:import (java.io File)))

(defn- setup-test-db [f]
  ;; This fixture creates a temporary shared DB for tests
  (let [db-path (str "/tmp/rocksdb/test-" (System/currentTimeMillis))]
    (.mkdirs (File. db-path))
    (kafka-pip/open-shared-db db-path)
    (f)
    (kafka-pip/close-shared-db)))

(use-fixtures :each setup-test-db)

(deftest kafka-pip-shared-db-lifecycle-test
  (testing "Initialization of a Kafka PIP with a shared DB"
    (let [config {:class "TestUser"
                  :type :kafka-pip
                  :kafka-topic "test-topic"}
          start-thread-called (atom false)]
      (with-redefs [kafka-pip/start-consumer-thread (fn [consumer class-name topic]
                                                      (is (= "TestUser" class-name))
                                                      (is (= "test-topic" topic))
                                                      (reset! start-thread-called true)
                                                      {:stop-fn (fn []) :thread (future)})
                    ;; Mock consumer creation to avoid real connection
                    kafka-pip/create-kafka-consumer (fn [_] :mock-consumer)]
        (kafka-pip/init-pip config)
        (is @start-thread-called)
        ;; After init, the consumer handle should be stored
        (is (= 1 (count @kafka-pip/consumer-handles))))
      ;; Cleanup after test
      (kafka-pip/stop-all-pips)))

  (testing "Querying a Kafka PIP uses a composite key"
    (let [class-name "TestUser"
          object-id "user123"
          composite-key "TestUser:user123"
          user-attributes {:name "Alice" :role "admin"}]
      (with-redefs [kafka-pip/db-get (fn [key]
                                       (is (= composite-key key))
                                       (json/write-value-as-string user-attributes))]
        (let [result (kafka-pip/query-pip class-name object-id)]
          (is (= user-attributes result))))))

  (testing "Stopping all PIPs closes the shared DB"
    (let [close-db-called (atom false)
          stop-fn-called (atom false)
          mock-handle {:stop-fn (fn [] (reset! stop-fn-called true))
                       :thread (future)}]
      ;; Manually set up a consumer handle for the test
      (reset! kafka-pip/consumer-handles [mock-handle])
      (with-redefs [kafka-pip/close-shared-db (fn [] (reset! close-db-called true))]
        (kafka-pip/stop-all-pips)
        (is @stop-fn-called)
        (is @close-db-called)
        (is (empty? @kafka-pip/consumer-handles))))))
