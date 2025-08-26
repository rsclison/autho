(ns autho.core
  (:require [autho.handler :as handler]
            [autho.pdp :as pdp])
  (:gen-class))

(defn -main [& args]
  (pdp/init)
  (let [mode (or (pdp/getProperty :autho.mode) "rest")]
    (if (= mode "rest")
      (do
        (handler/init)
        @(promise))
      (println "autho started in embedded mode."))))
