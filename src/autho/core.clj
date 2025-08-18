(ns autho.core
  (:require [com.brunobonacci.mulog :as u]
            [autho.handler :as handler])
  (:gen-class))


(u/start-publisher! {:type :console})

(defn -main [& args]
  (handler/init))
