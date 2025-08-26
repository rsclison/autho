(ns autho.pip
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [com.brunobonacci.mulog :as u]
            [autho.kafka-pip :as kafka-pip]))

;; Multimethod for PIP calls, dispatches on the :type from the :pip map
(defmulti callPip (fn [decl _ _] (get-in decl [:pip :type])))

;; Default implementation for unknown PIP types
(defmethod callPip :default [decl att obj]
  (u/log ::unknown-pip-type :decl decl)
  nil)

;; REST PIP implementation (from urlPip)
(defmethod callPip :rest [decl att obj]
  (let [pip-info (:pip decl)]
    (if (= (:verb pip-info) "post") ; Note: the original urlPip only handles POST
      (try
        (let [resp (client/post (:url pip-info) {:form-params obj :content-type :json})
              jsresp (json/read-str (:body resp) :key-fn keyword)]
          jsresp)
        (catch Exception e
          (u/log ::pip-exception :exception e :pip-info pip-info)
          nil))
      nil)))

(defmethod callPip :kafka-pip [decl att obj]
  (let [pip-info (:pip decl)
        class-name (:class pip-info)
        id-key (or (:id-key pip-info) :id)
        obj-id (get obj id-key)]
    (if (and class-name obj-id)
      (kafka-pip/query-pip class-name (str obj-id))
      nil)))

;; Java PIP implementation
(defmethod callPip :java [decl att obj]
  (let [instance (get-in decl [:pip :instance])]
    (if instance
      (.resolveAttribute instance att obj)
      nil)))

;; Internal PIP implementation
(defmethod callPip :internal [decl att obj]
  (let [method-name (get-in decl [:pip :method])]
    (if method-name
      (try
        ;; Assuming the internal methods are in attfun for now, as per original logic
        ((ns-resolve 'autho.attfun (symbol method-name)) obj)
        (catch Exception e
          (u/log ::pip-exception :exception e :pip-info (:pip decl))
          nil))
      nil)))

;; CSV PIP implementation
(defmethod callPip :csv [decl att obj]
  (let [pip-info (:pip decl)
        file-path (:path pip-info)
        id-key (or (:id-key pip-info) :id) ; More idiomatic default
        obj-id (get obj id-key)]
    (if (and file-path obj-id)
      (try
        (with-open [reader (io/reader file-path)]
          (let [csv-data (csv/read-csv reader)]
            (if-let [headers (first csv-data)]
              (let [headers (map keyword headers)
                    id-col-name (name id-key)
                    rows (rest csv-data)
                    id-index (.indexOf (first csv-data) id-col-name)]
                (if (>= id-index 0)
                  (if-let [found-row (first (filter #(= (str (get % id-index)) (str obj-id)) rows))]
                    (zipmap headers found-row)
                    nil)
                  nil))
              nil)))
        (catch java.io.FileNotFoundException e
          (u/log ::pip-exception :exception e :pip-info pip-info)
          nil))
      nil)))