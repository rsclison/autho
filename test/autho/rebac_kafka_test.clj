(ns autho.rebac-kafka-test
  (:require [clojure.test :refer :all]
            [autho.rebac-kafka :as sut]
            [autho.rebac :as rebac]
            [autho.metrics :as metrics]))

(use-fixtures :each
  (fn [f]
    (rebac/clear-relations!) (sut/clear-quarantine!) (f)
    (rebac/clear-relations!) (sut/clear-quarantine!)))

(deftest invalid-events-are-quarantined
  (is (= :quarantined (:status (sut/process-event-value! "{not-json"))))
  (let [entry (first (sut/quarantined))]
    (is (= 1 (count (sut/quarantined))))
    (is (= :quarantined (:status (sut/replay-quarantined! (:id entry)))))
    (is (= :not-found (:status (sut/replay-quarantined! "missing"))))))

(deftest valid-events-use-the-rebac-ingestion-core
  (let [event-id (str "kafka-event-" (java.util.UUID/randomUUID))
        group-id (str "finance-" (java.util.UUID/randomUUID))
        result (sut/process-event-value!
                (str "{\"eventId\":\"" event-id "\",\"eventType\":\"authorization.relationship.upserted\",\"tenantId\":\"tenant-a\",\"source\":\"iam\",\"version\":1,\"tuple\":{\"subject\":{\"class\":\"Person\",\"id\":\"alice\"},\"relation\":\"member\",\"resource\":{\"class\":\"Group\",\"id\":\"" group-id "\"}}}"))]
    (is (= :applied (:status result)))
    (is (true? (rebac/with-tenant "tenant-a"
                 (rebac/has-relation? {:class "Person" :id "alice"}
                                      "member" {:class "Group" :id group-id}))))))

(deftest projection-events-are-exported-as-prometheus-metrics
  (sut/process-event-value! "{not-json")
  (is (re-find #"autho_rebac_projection_events_total.*status=\"quarantined\""
               (metrics/scrape)))
  (is (re-find #"autho_rebac_quarantine_events_total.*operation=\"created\""
               (metrics/scrape))))

(deftest processed-events-update-the-projection-freshness-timestamp
  (sut/process-event-value!
   (str "{\"eventId\":\"kafka-freshness-" (java.util.UUID/randomUUID) "\",\"eventType\":\"authorization.relationship.upserted\",\"tenantId\":\"tenant-a\",\"source\":\"iam\",\"version\":1,\"tuple\":{\"subject\":{\"class\":\"Person\",\"id\":\"alice\"},\"relation\":\"member\",\"resource\":{\"class\":\"Group\",\"id\":\"finance\"}}}"))
  (is (pos? (.get metrics/rebac-last-event-ms)))
  (is (re-find #"autho_rebac_projection_last_event_timestamp_seconds"
               (metrics/scrape))))

(deftest kafka-lag-is-exported-per-topic-partition
  (metrics/record-rebac-kafka-lag! "authorization-relationships" 2 17)
  (is (re-find #"autho_rebac_kafka_consumer_lag_records.*partition=\"2\".*topic=\"authorization-relationships\""
               (metrics/scrape))))
