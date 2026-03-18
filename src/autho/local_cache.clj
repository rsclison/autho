(ns autho.local-cache
  "Enhanced local cache with TTL, LRU eviction, and Kafka invalidation support.
   Replaces atom-based cache with production-ready caching layer."
  (:require [clojure.core.cache.wrapped :as cc]
            [autho.kafka-invalidation :as invalidation]
            [autho.metrics :as metrics]
            [clojure.tools.logging :as log])
  (:import (java.util.concurrent TimeUnit)))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- get-env-or-default
  "Get environment variable or return default value."
  [env-key default-value]
  (or (System/getenv env-key) default-value))

;; Forward declaration for handle-invalidation (defined later, used in init-caches)
(declare handle-invalidation)

;; =============================================================================
;; Cache State Management
;; =============================================================================

(defonce cache-state (atom {
                            :subject-cache {}
                            :resource-cache {}
                            :policy-cache {}
                            :cache-stats {:hits 0 :misses 0 :evictions 0}}))

(defn get-cache-stats
  "Get current cache statistics."
  []
  (:cache-stats @cache-state))

(defn reset-cache-stats
  "Reset cache statistics to zero."
  []
  (swap! cache-state update :cache-stats (fn [_] {:hits 0 :misses 0 :evictions 0}))
  (log/info "Cache statistics reset"))

(defn- record-hit! [cache-type]
  (swap! cache-state update-in [:cache-stats :hits] inc)
  (metrics/record-cache-hit! cache-type)
  (log/debug "Cache HIT for" cache-type))

(defn- record-miss! [cache-type]
  (swap! cache-state update-in [:cache-stats :misses] inc)
  (metrics/record-cache-miss! cache-type)
  (log/debug "Cache MISS for" cache-type))

(defn- record-eviction! []
  (swap! cache-state update-in [:cache-stats :evictions] inc)
  (log/debug "Cache EVICTION"))

;; =============================================================================
;; Cache Configuration
;; =============================================================================

;; Configuration can be overridden via environment variables
(def cache-config
  (atom {
         ;; Cache TTL in milliseconds (default: 5 minutes for subjects)
         :subject-ttl (Long/parseLong (get-env-or-default "CACHE_TTL_SUBJECT" "300000"))

         ;; Resource cache TTL (default: 3 minutes)
         :resource-ttl (Long/parseLong (get-env-or-default "CACHE_TTL_RESOURCE" "180000"))

         ;; Policy cache TTL (default: 10 minutes)
         :policy-ttl (Long/parseLong (get-env-or-default "CACHE_TTL_POLICY" "600000"))

         ;; Maximum cache size (number of entries)
         :subject-max-size (Integer/parseInt (get-env-or-default "CACHE_MAX_SUBJECT_SIZE" "10000"))
         :resource-max-size (Integer/parseInt (get-env-or-default "CACHE_MAX_RESOURCE_SIZE" "10000"))
         :policy-max-size (Integer/parseInt (get-env-or-default "CACHE_MAX_POLICY_SIZE" "5000"))

         ;; Enable/disable cache
         :cache-enabled (Boolean/parseBoolean (get-env-or-default "CACHE_ENABLED" "true"))}))

(defn update-cache-config
  "Update cache configuration at runtime."
  [new-config]
  (swap! cache-config merge new-config)
  (log/info "Cache configuration updated:" new-config))

;; =============================================================================
;; Cache Factory Functions
;; =============================================================================

(defn- create-ttl-cache
  "Create a TTL-based cache with specified TTL in milliseconds.
   Uses LRU eviction when cache is full."
  [ttl-ms threshold]
  (cc/ttl-cache-factory {}
                        :ttl ttl-ms
                        :threshold threshold))

(defn- create-lru-cache
  "Create a simple LRU cache with specified threshold."
  [threshold]
  (cc/lru-cache-factory {} :threshold threshold))

(defn init-caches
  "Initialize all caches with configured TTL and size limits.
   Should be called during application startup."
  []
  (log/info "Initializing local caches..."
            "subject TTL:" (:subject-ttl @cache-config) "ms"
            "resource TTL:" (:resource-ttl @cache-config) "ms"
            "policy TTL:" (:policy-ttl @cache-config) "ms")

  ;; Reset caches to empty maps
  (swap! cache-state assoc
         :subject-cache {}
         :resource-cache {}
         :policy-cache {}
         :cache-stats {:hits 0 :misses 0 :evictions 0})

  ;; Start Kafka invalidation listener if enabled
  (when (:kafka-enabled @cache-config true)
    (invalidation/start-invalidation-listener! handle-invalidation))

  (log/info "Local caches initialized successfully"))

(defn clear-all-caches
  "Clear all cache instances and reset statistics."
  []
  (log/info "Clearing all caches...")
  (swap! cache-state assoc
         :subject-cache {}
         :resource-cache {}
         :policy-cache {}
         :cache-stats {:hits 0 :misses 0 :evictions 0})
  (log/info "All caches cleared"))

