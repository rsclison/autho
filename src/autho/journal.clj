(ns autho.journal
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.journal"))

(defn logRequest [request response]
  (.info logger "Authorization request: {}" (pr-str request))
  (.info logger "Authorization response: {}" (pr-str response)))

(defn logClient [client]
  (.info logger "Client initialized: {}" (pr-str client)))