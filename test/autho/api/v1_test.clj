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
            [autho.policy-impact :as policy-impact]
            [autho.policy-versions :as pv]
            [autho.policy-impact-history :as impact-history]
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


(deftest authorization-handlers-return-stabilized-contracts-test
  (let [decision {:allowed false
                  :decision "deny"
                  :matchedRules ["deny-rule"]
                  :resourceClass "doc"
                  :resourceId "doc-1"
                  :operation "read"}
        who {:strategy "almost_one_allow_no_deny"
             :resourceClass "doc"
             :operation "read"
             :allowCandidates [{:resourceClass "doc" :subjectCond [["=" "role" "manager"]] :operation "read"}]
             :denyCandidates []}
        what {:strategy "almost_one_allow_no_deny"
              :resourceClass "doc"
              :operation "read"
              :page 1
              :pageSize 20
              :allow [{:resourceClass "doc" :resourceCond [["=" "owner" "user1"]] :operation "read"}]
              :deny []}
        explain {:decision false
                 :allowed? false
                 :decisionType "deny"
                 :strategy "almost_one_allow_no_deny"
                 :resourceClass "doc"
                 :resourceId "doc-1"
                 :operation "read"
                 :matchedRuleNames ["deny-rule"]
                 :rules []}
        simulate {:decision true
                  :allowed? true
                  :decisionType "allow"
                  :strategy "almost_one_allow_no_deny"
                  :resourceClass "doc"
                  :resourceId "doc-1"
                  :operation "read"
                  :simulated true
                  :policySource "current"
                  :policyVersion 7
                  :matchedRuleNames ["allow-rule"]
                  :rules []}]
    (with-redefs [pdp/isAuthorized (fn [_ _] decision)
                  pdp/whoAuthorizedDetailed (fn [_ _] who)
                  pdp/whatAuthorizedDetailed (fn [_ _] what)
                  pdp/explain (fn [_ _] explain)
                  pdp/simulate (fn [_ _] simulate)]
      (let [decision-body (json/write-value-as-string {:subject {:id "user1"}
                                                       :resource {:class "doc" :id "doc-1"}
                                                       :operation "read"})
            who-body (json/write-value-as-string {:resource {:class "doc" :id "doc-1"}
                                                  :operation "read"})
            what-body (json/write-value-as-string {:subject {:id "user1"}
                                                   :resource {:class "doc"}
                                                   :operation "read"})]
        (is (= decision (:data (parse-response-body (handlers/is-authorized (mock-request :body decision-body))))))
        (is (= who (:data (parse-response-body (handlers/who-authorized (mock-request :body who-body))))))
        (is (= what (:data (parse-response-body (handlers/what-authorized (mock-request :body what-body))))))
        (is (= explain (:data (parse-response-body (handlers/explain-decision (mock-request :body decision-body))))))
        (is (= simulate (:data (parse-response-body (handlers/simulate-decision (mock-request :body decision-body))))))))))

(deftest who-and-what-authorization-handlers-use-endpoint-specific-validation-test
  (with-redefs [pdp/whoAuthorizedDetailed (fn [_ body] {:resourceClass (get-in body [:resource :class])
                                                        :operation (:operation body)
                                                        :allowCandidates []
                                                        :denyCandidates []})
                pdp/whatAuthorizedDetailed (fn [_ body] {:resourceClass (get-in body [:resource :class])
                                                         :operation (:operation body)
                                                         :page 1
                                                         :pageSize 20
                                                         :allow []
                                                         :deny []})]
    (let [who-response (handlers/who-authorized
                         (mock-request :body (json/write-value-as-string {:resource {:class "doc"}
                                                                          :operation "read"})))
          what-response (handlers/what-authorized
                          (mock-request :body (json/write-value-as-string {:subject {:id "user1"}
                                                                           :resource {:class "doc"}})))
          who-body (parse-response-body who-response)
          what-body (parse-response-body what-response)]
      (is (= 200 (:status who-response)))
      (is (= "doc" (get-in who-body [:data :resourceClass])))
      (is (= 200 (:status what-response)))
      (is (= "doc" (get-in what-body [:data :resourceClass]))))))

