(ns autho.pdp
  (:use [clojure.test :exclude [report]])
  (:require [autho.prp :as prp]
            [autho.jsonrule :as rule]
            [autho.cache :as cache]
            [autho.delegation :as deleg]
            [clojure.java.io :as io]
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

;;(fs-api/connect)



(def properties (atom {}))


;;(def secret "mysecret")

;;(defn unsign-token [token]
;;  (jwt/unsign token secret))


(defn- getProperty [prop]
  (get @properties prop)
  )


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

(defn evalRequest [^Map request]
  ;;(info "EvalRequest")
  (if-not (:resource request)
    {:error "No resource specified"}
    (if-not (:subject request)
      {:error "No subject specified"}
      (let [
            globalPolicy (prp/getGlobalPolicy (:class (:resource request)))
            policy (prp/getPolicy (:class (:resource request)) (:application (:context request)))
            ;;evals  (doall(map (fn [rule] [rule(rule/evaluateRule rule request)]) (:rules policy)))
            ;; call the fillers
            augreq (passThroughCache request)                                ;; //TODO reactivate fillers  (callFillers request)
            evalglob (reduce (fn [res rule] (if (:value (rule/evaluateRule rule augreq))
                                              (conj res rule)
                                              res
                                              ))
                             [] (:rules globalPolicy))
            resolve (resolve-conflict globalPolicy evalglob)
            ]

        (if resolve
          {:result resolve :rules evalglob}
          ;; try delegations
          (let [deleg (deleg/findDelegation (:subject request))
                one (some #(let [ev (evalRequest (assoc request :subject (:delegate %)))]
                             (when ev ev)
                             )
                          deleg
                          )
                ]
            (if one one
                    {:result false :rules evalglob}
                    )
            )
          )
;;          (let [evala (reduce (fn [res rule] (if (:value (rule/evaluateRule rule augreq))
;;                                               (do (println "found " (:name rule)) (conj res rule))
;;                                               res
;;                                               ))
;;                              [] (:rules policy))]
;;            (if (empty? evala)
;;              false
;;              (resolve-conflict policy evala))
            )
          )
        ))

(defn isAuthorized [request]
  ;; {:resource {:class cc} :subject yy}
  (if-not (:resource request)
    {:error "No resource specified"}
    (if-not (:subject request)
      {:error "No subject specified"}
      (let [
            globalPolicy (prp/getGlobalPolicy (:class (:resource request)))
            ;; call the fillers
            augreq (passThroughCache request)               ;; (callFillers request)    //TODO reactivate fillers
            evalglob (do (reduce (fn [res rule] (if (:value (rule/evaluateRule rule augreq))
                                                  (conj res rule)
                                                  res
                                                  ))
                                 [] (:rules globalPolicy)))
            ]
        (if-not globalPolicy {:error "No global policy applicable"}
                             {:results (map #(:name %1) evalglob)}))
      )))

;; V2.0 retreive charasteristics of persons allowed to do an operation on a resource*
;; on considère pour simplifier que la condition est un ET de clauses
;; on simplifie en retournant la 1ere règle en allow sans vérifier les deny
(defn whoAuthorized [^Map request]
;; verify we have a resource
  (if (:resource request)
    (let [candrules
          (filter #(= "allow" (:effect %)) (:rules (prp/getGlobalPolicy (:class(:resource request)))))
          evrules
           (filter #(rule/evalRuleWithResource % request) candrules)
          ]

    (map (fn [rule] {:resourceClass (:resourceClass rule)
                     :subjectCond (rest (:subjectCond rule))
                     :operation (:operation rule)
                     })
         evrules)
      )
    )
  )

(defn whichAuthorized [^Map request]
  ;; verify we have a subject
  (if (:subject request)
    (let [allowrules
          (filter #(= "allow" (:effect %)) (:rules (prp/getGlobalPolicy (:class(:resource request)))))
          denyrules
          (filter #(= "deny" (:effect %)) (:rules (prp/getGlobalPolicy (:class(:resource request)))))
          evrules1
          (keep (fn [rule]
                       (rule/evalRuleWithSubject rule request)) allowrules)
          evrules2
          (keep (fn [rule]
                  (rule/evalRuleWithSubject rule request)) denyrules)
          ]
      {:allow (map (fn [rule] {:resourceClass (:resourceClass rule)
                               :resourceCond  (rest (:resourceCond rule))
                               :operation     (:operation rule)
                               })
                   evrules1)
       :deny  (map (fn [rule] {:resourceClass (:resourceClass rule)
                               :resourceCond  (rest (:resourceCond rule))
                               :operation     (:operation rule)
                               })
                   evrules2)
       }
      )
    )
  )

(defn explain [^Map request]  ;; //TODO

  )




(defn- load-props
  [file-name]
  (with-open [^java.io.Reader reader (clojure.java.io/reader file-name)]
    (let [props (java.util.Properties.)]
      (.load props reader)
      (into {} (for [[k v] props] [(keyword k) (read-string v)])))))


(defn init []
  (swap! properties (fn [oldprop] (load-props "resources/pdp-prop.properties")))
  (let [ldapprop (filter (fn [propunit] (str/starts-with? (name propunit) "ldap")) @properties)]
    (if (getProperty :ldap.server) (ldap/init {:host     (getProperty :ldap.server)
                                               :bind-dn  (getProperty :ldap.connectstring)
                                               :password (getProperty :ldap.password)
                                               }))
    )
  ;; init the prp
  (prp/initf (getProperty :rules.repository))

  ;; init delegations
  (prp/initDelegations)
  ;; init persons
  (person/loadPersons {:type (keyword (getProperty :person.source)) :props @properties})

  (deleg/batchCompile)


  )

