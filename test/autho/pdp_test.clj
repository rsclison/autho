(ns autho.pdp-test
  (:require [clojure.test :refer :all]
            [autho.pdp :refer :all]
            [autho.prp :as prp]
            [autho.jsonrule :as rule]
            [autho.delegation :as deleg]
            [autho.local-cache :as local-cache]
            [autho.metrics :as metrics]
            [autho.audit :as audit]
            [autho.policy-versions :as pv]))

(deftest evalRequest-test
  (testing "evalRequest function"
    (let [request {:subject {:id "user1"} :resource {:class "doc"} :operation "read"}]
      (testing "when a global policy allows the request"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "allow-rule" :effect "allow" :priority 1}]
                                             :strategy :almost_one_allow_no_deny})
                      rule/evaluateRule (fn [rule req] {:value true})
                      deleg/findDelegation (fn [subject] [])
                      metrics/record-decision! (fn [& _] nil)
                      audit/log-decision! (fn [& _] nil)]
          (is (= {:result true :rules [{:name "allow-rule" :effect "allow" :priority 1}]}
                 (evalRequest request)))))
      (testing "when a global policy denies the request"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "deny-rule" :effect "deny" :priority 1}]
                                             :strategy :almost_one_allow_no_deny})
                      rule/evaluateRule (fn [rule req] {:value true})
                      deleg/findDelegation (fn [subject] [])
                      metrics/record-decision! (fn [& _] nil)
                      audit/log-decision! (fn [& _] nil)]
          (is (= {:result false :rules [{:name "deny-rule" :effect "deny" :priority 1}]}
                 (evalRequest request)))))
      (testing "when delegation allows the request"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "allow-rule" :effect "allow" :priority 1}]
                                             :strategy :almost_one_allow_no_deny})
                      deleg/findDelegation (fn [subject]
                                             (if (= (:id subject) "user1")
                                               [{:delegate {:id "user2"}}]
                                               []))
                      rule/evaluateRule (fn [rule req]
                                          (if (= (:id (:subject req)) "user2")
                                            {:value true}
                                            {:value false}))
                      metrics/record-decision! (fn [& _] nil)
                      audit/log-decision! (fn [& _] nil)]
          (is (= {:result true :rules [{:name "allow-rule" :effect "allow" :priority 1}]}
                 (evalRequest request)))))
      (testing "with no applicable policy"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class] nil)
                      deleg/findDelegation (fn [subject] [])
                      metrics/record-decision! (fn [& _] nil)
                      audit/log-decision! (fn [& _] nil)]
          (is (= {:result false :rules []}
                 (evalRequest request)))))
      (testing "with no subject"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No subject specified"
                                (evalRequest {:resource {:class "doc"}}))))
      (testing "with no resource"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No resource specified"
                                (evalRequest {:subject {:id "user1"}}))))
      (testing "simple request from original test"
        (with-redefs [prp/getGlobalPolicy (fn [c] {:rules []})
                      prp/getPolicy (fn [c a] {:rules []})
                      deleg/findDelegation (fn [s] [])
                      metrics/record-decision! (fn [& _] nil)
                      audit/log-decision! (fn [& _] nil)]
          (is (= false (:result (evalRequest {:subject {:id "Mary", :role "Professeur"} :resource {:class "Note"} :operation "lire" :context {:date "2019-08-14T04:03:27.456"}})))))))))

(deftest resolve-conflict-test
  (testing "resolve-conflict function"
    (testing "with no successful rules"
      (is (= false (resolve-conflict {:strategy :almost_one_allow_no_deny} []))))

    (testing "with :almost_one_allow_no_deny strategy"
      (let [policy {:strategy :almost_one_allow_no_deny}]
        (testing "one allow rule, no deny rules"
          (let [rules [{:effect "allow" :priority 1}]]
            (is (= true (resolve-conflict policy rules)))))

        (testing "one allow rule, one deny rule with lower priority"
          (let [rules [{:effect "allow" :priority 2} {:effect "deny" :priority 1}]]
            (is (= true (resolve-conflict policy rules)))))

        (testing "one allow rule, one deny rule with higher priority"
          (let [rules [{:effect "allow" :priority 1} {:effect "deny" :priority 2}]]
            (is (= false (resolve-conflict policy rules)))))

        (testing "no allow rules, one deny rule"
          (let [rules [{:effect "deny" :priority 1}]]
            (is (= false (resolve-conflict policy rules)))))

        (testing "multiple allow and deny rules"
          (let [rules [{:effect "allow" :priority 1} {:effect "deny" :priority 2} {:effect "allow" :priority 3}]]
            (is (= true (resolve-conflict policy rules)))))))

    (testing "with :default strategy"
      (let [policy {:strategy :default}
            rules [{:effect "allow" :priority 1}]]
        (is (= false (resolve-conflict policy rules)))))))