(deftest explain-route-forwards-request-test
  (let [captured (atom nil)
        response (with-redefs [handlers/explain-decision
                               (fn [request]
                                 (reset! captured request)
                                 (response/success-response {:ok true}))]
                   (api-v1/v1-routes {:request-method :post
                                      :uri "/authz/explain"
                                      :headers {}
                                      :body (ByteArrayInputStream. (.getBytes "{}" "UTF-8"))}))]
    (is (= 200 (:status response)))
    (is (map? @captured))
    (is (= "/authz/explain" (:uri @captured)))))


(deftest create-policy-returns-validation-warnings-test
  (let [request (mock-request :body (json/write-value-as-string {:resourceClass "Document"
                                                                 :strategy "almost_one_allow_no_deny"
                                                                 :rules []}))]
    (with-redefs [prp/submit-policy (fn [& _] {:errors []
                                               :warnings [{:code "UNCONDITIONAL_RULE"
                                                           :message "broad"}]})]
      (let [response (handlers/create-policy request)
            body (parse-response-body response)]
        (is (= 201 (:status response)))
        (is (= "UNCONDITIONAL_RULE" (get-in body [:data :validation :warnings 0 :code])))))))

(deftest update-policy-returns-validation-details-for-policy-errors-test
  (let [request (mock-request :body (json/write-value-as-string {:resourceClass "Document"
                                                                 :strategy "almost_one_allow_no_deny"
                                                                 :rules []}))]
    (with-redefs [prp/submit-policy (fn [& _]
                                      (throw (ex-info "Policy safety validation failed"
                                                      {:status 400
                                                       :error-code "INVALID_POLICY_SAFETY"
                                                       :issues [{:code "DUPLICATE_RULE_NAME"
                                                                 :message "dup"}]})))]
      (let [response (handlers/update-policy "Document" request)
            body (parse-response-body response)]
        (is (= 400 (:status response)))
        (is (= "INVALID_POLICY_SAFETY" (get-in body [:error :code])))
        (is (= "DUPLICATE_RULE_NAME" (get-in body [:error :details 0 :code])))))))


(deftest analyze-policy-impact-handler-returns-impact-summary-test
  (let [request (mock-request :body (json/write-value-as-string {:requests [{:subject {:id "user1"}
                                                                             :resource {:class "Document" :id "doc-1"}
                                                                             :operation "read"}]
                                                                 :candidatePolicy {:strategy "permit-unless-deny"}}))]
    (with-redefs [policy-impact/analyze-impact (fn [_ body]
                                                 {:resourceClass (:resourceClass body)
                                                  :summary {:totalRequests 1
                                                            :changedDecisions 1
                                                            :grants 1
                                                            :revokes 0}
                                                  :changes [{:requestId 0 :changeType :grant}]})]
      (let [response (handlers/analyze-policy-impact "Document" request)
            body (parse-response-body response)]
        (is (= 200 (:status response)))
        (is (= "Document" (get-in body [:data :resourceClass])))
        (is (= 1 (get-in body [:data :summary :grants])))))))

(deftest list-policy-versions-handler-returns-lineage-metadata-test
  (with-redefs [pv/list-versions (fn [_]
                                   [{:id 12
                                     :resource_class "Document"
                                     :version 6
                                     :author "api"
                                     :comment "Promoted"
                                     :source_analysis_id 7
                                     :deployment_kind "impact_rollout"
                                     :source_candidate_version 5
                                     :created_at "2026-03-27T10:00:00Z"}])]
    (let [response (handlers/list-policy-versions "Document")
          body (parse-response-body response)]
      (is (= 200 (:status response)))
      (is (= "Document" (get-in body [:data 0 :resourceClass])))
      (is (= 7 (get-in body [:data 0 :sourceAnalysisId])))
      (is (= "impact_rollout" (get-in body [:data 0 :deploymentKind])))
      (is (= 5 (get-in body [:data 0 :sourceCandidateVersion]))))))

