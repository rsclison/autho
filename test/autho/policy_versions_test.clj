(ns autho.policy-versions-test
  (:require [clojure.test :refer :all]
            [autho.policy-versions :as pv]))

(deftest diff-versions-returns-detailed-structural-diff-test
  (with-redefs [pv/get-version (fn [_ version]
                                 (case version
                                   1 {:resourceClass "Document"
                                      :strategy "almost_one_allow_no_deny"
                                      :rules [{:name "allow-read"
                                               :effect "allow"
                                               :operation "read"
                                               :subjectCond [["=" "role" "reader"]]}
                                              {:name "deny-write"
                                               :effect "deny"
                                               :operation "write"
                                               :subjectCond [["=" "role" "reader"]]}]}
                                   2 {:resourceClass "Document"
                                      :strategy "deny_unless_allow"
                                      :rules [{:name "allow-read"
                                               :effect "allow"
                                               :operation "read"
                                               :subjectCond [["=" "role" "employee"]]}
                                              {:name "allow-share"
                                               :effect "allow"
                                               :operation "share"
                                               :subjectCond [["=" "role" "manager"]]}]}
                                   nil))]
    (let [result (pv/diff-versions "Document" 1 2)]
      (is (= "Document" (:resourceClass result)))
      (is (= {:from "almost_one_allow_no_deny"
              :to "deny_unless_allow"
              :changed true}
             (:strategy result)))
      (is (= 1 (get-in result [:summary :addedRules])))
      (is (= 1 (get-in result [:summary :removedRules])))
      (is (= 1 (get-in result [:summary :changedRules])))
      (is (= 0 (get-in result [:summary :unchangedRules])))
      (is (= 3 (get-in result [:summary :totalRuleChanges])))
      (is (= "allow-share" (get-in result [:rules :added 0 :name])))
      (is (= "deny-write" (get-in result [:rules :removed 0 :name])))
      (is (= "allow-read" (get-in result [:rules :changed 0 :name])))
      (is (= ["subjectCond"] (get-in result [:rules :changed 0 :changedFields]))))))

(deftest diff-versions-tracks-unchanged-rules-test
  (with-redefs [pv/get-version (fn [_ version]
                                 (case version
                                   1 {:strategy "almost_one_allow_no_deny"
                                      :rules [{:name "allow-read"
                                               :effect "allow"
                                               :operation "read"}
                                              {:name "allow-comment"
                                               :effect "allow"
                                               :operation "comment"}]}
                                   2 {:strategy "almost_one_allow_no_deny"
                                      :rules [{:name "allow-read"
                                               :effect "allow"
                                               :operation "read"}
                                              {:name "allow-comment"
                                               :effect "allow"
                                               :operation "comment"}]}
                                   nil))]
    (let [result (pv/diff-versions "Document" 1 2)]
      (is (false? (get-in result [:strategy :changed])))
      (is (= 2 (count (get-in result [:rules :unchanged]))))
      (is (= 0 (get-in result [:summary :totalRuleChanges]))))))
