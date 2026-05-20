(ns autho.policy-impact-test
  (:require [clojure.test :refer :all]
            [autho.audit :as audit]
            [autho.policy-impact :as impact]
            [autho.pdp :as pdp]
            [autho.prp :as prp]
            [autho.policy-versions :as pv]))

(deftest analyze-impact-compares-before-and-after-decisions-test
  (let [request {}
        body {:resourceClass "Document"
              :requests [{:subject {:id "user1"}
                          :resource {:class "Document" :id "doc-1"}
                          :operation "read"}
                         {:subject {:id "user2"}
                          :resource {:class "Document" :id "doc-2"}
                          :operation "read"}]
              :candidatePolicy {:strategy "permit-unless-deny"}}
        current-policy {:strategy "almost_one_allow_no_deny"}]
    (with-redefs [prp/getGlobalPolicy (fn [_] current-policy)
                  pdp/simulate (fn [_ simulate-body]
                                 (if (= current-policy (:simulatedPolicy simulate-body))
                                   {:allowed? false :decisionType "deny" :matchedRuleNames ["old-rule"]}
                                   {:allowed? true :decisionType "allow" :matchedRuleNames ["new-rule"]}))]
      (let [result (impact/analyze-impact request body)]
        (is (= "Document" (:resourceClass result)))
        (is (= 2 (get-in result [:summary :totalRequests])))
        (is (= 2 (get-in result [:summary :changedDecisions])))
        (is (= 2 (get-in result [:summary :grants])))
        (is (= 0 (get-in result [:summary :revokes])))
        (is (= 2 (count (:changes result))))
        (is (= "deny_to_allow" (get-in result [:changes 0 :changeCategory])))
        (is (= ["new-rule"] (get-in result [:changes 0 :winningRuleNames])))
        (is (= ["old-rule"] (get-in result [:changes 0 :losingRuleNames])))
        (is (= 2 (get-in result [:summary :byOperation "read" :grants])))
        (is (= 1 (get-in result [:blastRadius :subjects "user1" :grants])))
        (is (= 1 (get-in result [:blastRadius :resources "Document/doc-1" :grants])))
        (is (false? (get-in result [:riskSignals :highRisk])))
        (is (= 2 (get-in result [:riskSignals :changedSubjectCount])))
        (is (= "review_required" (get-in result [:impactReport :status])))
        (is (= "review" (get-in result [:impactReport :recommendation])))
        (is (= "new-rule" (get-in result [:impactReport :rulesResponsible :winningRules 0 :ruleName])))
        (is (= "user1" (get-in result [:riskSignals :topImpactedSubjects 0 :key])))))))

(deftest analyze-impact-uses-versioned-baseline-and-candidate-test
  (with-redefs [pv/get-version (fn [_ version]
                                 (case version
                                   1 {:strategy "deny-unless-permit"}
                                   2 {:strategy "permit-unless-deny"}
                                   nil))
                pdp/simulate (fn [_ simulate-body]
                               (if (= "deny-unless-permit" (get-in simulate-body [:simulatedPolicy :strategy]))
                                 {:allowed? true :decisionType "allow" :matchedRuleNames ["allow-v1"]}
                                 {:allowed? false :decisionType "deny" :matchedRuleNames ["deny-v2"]}))]
    (let [result (impact/analyze-impact {} {:resourceClass "Document"
                                            :baselineVersion 1
                                            :candidateVersion 2
                                            :requests [{:subject {:id "user1"}
                                                        :resource {:class "Document" :id "doc-1"}
                                                        :operation "read"}]})]
      (is (= 1 (get-in result [:summary :revokes])))
      (is (= "version" (get-in result [:candidate :source])))
      (is (= "allow_to_deny" (get-in result [:changes 0 :changeCategory])))
      (is (= ["deny-v2"] (get-in result [:changes 0 :winningRuleNames])))
      (is (= ["allow-v1"] (get-in result [:changes 0 :losingRuleNames])))
      (is (true? (get-in result [:riskSignals :highRisk])))
      (is (= "blocked" (get-in result [:impactReport :status])))
      (is (= "block" (get-in result [:impactReport :recommendation])))
      (is (= "REVOKE_THRESHOLD_EXCEEDED"
             (get-in result [:impactReport :blockers 0 :code])))
      (is (= 1 (get-in result [:riskSignals :revokeCount]))))))

