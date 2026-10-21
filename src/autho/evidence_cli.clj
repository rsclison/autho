(ns autho.evidence-cli
  "Offline verification of evidence bundles with the Ed25519 public key.
   Needs neither the server nor AUDIT_HMAC_SECRET."
  (:require [autho.evidence :as evidence]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:gen-class))

(def ^:private usage
  (str "Usage:\n"
       "  lein evidence:verify <bundle.json> <public-key>\n\n"
       "  bundle.json  Bundle exported by GET /v1/evidence (with or without the {\"data\": ...} envelope).\n"
       "  public-key   File or literal value: base64 DER or PEM (GET /v1/evidence/public-key).\n"))

(defn- read-public-key
  [arg]
  (let [f (io/file arg)]
    (evidence/parse-public-key (if (.isFile f) (slurp f) arg))))

(defn run
  "Returns {:exit-code n :stdout s}."
  [args]
  (if (not= 2 (count args))
    {:exit-code 2 :stdout usage}
    (try
      (let [[bundle-path key-arg] args
            parsed (json/read-str (slurp bundle-path) :key-fn keyword)
            bundle (if (map? (:data parsed)) (:data parsed) parsed)
            result (evidence/verify-public-signature bundle (read-public-key key-arg))]
        {:exit-code (if (:valid result) 0 1)
         :stdout (str (if (:valid result) "VALID" "INVALID")
                      " keyId=" (:keyId result)
                      " payloadSha256=" (:payloadSha256 result)
                      (when (seq (:errors result))
                        (str " errors=" (str/join "," (:errors result))))
                      "\n")})
      (catch Exception e
        {:exit-code 2 :stdout (str "Error: " (.getMessage e) "\n\n" usage)}))))

(defn -main
  [& args]
  (let [{:keys [exit-code stdout]} (run args)]
    (print stdout)
    (flush)
    (System/exit exit-code)))
