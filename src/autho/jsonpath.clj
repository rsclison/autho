#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns autho.jsonpath
  [:require [hyauth.parser :as parser]
   [hyauth.match :as m]
   [hyauth.walker :as walker]])

(defn query [path object]
  (walker/walk (parser/parse-path path) {:root (m/root object)}))

(defn at-path [path object]
  (walker/map# :value (query path object)))