(ns client
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]))

(defn check-authorization [subject resource operation]
  (let [request-body {:subject subject
                      :resource resource
                      :operation operation}]
    (try
      (let [response (client/post "http://localhost:8080/isAuthorized"
                                  {:body (json/write-str request-body)
                                   :content-type :json
                                   :accept :json})]
        (json/read-str (:body response) :key-fn keyword))
      (catch Exception e
        (println (str "Error connecting to the server: " (.getMessage e)))
        nil))))

(defn -main [& args]
  (let [subject {:class "Person" :role "professeur"}
        resource {:class "Diplome"}
        operation "lire"]
    (println "Checking authorization for:")
    (println "  Subject:" subject)
    (println "  Resource:" resource)
    (println "  Operation:" operation)
    (println "Response:" (check-authorization subject resource operation)))

  (let [subject {:class "Person" :role "etudiant"}
        resource {:class "Diplome"}
        operation "lire"]
    (println "\nChecking authorization for:")
    (println "  Subject:" subject)
    (println "  Resource:" resource)
    (println "  Operation:" operation)
    (println "Response:" (check-authorization subject resource operation))))
