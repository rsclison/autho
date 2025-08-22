(ns client
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]))

(defn- post-request [endpoint body]
  (try
    (let [response (client/post (str "http://localhost:8080/" endpoint)
                                {:body (json/write-str body)
                                 :content-type :json
                                 :accept :json})]
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
    (println "Response:" (which-authorized subject operation))))
