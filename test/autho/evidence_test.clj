(ns autho.evidence-test
  (:require [clojure.test :refer :all]
            [autho.evidence :as evidence]))

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