(deftest whoAuthorized-test
  (testing "whoAuthorized function"
    (let [request {:resource {:class "doc"}}
          ring-req {:identity {:auth-method :api-key}}]
      (testing "when a rule allows the request"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "allow-rule"
                                                      :effect "allow"
                                                      :resourceClass "doc"
                                                      :subjectCond [:and [:eq "role" "user"]]
                                                      :operation "read"}]})
                      rule/evalRuleWithResource (fn [rule req] true)]
          (is (= [{:resourceClass "doc" :subjectCond '([:eq "role" "user"]) :operation "read"}]
                 (whoAuthorized ring-req request)))))
      (testing "when no rule is applicable"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "allow-rule"
                                                      :effect "allow"
                                                      :resourceClass "doc"
                                                      :subjectCond [:and [:eq "role" "user"]]
                                                      :operation "read"}]})
                      rule/evalRuleWithResource (fn [rule req] false)]
          (is (= [] (whoAuthorized ring-req request)))))
      (testing "when no allow rule exists"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "deny-rule"
                                                      :effect "deny"
                                                      :resourceClass "doc"
                                                      :subjectCond [:and [:eq "role" "admin"]]
                                                      :operation "read"}]})
                      rule/evalRuleWithResource (fn [rule req] true)]
          (is (= [] (whoAuthorized ring-req request)))))
      (testing "when request has no resource"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No resource specified"
                                (whoAuthorized ring-req {})))))))

(deftest whatAuthorized-test
  (testing "whatAuthorized function"
    (let [request {:subject {:id "user1"}}
          ring-req {:identity {:auth-method :api-key}}]
      (testing "when a rule allows the request"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "allow-rule"
                                                      :effect "allow"
                                                      :resourceClass "doc"
                                                      :resourceCond [:and [:eq "public" true]]
                                                      :operation "read"}]})
                      rule/evalRuleWithSubject (fn [rule req]
                                                 (when (= (:effect rule) "allow") rule))]
          (is (= {:allow [{:resourceClass "doc"
                           :resourceCond '([:eq "public" true])
                           :operation "read"}]
                  :deny []}
                 (whatAuthorized ring-req request)))))
      (testing "when a rule denies the request"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "deny-rule"
                                                      :effect "deny"
                                                      :resourceClass "doc"
                                                      :resourceCond [:and [:eq "private" true]]
                                                      :operation "read"}]})
                      rule/evalRuleWithSubject (fn [rule req]
                                                 (when (= (:effect rule) "deny") rule))]
          (is (= {:allow []
                  :deny [{:resourceClass "doc"
                          :resourceCond '([:eq "private" true])
                          :operation "read"}]}
                 (whatAuthorized ring-req request)))))
      (testing "when both allow and deny rules match"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "allow-rule" :effect "allow" :resourceClass "doc" :resourceCond [:and [:eq "public" true]] :operation "read"}
                                                     {:name "deny-rule" :effect "deny" :resourceClass "doc" :resourceCond [:and [:eq "private" true]] :operation "read"}]})
                      rule/evalRuleWithSubject (fn [rule req] rule)]
          (is (= {:allow [{:resourceClass "doc" :resourceCond '([:eq "public" true]) :operation "read"}]
                  :deny [{:resourceClass "doc" :resourceCond '([:eq "private" true]) :operation "read"}]}
                 (whatAuthorized ring-req request)))))
      (testing "when no rule is applicable"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class] {:rules []})
                      rule/evalRuleWithSubject (fn [rule req] nil)]
          (is (= {:allow [] :deny []} (whatAuthorized ring-req request)))))
      (testing "when request has no subject"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No subject specified"
                                (whatAuthorized ring-req {})))))))

