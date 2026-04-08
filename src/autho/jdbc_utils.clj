(ns autho.jdbc-utils
  (:import (java.sql Clob)))

(defn clob->string [value]
  (cond
    (nil? value) nil
    (string? value) value
    (instance? Clob value) (.getSubString ^Clob value 1 (int (.length ^Clob value)))
    :else (str value)))