(deftest analyze-impact-summarizes-by-operation-test
  (let [current-policy {:strategy "almost_one_allow_no_deny"}
        candidate-policy {:strategy "permit-unless-deny"}]
    (with-redefs [prp/getGlobalPolicy (fn [_] current-policy)
                  pdp/simulate (fn [_ simulate-body]
                                 (let [policy (:simulatedPolicy simulate-body)
                                       operation (:operation simulate-body)]
                                   (cond
                                     (and (= current-policy policy) (= "read" operation))
                                     {:allowed? false :decisionType "deny" :matchedRuleNames ["read-old"]}

                                     (and (= candidate-policy policy) (= "read" operation))
                                     {:allowed? true :decisionType "allow" :matchedRuleNames ["read-new"]}

                                     (and (= current-policy policy) (= "write" operation))
                                     {:allowed? true :decisionType "allow" :matchedRuleNames ["write-old"]}

                                     :else
                                     {:allowed? false :decisionType "deny" :matchedRuleNames ["write-new"]})))]
      (let [result (impact/analyze-impact {} {:resourceClass "Document"
                                              :candidatePolicy candidate-policy
                                              :requests [{:subject {:id "user1"}
                                                          :resource {:class "Document" :id "doc-1"}
                                                          :operation "read"}
                                                         {:subject {:id "user1"}
                                                          :resource {:class "Document" :id "doc-1"}
                                                          :operation "write"}]})]
        (is (= 1 (get-in result [:summary :byOperation "read" :grants])))
        (is (= 1 (get-in result [:summary :byOperation "write" :revokes])))
        (is (= 1 (get-in result [:summary :byOperation "read" :changedDecisions])))
        (is (= 1 (get-in result [:summary :byOperation "write" :changedDecisions])))
        (is (= 2 (get-in result [:blastRadius :subjects "user1" :changedDecisions])))
        (is (= 1 (get-in result [:blastRadius :operations "read" :grants])))
        (is (= 1 (get-in result [:blastRadius :operations "write" :revokes])))
        (is (= 1 (get-in result [:riskSignals :changedSubjectCount])))
        (is (= 1 (get-in result [:riskSignals :changedResourceCount])))))))

(deftest analyze-impact-reports-sensitive-resource-blockers-test
  (let [current-policy {:strategy "almost_one_allow_no_deny" :id "current"}
        candidate-policy {:strategy "almost_one_allow_no_deny" :id "candidate"}]
    (with-redefs [prp/getGlobalPolicy (fn [_] current-policy)
                  pdp/simulate (fn [_ simulate-body]
                                 (if (= current-policy (:simulatedPolicy simulate-body))
                                   {:allowed? false :decisionType "deny" :matchedRuleNames ["old"]}
                                   {:allowed? true :decisionType "allow" :matchedRuleNames ["new"]}))]
      (let [result (impact/analyze-impact {} {:resourceClass "Document"
                                              :candidatePolicy candidate-policy
                                              :requests [{:subject {:id "user1"}
                                                          :resource {:class "Document"
                                                                     :id "secret-doc"
                                                                     :classification "secret"}
                                                          :operation "read"}]})]
        (is (= "blocked" (get-in result [:impactReport :status])))
        (is (= "SENSITIVE_RESOURCE_IMPACT"
               (get-in result [:impactReport :blockers 0 :code])))
        (is (= "Document/secret-doc"
               (get-in result [:impactReport :sensitiveResourcesImpacted 0 :key])))))))

