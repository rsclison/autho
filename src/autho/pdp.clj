(ns autho.pdp
  (:use [clojure.test :exclude [report]])
  (:require [autho.prp :as prp]
            [autho.jsonrule :as rule]
            [autho.kafka-pip :as kafka-pip]
            [autho.cache :as cache]
            [autho.delegation :as deleg]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clj-http [client]]
            [clojure.data.json :as json]
            [autho.ldap :as ldap]
            [clojure.string :as str]
            [autho.unify :as unf]
            [autho.person :as person]
            [java-time :as ti]
 ;;           [taoensso.timbre :as timbre
 ;;            :refer [log  trace  debug  info  warn  error  fatal report
 ;;                    logf tracef debugf infof warnf errorf fatalf reportf
 ;;                    spy get-env]]
        ;;    [buddy.sign.jwt :as jwt]
            )
  (:import (java.util Map)))

(def properties (atom {}))

(defn getProperty [prop]
  (get @properties prop)
  )

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

(defn- callFillers [request]                                ;; TODO review implementation
  (let [
        subfill (prp/getSubjectFiller (:class (:subject request)))
        ressfill (prp/getResourceFiller (:class (:resource request)))]

    (let [augs (if subfill (apply (ns-resolve (symbol "autho.attfun") (symbol (:type subfill))) [subfill (:subject request)]))
          augr (if ressfill (apply (ns-resolve (symbol "autho.attfun") (symbol (:type ressfill))) [ressfill (:resource request)]))]
      (cache/mergeEntityWithCache augs cache/subject-cache)
      (cache/mergeEntityWithCache augr cache/resource-cache)
      (-> request
          (assoc :subject augs)
          (assoc :resource augr)
      )
    )
  )
  )


(defn passThroughCache [request]
  (let [cs (cache/mergeEntityWithCache (:subject request) cache/subject-cache)
        cr (cache/mergeEntityWithCache (:resource request) cache/resource-cache)]
    (assoc request
      :subject cs
      :resource cr))
  )

;; the request is composed of
;; subject
;; resource
;; operation
;; context (date, application, domain)
;; return a map composed of the result (:result) and the set of applicable rules (:rules)

(defn evalRequest
  "Evaluates an authorization request.
  Optional visited-subjects parameter tracks delegation chain to prevent cycles."
  ([request] (evalRequest request #{}))
  ([^Map request visited-subjects]
   (if-not (:resource request)
     (throw (ex-info "No resource specified" {:status 400})))
   (if-not (:subject request)
     (throw (ex-info "No subject specified" {:status 400})))

   (let [subject-id (:id (:subject request))
         globalPolicy (prp/getGlobalPolicy (:class (:resource request)))
         policy (prp/getPolicy (:class (:resource request)) (:application (:context request)))
         augreq (passThroughCache request)
         evalglob (reduce (fn [res rule] (if (:value (rule/evaluateRule rule augreq))
                                            (conj res rule)
                                            res))
                          [] (:rules globalPolicy))
         resolve (resolve-conflict globalPolicy evalglob)]

     (if resolve
       {:result resolve :rules evalglob}
       (let [deleg (deleg/findDelegation (:subject request))
             ;; Filter out delegations that would create cycles
             safe-deleg (filter #(let [delegate-id (:id (:delegate %))]
                                   (not (contains? visited-subjects delegate-id)))
                                deleg)
             new-visited (conj visited-subjects subject-id)
             one (some #(let [delegate-id (:id (:delegate %))]
                          ;; Log circular delegation attempts
                          (when (contains? visited-subjects delegate-id)
                            (.warn (org.slf4j.LoggerFactory/getLogger "autho.pdp")
                                   "Circular delegation detected: {} -> {}"
                                   subject-id delegate-id))
                          (let [ev (evalRequest (assoc request :subject (:delegate %)) new-visited)]
                            (when ev ev)))
                       safe-deleg)]
         (if one
           one
           {:result false :rules evalglob}))))))

