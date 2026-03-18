(ns autho.api.v1-test
  "Tests for RESTful API v1 endpoints.
   Verifies standardized responses, pagination, and error handling."
  (:require [clojure.test :refer :all]
            [autho.api.v1 :as api-v1]
            [autho.api.response :as response]
            [autho.api.pagination :as pagination]
            [autho.api.handlers :as handlers]
            [autho.pdp :as pdp]
            [autho.prp :as prp]
            [autho.local-cache :as cache]
            [jsonista.core :as json]
            [clojure.string :as str])
  (:import (java.io ByteArrayInputStream)))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn- mock-request
  "Create a mock request map with optional body and params."
  [& {:keys [body params headers]}]
  {:body (when body (ByteArrayInputStream. (.getBytes body "UTF-8")))
   :params (or params {})
   :headers (or headers {})})

(defn- parse-response-body
  "Parse response body JSON string to Clojure map."
  [response]
  (when-let [body (:body response)]
    (json/read-value body json/keyword-keys-object-mapper)))

;; =============================================================================
;; Response Utilities Tests
;; =============================================================================

(deftest success-response-test
  (testing "Success response creation"
    (let [resp (response/success-response {:test "data"})]
      (is (= 200 (:status resp)))
      (is (= "application/json" (get-in resp [:headers "Content-Type"])))
      (is (= "v1" (get-in resp [:headers "X-API-Version"])))
      (let [body (parse-response-body resp)]
        (is (= "success" (:status body)))
        (is (= {:test "data"} (:data body)))
        (is (some? (:timestamp body)))))))

(deftest success-response-with-meta-test
  (testing "Success response with metadata"
    (let [resp (response/success-response {:items []}
                                           200
                                           {:page 1 :per-page 20 :total 100})]
      (let [body (parse-response-body resp)]
        (is (= "success" (:status body)))
        (is (= {:page 1 :per-page 20 :total 100} (:meta body)))))))

(deftest error-response-test
  (testing "Error response creation"
    (let [resp (response/error-response "TEST_ERROR" "Test error message" 400)]
      (is (= 400 (:status resp)))
      (is (= "application/json" (get-in resp [:headers "Content-Type"])))
      (let [body (parse-response-body resp)]
        (is (= "TEST_ERROR" (get-in body [:error :code])))
        (is (= "Test error message" (get-in body [:error :message])))
        (is (some? (get-in body [:error :timestamp])))))))

(deftest paginated-response-test
  (testing "Paginated response creation"
    (let [data [{:id 1} {:id 2}]
          resp (response/paginated-response data 1 20 100)]
      (is (= 200 (:status resp)))
      (let [body (parse-response-body resp)]
        (is (= data (:data body)))
        (is (= {:page 1 :per-page 20 :total 100 :total-pages 5} (:meta body)))
        (is (some? (:links body)))))))

(deftest created-response-test
  (testing "Created response (201)"
    (let [data {:id "new-resource"}
          location "/v1/policies/new-resource"
          resp (response/created-response data location)]
      (is (= 201 (:status resp)))
      (is (= location (get-in resp [:headers "Location"])))
      (let [body (parse-response-body resp)]
        (is (= "created" (:status body)))
        (is (= data (:data body)))))))

(deftest no-content-response-test
  (testing "No content response (204)"
    (let [resp (response/no-content-response)]
      (is (= 204 (:status resp)))
      (is (= "v1" (get-in resp [:headers "X-API-Version"])))
      (is (str/blank? (:body resp))))))

;; =============================================================================
;; Pagination Tests
;; =============================================================================

(deftest parse-pagination-params-default-test
  (testing "Parse pagination params with defaults"
    (let [params {}
          result (pagination/parse-pagination-params params)]
      (is (= 1 (:page result)))
      (is (= 20 (:per-page result)))
      (is (nil? (:sort result)))
      (is (= :asc (:order result))))))

(deftest parse-pagination-params-custom-test
  (testing "Parse pagination params with custom values"
    (let [params {"page" "3"
                  "per-page" "50"
                  "sort" "name"
                  "order" "desc"}
          result (pagination/parse-pagination-params params)]
      (is (= 3 (:page result)))
      (is (= 50 (:per-page result)))
      (is (= "name" (:sort result)))
      (is (= :desc (:order result))))))

(deftest parse-pagination-params-max-limit-test
  (testing "Pagination per-page respects max limit"
    (let [params {"per-page" "200"}
          result (pagination/parse-pagination-params params)]
      (is (= 100 (:per-page result))))))  ; max is 100

