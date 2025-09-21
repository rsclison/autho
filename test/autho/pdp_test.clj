(ns autho.pdp-test
  (:require [clojure.test :refer :all]
            [autho.pdp :refer :all]
            [autho.prp :as prp]
            [autho.jsonrule :as rule]
            [autho.delegation :as deleg]))

(deftest evalRequest-test
  (testing "evalRequest function"
    (let [request {:subject {:id "user1"} :resource {:class "doc"} :operation "read"}]
      (testing "when a global policy allows the request"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "allow-rule" :effect "allow" :priority 1}]
                                             :strategy :almost_one_allow_no_deny})
                      rule/evaluateRule (fn [rule req] {:value true})
                      deleg/findDelegation (fn [subject] [])]
          (is (= {:result true :rules [{:name "allow-rule" :effect "allow" :priority 1}]}
                 (evalRequest request)))))
      (testing "when a global policy denies the request"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "deny-rule" :effect "deny" :priority 1}]
                                             :strategy :almost_one_allow_no_deny})
                      rule/evaluateRule (fn [rule req] {:value true})
                      deleg/findDelegation (fn [subject] [])]
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
                                            {:value false}))]
          (is (= {:result true :rules [{:name "allow-rule" :effect "allow" :priority 1}]}
                 (evalRequest request)))))
      (testing "with no applicable policy"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class] nil)
                      deleg/findDelegation (fn [subject] [])]
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
                      deleg/findDelegation (fn [s] [])]
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

(deftest whichAuthorized-test
  (testing "whichAuthorized function"
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
                 (whichAuthorized ring-req request)))))
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
                 (whichAuthorized ring-req request)))))
      (testing "when both allow and deny rules match"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class]
                                            {:rules [{:name "allow-rule" :effect "allow" :resourceClass "doc" :resourceCond [:and [:eq "public" true]] :operation "read"}
                                                     {:name "deny-rule" :effect "deny" :resourceClass "doc" :resourceCond [:and [:eq "private" true]] :operation "read"}]})
                      rule/evalRuleWithSubject (fn [rule req] rule)]
          (is (= {:allow [{:resourceClass "doc" :resourceCond '([:eq "public" true]) :operation "read"}]
                  :deny [{:resourceClass "doc" :resourceCond '([:eq "private" true]) :operation "read"}]}
                 (whichAuthorized ring-req request)))))
      (testing "when no rule is applicable"
        (with-redefs [prp/getGlobalPolicy (fn [resource-class] {:rules []})
                      rule/evalRuleWithSubject (fn [rule req] nil)]
          (is (= {:allow [] :deny []} (whichAuthorized ring-req request)))))
      (testing "when request has no subject"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No subject specified"
                                (whichAuthorized ring-req {})))))))
