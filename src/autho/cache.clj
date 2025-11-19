(ns autho.cache
  (:require [clojure.core.cache.wrapped :as cc]
            [clojure.data.json :as json])
  (:import (org.slf4j LoggerFactory)
           (java.security MessageDigest)
           (java.util Base64)))

(defonce logger (LoggerFactory/getLogger "autho.cache"))

;; ===== Configuration =====

(defn cache-enabled?
  "Check if cache features are enabled via CACHE_ENABLED environment variable.
  Defaults to true for backward compatibility."
  []
  (if-let [env-enabled (System/getenv "CACHE_ENABLED")]
    (Boolean/parseBoolean env-enabled)
    true))

(defn request-cache-enabled?
  "Check if request cache is enabled."
  []
  (and (cache-enabled?)
       (if-let [env-enabled (System/getenv "CACHE_REQUEST_ENABLED")]
         (Boolean/parseBoolean env-enabled)
         true)))

(defn pip-cache-enabled?
  "Check if PIP cache is enabled."
  []
  (and (cache-enabled?)
       (if-let [env-enabled (System/getenv "CACHE_PIP_ENABLED")]
         (Boolean/parseBoolean env-enabled)
         true)))

(defn get-env-int
  "Get environment variable as integer with default value."
  [env-var default]
  (if-let [env-val (System/getenv env-var)]
    (try
      (Integer/parseInt env-val)
      (catch Exception e
        (.warn logger "Invalid integer for {}: {}, using default: {}" env-var env-val default)
        default))
    default))

;; Cache TTL and size configuration
(def resource-cache-ttl (get-env-int "CACHE_RESOURCE_TTL_MS" 10000))
(def subject-cache-threshold (get-env-int "CACHE_SUBJECT_THRESHOLD" 100))
(def request-cache-ttl (get-env-int "CACHE_REQUEST_TTL_MS" 300000))  ; 5 minutes
(def request-cache-max-size (get-env-int "CACHE_REQUEST_MAX_SIZE" 10000))
(def pip-cache-ttl (get-env-int "CACHE_PIP_TTL_MS" 600000))  ; 10 minutes
(def pip-cache-max-size (get-env-int "CACHE_PIP_MAX_SIZE" 5000))

;; ===== Cache Instances =====

(def resource-cache (cc/ttl-cache-factory {} :ttl resource-cache-ttl))
(def subject-cache (cc/lru-cache-factory {} :threshold subject-cache-threshold))
(def request-cache (atom (cc/ttl-cache-factory {} :ttl request-cache-ttl)))
(def pip-cache (atom (cc/ttl-cache-factory {} :ttl pip-cache-ttl)))

;; ===== Cache Statistics =====

(def stats (atom {:request {:hits 0 :misses 0 :evictions 0}
                  :pip {:hits 0 :misses 0 :evictions 0}
                  :resource {:hits 0 :misses 0}
                  :subject {:hits 0 :misses 0}}))

(defn record-hit! [cache-type]
  (swap! stats update-in [cache-type :hits] inc))

(defn record-miss! [cache-type]
  (swap! stats update-in [cache-type :misses] inc))

(defn record-eviction! [cache-type]
  (swap! stats update-in [cache-type :evictions] inc))

(defn get-stats []
  (let [current-stats @stats
        request-cache-size (count @request-cache)
        pip-cache-size (count @pip-cache)
        resource-cache-size (count @resource-cache)
        subject-cache-size (count @subject-cache)]
    (assoc current-stats
           :cache-sizes {:request request-cache-size
                         :pip pip-cache-size
                         :resource resource-cache-size
                         :subject subject-cache-size}
           :config {:enabled (cache-enabled?)
                    :request-enabled (request-cache-enabled?)
                    :pip-enabled (pip-cache-enabled?)
                    :request-ttl-ms request-cache-ttl
                    :pip-ttl-ms pip-cache-ttl
                    :resource-ttl-ms resource-cache-ttl
                    :subject-threshold subject-cache-threshold})))

(defn clear-all-caches! []
  "Clears all caches and resets statistics."
  (.info logger "Clearing all caches")
  (reset! request-cache (cc/ttl-cache-factory {} :ttl request-cache-ttl))
  (reset! pip-cache (cc/ttl-cache-factory {} :ttl pip-cache-ttl))
  (cc/evict resource-cache (keys @resource-cache))
  (cc/evict subject-cache (keys @subject-cache))
  (reset! stats {:request {:hits 0 :misses 0 :evictions 0}
                 :pip {:hits 0 :misses 0 :evictions 0}
                 :resource {:hits 0 :misses 0}
                 :subject {:hits 0 :misses 0}})
  true)

(defn clear-cache! [cache-type]
  "Clears a specific cache type: :request, :pip, :resource, or :subject"
  (.info logger "Clearing {} cache" cache-type)
  (case cache-type
    :request (reset! request-cache (cc/ttl-cache-factory {} :ttl request-cache-ttl))
    :pip (reset! pip-cache (cc/ttl-cache-factory {} :ttl pip-cache-ttl))
    :resource (cc/evict resource-cache (keys @resource-cache))
    :subject (cc/evict subject-cache (keys @subject-cache))
    (.warn logger "Unknown cache type: {}" cache-type)))

;; ===== Utility Functions =====

