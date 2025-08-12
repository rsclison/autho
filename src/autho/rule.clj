(ns autho.rule
  (:require [clojure.reflect :as cr]
            [autho.prp :as prp]
            [clojure.string :as str]
            [clojure.test :refer :all]
            )
  (:use autho.attfun clojure.test autho.prp)
  )


(def ^:dynamic ?subject nil)
(def ^:dynamic ?resource nil)
(def ^:dynamic ?operation nil)
(def ^:dynamic ?context nil)


;; TODO rule precompilation. We should precompile resource conditions to find all attributes needed and do only on call to PIP to retrieve alla attributes at once

(defrecord evalClauseResult [result bindings evaluated])


(defn evalform [form request]
  ;; return a map {:value "result of evaluation" :binding ((var val)...) :explain {:form "the form evaluated" :error "exception message}}
  ;; a form could be evaluated to true, false or true with bindings
  ;; bindings are used to verify they are consistent between across clauses of a rule, and also across rules
  (if-not form
    form
    (if-not (list? form)
      (if (= (cr/typename (type form)) "clojure.lang.Symbol") ;; this is ?subject or ?resource or etc.
        (do {:value (var-get (find-var (symbol "autho.rule" (name form)))) :explain {}})
        {:value form :explain {}})
      ;; it is a list so let evaluate s-expr
      (let [func (symbol "autho.attfun" (str (first form)))
            funcres (resolve func)
            ]

        (if funcres
          (try
            (let [res (apply funcres (map (fn [arg] (:value (evalform arg request))) (rest form)))]
              {:value res :explain {}}
              )
            (catch Exception e {:value nil :explain {:form form :error (.getMessage e)}})
            )
          )))))




(defn getVarValue [var bindings]
  (let [res (if (map? bindings)
              (get bindings var)
              (second (first (filter #(= var (first %)) bindings))))]
    res))


(defn replaceVarValue [clauseOrVar bindings]  ;; bindings is like ((?var1 val1)(?var2 val2) ...)
  (if (symbol? clauseOrVar)
    (let [valvar (getVarValue clauseOrVar bindings)]
      (if valvar valvar
                 clauseOrVar
                 ))

    (let [val (getVarValue (nth clauseOrVar 2) bindings)]
      (list (first clauseOrVar)
            (second clauseOrVar)
            (if val val
                    (nth clauseOrVar 2)
                    ))
      )))

(defn isVariableTerm [clause]
  (str/includes? (str clause) "?")
  )


;; return a list composed of the status of the eval (:succeeded , :failed or :unevaluated) and the bindings eventually augmented
;; //TODO pourquoi renvoyer une liste dont le premier argument est un array avec seulement un symbol. Simplifier !!!
(defn evalClause2 [clause object bindings]
  (if (and (= (first clause) '=) (isVariableTerm (nth clause 2)))
    ;; maybe its a variable binding clause. If the variable is already bound test the equality else add a binding
    (let [val (getVarValue (nth clause 2) bindings) valatt (att (second clause) object)]
      (if val
        ;;  (->evalClauseResult (= val valatt) bindings true)   ;; the variable is already bound do the test of equality
        (if (= val valatt) (list [:succeeded] bindings)
                           (list [:failed (str val " different de " valatt)] bindings)
                           )
        (if valatt (list [:succeeded] (conj bindings (list (nth clause 2) valatt))) ;;(->evalClauseResult true (conj bindings (list (nth clause 2) valatt)) true) ;; else bound the variable and return true
                   (list [:failed (str "l'évaluation de l'attribut " (second clause) " a échoué")] bindings)                  ;;(->evalClauseResult false bindings true)  the resolution of the attribute has failed
                   )))

    (if (and (isVariableTerm (nth clause 2)) (not (getVarValue (nth clause 2) bindings)))
      ;; we have a variable and the operator is not an assignment
      (list [:unevaluated] bindings)                          ;;(->evalClauseResult true bindings false)
      ;; else it's a clause to truly evaluate
      (let [resev (att (second clause) object)
            op (symbol "autho.attfun" (str (first clause)))
            opr (resolve op)
            val (if (isVariableTerm (nth clause 2)) (getVarValue (nth clause 2) bindings) (nth clause 2))
            evcl (try (apply opr [resev val]) (catch Exception e nil))] ;; TODO store the exception
        evcl

        (if evcl (list [:succeeded] bindings) (list [:failed] bindings)) ;;(->evalClauseResult evcl bindings true)
        )
      )
    )
  )


(deftest clauseTest
  (is (= (evalClause2 '(< montant 1000) {:class "Facture", :montant 500, :service "compta"} '() )
         '(true ())
         ))

  )

(defn evalCond [clauses object bindings]                    ;; return a triplet (bool_result bindings not_evaluated_clauses)
  (if (empty? clauses)
    (list true bindings ())  ;; there is no conditions

    (let [not-evaluated ()
          res2 (reduce (fn [[bdings notevs] cl]
                         (let [res (evalClause2 cl object bdings)] ;; remember the result is a pair (symbol bindings)
                           (case (first(first res))
                             :succeeded (list (second res) notevs)
                             :failed (reduced (list false (replaceVarValue cl bdings)))
                             :unevaluated (list (second res) (conj notevs cl))
                             )
                           ))                               ;; return a false for the result and the clause which failed
                       (list bindings '())
                       clauses
                       )]
      (if (not (first res2))
        (conj res2 false)                                   ;; a clause has failed
        (conj res2 true)                                    ;; all clauses succeeded
        )
      )))

(defn evalResourceCond [form request bindings]
  (if (vector? form)                                        ;; form has to be a vector
    (let [object (:resource request) clauses (rest (rest form)) bdings (conj bindings (list (second form) (:resource request)))]
      (evalCond (rest (rest form)) object bdings)
      )
    )
  )

(defn evalSubjectCond [form request bindings]
  (if (vector? form)    ;; form has to be a vector
    (let [object (:subject request) clauses (rest (rest form)) bdings (conj bindings (list (second form) (:subject request)))]
       (evalCond (rest(rest form)) object bdings)
      )
    )
  )



;; a request is like : {:subject {:id "Mary", :role "Professeur"} :resource {:class "Note"} :operation "lire" :context {:date "2019-08-14T04:03:27.456"}}
(defn evaluateRule [rule request]
  ;; implementation with explanation of exception
  (binding [?subject (assoc (:subject request) :type :subject)
            ?resource (assoc (:resource request) :type :resource)
            ?operation (if (map? (:operation request)) (assoc (:operation request) :type :operation)
                                                       (assoc (assoc {} :name (:operation request)) :type :operation))
            ?context (assoc (:context request) :type :context)]

    (set! ?subject (assoc (:subject request) :type :subject))
    (if (and (= (:resourceClass rule) (:class (:resource request))) (or (nil? (:operation request)) (= (:operation rule) (:operation request)))) ;; TODO remplacer par contains? pour avoir une liste d'opé pour 1 rule
      (let [resscond (:resourceCond rule) subjcond (:subjectCond rule)
            resress (evalResourceCond resscond request nil)
            ressubj (if (= true (first resress)) (evalSubjectCond subjcond request (second resress)))
            ]
        (cond
          (and (= true (first resress)) (= true (first ressubj)) (empty? (nth resress 2))) {:value true}
          (and (= true (first resress)) (= true (first ressubj)) (not (empty? (nth resress 2))))
          (let [resresspass2                                ;; just do a second pass if necessary on resscond
                (evalCond (nth resress 2) (:resource request) (second ressubj))]
            (if (and (= true (first resresspass2)) (empty? (nth resresspass2 2)))
              {:value true}
              {:value false}
              )
            )
          true {:value false}
          ))
      {:value false}
      )))

(defn replace-symbol [sexpr sym rep]
  (if (= (first sexpr) sym)
    (conj (replace-symbol (rest sexpr) sym rep) rep)
    (conj (replace-symbol (rest sexpr) sym rep) (first sexpr) )
    )
  )


(defn isVariable [var]
  (= (first (str var)) \?)
  )

(defn hasClauseVariable [clause]
  (some #(if (and (symbol %) (isVariable %)) %) clause)
  )

(defn isBindingClause [clause]
  (and (= '= (first clause))
       (isVariable (nth clause 2))
       )
  )

(deftest clauseTests
  (is (= (hasClauseVariable '(lala ?zz "toto")) '?zz)
      )
  )



(defn instantiateRuleForm [form bindings]
   (into []
        (concat [(first form) (replaceVarValue (second form) bindings)]
                (map #(replaceVarValue % bindings) (rest (rest form)))
        ))
  )

(deftest instantiateRuleTest
  (is (= (instantiateRuleForm '[Facture ?f (< montant 1000) (= service ?serv)]
                              '((?f "mafacture") (?serv "service1")))
      '[Facture "mafacture" (< montant 1000) (= service "service1")]
      )
  ))



(deftest test-evalBindingClause
  (is (= (evalClause2 '(= atta ?avariable) {:atta 1 :attb 2} '((var1 val1)))
         (->evalClauseResult true '((?avariable 1) (var1 val1)) ())
      ))
  (is (= (evalClause2 '(= atta ?avariable) {:atta 1 :attb 2} '((?avariable 1)))
         (->evalClauseResult true '((?avariable 1)) ())
         ))
  )




;; try to evaluate s-expr that are constant and return an s-expr
;; used when we try to resolve rights with incomplete request
;; so not all variables are bound (ex: ?subject  if we try to find all users that have a right on a resource)


(defn evalResourcePart2 [rule request]
  (let [?subject (:subject request)
        ?resource (:resource request)
        ?operation (:operation request)
        ?context (:context request)
        evalRessclauses (if-not ?resource
                          nil
                          (evalCond (rest(rest (:resourceCond rule))) ?resource (list (list (second (:resourceCond rule)) ?resource)))
                          )
                        ]
    evalRessclauses
  ))

(defn evalRuleWithResource [rule request]
  (let [res (evalResourcePart2 rule request)
        uneval (nth res 2)
        ;; ok lets instantiate the subject condition with bindings coming from the evaluation of the resource condition
        resi (if (first res)
               (instantiateRuleForm (:subjectCond rule) (nth res 1)))
        ;; we have to treat the case where there is unevaluated clauses in resource part
        ;; if the clause were unevaluated because it includes a variable which can be resolved in subject part
        ;; replace the attribute of the resource with the value and the clause to the result
        ;; while replacing the var with the value for the subject
        affBindings (keep (fn [clause]
                           (if (and (= (first clause) '=) (hasClauseVariable clause))
                                        (list (nth clause 2) (nth clause 1))
                                        ))
                         (rest (rest resi))
                         )
        resscl (map (fn [unevclause]
                      (list (autho.attfun/inverseOp (first unevclause))
                            (getVarValue (nth unevclause 2) affBindings)
                            (att (nth unevclause 1) (:resource request)))
                      )
                    uneval
                    )
        ]

    ;; now delete aff clause in resource and add above clauses

    (assoc rule :subjectCond (into []
          (concat (take 2 resi)
                  (keep (fn [cl] (if (not(isBindingClause cl)) cl)) (rest(rest resi)))
                  resscl)))

    ))


(defn evalSubjectPart2 [rule request]
  (let [?subject (:subject request)
        ?resource (:resource request)
        ?operation (:operation request)
        ?context (:context request)
        evalSubjclauses (if-not ?subject
                          nil
                          (evalCond (rest(rest (:subjectCond rule))) ?subject (list (list (second (:subjectCond rule)) ?subject)))
                          )
        ]
    evalSubjclauses
    ))



(defn evalRuleWithSubject [rule request]
  (let [res (evalSubjectPart2 rule request)
        uneval (nth res 2)
        ;; ok lets instantiate the subject condition with bindings coming from the evaluation of the resource condition
        resi (if (first res)
               (instantiateRuleForm (:resourceCond rule) (nth res 1)))
        ;; we have to treat the case where there is unevaluated clauses in resource part
        ;; if the clause were unevaluated because it includes a variable which can be resolved in subject part
        ;; replace the attribute of the resource with the value and the clause to the result
        ;; while replacing the var with the value for the subject
        affBindings (keep (fn [clause]
                            (if (and (= (first clause) '=) (hasClauseVariable clause))
                              (list (nth clause 2) (nth clause 1))
                              ))
                          (rest (rest resi))
                          )
        resscl (map (fn [unevclause]
                      (list (autho.attfun/inverseOp (first unevclause))
                            (getVarValue (nth unevclause 2) affBindings)
                            (att (nth unevclause 1) (:subject request)))
                      )
                    uneval
                    )
        ]

    ;; now delete aff clause in resource and add above clauses
    (assoc rule :resourceCond (into []
          (concat (take 2 resi)
                  (keep (fn [cl] (if (not (isBindingClause cl)) cl)) (rest (rest resi)))
                  resscl)))

    ))

(deftest evalRuleTest
  (is (= (evalRuleWithResource (prp/rule2 {:name          "R1"
                                                  :resourceClass "Note"
                                                  :operation     "lire"
                                                  :resource '[Facture ?f (< montant 1000)(= service ?serv)]
                                                  :subject '[Person ?subject (= role "chef_de_service")(= service ?serv)]
                                                  :effect        "allow"
                                                  :startDate     "inf"
                                                  :endDate       "inf"})
                               {:resource {:class "Facture" :montant 500 :service "compta"} :operation "lire" :context {:date "2019-08-14T04:03:27.456"} }
                               )
         '[Person ?subject (= role "chef_de_service") (= service "compta")]))
  ;;       '((?serv "compta") (?f {:class "Facture", :montant 500, :service "compta"}))))

  (is (= (evalRuleWithResource (prp/rule2 {:name          "R1"
                                           :resourceClass "Note"
                                           :operation     "lire"
                                           :resource '[Facture ?f (< montant 1000)(= service ?serv)]
                                           :subject '[Person ?subject (= role chef_de_service)(= service ?serv)]
                                           :effect        "allow"
                                           :startDate     "inf"
                                           :endDate       "inf"})
                               {:resource {:class "Facture" :montant 2000 :service "compta"} :operation "lire" :context {:date "2019-08-14T04:03:27.456"} }
                               )
         nil))
      )