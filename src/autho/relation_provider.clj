(ns autho.relation-provider
  "Abstraction for resolving authorization relationships.

   Providers return an explicit status so callers can distinguish a missing
   relationship from an unavailable source. The local ReBAC store is the
   compatibility provider and represents an authorization projection, not a
   business-system source of truth."
  (:require [autho.rebac :as rebac]
            [autho.utils :as utils]
            [autho.metrics :as metrics]
            [autho.circuit-breaker :as cb]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (java.time Instant)))

(defn- hide-internal-tenant-keys
  "Tenant is an index key, not a business attribute of a returned entity.
   The effective tenant stays available from the request/decision context."
  [value]
  (walk/postwalk (fn [node]
                   (if (map? node)
                     (dissoc node :tenantId :tenant-id)
                     node))
                 value))

(defprotocol RelationProvider
  (check-relation* [provider request subject relation resource options])
  (list-objects* [provider request subject relation options])
  (list-subjects* [provider request resource relation options])
  (traverse* [provider request start steps options]))

(defrecord LocalProjectionProvider []
  RelationProvider
  (check-relation* [_ _request subject relation resource options]
    (let [proof (hide-internal-tenant-keys
                 (rebac/explain-relation subject relation resource options))]
      (assoc proof
             :status (if (:allowed proof) :allowed :denied)
             :source :projection)))
  (list-objects* [_ _request subject relation options]
    (mapv hide-internal-tenant-keys
          (rebac/list-accessible-resources subject relation options)))
  (list-subjects* [_ _request resource relation options]
    (mapv hide-internal-tenant-keys
          (rebac/list-authorized-subjects resource relation options)))
  (traverse* [_ _request start steps options]
    (hide-internal-tenant-keys
     (rebac/traverse-relations start steps options))))

(defonce ^:private default-provider
  (atom (->LocalProjectionProvider)))

(defonce ^:private pip-resolvers
  (atom {}))

(defonce ^:private configured-pip-names
  (atom #{}))

(defn- config-value
  [config key default]
  (let [value (or (get config key) (get config (name key)))]
    (if (nil? value) default value)))

(defn- rest-relation-resolver
  [{:keys [url] :as config}]
  (when (str/blank? (str url))
    (throw (ex-info "REST relation PIP requires a URL" {:config config})))
  (let [timeout-ms (config-value config :timeout-ms 1000)
        headers (config-value config :headers {})]
    (fn [request subject relation resource _options]
      (let [response (metrics/time-pip-call! :relation-rest
                       #(cb/call url (fn [] (http/post url {:throw-exceptions false
                                     :content-type :json
                                     :accept :json
                                     :as :json
                                     :socket-timeout timeout-ms
                                     :conn-timeout timeout-ms
                                     :headers headers
                                     :form-params {:subject subject
                                                   :relation relation
                                                   :resource resource
                                                   :context (:context request)
                                                   :tenantId (or (:tenantId request)
                                                                 (get-in request [:tenant :tenantId]))}}))))]
        (cond
          (<= 200 (:status response) 299) (:body response)
          (= 404 (:status response)) {:status :unknown
                                      :reason "Relation was not found by the PIP"}
          :else {:status :error
                 :reason "Relation PIP returned a non-success HTTP status"
                 :httpStatus (:status response)})))))