(defn sha256-hash
  "Generate SHA-256 hash of a value as base64 string."
  [value]
  (let [md (MessageDigest/getInstance "SHA-256")
        json-str (json/write-str value)
        hash-bytes (.digest md (.getBytes json-str "UTF-8"))
        encoder (Base64/getEncoder)]
    (.encodeToString encoder hash-bytes)))


;; merge 2 entities (maps)
;; used to merge a resource (or a subject) with the same entity from the cache
(defn mergeEntities [ent1 ent2]
  (.debug logger "Merging entities: {} // {}" ent1 ent2)
  (merge ent1 ent2)
  )

;; try to merge the entity and the same entity in the cache
;; return the merged entity
;; Uses atomic swap! to prevent race conditions
(defn mergeEntityWithCache [ent cache]
  (if-let [ent-id (:id ent)]
    (let [result (atom nil)]
      (swap! cache
             (fn [current-cache]
               (let [cached-entity (get current-cache ent-id)
                     merged (mergeEntities ent cached-entity)]
                 (reset! result merged)
                 (assoc current-cache ent-id merged))))
      @result)
    (do
      (.warn logger "Entity without ID cannot be cached: {}" ent)
      ent)))

(defn getCachedSubject [id]
  (let [result (cc/lookup subject-cache id)]
    (if result
      (record-hit! :subject)
      (record-miss! :subject))
    result))

(defn getCachedResource [id]
  (let [result (cc/lookup resource-cache id)]
    (if result
      (record-hit! :resource)
      (record-miss! :resource))
    result))

;; ===== Request Cache =====

(defn make-request-cache-key
  "Generate cache key for authorization request.
  Key components: subject-id, resource-class, resource-id, operation, context (optional), page, pageSize"
  [request]
  (let [subject-id (get-in request [:subject :id])
        resource-class (get-in request [:resource :class])
        resource-id (get-in request [:resource :id])
        operation (:operation request)
        context (:context request)
        page (:page request)
        pageSize (:pageSize request)]
    (sha256-hash {:subject-id subject-id
                  :resource-class resource-class
                  :resource-id resource-id
                  :operation operation
                  :context context
                  :page page
                  :pageSize pageSize})))

(defn get-cached-request
  "Get cached authorization result for a request."
  [request]
  (when (request-cache-enabled?)
    (let [cache-key (make-request-cache-key request)
          result (cc/lookup @request-cache cache-key)]
      (if result
        (do
          (record-hit! :request)
          (.debug logger "Request cache HIT for key: {}" cache-key))
        (do
          (record-miss! :request)
          (.debug logger "Request cache MISS for key: {}" cache-key)))
      result)))

(defn cache-request!
  "Cache authorization result for a request."
  [request result]
  (when (request-cache-enabled?)
    (let [cache-key (make-request-cache-key request)]
      (.debug logger "Caching request result for key: {}" cache-key)
      (swap! request-cache
             (fn [current-cache]
               (let [new-cache (cc/miss current-cache cache-key result)]
                 ;; Check if cache size exceeds max, evict oldest if needed
                 (if (> (count new-cache) request-cache-max-size)
                   (do
                     (record-eviction! :request)
                     (.warn logger "Request cache size exceeded, evicting entries")
                     ;; Reset to empty cache with TTL
                     (cc/ttl-cache-factory {cache-key result} :ttl request-cache-ttl))
                   new-cache))))
      result)))

;; ===== PIP Cache =====

(defn make-pip-cache-key
  "Generate cache key for PIP query.
  Key components: pip-type, class-name, object-id, attribute"
  [pip-type class-name object-id attribute]
  (sha256-hash {:pip-type pip-type
                :class-name class-name
                :object-id object-id
                :attribute attribute}))

(defn get-cached-pip
  "Get cached PIP result."
  [pip-type class-name object-id attribute]
  (when (pip-cache-enabled?)
    (let [cache-key (make-pip-cache-key pip-type class-name object-id attribute)
          result (cc/lookup @pip-cache cache-key)]
      (if result
        (do
          (record-hit! :pip)
          (.debug logger "PIP cache HIT for key: {} (type={}, class={}, id={}, att={})"
                  cache-key pip-type class-name object-id attribute))
        (do
          (record-miss! :pip)
          (.debug logger "PIP cache MISS for key: {} (type={}, class={}, id={}, att={})"
                  cache-key pip-type class-name object-id attribute)))
      result)))

(defn cache-pip!
  "Cache PIP result."
  [pip-type class-name object-id attribute result]
  (when (pip-cache-enabled?)
    (let [cache-key (make-pip-cache-key pip-type class-name object-id attribute)]
      (.debug logger "Caching PIP result for key: {} (type={}, class={}, id={}, att={})"
              cache-key pip-type class-name object-id attribute)
      (swap! pip-cache
             (fn [current-cache]
               (let [new-cache (cc/miss current-cache cache-key result)]
                 ;; Check if cache size exceeds max, evict oldest if needed
                 (if (> (count new-cache) pip-cache-max-size)
                   (do
                     (record-eviction! :pip)
                     (.warn logger "PIP cache size exceeded, evicting entries")
                     ;; Reset to empty cache with TTL
                     (cc/ttl-cache-factory {cache-key result} :ttl pip-cache-ttl))
                   new-cache))))
      result)))