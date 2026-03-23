(ns autho.api.response
  "Standardized API response utilities with consistent format.
   Provides success and error responses with metadata support."
  (:require [jsonista.core :as json])
  (:import (java.time Instant)))
;; =============================================================================
;; Response Builders
;; =============================================================================

(defn- current-timestamp []
  (str (Instant/now)))

(defn success-response
  "Create a standardized success response.
   Parameters:
   - data: The response data
   - status: HTTP status code (default 200)
   - meta: Optional metadata map (pagination, etc.)
   - links: Optional HATEOAS links map"
  ([data]
   (success-response data 200 nil nil))
  ([data status]
   (success-response data status nil nil))
  ([data status meta]
   (success-response data status meta nil))
  ([data status meta links]
   {:status status
    :headers {"Content-Type" "application/json"
              "X-API-Version" "v1"}
    :body (json/write-value-as-string
            (cond-> {:status "success"
                     :data data
                     :timestamp (current-timestamp)}
              meta (assoc :meta meta)
              links (assoc :links links)))}))

(defn error-response
  "Create a standardized error response.
   Parameters:
   - code: Error code (e.g., VALIDATION_ERROR)
   - message: Human-readable error message
   - status: HTTP status code (default 400)
   - details: Optional error details vector"
  ([code message status]
   (error-response code message status nil))
  ([code message status details]
   {:status status
    :headers {"Content-Type" "application/json"
              "X-API-Version" "v1"}
    :body (json/write-value-as-string
            (cond-> {:error {:code code
                             :message message
                             :timestamp (current-timestamp)}}
              details (assoc-in [:error :details] details)))}))

(defn paginated-response
  "Create a paginated response with metadata.
   Parameters:
   - data: The response data (vector of items)
   - page: Current page number
   - per-page: Items per page
   - total: Total number of items
   - status: HTTP status code (default 200)"
  ([data page per-page total]
   (paginated-response data page per-page total 200))
  ([data page per-page total status]
   (let [total-pages (max 1 (int (Math/ceil (/ total per-page))))
         base-path "/v1"]
     (success-response data status
                       {:page page
                        :per-page per-page
                        :total total
                        :total-pages total-pages}
                       {:self (str base-path "?page=" page "&per-page=" per-page)
                        :next (when (< page total-pages)
                                (str base-path "?page=" (inc page) "&per-page=" per-page))
                        :prev (when (> page 1)
                                (str base-path "?page=" (dec page) "&per-page=" per-page))
                        :first (str base-path "?page=1&per-page=" per-page)
                        :last (str base-path "?page=" total-pages "&per-page=" per-page)}))))

(defn created-response
  "Create a 201 Created response for resource creation.
   Parameters:
   - data: The created resource
   - location: Location header value (URL of created resource)"
  [data location]
  {:status 201
   :headers {"Content-Type" "application/json"
             "Location" location
             "X-API-Version" "v1"}
   :body (json/write-value-as-string
           {:status "created"
            :data data
            :timestamp (current-timestamp)})})

(defn no-content-response
  "Create a 204 No Content response for successful delete/update operations."
  []
  {:status 204
   :headers {"X-API-Version" "v1"}
   :body ""})
