(ns autho.topic-mirror-test
  (:require [clojure.test :refer :all]
            [autho.topic-mirror :as topic-mirror]
            [jsonista.core :as json])
  (:import (org.apache.kafka.streams StreamsBuilder)))

(deftest mirror-topology-builds-test
  (let [builder (StreamsBuilder.)]
    (topic-mirror/build-mirror-topology builder "business-objects-compacted" "business-objects-history")
    (let [description (str (.describe (.build builder)))]
      (is (re-find #"business-objects-compacted" description))
      (is (re-find #"business-objects-history" description)))))

(deftest enrich-with-timestamp-wraps-value-test
  (let [wrapped (json/read-value (topic-mirror/enrich-with-timestamp "k" "{\"class\":\"Facture\",\"id\":\"F1\"}")
                                 json/keyword-keys-object-mapper)]
    (is (string? (:_timestamp wrapped)))
    (is (= {:class "Facture" :id "F1"} (:_data wrapped)))))
