(ns autho.pdp
  (:require [autho.prp :as prp]
            [autho.jsonrule :as rule]
            [autho.kafka-pip :as kafka-pip]
            [autho.kafka-pip-unified :as kafka-pip-unified]
            [autho.local-cache :as local-cache]
            [autho.metrics :as metrics]
            [autho.audit :as audit]
            [autho.policy-yaml :as policy-yaml]
            [autho.policy-versions :as pv]
            [autho.policy-impact-history :as pih]
            [autho.otel :as otel]
            [autho.delegation :as deleg]
            [autho.features :as features]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clj-http [client]]
            [clojure.data.json :as json]
            [autho.ldap :as ldap]
            [clojure.string :as str]
            [autho.person :as person])
  (:import (java.util Map)))

(def properties (atom {}))

(defn getProperty [prop]
  (get @properties prop)
  )

(defn kafka-enabled?
  "Check if Kafka features are enabled via KAFKA_ENABLED environment variable.
  Defaults to true for backward compatibility."
  []
  (if-let [env-enabled (System/getenv "KAFKA_ENABLED")]
    (Boolean/parseBoolean env-enabled)
    true))

(defn- get-subject [request body]
  (let [identity (:identity request)]
    (if (= :api-key (:auth-method identity))
      (:subject body)
      identity)))

(defn get-rules-repository-path []
  (getProperty :rules.repository))


(defn resolve-conflict [policy success-rules]
  (if (empty? success-rules)
    false
  (case (:strategy policy)
    :almost_one_allow_no_deny (if (not (some (fn [rule] (= "allow" (:effect rule))) success-rules))
                                false
                                (let [fa (filter (fn [r] (= "allow" (:effect r))) success-rules)
                                      fd (filter (fn [r] (= "deny" (:effect r))) success-rules)
                                      maxr-allow (apply max-key :priority (filter (fn [r] (= "allow" (:effect r))) success-rules))
                                      maxr-deny (if (not(empty? fd))
                                                  (apply max-key :priority fd)
                                                  {:priority -1000})
                                      ]
                                  (>= (:priority maxr-allow)
                                          (:priority maxr-deny))))

    :default false
    )
  ))


(defn- urlFiller [^Map filler ^Map object]
  (try
    (let [resp (clj-http.client/post (:url filler) {:form-params object :content-type :json})
          jsresp (json/read-str (:body resp) :key-fn keyword)]
      jsresp                                                ;; return the augmented object
      )
    (catch Exception e (do (.getMessage e) object))))

;; Memoized ns-resolve to avoid repeated symbol lookup on every call
(def ^:private resolve-filler-fn
  (memoize (fn [type-str]
             (ns-resolve (symbol "autho.attfun") (symbol type-str)))))

(defn- apply-filler
  "Applies a filler to the entity and merges with cache.
  Returns the enriched+merged entity, or the original entity on error."
  [filler entity cache-type]
  (if-not filler
    (local-cache/mergeEntityWithCache entity cache-type)
    (try
      (let [filler-fn (resolve-filler-fn (:type filler))
            enriched  (when filler-fn (filler-fn filler entity))]
        (local-cache/mergeEntityWithCache (or enriched entity) cache-type))
      (catch Exception e
        (.warn (org.slf4j.LoggerFactory/getLogger "autho.pdp")
               "Filler call failed, falling back to cache-only: {}" (.getMessage e))
        (local-cache/mergeEntityWithCache entity cache-type)))))

(defn- callFillers
  "Enriches subject and resource using configured fillers, in parallel.
  Both enrichments are launched as futures and joined with a 5s timeout.
  Falls back to cache-only merge on timeout or error."
  [request]
  (let [subfill  (prp/getSubjectFiller  (:class (:subject request)))
        ressfill (prp/getResourceFiller (:class (:resource request)))
        ;; Launch both enrichments in parallel
        subj-f   (future (apply-filler subfill  (:subject  request) :subject))
        res-f    (future (apply-filler ressfill (:resource request) :resource))
        ;; Join with 5s timeout; fall back to cache-only on timeout
        cs       (deref subj-f 5000 (local-cache/mergeEntityWithCache (:subject  request) :subject))
        cr       (deref res-f  5000 (local-cache/mergeEntityWithCache (:resource request) :resource))]
    (assoc request :subject cs :resource cr)))

