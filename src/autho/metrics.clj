(ns autho.metrics
  "Prometheus metrics registry for autho.
   Exposes counters and timers for PDP decisions, cache activity, and HTTP traffic."
  (:import (io.micrometer.prometheus PrometheusMeterRegistry PrometheusConfig)
           (io.micrometer.core.instrument Counter Counter$Builder Timer Timer$Builder)
           (io.micrometer.core.instrument.binder.jvm JvmMemoryMetrics JvmGcMetrics JvmThreadMetrics)
           (io.micrometer.core.instrument.binder.system ProcessorMetrics)))

(defonce ^PrometheusMeterRegistry registry
  (PrometheusMeterRegistry. PrometheusConfig/DEFAULT))

(defn scrape
  "Returns current metrics in Prometheus text exposition format."
  []
  (.scrape registry))

;; ---------------------------------------------------------------------------
;; PDP Decision Metrics
;; ---------------------------------------------------------------------------

(defn- decision-counter ^Counter [^String decision ^String resource-class]
  (-> ^Counter$Builder (Counter/builder "autho_pdp_decisions_total")
      (.description "Total number of PDP authorization decisions")
      (.tags (into-array String ["decision"       decision
                                 "resource_class" (or resource-class "unknown")]))
      (.register registry)))

(defn record-decision!
  "Increment the PDP decision counter."
  [decision resource-class]
  (.increment (decision-counter (name decision) (or resource-class "unknown"))))

;; ---------------------------------------------------------------------------
;; PIP Call Metrics
;; ---------------------------------------------------------------------------

(defn- pip-timer ^Timer [^String pip-type]
  (-> ^Timer$Builder (Timer/builder "autho_pip_duration_seconds")
      (.description "Duration of external PIP calls")
      (.tags (into-array String ["type" (or pip-type "unknown")]))
      (.register registry)))

(defn time-pip-call!
  "Execute f and record its duration against the pip-type timer."
  [pip-type f]
  (let [sample (Timer/start registry)
        tname  (when pip-type (name pip-type))]
    (try
      (let [result (f)]
        (.stop sample (pip-timer tname))
        result)
      (catch Exception e
        (.stop sample (pip-timer tname))
        (throw e)))))

;; ---------------------------------------------------------------------------
;; Cache Metrics
;; ---------------------------------------------------------------------------

(defn- cache-counter ^Counter [^String event ^String cache-type]
  (-> ^Counter$Builder (Counter/builder "autho_cache_events_total")
      (.description "Total number of cache hits and misses")
      (.tags (into-array String ["event" event
                                 "type"  cache-type]))
      (.register registry)))

(defn record-cache-hit!
  "Increment the cache hit counter for the given cache type."
  [cache-type]
  (.increment (cache-counter "hit" (name cache-type))))

(defn record-cache-miss!
  "Increment the cache miss counter for the given cache type."
  [cache-type]
  (.increment (cache-counter "miss" (name cache-type))))

;; ---------------------------------------------------------------------------
;; HTTP Request Metrics
;; ---------------------------------------------------------------------------

(defn- http-timer ^Timer [^String method ^String uri ^String status]
  (-> ^Timer$Builder (Timer/builder "autho_http_requests_seconds")
      (.description "HTTP request duration")
      (.tags (into-array String ["method" method
                                 "uri"    uri
                                 "status" status]))
      (.register registry)))

(defn record-http-request!
  "Execute handler f and record the request duration."
  [method uri f]
  (let [sample (Timer/start registry)]
    (try
      (let [response (f)
            status   (str (or (:status response) 200))]
        (.stop sample (http-timer method uri status))
        response)
      (catch Exception e
        (.stop sample (http-timer method uri "500"))
        (throw e)))))

;; ---------------------------------------------------------------------------
;; JVM Metrics
;; ---------------------------------------------------------------------------

(defn init-jvm-metrics!
  "Register JVM memory, GC, thread, and CPU metrics."
  []
  (.bindTo (JvmMemoryMetrics.) registry)
  (.bindTo (JvmGcMetrics.) registry)
  (.bindTo (JvmThreadMetrics.) registry)
  (.bindTo (ProcessorMetrics.) registry))
