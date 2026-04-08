(ns autho.policy-format)

(def ^:private strategy-aliases
  {"almost_one_allow_no_deny" :almost_one_allow_no_deny
   "deny-unless-permit" :deny-unless-permit
   "permit-unless-deny" :permit-unless-deny
   "first-applicable" :first-applicable
   "only-one-applicable" :only-one-applicable})

(defn normalize-strategy [strategy]
  (cond
    (keyword? strategy) strategy
    (string? strategy) (or (get strategy-aliases strategy) (keyword strategy))
    :else strategy))

(defn normalize-policy [policy]
  (if (map? policy)
    (update policy :strategy normalize-strategy)
    policy))
