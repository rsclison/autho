(ns client
  (:require [clj-http.client :as client]
            [clojure.data.json :as json])
  (:import (org.apache.kafka.clients.producer KafkaProducer ProducerRecord)
           (java.util Properties)))

(defn- post-request [endpoint body]
  (try
    (let [response (client/post (str "http://localhost:8080/" endpoint)
                                {:body (json/write-str body)
                                 :content-type :json
                                 :accept :json
                                 :headers {"X-API-Key" "trusted-app-secret"}})]
      (json/read-str (:body response) :key-fn keyword))
    (catch Exception e
      (println (str "Error connecting to the server: " (.getMessage e)))
      nil)))

(defn is-authorized [subject resource operation]
  (post-request "isAuthorized" {:subject subject
                                :resource resource
                                :operation operation}))

(defn who-authorized [resource operation]
  (post-request "whoAuthorized" {:resource resource
                                 :operation operation}))

(defn which-authorized [subject operation]
  (post-request "whichAuthorized" {:subject subject
                                   :operation operation}))

(defn publish-to-kafka [topic key value]
  ;; This example requires the kafka-clients library on the classpath.
  ;; e.g., [org.apache.kafka/kafka-clients "4.0.0"]
  (println "\n=== Kafka Producer Example ===")
  (println "Publishing to topic" topic)
  (println "  Key:" key)
  (println "  Value:" value)
  (let [props (doto (Properties.)
                (.put "bootstrap.servers" "localhost:9092")
                (.put "key.serializer" "org.apache.kafka.common.serialization.StringSerializer")
                (.put "value.serializer" "org.apache.kafka.common.serialization.StringSerializer"))
        producer (KafkaProducer. props)
        record (ProducerRecord. topic key (json/write-str value))]
    (try
      (.send producer record)
      (.flush producer)
      (println "Message sent successfully.")
      (catch Exception e
        (println (str "Error publishing to Kafka: " (.getMessage e)))))
    (.close producer)))

(defn -main [& args]
  (println "=== isAuthorized ===")
  (let [subject {:class "Person" :role "professeur"}
        resource {:class "Diplome"}
        operation "lire"]
    (println "Checking authorization for:")
    (println "  Subject:" subject)
    (println "  Resource:" resource)
    (println "  Operation:" operation)
    (println "Response:" (is-authorized subject resource operation)))

  (let [subject {:class "Person" :role "etudiant"}
        resource {:class "Diplome"}
        operation "lire"]
    (println "\nChecking authorization for:")
    (println "  Subject:" subject)
    (println "  Resource:" resource)
    (println "  Operation:" operation)
    (println "Response:" (is-authorized subject resource operation)))

  (println "\n=== whoAuthorized ===")
  (let [resource {:class "Diplome"}
        operation "lire"]
    (println "Finding who is authorized for:")
    (println "  Resource:" resource)
    (println "  Operation:" operation)
    (println "Response:" (who-authorized resource operation)))

  (println "\n=== whichAuthorized ===")
  (let [subject {:class "Person" :role "professeur"}
        operation "lire"]
    (println "Finding which resources are authorized for:")
    (println "  Subject:" subject)
    (println "  Operation:" operation)
    (println "Response:" (which-authorized subject operation)))

  (publish-to-kafka "user-attributes-compacted"
                    "user789"
                    {:name "Charles" :role "manager" :team "product"}))
