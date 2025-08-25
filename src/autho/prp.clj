(ns autho.prp
  (:use [clojure.test])
  (:require [json-schema.core :as validjs]
            [clojure.data.json :as json]
            [clojure.java.jdbc :as jd]
            )
  (:require [clojure.java.io :as io] [clojure.edn :as edn])
  (:require [java-time :as ti])
  (:require [autho.utils :as utl])

  )

(def h2db
  {:classname   "org.h2.Driver"
   ;; :subprotocol "h2:mem"
   :subprotocol "h2"
  ;; :subname     "demo;DB_CLOSE_DELAY=-1"
   :subname "./resources/h2db"
   :user        "sa"
   :password    ""
  ;; :make-pool? true
   })


;;(defrecord Rule [^String name ^String resourceClass ^String operation ^String condition ^String effect ^String startDate ^String endDate])
(defrecord Rule2 [^String name ^String resourceClass ^Number priority ^String operation conditions ^String effect ^String startDate ^String endDate])  ;; une resourceCond ou une subjectCond sont de la forme [type ?var clause1 clause2 ...]

(defrecord Policy [^String resourceClass ])

(def policySchema (validjs/prepare-schema (slurp "resources/policySchema.json")))

(def delegationSingleton (atom {:type :file :path "resources/delegations.edn"}))
(def personSingleton (atom []))

(def pips (atom (utl/load-edn "resources/pips.edn")))

;;(defn rule [{:keys [name resourceClass operation condition effect]}]
;;  (->Rule name resourceClass operation condition effect (ti/local-date "yyyy-MM-dd" "1961-01-01")(ti/local-date  "yyyy-MM-dd" "3000-12-31"))
;;  )

(defn rule2 [{:keys [name resourceClass priority operation conditions effect]}]
  (->Rule2 name resourceClass priority operation conditions effect (ti/local-date "yyyy-MM-dd" "1961-01-01")(ti/local-date  "yyyy-MM-dd" "3000-12-31"))
  )


(def ^{:private true} policiesMap (atom {}))


(defn initdb []
  (jd/execute! h2db ["create table rules (name varchar(80), content json)"])
  (jd/execute! h2db ["create table delegations (id bigint auto_increment primary key, person varchar(80), delegate_expr varchar(80), ressource varchar(100) )"])
  (jd/execute! h2db ["create table delegresource (delegation_id bigint, content json, foreign key (delegation_id) references delegations(id) )"])
  (jd/execute! h2db ["create table comp_delegations (delegation_id bigint, delegate varchar(80), resource_class varchar(80), resource_id varchar(80), operation varchar(80), foreign key (delegation_id) references delegations(id) )"])
  )

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


(def subjectFillers (atom {"Person" {:type "internalFiller" :method "fillPerson"}
                           "Application" {}}))

(def resourceFillers (atom {"Note" {:type "urlFiller" :url "http://localhost:8080/NoteFiller" }}))

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
  (some #(if (and (= class (:class %))
                  (or (nil? (:attributes %))(some (fn [v] (= v (keyword attribute))) (:attributes %))))
          %) @pips)
  )

(defn insert-policy [resourceClass pol]
  (swap! policiesMap assoc resourceClass pol)

  )

;;(defn submit-policy [^String resourceClass ^String policy]
;;  (let [js (slurp "resources/policySchema.json")
;;        jsvalidate (validjs/validator js)]
;;    (if (jsvalidate policy)
;;      (insert-policy resourceClass (json/read-str policy :key-fn keyword))
;;  )))

(defn submit-policy [^String resourceClass ^String policy]
  (if (validjs/validate policySchema policy)
      (insert-policy resourceClass (json/read-str policy :key-fn keyword))
      ))



(defn delete-policy [^String resourceClass]
  (swap! policiesMap dissoc resourceClass)
  )

(defn initf [rulesf]
  (let [rules-map (utl/load-edn rulesf)]
    (swap! policiesMap
           (fn [a] (reduce (fn [hm ke]                      ;; treat a resourceClass rules
                             (let [rls (get rules-map ke)]
                               (assoc hm ke {:global {:rules (map #(rule2 %) (:rules (:global rls))) ;; TODO :global and :strategy are hard coded
                                                      :strategy :almost_one_allow_no_deny}})))
                           {}
                           (keys rules-map))))
    ))



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

(defn getGlobalPolicy [resourceClass]
  (:global (get @policiesMap resourceClass))
  )

(defn getGlobalPolicies []
  (mapcat #(:rules (:global %)) @policiesMap)
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