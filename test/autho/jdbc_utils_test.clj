(ns autho.jdbc-utils-test
  (:require [clojure.test :refer :all]
            [autho.jdbc-utils :as jdbc-utils])
  (:import (javax.sql.rowset.serial SerialClob)))

(deftest clob->string-supports-java-sql-clob-test
  (let [clob (SerialClob. (.toCharArray "{\"strategy\":\"deny-unless-permit\"}"))]
    (is (= "{\"strategy\":\"deny-unless-permit\"}"
           (jdbc-utils/clob->string clob)))))

(deftest clob->string-keeps-plain-strings-test
  (is (= "plain-json" (jdbc-utils/clob->string "plain-json"))))

(deftest clob->string-allows-nil-test
  (is (nil? (jdbc-utils/clob->string nil))))
