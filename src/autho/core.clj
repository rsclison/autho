(ns autho.core
  (:require [autho.handler :as handler]
            [autho.pdp :as pdp])
  (:import (org.slf4j LoggerFactory))
  (:gen-class))

(defonce logger (LoggerFactory/getLogger "autho.core"))

(defn -main [& args]
  (pdp/init)
  (let [mode (or (pdp/getProperty :autho.mode) "rest")]
    (if (= mode "rest")
      (do
        (handler/init)
        @(promise))
      (.info logger "autho started in embedded mode."))))
