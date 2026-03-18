(ns autho.cache-test
  (:require [clojure.test :refer :all]
            [autho.cache :as cache]))

;; --- Tests pour mergeEntities ---

(deftest mergeEntities-basic-test
  (testing "Basic entity merging"
    (let [ent1 {:id "1" :name "Alice" :age 30}
          ent2 {:id "1" :role "admin"}]
      (is (= {:id "1" :name "Alice" :age 30 :role "admin"}
             (cache/mergeEntities ent1 ent2))))))

(deftest mergeEntities-empty-entities-test
  (testing "Merging with empty entities"
    (is (= {:id "1" :name "Alice"}
           (cache/mergeEntities {:id "1" :name "Alice"} {})))
    (is (= {:id "1" :name "Alice"}
           (cache/mergeEntities {} {:id "1" :name "Alice"})))))

(deftest mergeEntities-overwrite-values-test
  (testing "Second entity values overwrite first"
    (let [ent1 {:id "1" :name "Alice" :age 30}
          ent2 {:id "1" :name "Bob" :role "user"}]
      (is (= {:id "1" :name "Bob" :age 30 :role "user"}
             (cache/mergeEntities ent1 ent2))))))

;; --- Tests pour mergeEntityWithCache ---

(deftest mergeEntityWithCache-new-entity-test
  (testing "Adding new entity to cache"
    (let [test-cache (atom {})
          entity {:id "test-1" :name "Test" :value 100}]
      (cache/mergeEntityWithCache entity test-cache)
      (is (= entity (get @test-cache "test-1"))))))

(deftest mergeEntityWithCache-existing-entity-test
  (testing "Merging with existing cached entity"
    (let [test-cache (atom {"test-1" {:id "test-1" :name "Original" :value 50}})
          entity {:id "test-1" :name "Updated" :new-field true}]
      (cache/mergeEntityWithCache entity test-cache)
      (let [cached (get @test-cache "test-1")]
        (is (= "test-1" (:id cached)))
        (is (= "Updated" (:name cached)))
        (is (= 50 (:value cached))) ; Valeur préservée du cache
        (is (= true (:new-field cached)))))))

(deftest mergeEntityWithCache-no-id-test
  (testing "Entity without ID returns entity unchanged and logs warning"
    (let [test-cache (atom {})
          entity {:name "No-ID" :value 100}]
      (is (= entity (cache/mergeEntityWithCache entity test-cache)))
      (is (empty? @test-cache)))))

(deftest mergeEntityWithCache-concurrent-test
  (testing "Concurrent merges are handled correctly"
    (let [test-cache (atom {})
          entity1 {:id "test-1" :counter 1}
          entity2 {:id "test-1" :counter 2}
          entity3 {:id "test-1" :counter 3}]
      ;; Simuler des mises à jour concurrentes
      (future (cache/mergeEntityWithCache entity1 test-cache))
      (future (cache/mergeEntityWithCache entity2 test-cache))
      (future (cache/mergeEntityWithCache entity3 test-cache))
      (Thread/sleep 100) ;; Attendre que les futures se terminent
      (let [cached (get @test-cache "test-1")]
        (is (= "test-1" (:id cached)))
        (is (number? (:counter cached)))))))

;; --- Tests pour getCachedSubject ---

(deftest getCachedSubject-interface-test
  (testing "getCachedSubject interface exists and returns nil for non-existing key"
    (is (fn? cache/getCachedSubject))
    (is (nil? (cache/getCachedSubject "non-existent-key")))))

;; --- Tests pour getCachedResource ---

(deftest getCachedResource-interface-test
  (testing "getCachedResource interface exists and returns nil for non-existing key"
    (is (fn? cache/getCachedResource))
    (is (nil? (cache/getCachedResource "non-existent-key")))))

;; --- Tests d'intégration ---

(deftest cache-workflow-subject-test
  (testing "Complete workflow: add, retrieve, merge subject"
    (let [test-subject-cache (atom {})]
      ;; Ajouter un sujet
      (cache/mergeEntityWithCache {:id "user-1" :name "Alice"} test-subject-cache)
      ;; Récupérer le sujet
      (is (= {:id "user-1" :name "Alice"} (get @test-subject-cache "user-1")))
      ;; Fusionner avec des données supplémentaires
      (cache/mergeEntityWithCache {:id "user-1" :role "admin" :department "IT"} test-subject-cache)
      ;; Vérifier que les données sont fusionnées
      (let [final (get @test-subject-cache "user-1")]
        (is (= "Alice" (:name final)))
        (is (= "admin" (:role final)))
        (is (= "IT" (:department final)))))))

(deftest cache-workflow-resource-test
  (testing "Complete workflow: add, retrieve, merge resource"
    (let [test-resource-cache (atom {})]
      ;; Ajouter une ressource initiale
      (cache/mergeEntityWithCache {:id "doc-1" :class "Document" :title "Test"} test-resource-cache)
      ;; Récupérer la ressource
      (is (= {:id "doc-1" :class "Document" :title "Test"} (get @test-resource-cache "doc-1")))
      ;; Fusionner avec des mises à jour qui incluent toutes les clés
      (cache/mergeEntityWithCache {:id "doc-1" :class "Document" :title "Updated Title" :version 2} test-resource-cache)
      ;; Vérifier que les données sont fusionnées
      (let [final (get @test-resource-cache "doc-1")]
        (is (= "Updated Title" (:title final)))
        (is (= 2 (:version final)))
        (is (= "Document" (:class final)))))))

;; --- Tests pour les edge cases ---

(deftest mergeEntities-nil-entities-test
  (testing "Merging with nil entities"
    (is (= {:id "1" :name "Alice"} (cache/mergeEntities {:id "1" :name "Alice"} nil)))
    (is (= {:id "1" :name "Alice"} (cache/mergeEntities nil {:id "1" :name "Alice"})))))

(deftest mergeEntityWithCache-nil-entity-test
  (testing "Merging nil entity with cache"
    (let [test-cache (atom {"existing" {:id "existing" :value 1}})]
      (is (nil? (cache/mergeEntityWithCache nil test-cache)))
      (is (= {"existing" {:id "existing" :value 1}} @test-cache)))))

(deftest mergeEntityWithCache-complex-nested-structures-test
  (testing "Merging entities with nested structures"
    (let [test-cache (atom {})
          original {:id "1" :metadata {:created "2024-01-01" :version 1}}
          updated {:id "1" :metadata {:version 2} :new-field "data"}]
      (cache/mergeEntityWithCache original test-cache)
      (cache/mergeEntityWithCache updated test-cache)
      (let [cached (get @test-cache "1")]
        ;; Clojure merge remplace complètement les structures imbriquées
        ;; Donc :created est perdu car :metadata entier est remplacé
        (is (= nil (get-in cached [:metadata :created])))
        (is (= 2 (get-in cached [:metadata :version])))
        (is (= "data" (:new-field cached)))))))