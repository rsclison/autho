(ns autho.policy-impact
  "Policy impact analysis over batches of authorization requests.
   Compares the current policy against a candidate, versioned, or provided policy."
  (:require [autho.policy-versions :as pv]
            [autho.audit :as audit]
            [autho.pdp :as pdp]
            [autho.prp :as prp]
            [clojure.string :as str]))

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

(defn- rule-impact
  [changes]
  (let [winning (frequencies (mapcat :winningRuleNames changes))
        losing (frequencies (mapcat :losingRuleNames changes))]
    {:winningRules (->> winning
                        (mapv (fn [[rule-name count]]
                                {:ruleName rule-name :count count}))
                        (sort-by (fn [{:keys [ruleName count]}] [(- count) ruleName]))
                        vec)
     :losingRules (->> losing
                       (mapv (fn [[rule-name count]]
                               {:ruleName rule-name :count count}))
                       (sort-by (fn [{:keys [ruleName count]}] [(- count) ruleName]))
                       vec)}))

(defn- sensitive-resource?
  [authz-request]
  (let [resource (or (:resource authz-request) {})
        classification (some-> (or (:classification resource)
                                   (:sensitivity resource)
                               (:risk resource))
                               str
                               str/lower-case)]
    (boolean (or (:sensitive resource)
                 (contains? #{"restricted" "confidential" "secret" "high" "critical"}
                            classification)))))

(defn- impacted-sensitive-resources
  [changes]
  (->> changes
       (filter #(sensitive-resource? (:request %)))
       (map (fn [change]
              {:key (resource-key (:request change))
               :changeCategory (:changeCategory change)
               :changeType (:changeType change)
               :subject (subject-key (:request change))
               :operation (or (get-in change [:request :operation]) "*")}))
       vec))

(defn- threshold-value
  [thresholds key default]
  (if (contains? thresholds key)
    (get thresholds key)
    default))

(defn- build-impact-report
  [summary blast-radius changes thresholds]
  (let [thresholds (or thresholds {})
        sensitive-resources (impacted-sensitive-resources changes)
        max-revokes (threshold-value thresholds :maxRevokes 0)
        max-changed (threshold-value thresholds :maxChangedDecisions 50)
        allow-sensitive? (threshold-value thresholds :allowSensitiveResourceChanges false)
        blockers (cond-> []
                   (> (:revokes summary) max-revokes)
                   (conj {:code "REVOKE_THRESHOLD_EXCEEDED"
                          :message (str "Revokes exceed threshold " max-revokes ".")
                          :actual (:revokes summary)
                          :threshold max-revokes})

                   (> (:changedDecisions summary) max-changed)
                   (conj {:code "CHANGED_DECISIONS_THRESHOLD_EXCEEDED"
                          :message (str "Changed decisions exceed threshold " max-changed ".")
                          :actual (:changedDecisions summary)
                          :threshold max-changed})

                   (and (seq sensitive-resources) (not allow-sensitive?))
                   (conj {:code "SENSITIVE_RESOURCE_IMPACT"
                          :message "Sensitive resources are impacted."
                          :actual (count sensitive-resources)
                          :threshold 0}))
        review-required? (or (seq blockers)
                             (pos? (:changedDecisions summary))
                             (pos? (:revokes summary)))
        status (cond
                 (seq blockers) "blocked"
                 (pos? (:revokes summary)) "high_risk"
                 (pos? (:changedDecisions summary)) "review_required"
                 :else "no_impact")]
    {:status status
     :recommendation (cond
                       (seq blockers) "block"
                       review-required? "review"
                       :else "approve")
     :reviewRequired (boolean review-required?)
     :blockers blockers
     :thresholds {:maxRevokes max-revokes
                  :maxChangedDecisions max-changed
                  :allowSensitiveResourceChanges allow-sensitive?}
     :sensitiveResourcesImpacted sensitive-resources
     :rulesResponsible (rule-impact changes)
     :populationsTouched (:subjects blast-radius)
     :resourcesTouched (:resources blast-radius)}))

(defn- audit-replay-source
  [resource-class audit-replay]
  (let [filters (cond-> audit-replay
                  resource-class (assoc :resource-class resource-class))]
    (audit/replay-requests filters)))

(defn- request-source
  [resource-class {:keys [requests auditReplay]}]
  (cond
    (vector? requests)
    {:type "provided"
     :requests requests}

    auditReplay
    (let [replay (audit-replay-source resource-class auditReplay)]
      {:type "audit"
       :requests (:requests replay)
       :auditReplay replay})

    :else
    {:type "provided"
     :requests requests}))

(defn analyze-impact
  [request {:keys [resourceClass] :as body}]
  (let [resource-class (or resourceClass (get-in body [:resource :class]))
        base-policy (baseline-policy resource-class body)
        next-policy (candidate-policy resource-class body)
        source (request-source resource-class body)
        requests (:requests source)]
    (when-not resource-class
      (throw (ex-info "Impact analysis requires resourceClass"
                      {:status 400 :error-code "MISSING_RESOURCE_CLASS"})))
    (when-not (and (vector? requests) (seq requests))
      (throw (ex-info "Impact analysis requires a non-empty requests vector or auditReplay source"
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
          blast-radius (build-blast-radius changed)
          impact-report (build-impact-report summary blast-radius (vec changed) (:thresholds body))]
      {:resourceClass resource-class
       :requestSource (dissoc source :requests)
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
       :impactReport impact-report
       :changes (vec changed)})))
