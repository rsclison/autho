(ns autho.reconciliation
  "Read-only snapshot connectors for relationship reconciliation."
  (:require [autho.rebac :as rebac]
            [autho.utils :as utils]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.util.concurrent Executors ScheduledExecutorService TimeUnit)))

(defonce sources (atom {}))
(defonce ^:private scheduler (atom nil))

(defn load-sources! []
  (let [path (or (System/getenv "RECONCILIATION_SOURCES_CONFIG_PATH")
                 "resources/reconciliation-sources.edn")]
    (reset! sources (into {} (map (juxt :name identity) (or (utils/load-edn path) []))))))

(defn list-sources []
  (->> @sources
       vals
       (map #(select-keys % [:name :type :url :timeout-ms :interval-ms :tenant-ids]))
       (sort-by :name)
       vec))

(defn reconcile-source!
  [source]
  (let [{:keys [type url timeout-ms headers] :as config} (get @sources source)]
    (when-not config
      (throw (ex-info "No reconciliation source is configured" {:source source})))
    (when-not (= :rest type)
      (throw (ex-info "Unsupported reconciliation source type" {:source source :type type})))
    (when (str/blank? (str url))
      (throw (ex-info "Reconciliation REST source requires URL" {:source source})))
    (let [response (http/get url {:throw-exceptions false :as :json :accept :json
                                  :socket-timeout (or timeout-ms 5000)
                                  :conn-timeout (or timeout-ms 5000)
                                  :headers headers})]
      (when-not (<= 200 (:status response) 299)
        (throw (ex-info "Reconciliation source request failed" {:source source :httpStatus (:status response)})))
      (let [body (:body response)
            tuples (if (map? body) (:tuples body) body)]
        (when-not (sequential? tuples)
          (throw (ex-info "Reconciliation source must return tuples" {:source source})))
        (rebac/reconcile-snapshot source tuples)))))

(defn run-scheduled-source!
  "Runs configured read-only reconciliation for every declared tenant of a
   source. Sources without both interval-ms and tenant-ids remain manual-only."
  [source]
  (let [config (get @sources source)
        tenants (:tenant-ids config)]
    (when-not config
      (throw (ex-info "No reconciliation source is configured" {:source source})))
    (mapv (fn [tenant-id]
            (try
              (rebac/with-tenant tenant-id (reconcile-source! source))
              (catch Exception e
                (log/error e "Scheduled relationship reconciliation failed" {:source source :tenantId tenant-id})
                {:source source :tenantId tenant-id :status :error :error (.getMessage e)})))
          tenants)))

(defn stop-scheduled-reconciliations! []
  (when-let [^ScheduledExecutorService executor @scheduler]
    (.shutdownNow executor)
    (reset! scheduler nil))
  true)

(defn start-scheduled-reconciliations!
  "Starts periodic comparison jobs declared by :interval-ms and :tenant-ids in
   reconciliation-sources.edn. Jobs never mutate the projection."
  []
  (stop-scheduled-reconciliations!)
  (let [scheduled (filter #(and (pos? (long (or (:interval-ms %) 0)))
                                (seq (:tenant-ids %)))
                          (vals @sources))]
    (when (seq scheduled)
      (let [executor (Executors/newScheduledThreadPool (count scheduled))]
        (doseq [{:keys [name interval-ms]} scheduled]
          (.scheduleWithFixedDelay executor
                                   ^Runnable #(run-scheduled-source! name)
                                   (long interval-ms)
                                   (long interval-ms)
                                   TimeUnit/MILLISECONDS))
        (reset! scheduler executor)
        (log/info "Started scheduled relationship reconciliation" {:sources (mapv :name scheduled)})))
    (boolean @scheduler)))
