(ns autho.handler-test
  (:require [clojure.test :refer :all]
            [autho.handler :refer :all]
            [autho.topology :as topology]
            [autho.pdp :as pdp]
            [autho.prp :as prp]
            [jsonista.core :as json]
            [autho.auth :as auth]
            [autho.audit :as audit]
            [autho.features :as features]
            [autho.time-travel :as time-travel])
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

(deftest audit-trail-route-uses-audit-log-test
  (let [app (auth/wrap-authentication app-routes)
        body (json/write-value-as-string {:resourceClass "Facture" :resourceId "INV-1"
                                          :startTime "2026-03-01T00:00:00Z"
                                          :endTime "2026-03-31T23:59:59Z"})
        request (fn [identity]
                  {:request-method :post :uri "/audit-trail" :headers {}
                   :identity identity
                   :body (ByteArrayInputStream. (.getBytes body "UTF-8"))})]
    (with-redefs [features/licensed? (constantly true)
                  time-travel/audit-trail (fn [& args] {:query "audit-trail" :args (vec (take 4 args))})]
      (testing "non-admin clients cannot read the audit trail"
        (is (= 403 (:status (app (request {:auth-method :api-key :client-id "app" :roles ["policy-deployer"]}))))))
      (testing "admins get the trail without Kafka"
        (let [response (app (request {:role "admin"}))
              parsed (json/read-value (:body response) json/keyword-keys-object-mapper)]
          (is (= 200 (:status response)))
          (is (= ["Facture" "INV-1" "2026-03-01T00:00:00Z" "2026-03-31T23:59:59Z"] (:args parsed))))))
    (with-redefs [features/licensed? (constantly false)]
      (is (= 402 (:status (app (request {:role "admin"}))))))))

(deftest time-travel-routes-test
  (let [app (auth/wrap-authentication app-routes)
        call (fn [uri body]
               (let [response (app {:request-method :post :uri uri :headers {}
                                    :identity {:role "admin"}
                                    :body (ByteArrayInputStream. (.getBytes (json/write-value-as-string body) "UTF-8"))})]
                 [(:status response) (json/read-value (:body response) json/keyword-keys-object-mapper)]))]
    (with-redefs [kafka-enabled false]
      (is (= 503 (first (call "/isAuthorized-at-time" {:timestamp "2026-03-15T10:30:00Z"})))))
    (with-redefs [kafka-enabled true
                  time-travel/is-authorized-at-time (fn [req ts config]
                                                      {:decision "allow" :req req :ts ts :env (:environment config)})
                  time-travel/who-was-authorized-at (fn [rc rid action ts config]
                                                      {:args [rc rid action ts (:subject-class config) (:limit config)]})
                  time-travel/what-could-access-at (fn [sid action ts config]
                                                     (throw (ex-info "bad timestamp" {:status 400 :error-code "INVALID_TIMESTAMP"})))]
      (let [[status body] (call "/isAuthorized-at-time" {:timestamp "2026-03-15T10:30:00Z" :environment "staging"
                                                          :subject {:id "alice"} :resource {:class "Facture" :id "1"}})]
        (is (= 200 status))
        (is (= "staging" (:env body)))
        (is (= {:subject {:id "alice"} :resource {:class "Facture" :id "1"}} (:req body))))
      (is (= 400 (first (call "/isAuthorized-at-time" {:subject {:id "alice"}}))))
      (let [[status body] (call "/who-was-authorized-at" {:resourceClass "Facture" :resourceId "1" :operation "approve"
                                                           :timestamp "2026-03-15T10:30:00Z" :subjectClass "Person" :limit 5})]
        (is (= 200 status))
        (is (= ["Facture" "1" "approve" "2026-03-15T10:30:00Z" "Person" 5] (:args body))))
      (is (= 400 (first (call "/who-was-authorized-at" {:resourceClass "Facture"}))))
      (let [[status body] (call "/what-could-access-at" {:subjectId "alice" :action "view" :timestamp "x"})]
        (is (= 400 status))
        (is (= "INVALID_TIMESTAMP" (get-in body [:error :code])))))))