(deftest get-policy-version-handler-returns-lineage-metadata-test
  (with-redefs [pv/get-version-details (fn [_ _]
                                         {:id 12
                                          :resourceClass "Document"
                                          :version 6
                                          :author "api"
                                          :comment "Promoted"
                                          :sourceAnalysisId 7
                                          :deploymentKind "impact_rollout"
                                          :sourceCandidateVersion 5
                                          :createdAt "2026-03-27T10:00:00Z"
                                          :policy {:resourceClass "Document"
                                                   :strategy "almost_one_allow_no_deny"
                                                   :rules []}})
                impact-history/get-analysis (fn [_ analysis-id]
                                              {:id analysis-id
                                               :resourceClass "Document"
                                               :reviewStatus "approved"
                                               :analysis {:summary {:changedDecisions 3}}})]
    (let [response (handlers/get-policy-version "Document" "6")
          body (parse-response-body response)]
      (is (= 200 (:status response)))
      (is (= 6 (get-in body [:data :version])))
      (is (= 7 (get-in body [:data :sourceAnalysisId])))
      (is (= "impact_rollout" (get-in body [:data :deploymentKind])))
      (is (= "almost_one_allow_no_deny" (get-in body [:data :policy :strategy])))
      (is (= 7 (get-in body [:data :sourceAnalysis :id])))
      (is (= "approved" (get-in body [:data :sourceAnalysis :reviewStatus])))
      (is (= 3 (get-in body [:data :sourceAnalysis :analysis :summary :changedDecisions]))))))
(deftest get-policy-change-timeline-handler-returns-unified-events-test
  (with-redefs [pv/list-versions (fn [_]
                                   [{:id 12
                                     :resource_class "Document"
                                     :version 6
                                     :author "api"
                                     :comment "Promoted"
                                     :source_analysis_id 7
                                     :deployment_kind "impact_rollout"
                                     :source_candidate_version 5
                                     :created_at "2026-03-27T10:15:00Z"}])
                impact-history/list-analyses (fn [_]
                                               [{:id 7
                                                 :resourceClass "Document"
                                                 :author "api"
                                                 :candidateVersion 5
                                                 :candidateSource "version"
                                                 :changedDecisions 2
                                                 :highRisk true
                                                 :createdAt "2026-03-27T10:00:00Z"
                                                 :reviewStatus "approved"
                                                 :reviewedBy "reviewer"
                                                 :reviewNote "ok"
                                                 :reviewedAt "2026-03-27T10:05:00Z"
                                                 :rolloutStatus "deployed"
                                                 :deployedVersion 6
                                                 :deployedBy "api"
                                                 :deployedAt "2026-03-27T10:10:00Z"}])
                pv/list-versions-by-analysis (fn [_ _] [])]
    (let [response (handlers/get-policy-change-timeline "Document" (mock-request))
          body (parse-response-body response)]
      (is (= 200 (:status response)))
      (is (= "Document" (get-in body [:data :resourceClass])))
      (is (= 4 (get-in body [:data :count])))
      (is (= "policy_version_created" (get-in body [:data :events 0 :eventType])))
      (is (= "impact_deployed" (get-in body [:data :events 1 :eventType])))
      (is (= "impact_reviewed" (get-in body [:data :events 2 :eventType])))
      (is (= "impact_analysis_created" (get-in body [:data :events 3 :eventType])))
      (is (= 7 (get-in body [:data :events 0 :sourceAnalysisId])))
      (is (= 6 (get-in body [:data :events 1 :deployedVersion]))))))

