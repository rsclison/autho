(ns autho.jsonrule
  (:require [clojure.string :as str]
            [autho.jsonpath :as js]
            [autho.pip :as pip]
            [autho.prp :as prp]
            )
  (:use clojure.test)
  )

;; a context is like {:class :student :name "john" :age 21}
;; callPip return a map which is like a context. In fact alaways return the object map with the attribute included
;; so for a person pip which is called for the age attribute the result of callPip should be like {:class :person :id "fziffo2343" :name "John" :age 33 }
;; resolveAttr then gets the right attribute in this map
(defn resolveAttr [ctxt att]
  (if (map? ctxt)
    (if-let [val (get ctxt (keyword att))] val
            (get (pip/callPip (prp/findPip (:class ctxt) att) ctxt att) (keyword att)))
    (throw (Exception. "Not an object"))))



(defn walkResolveJPath [path context]
  (let [pathcol (rest (str/split path #"\."))] ;; omit the $
    (reduce #(resolveAttr %1 %2) context pathcol)))

;; ctxt is a map with the primary and the secondary context of evaluation
;; 
#_(defn evalOperand [op subjOrRess ctxt]
  (if-not (= (subs op 0 1) "$")
    op
    (case (subs op 0 2)
      "$." (js/at-path op (if (= :subject subjOrRess)
                            (:subject ctxt)
                            (:resource ctxt)))
      "$r" (js/at-path (str "$" (subs op 2)) (:resource ctxt))
      "$s" (js/at-path (str "$" (subs op 2)) (:subject ctxt)))))

(defn evalOperand [ops subjOrRess ctxt]
  (let [op (str ops)]
    (if-not (= (subs op 0 1) "$")
      op  ;; scalar value
      (case (subs op 0 2)
        "$." (walkResolveJPath op (if (= :subject subjOrRess)
                                    (:subject ctxt)
                                    (:resource ctxt)))
        "$r" (walkResolveJPath (str "$" (subs op 2)) (:resource ctxt))
        "$s" (walkResolveJPath (str "$" (subs op 2)) (:subject ctxt))))))


(defn evalOperand2 [op ctxt]
  (if-not (coll? op)
    (case (str op)
      "$s" (:subject ctxt)
      "$r" (:resource ctxt)
      op)

    (let [
          type (first op)
          obj (evalOperand2 (second op) ctxt)
          attribute (nth op 2)]
      (resolveAttr (assoc obj :class type) attribute)
      )))


(defn evalClause [[operator op1 op2] type ctxt subjOrRess]
  (let [opv1 (evalOperand op1 subjOrRess ctxt)
        opv2 (evalOperand op2 subjOrRess ctxt)
        func (resolve(symbol "autho.attfun" operator))
        ]
    (println "OPV1" opv1)
     (apply func [op1 op2])
    )
  )

(defn evalClause2 [[operator op1 op2] ctxt]
  (println "IN EVALCLAUSE2")
  (let [opv1 (evalOperand2 op1 ctxt)
        opv2 (evalOperand2 op2 ctxt)
        func (resolve (symbol "autho.attfun" (str operator)))
        ]
    (apply func [opv1 opv2])
    )
  )

(deftest evalClause-testscalar
        (is (= (evalClause [">" "1" "2"] {:class :toto :a 1 :b 2} :subject)
               true)))

;; a request is like : {:subject {:id "Mary", :role "Professeur"} :resource {:class "Note"} :operation "lire" :context {:date "2019-08-14T04:03:27.456"}}
;; catch Exception while evaluating
(defn evaluateRule [rule request]
  (let [subjectClauses (:subjectCond rule) resourceClauses (:resourceCond rule) ctxtwtype (assoc request :class :Person)]
    (println "EVAL RULES")
    (doall (map #(evalClause % :Person ctxtwtype :subject) (rest subjectClauses)))
    (map #(evalClause % :Person ctxtwtype :resource) (rest resourceClauses))
    )
)

(defn evaluateRule2 [rule request]
  (loop [conds (:conditions rule)]
    (if (empty? conds)
      true
      (if (evalClause2 (first conds) request)
        (recur (rest conds))
        false)))
  )


