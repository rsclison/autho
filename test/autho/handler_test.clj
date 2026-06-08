(ns autho.handler-test
  (:require [clojure.test :refer :all]
            [autho.handler :refer :all]
            [autho.topology :as topology]
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

    (testing "POST /whatAuthorized with empty body"
      (let [request (assoc base-request :uri "/whatAuthorized" :body nil)
            response (app request)
            response-body (json/read-value (:body response) json/keyword-keys-object-mapper)]
        (is (= 400 (:status response)))
        (is (= "Request body is empty." (get-in response-body [:error :message])))))))

(deftest admin-routes-test
  ;; Inject identity directly into the request rather than relying on runtime
  ;; credential matching.  buddy-auth's wrap-authentication leaves :identity
  ;; unchanged when no backend matches, so the injected identity persists
  ;; through the inner auth middleware inside app-routes.
  (let [base-request {:request-method :post
                      :headers {}
                      :identity {:auth-method :api-key
                                 :client-id :trusted-internal-app
                                 :roles ["governance-admin"]}}
        app app-routes]
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

(deftest admin-routes-require-governance-admin-or-admin-jwt-test
  (let [app (auth/wrap-authentication app-routes)]
    (testing "API key without governance-admin cannot access admin routes"
      (let [response (app {:request-method :post
                           :uri "/admin/reinit"
                           :headers {}
                           :identity {:auth-method :api-key
                                      :client-id "trusted-internal-app"
                                      :roles ["policy-deployer"]}})
            body (json/read-value (:body response) json/keyword-keys-object-mapper)]
        (is (= 403 (:status response)))
        (is (= "FORBIDDEN" (get-in body [:error :code])))))

    (testing "JWT admin can access admin routes"
      (let [init-call-counter (atom 0)]
        (with-redefs [pdp/init (fn [] (swap! init-call-counter inc))]
          (let [response (app {:request-method :post
                               :uri "/admin/reinit"
                               :headers {}
                               :identity {:role "admin"}})]
            (is (= 1 @init-call-counter))
            (is (= 200 (:status response)))))))))

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

(deftest status-includes-topology-summary-test
  (let [app (auth/wrap-authentication app-routes)]
    (with-redefs [topology/current-config (fn []
                                           {:supportedPlanes [:control :data :evidence]
                                           :enabledPlanes #{:control :evidence}})]
      (let [response (app {:request-method :get
                           :uri "/status"
                           :headers {}})
            body (json/read-value (:body response) json/keyword-keys-object-mapper)]
        (is (= 200 (:status response)))
        (is (= ["control" "data" "evidence"]
               (get-in body [:topology :supportedPlanes])))
        (is (= ["control" "evidence"]
               (sort (get-in body [:topology :enabledPlanes]))))))))

(deftest v1-routes-are-plane-gated-at-app-level-test
  (let [app (auth/wrap-authentication app-routes)]
    (try
      (topology/set-enabled-planes! #{:data :control})
      (let [response (app {:request-method :get
                           :uri "/v1/evidence"
                           :headers {}})
            body (json/read-value (:body response) json/keyword-keys-object-mapper)]
        (is (= 503 (:status response)))
        (is (= "PLANE_DISABLED" (get-in body [:error :code]))))
      (finally
        (topology/set-enabled-planes! #{:control :data :evidence})))))
