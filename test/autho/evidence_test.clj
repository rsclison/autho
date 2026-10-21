(ns autho.evidence-test
  (:require [clojure.test :refer :all]
            [autho.evidence :as evidence]
            [autho.evidence-cli :as evidence-cli]
            [clojure.data.json :as json])
  (:import (java.security KeyPairGenerator)
           (java.util Base64)))

(defn- ed25519-keys []
  (let [kp (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))]
    {:private (.getPrivate kp) :public (.getPublic kp)}))

(def ^:private sample-payload
  {:kind "evidence_bundle"
   :resourceClass "Document"
   :auditChain {:valid true :total 2 :headId 2 :headHash "ab/cd"}
   :auditReplay {:returned 1 :total 1 :requests [{:operation "read" :decision :allow}]}})

(def ^:private test-secret "audit-hmac-secret-32-chars-min-ok!!")

(deftest export-evidence-package-signs-payload-test
  (with-redefs [evidence/bundle-secret test-secret]
    (let [payload {:kind "evidence_bundle"
                   :resourceClass "Document"
                   :auditChain {:valid true :total 2}
                   :auditReplay {:returned 1 :total 1}}
          bundle (evidence/sign-bundle payload)
          verification (evidence/verify-bundle bundle)]
      (is (= "autho.evidence.bundle.v1" (:format bundle)))
      (is (= "HMAC-SHA256" (get-in bundle [:integrity :algorithm])))
      (is (= true (:valid verification)))
      (is (= true (:signatureValid verification)))
      (is (= true (:digestValid verification))))))

(deftest verify-evidence-package-detects-tampering-test
  (with-redefs [evidence/bundle-secret test-secret]
    (let [payload {:kind "evidence_bundle"
                   :resourceClass "Document"
                   :auditChain {:valid true :total 2}
                   :auditReplay {:returned 1 :total 1}}
          bundle (evidence/sign-bundle payload)
          tampered (assoc-in bundle [:auditChain :total] 99)
          verification (evidence/verify-bundle tampered)]
      (is (= false (:valid verification)))
      (is (= false (:digestValid verification)))
      (is (= false (:signatureValid verification)))
      (is (some #{"PAYLOAD_DIGEST_MISMATCH"} (:errors verification)))
      (is (some #{"SIGNATURE_MISMATCH"} (:errors verification))))))

(deftest bundle-without-signing-key-has-no-public-signature-test
  (with-redefs [evidence/bundle-secret test-secret]
    (let [bundle (evidence/sign-bundle sample-payload)]
      (is (nil? (get-in bundle [:integrity :publicKeySignature])))
      (is (nil? (evidence/public-key-info)))
      (is (true? (:valid (evidence/verify-bundle bundle)))))))

(deftest ed25519-signature-verifies-offline-after-json-round-trip-test
  (let [signing (ed25519-keys)
        bundle (with-redefs [evidence/bundle-secret test-secret
                             evidence/signing-keys signing]
                 (evidence/sign-bundle sample-payload))
        ;; what an auditor receives: the HTTP JSON body, parsed again
        received (json/read-str (json/write-str {:data bundle}) :key-fn keyword)]
    (is (= "Ed25519" (get-in bundle [:integrity :publicKeySignature :algorithm])))
    (is (true? (:valid (evidence/verify-public-signature (:data received) (:public signing)))))
    (testing "server-side verification also checks the public-key signature"
      (with-redefs [evidence/bundle-secret test-secret
                    evidence/signing-keys signing]
        (let [result (evidence/verify-bundle (:data received))]
          (is (true? (:valid result)))
          (is (true? (:publicKeySignatureValid result))))))
    (testing "a tampered bundle is rejected"
      (let [tampered (assoc-in (:data received) [:auditChain :total] 1)
            result (evidence/verify-public-signature tampered (:public signing))]
        (is (false? (:valid result)))
        (is (some #{"PUBLIC_KEY_SIGNATURE_MISMATCH"} (:errors result)))))
    (testing "another key is rejected"
      (let [result (evidence/verify-public-signature (:data received) (:public (ed25519-keys)))]
        (is (false? (:valid result)))
        (is (some #{"PUBLIC_KEY_ID_MISMATCH"} (:errors result)))))))

(deftest public-key-info-round-trips-test
  (let [signing (ed25519-keys)]
    (with-redefs [evidence/signing-keys signing]
      (let [info (evidence/public-key-info)
            parsed (evidence/parse-public-key (:publicKey info))]
        (is (= (evidence/key-id (:public signing)) (:keyId info)))
        (is (= (seq (.getEncoded (:public signing))) (seq (.getEncoded parsed))))))))

(deftest evidence-cli-verifies-bundle-file-test
  (let [signing (ed25519-keys)
        bundle (with-redefs [evidence/bundle-secret test-secret
                             evidence/signing-keys signing]
                 (evidence/sign-bundle sample-payload))
        bundle-file (doto (java.io.File/createTempFile "bundle" ".json") (.deleteOnExit))
        pem (str "-----BEGIN PUBLIC KEY-----\n"
                 (.encodeToString (Base64/getMimeEncoder) (.getEncoded (:public signing)))
                 "\n-----END PUBLIC KEY-----\n")]
    (spit bundle-file (json/write-str {:data bundle}))
    (let [{:keys [exit-code stdout]} (evidence-cli/run [(str bundle-file) pem])]
      (is (= 0 exit-code))
      (is (.startsWith ^String stdout "VALID")))
    (spit bundle-file (json/write-str (assoc-in bundle [:resourceClass] "Other")))
    (is (= 1 (:exit-code (evidence-cli/run [(str bundle-file) pem]))))
    (is (= 2 (:exit-code (evidence-cli/run []))))))
