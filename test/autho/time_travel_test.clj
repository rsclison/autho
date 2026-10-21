(ns autho.time-travel-test
  (:require [clojure.test :refer :all]
            [autho.audit :as audit]
            [autho.time-travel :as time-travel]))

(deftest audit-trail-reads-decisions-from-audit-log-test
  (let [captured (atom nil)]
    (with-redefs [audit/search (fn [filters]
                                 (reset! captured filters)
                                 {:total 2 :page 1 :pageSize 100
                                  :items [{:id 7 :ts "2026-03-15T10:30:00Z" :subject_id "alice"
                                           :operation "view" :decision "allow"
                                           :matched_rules ["R1"]
                                           :decision_snapshot {:delegationChain ["bob"] :grantedVia "bob"}}
                                          {:id 9 :ts "2026-03-20T14:22:13Z" :subject_id "carol"
                                           :operation "relation:viewer" :decision "deny"
                                           :matched_rules []
                                           :decision_snapshot {:plane "rebac" :status "denied"}}]})]
      (let [result (time-travel/audit-trail "Facture" "INV-1" "2026-03-01T00:00:00Z" "2026-03-31T23:59:59Z" {})]
        (is (= {:resource-class "Facture" :resource-id "INV-1"
                :from "2026-03-01T00:00:00Z" :to "2026-03-31T23:59:59Z"
                :order "asc" :page nil :page-size nil}
               @captured))
        (is (= "audit-trail" (:query result)))
        (is (= "audit-log" (:source result)))
        (is (= 2 (:total result)))
        (is (= [{:auditId 7 :timestamp "2026-03-15T10:30:00Z" :subject "alice" :action "view"
                 :decision "allow" :plane "abac" :matchedRules ["R1"]
                 :delegationChain ["bob"] :grantedVia "bob"}
                {:auditId 9 :timestamp "2026-03-20T14:22:13Z" :subject "carol" :action "relation:viewer"
                 :decision "deny" :plane "rebac" :matchedRules []}]
               (:events result)))))))

;; ---------------------------------------------------------------------------
;; Historical evaluation
;; ---------------------------------------------------------------------------

(def ^:private t-query "2026-03-15T10:30:00Z")

(def ^:private snapshot
  {"Person"  {"alice" {:role "manager"} "bob" {:role "clerk"}}
   "Facture" {"INV-1" {:amount 5000} "INV-2" {:amount 500}}})

(def ^:private facture-policy
  {:resourceClass "Facture"
   :strategy :almost_one_allow_no_deny
   :rules [{:name "R-approve" :effect "allow" :priority 1 :operation "approve"
            :conditions [["=" ["Person" "$s" "role"] "manager"]
                         [">" ["Facture" "$r" "amount"] "1000"]]}]})

(defn- ts [s] (java.sql.Timestamp/from (java.time.Instant/parse s)))

