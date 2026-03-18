(ns autho.validation-test
  (:require [clojure.test :refer :all]
            [autho.validation :as v]))

;; --- Tests pour validate-subject ---

(deftest validate-subject-valid-test
  (testing "Valid subject validation"
    (let [subject {:id "user-123" :role "admin" :department "IT"}]
      (is (= subject (v/validate-subject subject))))))

(deftest validate-subject-minimal-test
  (testing "Minimal valid subject"
    (let [subject {:id "user-123"}]
      (is (= subject (v/validate-subject subject))))))

(deftest validate-subject-invalid-missing-id-test
  (testing "Invalid subject without ID"
    (let [subject {:role "admin"}]
      (is (thrown? Exception (v/validate-subject subject))))))

(deftest validate-subject-with-email-test
  (testing "Valid subject with email"
    (let [subject {:id "user-123" :email "user@example.com"}]
      (is (= subject (v/validate-subject subject))))))

;; --- Tests pour validate-resource ---

(deftest validate-resource-valid-test
  (testing "Valid resource validation"
    (let [resource {:class "Document" :id "doc-456" :owner "user-123"}]
      (is (= resource (v/validate-resource resource))))))

(deftest validate-resource-minimal-test
  (testing "Minimal valid resource (class required)"
    (let [resource {:class "Document"}]
      (is (= resource (v/validate-resource resource))))))

(deftest validate-resource-invalid-missing-class-test
  (testing "Invalid resource without class"
    (let [resource {:id "doc-456"}]
      (is (thrown? Exception (v/validate-resource resource))))))

;; --- Tests pour validate-operation ---

(deftest validate-operation-valid-test
  (testing "Valid operation"
    (is (= "read" (v/validate-operation "read")))
    (is (= "write" (v/validate-operation "write")))))

(deftest validate-operation-invalid-blank-test
  (testing "Invalid blank operation"
    (is (thrown? Exception (v/validate-operation "")))))

(deftest validate-operation-invalid-characters-test
  (testing "Invalid operation with special characters"
    (is (thrown? Exception (v/validate-operation "read/delete")))))

;; --- Tests pour validate-authorization-request ---

(deftest validate-authorization-request-valid-test
  (testing "Valid complete authorization request"
    (let [request {:subject {:id "user-123" :role "admin"}
                   :resource {:class "Document" :id "doc-456"}
                   :operation "read"}]
      (is (= request (v/validate-authorization-request request))))))

(deftest validate-authorization-request-missing-subject-test
  (testing "Invalid authorization request without subject"
    (let [request {:resource {:class "Document"} :operation "read"}]
      (is (thrown? Exception (v/validate-authorization-request request))))))

;; --- Tests pour check-for-injection ---

(deftest check-for-injection-sql-test
  (testing "SQL injection detection"
    (is (thrown-with-msg? Exception #"security"
                            (v/check-for-injection "'; DROP TABLE users; --")))))

(deftest check-for-injection-xss-test
  (testing "XSS injection detection"
    (is (thrown-with-msg? Exception #"security"
                            (v/check-for-injection "<script>alert('xss')</script>")))))

(deftest check-for-injection-safe-input-test
  (testing "Safe input passes injection check"
    (is (nil? (v/check-for-injection "normal-request-123")))))

;; --- Tests pour validation-error->response ---

(deftest validation-error->response-test
  (testing "Convert validation error to response"
    (let [error (ex-info "Validation failed" {:type :validation-error :details "Invalid input"})]
      (let [response (v/validation-error->response error)]
        (is (= 400 (:status response)))
        (is (contains? (:headers response) "X-Content-Type-Options"))
        (is (contains? (:headers response) "X-Frame-Options"))))))

;; --- Tests pour validate-policy-rule ---

(deftest validate-policy-rule-valid-test
  (testing "Valid policy rule"
    (let [policy {:name "test-rule"
                  :resourceClass "Document"
                  :operation "read"
                  :effect "allow"}]
      (is (= policy (v/validate-policy-rule policy))))))

(deftest validate-policy-rule-invalid-effect-test
  (testing "Invalid policy effect"
    (let [policy {:name "test-rule"
                  :resourceClass "Document"
                  :operation "read"
                  :effect "invalid"}]
      (is (thrown? Exception (v/validate-policy-rule policy))))))

;; --- Tests d'intégration ---

(deftest complete-validation-flow-test
  (testing "Complete validation flow for authorization request"
    (let [request {:subject {:id "user-123" :role "admin" :email "user@example.com"}
                   :resource {:class "Document" :id "doc-456"}
                   :operation "read"}]
      (is (= request (v/validate-and-sanitize-request request))))))

(deftest validation-with-injection-attempt-test
  (testing "Validation rejects injection attempts"
    (let [request {:subject {:id "'; DROP TABLE users; --"}
                   :resource {:class "Document"}
                   :operation "read"}]
      (is (thrown-with-msg? Exception #"security"
                            (v/validate-and-sanitize-request request))))))
