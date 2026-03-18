(ns autho.api.subject-handlers-test
  "Tests for subject API handlers."
  (:require [clojure.test :refer :all]
            [autho.api.subject-handlers :as subject-handlers]
            [autho.api.response :as response]
            [autho.prp :as prp]
            [jsonista.core :as json])
  (:import (java.io ByteArrayInputStream)))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn- mock-request
  "Create a mock request map."
  [& {:keys [body params]}]
  {:body (when body (ByteArrayInputStream. (.getBytes body "UTF-8")))
   :params (or params {})})

(defn- parse-response-body
  "Parse response body JSON to Clojure map."
  [response]
  (when-let [body (:body response)]
    (json/read-value body json/keyword-keys-object-mapper)))

;; =============================================================================
;; Setup/Teardown
;; =============================================================================

(def sample-subjects
  [{:id "user1" :name "Alice" :role "admin" :department "engineering"}
   {:id "user2" :name "Bob" :role "user" :department "sales"}
   {:id "user3" :name "Charlie" :role "user" :department "engineering"}
   {:id "user4" :name "Diana" :role "manager" :department "engineering"}])

(use-fixtures :each
  (fn [f]
    ;; Setup: Reset personSingleton with test data
    (reset! prp/personSingleton sample-subjects)
    (f)
    ;; Teardown
    (reset! prp/personSingleton [])))

;; =============================================================================
;; List Subjects Tests
;; =============================================================================

(deftest list-subjects-default-pagination-test
  (testing "List subjects with default pagination"
    (let [request (mock-request :params {})
          resp (subject-handlers/list-subjects request)
          body (parse-response-body resp)]
      (is (= 200 (:status resp)))
      (is (= "success" (:status body)))
      (is (= 4 (get-in body [:meta :total])))
      (is (= 4 (count (:data body)))))))

(deftest list-subjects-with-pagination-test
  (testing "List subjects with custom pagination"
    (let [request (mock-request :params {"page" "1" "per-page" "2"})
          resp (subject-handlers/list-subjects request)
          body (parse-response-body resp)]
      (is (= 200 (:status resp)))
      (is (= 2 (count (:data body))))
      (is (= 2 (get-in body [:meta :per-page])))
      (is (= 4 (get-in body [:meta :total]))))))

(deftest list-subjects-sorted-test
  (testing "List subjects sorted by name"
    (let [request (mock-request :params {"sort" "name" "order" "asc"})
          resp (subject-handlers/list-subjects request)
          body (parse-response-body resp)]
      (is (= 200 (:status resp)))
      (let [names (map :name (:data body))]
        (is (= ["Alice" "Bob" "Charlie" "Diana"] names))))))

;; =============================================================================
;; Get Subject Tests
;; =============================================================================

(deftest get-subject-found-test
  (testing "Get existing subject by ID"
    (let [resp (subject-handlers/get-subject "user1")
          body (parse-response-body resp)]
      (is (= 200 (:status resp)))
      (is (= "user1" (get-in body [:data :id])))
      (is (= "Alice" (get-in body [:data :name]))))))

(deftest get-subject-not-found-test
  (testing "Get non-existent subject"
    (let [resp (subject-handlers/get-subject "nonexistent")
          body (parse-response-body resp)]
      (is (= 404 (:status resp)))
      (is (= "SUBJECT_NOT_FOUND" (get-in body [:error :code]))))))

;; =============================================================================
;; Search Subjects Tests
;; =============================================================================

(deftest search-subjects-by-role-test
  (testing "Search subjects by role attribute"
    (let [request (mock-request :params {"role" "user"})
          resp (subject-handlers/search-subjects-handler request)
          body (parse-response-body resp)]
      (is (= 200 (:status resp)))
      (is (= 2 (count (:data body))))
      (is (every? #(= "user" (:role %)) (:data body))))))

(deftest search-subjects-by-department-test
  (testing "Search subjects by department attribute"
    (let [request (mock-request :params {"department" "engineering"})
          resp (subject-handlers/search-subjects-handler request)
          body (parse-response-body resp)]
      (is (= 200 (:status resp)))
      (is (= 3 (count (:data body)))))))

(deftest search-subjects-text-search-test
  (testing "Search subjects by text across all attributes"
    (let [request (mock-request :params {"q" "ali"})
          resp (subject-handlers/search-subjects-handler request)
          body (parse-response-body resp)]
      (is (= 200 (:status resp)))
      (is (= 1 (count (:data body))))
      (is (= "Alice" (get-in body [:data 0 :name]))))))

(deftest search-subjects-multiple-attributes-test
  (testing "Search subjects by multiple attributes"
    (let [request (mock-request :params {"role" "user" "department" "engineering"})
          resp (subject-handlers/search-subjects-handler request)
          body (parse-response-body resp)]
      (is (= 200 (:status resp)))
      (is (= 1 (count (:data body))))
      (is (= "Charlie" (get-in body [:data 0 :name]))))))

;; =============================================================================
;; Batch Get Subjects Tests
;; =============================================================================

(deftest batch-get-subjects-all-found-test
  (testing "Batch get subjects - all found"
    (let [body (json/write-value-as-string {:ids ["user1" "user2"]})
          request (mock-request :body body)
          resp (subject-handlers/batch-get-subjects request)
          resp-body (parse-response-body resp)]
      (is (= 200 (:status resp)))
      (is (= 2 (count (get-in resp-body [:data :results]))))
      (is (every? :found (get-in resp-body [:data :results]))))))

(deftest batch-get-subjects-some-not-found-test
  (testing "Batch get subjects - some not found"
    (let [body (json/write-value-as-string {:ids ["user1" "nonexistent"]})
          request (mock-request :body body)
          resp (subject-handlers/batch-get-subjects request)
          resp-body (parse-response-body resp)]
      (is (= 200 (:status resp)))
      (let [results (get-in resp-body [:data :results])]
        (is (= 2 (count results)))
        (is (= true (:found (first results))))
        (is (= false (:found (second results))))))))

(deftest batch-get-subjects-empty-array-test
  (testing "Batch get subjects - empty array"
    (let [body (json/write-value-as-string {:ids []})
          request (mock-request :body body)
          resp (subject-handlers/batch-get-subjects request)
          resp-body (parse-response-body resp)]
      (is (= 400 (:status resp)))
      (is (= "INVALID_BATCH_REQUEST" (get-in resp-body [:error :code]))))))
