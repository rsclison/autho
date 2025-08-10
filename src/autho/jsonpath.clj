#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns autho.jsonpath
  (:require [autho.parser :as parser]
            [autho.match :as m]
            [autho.walker :as walker]))

(defn query [path object]
  (walker/walk (parser/parse-path path) {:root (m/root object)}))

(defn at-path [path object]
  (walker/map# :value (query path object)))