(ns autho.policy-tests
  "Declarative policy scenario tests executed before policy persistence."
  (:require [clojure.string :as str]))

(defn- rule-applies-to-operation?
  [operation rule]
  (let [rule-op (:operation rule)]
    (or (nil? operation)
        (nil? rule-op)
        (= rule-op operation))))

(defn- resolve-conflict
  [policy matched-rules]
  (if (empty? matched-rules)
    false
    (case (cond-> (:strategy policy)
            (string? (:strategy policy)) keyword)
      :almost_one_allow_no_deny
      (if-not (some #(= "allow" (:effect %)) matched-rules)
        false
        (let [allow-rules (filter #(= "allow" (:effect %)) matched-rules)
              deny-rules (filter #(= "deny" (:effect %)) matched-rules)
              max-allow (apply max-key :priority allow-rules)
              max-deny (if (seq deny-rules)
                         (apply max-key :priority deny-rules)
                         {:priority -1000})]
          (>= (:priority max-allow) (:priority max-deny))))

      false)))

(defn- expected-decision
  [scenario]
  (let [expect (:expect scenario)]
    (cond
      (map? expect) (or (:decisionType expect)
                        (:decision expect)
                        (when (contains? expect :allowed?)
                          (if (:allowed? expect) "allow" "deny"))
                        (when (contains? expect :allowed)
                          (if (:allowed expect) "allow" "deny")))
      (boolean? expect) (if expect "allow" "deny")
      (keyword? expect) (name expect)
      (string? expect) expect
      :else nil)))

(defn- evaluate-scenario
  [policy scenario]
  (let [operation (:operation scenario)
        request {:subject (:subject scenario)
                 :resource (:resource scenario)
                 :operation operation
                 :context (:context scenario)}
        candidate-rules (filter #(rule-applies-to-operation? operation %) (:rules policy))
        evaluate-rule (requiring-resolve 'autho.jsonrule/evaluateRule)
        evaluated (mapv (fn [candidate]
                          (let [result (evaluate-rule candidate request)]
                            {:rule candidate
                             :matched (boolean (:value result))}))
                        candidate-rules)
        matched-rules (mapv :rule (filter :matched evaluated))
        allowed? (boolean (resolve-conflict policy matched-rules))]
    {:allowed? allowed?
     :decisionType (if allowed? "allow" "deny")
     :matchedRuleNames (mapv :name matched-rules)}))

(defn- scenario-issue
  [scenario index actual expected]
  {:code "POLICY_TEST_FAILED"
   :message (str "Policy test '" (or (:name scenario) (str "#" index))
                 "' expected '" expected "' but got '" (:decisionType actual) "'.")
   :severity :error
   :test-index index
   :test-name (:name scenario)
   :expected expected
   :actual (:decisionType actual)
   :matchedRuleNames (:matchedRuleNames actual)})

(defn- invalid-scenario-issue
  [scenario index message]
  {:code "INVALID_POLICY_TEST"
   :message message
   :severity :error
   :test-index index
   :test-name (:name scenario)})

(defn- evaluation-issue
  [scenario index e]
  {:code "POLICY_TEST_EVALUATION_ERROR"
   :message (str "Policy test '" (or (:name scenario) (str "#" index))
                 "' could not be evaluated: " (.getMessage e))
   :severity :error
   :test-index index
   :test-name (:name scenario)})

(defn- validate-scenario-shape
  [scenario index]
  (cond
    (not (map? scenario))
    [(invalid-scenario-issue scenario index "Policy test must be an object.")]

    (nil? (:subject scenario))
    [(invalid-scenario-issue scenario index "Policy test must include subject.")]

    (nil? (:resource scenario))
    [(invalid-scenario-issue scenario index "Policy test must include resource.")]

    (nil? (:operation scenario))
    [(invalid-scenario-issue scenario index "Policy test must include operation.")]

    (nil? (expected-decision scenario))
    [(invalid-scenario-issue scenario index "Policy test must include expect as allow/deny or boolean.")]

    :else []))

(defn run-policy-tests
  [policy]
  (let [scenarios (vec (or (:tests policy) []))]
    (loop [index 0
           remaining scenarios
           results []
           errors []]
      (if (empty? remaining)
        {:count (count scenarios)
         :passed (- (count scenarios) (count errors))
         :failed (count errors)
         :results results
         :errors errors}
        (let [scenario (first remaining)
              shape-errors (validate-scenario-shape scenario index)]
          (if (seq shape-errors)
            (recur (inc index)
                   (rest remaining)
                   results
                   (into errors shape-errors))
            (let [expected (expected-decision scenario)
                  evaluation (try
                               {:actual (evaluate-scenario policy scenario)}
                               (catch Exception e
                                 {:issue (evaluation-issue scenario index e)}))]
              (if-let [issue (:issue evaluation)]
                (recur (inc index)
                       (rest remaining)
                       results
                       (conj errors issue))
                (let [actual (:actual evaluation)
                      passed? (= expected (:decisionType actual))
                      result (merge {:index index
                                     :name (:name scenario)
                                     :expected expected
                                     :passed passed?}
                                    actual)]
                  (recur (inc index)
                         (rest remaining)
                         (conj results result)
                         (cond-> errors
                           (not passed?)
                           (conj (scenario-issue scenario index actual expected)))))))))))))

(defn validate-policy-tests!
  [policy]
  (let [{:keys [errors] :as analysis} (run-policy-tests policy)]
    (when (seq errors)
      (throw (ex-info
              (str "Policy tests failed: "
                   (str/join "; " (map :message (take 3 errors)))
                   (when (> (count errors) 3)
                     (str " (and " (- (count errors) 3) " more issues)")))
              {:status 400
               :error-code "POLICY_TESTS_FAILED"
               :issues errors
               :analysis analysis})))
    analysis))
