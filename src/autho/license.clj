(ns autho.license
  "Ed25519-based licence verification for Autho.

  Token format: <payload-base64url>.<signature-base64url>
  The payload is a JSON object with the licence claims.
  The signature is produced by the private key held by Autho's licence server.
  Only the public key is embedded in the JAR (resources/license-public.pem)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security KeyFactory Signature)
           (java.security.spec X509EncodedKeySpec)
           (java.time LocalDate)
           (java.util Base64)
           (org.slf4j LoggerFactory)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.license"))

(def ^:private public-key-resource "license-public.pem")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- pad-base64
  "Adds = padding stripped by URL-safe Base64 encoders."
  [s]
  (let [rem (mod (count s) 4)]
    (if (zero? rem) s (str s (str/join (repeat (- 4 rem) "="))))))

;; ---------------------------------------------------------------------------
;; Key loading
;; ---------------------------------------------------------------------------

(defn- load-public-key
  "Loads the Ed25519 public key from resources/license-public.pem."
  []
  (let [resource (io/resource public-key-resource)]
    (when-not resource
      (throw (ex-info "license-public.pem introuvable dans les resources" {})))
    (let [pem     (slurp resource)
          b64     (-> pem
                      (str/replace "-----BEGIN PUBLIC KEY-----" "")
                      (str/replace "-----END PUBLIC KEY-----" "")
                      (str/replace #"\s" ""))
          decoded (.decode (Base64/getDecoder) b64)
          spec    (X509EncodedKeySpec. decoded)
          kf      (KeyFactory/getInstance "Ed25519")]
      (.generatePublic kf spec))))

;; ---------------------------------------------------------------------------
;; Signature verification
;; ---------------------------------------------------------------------------

(defn- verify-signature!
  "Throws if the Ed25519 signature does not match the payload."
  [payload-b64 sig-b64 pub-key]
  (let [sig       (Signature/getInstance "Ed25519")
        payload   (.getBytes ^String payload-b64 "UTF-8")
        sig-bytes (try
                    (.decode (Base64/getUrlDecoder) (pad-base64 sig-b64))
                    (catch IllegalArgumentException _
                      (throw (ex-info "Licence invalide : signature mal encodée" {}))))]
    (.initVerify sig pub-key)
    (.update sig payload)
    (when-not (.verify sig sig-bytes)
      (throw (ex-info "Licence invalide : signature incorrecte" {})))))

;; ---------------------------------------------------------------------------
;; Claims parsing and validation
;; ---------------------------------------------------------------------------

(defn- parse-payload [payload-b64]
  (try
    (-> (.decode (Base64/getUrlDecoder) (pad-base64 payload-b64))
        (String. "UTF-8")
        (json/read-str :key-fn keyword))
    (catch Exception e
      (throw (ex-info "Licence invalide : payload illisible" {:cause (.getMessage e)})))))

(defn- validate-expiry! [claims]
  (when-not (:expires_at claims)
    (throw (ex-info "Licence invalide : champ expires_at manquant" {})))
  (when (.isAfter (LocalDate/now) (LocalDate/parse (:expires_at claims)))
    (throw (ex-info "Licence expirée" {:expired (:expires_at claims)}))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn parse-and-verify
  "Parses and verifies a licence token string.
  Returns the claims map on success, throws ex-info on failure.

  Claims keys: :customer :tier :features :instances :decisions
               :issued_at :expires_at"
  [token]
  (let [parts (str/split token #"\." 2)]
    (when (< (count parts) 2)
      (throw (ex-info "Licence invalide : format <payload>.<signature> attendu" {})))
    (let [[payload-b64 sig-b64] parts
          pub-key (load-public-key)]
      (verify-signature! payload-b64 sig-b64 pub-key)
      (let [claims (parse-payload payload-b64)]
        (validate-expiry! claims)
        claims))))
