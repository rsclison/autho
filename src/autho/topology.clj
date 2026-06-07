(ns autho.topology
  "Deployment topology helpers for Autho.

  The module keeps the deployment plane split explicit:
  - :data for authorization requests and subject/resource lookups
  - :control for policy, relation, and cache administration
  - :evidence for compliance exports

  The active plane set is loaded from AUTHO_ENABLED_PLANES at startup and can
  be overridden in tests via `set-enabled-planes!`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [autho.api.response :as response]))

(def ^:private supported-planes #{:control :data :evidence})

(def ^:private route-topology
  {:data ["/v1/authz" "/v1/subjects" "/v1/resources"]
   :control ["/v1/policies" "/v1/relations" "/v1/cache"]
   :evidence ["/v1/evidence"]})

(defonce ^:private active-planes
  (atom supported-planes))

(defn env
  "Returns the value of an environment variable.

  Kept public so tests can stub it without reaching into the JVM process."
  [key]
  (System/getenv key))

(defn- normalize-plane
  [plane]
  (cond
    (keyword? plane) plane
    (string? plane) (keyword (str/lower-case (str/trim plane)))
    :else (keyword (str/lower-case (str plane)))))

(defn- parse-enabled-planes
  []
  (if-let [raw (some-> (env "AUTHO_ENABLED_PLANES") str/trim not-empty)]
    (let [tokens (->> (str/split raw #"[,\s]+")
                      (remove str/blank?)
                      (map normalize-plane))
          unknown (seq (remove supported-planes tokens))]
      (when unknown
        (throw (ex-info "AUTHO_ENABLED_PLANES contains unsupported plane(s)"
                        {:status 500
                         :error-code "INVALID_PLANE_CONFIGURATION"
                         :unsupported-planes (sort unknown)
                         :supported-planes (sort supported-planes)})))
      (set tokens))
    supported-planes))

(defn- ordered-planes
  [planes]
  (vec (sort-by name planes)))

(defn init!
  "Reloads the active plane set from the environment."
  []
  (reset! active-planes (parse-enabled-planes)))

(defn set-enabled-planes!
  "Test hook that replaces the active plane set directly."
  [planes]
  (reset! active-planes (->> planes
                             (map normalize-plane)
                             set
                             (set/intersection supported-planes))))

(defn enabled-planes
  []
  @active-planes)

(defn plane-enabled?
  [plane]
  (contains? @active-planes (normalize-plane plane)))

(defn current-config
  "Returns a deployment summary suitable for /status."
  []
  {:supportedPlanes (ordered-planes supported-planes)
   :enabledPlanes (ordered-planes (enabled-planes))
   :disabledPlanes (ordered-planes (set/difference supported-planes (enabled-planes)))
   :routeTopology route-topology})

(defn plane-disabled-response
  [plane]
  (response/error-response "PLANE_DISABLED"
                           (str "The " (name (normalize-plane plane))
                                " plane is disabled in this deployment.")
                           503))

(defn call-with-plane
  "Runs thunk when plane is enabled, otherwise returns a 503 response."
  [plane thunk]
  (if (plane-enabled? plane)
    (thunk)
    (plane-disabled-response plane)))