;; =============================================================================
;; Cache Access Functions
;; =============================================================================

(defn get-or-fetch
  "Get value from cache or fetch using provided function.
   Automatically caches the result.

   Parameters:
   - cache-key: Cache key (typically entity ID)
   - cache-type: :subject, :resource, or :policy
   - fetch-fn: Function to call on cache miss
   - ttl-ms: Optional TTL override in milliseconds

   Returns cached or fetched value."
  ([cache-key cache-type fetch-fn]
   (get-or-fetch cache-key cache-type fetch-fn nil))
  ([cache-key cache-type fetch-fn ttl-ms]
   (when-not (:cache-enabled @cache-config)
     (fetch-fn))

   (let [cache-kw (case cache-type
                    :subject :subject-cache
                    :resource :resource-cache
                    :policy :policy-cache)
         cache (cache-kw @cache-state)]
     (if-let [cached-value (get cache cache-key)]
       (do
         (record-hit! cache-type)
         (log/trace "Cache hit for" cache-type ":" cache-key)
         cached-value)
       (do
         (record-miss! cache-type)
         (log/trace "Cache miss for" cache-type ":" cache-key " - fetching...")
         (try
           (let [fetched-value (fetch-fn)]
             (when fetched-value
               ;; Add to existing cache
               (swap! cache-state update cache-kw assoc cache-key fetched-value)
               (log/trace "Cached" cache-type ":" cache-key))
             fetched-value)
           (catch Exception e
             (log/error e "Error fetching" cache-type "for key" cache-key)
             nil)))))))

(defn invalidate
  "Invalidate a specific cache entry.
   Also publishes invalidation to Kafka for multi-instance sync."
  [cache-type cache-key]
  (when (:cache-enabled @cache-config)
    (log/debug "Invalidating" cache-type ":" cache-key)

    ;; Remove from local cache
    (let [cache-kw (case cache-type
                     :subject :subject-cache
                     :resource :resource-cache
                     :policy :policy-cache)]
      (swap! cache-state update cache-kw dissoc cache-key))

    ;; Publish invalidation to Kafka
    (when (:kafka-enabled @cache-config true)
      (invalidation/publish-invalidation cache-type cache-key))

    (log/debug "Invalidated" cache-type ":" cache-key)))

(defn invalidate-all-type
  "Invalidate all entries of a specific type."
  [cache-type]
  (log/info "Invalidating all" cache-type "cache entries")

  (let [cache-kw (case cache-type
                   :subject :subject-cache
                   :resource :resource-cache
                   :policy :policy-cache)]
    (swap! cache-state assoc cache-kw {})))

;; =============================================================================
;; Kafka Invalidation Handler
;; =============================================================================

(defn handle-invalidation
  "Handle invalidation message received from Kafka.
   Message format: {:type \"subject\" :key \"user123\"}"
  [invalidation-msg]
  (try
    (let [cache-type (keyword (:type invalidation-msg))
          cache-key (:key invalidation-msg)]

      (when (and cache-type cache-key)
        (log/debug "Received Kafka invalidation for" cache-type ":" cache-key)

        ;; Remove from local cache
        (let [cache-kw (case cache-type
                         :subject :subject-cache
                         :resource :resource-cache
                         :policy :policy-cache)]
          (swap! cache-state update cache-kw dissoc cache-key))

        (log/trace "Removed from cache:" cache-type ":" cache-key)))
    (catch Exception e
      (log/error e "Error handling invalidation message:" invalidation-msg))))

;; =============================================================================
;; Backward Compatibility API
;; =============================================================================

;; Maintain backward compatibility with existing cache.clj API

(defn getCachedSubject
  "Get subject from cache. Backward compatible function."
  [id]
  (get (:subject-cache @cache-state) id))

(defn getCachedResource
  "Get resource from cache. Backward compatible function."
  [id]
  (get (:resource-cache @cache-state) id))

(defn getCachedPolicy
  "Get policy from cache."
  [id]
  (get (:policy-cache @cache-state) id))

(defn mergeEntityWithCache
  "Merge entity with cached version and update cache.
   Backward compatible function from cache.clj"
  [ent cache-type]
  (if-let [ent-id (:id ent)]
    (let [cache-kw (case cache-type
                     :subject :subject-cache
                     :resource :resource-cache
                     nil)
          cache (when cache-kw (cache-kw @cache-state))
          cached-entity (get cache ent-id)
          merged (merge cached-entity ent)]
      ;; Update cache with merged entity
      (when (and cache-kw cache)
        (swap! cache-state update cache-kw assoc ent-id merged))
      merged)
    (do
      (log/warn "Entity without ID cannot be cached:" ent)
      ent)))
