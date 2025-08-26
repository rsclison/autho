(ns autho.attfun
  ;;(:use clojure.instant)
;;  (:import (org.cocktail Pip))
  (:require [clojure.string :as str])
  (:require [clojure.data.json :as json])
  (:require [clojure.core.cache :as cache])
  (:require [clj-http [client]])
  (:require [java-time :as ti])
 ;; (:require [clj-time.core :as t])
  (:require
    ;;[clj-time.format :as f]
            [autho.prp :as prp]
            [autho.pip :as pip])

  )




;;(defn findAndCallPipCache [attName obj]
;;  (let [compkey (keyword (str (:id obj) "_" attName))]
;;    (cache/lookup (swap! cache-store cache/through-cache compkey
;;                         (fn [k]
;;                           (let [pipdecl (prp/findPip attName)] ;; TODO the PIP is attached only to attribute not to class/attribute
;;                             (if (nil? pipdecl)
;;                               nil
;;                               (try (apply (ns-resolve (symbol "hyauth.attfun") (symbol (:type pipdecl))) [pipdecl attName obj])
;;                                    (catch Exception e nil))))))
;;                  compkey)))


(defn findAndCallPip [attName obj]
  (let [pipdecl (prp/findPip (:class obj) attName)]
    (if (nil? pipdecl)
      nil
      (try (pip/callPip pipdecl attName obj)
           (catch Exception e
             (println (str "Error calling pip for " attName " on " obj))
             nil)))))

#_(defn att [attribute obj]
  ;; the object is a symbol map not a json string
  (println "IN ATT " attribute " " obj)
  (let [attseq (str/split (name attribute) #"\.")
        ;; try to evaluate from map
        evalfromjson (reduce (fn [mp f]
                               (println "Reducing " mp "-" f)
                               (get mp (keyword f)))
                               obj attseq)]

    (println "evalfromjson = " evalfromjson)
    (if (nil? evalfromjson)
      (findAndCallPip attribute obj)
      evalfromjson
      )
    )
  )

;; TODO should be modified to return a map with the value and the augmented object
(defn att [attribute-string obj]
  (let [attseq (str/split (name attribute-string) #"\.")]
    (try
      (reduce (fn [ob attu]
                (if (nil? ob) (do(println "XXXXXXX Exception XXXXXXX")(throw (Exception. "bad value"))))
                (let [res (get ob (keyword attu))]
                  (if res
                    ;; the attribute is in the record
                    res
                    (findAndCallPip attu ob))))
              obj attseq)
      (catch Exception e nil))
    ))


(defn role [obj]
  (println "Calling ldapRole")
  "Professeur"
  )


(defn fillPersonne [subj]
  (assoc subj :mood "excellent")
  )

(defn internalFiller [filler obj]
  (apply (ns-resolve (symbol "autho.attfun") (symbol(:method filler))) [obj])
  )

;; (defmacro json-read-extd [st]
;;  `(let [res# (try (json/read-str ~st :key-fn keyword)
;;                  (catch NumberFormatException e# (try (#'clojure.instant/read-instant-date ~st) (catch Exception e# nil))))]
;;     res#
;;  ))

;; FUNCTIONS

(defn et [& args]
  (println "ET " args)
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
  (clojure.core/> (read-string arg1) (read-string arg2))
  )

(defn >= [arg1 arg2]
  (let [rarg1 (read-string arg1) rarg2 (read-string arg2)]
    (or (clojure.core/> rarg1 rarg2)(clojure.core/= rarg1 rarg2))
  ))

(defn < [arg1 arg2]
  (clojure.core/< (read-string arg1) (read-string arg2))
  )

(defn <= [arg1 arg2]
  (let [rarg1 (read-string arg1) rarg2 (read-string arg2)]
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