(ns autho.api.subject-handlers
  "Handlers for subject-related API endpoints.
   Provides read-only access to subjects from the person repository."
  (:require [autho.prp :as prp]
            [autho.api.response :as response]
            [autho.api.pagination :as pagination]
            [jsonista.core :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:import (org.slf4j LoggerFactory)
           (java.io ByteArrayInputStream)))

(defonce logger (LoggerFactory/getLogger "autho.api.subject-handlers"))

(defn- parse-json-body
  "Parse JSON request body safely.
   Returns nil if parsing fails."
  [request]
  (try
    (when-let [body (:body request)]
      (json/read-value (slurp body) json/keyword-keys-object-mapper))
    (catch Exception e
      (log/error e "Failed to parse request body")
      nil)))

;; =============================================================================
;; Subject Utilities
;; =============================================================================

(defn- get-subjects-list
  "Get all subjects from the person repository."
  []
  @prp/personSingleton)

(defn- find-subject-by-id
  "Find a subject by ID in the person repository."
  [subject-id]
  (first (filter (fn [subject]
                   (= (:id subject) subject-id))
                 (get-subjects-list))))

(defn- search-subjects
  "Search subjects by attribute values.
   query-map: map of attribute name -> value to match"
  [query-map]
  (filter (fn [subject]
            (every? (fn [[attr-name attr-value]]
                      (let [subject-value (get subject attr-name)]
                        (= subject-value attr-value)))
                    query-map))
          (get-subjects-list)))

(defn- text-search-subjects
  "Search subjects by text across all attributes."
  [search-term]
  (let [term (str/lower-case search-term)]
    (filter (fn [subject]
              (some (fn [[_ v]]
                      (when (string? v)
                        (str/includes? (str/lower-case v) term)))
                    subject))
            (get-subjects-list))))

;; =============================================================================
;; Subject Handlers
;; =============================================================================

(defn list-subjects
  "Handle request to list all subjects with pagination.
   GET /v1/subjects?page=1&per-page=20&sort=id&order=asc"
  [request]
  (log/debug "Processing list subjects request")
  (try
    (let [params (:params request)
          page-params (pagination/parse-pagination-params params)
          all-subjects (get-subjects-list)
          paginated (pagination/paginate all-subjects page-params)]
      (response/paginated-response
        (:items paginated)
        (:page paginated)
        (:per-page paginated)
        (:total paginated)))
    (catch Exception e
      (log/error e "Error listing subjects")
      (response/error-response "SUBJECTS_LIST_ERROR"
                              (str "Failed to list subjects: " (.getMessage e))
                              500))))

(defn get-subject
  "Handle request to get a specific subject by ID.
   GET /v1/subjects/:id"
  [subject-id]
  (log/debug "Processing get subject request for" subject-id)
  (try
    (if-let [subject (find-subject-by-id subject-id)]
      (response/success-response subject)
      (response/error-response "SUBJECT_NOT_FOUND"
                              (str "Subject not found: " subject-id)
                              404))
    (catch Exception e
      (log/error e "Error getting subject")
      (response/error-response "SUBJECT_GET_ERROR"
                              (str "Failed to get subject: " (.getMessage e))
                              500))))

(defn search-subjects-handler
  "Handle request to search subjects by attributes.
   GET /v1/subjects/search?q=search-term
   or
   GET /v1/subjects/search?role=admin&department=engineering"
  [request]
  (log/debug "Processing search subjects request")
  (try
    (let [params (:params request)
          search-term (get params "q")
          page-params (pagination/parse-pagination-params params)]

      ;; Determine search type: text search or attribute search
      (let [all-subjects (if search-term
                           (text-search-subjects search-term)
                           ;; Attribute search: filter out pagination params
                           (let [attr-params (dissoc params "page" "per-page" "sort" "order" "filter" "q")
                                     query-map (into {} (map (fn [[k v]]
                                                             [(keyword k) v])
                                                           attr-params))]
                             (if (empty? query-map)
                               (get-subjects-list)
                               (search-subjects query-map))))

            paginated (pagination/paginate all-subjects page-params)]
        (response/paginated-response
          (:items paginated)
          (:page paginated)
          (:per-page paginated)
          (:total paginated))))
    (catch Exception e
      (log/error e "Error searching subjects")
      (response/error-response "SUBJECTS_SEARCH_ERROR"
                              (str "Failed to search subjects: " (.getMessage e))
                              500))))

(defn batch-get-subjects
  "Handle request to batch get multiple subjects.
   POST /v1/subjects/batch-get
   Body: {:ids [\"user1\" \"user2\" \"user3\"]}"
  [request]
  (log/debug "Processing batch get subjects request")
  (try
    (if-let [body (parse-json-body request)]
      (let [ids (:ids body)]
        (if (and (vector? ids) (not (empty? ids)))
          (let [subjects (map (fn [id]
                               (if-let [subject (find-subject-by-id id)]
                                 {:id id :found true :subject subject}
                                 {:id id :found false}))
                             ids)]
            (response/success-response {:results subjects}))
          (response/error-response "INVALID_BATCH_REQUEST"
                                  "Request must contain non-empty 'ids' array"
                                  400)))
      (response/error-response "INVALID_REQUEST_BODY"
                              "Request body must be valid JSON"
                              400))
    (catch Exception e
      (log/error e "Error batch getting subjects")
      (response/error-response "SUBJECTS_BATCH_GET_ERROR"
                              (str "Failed to batch get subjects: " (.getMessage e))
                              500))))
