(ns autho.cache
  (:require [clojure.core.cache.wrapped :as cc])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.cache"))

(def resource-cache (cc/ttl-cache-factory {} :ttl 10000))
(def subject-cache (cc/lru-cache-factory {} :threshold 100))


;; merge 2 entities (maps)
;; used to merge a resource (or a subject) with the same entity from the cache
(defn mergeEntities [ent1 ent2]
  (.debug logger "Merging entities: {} // {}" ent1 ent2)
  (merge ent1 ent2)
  )

;; try to merge the entity and the same entity in the cache
;; return the merged entity
(defn mergeEntityWithCache [ent cache]
  (let [cacheEntity (cc/lookup cache (:id ent))
        mergedEntity (mergeEntities ent cacheEntity)]
      (swap! cache assoc (:id ent) mergedEntity)
      mergedEntity
      )
  )

(defn getCachedSubject [id]
  (get subject-cache id)
  )

(defn getCachedResource [id]
  (get resource-cache id)
  )