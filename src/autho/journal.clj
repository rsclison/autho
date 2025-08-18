(ns autho.journal
  (:require
    [taoensso.timbre :as timbre
     :refer [log  trace  debug  info  warn  error  fatal report
             logf tracef debugf infof warnf errorf fatalf reportf
             spy get-env]]
    )
  )


;; journal of authorization requests

(defn logRequest [request response]
  (info "Authorization request: " request)
  (info "Authorization response: " response)
  )

(defn logClient [client])