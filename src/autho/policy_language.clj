(ns autho.policy-language
  "Extended policy language with complex operators (OR, NOT, temporal, set operations)"
  (:require [autho.jsonrule :as jr]
            [autho.attfun :as attfun])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.policy-language"))

;; ==============================================================================
;; Logical Operators: OR and NOT
;; ==============================================================================

(defn eval-or
  "Evaluates a logical OR expression between two clauses.
   Returns true if at least one clause evaluates to true.

   Each operand can be:
   - A vector clause: [\"=\" \"$.role\" \"admin\"]
   - A scalar (treated as truthy): \"admin\"

   Syntax: (or clause1 clause2)

   Examples:
   - (or [\"=\" \"$.role\" \"admin\"] [\"=\" \"$.role\" \"superadmin\"])"
  [op1 op2 ctxt subj-ress]
  (try
    (let [result1 (if (vector? op1)
                    (jr/evalClause op1 ctxt subj-ress)
                    true)  ; scalars are truthy
          result2 (if (vector? op2)
                    (jr/evalClause op2 ctxt subj-ress)
                    true)]  ; scalars are truthy
      (or result1 result2))
    (catch Exception e
      (.error logger "Error evaluating OR expression: {}" (.getMessage e))
      false)))

(defn eval-not
  "Evaluates a logical NOT expression on a clause.
   Returns the negation of the clause result.

   The operand can be:
   - A vector clause: [\"in\" \"$.status\" \"suspended,blocked\"]
   - A scalar (treated as truthy, so NOT makes it false)

   Syntax: (not clause)

   Examples:
   - (not [\"in\" \"$.status\" \"suspended,blocked\"])"
  [op ctxt subj-ress]
  (try
    (let [result (if (vector? op)
                   (jr/evalClause op ctxt subj-ress)
                   true)]  ; scalars are truthy
      (not result))
    (catch Exception e
      (.error logger "Error evaluating NOT expression: {}" (.getMessage e))
      false)))

(defn eval-and-extended
  "Evaluates a logical AND expression across multiple clauses.
   Returns true only if all clauses evaluate to true.

   Each operand can be:
   - A vector clause: [\"=\" \"$.role\" \"admin\"]
   - A scalar (treated as truthy)

   Syntax: (and clause1 clause2 ... clauseN)

   Examples:
   - (and [\"=\" \"$.role\" \"admin\"] [\">\" \"$.level\" \"5\"])"
  [operands ctxt subj-ress]
  (try
    (every? #(if (vector? %)
               (jr/evalClause % ctxt subj-ress)
               true)  ; scalars are truthy
            operands)
    (catch Exception e
      (.error logger "Error evaluating AND expression: {}" (.getMessage e))
      false)))

;; ==============================================================================
;; Extended evalClause that supports logical operators
;; ==============================================================================

(defn eval-clause-extended
  "Extended clause evaluator that supports OR and NOT operators.

   Supports:
   - Standard operators: =, diff, >, <, >=, <=, in, notin
   - Logical operators: or, not, and

   Syntax examples:
   - [\"or\" [\"=\" \"$.role\" \"admin\"] [\"=\" \"$.role\" \"superadmin\"]]
   - [\"not\" [\"in\" \"$.status\" \"suspended,blocked\"]]
   - [\"and\" [\"=\" \"$.role\" \"admin\"] [\">\" \"$.level\" \"5\"]]"
  [operator op1 op2 ctxt subj-ress]
  (try
    (cond
      ;; Logical OR operator
      (= operator "or")
      (eval-or op1 op2 ctxt subj-ress)

      ;; Logical NOT operator (op2 is ignored for NOT)
      (= operator "not")
      (eval-not op1 ctxt subj-ress)

      ;; Extended AND operator (for consistency)
      (= operator "and")
      (eval-and-extended [op1 op2] ctxt subj-ress)

      ;; Standard operators - delegate to original evalClause
      :else
      (jr/evalClause [operator op1 op2] ctxt subj-ress))
    (catch Exception e
      (.error logger "Error in extended clause evaluation: {}" (.getMessage e))
      false)))

;; ==============================================================================
;; Helper functions for nested expressions
;; ==============================================================================

(defn eval-nested-expression
  "Evaluates a nested expression that can be:
   - A vector clause: [\"=\" \"$.role\" \"admin\"]
   - A list expression: (or clause1 clause2)
   - A scalar value: \"admin\"

   Examples:
   - [\"=\" \"$.role\" \"admin\"]  -> evaluates the clause
   - \"admin\"  -> returns the value (truthy)
   - (or [\"=\" \"$.role\" \"admin\"] [\"=\" \"$.role\" \"superadmin\"])  -> evaluates OR"
  [expr ctxt subj-ress]
  (cond
    ;; Vector clause: [operator op1 op2]
    (vector? expr)
    (if (= 3 (count expr))
      (eval-clause-extended (get expr 0) (get expr 1) (get expr 2) ctxt subj-ress)
      (jr/evalClause expr ctxt subj-ress))

    ;; List expression: (or expr1 expr2) or (not expr) or (and expr1 expr2 ...)
    (list? expr)
    (let [operator (first expr)
          operands (rest expr)]
      (case operator
        "or" (eval-or (first operands) (second operands) ctxt subj-ress)
        "not" (eval-not (first operands) ctxt subj-ress)
        "and" (eval-and-extended operands ctxt subj-ress)
        (throw (Exception. (str "Unknown nested operator: " operator)))))

    ;; Scalar value - truthy
    :else
    true))