(defn configure-pip-resolvers!
  "Registers PIP resolvers declared in configuration.

   Current durable connector type: :rest. Each declaration requires :name,
   :type and, for REST, :url; :timeout-ms defaults to 1000. Existing configured
   resolvers are replaced atomically while explicitly registered resolvers are
   kept."
  [declarations]
  (let [declarations (or declarations [])
        next-resolvers (into {}
                             (map (fn [declaration]
                                    (let [resolver-name (config-value declaration :name nil)
                                          configured-type (config-value declaration :type nil)]
                                      (when (str/blank? (str resolver-name))
                                        (throw (ex-info "Relation PIP requires a name"
                                                        {:config declaration})))
                                      (when (nil? configured-type)
                                        (throw (ex-info "Relation PIP requires a type"
                                                        {:config declaration})))
                                      (let [type (keyword (name configured-type))]
                                        [(name resolver-name)
                                         (case type
                                           :rest (rest-relation-resolver declaration)
                                           (throw (ex-info "Unsupported relation PIP type"
                                                           {:type type :config declaration})))]))))
                             declarations)
        previous @configured-pip-names]
    (swap! pip-resolvers #(merge (apply dissoc % previous) next-resolvers))
    (reset! configured-pip-names (set (keys next-resolvers)))
    (keys next-resolvers)))

(defn load-configured-pip-resolvers!
  "Loads relation PIPs from RELATION_PIPS_CONFIG_PATH, defaulting to
   resources/relation-pips.edn. Missing configuration is equivalent to an
   empty list, so relation PIPs remain opt-in."
  []
  (let [path (or (System/getenv "RELATION_PIPS_CONFIG_PATH")
                 "resources/relation-pips.edn")]
    (configure-pip-resolvers! (or (utils/load-edn path) []))))

(defn register-pip-resolver!
  "Registers a named relation PIP resolver.

   A resolver receives `request subject relation resource options` and returns
   either a boolean or a map containing `:status` (`:allowed`, `:denied`,
   `:unknown`, or `:error`). This small registry keeps relation resolution
   independent from the legacy attribute-PIP contract while connectors are
   introduced incrementally."
  [resolver-name resolver]
  (swap! pip-resolvers assoc (name resolver-name) resolver))

(defn unregister-pip-resolver!
  [resolver-name]
  (swap! pip-resolvers dissoc (name resolver-name)))

(defn clear-pip-resolvers!
  []
  (reset! pip-resolvers {})
  (reset! configured-pip-names #{}))

(defn- option-value
  [options key]
  (or (get options key)
      (get options (name key))))

(defn- normalized-pip-result
  [result pip-name]
  (let [result (cond
                 (true? result) {:status :allowed}
                 (false? result) {:status :denied}
                 (map? result) result
                 (nil? result) {:status :unknown}
                 :else {:status :error
                        :reason "Invalid relation PIP result"})
        status (let [value (:status result)]
                 (cond
                   (keyword? value) value
                   (string? value) (keyword value)
                   (:allowed result) :allowed
                   (contains? result :allowed) :denied
                   :else :unknown))]
    (assoc result :status status :source :pip :pip pip-name)))

(defn- check-pip-relation
  [request subject relation resource options]
  (let [pip-name (some-> (or (option-value options :pip)
                             (option-value options :provider)) name)
        resolver (get @pip-resolvers pip-name)]
    (if-not resolver
      {:status :unknown
       :source :pip
       :pip pip-name
       :reason "No relation PIP resolver is configured"}
      (try
        (normalized-pip-result (resolver request subject relation resource options) pip-name)
        (catch Exception e
          {:status :error
           :source :pip
           :pip pip-name
           :reason (.getMessage e)})))))

(defn current-provider
  "Returns the provider selected for relation checks. A later configuration
   layer can select PIP or hybrid providers without changing PDP callers."
  []
  @default-provider)

(defn set-default-provider!
  "Sets the process-wide provider. Intended for bootstrapping and tests."
  [provider]
  (reset! default-provider provider))

(defn reset-default-provider!
  []
  (reset! default-provider (->LocalProjectionProvider)))

(defn- source
  [options]
  (some-> (option-value options :source) name keyword))

(defn- projection-check
  [request subject relation resource options]
  (let [tenant-id (or (:tenantId request)
                      (get-in request [:tenant :tenantId])
                      (option-value options :tenantId))]
    (rebac/with-tenant tenant-id
      (check-relation* (current-provider) request subject relation resource options))))

(defn- stale-projection?
  [result options]
  (when-let [limit (option-value options :maxStalenessMs)]
    (when-let [timestamp (or (get-in result [:projection :receivedAt])
                             (get-in result [:projection :occurredAt]))]
      (try
        (> (- (.toEpochMilli (Instant/now)) (.toEpochMilli (Instant/parse timestamp)))
           (Long/parseLong (str limit)))
        (catch Exception _ true)))))

(defn- enforce-consistency
  [result options]
  (let [mode (some-> (option-value options :consistency) name keyword)
        projection-time (or (get-in result [:projection :receivedAt])
                            (get-in result [:projection :occurredAt]))]
    (if (and (contains? #{:bounded-staleness :fail-closed} mode)
             (= :allowed (:status result))
             (or (and (= :fail-closed mode) (nil? projection-time))
                 (stale-projection? result options)))
      (assoc result :status :unknown :reason "Relationship projection is older than its allowed staleness")
      result)))

(defn check-relation
  ([request subject relation resource]
   (check-relation request subject relation resource {}))
  ([request subject relation resource options]
   (let [hybrid-result (fn []
                         (let [projection (projection-check request subject relation resource options)]
                           ;; Hybrid mode is deliberately fail-closed: an authorization
                           ;; from a local projection is confirmed by the named PIP.
                           (if (not= :allowed (:status projection))
                             (assoc projection :source :hybrid :projection projection)
                             (let [pip-result (check-pip-relation request subject relation resource options)]
                               (assoc pip-result :source :hybrid :projection projection :pipResult pip-result)))))]
     (case (source options)
       :pip (check-pip-relation request subject relation resource options)
       :hybrid (hybrid-result)
       (if (= :fresh (some-> (option-value options :consistency) name keyword))
         (if (option-value options :pip)
           (hybrid-result)
           {:status :unknown :source :projection
            :reason "Fresh relationship checks require a named relation PIP"})
         (enforce-consistency (projection-check request subject relation resource options) options))))))

(defn allowed?
  "True only for an affirmative relationship result. `unknown` and `error`
   intentionally do not authorize a request."
  [result]
  (= :allowed (:status result)))

(defn list-objects
  ([request subject relation]
   (list-objects request subject relation {}))
  ([request subject relation options]
   (let [tenant-id (or (:tenantId request)
                       (get-in request [:tenant :tenantId])
                       (option-value options :tenantId))]
     (rebac/with-tenant tenant-id
       (list-objects* (current-provider) request subject relation options)))))

(defn list-subjects
  ([request resource relation]
   (list-subjects request resource relation {}))
  ([request resource relation options]
   (let [tenant-id (or (:tenantId request)
                       (get-in request [:tenant :tenantId])
                       (option-value options :tenantId))]
     (rebac/with-tenant tenant-id
       (list-subjects* (current-provider) request resource relation options)))))

(defn traverse
  ([request start steps]
   (traverse request start steps {}))
  ([request start steps options]
   (let [tenant-id (or (:tenantId request)
                       (get-in request [:tenant :tenantId])
                       (option-value options :tenantId))]
     (rebac/with-tenant tenant-id
       (traverse* (current-provider) request start steps options)))))
