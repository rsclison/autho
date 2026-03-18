(ns autho.api.resource-handlers
  "Handlers for resource-related API endpoints.
   Provides read-only access to resources from the RocksDB/Kafka PIP."
  (:require [autho.kafka-pip :as kpip]
            [autho.api.response :as response]
            [autho.api.pagination :as pagination]
            [jsonista.core :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:import (org.slf4j LoggerFactory)
           (java.io ByteArrayInputStream)))

(defonce logger (LoggerFactory/getLogger "autho.api.resource-handlers"))

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
;; Resource Utilities
;; =============================================================================

(defn- get-all-cf-names
  "Get all column family names from the RocksDB."
  []
  (when-let [db-state @kpip/db-state]
    (keys (:cf-handles db-state))))

(defn- fetch-resources-by-class
  "Get all resources for a specific class from RocksDB."
  [class-name]
  (when-let [db-state @kpip/db-state]
    (when-let [cf-handle (get-in db-state [:cf-handles class-name])]
      ;; RocksDB doesn't have a built-in way to list all keys
      ;; We'll need to use an iterator or maintain a separate index
      ;; For now, return empty list - this would need to be implemented
      (log/warn "Resource listing not yet implemented for class:" class-name)
      [])))

(defn- get-resource-by-id
  "Get a specific resource by class and ID."
  [class-name resource-id]
  (when-let [db-state @kpip/db-state]
    (when-let [cf-handle (get-in db-state [:cf-handles class-name])]
      (when-let [value-str (kpip/db-get cf-handle resource-id)]
        (try
          (let [attrs (json/read-value value-str json/keyword-keys-object-mapper)]
            {:class class-name
             :id resource-id
             :attributes attrs})
          (catch Exception e
            (log/error e "Error parsing resource attributes for" class-name ":" resource-id)
            nil))))))

(defn- get-resource-classes
  "List all available resource classes."
  []
  (when-let [db-state @kpip/db-state]
    (let [cf-names (keys (:cf-handles db-state))]
      (map (fn [class-name]
             {:class class-name
              :description (str "Resource class: " class-name)})
           cf-names))))

;; =============================================================================
;; Resource Handlers
;; =============================================================================

(defn list-resource-classes
  "Handle request to list all resource classes.
   GET /v1/resources"
  []
  (log/debug "Processing list resource classes request")
  (try
    (let [classes (get-resource-classes)]
      (if classes
        (response/success-response classes)
        (response/error-response "DB_NOT_INITIALIZED"
                                "Database not initialized. No resource classes available."
                                503)))
    (catch Exception e
      (log/error e "Error listing resource classes")
      (response/error-response "RESOURCES_LIST_ERROR"
                              (str "Failed to list resource classes: " (.getMessage e))
                              500))))

(defn list-resources-by-class
  "Handle request to list all resources of a specific class.
   GET /v1/resources/:class?page=1&per-page=20"
  [class-name request]
  (log/debug "Processing list resources request for class" class-name)
  (try
    (let [params (:params request)
          page-params (pagination/parse-pagination-params params)
          all-resources (fetch-resources-by-class class-name)]
      (if (nil? all-resources)
        (response/error-response "RESOURCE_CLASS_NOT_FOUND"
                                (str "Resource class not found: " class-name)
                                404)
        (let [paginated (pagination/paginate all-resources page-params)]
          (response/paginated-response
            (:items paginated)
            (:page paginated)
            (:per-page paginated)
            (:total paginated)))))
    (catch Exception e
      (log/error e "Error listing resources")
      (response/error-response "RESOURCES_LIST_ERROR"
                              (str "Failed to list resources: " (.getMessage e))
                              500))))

(defn get-resource
  "Handle request to get a specific resource by class and ID.
   GET /v1/resources/:class/:id"
  [class-name resource-id]
  (log/debug "Processing get resource request for" class-name ":" resource-id)
  (try
    (if-let [resource (get-resource-by-id class-name resource-id)]
      (response/success-response resource)
      (response/error-response "RESOURCE_NOT_FOUND"
                              (str "Resource not found: " class-name ":" resource-id)
                              404))
    (catch Exception e
      (log/error e "Error getting resource")
      (response/error-response "RESOURCE_GET_ERROR"
                              (str "Failed to get resource: " (.getMessage e))
                              500))))

(defn search-resources-handler
  "Handle request to search resources by attributes.
   GET /v1/resources/search?class=Document&q=search-term
   or
   GET /v1/resources/search?class=Document&owner=user123"
  [request]
  (log/debug "Processing search resources request")
  (try
    (let [params (:params request)
          class-name (get params "class")
          search-term (get params "q")
          page-params (pagination/parse-pagination-params params)]

      (if (nil? class-name)
        (response/error-response "MISSING_RESOURCE_CLASS"
                                "Resource class parameter is required"
                                400)

        ;; Get all resources for the class and filter
        (let [all-resources (fetch-resources-by-class class-name)]
          (if (nil? all-resources)
            (response/error-response "RESOURCE_CLASS_NOT_FOUND"
                                    (str "Resource class not found: " class-name)
                                    404)

            ;; Filter resources
            (let [filtered (if search-term
                            (filter (fn [resource]
                                      (some (fn [[_ v]]
                                              (when (string? v)
                                                (str/includes? (str/lower-case v)
                                                               (str/lower-case search-term))))
                                            (:attributes resource)))
                                    all-resources)
                            ;; Attribute search
                            (let [attr-params (dissoc params "page" "per-page" "sort" "order" "filter" "q" "class")
                                  query-map (into {} (map (fn [[k v]]
                                                          [(keyword k) v])
                                                        attr-params))]
                              (if (empty? query-map)
                                all-resources
                                (filter (fn [resource]
                                          (every? (fn [[attr-name attr-value]]
                                                    (= (get-in resource [:attributes attr-name])
                                                       attr-value))
                                                  query-map))
                                        all-resources))))

                  paginated (pagination/paginate filtered page-params)]
              (response/paginated-response
                (:items paginated)
                (:page paginated)
                (:per-page paginated)
                (:total paginated)))))))
    (catch Exception e
      (log/error e "Error searching resources")
      (response/error-response "RESOURCES_SEARCH_ERROR"
                              (str "Failed to search resources: " (.getMessage e))
                              500))))

(defn batch-get-resources
  "Handle request to batch get multiple resources.
   POST /v1/resources/batch-get
   Body: {:resources [{:class \"Document\" :id \"doc1\"} ...]}"
  [request]
  (log/debug "Processing batch get resources request")
  (try
    (if-let [body (parse-json-body request)]
      (let [resources (:resources body)]
        (if (and (vector? resources) (not (empty? resources)))
          (let [results (map (fn [req]
                              (let [class (:class req)
                                    id (:id req)]
                                (if (and class id)
                                  (if-let [resource (get-resource-by-id class id)]
                                    {:class class :id id :found true :resource resource}
                                    {:class class :id id :found false})
                                  {:class (or class "nil") :id (or id "nil") :found false :error "Missing class or id"})))
                            resources)]
            (response/success-response {:results results}))
          (response/error-response "INVALID_BATCH_REQUEST"
                                  "Request must contain non-empty 'resources' array"
                                  400)))
      (response/error-response "INVALID_REQUEST_BODY"
                              "Request body must be valid JSON"
                              400))
    (catch Exception e
      (log/error e "Error batch getting resources")
      (response/error-response "RESOURCES_BATCH_GET_ERROR"
                              (str "Failed to batch get resources: " (.getMessage e))
                              500))))
