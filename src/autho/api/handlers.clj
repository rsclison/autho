(ns autho.api.handlers
  "REST API v1 request handlers.
   Each handler returns standardized JSON responses and validates input."
  (:require [autho.api.response :as response]
            [autho.api.pagination :as pagination]
            [autho.validation :as validation]
            [autho.local-cache :as cache]
            [autho.pdp :as pdp]
            [autho.prp :as prp]
            [autho.policy-yaml :as policy-yaml]
            [autho.policy-impact :as policy-impact]
            [autho.policy-impact-history :as impact-history]
            [autho.policy-versions :as pv]
            [autho.features :as features]
            [jsonista.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.api.handlers"))

(defn- policy-exception->response
  [exception default-code default-prefix]
  (let [data (ex-data exception)
        status (or (:status data) 500)
        code (or (:error-code data) default-code)
        details (or (:issues data)
                    (get-in data [:analysis :warnings]))
        message (if (< status 500)
                  (.getMessage exception)
                  (str default-prefix (.getMessage exception)))]
    (when (>= status 500)
      (log/error exception default-prefix))
    (response/error-response code message status details)))

(defn- response-map?
  [value]
  (and (map? value)
       (contains? value :status)
       (contains? value :headers)
       (contains? value :body)))

(defn require-body
  [request]
  (if-let [body (:body-params request)]
    body
    (try
      (if-let [stream (:body request)]
        (json/read-value stream json/keyword-keys-object-mapper)
        (response/error-response "MISSING_BODY" "Request body is required" 400))
      (catch Exception _
        (response/error-response "INVALID_REQUEST_BODY" "Malformed JSON request body" 400)))))

(defn parse-json-body
  [request]
  (let [result (require-body request)]
    (when-not (response-map? result)
      result)))
(defn- validate-authz-request
  [body]
  (cond
    (not (map? body))
    {:code "INVALID_REQUEST" :message "Request body must be a JSON object"}

    (nil? (:subject body))
    {:code "MISSING_SUBJECT" :message "Field 'subject' is required"}

    (nil? (:resource body))
    {:code "MISSING_RESOURCE" :message "Field 'resource' is required"}

    (nil? (:operation body))
    {:code "MISSING_OPERATION" :message "Field 'operation' is required"}

    :else nil))

(defn- validate-who-request
  [body]
  (cond
    (not (map? body))
    {:code "INVALID_REQUEST" :message "Request body must be a JSON object"}

    (nil? (:resource body))
    {:code "MISSING_RESOURCE" :message "Field 'resource' is required"}

    (nil? (:operation body))
    {:code "MISSING_OPERATION" :message "Field 'operation' is required"}

    :else nil))

(defn- validate-what-request
  [body]
  (cond
    (not (map? body))
    {:code "INVALID_REQUEST" :message "Request body must be a JSON object"}

    (nil? (:subject body))
    {:code "MISSING_SUBJECT" :message "Field 'subject' is required"}

    :else nil))

(defn parse-json-body
  [request]
  (let [result (require-body request)]
    (when-not (response-map? result)
      result)))
(defn- validate-authz-request-by-mode
  [mode body]
  (case mode
    :whoAuthorized (validate-who-request body)
    :whatAuthorized (validate-what-request body)
    (validate-authz-request body)))

(defn- handle-authz-request
  [request mode handler-fn error-code error-prefix]
  (let [body-or-response (require-body request)]
    (if (response-map? body-or-response)
      body-or-response
      (if-let [validation-error (validate-authz-request-by-mode mode body-or-response)]
        (response/error-response (:code validation-error) (:message validation-error) 400)
        (try
          (response/success-response (handler-fn request body-or-response))
          (catch Exception e
            (log/error e error-prefix)
            (response/error-response error-code
                                     (str error-prefix (.getMessage e))
                                     500)))))))

(defn is-authorized
  [request]
  (log/debug "Processing isAuthorized request")
  (handle-authz-request request :isAuthorized pdp/isAuthorized "AUTHZ_ERROR" "Authorization failed: "))

(defn who-authorized
  [request]
  (log/debug "Processing whoAuthorized request")
  (handle-authz-request request :whoAuthorized pdp/whoAuthorizedDetailed "WHO_AUTHZ_ERROR" "whoAuthorized failed: "))

(defn what-authorized
  [request]
  (log/debug "Processing whatAuthorized request")
  (handle-authz-request request :whatAuthorized pdp/whatAuthorizedDetailed "WHAT_AUTHZ_ERROR" "whatAuthorized failed: "))

(defn explain-decision
  [request]
  (features/require-license! :explain)
  (log/debug "Processing explain request")
  (handle-authz-request request :explain pdp/explain "EXPLAIN_ERROR" "Failed to explain decision: "))

(defn simulate-decision
  [request]
  (features/require-license! :simulate)
  (log/debug "Processing simulation request")
  (handle-authz-request request :simulate pdp/simulate "SIMULATE_ERROR" "Simulation failed: "))

(defn- version-record->api
  [record]
  (cond-> {:id (:id record)
           :resourceClass (or (:resourceClass record) (:resource_class record))
           :version (:version record)
           :author (:author record)
           :comment (:comment record)
           :createdAt (or (:createdAt record) (:created_at record))}
    (contains? record :sourceAnalysisId)
    (assoc :sourceAnalysisId (:sourceAnalysisId record))
    (contains? record :deploymentKind)
    (assoc :deploymentKind (:deploymentKind record))
    (contains? record :sourceCandidateVersion)
    (assoc :sourceCandidateVersion (:sourceCandidateVersion record))
    (contains? record :source_analysis_id)
    (assoc :sourceAnalysisId (:source_analysis_id record))
    (contains? record :deployment_kind)
    (assoc :deploymentKind (:deployment_kind record))
    (contains? record :source_candidate_version)
    (assoc :sourceCandidateVersion (:source_candidate_version record))
    (:policy record)
    (assoc :policy (:policy record))
    (:sourceAnalysis record)
    (assoc :sourceAnalysis (:sourceAnalysis record))))

(defn- attach-source-analysis
  [version-record]
  (if-let [analysis-id (or (:sourceAnalysisId version-record)
                           (:source_analysis_id version-record))]
    (assoc version-record
           :sourceAnalysis (impact-history/get-analysis (or (:resourceClass version-record)
                                                            (:resource_class version-record))
                                                        analysis-id))
    version-record))

(defn- attach-deployed-versions
  [analysis-record]
  (let [resource-class (:resourceClass analysis-record)
        analysis-id (:id analysis-record)]
    (if (and resource-class analysis-id)
      (assoc analysis-record
             :deployedVersions (mapv version-record->api
                                     (pv/list-versions-by-analysis resource-class analysis-id)))
      analysis-record)))
(defn- timeline-sort-key
  [event]
  (str (:occurredAt event)))

(defn- parse-timeline-event-types
  [request]
  (let [raw (or (get-in request [:params "event-type"])
                (get-in request [:params :event-type])
                (get-in request [:params "eventTypes"])
                (get-in request [:params :eventTypes]))]
    (when (and raw (not= "" raw))
      (->> (.split (str raw) ",")
           (map str/trim)
           (remove empty?)
           set))))

(defn- event-in-range?
  [event from-ts to-ts]
  (let [occurred-at (str (:occurredAt event))]
    (and (or (nil? from-ts) (>= (compare occurred-at from-ts) 0))
         (or (nil? to-ts) (<= (compare occurred-at to-ts) 0)))))

(defn- filter-timeline-events
  [request events]
  (let [event-types (parse-timeline-event-types request)
        from-ts (or (get-in request [:params "from"]) (get-in request [:params :from]))
        to-ts (or (get-in request [:params "to"]) (get-in request [:params :to]))]
    (->> events
         (filter #(or (nil? event-types) (contains? event-types (:eventType %))))
         (filter #(event-in-range? % from-ts to-ts))
         vec)))

(defn- version->timeline-event
  [version-record]
  {:eventType "policy_version_created"
   :resourceClass (:resourceClass version-record)
   :version (:version version-record)
   :occurredAt (:createdAt version-record)
   :author (:author version-record)
   :comment (:comment version-record)
   :sourceAnalysisId (:sourceAnalysisId version-record)
   :deploymentKind (:deploymentKind version-record)
   :sourceCandidateVersion (:sourceCandidateVersion version-record)})

(defn- analysis->timeline-events
  [analysis-record]
  (cond-> [{:eventType "impact_analysis_created"
            :resourceClass (:resourceClass analysis-record)
            :analysisId (:id analysis-record)
            :occurredAt (:createdAt analysis-record)
            :author (:author analysis-record)
            :candidateVersion (:candidateVersion analysis-record)
            :candidateSource (:candidateSource analysis-record)
            :changedDecisions (:changedDecisions analysis-record)
            :highRisk (:highRisk analysis-record)}]
    (:reviewedAt analysis-record)
    (conj {:eventType "impact_reviewed"
           :resourceClass (:resourceClass analysis-record)
           :analysisId (:id analysis-record)
           :occurredAt (:reviewedAt analysis-record)
           :reviewStatus (:reviewStatus analysis-record)
           :reviewedBy (:reviewedBy analysis-record)
           :reviewNote (:reviewNote analysis-record)})
    (:deployedAt analysis-record)
    (conj {:eventType "impact_deployed"
           :resourceClass (:resourceClass analysis-record)
           :analysisId (:id analysis-record)
           :occurredAt (:deployedAt analysis-record)
           :deployedVersion (:deployedVersion analysis-record)
           :deployedBy (:deployedBy analysis-record)
           :rolloutStatus (:rolloutStatus analysis-record)})))

(defn get-policy-change-timeline
  [resource-class request]
  (log/debug "Building policy change timeline for" resource-class)
  (try
    (let [versions (mapv version-record->api (pv/list-versions resource-class))
          analyses (mapv attach-deployed-versions (impact-history/list-analyses resource-class))
          events (->> (concat (mapcat analysis->timeline-events analyses)
                              (map version->timeline-event versions))
                      (sort-by timeline-sort-key #(compare %2 %1))
                      vec)
          filtered-events (filter-timeline-events request events)]
      (response/success-response {:resourceClass resource-class
                                  :count (count filtered-events)
                                  :events filtered-events}))
    (catch Exception e
      (log/error e "Error building policy change timeline")
      (response/error-response "POLICY_TIMELINE_ERROR"
                               (str "Failed to build policy change timeline: " (.getMessage e))
                               500))))
(def max-batch-size
  (try (Long/parseLong (or (System/getenv "MAX_BATCH_SIZE") "100"))
       (catch NumberFormatException _ 100)))

(defn batch-decisions
  [request]
  (log/debug "Processing batch authorization request")
  (let [body-or-response (require-body request)]
    (if (response-map? body-or-response)
      body-or-response
      (try
        (let [requests (:requests body-or-response)]
          (cond
            (not (and (vector? requests) (not (empty? requests))))
            (response/error-response "INVALID_BATCH_REQUEST"
                                     "Field 'requests' must be a non-empty array" 400)

            (> (count requests) max-batch-size)
            (response/error-response "BATCH_TOO_LARGE"
                                     (str "Batch size exceeds limit of " max-batch-size) 400)

            :else
            (let [results (doall (pmap #(pdp/isAuthorized request %) requests))]
              (response/success-response {:results results
                                          :count (count results)}))))
        (catch Exception e
          (log/error e "Error processing batch authorization request")
          (response/error-response "BATCH_ERROR"
                                   (str "Failed to process batch: " (.getMessage e))
                                   500))))))

(defn list-policies
  [request]
  (log/debug "Listing policies")
  (try
    (let [policies (vals (prp/get-policies))
          page-params (pagination/parse-pagination-params (:params request))
          paginated (pagination/paginate policies page-params)]
      (response/paginated-response (:items paginated)
                                   (:page paginated)
                                   (:per-page paginated)
                                   (:total paginated)))
    (catch Exception e
      (log/error e "Error listing policies")
      (response/error-response "LIST_POLICIES_ERROR"
                               (str "Failed to list policies: " (.getMessage e))
                               500))))

(defn get-policy
  [resource-class]
  (log/debug "Getting policy for" resource-class)
  (try
    (if-let [policy (prp/getGlobalPolicy resource-class)]
      (response/success-response policy)
      (response/error-response "POLICY_NOT_FOUND"
                               (str "Policy for resource class " resource-class " not found")
                               404))
    (catch Exception e
      (log/error e "Error getting policy")
      (response/error-response "GET_POLICY_ERROR"
                               (str "Failed to get policy: " (.getMessage e))
                               500))))

(defn create-policy
  [request]
  (log/debug "Creating policy")
  (let [body-or-response (require-body request)]
    (if (response-map? body-or-response)
      body-or-response
      (try
        (let [policy-json (json/write-value-as-string body-or-response)
              author (get-in request [:identity :client-id] "api")
              analysis (prp/submit-policy (:resourceClass body-or-response) policy-json author "Created via API")]
          (response/created-response {:message "Policy created successfully"
                                      :resourceClass (:resourceClass body-or-response)
                                      :validation {:warnings (:warnings analysis)}}
                                     (str "/v1/policies/" (:resourceClass body-or-response))))
        (catch clojure.lang.ExceptionInfo e
          (policy-exception->response e "INVALID_POLICY_SAFETY" "Failed to create policy: "))
        (catch Exception e
          (log/error e "Error creating policy")
          (response/error-response "CREATE_POLICY_ERROR"
                                   (str "Failed to create policy: " (.getMessage e))
                                   500))))))

(defn update-policy
  [resource-class request]
  (log/debug "Updating policy for" resource-class)
  (let [body-or-response (require-body request)]
    (if (response-map? body-or-response)
      body-or-response
      (let [body (assoc body-or-response :resourceClass resource-class)]
        (try
          (let [policy-json (json/write-value-as-string body)
                author (get-in request [:identity :client-id] "api")
                analysis (prp/submit-policy resource-class policy-json author "Updated via API")]
            (response/success-response {:message "Policy updated successfully"
                                        :resourceClass resource-class
                                        :validation {:warnings (:warnings analysis)}}))
          (catch clojure.lang.ExceptionInfo e
            (policy-exception->response e "INVALID_POLICY_SAFETY" "Failed to update policy: "))
          (catch Exception e
            (log/error e "Error updating policy")
            (response/error-response "UPDATE_POLICY_ERROR"
                                     (str "Failed to update policy: " (.getMessage e))
                                     500)))))))

(defn delete-policy
  [resource-class]
  (log/debug "Deleting policy for" resource-class)
  (try
    (if (prp/delete-policy resource-class)
      (response/success-response {:message "Policy deleted successfully"
                                  :resourceClass resource-class})
      (response/error-response "POLICY_NOT_FOUND"
                               (str "Policy for resource class " resource-class " not found")
                               404))
    (catch Exception e
      (log/error e "Error deleting policy")
      (response/error-response "DELETE_POLICY_ERROR"
                               (str "Failed to delete policy: " (.getMessage e))
                               500))))

(defn import-yaml-policies
  [request]
  (log/debug "Importing YAML policy")
  (try
    (let [yaml-content (slurp (:body request))
          result (policy-yaml/import-yaml-policy! yaml-content)]
      (response/created-response result))
    (catch Exception e
      (log/error e "Error importing YAML policy")
      (response/error-response "IMPORT_YAML_ERROR"
                               (str "Failed to import YAML policy: " (.getMessage e))
                               400))))

(defn list-policy-versions
  [resource-class]
  (log/debug "Listing versions for" resource-class)
  (try
    (response/success-response (mapv version-record->api (pv/list-versions resource-class)))
    (catch Exception e
      (log/error e "Error listing policy versions")
      (response/error-response "VERSIONS_ERROR"
                               (str "Failed to list versions: " (.getMessage e)) 500))))

(defn get-policy-version
  [resource-class version]
  (log/debug "Getting version" version "for" resource-class)
  (try
    (if-let [record (pv/get-version-details resource-class (Long/parseLong (str version)))]
      (response/success-response (version-record->api (attach-source-analysis record)))
      (response/error-response "VERSION_NOT_FOUND"
                               (str "Version " version " not found for " resource-class) 404))
    (catch Exception e
      (log/error e "Error getting policy version")
      (response/error-response "VERSION_ERROR"
                               (str "Failed to get version: " (.getMessage e)) 500))))

(defn diff-policy-versions
  [resource-class from-v to-v]
  (log/debug "Diffing versions" from-v "->" to-v "for" resource-class)
  (try
    (if-let [d (pv/diff-versions resource-class
                                 (Long/parseLong (str from-v))
                                 (Long/parseLong (str to-v)))]
      (response/success-response d)
      (response/error-response "DIFF_NOT_FOUND"
                               "One or both versions not found" 404))
    (catch Exception e
      (log/error e "Error diffing policy versions")
      (response/error-response "DIFF_ERROR"
                               (str "Failed to diff versions: " (.getMessage e)) 500))))

(defn analyze-policy-impact
  [resource-class request]
  (log/debug "Analyzing policy impact for" resource-class)
  (let [body-or-response (require-body request)]
    (if (response-map? body-or-response)
      body-or-response
      (try
        (let [body (assoc body-or-response :resourceClass resource-class)
              analysis (policy-impact/analyze-impact request body)
              author (get-in request [:identity :client-id] "api")
              analysis-id (try
                            (impact-history/save-analysis! resource-class body analysis author)
                            (catch Exception e
                              (log/error e "Error persisting policy impact analysis")
                              nil))
              result (cond-> analysis analysis-id (assoc :analysisId analysis-id))]
          (response/success-response result))
        (catch clojure.lang.ExceptionInfo e
          (policy-exception->response e "POLICY_IMPACT_ERROR" "Failed to analyze policy impact: "))
        (catch Exception e
          (log/error e "Error analyzing policy impact")
          (response/error-response "POLICY_IMPACT_ERROR"
                                   (str "Failed to analyze policy impact: " (.getMessage e))
                                   500))))))

(defn list-policy-impact-history
  [resource-class]
  (log/debug "Listing policy impact history for" resource-class)
  (try
    (response/success-response (mapv attach-deployed-versions (impact-history/list-analyses resource-class)))
    (catch Exception e
      (log/error e "Error listing policy impact history")
      (response/error-response "POLICY_IMPACT_HISTORY_ERROR"
                               (str "Failed to list impact history: " (.getMessage e))
                               500))))

(defn get-policy-impact-history-entry
  [resource-class analysis-id]
  (log/debug "Getting policy impact history entry" analysis-id "for" resource-class)
  (try
    (if-let [entry (impact-history/get-analysis resource-class (Long/parseLong (str analysis-id)))]
      (response/success-response (attach-deployed-versions entry))
      (response/error-response "POLICY_IMPACT_HISTORY_NOT_FOUND"
                               (str "Impact analysis " analysis-id " not found for " resource-class)
                               404))
    (catch Exception e
      (log/error e "Error getting policy impact history entry")
      (response/error-response "POLICY_IMPACT_HISTORY_ERROR"
                               (str "Failed to get impact history entry: " (.getMessage e))
                               500))))


(defn update-policy-impact-review
  [resource-class analysis-id request]
  (log/debug "Updating policy impact review" analysis-id "for" resource-class)
  (let [body-or-response (require-body request)]
    (if (response-map? body-or-response)
      body-or-response
      (try
        (let [review-status (:status body-or-response)
              reviewer (or (get-in request [:identity :client-id]) (:reviewedBy body-or-response) "api")
              review-note (:reviewNote body-or-response)
              updated (impact-history/update-review! resource-class (Long/parseLong (str analysis-id))
                                                   {:status review-status
                                                    :reviewedBy reviewer
                                                    :reviewNote review-note})]
          (if updated
            (response/success-response updated)
            (response/error-response "POLICY_IMPACT_HISTORY_NOT_FOUND"
                                     (str "Impact analysis " analysis-id " not found for " resource-class)
                                     404)))
        (catch clojure.lang.ExceptionInfo e
          (policy-exception->response e "POLICY_IMPACT_REVIEW_ERROR" "Failed to update impact review: "))
        (catch Exception e
          (log/error e "Error updating policy impact review")
          (response/error-response "POLICY_IMPACT_REVIEW_ERROR"
                                   (str "Failed to update impact review: " (.getMessage e))
                                   500))))))

(defn rollout-policy-impact-preview
  [resource-class analysis-id request]
  (log/info "Promoting policy impact preview" analysis-id "for" resource-class)
  (try
    (let [analysis-key (Long/parseLong (str analysis-id))
          entry (impact-history/get-analysis resource-class analysis-key)
          stored-candidate-policy (:candidatePolicy entry)]
      (cond
        (nil? entry)
        (response/error-response "POLICY_IMPACT_HISTORY_NOT_FOUND"
                                 (str "Impact analysis " analysis-id " not found for " resource-class)
                                 404)

        (not= "approved" (:reviewStatus entry))
        (response/error-response "POLICY_IMPACT_NOT_APPROVED"
                                 (str "Impact analysis " analysis-id " must be approved before rollout")
                                 409)

        (= "deployed" (:rolloutStatus entry))
        (response/error-response "POLICY_IMPACT_ALREADY_DEPLOYED"
                                 (str "Impact analysis " analysis-id " has already been deployed")
                                 409)

        (and (nil? stored-candidate-policy)
             (nil? (:candidateVersion entry)))
        (response/error-response "POLICY_IMPACT_NOT_PROMOTABLE"
                                 (str "Impact analysis " analysis-id " does not contain a deployable candidate policy")
                                 409)

        :else
        (if-let [candidate-policy (or stored-candidate-policy
                                      (when-let [candidate-version (:candidateVersion entry)]
                                        (pv/get-version resource-class candidate-version)))]
          (let [author (get-in request [:identity :client-id] "api")
                promoted-from (if stored-candidate-policy
                                "stored candidate policy"
                                (str "candidate version " (:candidateVersion entry)))
                comment (str "Promoted from impact analysis " analysis-id
                             " using " promoted-from)
                candidate-policy-json (json/write-value-as-string candidate-policy)]
            (prp/submit-policy resource-class candidate-policy-json author comment)
            (let [new-version (pv/latest-version-number resource-class)
                  version-link (pv/annotate-version! resource-class new-version
                                                     {:sourceAnalysisId analysis-key
                                                      :deploymentKind "impact_rollout"
                                                      :sourceCandidateVersion (:candidateVersion entry)})
                  rollout-entry (impact-history/mark-deployed! resource-class analysis-key
                                                               {:deployedVersion new-version
                                                                :deployedBy author})]
              (response/success-response (cond-> {:resourceClass resource-class
                                                  :analysisId analysis-key
                                                  :newVersion new-version
                                                  :rolloutStatus "deployed"
                                                  :versionLink version-link
                                                  :history rollout-entry}
                                           (:candidateVersion entry)
                                           (assoc :sourceCandidateVersion (:candidateVersion entry))
                                           stored-candidate-policy
                                           (assoc :sourceCandidatePolicy true)))))
          (response/error-response "CANDIDATE_VERSION_NOT_FOUND"
                                   (str "Candidate version " (:candidateVersion entry)
                                        " not found for " resource-class)
                                   404))))
    (catch clojure.lang.ExceptionInfo e
      (policy-exception->response e "POLICY_IMPACT_ROLLOUT_ERROR" "Failed to rollout impact preview: "))
    (catch Exception e
      (log/error e "Error rolling out policy impact preview")
      (response/error-response "POLICY_IMPACT_ROLLOUT_ERROR"
                               (str "Failed to rollout impact preview: " (.getMessage e))
                               500))))

(defn rollback-policy
  [resource-class version request]
  (log/info "Rolling back" resource-class "to version" version)
  (try
    (let [v (Long/parseLong (str version))
          pol (pv/get-version resource-class v)]
      (if-not pol
        (response/error-response "VERSION_NOT_FOUND"
                                 (str "Version " v " not found for " resource-class) 404)
        (let [author (get-in request [:identity :client-id] "rollback")
              comment (str "Rollback to version " v)
              pol-str (json/write-value-as-string pol)]
          (prp/submit-policy resource-class pol-str author comment)
          (response/success-response {:resourceClass resource-class
                                      :rolledBackTo v
                                      :newVersion (pv/latest-version-number resource-class)}))))
    (catch Exception e
      (log/error e "Error rolling back policy")
      (response/error-response "ROLLBACK_ERROR"
                               (str "Failed to rollback: " (.getMessage e)) 500))))

(defn get-cache-stats
  []
  (log/debug "Processing get cache stats request")
  (try
    (let [stats (cache/get-cache-stats)]
      (response/success-response stats))
    (catch Exception e
      (log/error e "Error getting cache stats")
      (response/error-response "CACHE_STATS_ERROR"
                               (str "Failed to get cache stats: " (.getMessage e))
                               500))))

(defn clear-cache
  []
  (log/debug "Processing clear cache request")
  (try
    (cache/clear-all-caches)
    (response/success-response {:status "cleared"})
    (catch Exception e
      (log/error e "Error clearing cache")
      (response/error-response "CACHE_CLEAR_ERROR"
                               (str "Failed to clear cache: " (.getMessage e))
                               500))))

(defn invalidate-cache-entry
  [cache-type cache-key]
  (log/debug "Processing cache invalidation request" cache-type cache-key)
  (try
    (cache/invalidate (keyword cache-type) cache-key)
    (response/success-response {:status "cleared"
                                :type cache-type
                                :key cache-key})
    (catch Exception e
      (log/error e "Error invalidating cache entry")
      (response/error-response "CACHE_INVALIDATE_ERROR"
                               (str "Failed to invalidate cache entry: " (.getMessage e))
                               500))))









