(ns autho.auth-test
  (:require [clojure.test :refer :all]
            [buddy.auth.backends :as backends]
            [buddy.auth.middleware :as middleware]))

;; --- Tests for Backend Creation ---

(deftest jws-backend-creation-test
  (testing "JWS backend can be created with a secret"
    (let [backend (backends/jws {:secret "test-secret"})]
      (is (some? backend)))))

(deftest token-backend-creation-test
  (testing "Token backend can be created with authfn and token-name"
    (let [authfn (fn [req token]
                   (when (= token "valid-key")
                     {:auth-method :api-key :user "test"}))
          backend (backends/token {:authfn authfn :token-name "X-API-Key"})]
      (is (some? backend)))))

;; --- Tests for Authentication Function ---

(deftest authfn-accepts-valid-token-test
  (testing "Custom authfn accepts valid token"
    (let [authfn (fn [req token]
                   (when (= token "valid-key")
                     {:authenticated true}))
          result (authfn {} "valid-key")]
      (is (some? result))
      (is (= true (:authenticated result))))))

(deftest authfn-rejects-invalid-token-test
  (testing "Custom authfn rejects invalid token"
    (let [authfn (fn [req token]
                   (when (= token "valid-key")
                     {:authenticated true}))
          result (authfn {} "invalid-key")]
      (is (nil? result)))))

;; --- Tests for Authentication Middleware ---

(deftest wrap-authentication-creates-middleware-test
  (testing "wrap-authentication creates a middleware function"
    (let [backend (backends/token
                    {:authfn (fn [req token]
                               (when (= token "test")
                                 {:authenticated true}))
                     :token-name "x-api-key"})
          handler (fn [req] {:status 200})
          wrapped (middleware/wrap-authentication handler backend)]
      (is (fn? wrapped))
      (is (some? (wrapped {:headers {} :request-method :get}))))))

;; --- Tests for Token Name ---

(deftest token-backend-uses-correct-header-name-test
  (testing "Token backend can use different header names"
    (let [authfn (fn [req token] {:user "test"})]
      ;; Test with X-API-Key
      (let [backend1 (backends/token {:authfn authfn :token-name "X-API-Key"})]
        (is (some? backend1)))
      ;; Test with Authorization
      (let [backend2 (backends/token {:authfn authfn :token-name "Authorization"})]
        (is (some? backend2)))
      ;; Test with custom header
      (let [backend3 (backends/token {:authfn authfn :token-name "X-Custom-Auth"})]
        (is (some? backend3))))))

;; --- Tests for Identity Structure ---

(deftest authfn-returns-correct-identity-structure-test
  (testing "Authfn returns proper identity structure"
    (let [authfn (fn [req token]
                   (when (= token "test")
                     {:auth-method :api-key
                      :client-id :trusted-app
                      :user-id "user123"}))
          result (authfn {} "test")]
      (is (= :api-key (:auth-method result)))
      (is (= :trusted-app (:client-id result)))
      (is (= "user123" (:user-id result))))))

;; --- Tests for Case Sensitivity ---

(deftest token-comparison-is-case-sensitive-test
  (testing "Token comparison is case-sensitive"
    (let [authfn (fn [req token]
                   (when (= token "SecretKey")
                     {:authenticated true}))]
      (is (some? (authfn {} "SecretKey")))
      (is (nil? (authfn {} "secretkey")))
      (is (nil? (authfn {} "SECRETKEY"))))))

;; --- Tests for Middleware Behavior ---

(deftest middleware-passes-request-to-handler-test
  (testing "Middleware passes request through to handler"
    (let [received-request (atom nil)
          handler (fn [req]
                    (reset! received-request req)
                    {:status 200})
          backend (backends/token
                    {:authfn (fn [req token] nil)
                     :token-name "x-api-key"})
          wrapped (middleware/wrap-authentication handler backend)
          request {:headers {} :request-method :get :uri "/test"}
          response (wrapped request)]
      (is (= 200 (:status response)))
      (is (some? @received-request))
      (is (= "/test" (:uri @received-request))))))

;; --- Tests for Multiple Backends ---

(deftest multiple-backends-can-be-created-test
  (testing "Multiple backends of different types can be created"
    (let [jws-backend (backends/jws {:secret "secret1"})
          token-backend (backends/token
                           {:authfn (fn [req token] {:user "test"})
                            :token-name "X-API-Key"})]
      (is (some? jws-backend))
      (is (some? token-backend))
      ;; They should be different objects
      (is (not (= jws-backend token-backend))))))

;; --- Integration Test with wrap-authentication ---

(deftest wrap-authentication-with-handler-test
  (testing "wrap-authentication successfully wraps a handler"
    (let [handler-called (atom false)
          handler (fn [req]
                    (reset! handler-called true)
                    {:status 200 :body "OK"})
          backend (backends/token
                    {:authfn (fn [req token] nil)  ; Always unauthenticated
                     :token-name "x-api-key"})
          wrapped (middleware/wrap-authentication handler backend)
          response (wrapped {:headers {} :request-method :get})]
      (is (true? @handler-called))
      (is (= 200 (:status response)))
      (is (= "OK" (:body response))))))
