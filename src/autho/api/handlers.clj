(ns autho.api.handlers
  "Reusable API request handlers for authorization endpoints.
   Wraps existing PDP/PRP functions with RESTful conventions."
  (:require [autho.pdp :as pdp]
            [autho.prp :as prp]
            [autho.api.response :as response]
            [autho.api.pagination :as pagination]
            [autho.validation :as validation]
            [autho.local-cache :as cache]
            [autho.policy-yaml :as policy-yaml]
            [jsonista.core :as json]
            [clojure.tools.logging :as log])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.api.handlers"))

;; =============================================================================
;; Request Parsing
;; =============================================================================

(defn parse-json-body
  "Parse JSON request body safely.
   Returns nil if parsing fails."
  [request]
  (try
    (when-let [body (:body request)]
      (json/read-value (slurp body) json/keyword-keys-object-mapper))
    (catch Exception e
      (log/error e "Failed to parse request body")
      nil)))

(defn require-body
  "Validate request body exists and return parsed JSON.
   Returns error response if body is missing or invalid."
  [request]
  (if-let [parsed (parse-json-body request)]
    parsed
    (response/error-response "INVALID_REQUEST_BODY"
                             "Request body must be valid JSON"
                             400)))

;; =============================================================================
;; Authorization Handlers
;; =============================================================================

(defn is-authorized
  "Handle authorization decision request.
   POST /v1/authz/decisions
   Body: {:subject {...} :resource {...} :action \"...\"}"
  [request]
  (log/debug "Processing authorization decision request")
  (if-let [body (require-body request)]
    (try
      (validation/validate-and-sanitize-request body)
      (let [result (pdp/isAuthorized request body)]
        (response/success-response result))
      (catch Exception e
        (let [error-data (ex-data e)]
          (if (= :validation-error (:type error-data))
            (validation/validation-error->response e)
            (do
              (log/error e "Error processing authorization request")
              (response/error-response "AUTHORIZATION_ERROR"
                                      (str "Failed to process authorization: " (.getMessage e))
                                      500))))))
    (response/error-response "INVALID_REQUEST_BODY"
                            "Request body must be valid JSON"
                            400)))

(defn who-authorized
  "Handle request to list authorized subjects for a resource.
   POST /v1/authz/subjects
   Body: {:resource {...} :action \"...\"}"
  [request]
  (log/debug "Processing who authorized request")
  (if-let [body (require-body request)]
    (try
      (validation/validate-and-sanitize-request body)
      (let [result (pdp/whoAuthorized request body)]
        (response/success-response result))
      (catch Exception e
        (log/error e "Error processing who authorized request")
        (response/error-response "WHO_AUTHORIZED_ERROR"
                                (str "Failed to query authorized subjects: " (.getMessage e))
                                500)))
    (response/error-response "INVALID_REQUEST_BODY"
                            "Request body must be valid JSON"
                            400)))

(defn what-authorized
  "Handle request to list permissions for a subject.
   POST /v1/authz/permissions
   Body: {:subject {...} :resource {:class \"...\"}}"
  [request]
  (log/debug "Processing what authorized request")
  (if-let [body (require-body request)]
    (try
      (validation/validate-and-sanitize-request body)
      (let [result (pdp/whatAuthorized request body)]
        (response/success-response result))
      (catch Exception e
        (log/error e "Error processing what authorized request")
        (response/error-response "WHAT_AUTHORIZED_ERROR"
                                (str "Failed to query permissions: " (.getMessage e))
                                500)))
    (response/error-response "INVALID_REQUEST_BODY"
                            "Request body must be valid JSON"
                            400)))

(defn explain-decision
  "Handle request to explain authorization decision.
   POST /v1/authz/explain
   Body: {:subject {...} :resource {...} :action \"...\"}"
  [request]
  (log/debug "Processing explain decision request")
  (if-let [body (require-body request)]
    (try
      (validation/validate-and-sanitize-request body)
      (let [result (pdp/explain request body)]
        (response/success-response result))
      (catch Exception e
        (log/error e "Error processing explain request")
        (response/error-response "EXPLAIN_ERROR"
                                (str "Failed to explain decision: " (.getMessage e))
                                500)))
    (response/error-response "INVALID_REQUEST_BODY"
                            "Request body must be valid JSON"
                            400)))

(def max-batch-size
  (try (Long/parseLong (or (System/getenv "MAX_BATCH_SIZE") "100"))
       (catch NumberFormatException _ 100)))

(defn batch-decisions
  "Handle batch authorization decision request.
   POST /v1/authz/batch
   Body: {:requests [{:subject {...} :resource {...} :action \"...\"} ...]}
   Evaluates all requests in parallel via pmap (capped at MAX_BATCH_SIZE, default 100)."
  [request]
  (log/debug "Processing batch authorization request")
  (if-let [body (require-body request)]
    (try
      (let [requests (:requests body)]
        (cond
          (not (and (vector? requests) (not (empty? requests))))
          (response/error-response "INVALID_BATCH_REQUEST"
                                   "Request must contain non-empty 'requests' array"
                                   400)

          (> (count requests) max-batch-size)
          (response/error-response "BATCH_TOO_LARGE"
                                   (str "Batch size " (count requests)
                                        " exceeds maximum of " max-batch-size)
                                   400)

          :else
          (let [indexed (map-indexed vector requests)
                results (pmap
                          (fn [[idx req]]
                            (try
                              (validation/validate-and-sanitize-request req)
                              {:request-id idx
                               :decision   (pdp/isAuthorized request req)}
                              (catch Exception e
                                (let [ed (ex-data e)]
                                  {:request-id idx
                                   :error      (if (= :validation-error (:type ed))
                                                 (:message ed)
                                                 (.getMessage e))}))))
                          indexed)]
            (response/success-response {:results (vec results)
                                        :count   (count results)}))))
      (catch Exception e
        (log/error e "Error processing batch authorization request")
        (response/error-response "BATCH_ERROR"
                                 (str "Failed to process batch request: " (.getMessage e))
                                 500)))
    (response/error-response "INVALID_REQUEST_BODY"
                             "Request body must be valid JSON"
                             400)))

;; =============================================================================
;; Policy Handlers
;; =============================================================================

(defn import-yaml-policies
  "Handle YAML policy import.
   POST /v1/policies/import
   Body: YAML string (Content-Type: text/yaml or application/yaml)"
  [request]
  (log/debug "Processing YAML policy import request")
  (try
    (let [yaml-str (slurp (:body request))]
      (if (or (nil? yaml-str) (clojure.string/blank? yaml-str))
        (response/error-response "EMPTY_BODY" "Request body must contain a YAML policy" 400)
        (let [result (policy-yaml/import-yaml-policy! yaml-str)]
          (if (:ok result)
            (response/success-response result 201)
            (response/error-response "YAML_IMPORT_FAILED" (:error result) 400)))))
    (catch Exception e
      (log/error e "Error importing YAML policy")
      (response/error-response "YAML_IMPORT_ERROR"
                              (str "Failed to import YAML policy: " (.getMessage e))
                              500))))

(defn list-policies
  "Handle request to list all policies with pagination.
   GET /v1/policies?page=1&per-page=20&sort=resourceClass&order=asc"
  [request]
  (log/debug "Processing list policies request")
  (try
    (let [params (:params request)
          page-params (pagination/parse-pagination-params params)
          all-policies (prp/get-policies)
          policy-vector (if (map? all-policies)
                          (vec (vals all-policies))
                          (vec all-policies))
          paginated (pagination/paginate policy-vector page-params)]
      (response/paginated-response
        (:items paginated)
        (:page paginated)
        (:per-page paginated)
        (:total paginated)))
    (catch Exception e
      (log/error e "Error listing policies")
      (response/error-response "POLICIES_ERROR"
                              (str "Failed to list policies: " (.getMessage e))
                              500))))

(defn get-policy
  "Handle request to get a specific policy.
   GET /v1/policies/:resource-class"
  [resource-class]
  (log/debug "Processing get policy request for" resource-class)
  (try
    (if-let [policy (prp/getPolicy resource-class nil)]
      (response/success-response policy)
      (response/error-response "POLICY_NOT_FOUND"
                              (str "Policy not found for resource class: " resource-class)
                              404))
    (catch Exception e
      (log/error e "Error getting policy")
      (response/error-response "POLICY_ERROR"
                              (str "Failed to get policy: " (.getMessage e))
                              500))))

(defn create-policy
  "Handle request to create a new policy.
   POST /v1/policies
   Body: policy definition"
  [request]
  (log/debug "Processing create policy request")
  (if-let [body (require-body request)]
    (try
      (let [resource-class (:resourceClass body)]
        (if resource-class
          (do
            (prp/submit-policy resource-class (json/write-value-as-string body))
            (response/created-response body (str "/v1/policies/" resource-class)))
          (response/error-response "INVALID_POLICY"
                                  "Policy must include 'resourceClass' field"
                                  400)))
      (catch Exception e
        (log/error e "Error creating policy")
        (response/error-response "POLICY_CREATE_ERROR"
                                (str "Failed to create policy: " (.getMessage e))
                                500)))
    (response/error-response "INVALID_REQUEST_BODY"
                            "Request body must be valid JSON"
                            400)))

(defn update-policy
  "Handle request to update a policy.
   PUT /v1/policies/:resource-class
   Body: policy definition"
  [resource-class request]
  (log/debug "Processing update policy request for" resource-class)
  (if-let [body (require-body request)]
    (try
      (prp/submit-policy resource-class (json/write-value-as-string body))
      (response/success-response body)
      (catch Exception e
        (log/error e "Error updating policy")
        (response/error-response "POLICY_UPDATE_ERROR"
                                (str "Failed to update policy: " (.getMessage e))
                                500)))
    (response/error-response "INVALID_REQUEST_BODY"
                            "Request body must be valid JSON"
                            400)))

(defn delete-policy
  "Handle request to delete a policy.
   DELETE /v1/policies/:resource-class"
  [resource-class]
  (log/debug "Processing delete policy request for" resource-class)
  (try
    (prp/delete-policy resource-class)
    (response/no-content-response)
    (catch Exception e
      (log/error e "Error deleting policy")
      (response/error-response "POLICY_DELETE_ERROR"
                              (str "Failed to delete policy: " (.getMessage e))
                              500))))

;; =============================================================================
;; Cache Handlers
;; =============================================================================

(defn get-cache-stats
  "Handle request to get cache statistics.
   GET /v1/cache/stats"
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
  "Handle request to clear all caches.
   DELETE /v1/cache"
  []
  (log/info "Processing clear cache request")
  (try
    (cache/clear-all-caches)
    (response/success-response {:status "cleared"})
    (catch Exception e
      (log/error e "Error clearing cache")
      (response/error-response "CACHE_CLEAR_ERROR"
                              (str "Failed to clear cache: " (.getMessage e))
                              500))))

(defn invalidate-cache-entry
  "Handle request to invalidate a specific cache entry.
   DELETE /v1/cache/:type/:key"
  [cache-type cache-key]
  (log/debug "Processing invalidate cache request for" cache-type ":" cache-key)
  (try
    (cache/invalidate (keyword cache-type) cache-key)
    (response/success-response {:status "cleared"
                                :type cache-type
                                :key cache-key})
    (catch Exception e
      (log/error e "Error invalidating cache entry")
      (response/error-response "CACHE_INVALIDATE_ERROR"
                              (str "Failed to invalidate cache: " (.getMessage e))
                              500))))
