(ns autho.pip
  (:require [clj-http.client :as client]
            [com.brunobonacci.mulog :as u])
  )

(defmulti callPip :type 
  )

(defmethod callPip :rest 
  [pipdef ctxt att]
  (try (client/get (:url pipdef) {:accept :json :query-params {"id" (str(:id ctxt)) "att" att}})
       (catch Exception e
         (u/log ::pip-exception :exception e)
         {:error e}
         ))
  )

(defmethod callPip :internal
  [pipdef ctxt att])



(defmethod callPip :file
  [pipdef ctxt att])

;; for testing reason


(defn junkJsonPip [ctxt att]
  {:class :person :name "John" :surname "Doe"}
  )