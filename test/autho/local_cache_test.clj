(ns autho.local-cache-test
  (:require [clojure.test :refer :all]
            [autho.local-cache :as cache]))

;; =============================================================================
;; Cache Initialization Tests
;; =============================================================================

(deftest init-caches-test
  (testing "Initialize all caches"
    (cache/init-caches)
    (is (some? (get @cache/cache-state :subject-cache)))
    (is (some? (get @cache/cache-state :resource-cache)))
    (is (some? (get @cache/cache-state :policy-cache)))))

;; =============================================================================
;; Cache Get/Fetch Tests
;; =============================================================================

(deftest get-or-fetch-cache-miss-test
  (testing "Cache miss - fetch function called"
    (cache/init-caches)
    (let [fetch-called (atom false)
          fetch-fn (fn []
                     (reset! fetch-called true)
                     {:id "test1" :name "Test Subject"})]
      (cache/get-or-fetch "test1" :subject fetch-fn)
      (is (true? @fetch-called)))))

(deftest get-or-fetch-cache-hit-test
  (testing "Cache hit - fetch function not called"
    (cache/init-caches)
    (let [fetch-called (atom false)
          fetch-fn (fn []
                     (reset! fetch-called true)
                     {:id "test2" :name "Test Subject 2"})
          ;; First call populates cache
          _ (cache/get-or-fetch "test2" :subject fetch-fn)
          fetch-called-2 (atom false)
          fetch-fn-2 (fn []
                       (reset! fetch-called-2 true)
                       {:id "test2" :name "Different"})]
      ;; Second call should hit cache
      (cache/get-or-fetch "test2" :subject fetch-fn-2)
      (is (false? @fetch-called-2)))))

;; =============================================================================
;; Cache Statistics Tests
;; =============================================================================

(deftest cache-stats-test
  (testing "Cache statistics recorded correctly"
    (cache/init-caches)
    (cache/reset-cache-stats)
    (let [initial-stats (cache/get-cache-stats)]
      (is (zero? (:hits initial-stats)))
      (is (zero? (:misses initial-stats)))

      ;; Generate some cache activity
      (cache/get-or-fetch "stat-test" :subject (fn [] {:id "stat-test" :name "Stats"}))
      (cache/get-or-fetch "stat-test" :subject (fn [] {:id "stat-test" :name "Different"}))

      (let [final-stats (cache/get-cache-stats)]
        (is (= 1 (:hits final-stats)))
        (is (= 1 (:misses final-stats)))))))

;; =============================================================================
;; Cache Invalidation Tests
;; =============================================================================

(deftest invalidate-cache-entry-test
  (testing "Invalidate specific cache entry"
    (cache/init-caches)
    ;; Populate cache
    (cache/get-or-fetch "inv-test" :subject (fn [] {:id "inv-test" :name "Invalidate Test"}))

    ;; Invalidate
    (cache/invalidate :subject "inv-test")

    ;; Should miss on next fetch
    (let [fetch-called (atom false)
          fetch-fn (fn []
                     (reset! fetch-called true)
                     {:id "inv-test" :name "Fetched Again"})]
      (cache/get-or-fetch "inv-test" :subject fetch-fn)
      (is (true? @fetch-called)))))

(deftest invalidate-all-type-test
  (testing "Invalidate all entries of a type"
    (cache/init-caches)
    ;; Populate cache with multiple entries
    (cache/get-or-fetch "clear1" :subject (fn [] {:id "clear1" :name "Clear 1"}))
    (cache/get-or-fetch "clear2" :subject (fn [] {:id "clear2" :name "Clear 2"}))
    (cache/get-or-fetch "clear3" :subject (fn [] {:id "clear3" :name "Clear 3"}))

    ;; Clear all subject cache
    (cache/invalidate-all-type :subject)

    ;; All should miss on next fetch
    (let [fetch-count (atom 0)
          fetch-fn (fn []
                     (swap! fetch-count inc)
                     {:id "fetched" :name "Fetched"})]
      (cache/get-or-fetch "clear1" :subject fetch-fn)
      (cache/get-or-fetch "clear2" :subject fetch-fn)
      (cache/get-or-fetch "clear3" :subject fetch-fn)
      (is (= 3 @fetch-count)))))

;; =============================================================================
;; Cache Clear Tests
;; =============================================================================

(deftest clear-all-caches-test
  (testing "Clear all caches"
    (cache/init-caches)
    ;; Populate all caches
    (cache/get-or-fetch "subject1" :subject (fn [] {:id "subject1"}))
    (cache/get-or-fetch "resource1" :resource (fn [] {:id "resource1"}))
    (cache/get-or-fetch "policy1" :policy (fn [] {:id "policy1"}))

    ;; Clear all
    (cache/clear-all-caches)

    ;; Check stats reset
    (let [stats (cache/get-cache-stats)]
      (is (zero? (:hits stats)))
      (is (zero? (:misses stats))))))

;; =============================================================================
;; Backward Compatibility Tests
;; =============================================================================

(deftest get-cached-subject-test
  (testing "Backward compatible getCachedSubject"
    (cache/init-caches)
    ;; Populate cache
    (cache/get-or-fetch "compat1" :subject (fn [] {:id "compat1" :name "Compatibility"}))

    ;; Should retrieve from cache
    (let [cached (cache/getCachedSubject "compat1")]
      (is (some? cached))
      (is (= "compat1" (:id cached))))))

(deftest get-cached-resource-test
  (testing "Backward compatible getCachedResource"
    (cache/init-caches)
    ;; Populate cache
    (cache/get-or-fetch "compat2" :resource (fn [] {:id "compat2" :type "Resource"}))

    ;; Should retrieve from cache
    (Thread/sleep 100)  ; Small delay to ensure cache is populated
    (let [cached (cache/getCachedResource "compat2")]
      (is (some? cached))
      (is (= "compat2" (:id cached))))))

(deftest get-cached-policy-test
  (testing "getCachedPolicy"
    (cache/init-caches)
    ;; Populate cache
    (cache/get-or-fetch "compat3" :policy (fn [] {:id "compat3" :effect "allow"}))

    ;; Should retrieve from cache
    (Thread/sleep 100)  ; Small delay to ensure cache is populated
    (let [cached (cache/getCachedPolicy "compat3")]
      (is (some? cached))
      (is (= "compat3" (:id cached))))))

(deftest merge-entity-with-cache-test
  (testing "Backward compatible mergeEntityWithCache"
    (cache/init-caches)
    ;; Initial cache
    (cache/get-or-fetch "merge1" :subject (fn [] {:id "merge1" :name "Original" :value "old"}))

    ;; Merge with new data
    (let [merged (cache/mergeEntityWithCache {:id "merge1" :name "Updated" :new-field "new"} :subject)]
      (is (= "merge1" (:id merged)))
      (is (= "Updated" (:name merged)))
      (is (= "old" (:value merged)))  ; Preserved from cache
      (is (= "new" (:new-field merged))))))

;; =============================================================================
;; Cache Configuration Tests
;; =============================================================================

(deftest update-cache-config-test
  (testing "Update cache configuration at runtime"
    (let [new-config {:subject-ttl 1000 :resource-ttl 2000}]
      (cache/update-cache-config new-config)
      (is (= 1000 (:subject-ttl @cache/cache-config)))
      (is (= 2000 (:resource-ttl @cache/cache-config))))))
