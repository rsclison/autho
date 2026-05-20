(ns autho.policy-cli-test
  (:require [autho.policy-cli :as cli]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(def valid-policy
  {"resourceClass" "Document"
   "strategy" "almost_one_allow_no_deny"
   "rules" [{"name" "allow-admin-read"
             "priority" 10
             "effect" "allow"
             "resourceClass" "Document"
             "operation" "read"
             "conditions" [["=" ["Person" "$s" "role"] "admin"]]}]
   "tests" [{"name" "admin can read"
             "subject" {"id" "alice" "class" "Person" "role" "admin"}
             "resource" {"id" "doc-1" "class" "Document"}
             "operation" "read"
             "expect" "allow"}]})

(defn- temp-policy-file
  [suffix content]
  (let [file (java.io.File/createTempFile "autho-policy-cli-" suffix)]
    (spit file content)
    (.deleteOnExit file)
    (.getAbsolutePath file)))

(deftest parse-args-accepts-policy-validate-options-test
  (is (= {:command "validate"
          :format "json"
          :resource-class "Document"
          :file "candidate.json"}
         (cli/parse-args ["validate"
                          "--resource-class" "Document"
                          "--file" "candidate.json"
                          "--format" "json"]))))

(deftest run-validates-json-policy-and-returns-json-test
  (let [path (temp-policy-file ".json" (json/write-str valid-policy))
        result (cli/run ["validate" "--file" path "--format" "json"])
        body (json/read-str (:stdout result) :key-fn keyword)]
    (is (= 0 (:exit-code result)))
    (is (= true (:valid body)))
    (is (= "Document" (:resourceClass body)))
    (is (= "prod" (:environment body)))
    (is (= "passed" (get-in body [:report :status])))
    (is (= 1 (get-in body [:validation :tests :passed])))))

(deftest run-validates-yaml-policy-test
  (let [path (temp-policy-file ".yaml"
                               (str "resourceClass: Document\n"
                                    "strategy: almost_one_allow_no_deny\n"
                                    "rules:\n"
                                    "  - name: allow-admin-read\n"
                                    "    priority: 10\n"
                                    "    effect: allow\n"
                                    "    resourceClass: Document\n"
                                    "    operation: read\n"
                                    "    conditions:\n"
                                    "      - ['=', ['Person', '$s', 'role'], 'admin']\n"
                                    "tests:\n"
                                    "  - name: admin can read\n"
                                    "    subject: {id: alice, class: Person, role: admin}\n"
                                    "    resource: {id: doc-1, class: Document}\n"
                                    "    operation: read\n"
                                    "    expect: allow\n"))
        result (cli/run ["validate" "--file" path])]
    (is (= 0 (:exit-code result)))
    (is (str/includes? (:stdout result) "Policy validation passed for Document"))))

(deftest run-returns-nonzero-for-failing-policy-test
  (let [policy (assoc valid-policy
                      "tests" [{"name" "wrong expectation"
                                "subject" {"id" "alice" "class" "Person" "role" "admin"}
                                "resource" {"id" "doc-1" "class" "Document"}
                                "operation" "read"
                                "expect" "deny"}])
        path (temp-policy-file ".json" (json/write-str policy))
        result (cli/run ["validate" "--file" path "--format" "json"])
        body (json/read-str (:stdout result) :key-fn keyword)]
    (is (= 1 (:exit-code result)))
    (is (= false (:valid body)))
    (is (= "Document" (:resourceClass body)))
    (is (= "failed" (get-in body [:report :status])))
    (is (= "POLICY_TESTS_FAILED" (get-in body [:error :code])))))

(deftest run-requires-file-test
  (let [result (cli/run ["validate"])]
    (is (= 2 (:exit-code result)))
    (is (str/includes? (:stdout result) "Missing policy file"))))
