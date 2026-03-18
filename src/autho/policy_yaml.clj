(ns autho.policy-yaml
  "Policy-as-Code: load authorization policies from YAML files.

   YAML format maps directly to the existing JSON policy schema.
   Keys are camelCase to match the schema (resourceClass, startDate, etc.).

   Example YAML:
     resourceClass: Document
     strategy: almost_one_allow_no_deny
     rules:
       - name: admin-read
         priority: 10
         operation: read
         effect: allow
         startDate: 'inf'
         endDate: 'inf'
         resourceCond: ['Document', '?doc']
         subjectCond: ['Person', '?subj', ['=', 'role', 'admin']]

   The resourceCond/subjectCond arrays mirror the EDN vector format used
   internally: [Type ?var clause1 clause2 ...].
   Conditions are nested arrays that JSON-serialise transparently."
  (:require [clj-yaml.core :as yaml]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [autho.prp :as prp]
            [com.brunobonacci.mulog :as u])
  (:import (java.nio.file FileSystems StandardWatchEventKinds Path Paths)
           (org.slf4j LoggerFactory)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.policy-yaml"))

;; ---------------------------------------------------------------------------
;; Parsing & conversion
;; ---------------------------------------------------------------------------

(defn parse-yaml
  "Parse a YAML string into a Clojure map. Throws on malformed YAML."
  [^String yaml-str]
  (yaml/parse-string yaml-str :keywords false))

(defn- policy-map->json-str
  "Converts a parsed YAML map into the JSON string expected by prp/submit-policy.
   String keys are preserved as-is (the schema uses camelCase)."
  [policy-map]
  (json/write-str policy-map))

(defn import-yaml-policy!
  "Parses a YAML string and imports the policy via prp/submit-policy.
   Returns {:ok true :resourceClass rc} or {:ok false :error msg}."
  [^String yaml-str]
  (try
    (let [policy-map  (parse-yaml yaml-str)
          resource-class (get policy-map "resourceClass")]
      (if-not resource-class
        {:ok false :error "Missing 'resourceClass' field in YAML policy"}
        (let [json-str (policy-map->json-str policy-map)]
          (prp/submit-policy resource-class json-str)
          (u/log ::policy-imported :resource-class resource-class)
          (.info logger "Imported YAML policy for class: {}" resource-class)
          {:ok true :resourceClass resource-class})))
    (catch Exception e
      (.error logger "Failed to import YAML policy: {}" (.getMessage e) e)
      {:ok false :error (.getMessage e)})))

(defn import-yaml-file!
  "Reads a YAML file from disk and imports it.
   Returns the result of import-yaml-policy!."
  [^java.io.File file]
  (try
    (import-yaml-policy! (slurp file))
    (catch Exception e
      {:ok false :error (str "Cannot read file " (.getName file) ": " (.getMessage e))})))

;; ---------------------------------------------------------------------------
;; Directory watcher
;; ---------------------------------------------------------------------------

(defonce ^:private watcher-running (atom false))

(defn- load-all-yaml-in-dir!
  "Import all .yaml / .yml files found in the given directory."
  [^String dir]
  (let [files (filter #(let [n (.getName ^java.io.File %)]
                         (or (str/ends-with? n ".yaml")
                             (str/ends-with? n ".yml")))
                      (.listFiles (io/file dir)))]
    (doseq [f files]
      (let [result (import-yaml-file! f)]
        (if (:ok result)
          (.info logger "Loaded policy from {}: {}" (.getName ^java.io.File f) result)
          (.warn logger "Failed to load {}: {}" (.getName ^java.io.File f) (:error result)))))))

(defn start-directory-watcher!
  "Watches the given directory for .yaml/.yml changes and reloads policies.
   Initial load of all existing files is performed immediately.
   Non-blocking: runs in a background future.
   Safe to call multiple times — only one watcher will run."
  [^String dir]
  (when (compare-and-set! watcher-running false true)
    (.info logger "Starting YAML policy watcher on directory: {}" dir)
    ;; Load existing files immediately
    (load-all-yaml-in-dir! dir)
    (future
      (try
        (let [watch-service (.newWatchService (FileSystems/getDefault))
              path          (Paths/get dir (into-array String []))]
          (.register path watch-service
                     (into-array [StandardWatchEventKinds/ENTRY_CREATE
                                  StandardWatchEventKinds/ENTRY_MODIFY]))
          (.info logger "YAML policy watcher active on {}" dir)
          (loop []
            (when @watcher-running
              (let [watch-key (.take watch-service)]
                (Thread/sleep 200) ; debounce: wait 200ms for write to complete
                (doseq [event (.pollEvents watch-key)]
                  (let [file-name (str (.context event))]
                    (when (or (str/ends-with? file-name ".yaml")
                              (str/ends-with? file-name ".yml"))
                      (.info logger "Policy file changed: {}" file-name)
                      (import-yaml-file! (io/file dir file-name)))))
                (.reset watch-key)
                (recur)))))
        (catch InterruptedException _
          (.info logger "YAML policy watcher stopped"))
        (catch Exception e
          (.error logger "YAML policy watcher error: {}" (.getMessage e) e)
          (reset! watcher-running false))))))

(defn stop-watcher! []
  (reset! watcher-running false))
