(ns autho.delegation
  (:require [autho.prp :as prp]
            [autho.rule :as rule]
            [autho.utils :as utl])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.delegation"))

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
                                         (rule/evalCond (:delegate del) pers nil))
                                       persons))
                             delegations))]
    (prp/saveCompDelegations compiled)
    (.info logger "Completed batch compilation of {} delegations" (count delegations))))

(defn findDelegation [person]
  (filter #(= (:id person) (:id (:delegate %)))
          (prp/getCompiledDelegations)
          )
  )