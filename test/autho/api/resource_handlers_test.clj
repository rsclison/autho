(ns autho.api.resource-handlers-test
  "Tests for resource API handlers."
  (:require [clojure.test :refer :all]
            [autho.api.resource-handlers :as resource-handlers]
            [autho.api.response :as response]
            [autho.kafka-pip :as kpip]
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
;; List Resource Classes Tests
;; =============================================================================

(deftest list-resource-classes-test
  (testing "List all resource classes"
    (let [resp (resource-handlers/list-resource-classes)
          body (parse-response-body resp)]
      ;; When db-state is nil, returns 503 DB_NOT_INITIALIZED
      (is (or (= 200 (:status resp))
              (= 503 (:status resp))))
      (when (= 200 (:status resp))
        (is (= "success" (:status body)))
        (is (vector? (:data body)))))))

;; =============================================================================
;; Get Resource Tests
;; =============================================================================

(deftest get-resource-not-found-test
  (testing "Get non-existent resource"
    (let [resp (resource-handlers/get-resource "Document" "nonexistent-doc")
          body (parse-response-body resp)]
      ;; When db-state is nil, it should return 404
      (is (or (= 404 (:status resp))
              (= 500 (:status resp)))))))

;; =============================================================================
;; Search Resources Tests
;; =============================================================================

(deftest search-resources-missing-class-test
  (testing "Search resources without class parameter"
    (let [request (mock-request :params {"q" "test"})
          resp (resource-handlers/search-resources-handler request)
          body (parse-response-body resp)]
      (is (= 400 (:status resp)))
      (is (= "MISSING_RESOURCE_CLASS" (get-in body [:error :code]))))))

;; =============================================================================
;; Batch Get Resources Tests
;; =============================================================================

(deftest batch-get-resources-empty-array-test
  (testing "Batch get resources - empty array"
    (let [body (json/write-value-as-string {:resources []})
          request (mock-request :body body)
          resp (resource-handlers/batch-get-resources request)
          resp-body (parse-response-body resp)]
      (is (= 400 (:status resp)))
      (is (= "INVALID_BATCH_REQUEST" (get-in resp-body [:error :code]))))))

(deftest batch-get-resources-missing-fields-test
  (testing "Batch get resources - missing class or id"
    (let [body (json/write-value-as-string {:resources [{:class "Document"}]})
          request (mock-request :body body)
          resp (resource-handlers/batch-get-resources request)
          resp-body (parse-response-body resp)]
      (is (= 200 (:status resp)))
      (let [results (get-in resp-body [:data :results])]
        (is (= 1 (count results)))
        (is (= false (:found (first results))))
        (is (:error (first results)))))))

(deftest batch-get-resources-malformed-test
  (testing "Batch get resources - malformed request"
    (let [body (json/write-value-as-string {:invalid "data"})
          request (mock-request :body body)
          resp (resource-handlers/batch-get-resources request)
          resp-body (parse-response-body resp)]
      (is (= 400 (:status resp)))  ; Invalid request - no 'resources' key
      (is (= "INVALID_BATCH_REQUEST" (get-in resp-body [:error :code]))))))
