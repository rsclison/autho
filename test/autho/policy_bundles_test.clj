(ns autho.policy-bundles-test
  (:require [clojure.test :refer :all]
            [autho.policy-bundles :as bundles]
            [autho.policy-versions :as pv]))

(def ^:private test-secret "policy-bundle-hmac-secret-32-chars-min")

(def ^:private version-details
  {:resourceClass "Document"
   :version 3
   :author "tester"
   :comment "approved"
   :createdAt "2026-05-21T10:00:00Z"
   :lifecycleStatus "deployed"
   :workflowAction "rollout"
   :policy {:resourceClass "Document"
            :strategy :almost_one_allow_no_deny
            :rules [{:name "allow-read"
                     :effect "allow"
                     :operation "read"
                     :priority 1}]}})

(deftest export-version-bundle-signs-canonical-payload-test
  (with-redefs-fn {#'bundles/bundle-secret test-secret
                   #'pv/get-version-details (fn [_ _] version-details)}
    (fn []
      (let [bundle (bundles/export-version-bundle "Document" 3)
            verification (bundles/verify-bundle bundle)]
        (is (= "autho.policy.bundle.v1" (get-in bundle [:payload :format])))
        (is (= "Document" (get-in bundle [:payload :resourceClass])))
        (is (= "HMAC-SHA256" (get-in bundle [:integrity :algorithm])))
        (is (= true (:valid verification)))
        (is (= true (:signatureValid verification)))
        (is (= true (:digestValid verification)))))))

(deftest verify-bundle-detects-policy-tampering-test
  (with-redefs-fn {#'bundles/bundle-secret test-secret
                   #'pv/get-version-details (fn [_ _] version-details)}
    (fn []
      (let [bundle (bundles/export-version-bundle "Document" 3)
            tampered (assoc-in bundle
                               [:payload :policy :rules 0 :effect]
                               "deny")
            verification (bundles/verify-bundle tampered)]
        (is (= false (:valid verification)))
        (is (= false (:digestValid verification)))
        (is (= false (:signatureValid verification)))
        (is (= ["PAYLOAD_DIGEST_MISMATCH" "SIGNATURE_MISMATCH"]
               (:errors verification)))))))

(deftest export-version-bundle-requires-strong-signing-secret-test
  (with-redefs-fn {#'bundles/bundle-secret "short"
                   #'pv/get-version-details (fn [_ _] version-details)}
    (fn []
      (try
        (bundles/export-version-bundle "Document" 3)
        (is false "Expected weak signing secret rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= "POLICY_BUNDLE_SECRET_WEAK"
                 (get-in (ex-data e) [:error-code]))))))))
