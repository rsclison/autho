(ns autho.handler-test
  (:require [clojure.test :refer :all]
            [autho.handler :refer :all]
            [autho.pdp :as pdp]
            [autho.prp :as prp]
            [jsonista.core :as json]
            [autho.auth :as auth])
  (:import (java.io ByteArrayInputStream)))

(deftest empty-body-protection-test
  (let [base-request {:request-method :post :headers {"x-api-key" "trusted-app-secret"}}
        app (auth/wrap-authentication app-routes)]
    (testing "POST /isAuthorized with empty body"
      (let [request (assoc base-request :uri "/isAuthorized" :body nil)
            response (app request)
            response-body (json/read-value (:body response) json/keyword-keys-object-mapper)]
        (is (= 400 (:status response)))
        (is (= "Request body is empty." (get-in response-body [:error :message])))))

    (testing "POST /whoAuthorized with empty body"
      (let [request (assoc base-request :uri "/whoAuthorized" :body nil)
            response (app request)
            response-body (json/read-value (:body response) json/keyword-keys-object-mapper)]
        (is (= 400 (:status response)))
        (is (= "Request body is empty." (get-in response-body [:error :message])))))

    (testing "POST /whichAuthorized with empty body"
      (let [request (assoc base-request :uri "/whichAuthorized" :body nil)
            response (app request)
            response-body (json/read-value (:body response) json/keyword-keys-object-mapper)]
        (is (= 400 (:status response)))
        (is (= "Request body is empty." (get-in response-body [:error :message])))))))

(deftest admin-routes-test
  (let [base-request {:request-method :post :headers {"x-api-key" "trusted-app-secret"}}
        app (auth/wrap-authentication app-routes)]
    (testing "POST /admin/reinit"
      (let [init-call-counter (atom 0)]
        (with-redefs [pdp/init (fn [] (swap! init-call-counter inc))]
          (let [request (assoc base-request :uri "/admin/reinit")
                response (app request)]
            (is (= 1 @init-call-counter))
            (is (= 200 (:status response)))
            (is (= {:status "ok" :message "PDP reinitialized."}
                   (json/read-value (:body response) json/keyword-keys-object-mapper)))))))

    (testing "POST /admin/reload_rules"
      (let [initf-call-counter (atom 0)
            rules-path "path/to/rules.edn"]
        (with-redefs [pdp/get-rules-repository-path (fn [] rules-path)
                      prp/initf (fn [path]
                                  (is (= rules-path path))
                                  (swap! initf-call-counter inc))]
          (let [request (assoc base-request :uri "/admin/reload_rules")
                response (app request)]
            (is (= 1 @initf-call-counter))
            (is (= 200 (:status response)))
            (is (= {:status "ok" :message "Rule repository reloaded."}
                   (json/read-value (:body response) json/keyword-keys-object-mapper)))))))))

(deftest rule-loading-failure-test
  (let [app (auth/wrap-authentication app-routes)]
    (testing "API returns 503 when rule repository fails to load"
      (with-redefs [prp/get-rules-repository-status (fn [] :failed)]
        (let [request {:request-method :post
                       :uri "/isAuthorized"
                       :headers {"x-api-key" "trusted-app-secret"}
                       :body (ByteArrayInputStream. (.getBytes "{}" "UTF-8"))}
              response (app request)
              response-body (json/read-value (:body response) json/keyword-keys-object-mapper)]
          (is (= 503 (:status response)))
          (is (= "Rule repository is not loaded. Please check server logs."
                 (get-in response-body [:error :message]))))))))