(deftest paginate-collection-test
  (testing "Paginate a collection"
    (let [coll (vec (range 1 101))  ; 1 to 100
          page-params {:page 2 :per-page 10}
          result (pagination/paginate coll page-params)]
      (is (= 10 (count (:items result))))
      (is (= 11 (first (:items result))))  ; Starts at 11
      (is (= 20 (last (:items result))))   ; Ends at 20
      (is (= 2 (:page result)))
      (is (= 10 (:per-page result)))
      (is (= 100 (:total result)))
      (is (= 10 (:total-pages result))))))

(deftest paginate-sort-test
  (testing "Paginate with sorting"
    (let [coll [{:id 3 :name "charlie"}
                {:id 1 :name "alpha"}
                {:id 2 :name "bravo"}]
          page-params {:page 1 :per-page 10 :sort "name" :order :asc}
          result (pagination/paginate coll page-params)]
      (is (= [{:id 1 :name "alpha"}
              {:id 2 :name "bravo"}
              {:id 3 :name "charlie"}]
             (:items result))))))

;; =============================================================================
;; Handlers Tests
;; =============================================================================

(deftest parse-json-body-valid-test
  (testing "Parse valid JSON body"
    (let [body (json/write-value-as-string {:test "value"})
          request (mock-request :body body)
          result (handlers/parse-json-body request)]
      (is (= {:test "value"} result)))))

(deftest parse-json-body-invalid-test
  (testing "Parse invalid JSON body returns nil"
    (let [request (mock-request :body "invalid json")
          result (handlers/parse-json-body request)]
      (is (nil? result)))))

(deftest require-body-valid-test
  (testing "Require body with valid JSON"
    (let [body (json/write-value-as-string {:test "value"})
          request (mock-request :body body)
          result (handlers/require-body request)]
      (is (= {:test "value"} result)))))

(deftest require-body-invalid-test
  (testing "Require body with invalid JSON returns error response"
    (let [request (mock-request :body "invalid")
          result (handlers/require-body request)]
      (is (map? result))
      (is (= 400 (:status result)))
      (let [body (parse-response-body result)]
        (is (= "INVALID_REQUEST_BODY" (get-in body [:error :code])))))))

;; =============================================================================
;; Cache Handlers Tests
;; =============================================================================

(deftest get-cache-stats-test
  (testing "Get cache statistics"
    (cache/init-caches)
    (let [resp (handlers/get-cache-stats)]
      (is (= 200 (:status resp)))
      (let [body (parse-response-body resp)]
        (is (contains? body :data))
        (is (contains? (:data body) :hits))
        (is (contains? (:data body) :misses))))))

(deftest clear-cache-test
  (testing "Clear all caches"
    (cache/init-caches)
    ;; Add some data to cache
    (cache/get-or-fetch "test-clear" :subject (fn [] {:id "test-clear"}))

    ;; Clear
    (let [resp (handlers/clear-cache)]
      (is (= 200 (:status resp)))
      (let [body (parse-response-body resp)]
        (is (= "cleared" (get-in body [:data :status])))))))

(deftest invalidate-cache-entry-test
  (testing "Invalidate specific cache entry"
    (cache/init-caches)
    ;; Add data to cache
    (cache/get-or-fetch "test-inv" :subject (fn [] {:id "test-inv"}))

    ;; Invalidate
    (let [resp (handlers/invalidate-cache-entry "subject" "test-inv")]
      (is (= 200 (:status resp)))
      (let [body (parse-response-body resp)]
        (is (= "cleared" (get-in body [:data :status])))
        (is (= "subject" (get-in body [:data :type])))
        (is (= "test-inv" (get-in body [:data :key])))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest api-version-header-test
  (testing "All v1 API responses include version header"
    (let [resp (response/success-response {})]
      (is (= "v1" (get-in resp [:headers "X-API-Version"]))))

    (let [resp (response/error-response "TEST" "test" 400)]
      (is (= "v1" (get-in resp [:headers "X-API-Version"]))))

    (let [resp (response/no-content-response)]
      (is (= "v1" (get-in resp [:headers "X-API-Version"]))))))

(deftest content-type-header-test
  (testing "All v1 API responses include JSON content-type"
    (let [resp (response/success-response {})]
      (is (= "application/json" (get-in resp [:headers "Content-Type"]))))

    (let [resp (response/error-response "TEST" "test" 400)]
      (is (= "application/json" (get-in resp [:headers "Content-Type"]))))))

(deftest error-response-structure-test
  (testing "Error responses have consistent structure"
    (let [resp (response/error-response "VALIDATION_ERROR"
                                        "Invalid input"
                                        400
                                        [{:field "test" :message "required"}])
          body (parse-response-body resp)]
      (is (contains? (:error body) :code))
      (is (contains? (:error body) :message))
      (is (contains? (:error body) :timestamp))
      (is (contains? (:error body) :details))
      (is (= "VALIDATION_ERROR" (get-in body [:error :code])))
      (is (= "Invalid input" (get-in body [:error :message]))))))
