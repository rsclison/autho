(ns autho.policy-safety
  "Static policy validation that complements JSON Schema checks.
   Catches rule conflicts and malformed clause structures before persistence."
  (:require [clojure.string :as str]
            [clojure.set :as set]))

(def ^:private supported-operators
  #{"=" "diff" "<" ">" "<=" ">=" "in" "notin" "date>"})

(def ^:private supported-strategies
  #{"almost_one_allow_no_deny"})

(defn- mapcat-indexed
  [f coll]
  (mapcat (fn [[idx item]] (f idx item)) (map-indexed vector coll)))

(defn- issue
  [code message & {:as data}]
  (merge {:code code :message message :severity :error} data))

(defn- warning
  [code message & {:as data}]
  (merge {:code code :message message :severity :warning} data))

(defn- operator-name
  [operator]
  (cond
    (keyword? operator) (name operator)
    (symbol? operator) (name operator)
    (string? operator) operator
    :else nil))

(defn- strategy-name
  [strategy]
  (cond
    (keyword? strategy) (name strategy)
    (string? strategy) strategy
    :else nil))

(defn- non-blank-ident?
  [value]
  (and (or (string? value) (keyword? value) (symbol? value))
       (not (str/blank? (name value)))))

(defn- ident-name
  [value]
  (cond
    (keyword? value) (name value)
    (symbol? value) (name value)
    (string? value) value
    :else nil))

(defn- variable-reference?
  [operand]
  (and (sequential? operand)
       (= 3 (count operand))
       (let [[class-name scope attribute] operand]
         (and (non-blank-ident? class-name)
              (contains? #{"$s" "$r"} (str scope))
              (non-blank-ident? attribute)))))

(defn- path-reference?
  [operand]
  (and (string? operand)
       (str/starts-with? operand "$")
       (> (count operand) 1)))

(defn- scalar-operand?
  [operand]
  (or (string? operand)
      (number? operand)
      (boolean? operand)
      (nil? operand)))

(defn- valid-operand?
  [operand]
  (or (scalar-operand? operand)
      (variable-reference? operand)
      (path-reference? operand)))

(defn- parse-field-reference
  [operand default-scope]
  (cond
    (variable-reference? operand)
    (let [[class-name scope attribute] operand]
      {:scope (if (= "$r" (str scope)) :resource :subject)
       :class (name class-name)
       :attribute (name attribute)})

    (path-reference? operand)
    (cond
      (str/starts-with? operand "$.")
      {:scope default-scope :attribute (subs operand 2)}

      (str/starts-with? operand "$s.")
      {:scope :subject :attribute (subs operand 3)}

      (str/starts-with? operand "$r.")
      {:scope :resource :attribute (subs operand 3)}

      :else nil)

    :else nil))

(defn- normalize-literal
  [operand]
  (when (scalar-operand? operand)
    operand))

(defn- contradiction-facts
  [clauses default-scope]
  (reduce
   (fn [acc clause]
     (if (and (sequential? clause) (= 3 (count clause)))
       (let [[operator op1 op2] clause
             op-name (operator-name operator)
             ref1 (parse-field-reference op1 default-scope)
             ref2 (parse-field-reference op2 default-scope)
             lit1 (normalize-literal op1)
             lit2 (normalize-literal op2)
             field-ref (or (when (and ref1 (some? lit2)) ref1)
                           (when (and ref2 (some? lit1)) ref2))
             literal (or lit2 lit1)
             target-key (select-keys field-ref [:scope :class :attribute])]
         (cond
           (or (nil? field-ref) (nil? literal) (not (contains? #{"=" "diff"} op-name))) acc
           (= op-name "=") (update-in acc [target-key :equals] (fnil conj #{}) literal)
           (= op-name "diff") (update-in acc [target-key :differs] (fnil conj #{}) literal)
           :else acc))
       acc))
   {}
   clauses))

(defn- contradiction-issues
  [rule-name field clauses default-scope]
  (reduce-kv
   (fn [issues ref-key {:keys [equals differs]}]
     (cond-> issues
       (> (count equals) 1)
       (conj (issue "CONTRADICTORY_EQUALS"
                    (str "Rule '" rule-name "' contains contradictory equality clauses for " (:attribute ref-key) ".")
                    :rule rule-name
                    :field field
                    :attribute (:attribute ref-key)))

       (seq (set/intersection (or equals #{}) (or differs #{})))
       (conj (issue "CONTRADICTORY_EQUALITY"
                    (str "Rule '" rule-name "' both requires and forbids the same value for " (:attribute ref-key) ".")
                    :rule rule-name
                    :field field
                    :attribute (:attribute ref-key)))))
   []
   (contradiction-facts clauses default-scope)))

(defn- clause-issues
  [rule-name field clause-index clause]
  (cond
    (not (sequential? clause))
    [(issue "INVALID_CLAUSE"
            (str "Rule '" rule-name "' has a non-sequential clause in " (name field) ".")
            :rule rule-name
            :field field
            :clause-index clause-index)]

    (not= 3 (count clause))
    [(issue "INVALID_CLAUSE_ARITY"
            (str "Rule '" rule-name "' has a clause in " (name field) " that must contain exactly 3 items.")
            :rule rule-name
            :field field
            :clause-index clause-index)]

    :else
    (let [[operator op1 op2] clause
          op-name (operator-name operator)]
      (cond-> []
        (not (contains? supported-operators op-name))
        (conj (issue "UNSUPPORTED_OPERATOR"
                     (str "Rule '" rule-name "' uses unsupported operator '" operator "'.")
                     :rule rule-name
                     :field field
                     :clause-index clause-index
                     :operator operator))

        (not (valid-operand? op1))
        (conj (issue "INVALID_OPERAND"
                     (str "Rule '" rule-name "' has an invalid first operand in " (name field) ".")
                     :rule rule-name
                     :field field
                     :clause-index clause-index))

        (not (valid-operand? op2))
        (conj (issue "INVALID_OPERAND"
                     (str "Rule '" rule-name "' has an invalid second operand in " (name field) ".")
                     :rule rule-name
                     :field field
                     :clause-index clause-index))))))

(defn- validate-conditions-field
  [rule-name conditions]
  (cond
    (nil? conditions) []
    (not (sequential? conditions))
    [(issue "INVALID_CONDITIONS"
            (str "Rule '" rule-name "' has a :conditions field that must be sequential.")
            :rule rule-name
            :field :conditions)]
    :else
    (into []
          (concat
           (mapcat-indexed (fn [idx clause]
                             (clause-issues rule-name :conditions idx clause))
                           conditions)
           (contradiction-issues rule-name :conditions conditions :subject)))))

(defn- legacy-clauses
  [cond-vector]
  (if (and (sequential? cond-vector) (seq cond-vector))
    (rest cond-vector)
    []))

(defn- rule-clauses
  [rule]
  (concat (or (:conditions rule) [])
          (legacy-clauses (:subjectCond rule))
          (legacy-clauses (:resourceCond rule))))

(defn- validate-legacy-field
  [rule-name field cond-vector default-scope]
  (cond
    (nil? cond-vector) []
    (not (sequential? cond-vector))
    [(issue "INVALID_LEGACY_CONDITION"
            (str "Rule '" rule-name "' has a " (name field) " field that must be sequential.")
            :rule rule-name
            :field field)]
    :else
    (let [clauses (legacy-clauses cond-vector)]
      (into []
            (concat
             (mapcat-indexed (fn [idx clause]
                               (clause-issues rule-name field idx clause))
                             clauses)
             (contradiction-issues rule-name field clauses default-scope))))))

(defn- duplicate-name-issues
  [rules]
  (->> rules
       (map :name)
       frequencies
       (keep (fn [[rule-name count]]
               (when (> count 1)
                 (issue "DUPLICATE_RULE_NAME"
                        (str "Rule name '" rule-name "' is duplicated " count " times.")
                        :rule rule-name))))
       vec))

(defn- resource-class-issues
  [submitted-resource-class rules]
  (->> rules
       (keep (fn [rule]
               (when-let [rule-resource-class (:resourceClass rule)]
                 (when (not= submitted-resource-class rule-resource-class)
                   (issue "RESOURCE_CLASS_MISMATCH"
                          (str "Rule '" (:name rule) "' targets resourceClass '" rule-resource-class
                               "' but is being submitted under '" submitted-resource-class "'.")
                          :rule (:name rule)
                          :field :resourceClass)))))
       vec))

(defn- strategy-issues
  [policy]
  (let [strategy (strategy-name (:strategy policy))]
    (cond
      (str/blank? strategy)
      [(issue "MISSING_STRATEGY"
              "Policy must declare an explicit conflict resolution strategy."
              :field :strategy)]

      (not (contains? supported-strategies strategy))
      [(issue "UNSUPPORTED_STRATEGY"
              (str "Policy uses unsupported conflict resolution strategy '" strategy "'.")
              :field :strategy
              :strategy strategy
              :supported-strategies (vec (sort supported-strategies)))]

      :else [])))

(defn- schema-section
  [schema scope]
  (case scope
    :subject (or (:subjects schema) (:subject schema) {})
    :resource (or (:resources schema) (:resource schema) {})
    {}))

(defn- schema-classes
  [schema scope]
  (set (keep ident-name (keys (schema-section schema scope)))))

(defn- schema-operations
  [schema]
  (set (keep ident-name (or (:operations schema) (:operation schema) []))))

(defn- schema-attributes
  [schema scope class-name]
  (let [section (schema-section schema scope)
        requested (ident-name class-name)
        attrs (some (fn [[declared-class declared-attrs]]
                      (when (= requested (ident-name declared-class))
                        declared-attrs))
                    section)]
    (set (keep ident-name attrs))))

(defn- default-schema-class
  [schema scope]
  (let [classes (schema-classes schema scope)]
    (when (= 1 (count classes))
      (first classes))))

(defn- operand-schema-issue
  [schema rule-name field clause-index operand default-scope]
  (when-let [ref (parse-field-reference operand default-scope)]
    (let [scope (:scope ref)
          class-name (or (:class ref) (default-schema-class schema scope))
          attribute (:attribute ref)
          declared-classes (schema-classes schema scope)
          declared-attrs (when class-name (schema-attributes schema scope class-name))
          scope-label (name scope)]
      (cond
        (and (:class ref)
             (seq declared-classes)
             (not (contains? declared-classes class-name)))
        (issue (str "UNKNOWN_" (str/upper-case scope-label) "_CLASS")
               (str "Rule '" rule-name "' references unknown " scope-label " class '" class-name "'.")
               :rule rule-name
               :field field
               :clause-index clause-index
               :class class-name)

        (and class-name
             (seq declared-attrs)
             (not (contains? declared-attrs attribute)))
        (issue (str "UNKNOWN_" (str/upper-case scope-label) "_ATTRIBUTE")
               (str "Rule '" rule-name "' references unknown " scope-label " attribute '" attribute
                    "' for class '" class-name "'.")
               :rule rule-name
               :field field
               :clause-index clause-index
               :class class-name
               :attribute attribute)))))

(defn- schema-clause-issues
  [schema rule-name field clause-index clause default-scope]
  (if (and (sequential? clause) (= 3 (count clause)))
    (let [[_ op1 op2] clause]
      (vec (keep #(operand-schema-issue schema rule-name field clause-index % default-scope)
                 [op1 op2])))
    []))

(defn- schema-rule-issues
  [schema rule]
  (let [rule-name (or (:name rule) "<unnamed>")
        operations (schema-operations schema)
        operation (ident-name (:operation rule))
        operation-issues (when (and operation
                                    (seq operations)
                                    (not (contains? operations operation)))
                           [(issue "UNKNOWN_OPERATION"
                                   (str "Rule '" rule-name "' uses unknown operation '" operation "'.")
                                   :rule rule-name
                                   :field :operation
                                   :operation operation)])]
    (vec (concat
          operation-issues
          (mapcat-indexed (fn [idx clause]
                            (schema-clause-issues schema rule-name :conditions idx clause :subject))
                          (or (:conditions rule) []))
          (mapcat-indexed (fn [idx clause]
                            (schema-clause-issues schema rule-name :subjectCond idx clause :subject))
                          (legacy-clauses (:subjectCond rule)))
          (mapcat-indexed (fn [idx clause]
                            (schema-clause-issues schema rule-name :resourceCond idx clause :resource))
                          (legacy-clauses (:resourceCond rule)))))))

(defn- schema-issues
  [submitted-resource-class policy rules]
  (if-let [schema (:schema policy)]
    (let [resource-classes (schema-classes schema :resource)]
      (vec (concat
            (when (and (seq resource-classes)
                       (not (contains? resource-classes submitted-resource-class)))
              [(issue "UNKNOWN_RESOURCE_CLASS"
                      (str "Submitted resource class '" submitted-resource-class "' is not declared in policy schema.")
                      :field :resourceClass
                      :class submitted-resource-class)])
            (mapcat #(schema-rule-issues schema %) rules))))
    []))

(defn- normalized-condition-signature
  [rule]
  {:operation (:operation rule)
   :conditions (:conditions rule)
   :subjectCond (:subjectCond rule)
   :resourceCond (:resourceCond rule)})

(defn- warning-issues
  [rules]
  (let [missing-operation
        (keep (fn [rule]
                (when (nil? (:operation rule))
                  (warning "MISSING_OPERATION"
                           (str "Rule '" (:name rule) "' has no operation and will apply to every operation.")
                           :rule (:name rule)
                           :field :operation)))
              rules)
        unconditional
        (keep (fn [rule]
                (when (and (empty? (or (:conditions rule) []))
                           (empty? (legacy-clauses (:subjectCond rule)))
                           (empty? (legacy-clauses (:resourceCond rule))))
                  (warning "UNCONDITIONAL_RULE"
                           (str "Rule '" (:name rule) "' has no conditions and will match every request in scope.")
                           :rule (:name rule))))
              rules)
        grouped (vals (group-by normalized-condition-signature rules))
        signature-warnings
        (mapcat (fn [same-signature-rules]
                  (let [sorted-rules (sort-by (fn [rule] [(- (or (:priority rule) 0)) (:name rule)]) same-signature-rules)
                        highest (first sorted-rules)
                        rest-rules (rest sorted-rules)
                        effects (set (map :effect same-signature-rules))]
                    (concat
                     (when (and (> (count same-signature-rules) 1) (= 1 (count effects)))
                       (map (fn [shadowed-rule]
                              (warning "SHADOWED_RULE"
                                       (str "Rule '" (:name shadowed-rule) "' is shadowed by higher-priority rule '"
                                            (:name highest) "' with the same match conditions.")
                                       :rule (:name shadowed-rule)
                                       :shadowed-by (:name highest)))
                            rest-rules))
                     (when (> (count effects) 1)
                       [(warning "CONFLICTING_RULE_MATCH"
                                 (str "Multiple rules with identical match conditions produce different effects for operation '"
                                      (or (:operation highest) "*") "'.")
                                 :rules (mapv :name same-signature-rules))]))))
                grouped)]
    (vec (concat missing-operation unconditional signature-warnings))))

(defn analyze-policy
  [submitted-resource-class policy]
  (let [rules (vec (:rules policy))
        per-rule-issues
        (mapcat (fn [rule]
                  (let [rule-name (or (:name rule) "<unnamed>")]
                    (concat
                     (validate-conditions-field rule-name (:conditions rule))
                     (validate-legacy-field rule-name :subjectCond (:subjectCond rule) :subject)
                     (validate-legacy-field rule-name :resourceCond (:resourceCond rule) :resource))))
                rules)]
    {:errors (vec (concat (duplicate-name-issues rules)
                          (strategy-issues policy)
                          (resource-class-issues submitted-resource-class rules)
                          (schema-issues submitted-resource-class policy rules)
                          per-rule-issues))
     :warnings (warning-issues rules)}))

(defn validate-policy!
  [submitted-resource-class policy]
  (let [{:keys [errors] :as analysis} (analyze-policy submitted-resource-class policy)]
    (when (seq errors)
      (throw (ex-info
              (str "Policy safety validation failed: "
                   (str/join "; " (map :message (take 3 errors)))
                   (when (> (count errors) 3)
                     (str " (and " (- (count errors) 3) " more issues)")))
              {:status 400
               :error-code "INVALID_POLICY_SAFETY"
               :issues errors
               :analysis analysis})))
    analysis))
