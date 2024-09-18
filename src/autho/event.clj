(ns autho.event
  (:require [dvlopt.kafka       :as K]
           [dvlopt.kafka.admin :as K.admin]
           [dvlopt.kafka.in    :as K.in]
           [dvlopt.kafka.out   :as K.out]
            [clojure.data.json :as json]
  ))


(def kafka-nodes [["127.0.0.1" 9092]])


(defn init []
  (with-open [admin (K.admin/admin {:dvlopt.kafka/nodes kafka-nodes })]
    (K.admin/create-topics admin
                           {"prp" {::K.admin/number-of-partitions 1
                                        ::K.admin/replication-factor   1
                                        ::K.admin/configuration        {"cleanup.policy" "compact" }}})
    (println "Existing topics : " (keys @(K.admin/topics admin
                                                         {::K/internal? false}))))
  )


(defn send-policy-event [event-type policy]
  (with-open [producer (K.out/producer {::K/nodes             [["localhost" 9092]]
                                        ::K/serializer.key    (K/serializers :long)
                                        ::K/serializer.value  :string
                                        ::K.out/configuration {"client.id" "prp-producer"}})]
    (K.out/send producer
                {::K/topic "prp"
        ;;         ::K/key   i
                 ::K/value (json/write-str {:event-type event-type :object policy})}
                (fn callback [exception metadata]
                  (println exception)
                  )
                )
    )
  )