(deftest get-policy-change-timeline-handler-filters-events-test
  (with-redefs [pv/list-versions (fn [_]
                                   [{:id 12
                                     :resource_class "Document"
                                     :version 6
                                     :author "api"
                                     :comment "Promoted"
                                     :source_analysis_id 7
                                     :deployment_kind "impact_rollout"
                                     :source_candidate_version 5
                                     :created_at "2026-03-27T10:15:00Z"}])
                impact-history/list-analyses (fn [_]
                                               [{:id 7
                                                 :resourceClass "Document"
                                                 :author "api"
                                                 :candidateVersion 5
                                                 :candidateSource "version"
                                                 :changedDecisions 2
                                                 :highRisk true
                                                 :createdAt "2026-03-27T10:00:00Z"
                                                 :reviewStatus "approved"
                                                 :reviewedBy "reviewer"
                                                 :reviewNote "ok"
                                                 :reviewedAt "2026-03-27T10:05:00Z"
                                                 :rolloutStatus "deployed"
                                                 :deployedVersion 6
                                                 :deployedBy "api"
                                                 :deployedAt "2026-03-27T10:10:00Z"}])
                pv/list-versions-by-analysis (fn [_ _] [])]
    (let [event-type-response (handlers/get-policy-change-timeline "Document"
                                                                    (mock-request :params {"event-type" "impact_reviewed,impact_deployed"}))
          event-type-body (parse-response-body event-type-response)
          range-response (handlers/get-policy-change-timeline "Document"
                                                              (mock-request :params {"from" "2026-03-27T10:06:00Z"
                                                                                     "to" "2026-03-27T10:15:00Z"}))
          range-body (parse-response-body range-response)]
      (is (= 200 (:status event-type-response)))
      (is (= 2 (get-in event-type-body [:data :count])))
      (is (= ["impact_deployed" "impact_reviewed"] (mapv :eventType (get-in event-type-body [:data :events]))))
      (is (= 200 (:status range-response)))
      (is (= 2 (get-in range-body [:data :count])))
      (is (= ["policy_version_created" "impact_deployed"] (mapv :eventType (get-in range-body [:data :events])))))))
(deftest diff-policy-versions-handler-returns-detailed-contract-test
  (with-redefs [pv/diff-versions (fn [_ from to]
                                   {:resourceClass "Document"
                                    :from from
                                    :to to
                                    :strategy {:from "almost_one_allow_no_deny"
                                               :to "deny_unless_allow"
                                               :changed true}
                                    :summary {:strategyChanged true
                                              :addedRules 1
                                              :removedRules 1
                                              :changedRules 1
                                              :unchangedRules 0
                                              :totalRuleChanges 3}
                                    :rules {:added [{:name "allow-share"}]
                                            :removed [{:name "deny-write"}]
                                            :changed [{:name "allow-read"
                                                       :changedFields ["subjectCond"]}]
                                            :unchanged []}})]
    (let [response (handlers/diff-policy-versions "Document" "1" "2")
          body (parse-response-body response)]
      (is (= 200 (:status response)))
      (is (= "Document" (get-in body [:data :resourceClass])))
      (is (= true (get-in body [:data :strategy :changed])))
      (is (= 3 (get-in body [:data :summary :totalRuleChanges])))
      (is (= "allow-read" (get-in body [:data :rules :changed 0 :name]))))))

(deftest analyze-policy-impact-handler-persists-history-test
  (let [request (mock-request :body (json/write-value-as-string {:requests [{:subject {:id "user1"}
                                                                             :resource {:class "Document" :id "doc-1"}
                                                                             :operation "read"}]
                                                                 :candidatePolicy {:strategy "permit-unless-deny"}}))]
    (with-redefs [policy-impact/analyze-impact (fn [_ body]
                                                 {:resourceClass (:resourceClass body)
                                                  :summary {:totalRequests 1
                                                            :changedDecisions 1
                                                            :grants 1
                                                            :revokes 0}
                                                  :riskSignals {:highRisk false
                                                                :revokeCount 0}
                                                  :changes [{:requestId 0 :changeType :grant}]})
                  impact-history/save-analysis! (fn [_ _ _ _] 42)]
      (let [response (handlers/analyze-policy-impact "Document" request)
            body (parse-response-body response)]
        (is (= 200 (:status response)))
        (is (= 42 (get-in body [:data :analysisId])))
        (is (= "Document" (get-in body [:data :resourceClass])))))))

