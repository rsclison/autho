(ns autho.handler-test
  (:require [clojure.test :refer :all]
            [autho.handler :refer :all]
            [autho.prp :as prp]
            [jsonista.core :as json]))

(deftest empty-body-protection-test
  (testing "POST /isAuthorized with empty body"
    (let [request {:request-method :post :uri "/isAuthorized" :body nil}
          response (app-routes request)
          response-body (json/read-value (:body response) json/keyword-keys-object-mapper)]
      (is (= 400 (:status response)))
      (is (= "Request body is empty." (get-in response-body [:error :message])))
      (is (contains? (get-in response-body [:error]) :example))))

  (testing "POST /whoAuthorized with empty body"
    (let [request {:request-method :post :uri "/whoAuthorized" :body nil}
          response (app-routes request)
          response-body (json/read-value (:body response) json/keyword-keys-object-mapper)]
      (is (= 400 (:status response)))
      (is (= "Request body is empty." (get-in response-body [:error :message])))
      (is (contains? (get-in response-body [:error]) :example))))

  (testing "POST /whichAuthorized with empty body"
    (let [request {:request-method :post :uri "/whichAuthorized" :body nil}
          response (app-routes request)
          response-body (json/read-value (:body response) json/keyword-keys-object-mapper)]
      (is (= 400 (:status response)))
      (is (= "Request body is empty." (get-in response-body [:error :message])))
      (is (contains? (get-in response-body [:error]) :example)))))

(deftest rule-loading-failure-test
  (testing "API returns 503 when rule repository fails to load"
    (with-redefs [prp/get-rules-repository-status (fn [] :failed)]
      (let [request {:request-method :post :uri "/isAuthorized" :body "{}"}
            response (app-routes request)
            response-body (json/read-value (:body response) json/keyword-keys-object-mapper)]
        (is (= 503 (:status response)))
        (is (= "Rule repository is not loaded. Please check server logs."
               (get-in response-body [:error :message])))))))
