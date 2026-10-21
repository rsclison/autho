(ns autho.evidence
  "Signed evidence bundle export and verification.
   Evidence bundles package audit and policy-governance context for export to
   operators, auditors, and compliance tooling.

   Bundles are always HMAC-SHA256 signed (shared secret, verifiable by the
   server). When an Ed25519 key pair is configured they also carry a public-key
   signature that third parties can verify offline with the published public
   key (GET /v1/evidence/public-key, autho.evidence-cli)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.security KeyFactory MessageDigest PrivateKey PublicKey Signature)
           (java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec)
           (java.util Base64)
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

;; ---------------------------------------------------------------------------
;; Ed25519 public-key signatures
;; ---------------------------------------------------------------------------

;; Overridable in tests: {:private PrivateKey :public PublicKey}
(def ^:private signing-keys nil)

(defn- key-bytes
  "Accepts base64 DER or PEM (headers and whitespace are ignored)."
  [^String encoded]
  (.decode (Base64/getDecoder)
           (-> encoded
               (str/replace #"-----[A-Z ]+-----" "")
               (str/replace #"\s" ""))))

(defn parse-public-key
  ^PublicKey [^String encoded]
  (.generatePublic (KeyFactory/getInstance "Ed25519")
                   (X509EncodedKeySpec. (key-bytes encoded))))

(defn- parse-private-key
  ^PrivateKey [^String encoded]
  (.generatePrivate (KeyFactory/getInstance "Ed25519")
                    (PKCS8EncodedKeySpec. (key-bytes encoded))))

(defn- configured-signing-keys
  "Reads EVIDENCE_SIGNING_PRIVATE_KEY (PKCS#8) and EVIDENCE_SIGNING_PUBLIC_KEY
   (X.509 SubjectPublicKeyInfo), base64 DER or PEM. Returns nil when unset."
  []
  (or signing-keys
      (let [private (System/getenv "EVIDENCE_SIGNING_PRIVATE_KEY")
            public (System/getenv "EVIDENCE_SIGNING_PUBLIC_KEY")]
        (cond
          (and (str/blank? private) (str/blank? public)) nil
          (or (str/blank? private) (str/blank? public))
          (throw (ex-info "EVIDENCE_SIGNING_PRIVATE_KEY and EVIDENCE_SIGNING_PUBLIC_KEY must both be set"
                          {:status 500 :error-code "EVIDENCE_SIGNING_KEY_INCOMPLETE"}))
          :else {:private (parse-private-key private)
                 :public (parse-public-key public)}))))

(defn key-id
  [^PublicKey public-key]
  (subs (bytes->hex (.digest (MessageDigest/getInstance "SHA-256") (.getEncoded public-key))) 0 16))

(defn public-key-info
  "Public key description for offline verification, or nil when not configured."
  []
  (when-let [{:keys [^PublicKey public]} (configured-signing-keys)]
    {:algorithm "Ed25519"
     :keyId (key-id public)
     :encoding "X.509 SubjectPublicKeyInfo, DER, base64"
     :publicKey (.encodeToString (Base64/getEncoder) (.getEncoded public))}))

(defn- ed25519-sign
  [^PrivateKey private-key ^String data]
  (let [sig (doto (Signature/getInstance "Ed25519")
              (.initSign private-key)
              (.update (.getBytes data "UTF-8")))]
    (.encodeToString (Base64/getEncoder) (.sign sig))))

(defn- ed25519-valid?
  [^PublicKey public-key ^String data ^String signature]
  (try
    (let [sig (doto (Signature/getInstance "Ed25519")
                (.initVerify public-key)
                (.update (.getBytes data "UTF-8")))]
      (.verify sig (.decode (Base64/getDecoder) signature)))
    (catch Exception _ false)))

(defn verify-public-signature
  "Verifies only the Ed25519 signature of a bundle with the given public key.
   Needs no shared secret: this is what an external auditor runs."
  [bundle ^PublicKey public-key]
  (let [payload (dissoc bundle :integrity)
        {:keys [keyId signature] :as pk-sig} (get-in bundle [:integrity :publicKeySignature])
        expected-key-id (key-id public-key)
        expected-digest (payload-digest payload)
        valid-format? (= bundle-format (:format payload))
        present? (some? pk-sig)
        key-matches? (= expected-key-id keyId)
        valid-signature? (and present? key-matches?
                              (ed25519-valid? public-key (canonical-json payload) (str signature)))
        valid-digest? (constant-time-equals? expected-digest (get-in bundle [:integrity :payloadSha256]))]
    {:valid (boolean (and valid-format? valid-signature? valid-digest?))
     :algorithm "Ed25519"
     :keyId expected-key-id
     :payloadSha256 expected-digest
     :errors (cond-> []
               (not valid-format?) (conj "INVALID_BUNDLE_FORMAT")
               (not present?) (conj "PUBLIC_KEY_SIGNATURE_MISSING")
               (and present? (not key-matches?)) (conj "PUBLIC_KEY_ID_MISMATCH")
               (and present? key-matches? (not valid-signature?)) (conj "PUBLIC_KEY_SIGNATURE_MISMATCH")
               (not valid-digest?) (conj "PAYLOAD_DIGEST_MISMATCH"))}))

(defn sign-bundle
  [bundle]
  (let [payload (assoc bundle :format bundle-format)
        digest (payload-digest payload)
        signature (sign-payload payload)
        signing (configured-signing-keys)]
    (assoc payload
           :integrity (cond-> {:algorithm "HMAC-SHA256"
                               :canonicalization "json-sorted-keys-v1"
                               :payloadSha256 digest
                               :signature signature
                               :signedAt (str (Instant/now))}
                        signing
                        (assoc :publicKeySignature
                               {:algorithm "Ed25519"
                                :keyId (key-id (:public signing))
                                :signature (ed25519-sign (:private signing) (canonical-json payload))})))))

(defn verify-bundle
  [bundle]
  (let [payload (dissoc bundle :integrity)
        integrity (:integrity bundle)
        expected-digest (payload-digest payload)
        expected-signature (sign-payload payload)
        valid-digest? (constant-time-equals? expected-digest (:payloadSha256 integrity))
        valid-signature? (constant-time-equals? expected-signature (:signature integrity))
        valid-format? (= bundle-format (:format payload))
        pk-sig (:publicKeySignature integrity)
        signing (when pk-sig (configured-signing-keys))
        pk-result (when (and pk-sig signing) (verify-public-signature bundle (:public signing)))
        valid-pk? (if pk-result (:valid pk-result) true)]
    {:valid (boolean (and valid-format? valid-digest? valid-signature? valid-pk?))
     :format (:format payload)
     :resourceClass (:resourceClass payload)
     :algorithm (:algorithm integrity)
     :payloadSha256 expected-digest
     :signatureValid valid-signature?
     :digestValid valid-digest?
     :formatValid valid-format?
     :publicKeySignatureValid (when pk-result (:valid pk-result))
     :warnings (cond-> []
                 (and pk-sig (nil? signing)) (conj "PUBLIC_KEY_NOT_CONFIGURED"))
     :errors (cond-> []
               (not valid-format?) (conj "INVALID_BUNDLE_FORMAT")
               (not valid-digest?) (conj "PAYLOAD_DIGEST_MISMATCH")
               (not valid-signature?) (conj "SIGNATURE_MISMATCH")
               (and pk-result (not (:valid pk-result)))
               (into (remove #{"PAYLOAD_DIGEST_MISMATCH" "INVALID_BUNDLE_FORMAT"} (:errors pk-result))))}))
