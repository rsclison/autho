(ns autho.time-travel-kafka-integration-test
  "Replays a real Kafka history topic for time-travel queries.

   Run with a broker reachable at TIME_TRAVEL_TEST_KAFKA (default localhost:9092):
     TIME_TRAVEL_TEST_KAFKA=localhost:9092 lein test :integration :only autho.time-travel-kafka-integration-test"
  (:require [clojure.test :refer :all]
            [autho.time-travel :as time-travel]
            [autho.policy-versions :as pv]
            [autho.prp :as prp]
            [jsonista.core :as json])
  (:import (org.apache.kafka.clients.producer KafkaProducer ProducerRecord)
           (java.util Properties)))

(def ^:private bootstrap (or (System/getenv "TIME_TRAVEL_TEST_KAFKA") "localhost:9092"))

(def ^:private policy
  {:resourceClass "Facture"
   :strategy :almost_one_allow_no_deny
   :rules [{:name "R-approve" :effect "allow" :priority 1 :operation "approve"
            :conditions [["=" ["Person" "$s" "role"] "manager"]
                         [">" ["Facture" "$r" "amount"] "1000"]]}]})

(defn- produce! [topic events]
  (let [props (doto (Properties.)
                (.put "bootstrap.servers" bootstrap)
                (.put "key.serializer" "org.apache.kafka.common.serialization.StringSerializer")
                (.put "value.serializer" "org.apache.kafka.common.serialization.StringSerializer"))]
    (with-open [producer (KafkaProducer. props)]
      (doseq [[ts data] events]
        ;; same envelope as autho.topic-mirror/enrich-with-timestamp
        @(.send producer (ProducerRecord. topic (str (:id data))
                                          (json/write-value-as-string {:_timestamp ts :_data data}))))
      (.flush producer))))

(deftest ^:integration time-travel-replays-kafka-history-test
  (let [topic (str "tt-history-" (System/currentTimeMillis))
        config {:bootstrap-servers bootstrap :history-topic topic}]
    (produce! topic [["2026-03-01T00:00:00Z" {:class "Person" :id "alice" :role "clerk"}]
                     ["2026-03-01T00:00:00Z" {:class "Person" :id "bob" :role "clerk"}]
                     ["2026-03-01T00:00:00Z" {:class "Facture" :id "INV-1" :amount 500}]
                     ["2026-03-10T00:00:00Z" {:class "Person" :id "alice" :role "manager"}]
                     ["2026-03-10T00:00:00Z" {:class "Facture" :id "INV-1" :amount 5000}]
                     ["2026-03-20T00:00:00Z" {:class "Person" :id "alice" :role "clerk"}]])
    (with-redefs [pv/list-versions (fn [_] [])
                  prp/getGlobalPolicy (fn [& _] policy)]
      (let [decide (fn [at]
                     (:decision (time-travel/is-authorized-at-time
                                 {:subject {:id "alice"} :resource {:class "Facture" :id "INV-1"}
                                  :operation "approve"}
                                 at config)))]
        (is (= "deny" (decide "2026-03-05T00:00:00Z")) "alice was a clerk and the invoice was small")
        (is (= "allow" (decide "2026-03-15T00:00:00Z")) "alice was a manager and the invoice was large")
        (is (= "deny" (decide "2026-03-25T00:00:00Z")) "alice had been demoted"))
      (is (= [{:class "Person" :id "alice" :matchedRules ["R-approve"]}]
             (:authorized-subjects
              (time-travel/who-was-authorized-at "Facture" "INV-1" "approve" "2026-03-15T00:00:00Z" config))))
      (is (= ["INV-1"]
             (map :id (:accessible-resources
                       (time-travel/what-could-access-at "alice" "approve" "2026-03-15T00:00:00Z" config))))))))

(deftest ^:integration missing-history-topic-is-reported-test
  (let [e (try (time-travel/build-historical-snapshot "2026-03-15T00:00:00Z"
                                                      {:bootstrap-servers bootstrap
                                                       :history-topic (str "missing-" (System/currentTimeMillis))})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= 503 (:status (ex-data e))))
    (is (= "HISTORY_TOPIC_NOT_FOUND" (:error-code (ex-data e))))))