(deftest circular-delegation-test
  (testing "Circular delegation detection"
    (let [request {:subject {:id "user1"} :resource {:class "doc"} :operation "read"}]
      (testing "two-way circular delegation does not cause infinite recursion"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "allow-rule" :effect "allow" :priority 1}]
                                             :strategy :almost_one_allow_no_deny})
                      prp/getPolicy (fn [c a] {:rules []})
                      deleg/findDelegation (fn [subject]
                                             ;; Create circular delegation: user1 -> user2 -> user1
                                             (cond
                                               (= (:id subject) "user1") [{:delegate {:id "user2"}}]
                                               (= (:id subject) "user2") [{:delegate {:id "user1"}}]
                                               :else []))
                      rule/evaluateRule (fn [rule req] {:value false})
                      metrics/record-decision! (fn [& _] nil)
                      audit/log-decision! (fn [& _] nil)]
          ;; Should not cause infinite recursion and should return false
          (is (= {:result false :rules []}
                 (evalRequest request)))))
      (testing "three-way circular delegation does not cause infinite recursion"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "allow-rule" :effect "allow" :priority 1}]
                                             :strategy :almost_one_allow_no_deny})
                      prp/getPolicy (fn [c a] {:rules []})
                      deleg/findDelegation (fn [subject]
                                             ;; Create three-way circular: user1 -> user2 -> user3 -> user1
                                             (cond
                                               (= (:id subject) "user1") [{:delegate {:id "user2"}}]
                                               (= (:id subject) "user2") [{:delegate {:id "user3"}}]
                                               (= (:id subject) "user3") [{:delegate {:id "user1"}}]
                                               :else []))
                      rule/evaluateRule (fn [rule req] {:value false})
                      metrics/record-decision! (fn [& _] nil)
                      audit/log-decision! (fn [& _] nil)]
          ;; Should not cause infinite recursion and should return false
          (is (= {:result false :rules []}
                 (evalRequest request)))))
      (testing "valid delegation chain still works"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "allow-rule" :effect "allow" :priority 1}]
                                             :strategy :almost_one_allow_no_deny})
                      prp/getPolicy (fn [c a] {:rules []})
                      deleg/findDelegation (fn [subject]
                                             ;; Valid chain: user1 -> user2 -> user3
                                             (cond
                                               (= (:id subject) "user1") [{:delegate {:id "user2"}}]
                                               (= (:id subject) "user2") [{:delegate {:id "user3"}}]
                                               :else []))
                      rule/evaluateRule (fn [rule req]
                                          (if (= (:id (:subject req)) "user3")
                                            {:value true}
                                            {:value false}))
                      metrics/record-decision! (fn [& _] nil)
                      audit/log-decision! (fn [& _] nil)]
          ;; Should successfully find authorization through user3
          (is (= {:result true :rules [{:name "allow-rule" :effect "allow" :priority 1}]}
                 (evalRequest request))))))))


