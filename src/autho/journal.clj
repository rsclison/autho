(ns autho.journal
  (:require [com.brunobonacci.mulog :as u]))

(defn logRequest [request response]
  (u/log ::http-request
         :method      (name (:request-method request))
         :uri         (:uri request)
         :status      (:status response)
         :remote-addr (:remote-addr request)
         :client-id   (get-in request [:identity :client-id])))

(defn logClient [client]
  (u/log ::client-initialized :client client))