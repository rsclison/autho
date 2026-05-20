(ns autho.policy-cli
  "Command-line utilities for policy lifecycle automation."
  (:require [autho.policy-yaml :as policy-yaml]
            [autho.prp :as prp]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:gen-class))

(def ^:private usage
  (str "Usage:\n"
       "  lein policy:validate --resource-class <class> --file <policy.json|policy.yaml> [--format text|json]\n\n"
       "Options:\n"
       "  -r, --resource-class  Resource class validated against the candidate policy.\n"
       "  -f, --file            Candidate policy file in JSON, YAML or YML format.\n"
       "      --format          Output format. Defaults to text.\n"
       "  -h, --help            Show this help.\n"))

(defn- yaml-file?
  [path]
  (let [lower (str/lower-case (str path))]
    (or (str/ends-with? lower ".yaml")
        (str/ends-with? lower ".yml"))))

(defn- parse-policy-file
  [path]
  (let [content (slurp (io/file path))
        policy-map (if (yaml-file? path)
                     (policy-yaml/parse-yaml content)
                     (json/read-str content))]
    {:json (json/write-str policy-map)
     :map policy-map}))

(defn parse-args
  [args]
  (loop [remaining args
         parsed {:command nil
                 :format "text"}]
    (if (empty? remaining)
      parsed
      (let [[arg value & tail] remaining]
        (cond
          (= arg "validate")
          (recur (rest remaining) (assoc parsed :command "validate"))

          (contains? #{"-h" "--help"} arg)
          (recur (rest remaining) (assoc parsed :help true))

          (contains? #{"-r" "--resource-class"} arg)
          (recur tail (assoc parsed :resource-class value))

          (contains? #{"-f" "--file"} arg)
          (recur tail (assoc parsed :file value))

          (= "--format" arg)
          (recur tail (assoc parsed :format value))

          :else
          (recur (rest remaining)
                 (update parsed :unknown (fnil conj []) arg)))))))

(defn- usage-result
  [exit-code message]
  {:exit-code exit-code
   :stdout (if message
             (str message "\n\n" usage)
             usage)})

(defn- validation-error-result
  [format resource-class exception]
  (let [data (ex-data exception)
        issues (or (:issues data)
                   (get-in data [:analysis :errors])
                   [])
        payload {:valid false
                 :resourceClass resource-class
                 :error {:code (or (:error-code data) "POLICY_VALIDATION_FAILED")
                         :message (.getMessage exception)
                         :issues issues}}]
    {:exit-code 1
     :stdout (if (= "json" format)
               (json/write-str payload)
               (str "Policy validation failed for " resource-class "\n"
                    (.getMessage exception) "\n"))}))

(defn- unexpected-error-result
  [format exception]
  (let [payload {:valid false
                 :error {:code "POLICY_VALIDATE_CLI_ERROR"
                         :message (.getMessage exception)}}]
    {:exit-code 2
     :stdout (if (= "json" format)
               (json/write-str payload)
               (str "Policy validation could not run: " (.getMessage exception) "\n"))}))

(defn validate-file
  [{:keys [resource-class file format]}]
  (try
    (let [{policy-json :json policy-map :map} (parse-policy-file file)
          effective-resource-class (or resource-class
                                       (get policy-map "resourceClass")
                                       (get policy-map :resourceClass))]
      (if (str/blank? effective-resource-class)
        (usage-result 2 "Missing resource class. Provide --resource-class or resourceClass in the policy file.")
        (try
          (let [analysis (prp/validate-policy-submission effective-resource-class policy-json)
                payload {:valid true
                         :resourceClass effective-resource-class
                         :validation (dissoc analysis :policy)}]
            {:exit-code 0
             :stdout (if (= "json" format)
                       (json/write-str payload)
                       (str "Policy validation passed for " effective-resource-class "\n"
                            "Warnings: " (count (:warnings analysis)) "\n"
                            "Policy tests: " (get-in analysis [:tests :passed]) "/"
                            (get-in analysis [:tests :count]) " passed\n"))})
          (catch clojure.lang.ExceptionInfo e
            (validation-error-result format effective-resource-class e)))))
    (catch clojure.lang.ExceptionInfo e
      (validation-error-result format resource-class e))
    (catch Exception e
      (unexpected-error-result format e))))

(defn run
  [args]
  (let [{:keys [command help unknown file format] :as parsed} (parse-args args)]
    (cond
      help
      (usage-result 0 nil)

      (seq unknown)
      (usage-result 2 (str "Unknown argument(s): " (str/join ", " unknown)))

      (not= command "validate")
      (usage-result 2 "Missing command: validate")

      (str/blank? file)
      (usage-result 2 "Missing policy file. Provide --file.")

      (not (contains? #{"text" "json"} format))
      (usage-result 2 "Unsupported format. Use text or json.")

      :else
      (validate-file parsed))))

(defn -main
  [& args]
  (let [{:keys [exit-code stdout]} (run args)]
    (print stdout)
    (flush)
    (shutdown-agents)
    (System/exit exit-code)))
