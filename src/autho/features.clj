(ns autho.features
  "Licence-gated feature flags for Autho.

  Usage:
    (features/licensed? :audit)      ; boolean check
    (features/require-license! :audit) ; throws HTTP 402 if not licensed

  Features available per tier:
    :free       — core PDP decisions only
    :pro        — + audit, versioning, explain, simulate, metrics
    :enterprise — + kafka-pip, multi-instance"
  (:require [autho.license :as license]
            [clojure.string :as str])
  (:import (org.slf4j LoggerFactory)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.features"))

;; ---------------------------------------------------------------------------
;; Tier definitions
;; ---------------------------------------------------------------------------

(def ^:private tier-features
  {:free       #{:is-authorized :who-authorized :what-authorized}
   :pro        #{:is-authorized :who-authorized :what-authorized
                 :audit :versioning :explain :simulate :metrics}
   :enterprise #{:is-authorized :who-authorized :what-authorized
                 :audit :versioning :explain :simulate :metrics
                 :kafka-pip :multi-instance}})

;; Default: free tier (no AUTHO_LICENSE_KEY set)
(defonce ^:private active-features
  (atom (:free tier-features)))

(defonce ^:private active-claims
  (atom nil))

;; ---------------------------------------------------------------------------
;; Activation
;; ---------------------------------------------------------------------------

(defn activate!
  "Called at startup with verified licence claims. Updates active features."
  [claims]
  (let [tier     (keyword (:tier claims))
        base     (get tier-features tier #{})
        explicit (set (map keyword (:features claims [])))
        enabled  (into base explicit)]
    (reset! active-features enabled)
    (reset! active-claims claims)
    (.info logger (format "Licence %s activée — client: %s — expire: %s — features: [%s]"
                          (name tier)
                          (:customer claims)
                          (:expires_at claims)
                          (str/join ", " (map name enabled))))))

(defn init!
  "Reads AUTHO_LICENSE_KEY and activates the licence. Safe to call with no env var (free tier)."
  []
  (if-let [token (System/getenv "AUTHO_LICENSE_KEY")]
    (try
      (let [claims (license/parse-and-verify token)]
        (activate! claims))
      (catch clojure.lang.ExceptionInfo e
        (.error logger "Erreur de licence : {} — démarrage en mode free" (ex-message e))
        ;; Keep free tier features — do not crash the server
        ))
    (.info logger "Aucune licence détectée (AUTHO_LICENSE_KEY absent) — mode free")))

;; ---------------------------------------------------------------------------
;; Feature checks
;; ---------------------------------------------------------------------------

(defn licensed?
  "Returns true if the given feature keyword is active in the current licence."
  [feature]
  (contains? @active-features feature))

(defn require-license!
  "Throws an ex-info with :status 402 if the feature is not licensed.
  Use this at the start of handlers for gated endpoints."
  [feature]
  (when-not (licensed? feature)
    (throw (ex-info (str "Cette fonctionnalité requiert une licence Pro ou Enterprise : "
                         (name feature))
                    {:feature feature
                     :status  402}))))

(defn active-tier
  "Returns the active tier keyword (:free :pro :enterprise), or :free if no licence."
  []
  (if-let [claims @active-claims]
    (keyword (:tier claims))
    :free))

(defn licence-info
  "Returns a summary map for the /status endpoint."
  []
  (if-let [claims @active-claims]
    {:tier       (:tier claims)
     :customer   (:customer claims)
     :expires_at (:expires_at claims)
     :features   (mapv name @active-features)}
    {:tier     "free"
     :features (mapv name @active-features)}))