(deftest list-policy-impact-history-handler-returns-history-test
  (with-redefs [impact-history/list-analyses (fn [_]
                                               [{:id 7
                                                 :resourceClass "Document"
                                                 :requestCount 10
                                                 :changedDecisions 2
                                                 :highRisk true}])
                pv/list-versions-by-analysis (fn [_ analysis-id]
                                               [{:id 12
                                                 :resource_class "Document"
                                                 :version 6
                                                 :author "api"
                                                 :comment "Promoted"
                                                 :source_analysis_id analysis-id
                                                 :deployment_kind "impact_rollout"
                                                 :source_candidate_version 5
                                                 :created_at "2026-03-27T10:00:00Z"}])]
    (let [response (handlers/list-policy-impact-history "Document")
          body (parse-response-body response)]
      (is (= 200 (:status response)))
      (is (= 7 (get-in body [:data 0 :id])))
      (is (= true (get-in body [:data 0 :highRisk])))
      (is (= 6 (get-in body [:data 0 :deployedVersions 0 :version])))
      (is (= 7 (get-in body [:data 0 :deployedVersions 0 :sourceAnalysisId]))))))

(deftest get-policy-impact-history-entry-handler-returns-entry-test
  (with-redefs [impact-history/get-analysis (fn [_ _]
                                              {:id 7
                                               :resourceClass "Document"
                                               :candidatePolicy {:strategy "permit-unless-deny"}
                                               :analysis {:summary {:totalRequests 10}}})
                pv/list-versions-by-analysis (fn [_ analysis-id]
                                               [{:id 12
                                                 :resource_class "Document"
                                                 :version 6
                                                 :author "api"
                                                 :comment "Promoted"
                                                 :source_analysis_id analysis-id
                                                 :deployment_kind "impact_rollout"
                                                 :source_candidate_version 5
                                                 :created_at "2026-03-27T10:00:00Z"}])]
    (let [response (handlers/get-policy-impact-history-entry "Document" "7")
          body (parse-response-body response)]
      (is (= 200 (:status response)))
      (is (= 7 (get-in body [:data :id])))
      (is (= "permit-unless-deny" (get-in body [:data :candidatePolicy :strategy])))
      (is (= 10 (get-in body [:data :analysis :summary :totalRequests])))
      (is (= 6 (get-in body [:data :deployedVersions 0 :version])))
      (is (= "impact_rollout" (get-in body [:data :deployedVersions 0 :deploymentKind]))))))

(deftest update-policy-impact-review-handler-updates-status-test
  (let [request (mock-request :body (json/write-value-as-string {:status "approved"
                                                                 :reviewNote "Looks good"}))]
    (with-redefs [impact-history/update-review! (fn [_ _ payload]
                                                  {:id 7
                                                   :resourceClass "Document"
                                                   :reviewStatus (:status payload)
                                                   :reviewedBy (:reviewedBy payload)
                                                   :reviewNote (:reviewNote payload)})]
      (let [response (handlers/update-policy-impact-review "Document" "7" request)
            body (parse-response-body response)]
        (is (= 200 (:status response)))
        (is (= "approved" (get-in body [:data :reviewStatus])))
        (is (= "api" (get-in body [:data :reviewedBy])))
        (is (= "Looks good" (get-in body [:data :reviewNote])))))))

(deftest rollout-policy-impact-preview-handler-promotes-approved-preview-test
  (let [request (mock-request)]
    (with-redefs [impact-history/get-analysis (fn [_ _]
                                                {:id 7
                                                 :resourceClass "Document"
                                                 :candidateVersion 5
                                                 :reviewStatus "approved"
                                                 :rolloutStatus "not_deployed"})
                  pv/get-version (fn [_ version]
                                   {:resourceClass "Document"
                                    :strategy "almost_one_allow_no_deny"
                                    :rules [{:name (str "rule-" version)}]})
                  prp/submit-policy (fn [& _] nil)
                  pv/latest-version-number (fn [_] 6)
                  pv/annotate-version! (fn [_ version payload]
                                         {:version version
                                          :source_analysis_id (:sourceAnalysisId payload)
                                          :deployment_kind (:deploymentKind payload)
                                          :source_candidate_version (:sourceCandidateVersion payload)})
                  impact-history/mark-deployed! (fn [_ analysis-id payload]
                                                  {:id analysis-id
                                                   :resourceClass "Document"
                                                   :reviewStatus "approved"
                                                   :rolloutStatus "deployed"
                                                   :deployedVersion (:deployedVersion payload)
                                                   :deployedBy (:deployedBy payload)})]
      (let [response (handlers/rollout-policy-impact-preview "Document" "7" request)
            body (parse-response-body response)]
        (is (= 200 (:status response)))
        (is (= 7 (get-in body [:data :analysisId])))
        (is (= 5 (get-in body [:data :sourceCandidateVersion])))
        (is (= 6 (get-in body [:data :newVersion])))
        (is (= "deployed" (get-in body [:data :rolloutStatus])))
        (is (= 7 (get-in body [:data :versionLink :source_analysis_id])))
        (is (= "impact_rollout" (get-in body [:data :versionLink :deployment_kind])))
        (is (= "deployed" (get-in body [:data :history :rolloutStatus])))))))