(deftest analyze-impact-allows-threshold-overrides-test
  (let [current-policy {:strategy "almost_one_allow_no_deny" :id "current"}
        candidate-policy {:strategy "almost_one_allow_no_deny" :id "candidate"}]
    (with-redefs [prp/getGlobalPolicy (fn [_] current-policy)
                  pdp/simulate (fn [_ simulate-body]
                                 (if (= current-policy (:simulatedPolicy simulate-body))
                                   {:allowed? true :decisionType "allow" :matchedRuleNames ["old"]}
                                   {:allowed? false :decisionType "deny" :matchedRuleNames ["new"]}))]
      (let [result (impact/analyze-impact {} {:resourceClass "Document"
                                              :candidatePolicy candidate-policy
                                              :thresholds {:maxRevokes 1
                                                           :allowSensitiveResourceChanges true}
                                              :requests [{:subject {:id "user1"}
                                                          :resource {:class "Document"
                                                                     :id "secret-doc"
                                                                     :sensitive true}
                                                          :operation "read"}]})]
        (is (= "high_risk" (get-in result [:impactReport :status])))
        (is (= "review" (get-in result [:impactReport :recommendation])))
        (is (= [] (get-in result [:impactReport :blockers])))))))

(deftest analyze-impact-can-use-audit-replay-source-test
  (let [current-policy {:strategy "almost_one_allow_no_deny" :id "current"}
        candidate-policy {:strategy "almost_one_allow_no_deny" :id "candidate"}]
    (with-redefs [audit/replay-requests (fn [filters]
                                          {:requests [{:subject {:id "alice"}
                                                       :resource {:class "Document" :id "doc-1"}
                                                       :operation "read"
                                                       :context {:auditReplay true
                                                                 :auditId 42}}]
                                           :total 12
                                           :returned 1
                                           :filters filters})
                  prp/getGlobalPolicy (fn [_] current-policy)
                  pdp/simulate (fn [_ simulate-body]
                                 (if (= current-policy (:simulatedPolicy simulate-body))
                                   {:allowed? false :decisionType "deny" :matchedRuleNames ["old"]}
                                   {:allowed? true :decisionType "allow" :matchedRuleNames ["new"]}))]
      (let [result (impact/analyze-impact {} {:resourceClass "Document"
                                              :candidatePolicy candidate-policy
                                              :auditReplay {:decision "deny"
                                                            :limit 1}})]
        (is (= "audit" (get-in result [:requestSource :type])))
        (is (= 12 (get-in result [:requestSource :auditReplay :total])))
        (is (= "Document" (get-in result [:requestSource :auditReplay :filters :resource-class])))
        (is (= 1 (get-in result [:summary :grants])))
        (is (= true (get-in result [:changes 0 :request :context :auditReplay])))))))

(deftest analyze-impact-blast-radius-ignores-unchanged-decisions-test
  (let [current-policy {:strategy "almost_one_allow_no_deny"}
        candidate-policy {:strategy "permit-unless-deny"}]
    (with-redefs [prp/getGlobalPolicy (fn [_] current-policy)
                  pdp/simulate (fn [_ simulate-body]
                                 (let [policy (:simulatedPolicy simulate-body)
                                       resource-id (get-in simulate-body [:resource :id])]
                                   (cond
                                     (= resource-id "doc-1")
                                     (if (= current-policy policy)
                                       {:allowed? false :decisionType "deny" :matchedRuleNames ["old"]}
                                       {:allowed? true :decisionType "allow" :matchedRuleNames ["new"]})

                                     :else
                                     {:allowed? true :decisionType "allow" :matchedRuleNames ["same-rule"]}))) ]
      (let [result (impact/analyze-impact {} {:resourceClass "Document"
                                              :candidatePolicy candidate-policy
                                              :requests [{:subject {:id "user1"}
                                                          :resource {:class "Document" :id "doc-1"}
                                                          :operation "read"}
                                                         {:subject {:id "user2"}
                                                          :resource {:class "Document" :id "doc-2"}
                                                          :operation "read"}]})]
        (is (= 1 (count (:changes result))))
        (is (= ["user1"] (keys (get-in result [:blastRadius :subjects]))))
        (is (nil? (get-in result [:blastRadius :subjects "user2"])))
        (is (= 1 (get-in result [:blastRadius :resources "Document/doc-1" :changedDecisions])))
        (is (= 1 (count (get-in result [:riskSignals :topImpactedSubjects]))))))))
