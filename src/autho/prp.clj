(ns autho.prp
  (:require [json-schema.core :as validjs]
            [clojure.data.json :as json]
            [autho.policy-format :as policy-format]
            [clojure.java.jdbc :as jd]
            [autho.local-cache :as local-cache]
            [autho.policy-safety :as policy-safety]
            [autho.policy-tests :as policy-tests]
            [autho.policy-versions :as pv]
            [java-time :as ti]
            [autho.utils :as utl])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.prp"))

;; H2_POLICY_CIPHER_KEY enables AES-128 at-rest encryption of the policy database.
;; Must match the key used by policy_versions.clj (shared database).
;; See docs/SECURITY_ADMIN_GUIDE.md for migration from unencrypted.
(def ^:private h2-policy-cipher-key (System/getenv "H2_POLICY_CIPHER_KEY"))
(def ^:private h2-policy-db-path
  (or (System/getenv "AUTHO_POLICY_DB_PATH")
      (System/getProperty "autho.policy.db.path")
      "./resources/h2db"))

(def h2db
  (merge
   {:classname   "org.h2.Driver"
    :subprotocol "h2"
    :user        "sa"}
   (if h2-policy-cipher-key
     {:subname  (str h2-policy-db-path ";CIPHER=AES")
      :password (str h2-policy-cipher-key " ")}
     {:subname  h2-policy-db-path
      :password ""})))


;;(defrecord Rule [^String name ^String resourceClass ^String operation ^String condition ^String effect ^String startDate ^String endDate])
(defrecord Rule2 [^String name ^String resourceClass ^Number priority ^String operation conditions ^String effect ^String startDate ^String endDate])  ;; une resourceCond ou une subjectCond sont de la forme [type ?var clause1 clause2 ...]

(defrecord Policy [^String resourceClass ])

(def policySchema (validjs/prepare-schema (slurp "resources/policySchema.json")))

(def delegationSingleton (atom {:type :file :path "resources/delegations.edn"}))
(def personSingleton (atom []))

(def pips (atom (utl/load-edn (or (System/getenv "PIPS_CONFIG_PATH")
                                  "resources/pips.edn"))))

(defn get-pips []
  @pips)

;;(defn rule [{:keys [name resourceClass operation condition effect]}]
;;  (->Rule name resourceClass operation condition effect (ti/local-date "yyyy-MM-dd" "1961-01-01")(ti/local-date  "yyyy-MM-dd" "3000-12-31"))
;;  )

(defn rule2 [{:keys [name resourceClass priority operation conditions effect]}]
  (->Rule2 name resourceClass priority operation conditions effect (ti/local-date "yyyy-MM-dd" "1961-01-01")(ti/local-date  "yyyy-MM-dd" "3000-12-31"))
  )


(def ^{:private true} policiesMap (atom {}))

(def supported-policy-environments #{"dev" "staging" "prod"})
(def default-policy-environment "prod")

(defonce rules-repository-status (atom :not-loaded))

(defn get-rules-repository-status []
  @rules-repository-status)

;; return a collection of all delegations for compilation
(defmulti getDelegations (fn [](:type @delegationSingleton)))
(defmethod getDelegations :file []
  (:delegations @delegationSingleton)
  )

(defmulti getCompiledDelegations (fn [] (:type @delegationSingleton)))
(defmethod getCompiledDelegations :file [] (:compiled @delegationSingleton))

(defn getPersons []
  @personSingleton)

(defmulti saveCompDelegations (fn [compdel](:type @delegationSingleton)))
(defmethod saveCompDelegations :file [rescomp]
  (swap! delegationSingleton (fn [curvalue comps] (assoc curvalue :compiled comps)) rescomp)
  )

(defmulti initDelegations (fn [] (:type @delegationSingleton)))

(defmethod initDelegations :file []
    (swap! delegationSingleton
           (fn [dels delfromfile]
             (assoc dels :delegations delfromfile)
             )
           (utl/load-edn (:path @delegationSingleton))
           ))


;; TODO implement the pip cache
;; TODO read pip declaration from a file
(def ^{:private true} attributeMap (atom '{:role {:type "internalPip" :method "role" :target :subject :cacheable false}
                    :astro {:type "urlPip" :url "http://localhost:8080/astro" :verb "post" :target :subject :cacheable false}}))


;; les fillers permettent de pré-remplir des sujets ou des ressources


;; Fillers are loaded from resources/fillers.edn.
;; Format: {:subject {"Person" {:type "internalFiller" :method "fillPerson"}}
;;          :resource {"Note"   {:type "urlFiller"      :url "http://..."}}}
(let [fillers-config (or (utl/load-edn "resources/fillers.edn") {})]
  (def subjectFillers  (atom (or (:subject  fillers-config) {})))
  (def resourceFillers (atom (or (:resource fillers-config) {}))))

;; TODO we could have also operationFillers
;; an operation could have specific attributes like "criticity" which could be used in rules

(defn getSubjectFiller [subjectClass]
  (get @subjectFillers subjectClass)
  )


(defn getResourceFiller [resourceClass]
  (get @resourceFillers resourceClass)
  )


(defn addOrReplaceJavaPip [attributes pip]
  (swap! attributeMap
         (fn [map]
           (reduce (fn [attmap att]
                     (assoc attmap (keyword att) {:type "javaPip" :instance pip})
                     )
                   map
                   attributes
                   )))
  )

(defn findPip [class attribute]
  (some (fn [pip]
          (cond
            ;; Unified Kafka PIP: matches any class in :classes list
            (and (= :kafka-pip-unified (:type pip))
                 (some #(= class %) (:classes pip)))
            pip
            ;; Standard PIP: matches by :class and optional :attributes filter
            (and (= class (:class pip))
                 (or (nil? (:attributes pip))
                     (some #(= % (keyword attribute)) (:attributes pip))))
            pip))
        @pips))

(defn has-kafka-pip? [class-name]
  "Checks if a given class has a Kafka PIP configured (individual or unified)."
  (some (fn [pip]
          (or (and (= :kafka-pip-unified (:type pip))
                   (some #(= class-name %) (:classes pip)))
              (and (= class-name (:class pip))
                   (= :kafka-pip (get-in pip [:pip :type])))))
        @pips))

(defn normalize-policy-environment
  [environment]
  (cond
    (nil? environment) default-policy-environment
    (keyword? environment) (name environment)
    (string? environment) environment
    :else (str environment)))

(defn validate-policy-environment!
  [environment]
  (let [env-name (normalize-policy-environment environment)]
    (when-not (contains? supported-policy-environments env-name)
      (throw (ex-info
              (str "Unsupported policy environment '" env-name "'.")
              {:status 400
               :error-code "INVALID_POLICY_ENVIRONMENT"
               :issues [{:code "INVALID_POLICY_ENVIRONMENT"
                         :message (str "Unsupported policy environment '" env-name "'.")
                         :environment env-name
                         :supported-environments (vec (sort supported-policy-environments))}]})))
    env-name))

(defn- entry->environments
  [entry]
  (cond
    (nil? entry) {}
    (contains? entry :environments) (:environments entry)
    (contains? entry :global) {default-policy-environment (:global entry)}
    :else {default-policy-environment entry}))

(defn- entry->global
  [entry]
  (cond
    (nil? entry) nil
    (contains? entry :global) (:global entry)
    :else entry))

(defn- validation-gate
  [name errors warnings & extra]
  (merge {:name name
          :status (cond
                    (seq errors) "failed"
                    (seq warnings) "warning"
                    :else "passed")
          :errors (vec (or errors []))
          :warnings (vec (or warnings []))}
         (apply hash-map extra)))

(defn build-validation-report
  [resource-class environment safety-analysis test-analysis]
  (let [safety-errors (vec (or (:errors safety-analysis) []))
        safety-warnings (vec (or (:warnings safety-analysis) []))
        test-errors (vec (or (:errors test-analysis) []))
        gates [(validation-gate "schema" [] [])
               (validation-gate "policy-safety" safety-errors safety-warnings)
               (validation-gate "policy-tests" test-errors []
                                :count (or (:count test-analysis) 0)
                                :passed (or (:passed test-analysis) 0)
                                :failed (or (:failed test-analysis) 0))]
        error-count (+ (count safety-errors) (count test-errors))
        warning-count (count safety-warnings)]
    {:resourceClass resource-class
     :environment environment
     :status (cond
               (pos? error-count) "failed"
               (pos? warning-count) "passed_with_warnings"
               :else "passed")
     :summary {:errors error-count
               :warnings warning-count
               :policyTests {:count (or (:count test-analysis) 0)
                             :passed (or (:passed test-analysis) 0)
                             :failed (or (:failed test-analysis) 0)}}
     :gates gates}))

(defn validation-exception-report
  [resource-class environment exception]
  (let [data (ex-data exception)
        error-code (:error-code data)
        issues (vec (or (:issues data) []))
        analysis (:analysis data)
        safety-analysis (if (= "INVALID_POLICY_SAFETY" error-code)
                          (or analysis {:errors issues :warnings []})
                          {:errors [] :warnings []})
        test-analysis (if (= "POLICY_TESTS_FAILED" error-code)
                        (or analysis {:count 0 :passed 0 :failed (count issues) :errors issues})
                        {:count 0 :passed 0 :failed 0 :errors []})
        report (build-validation-report resource-class
                                        (normalize-policy-environment environment)
                                        safety-analysis
                                        test-analysis)
        schema-errors (when (contains? #{"INVALID_POLICY_ENVIRONMENT"} error-code)
                        issues)]
    (cond-> report
      (seq schema-errors)
      (assoc :status "failed"
             :summary (assoc (:summary report)
                             :errors (+ (get-in report [:summary :errors] 0)
                                        (count schema-errors)))
             :gates (assoc (:gates report)
                           0
                           (validation-gate "schema" schema-errors []))))))

(defn insert-policy
  ([resourceClass pol]
   (if (or (contains? pol :global)
           (contains? pol :environments))
     (swap! policiesMap assoc resourceClass pol)
     (insert-policy resourceClass default-policy-environment pol)))
  ([resourceClass environment pol]
   (let [env-name (validate-policy-environment! environment)
         stored-policy (assoc pol :environment env-name)]
     (swap! policiesMap
            (fn [policies]
              (let [current-entry (get policies resourceClass)
                    environments (entry->environments current-entry)
                    next-environments (assoc environments env-name stored-policy)
                    current-global (or (entry->global current-entry)
                                       (get next-environments default-policy-environment))
                    next-global (if (= env-name default-policy-environment)
                                  stored-policy
                                  current-global)]
                (assoc policies resourceClass
                       {:global next-global
                        :environments next-environments})))))))

(defn validate-policy-submission
  "Validate a submitted policy without persisting it.
   Runs JSON Schema validation, policy safety checks and declarative policy tests."
  [^String resourceClass ^String policy]
  (let [raw-policy (json/read-str policy :key-fn keyword)
        environment (validate-policy-environment! (:environment raw-policy))]
    (validjs/validate policySchema policy)
    (let [pol-map (-> raw-policy
                    (policy-format/normalize-policy))
          pol-map (assoc pol-map :environment environment)
          safety-analysis (policy-safety/validate-policy! resourceClass pol-map)
          test-analysis (policy-tests/validate-policy-tests! pol-map)
          report (build-validation-report resourceClass environment safety-analysis test-analysis)]
      {:valid true
       :policy pol-map
       :environment environment
       :errors []
       :warnings (:warnings safety-analysis)
       :safety safety-analysis
       :tests test-analysis
       :report report})))

;;(defn submit-policy [^String resourceClass ^String policy]
;;  (let [js (slurp "resources/policySchema.json")
;;        jsvalidate (validjs/validator js)]
;;    (if (jsvalidate policy)
;;      (insert-policy resourceClass (json/read-str policy :key-fn keyword))
;;  )))

(defn submit-policy
  ([^String resourceClass ^String policy]
   (submit-policy resourceClass policy nil nil))
  ([^String resourceClass ^String policy author comment]
   ;; validate-policy-submission throws clojure.lang.ExceptionInfo on failure.
   (let [{policy-map :policy :as analysis} (validate-policy-submission resourceClass policy)]
      (insert-policy resourceClass (:environment policy-map) policy-map)
      (pv/save-version! resourceClass policy-map author comment)
      (local-cache/invalidate-decisions-for-class! resourceClass)
      (dissoc analysis :policy))))



(defn delete-policy
  ([^String resourceClass]
   (swap! policiesMap dissoc resourceClass)
   (local-cache/invalidate-decisions-for-class! resourceClass))
  ([^String resourceClass environment]
   (let [env-name (validate-policy-environment! environment)]
     (if (= env-name default-policy-environment)
       (delete-policy resourceClass)
       (do
         (swap! policiesMap update resourceClass
                (fn [entry]
                  (when entry
                    (let [next-environments (dissoc (entry->environments entry) env-name)]
                      (if (seq next-environments)
                        (assoc entry :environments next-environments)
                        nil)))))
         (local-cache/invalidate-decisions-for-class! resourceClass))))))

(defn initf [rulesf]
  (try
    (let [rules-map (utl/load-edn rulesf)]
      (swap! policiesMap
             (fn [a] (reduce (fn [hm ke]                      ;; treat a resourceClass rules
                               (let [rls (get rules-map ke)]
                                 (assoc hm ke {:global {:rules    (map #(rule2 %) (:rules (:global rls)))
                                                        :strategy (or (:strategy (:global rls))
                                                                       :almost_one_allow_no_deny)}})))
                             {}
                             (keys rules-map))))
      (reset! rules-repository-status :loaded))
    (catch Exception e
      (.error logger (str "Failed to load rule repository from " rulesf) e)
      (reset! rules-repository-status :failed))))



;; init in version 2.0
(defn init []
  (insert-policy "Facture" {:global {
                                  :rules    [(rule2 {:name          "R1"
                                                         :resourceClass "Facture"
                                                         :operation     "lire"
                                                         ;;   :condition     "(et(egal (att \"role\" ?subject) \"Professeur\")(egal(att \"astro.signe\" ?subject) \"poisson\"))"
                                                         :resource '[Facture ?f (< montant 1000)(= service ?serv)]
                                                         :subject '[Person ?subject (= role "chef_de_service")(= service ?serv)]
                                                         :effect        "allow"
                                                         :startDate     "inf"
                                                         :endDate       "inf"})

                                             (rule2 {:name          "R2"
                                                     :resourceClass "Facture"
                                                     :operation     "lire"
                                                     :resource '[Facture ?f ]
                                                     :subject '[Person ?subject (= role "chef_de_service")(= service ?serv)]
                                                     :effect        "allow"
                                                     :startDate     "inf"
                                                     :endDate       "inf"})
                                             ]
                                  :strategy :almost_one_allow_no_deny}
                         }
                 )
  )



(defn get-policies []
  @policiesMap
  )

(defn getGlobalPolicy
  ([resourceClass]
   (getGlobalPolicy resourceClass default-policy-environment))
  ([resourceClass environment]
   (let [env-name (validate-policy-environment! environment)
         entry (get @policiesMap resourceClass)]
     (or (get-in entry [:environments env-name])
         (when (= env-name default-policy-environment)
           (entry->global entry))))))

(defn getGlobalPolicies []
  (mapcat (fn [[_ entry]]
            (:rules (entry->global entry)))
          @policiesMap)
  )

(defn getPolicy [resourceClass application]
  (let [res (get (get @policiesMap resourceClass) application)]
    res
    )
  )

(defn valid-rules [ResourceClass policies]
  ;; find rules valid for today and for a ResourceClass
  (filter (fn [rule] (and (ti/after? (ti/local-date) (:startDate rule))
                          (ti/before? (ti/local-date) (:endDate rule))))
          (:rules (get policies ResourceClass))
          ))

(defn initDelegation []

  )