(deftest rollout-policy-impact-preview-handler-requires-approved-preview-test
  (let [request (mock-request)]
    (with-redefs [impact-history/get-analysis (fn [_ _]
                                                {:id 7
                                                 :resourceClass "Document"
                                                 :candidateVersion 5
                                                 :reviewStatus "reviewed"
                                                 :rolloutStatus "not_deployed"})]
      (let [response (handlers/rollout-policy-impact-preview "Document" "7" request)
            body (parse-response-body response)]
        (is (= 409 (:status response)))
        (is (= "POLICY_IMPACT_NOT_APPROVED" (get-in body [:error :code])))))))

(deftest rollout-policy-impact-preview-handler-promotes-stored-candidate-policy-test
  (let [request (mock-request)
        submitted (atom nil)]
    (with-redefs [impact-history/get-analysis (fn [_ _]
                                                {:id 9
                                                 :resourceClass "Document"
                                                 :candidatePolicy {:resourceClass "Document"
                                                                   :strategy "deny_unless_allow"
                                                                   :rules [{:name "allow-read"}]}
                                                 :reviewStatus "approved"
                                                 :rolloutStatus "not_deployed"})
                  pv/get-version (fn [& _]
                                   (throw (ex-info "should not load version" {})))
                  prp/submit-policy (fn [_ policy-json author comment]
                                      (reset! submitted {:policyJson policy-json
                                                         :author author
                                                         :comment comment})
                                      nil)
                  pv/latest-version-number (fn [_] 11)
                  pv/annotate-version! (fn [_ version payload]
                                         {:version version
                                          :source_analysis_id (:sourceAnalysisId payload)
                                          :deployment_kind (:deploymentKind payload)})
                  impact-history/mark-deployed! (fn [_ analysis-id payload]
                                                  {:id analysis-id
                                                   :resourceClass "Document"
                                                   :rolloutStatus "deployed"
                                                   :deployedVersion (:deployedVersion payload)})]
      (let [response (handlers/rollout-policy-impact-preview "Document" "9" request)
            body (parse-response-body response)]
        (is (= 200 (:status response)))
        (is (= true (get-in body [:data :sourceCandidatePolicy])))
        (is (= 11 (get-in body [:data :newVersion])))
        (is (= 9 (get-in body [:data :versionLink :source_analysis_id])))
        (is (= "impact_rollout" (get-in body [:data :versionLink :deployment_kind])))
        (is (str/includes? (:comment @submitted) "stored candidate policy"))
        (is (str/includes? (:policyJson @submitted) "\"deny_unless_allow\""))))))

(deftest rollout-policy-impact-preview-handler-rejects-non-promotable-preview-test
  (let [request (mock-request)]
    (with-redefs [impact-history/get-analysis (fn [_ _]
                                                {:id 7
                                                 :resourceClass "Document"
                                                 :candidateVersion nil
                                                 :reviewStatus "approved"
                                                 :rolloutStatus "not_deployed"})]
      (let [response (handlers/rollout-policy-impact-preview "Document" "7" request)
            body (parse-response-body response)]
        (is (= 409 (:status response)))
        (is (= "POLICY_IMPACT_NOT_PROMOTABLE" (get-in body [:error :code])))))))







