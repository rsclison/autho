(ns autho.attfun
  ;;(:use clojure.instant)
;;  (:import (org.cocktail Pip))
  (:require [clojure.string :as str])
  (:require [clojure.data.json :as json])
  (:require [clojure.core.cache :as cache])
  (:require [clojure.edn :as edn])
  (:require [clj-http [client]])
  (:require [java-time :as ti])
 ;; (:require [clj-time.core :as t])
  (:require
    ;;[clj-time.format :as f]
            [autho.prp :as prp]
            [autho.pip :as pip])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.attfun"))

(defn findAndCallPip [attName obj]
  (let [pipdecl (prp/findPip (:class obj) attName)]
    (if (nil? pipdecl)
      nil
      (try (pip/callPip pipdecl attName obj)
           (catch Exception e
             (.error logger "Error calling pip for {} on {}" attName obj e)
             nil)))))

;; TODO should be modified to return a map with the value and the augmented object
(defn att [attribute-string obj]
  (let [attseq (str/split (name attribute-string) #"\.")]
    (try
      (reduce (fn [ob attu]
                (if (nil? ob)
                  (do
                    (.error logger "Bad value encountered while processing attribute: {}" attu)
                    (throw (Exception. "bad value"))))
                (let [res (get ob (keyword attu))]
                  (if res
                    ;; the attribute is in the record
                    res
                    (findAndCallPip attu ob))))
              obj attseq)
      (catch Exception e nil))
    ))


(defn role [obj]
  (.debug logger "Calling ldapRole for object: {}" obj)
  "Professeur"
  )


(defn fillPersonne [subj]
  (assoc subj :mood "excellent")
  )

(defn internalFiller [filler obj]
  (apply (ns-resolve (symbol "autho.attfun") (symbol(:method filler))) [obj]))

;; FUNCTIONS

(defn et [& args]
  (.debug logger "ET evaluating args: {}" args)
  (every? identity args)
  )

(defn = [& args]
  (apply #'clojure.core/= args)
  )

(defn diff [& args]
  (not(apply #'clojure.core/= args))
  )

(defn non [arg]
  (not arg)
  )

(defn in [arg1 list]
  (contains? list arg1)
  )

(defn notin [arg1 list]
  (not(in arg1 list))
  )

(defn > [arg1 arg2]
  (clojure.core/> (edn/read-string arg1) (edn/read-string arg2))
  )

(defn >= [arg1 arg2]
  (let [rarg1 (edn/read-string arg1) rarg2 (edn/read-string arg2)]
    (or (clojure.core/> rarg1 rarg2)(clojure.core/= rarg1 rarg2))
  ))

(defn < [arg1 arg2]
  (clojure.core/< (edn/read-string arg1) (edn/read-string arg2))
  )

(defn <= [arg1 arg2]
  (let [rarg1 (edn/read-string arg1) rarg2 (edn/read-string arg2)]
    (or (clojure.core/< rarg1 rarg2)(clojure.core/= rarg1 rarg2)))
  )

(defn date> [d1 d2]
  ;; args are strings
  (let [date1 (ti/local-date "yyyy-MM-dd" d1) date2 (ti/local-date "yyyy-MM-dd" d2)]
    (ti/after? date1 date2)
    ))

(defn inverseOp [op]
  (case op
       < '>=
       > '<=
       diff '=
       = 'diff
       nil)
  )