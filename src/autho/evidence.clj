(ns autho.evidence
  "Signed evidence bundle export and verification.
   Evidence bundles package audit and policy-governance context for export to
   operators, auditors, and compliance tooling."
  (:require [clojure.data.json :as json])
  (:import (java.security MessageDigest)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (java.time Instant)))

(def bundle-format "autho.evidence.bundle.v1")

(def ^:private bundle-secret nil)

(defn- configured-secret
  []
  (or bundle-secret
      (System/getenv "AUDIT_HMAC_SECRET")))

(defn- require-bundle-secret!
  []
  (let [secret (configured-secret)]
    (cond
      (nil? secret)
      (throw (ex-info "AUDIT_HMAC_SECRET environment variable must be set"
                      {:status 500
                       :error-code "EVIDENCE_BUNDLE_SECRET_MISSING"}))
      (< (count secret) 32)
      (throw (ex-info "AUDIT_HMAC_SECRET must be at least 32 characters"
                      {:status 500
                       :error-code "EVIDENCE_BUNDLE_SECRET_WEAK"
                       :minimum 32
                       :length (count secret)}))
      :else secret)))

(defn- bytes->hex
  [bytes]
  (apply str (map #(format "%02x" %) bytes)))

(defn- sha256
  [data]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes data "UTF-8"))]
    (bytes->hex digest)))

(defn- hmac-sha256
  [data secret]
  (let [mac (Mac/getInstance "HmacSHA256")
        key-spec (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")]
    (.init mac key-spec)
    (bytes->hex (.doFinal mac (.getBytes data "UTF-8")))))

(defn- constant-time-equals?
  [a b]
  (and (string? a)
       (string? b)
       (MessageDigest/isEqual (.getBytes ^String a "UTF-8")
                              (.getBytes ^String b "UTF-8"))))

(defn- canonical-value
  [value]
  (cond
    (map? value)
    (into (sorted-map)
          (map (fn [[k v]]
                 [(name k) (canonical-value v)])
               value))

    (sequential? value)
    (mapv canonical-value value)

    :else value))

(defn canonical-json
  [value]
  (json/write-str (canonical-value value)))

(defn payload-digest
  [payload]
  (sha256 (canonical-json payload)))

(defn sign-payload
  [payload]
  (hmac-sha256 (canonical-json payload) (require-bundle-secret!)))

(defn sign-bundle
  [bundle]
  (let [payload (assoc bundle :format bundle-format)
        digest (payload-digest payload)
        signature (sign-payload payload)]
    (assoc payload
           :integrity {:algorithm "HMAC-SHA256"
                       :canonicalization "json-sorted-keys-v1"
                       :payloadSha256 digest
                       :signature signature
                       :signedAt (str (Instant/now))})))

(defn verify-bundle
  [bundle]
  (let [payload (dissoc bundle :integrity)
        integrity (:integrity bundle)
        expected-digest (payload-digest payload)
        expected-signature (sign-payload payload)
        valid-digest? (constant-time-equals? expected-digest (:payloadSha256 integrity))
        valid-signature? (constant-time-equals? expected-signature (:signature integrity))
        valid-format? (= bundle-format (:format payload))]
    {:valid (boolean (and valid-format? valid-digest? valid-signature?))
     :format (:format payload)
     :resourceClass (:resourceClass payload)
     :algorithm (:algorithm integrity)
     :payloadSha256 expected-digest
     :signatureValid valid-signature?
     :digestValid valid-digest?
     :formatValid valid-format?
     :errors (cond-> []
               (not valid-format?) (conj "INVALID_BUNDLE_FORMAT")
               (not valid-digest?) (conj "PAYLOAD_DIGEST_MISMATCH")
               (not valid-signature?) (conj "SIGNATURE_MISMATCH"))}))
