(ns autho.delegation
  (:require [autho.prp :as prp]
            [autho.jsonrule :as jsonrule])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.delegation"))

;; Helper function to convert old rule format to jsonrule format
;; Old format: [(= id "002")] -> New format: [["=" "$.id" "002"]]
(defn convert-clause-to-jsonrule [[op attr value]]
  (let [op-str (name op)
        attr-path (if (symbol? attr)
                    (str "$." (name attr))
                    (str attr))]
    [op-str attr-path (str value)]))

;; Evaluate conditions using jsonrule instead of rule
;; Returns true if all conditions match the person
(defn eval-conditions [conditions person]
  (try
    (let [ctxt {:subject person :resource {} :operation "" :context {}}]
      (every? #(jsonrule/evalClause % ctxt :subject)
              (map convert-clause-to-jsonrule conditions)))
    (catch Exception e
      (.error logger (str "Error evaluating conditions: " (.getMessage e)) e)
      false)))

;; for performance reason we have to batch compile delegations
;; evaluate clauses for each person
;; this should be done on a daily basis
(defn batchCompile []
  (.info logger "Starting batch compilation of delegations")
  (let [delegations (prp/getDelegations)
        persons (prp/getPersons)
        compiled (doall (map (fn [del]
                               (.debug logger "Compiling delegation: {}" del)
                               (filter (fn [pers]
                                         (eval-conditions (:delegate del) pers))
                                       persons))
                             delegations))]
    (prp/saveCompDelegations compiled)
    (.info logger "Completed batch compilation of {} delegations" (count delegations))))

(defn findDelegation [person]
  (filter #(= (:id person) (:id (:delegate %)))
          (prp/getCompiledDelegations)
          )
  )