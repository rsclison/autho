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