(defmacro with-history [{:keys [versions policies current]} & body]
  `(with-redefs [time-travel/build-historical-snapshot (fn [~'_ ~'_] snapshot)
                 autho.policy-versions/list-versions (fn [rc#] (get ~versions rc# []))
                 autho.policy-versions/get-version (fn [rc# v#] (get ~policies [rc# v#]))
                 autho.prp/getGlobalPolicy (fn [rc# & ~'_] (get ~current rc#))
                 autho.pip/callPip (fn [& ~'_] (throw (ex-info "live PIP must not be called" {})))]
     ~@body))

(deftest policy-at-picks-last-deployed-version-before-timestamp-test
  (let [staging (assoc facture-policy :environment "staging")]
    (with-history {:versions {"Facture" [{:version 4 :created_at (ts "2026-03-16T00:00:00Z")}
                                         {:version 3 :created_at (ts "2026-03-14T00:00:00Z")}
                                         {:version 2 :created_at (ts "2026-03-10T00:00:00Z")}]}
                   :policies {["Facture" 4] facture-policy
                              ["Facture" 3] staging
                              ["Facture" 2] (assoc facture-policy :environment "prod")}}
      (let [result (time-travel/policy-at "Facture" (java.time.Instant/parse t-query) "prod")]
        (is (= "version" (:source result)))
        (is (= 2 (:version result)))))
    (with-history {:versions {"Facture" [{:version 1 :created_at (ts "2026-04-01T00:00:00Z")}]}
                   :policies {["Facture" 1] facture-policy}}
      (is (= {:source "none" :policy nil}
             (select-keys (time-travel/policy-at "Facture" (java.time.Instant/parse t-query) "prod")
                          [:source :policy]))))
    (with-history {:current {"Facture" facture-policy}}
      (is (= "current" (:source (time-travel/policy-at "Facture" (java.time.Instant/parse t-query) "prod")))))))

(deftest is-authorized-at-time-uses-historical-attributes-only-test
  (with-history {:current {"Facture" facture-policy}}
    (let [allow (time-travel/is-authorized-at-time
                 {:subject {:id "alice" :role "clerk"} ;; caller-supplied value is overridden
                  :resource {:class "Facture" :id "INV-1"}
                  :action "approve"}
                 t-query {})]
      (is (= "allow" (:decision allow)))
      (is (true? (:allowed allow)))
      (is (= ["R-approve"] (:matchedRules allow)))
      (is (= "Person" (get-in allow [:subject :class])))
      (is (= "manager" (get-in allow [:subject :role])))
      (is (= "current" (get-in allow [:policy :source])))
      (is (some #{"POLICY_VERSION_UNAVAILABLE"} (:warnings allow))))
    (is (= "deny" (:decision (time-travel/is-authorized-at-time
                              {:subject {:id "bob"} :resource {:class "Facture" :id "INV-1"}
                               :operation "approve"}
                              t-query {}))))
    (is (= "deny" (:decision (time-travel/is-authorized-at-time
                              {:subject {:id "alice"} :resource {:class "Facture" :id "INV-2"}
                               :operation "approve"}
                              t-query {}))))))

(deftest is-authorized-at-time-reports-missing-objects-and-policy-test
  (with-history {:current {"Facture" facture-policy}}
    (let [result (time-travel/is-authorized-at-time
                  {:subject {:id "zed" :class "Person"} :resource {:class "Facture" :id "INV-404"}
                   :operation "approve"}
                  t-query {})]
      (is (= "deny" (:decision result)))
      (is (some #{"SUBJECT_NOT_IN_SNAPSHOT"} (:warnings result)))
      (is (some #{"RESOURCE_NOT_IN_SNAPSHOT"} (:warnings result)))))
  (with-history {:versions {"Facture" [{:version 1 :created_at (ts "2026-04-01T00:00:00Z")}]}
                 :policies {["Facture" 1] facture-policy}}
    (let [result (time-travel/is-authorized-at-time
                  {:subject {:id "alice"} :resource {:class "Facture" :id "INV-1"} :operation "approve"}
                  t-query {})]
      (is (= "deny" (:decision result)))
      (is (some #{"NO_POLICY_AT_TIME"} (:warnings result))))))

(deftest is-authorized-at-time-validates-input-test
  (with-history {:current {"Facture" facture-policy}}
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"timestamp"
                          (time-travel/is-authorized-at-time
                           {:subject {:id "alice"} :resource {:class "Facture" :id "INV-1"}}
                           "yesterday" {})))
    (is (= 400 (:status (ex-data (try (time-travel/is-authorized-at-time
                                       {:subject {:id "alice"}} t-query {})
                                      (catch clojure.lang.ExceptionInfo e e))))))))

(deftest ambiguous-subject-class-is-rejected-test
  (with-redefs [time-travel/build-historical-snapshot
                (fn [_ _] (assoc snapshot "Employee" {"alice" {:role "manager"}}))
                autho.policy-versions/list-versions (fn [_] [])
                autho.prp/getGlobalPolicy (fn [& _] (assoc-in facture-policy [:rules 0 :conditions 0 1 0] "Party"))]
    (let [e (try (time-travel/is-authorized-at-time
                  {:subject {:id "alice"} :resource {:class "Facture" :id "INV-1"} :operation "approve"}
                  t-query {})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= 400 (:status (ex-data e))))
      (is (= "AMBIGUOUS_SUBJECT_CLASS" (:error-code (ex-data e)))))))

(deftest who-was-authorized-at-evaluates-snapshot-subjects-test
  (with-history {:current {"Facture" facture-policy}}
    (let [result (time-travel/who-was-authorized-at "Facture" "INV-1" "approve" t-query {})]
      (is (= [{:class "Person" :id "alice" :matchedRules ["R-approve"]}] (:authorized-subjects result)))
      (is (= 2 (:evaluated result)))
      (is (false? (:truncated result))))
    (is (= [] (:authorized-subjects (time-travel/who-was-authorized-at "Facture" "INV-2" "approve" t-query {}))))
    (is (= 1 (:evaluated (time-travel/who-was-authorized-at "Facture" "INV-1" "approve" t-query {:limit 1}))))))

(deftest what-could-access-at-evaluates-snapshot-resources-test
  (with-history {:current {"Facture" facture-policy}}
    (let [result (time-travel/what-could-access-at "alice" "approve" t-query {})]
      (is (= [{:class "Facture" :id "INV-1" :matchedRules ["R-approve"]}] (:accessible-resources result)))
      (is (= 2 (:evaluated result))))
    (is (= [] (:accessible-resources (time-travel/what-could-access-at "bob" "approve" t-query {}))))))

(deftest relation-clauses-are-flagged-test
  (with-history {:current {"Facture" (update-in facture-policy [:rules 0 :conditions]
                                                conj ["relation" "$s" "viewer" "$r"])}}
    (with-redefs [autho.relation-provider/check-relation (fn [& _] {:status :allowed})]
      (let [result (time-travel/is-authorized-at-time
                    {:subject {:id "alice"} :resource {:class "Facture" :id "INV-1"} :operation "approve"}
                    t-query {})]
        (is (some #{"RELATIONS_EVALUATED_ON_CURRENT_STATE"} (:warnings result)))))))
