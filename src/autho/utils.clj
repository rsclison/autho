(ns autho.utils
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import (org.slf4j LoggerFactory)))

(defonce logger (LoggerFactory/getLogger "autho.utils"))

(defn load-edn
  "Load edn from an io/reader source (filename or io/resource)."
  [source]
  (try
    (with-open [r (io/reader source)]
      (edn/read (java.io.PushbackReader. r)))

    (catch java.io.IOException e
      (.error logger "Couldn't open EDN source '{}': {}" source (.getMessage e))
      (throw e))
    (catch RuntimeException e
      (.error logger "Error parsing EDN file '{}': {}" source (.getMessage e))
      (throw e))))