(defn passThroughCache
  "Merges subject and resource with their cache entries (no external PIP calls).
  Used when no fillers are configured for the request's classes."
  [request]
  (let [cs (local-cache/mergeEntityWithCache (:subject request) :subject)
        cr (local-cache/mergeEntityWithCache (:resource request) :resource)]
    (assoc request :subject cs :resource cr)))

(defn- enrich-request
  "Enriches the request with fillers if configured, otherwise uses cache-only merge.
  This is the main entry point replacing direct passThroughCache calls."
  [request]
  (let [has-subject-filler  (prp/getSubjectFiller  (:class (:subject request)))
        has-resource-filler (prp/getResourceFiller (:class (:resource request)))]
    (if (or has-subject-filler has-resource-filler)
      (callFillers request)
      (passThroughCache request))))

;; the request is composed of
;; subject
;; resource
;; operation
;; context (date, application, domain)
;; return a map composed of the result (:result) and the set of applicable rules (:rules)

(defn- applicable-rules
  "Filtre les regles d'une politique selon l'operation demandee.
   Si aucune operation n'est fournie, toutes les regles sont candidates.
   Une regle sans :operation s'applique a toutes les operations."
  [rules operation]
  (filter (fn [rule]
            (let [rule-op (:operation rule)]
              (or (nil? operation)
                  (nil? rule-op)
                  (= rule-op operation))))
          rules))

(defn- validate-authz-request!
  [authz-request]
  (if-not (:resource authz-request)
    (throw (ex-info "No resource specified" {:status 400})))
  (if-not (:subject authz-request)
    (throw (ex-info "No subject specified" {:status 400})))
  authz-request)

(defn- build-authz-request
  [request body]
  (validate-authz-request!
   {:subject (get-subject request body)
    :resource (:resource body)
    :operation (:operation body)
    :context (:context body)}))

(defn- build-rule-evaluation
  [rule augreq operation]
  (let [rule-op (:operation rule)
        op-match (or (nil? rule-op) (= rule-op operation))
        eval-result (if op-match
                      (rule/evaluateRule rule augreq)
                      {:value false})]
    {:rule rule
     :name (:name rule)
     :effect (:effect rule)
     :operation (:operation rule)
     :matched (:value eval-result)
     :resourceClass (:resourceClass rule)
     :priority (:priority rule)
     :subjectCond (when-let [subject-cond (:subjectCond rule)]
                    (rest subject-cond))
     :resourceCond (when-let [resource-cond (:resourceCond rule)]
                     (rest resource-cond))}))

