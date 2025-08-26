(ns autho.kafka-pip-test
  (:require [clojure.test :refer :all]
            [autho.kafka-pip :as kafka-pip]
            [jsonista.core :as json]))

(deftest kafka-pip-lifecycle-test
  (testing "Initialization of a Kafka PIP"
    (let [config {:class "TestUser"
                  :type :kafka-pip
                  :kafka-topic "test-topic"
                  :kafka-bootstrap-servers "localhost:9092"
                  :rocksdb-path "/tmp/rocksdb/test-users"}
          open-db-called (atom false)
          create-consumer-called (atom false)
          start-thread-called (atom false)]
      (with-redefs [kafka-pip/open-db (fn [path]
                                        (is (= "/tmp/rocksdb/test-users" path))
                                        (reset! open-db-called true)
                                        :mock-db)
                    kafka-pip/create-kafka-consumer (fn [cfg]
                                                      (is (= config cfg))
                                                      (reset! create-consumer-called true)
                                                      :mock-consumer)
                    kafka-pip/start-consumer-thread (fn [consumer topic db]
                                                      (is (= :mock-consumer consumer))
                                                      (is (= "test-topic" topic))
                                                      (is (= :mock-db db))
                                                      (reset! start-thread-called true)
                                                      {:stop-fn (fn []) :thread (future)})]
        (kafka-pip/init-pip config)
        (is @open-db-called)
        (is @create-consumer-called)
        (is @start-thread-called)
        (is (contains? @kafka-pip/pip-instances "TestUser")))))

  (testing "Querying a Kafka PIP"
    (let [mock-instance {:db :mock-db}
          user-id "user123"
          user-attributes {:name "Alice" :role "admin"}]
      (swap! kafka-pip/pip-instances assoc "TestUser" mock-instance)
      (with-redefs [kafka-pip/db-get (fn [db key]
                                       (is (= :mock-db db))
                                       (is (= user-id key))
                                       (json/write-value-as-string user-attributes))]
        (let [result (kafka-pip/query-pip "TestUser" user-id)]
          (is (= user-attributes result))))))

  (testing "Stopping all Kafka PIPs"
    (let [stop-fn-called (atom false)
          close-db-called (atom false)
          mock-instance {:db :mock-db
                         :consumer-handle {:stop-fn (fn [] (reset! stop-fn-called true))
                                           :thread (future)}}]
      (swap! kafka-pip/pip-instances assoc "TestUser" mock-instance)
      (with-redefs [kafka-pip/close-db (fn [db]
                                         (is (= :mock-db db))
                                         (reset! close-db-called true))]
        (kafka-pip/stop-all-pips)
        (is @stop-fn-called)
        (is @close-db-called)
        (is (empty? @kafka-pip/pip-instances))))))