(defn isAuthorized [request body]
  (let [subject (get-subject request body)
        authz-request {:subject subject :resource (:resource body) :operation (:operation body) :context (:context body)}]
    (if-not (:resource authz-request)
      (throw (ex-info "No resource specified" {:status 400})))
    (if-not (:subject authz-request)
      (throw (ex-info "No subject specified" {:status 400})))

    (let [globalPolicy (prp/getGlobalPolicy (:class (:resource authz-request)))
          augreq (passThroughCache authz-request)
          evalglob (reduce (fn [res rule] (if (:value (rule/evaluateRule rule augreq))
                                             (conj res rule)
                                             res))
                           [] (:rules globalPolicy))]
      (if-not globalPolicy
        (throw (ex-info "No global policy applicable" {:status 404})))
      {:results (map #(:name %1) evalglob)})))

;; V2.0 retreive charasteristics of persons allowed to do an operation on a resource*
;; on considère pour simplifier que la condition est un ET de clauses
;; on simplifie en retournant la 1ere règle en allow sans vérifier les deny
(defn whoAuthorized [request body]
  (if-not (:resource body)
    (throw (ex-info "No resource specified" {:status 400})))

  (let [candrules (filter #(= "allow" (:effect %)) (:rules (prp/getGlobalPolicy (:class (:resource body)))))
        evrules (filter #(rule/evalRuleWithResource % body) candrules)]
    (map (fn [rule] {:resourceClass (:resourceClass rule)
                     :subjectCond (rest (:subjectCond rule))
                     :operation (:operation rule)})
         evrules)))

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

(defn- enrich-with-objects
  "Enriches a rule result with actual objects from Kafka PIP if available.
  Supports pagination with page and pageSize parameters."
  [rule-result {:keys [page pageSize] :or {page 1 pageSize 20}}]
  (let [resource-class (:resourceClass rule-result)
        resource-conds (rest (:resourceCond rule-result))]
    (if (and (kafka-enabled?) (prp/has-kafka-pip? resource-class))
      (let [offset (* (dec page) pageSize)
            ;; Get more objects than needed to filter, then paginate
            ;; This is not optimal but works for now
            all-objects (kafka-pip/query-all-objects resource-class {})
            filtered-objects (filter #(object-matches-resource-cond? % resource-conds) all-objects)
            total (count filtered-objects)
            paginated-objects (take pageSize (drop offset filtered-objects))
            has-more (> total (+ offset pageSize))]
        (assoc rule-result :objects {
                                     :items (vec paginated-objects)
                                     :pagination {
                                                  :page page
                                                  :pageSize pageSize
                                                  :total total
                                                  :hasMore has-more}}))
      rule-result)))

(defn whatAuthorized
  "Returns resources that a subject is authorized to access.
  For classes with Kafka PIPs, also returns the actual matching objects with pagination.

  Optional query parameters in body:
  - page: Page number (default: 1)
  - pageSize: Number of objects per page (default: 20)"
  [request body]
  (let [subject (get-subject request body)
        page (get body :page 1)
        pageSize (get body :pageSize 20)
        authz-request {:subject subject :resource (:resource body) :operation (:operation body)}]
    (if-not (:subject authz-request)
      (throw (ex-info "No subject specified" {:status 400})))

    (let [allowrules (filter #(= "allow" (:effect %)) (:rules (prp/getGlobalPolicy (:class (:resource authz-request)))))
          denyrules (filter #(= "deny" (:effect %)) (:rules (prp/getGlobalPolicy (:class (:resource authz-request)))))
          evrules1 (filter #(rule/evalRuleWithSubject % authz-request) allowrules)
          evrules2 (filter #(rule/evalRuleWithSubject % authz-request) denyrules)
          pagination-opts {:page page :pageSize pageSize}]
      {:allow (map #(enrich-with-objects % pagination-opts)
                   (map (fn [rule] {:resourceClass (:resourceClass rule)
                                    :resourceCond (:resourceCond rule)
                                    :operation (:operation rule)})
                        evrules1))
       :deny (map #(enrich-with-objects % pagination-opts)
                  (map (fn [rule] {:resourceClass (:resourceClass rule)
                                   :resourceCond (:resourceCond rule)
                                   :operation (:operation rule)})
                       evrules2))})))

(defn explain
  "Explains the authorization decision by showing all evaluated rules and their results.
  Takes the same request body as isAuthorized, returns detailed rule evaluation information."
  [request body]
  (let [subject (get-subject request body)
        authz-request {:subject subject :resource (:resource body) :operation (:operation body) :context (:context body)}]
    (if-not (:resource authz-request)
      (throw (ex-info "No resource specified" {:status 400 :error-code "MISSING_RESOURCE"})))
    (if-not (:subject authz-request)
      (throw (ex-info "No subject specified" {:status 400 :error-code "MISSING_SUBJECT"})))

    (let [globalPolicy (prp/getGlobalPolicy (:class (:resource authz-request)))
          augreq (passThroughCache authz-request)
          all-rules (:rules globalPolicy)
          evaluated-rules (map (fn [rule]
                                 (let [eval-result (rule/evaluateRule rule augreq)]
                                   {:name (:name rule)
                                    :effect (:effect rule)
                                    :operation (:operation rule)
                                    :matched (:value eval-result)
                                    :resourceClass (:resourceClass rule)
                                    :subjectCond (rest (:subjectCond rule))
                                    :resourceCond (rest (:resourceCond rule))}))
                               all-rules)
          matched-rules (filter :matched evaluated-rules)
          final-decision (resolve-conflict globalPolicy (filter #(:value (rule/evaluateRule % augreq)) all-rules))]

      (if-not globalPolicy
        (throw (ex-info "No global policy applicable" {:status 404 :error-code "NO_POLICY"})))

      {:decision final-decision
       :strategy (:strategy globalPolicy)
       :totalRules (count all-rules)
       :matchedRules (count matched-rules)
       :rules evaluated-rules})))




(defn- load-props
  [file-name]
  (with-open [^java.io.Reader reader (clojure.java.io/reader file-name)]
    (let [props (java.util.Properties.)]
      (.load props reader)
      (into {} (for [[k v] props] [(keyword k) (edn/read-string v)])))))

(defn kafka-enabled?
  "Check if Kafka features are enabled via KAFKA_ENABLED environment variable.
  Defaults to true for backward compatibility."
  []
  (if-let [env-enabled (System/getenv "KAFKA_ENABLED")]
    (Boolean/parseBoolean env-enabled)
    true))

(defn init []
  (swap! properties (fn [oldprop] (load-props "resources/pdp-prop.properties")))

  ;; Only register Kafka shutdown hook if Kafka is enabled
  (when (kafka-enabled?)
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(kafka-pip/stop-all-pips))))

  (let [ldapprop (filter (fn [propunit] (str/starts-with? (name propunit) "ldap")) @properties)
        kafka-pips (filter #(= :kafka-pip (:type %)) (prp/get-pips))
        rocksdb-path (getProperty :kafka.pip.rocksdb.path)
        kafka-pip-classes (map :class kafka-pips)]
    (if (getProperty :ldap.server) (ldap/init {:host     (getProperty :ldap.server)
                                               :bind-dn  (getProperty :ldap.connectstring)
                                               :password (or (System/getenv "LDAP_PASSWORD")
                                                            (getProperty :ldap.password))
                                               }))
    ;; Only initialize Kafka PIPs if Kafka is enabled
    (when (and (kafka-enabled?) (seq kafka-pips) rocksdb-path)
      (kafka-pip/open-shared-db rocksdb-path kafka-pip-classes)
      (doseq [pip-config kafka-pips]
        (kafka-pip/init-pip pip-config))))
  ;; init the prp
  (prp/initf (getProperty :rules.repository))

  ;; init delegations
  (prp/initDelegations)
  ;; init persons
  (person/loadPersons {:type (keyword (getProperty :person.source)) :props @properties})

  (deleg/batchCompile)


  )

