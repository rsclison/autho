(ns autho.delegation
  (:require [hyauth.prp :as prp]
            [hyauth.rule :as rule]
            [hyauth.utils :as utl])
  )




;; for performance reason we have to bach compile delegations
;; evaluate clauses for each person
;; this should be done on a daily base
(defn batchCompile []
  (prp/saveCompDelegations
    (doall(map (fn [del]
                (println del)
                (filter (fn [pers]
                          (rule/evalCond (:delegate del) pers nil))
                        (prp/getPersons)
                        )
                )
              (prp/getDelegations)
              )))
  )

(defn findDelegation [person]
  (filter #(= (:id person) (:id (:delegate %)))
          (prp/getCompiledDelegations)
          )
  )