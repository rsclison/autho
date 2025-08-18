(ns autho.core
  (:require [com.brunobonacci.mulog :as u]
            [autho.handler :as handler]
            [autho.pdp :as pdp])
  (:gen-class))


(u/start-publisher! {:type :console})

(defn -main [& args]
  (pdp/init)
  (let [mode (or (pdp/getProperty :autho.mode) "rest")]
    (if (= mode "rest")
      (handler/init)
      (println "autho started in embedded mode."))))
