(ns autho.policy-impact
  "Policy impact analysis over batches of authorization requests.
   Compares the current policy against a candidate, versioned, or provided policy."
  (:require [autho.policy-versions :as pv]
            [autho.pdp :as pdp]
            [autho.prp :as prp]))

(defn- candidate-policy
  [resource-class {:keys [candidatePolicy candidateVersion]}]
  (cond
    candidatePolicy candidatePolicy
    candidateVersion (pv/get-version resource-class candidateVersion)
    :else (prp/getGlobalPolicy resource-class)))

(defn- baseline-policy
  [resource-class {:keys [baselineVersion]}]
  (if baselineVersion
    (pv/get-version resource-class baselineVersion)
    (prp/getGlobalPolicy resource-class)))

(defn- change-category
  [before-allowed after-allowed]
  (cond
    (and (not before-allowed) after-allowed) "deny_to_allow"
    (and before-allowed (not after-allowed)) "allow_to_deny"
    :else "unchanged"))

(defn- compare-decision
  [before after authz-request idx]
  (let [before-allowed (:allowed? before)
        after-allowed (:allowed? after)
        changed? (not= before-allowed after-allowed)
        before-rules (vec (or (:matchedRuleNames before) []))
        after-rules (vec (or (:matchedRuleNames after) []))
        winning-rules (vec (remove (set before-rules) after-rules))
        losing-rules (vec (remove (set after-rules) before-rules))]
    {:requestId idx
     :request authz-request
     :before {:allowed before-allowed
              :decisionType (:decisionType before)
              :matchedRuleNames before-rules}
     :after {:allowed after-allowed
             :decisionType (:decisionType after)
             :matchedRuleNames after-rules}
     :changed changed?
     :changeCategory (change-category before-allowed after-allowed)
     :winningRuleNames winning-rules
     :losingRuleNames losing-rules
     :changeType (cond
                   (and (not before-allowed) after-allowed) :grant
                   (and before-allowed (not after-allowed)) :revoke
                   :else :unchanged)}))

(defn- summarize-items
  [items]
  {:totalRequests (count items)
   :changedDecisions (count (filter :changed items))
   :grants (count (filter #(= :grant (:changeType %)) items))
   :revokes (count (filter #(= :revoke (:changeType %)) items))})

(defn- summarize-by
  [comparisons key-fn]
  (->> comparisons
       (group-by key-fn)
       (map (fn [[group-key items]]
              [group-key (summarize-items items)]))
       (into (sorted-map))))

(defn- resource-key
  [authz-request]
  (let [resource (or (:resource authz-request) {})
        resource-class (:class resource)
        resource-id (:id resource)]
    (if (and resource-class resource-id)
      (str resource-class "/" resource-id)
      (or resource-id resource-class "*"))))

(defn- subject-key
  [authz-request]
  (let [subject (or (:subject authz-request) {})]
    (or (:id subject) (:name subject) "*")))

(defn- build-blast-radius
  [changed]
  {:subjects (summarize-by changed #(subject-key (:request %)))
   :resources (summarize-by changed #(resource-key (:request %)))
   :operations (summarize-by changed #(or (get-in % [:request :operation]) "*"))})

(defn- top-entries
  [entries]
  (->> entries
       (sort-by (fn [[k v]] [(- (:changedDecisions v)) (- (:revokes v)) k]))
       (take 5)
       (mapv (fn [[entry-key metrics]]
               {:key entry-key
                :changedDecisions (:changedDecisions metrics)
                :grants (:grants metrics)
                :revokes (:revokes metrics)}))))

(defn- build-risk-signals
  [summary blast-radius]
  (let [subjects (:subjects blast-radius)
        resources (:resources blast-radius)
        operations (:operations blast-radius)
        revoke-count (:revokes summary)
        changed-decisions (:changedDecisions summary)
        changed-subject-count (count subjects)
        changed-resource-count (count resources)
        changed-operation-count (count operations)]
    {:highRisk (boolean (or (>= revoke-count 1)
                            (>= changed-decisions 10)
                            (>= changed-subject-count 5)
                            (>= changed-resource-count 10)))
     :revokeCount revoke-count
     :changedSubjectCount changed-subject-count
     :changedResourceCount changed-resource-count
     :changedOperationCount changed-operation-count
     :topImpactedSubjects (top-entries subjects)
     :topImpactedResources (top-entries resources)}))

(defn analyze-impact
  [request {:keys [resourceClass requests] :as body}]
  (let [resource-class (or resourceClass (get-in body [:resource :class]))
        base-policy (baseline-policy resource-class body)
        next-policy (candidate-policy resource-class body)]
    (when-not resource-class
      (throw (ex-info "Impact analysis requires resourceClass"
                      {:status 400 :error-code "MISSING_RESOURCE_CLASS"})))
    (when-not (vector? requests)
      (throw (ex-info "Impact analysis requires a non-empty requests vector"
                      {:status 400 :error-code "INVALID_IMPACT_REQUESTS"})))
    (when-not base-policy
      (throw (ex-info "Baseline policy not found"
                      {:status 404 :error-code "BASELINE_POLICY_NOT_FOUND"})))
    (when-not next-policy
      (throw (ex-info "Candidate policy not found"
                      {:status 404 :error-code "CANDIDATE_POLICY_NOT_FOUND"})))
    (let [comparisons (mapv (fn [idx authz-body]
                              (let [before (pdp/simulate request (assoc authz-body :simulatedPolicy base-policy))
                                    after (pdp/simulate request (assoc authz-body :simulatedPolicy next-policy))]
                                (compare-decision before after authz-body idx)))
                            (range)
                            requests)
          changed (filter :changed comparisons)
          summary (assoc (summarize-items comparisons)
                         :byOperation (summarize-by comparisons #(or (get-in % [:request :operation]) "*")))
          blast-radius (build-blast-radius changed)]
      {:resourceClass resource-class
       :baseline {:version (:baselineVersion body)
                  :strategy (:strategy base-policy)}
       :candidate {:version (:candidateVersion body)
                   :strategy (:strategy next-policy)
                   :source (cond
                             (:candidatePolicy body) "provided"
                             (:candidateVersion body) "version"
                             :else "current")}
       :summary summary
       :blastRadius blast-radius
       :riskSignals (build-risk-signals summary blast-radius)
       :changes (vec changed)})))
