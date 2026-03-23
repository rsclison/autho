(ns autho.jsonrule
  (:require [autho.jsonpath :as js]
            [autho.attfun :as attfun])
  (:use clojure.test))

;; a context is like {:class :student :name "john" :age 21}
;; callPip return a map which is like a context. In fact alaways return the object map with the attribute included
;; so for a person pip which is called for the age attribute the result of callPip should be like {:class :person :id "fziffo2343" :name "John" :age 33 }
;; resolveAttr then gets the right attribute in this map
#_(defn resolveAttr [ctxt att]
  (if (map? ctxt)
    (if-let [val (get ctxt (keyword att))] val
            (get (pip/callPip (prp/findPip (:class ctxt) att) ctxt att) (keyword att)))
    (throw (Exception. "Not an object"))))

;; --- Evaluateur pour le format :conditions de jrules.edn ---
;; Format d'une condition : [operateur operande1 operande2]
;; Format d'un operande   : "valeur-litterale"  OU  [Classe $s-ou-$r attribut]

(defn- coerce-str
  "Convertit les nombres en string pour la compatibilité avec les fonctions attfun."
  [v]
  (if (number? v) (str v) v))

(defn- resolve-attr
  "Résout l'attribut `att` sur l'objet `obj` en utilisant `class-name` pour le PIP.
   Retourne la valeur coercée en string si c'est un nombre.
   Si le PIP retourne une map (ex: REST PIP retournant le corps JSON complet),
   extrait l'attribut `att` depuis cette map."
  [obj class-name att]
  (let [obj-with-class (assoc obj :class class-name)
        val (or (get obj (keyword att))
                (let [pip-result (attfun/findAndCallPip att obj-with-class)]
                  (if (map? pip-result)
                    (get pip-result (keyword att))
                    pip-result)))]
    (coerce-str val)))

(defn- eval-operand2
  "Evalue un opérande du nouveau format :conditions.
   Scalaire → valeur littérale (string).
   Vecteur [Classe $s|$r attribut] → résolution PIP/objet."
  [op ctxt]
  (if-not (coll? op)
    (coerce-str op)
    (let [class-name (name (first op))
          var-sym    (str (second op))
          attribute  (name (nth op 2))
          obj        (case var-sym
                       "$s" (:subject ctxt)
                       "$r" (:resource ctxt)
                       nil)]
      (when obj
        (resolve-attr obj class-name attribute)))))

(defn- eval-clause2
  "Evalue une clause [operateur op1 op2] en utilisant les fonctions de autho.attfun.
   Retourne false si un des opérandes n'a pas pu être résolu (nil)."
  [[operator op1 op2] ctxt]
  (let [opv1 (eval-operand2 op1 ctxt)
        opv2 (eval-operand2 op2 ctxt)
        func (get (ns-publics 'autho.attfun) (symbol (name operator)))]
    (if (and func (some? opv1) (some? opv2))
      (boolean (apply func [opv1 opv2]))
      false)))

(defn- evaluate-conditions
  "Evalue toutes les conditions d'une règle jrules (format :conditions).
   Retourne true ssi toutes les conditions sont satisfaites."
  [rule request]
  (every? #(eval-clause2 % request) (:conditions rule)))



#_(defn walkResolveJPath [path context]
  (let [pathcol (rest (str/split path #"\."))] ;; omit the $
    (reduce #(resolveAttr %1 %2) context pathcol)))

;; ctxt is a map with the primary and the secondary context of evaluation
;; 
(defn evalOperand [op subjOrRess ctxt]
  (if-not (= (subs op 0 1) "$")
    op
    (case (subs op 0 2)
      "$." (let [value (js/at-path op (if (= :subject subjOrRess)
                                         (:subject ctxt)
                                         (:resource ctxt)))]
             (if (number? value) (str value) value))
      "$r" (let [value (js/at-path (str "$" (subs op 2)) (:resource ctxt))]
             (if (number? value) (str value) value))
      "$s" (let [value (js/at-path (str "$" (subs op 2)) (:subject ctxt))]
             (if (number? value) (str value) value)))))

#_(defn evalOperand [ops subjOrRess ctxt]
  (let [op (str ops)]
    (if-not (= (subs op 0 1) "$")
      op  ;; scalar value
      (case (subs op 0 2)
        "$." (walkResolveJPath op (if (= :subject subjOrRess)
                                    (:subject ctxt)
                                    (:resource ctxt)))
        "$r" (walkResolveJPath (str "$" (subs op 2)) (:resource ctxt))
        "$s" (walkResolveJPath (str "$" (subs op 2)) (:subject ctxt))))))


#_(defn evalOperand2 [op ctxt]
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


(defn evalClause [[operator op1 op2] ctxt subjOrRess]
  (let [opv1 (evalOperand op1 subjOrRess ctxt)
        opv2 (evalOperand op2 subjOrRess ctxt)
        func (get (ns-publics 'autho.attfun) (symbol operator))]
    (if func
      (apply func [opv1 opv2])
      (throw (Exception. (str "Unknown operator: " operator))))))

#_(defn evalClause2 [[operator op1 op2] ctxt]
  (let [opv1 (evalOperand2 op1 ctxt)
        opv2 (evalOperand2 op2 ctxt)
        func (resolve (symbol "autho.attfun" (str operator)))
        ]
    (apply func [opv1 opv2])
    )
  )

(deftest evalClause-testscalar
        (is (= (evalClause [">" "2" "1"] {:class :toto :a 1 :b 2} :subject)
               true)))

;; a request is like : {:subject {:id "Mary", :role "Professeur"} :resource {:class "Note"} :operation "lire" :context {:date "2019-08-14T04:03:27.456"}}
;; catch Exception while evaluating
(defn evaluateRule [rule request]
  (if (:conditions rule)
    {:value (evaluate-conditions rule request)}
    (let [subjectClauses (rest (:subjectCond rule))
          resourceClauses (rest (:resourceCond rule))
          ctxtwtype (assoc request :class :Person)
          all-true? (and
                      (every? #(evalClause % ctxtwtype :subject) subjectClauses)
                      (every? #(evalClause % ctxtwtype :resource) resourceClauses))]
      {:value all-true?})))

(defn evalRuleWithResource [rule request]
  (let [resourceClauses (rest (:resourceCond rule))
        ctxtwtype (assoc request :class :Person)]
    (every? #(evalClause % ctxtwtype :resource) resourceClauses)))

(defn evalRuleWithSubject [rule request]
  (let [subjectClauses (rest (:subjectCond rule))
        ctxtwtype (assoc request :class :Person)]
    (every? #(evalClause % ctxtwtype :subject) subjectClauses)))

#_(defn evaluateRule2 [rule request]
  (loop [conds (:conditions rule)]
    (if (empty? conds)
      true
      (if (evalClause2 (first conds) request)
        (recur (rest conds))
        false)))
  )