(defn- evaluate-policy-rules
  [policy authz-request]
  (let [augreq (enrich-request authz-request)
        all-rules (vec (or (:rules policy) []))
        op (:operation authz-request)
        evaluated-rules (mapv #(build-rule-evaluation % augreq op) all-rules)
        matched-rule-objs (mapv :rule (filter :matched evaluated-rules))]
    {:policy policy
     :authz-request authz-request
     :augreq augreq
     :operation op
     :all-rules all-rules
     :evaluated-rules evaluated-rules
     :matched-rule-objs matched-rule-objs}))

(defn- canonical-decision
  [policy-eval]
  (let [policy (:policy policy-eval)
        matched-rule-objs (:matched-rule-objs policy-eval)
        allowed? (boolean (resolve-conflict policy matched-rule-objs))]
    (assoc policy-eval
           :decision (if allowed? :allow :deny)
           :allowed? allowed?
           :matched-rule-names (mapv :name matched-rule-objs)
           :conflict-strategy (:strategy policy))))

(defn- who-authorized-matches
  [policy body]
  (let [op (:operation body)
        candidate-rules (vec (applicable-rules (:rules policy) op))]
    {:strategy (:strategy policy)
     :allow-rules (->> candidate-rules
                       (filter #(= "allow" (:effect %)))
                       (filter #(rule/evalRuleWithResource % body))
                       vec)
     :deny-rules (->> candidate-rules
                      (filter #(= "deny" (:effect %)))
                      (filter #(rule/evalRuleWithResource % body))
                      vec)}))

(defn- project-who-authorized-rule
  [rule]
  {:resourceClass (:resourceClass rule)
   :subjectCond (rest (:subjectCond rule))
   :operation (:operation rule)})

(declare enrich-with-objects)

(defn- what-authorized-matches
  [policy authz-request]
  (let [op (:operation authz-request)
        op-rules (vec (applicable-rules (:rules policy) op))]
    {:strategy (:strategy policy)
     :allow-rules (->> op-rules
                       (filter #(= "allow" (:effect %)))
                       (filter #(rule/evalRuleWithSubject % authz-request))
                       vec)
     :deny-rules (->> op-rules
                      (filter #(= "deny" (:effect %)))
                      (filter #(rule/evalRuleWithSubject % authz-request))
                      vec)}))

(defn- project-what-authorized-rule
  [rule pagination-opts]
  (enrich-with-objects
   {:resourceClass (:resourceClass rule)
    :resourceCond (if (vector? (:resourceCond rule))
                    (rest (:resourceCond rule))
                    (:resourceCond rule))
    :operation (:operation rule)}
   pagination-opts))

(defn whoAuthorizedDetailed
  "Returns the inverse authorization analysis for a resource.
   This exposes both allow and deny rule candidates so callers can reason
   about the inverse view without losing the policy conflict context."
  [request body]
  (if-not (:resource body)
    (throw (ex-info "No resource specified" {:status 400})))

  (let [policy (prp/getGlobalPolicy (:class (:resource body)))]
    (if-not policy
      (throw (ex-info "No global policy applicable" {:status 404 :error-code "NO_POLICY"})))

    (let [matches (who-authorized-matches policy body)]
      {:strategy (:strategy matches)
       :operation (:operation body)
       :resourceClass (:class (:resource body))
       :allowCandidates (mapv project-who-authorized-rule (:allow-rules matches))
       :denyCandidates (mapv project-who-authorized-rule (:deny-rules matches))})))

(defn whatAuthorizedDetailed
  "Returns the forward authorization analysis for a subject and optional resource class.
   The result preserves both allow and deny rule projections so callers can
   inspect the effective permission space with the policy strategy in view.
   For backward compatibility, :resource is optional as long as the policy lookup
   layer can resolve the intended class."
  [request body]
  (let [subject (get-subject request body)
        page (get body :page 1)
        pageSize (get body :pageSize 20)
        authz-request {:subject subject
                       :resource (:resource body)
                       :operation (:operation body)
                       :context (:context body)}]
    (if-not (:subject authz-request)
      (throw (ex-info "No subject specified" {:status 400})))

    (let [policy (prp/getGlobalPolicy (:class (:resource authz-request)))]
      (if-not policy
        (throw (ex-info "No global policy applicable" {:status 404 :error-code "NO_POLICY"})))

      (let [matches (what-authorized-matches policy authz-request)
            pagination-opts {:page page :pageSize pageSize}]
        {:strategy (:strategy matches)
         :resourceClass (:class (:resource authz-request))
         :operation (:operation authz-request)
         :page page
         :pageSize pageSize
         :allow (mapv #(project-what-authorized-rule % pagination-opts)
                      (:allow-rules matches))
         :deny (mapv #(project-what-authorized-rule % pagination-opts)
                     (:deny-rules matches))}))))

(defn evalRequest
  "Evaluates an authorization request.
  Optional visited-subjects parameter tracks delegation chain to prevent cycles."
  ([request] (evalRequest request #{}))
  ([^Map request visited-subjects]
   (validate-authz-request! request)

   (let [subject-id (:id (:subject request))
         globalPolicy (prp/getGlobalPolicy (:class (:resource request)))
         decision-result (-> (evaluate-policy-rules globalPolicy request)
                             (canonical-decision))
         allowed? (:allowed? decision-result)]

     (if allowed?
       (do
         (metrics/record-decision! :allow (:class (:resource request)))
         (audit/log-decision! {:subject-id     (:id (:subject request))
                               :resource-class (:class (:resource request))
                               :resource-id    (:id (:resource request))
                               :operation      (:operation request)
                               :decision       :allow
                               :matched-rules  (:matched-rule-names decision-result)})
         {:result true :rules (:matched-rule-objs decision-result)})
       (let [deleg (deleg/findDelegation (:subject request))
             safe-deleg (filter #(let [delegate-id (:id (:delegate %))]
                                   (not (contains? visited-subjects delegate-id)))
                                deleg)
             new-visited (conj visited-subjects subject-id)
             delegated-allow (some #(let [delegate-id (:id (:delegate %))]
                                      (when (contains? visited-subjects delegate-id)
                                        (.warn (org.slf4j.LoggerFactory/getLogger "autho.pdp")
                                               "Circular delegation detected: {} -> {}"
                                               subject-id delegate-id))
                                      (let [ev (evalRequest (assoc request :subject (:delegate %)) new-visited)]
                                        (when (:result ev) ev)))
                                   safe-deleg)]
         (if delegated-allow
           delegated-allow
           (do
             (metrics/record-decision! :deny (:class (:resource request)))
             (audit/log-decision! {:subject-id     (:id (:subject request))
                                   :resource-class (:class (:resource request))
                                   :resource-id    (:id (:resource request))
                                   :operation      (:operation request)
                                   :decision       :deny
                                   :matched-rules  (:matched-rule-names decision-result)})
             {:result false :rules (:matched-rule-objs decision-result)})))))))

(defn isAuthorized [request body]
  (otel/with-span "authz.isAuthorized"
    {:subject-id     (str (get-in body [:subject :id]))
     :resource-class (str (get-in body [:resource :class]))
     :operation      (str (:operation body))}
    (let [authz-request (build-authz-request request body)
          subject-id (:id (:subject authz-request))
          resource-class (:class (:resource authz-request))
          resource-id (:id (:resource authz-request))
          operation (:operation authz-request)]
      (if-not (prp/getGlobalPolicy resource-class)
        (throw (ex-info "No global policy applicable" {:status 404 :error-code "NO_POLICY"})))

      (or (when-not (:timestamp (:context body))
            (local-cache/get-cached-decision subject-id resource-class resource-id operation))
          (let [decision-result (evalRequest authz-request)
                allowed? (:result decision-result)
                matched-rules (mapv :name (:rules decision-result))
                result {:allowed allowed?
                        :decision (if allowed? "allow" "deny")
                        :results matched-rules
                        :matchedRules matched-rules
                        :resourceClass resource-class
                        :resourceId resource-id
                        :operation operation}]
            (local-cache/cache-decision! subject-id resource-class resource-id operation result)
            result)))))

;; V2.0 retreive charasteristics of persons allowed to do an operation on a resource*
;; on considÃ¨re pour simplifier que la condition est un ET de clauses
;; on simplifie en retournant la 1ere rÃ¨gle en allow sans vÃ©rifier les deny
(defn whoAuthorized [request body]
  (:allowCandidates (whoAuthorizedDetailed request body)))

(defn whoAuthorizedByClass
  "Returns all authorization rules (allow) for a given resource class.
  This shows what subject conditions are required to access resources of this class."
  [resourceClass]
  (if-not resourceClass
    (throw (ex-info "No resource class specified" {:status 400 :error-code "MISSING_RESOURCE_CLASS"})))

  (let [globalPolicy (prp/getGlobalPolicy resourceClass)]
    (if-not globalPolicy
      (throw (ex-info "No global policy found for this resource class" {:status 404 :error-code "NO_POLICY"})))

    (let [allowRules (filter #(= "allow" (:effect %)) (:rules globalPolicy))
          denyRules (filter #(= "deny" (:effect %)) (:rules globalPolicy))]
      {:resourceClass resourceClass
       :strategy (:strategy globalPolicy)
       :allowRules (map (fn [rule] {:name (:name rule)
                                    :operation (:operation rule)
                                    :subjectCond (rest (:subjectCond rule))
                                    :resourceCond (rest (:resourceCond rule))
                                    :priority (:priority rule)})
                        allowRules)
       :denyRules (map (fn [rule] {:name (:name rule)
                                   :operation (:operation rule)
                                   :subjectCond (rest (:subjectCond rule))
                                   :resourceCond (rest (:resourceCond rule))
                                   :priority (:priority rule)})
                       denyRules)})))

(defn- object-matches-resource-cond? [obj resource-clauses]
  "Checks if an object matches all resource conditions from a rule.
  Returns true if all conditions are satisfied."
  (try
    (let [ctxt (assoc {:resource obj} :class :Resource)]
      (every? #(rule/evalClause % ctxt :resource) resource-clauses))
    (catch Exception e
      false)))

;; Configurable upper bound on the number of objects fetched per query.
;; Prevents OOM when classes contain millions of objects.
;; Can be overridden with MAX_OBJECTS_QUERY_LIMIT environment variable.
(def max-objects-query-limit
  (Long/parseLong (or (System/getenv "MAX_OBJECTS_QUERY_LIMIT") "10000")))

(defn- enrich-with-objects
  "Enriches a rule result with actual objects from Kafka PIP if available.
  Supports pagination with page and pageSize parameters.

  Uses server-side offset/limit pagination via RocksDB iterator to avoid
  loading all objects into memory (prevents OOM on large datasets).
  The total count is an approximation of the pre-filter object count."
  [rule-result {:keys [page pageSize] :or {page 1 pageSize 20}}]
  (let [resource-class (:resourceClass rule-result)
        resource-conds (rest (:resourceCond rule-result))]
    (if (and (kafka-enabled?) (prp/has-kafka-pip? resource-class))
      (let [offset       (* (dec page) pageSize)
            ;; Overfetch by 10x the page size to absorb filtering losses,
            ;; but never exceed the configured safety limit.
            fetch-limit  (min max-objects-query-limit (* pageSize 10))
            objects      (kafka-pip/query-all-objects resource-class {:offset offset :limit fetch-limit})
            filtered     (filter #(object-matches-resource-cond? % resource-conds) objects)
            items        (vec (take pageSize filtered))
            ;; Total is the pre-filter count (fast RocksDB metadata read).
            total-approx (kafka-pip/count-objects resource-class)
            has-more     (> total-approx (+ offset pageSize))]
        (assoc rule-result :objects {:items      items
                                     :pagination {:page     page
                                                  :pageSize pageSize
                                                  :total    total-approx
                                                  :hasMore  has-more}}))
      rule-result)))

(defn whatAuthorized
  "Returns resources that a subject is authorized to access.
  For classes with Kafka PIPs, also returns the actual matching objects with pagination.

  Optional query parameters in body:
  - page: Page number (default: 1)
  - pageSize: Number of objects per page (default: 20)"
  [request body]
  (let [detailed (whatAuthorizedDetailed request body)]
    {:allow (:allow detailed)
     :deny (:deny detailed)}))

(defn explain
  "Explains the authorization decision by showing all evaluated rules and their results.
  Takes the same request body as isAuthorized, returns detailed rule evaluation information."
  [request body]
  (let [authz-request (build-authz-request request body)
        resource-class (:class (:resource authz-request))
        resource-id (:id (:resource authz-request))
        operation (:operation authz-request)
        globalPolicy (prp/getGlobalPolicy resource-class)]
    (if-not globalPolicy
      (throw (ex-info "No global policy applicable" {:status 404 :error-code "NO_POLICY"})))

    (let [decision-result (-> (evaluate-policy-rules globalPolicy authz-request)
                              (canonical-decision))]
      {:decision (:allowed? decision-result)
       :allowed? (:allowed? decision-result)
       :decisionType (name (:decision decision-result))
       :strategy (:conflict-strategy decision-result)
       :resourceClass resource-class
       :resourceId resource-id
       :operation operation
       :totalRules (count (:all-rules decision-result))
       :matchedRules (count (:matched-rule-objs decision-result))
       :matchedRuleNames (:matched-rule-names decision-result)
       :rules (:evaluated-rules decision-result)})))

(defn simulate
  "Dry-run: evaluates an authorization request against a supplied (unsaved) policy.
   body keys:
     :subject, :resource, :operation - same as isAuthorized
     :simulatedPolicy - full policy map (resourceClass, strategy, rules)
                        If absent, uses the currently active policy (like explain).
     :policyVersion   - integer; if supplied, uses that stored version instead
   Returns the same shape as explain: decision + per-rule trace.
   Never writes to cache or audit log."
  [request body]
  (otel/with-span "authz.simulate"
    {:resource-class (str (get-in body [:resource :class]))}
    (let [authz-request (build-authz-request request body)
          resource-class (:class (:resource authz-request))
          policy (cond
                   (:simulatedPolicy body) (:simulatedPolicy body)
                   (:policyVersion body) (pv/get-version resource-class (:policyVersion body))
                   :else (prp/getGlobalPolicy resource-class))]
      (if-not policy
        (throw (ex-info "No policy available for simulation"
                        {:status 404 :error-code "NO_POLICY"})))

      (let [decision-result (-> (evaluate-policy-rules policy authz-request)
                                (canonical-decision))]
        {:decision      (:allowed? decision-result)
         :allowed?      (:allowed? decision-result)
         :decisionType  (name (:decision decision-result))
         :strategy      (:conflict-strategy decision-result)
         :resourceClass resource-class
         :resourceId    (:id (:resource authz-request))
         :operation     (:operation authz-request)
         :simulated     true
         :policySource  (cond (:simulatedPolicy body) :provided
                              (:policyVersion body)   :version
                              :else                   :current)
         :policyVersion (or (:policyVersion body)
                            (pv/latest-version-number resource-class))
         :totalRules    (count (:all-rules decision-result))
         :matchedRules  (count (:matched-rule-objs decision-result))
         :matchedRuleNames (:matched-rule-names decision-result)
         :rules         (:evaluated-rules decision-result)}))))

(defn- load-props
  [file-name]
  (with-open [^java.io.Reader reader (clojure.java.io/reader file-name)]
    (let [props (java.util.Properties.)]
      (.load props reader)
      (into {} (for [[k v] props] [(keyword k) (edn/read-string v)])))))

(defn init []
  (swap! properties (fn [oldprop] (load-props "resources/pdp-prop.properties")))
  (metrics/init-jvm-metrics!)
  (when (features/licensed? :audit)
    (audit/init!))
  (when (features/licensed? :versioning)
    (pv/init!)
    (pih/init!))

  ;; Only register Kafka shutdown hook if Kafka is enabled
  (when (kafka-enabled?)
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(kafka-pip/stop-all-pips))))

  (let [ldapprop (filter (fn [propunit] (str/starts-with? (name propunit) "ldap")) @properties)
        all-pips       (prp/get-pips)
        kafka-pips     (filter #(= :kafka-pip     (:type %)) all-pips)
        unified-pips   (filter #(= :kafka-pip-unified (:type %)) all-pips)
        rocksdb-path   (getProperty :kafka.pip.rocksdb.path)
        kafka-pip-classes (map :class kafka-pips)
        unified-classes   (mapcat :classes unified-pips)]
    (if (getProperty :ldap.server) (ldap/init {:host     (getProperty :ldap.server)
                                               :bind-dn  (getProperty :ldap.connectstring)
                                               :password (or (System/getenv "LDAP_PASSWORD")
                                                            (getProperty :ldap.password))
                                               }))
    ;; Initialise les PIPs Kafka individuels
    (when (and (kafka-enabled?) (seq kafka-pips) rocksdb-path)
      (kafka-pip/open-shared-db rocksdb-path kafka-pip-classes)
      (doseq [pip-config kafka-pips]
        (kafka-pip/init-pip pip-config)))
    ;; Initialise le PIP Kafka unifiÃ©
    (when (and (kafka-enabled?) (seq unified-pips) rocksdb-path)
      (kafka-pip-unified/open-shared-db rocksdb-path unified-classes)
      (doseq [pip-config unified-pips]
        (kafka-pip-unified/init-unified-pip pip-config))))
  ;; init the prp
  (prp/initf (getProperty :rules.repository))

  ;; Start YAML policy directory watcher if POLICIES_DIR is configured
  (when-let [policies-dir (or (System/getenv "POLICIES_DIR") (getProperty :policies.dir))]
    (policy-yaml/start-directory-watcher! policies-dir))

  ;; init delegations
  (prp/initDelegations)
  ;; init persons
  (person/loadPersons {:type (keyword (getProperty :person.source)) :props @properties})

  (deleg/batchCompile)


  )