(deftest isAuthorized-shared-decision-test
  (testing "isAuthorized uses the shared decision semantics"
    (let [body {:subject {:id "user1"}
                :resource {:class "doc" :id "doc-1"}
                :operation "read"}
          ring-req {:identity {:auth-method :api-key}}]
      (with-redefs [prp/getGlobalPolicy (fn [_]
                                          {:rules [{:name "allow-rule" :effect "allow" :priority 1 :operation "read"}
                                                   {:name "deny-rule" :effect "deny" :priority 5 :operation "read"}]
                                           :strategy :almost_one_allow_no_deny})
                    deleg/findDelegation (fn [_] [])
                    local-cache/get-cached-decision (fn [& _] nil)
                    local-cache/cache-decision! (fn [& _] nil)
                    metrics/record-decision! (fn [& _] nil)
                    audit/log-decision! (fn [& _] nil)
                    rule/evaluateRule (fn [rule _] {:value (contains? #{"allow-rule" "deny-rule"} (:name rule))})]
        (let [result (isAuthorized ring-req body)]
          (is (= false (:allowed result)))
          (is (= "deny" (:decision result)))
          (is (= ["allow-rule" "deny-rule"] (:results result)))
          (is (= ["allow-rule" "deny-rule"] (:matchedRules result)))
          (is (= "doc" (:resourceClass result)))
          (is (= "doc-1" (:resourceId result)))
          (is (= "read" (:operation result))))))))

(deftest explain-and-simulate-share-canonical-evaluation-test
  (testing "explain and simulate project the same canonical decision"
    (let [body {:subject {:id "user1"}
                :resource {:class "doc" :id "doc-1"}
                :operation "read"}
          ring-req {:identity {:auth-method :api-key}}
          policy {:rules [{:name "allow-rule" :effect "allow" :priority 10 :operation "read"}
                          {:name "deny-rule" :effect "deny" :priority 1 :operation "read"}]
                  :strategy :almost_one_allow_no_deny}]
      (with-redefs [prp/getGlobalPolicy (fn [_] policy)
                    deleg/findDelegation (fn [_] [])
                    metrics/record-decision! (fn [& _] nil)
                    audit/log-decision! (fn [& _] nil)
                    rule/evaluateRule (fn [rule _] {:value (contains? #{"allow-rule" "deny-rule"} (:name rule))})
                    pv/latest-version-number (fn [_] 7)]
        (let [explain-result (explain ring-req body)
              simulate-result (simulate ring-req body)]
          (is (= true (:decision explain-result)))
          (is (= true (:allowed? explain-result)))
          (is (= "allow" (:decisionType explain-result)))
          (is (= true (:decision simulate-result)))
          (is (= true (:allowed? simulate-result)))
          (is (= "allow" (:decisionType simulate-result)))
          (is (= (:matchedRuleNames explain-result)
                 (:matchedRuleNames simulate-result))))))))


(deftest whoAuthorizedDetailed-test
  (testing "whoAuthorizedDetailed exposes allow and deny candidates"
    (let [request {:resource {:class "doc"}}
          ring-req {:identity {:auth-method :api-key}}]
      (with-redefs [prp/getGlobalPolicy (fn [_]
                                          {:rules [{:name "allow-rule"
                                                    :effect "allow"
                                                    :resourceClass "doc"
                                                    :subjectCond [:and [:eq "role" "user"]]
                                                    :operation "read"}
                                                   {:name "deny-rule"
                                                    :effect "deny"
                                                    :resourceClass "doc"
                                                    :subjectCond [:and [:eq "role" "suspended"]]
                                                    :operation "read"}]
                                           :strategy :almost_one_allow_no_deny})
                    rule/evalRuleWithResource (fn [rule _] true)]
        (let [result (whoAuthorizedDetailed ring-req request)]
          (is (= :almost_one_allow_no_deny (:strategy result)))
          (is (= "doc" (:resourceClass result)))
          (is (nil? (:operation result)))
          (is (= [{:resourceClass "doc"
                   :subjectCond '([:eq "role" "user"])
                   :operation "read"}]
                 (:allowCandidates result)))
          (is (= [{:resourceClass "doc"
                   :subjectCond '([:eq "role" "suspended"])
                   :operation "read"}]
                 (:denyCandidates result))))))))

(deftest whatAuthorizedDetailed-test
  (testing "whatAuthorizedDetailed preserves strategy alongside allow and deny projections"
    (let [request {:subject {:id "user1"}
                   :resource {:class "doc"}}
          ring-req {:identity {:auth-method :api-key}}]
      (with-redefs [prp/getGlobalPolicy (fn [_]
                                          {:rules [{:name "allow-rule"
                                                    :effect "allow"
                                                    :resourceClass "doc"
                                                    :resourceCond [:and [:eq "public" true]]
                                                    :operation "read"}
                                                   {:name "deny-rule"
                                                    :effect "deny"
                                                    :resourceClass "doc"
                                                    :resourceCond [:and [:eq "private" true]]
                                                    :operation "read"}]
                                           :strategy :almost_one_allow_no_deny})
                    rule/evalRuleWithSubject (fn [rule _] rule)]
        (let [result (whatAuthorizedDetailed ring-req (assoc request :operation "read"))]
          (is (= :almost_one_allow_no_deny (:strategy result)))
          (is (= "doc" (:resourceClass result)))
          (is (= "read" (:operation result)))
          (is (= 1 (:page result)))
          (is (= 20 (:pageSize result)))
          (is (= [{:resourceClass "doc"
                   :resourceCond '([:eq "public" true])
                   :operation "read"}]
                 (:allow result)))
          (is (= [{:resourceClass "doc"
                   :resourceCond '([:eq "private" true])
                   :operation "read"}]
                 (:deny result))))))))


(deftest detailed-authorization-contract-test
  (testing "Detailed authorization helpers expose stable context fields"
    (let [who-request {:resource {:class "doc"}
                       :operation "read"}
          what-request {:subject {:id "user1"}
                        :resource {:class "doc"}
                        :operation "read"}
          ring-req {:identity {:auth-method :api-key}}]
      (with-redefs [prp/getGlobalPolicy (fn [_]
                                          {:rules [{:name "allow-rule"
                                                    :effect "allow"
                                                    :resourceClass "doc"
                                                    :subjectCond [:and [:eq "role" "user"]]
                                                    :resourceCond [:and [:eq "public" true]]
                                                    :operation "read"}]
                                           :strategy :almost_one_allow_no_deny})
                    rule/evalRuleWithResource (fn [_ _] true)
                    rule/evalRuleWithSubject (fn [rule _] rule)]
        (let [who-result (whoAuthorizedDetailed ring-req who-request)
              what-result (whatAuthorizedDetailed ring-req what-request)]
          (is (= :almost_one_allow_no_deny (:strategy who-result)))
          (is (= "doc" (:resourceClass who-result)))
          (is (= "read" (:operation who-result)))
          (is (= :almost_one_allow_no_deny (:strategy what-result)))
          (is (= "doc" (:resourceClass what-result)))
          (is (= "read" (:operation what-result)))
          (is (= 1 (:page what-result)))
          (is (= 20 (:pageSize what-result))))))))
