(ns autho.api-keys-test
  (:require [clojure.test :refer :all]
            [autho.api-keys :as api-keys]))

(use-fixtures
  :each
  (fn [test-fn]
    (api-keys/init!)
    (api-keys/clear-keys!)
    (try
      (test-fn)
      (finally
        (api-keys/clear-keys!)))))

(defn- create-test-key []
  (api-keys/create-key! {:name "payments production"
                         :clientId "payments-api"
                         :clientClass "Application"
                         :roles ["policy-admin"]
                         :tenants ["acme"]
                         :organizations ["org-acme"]
                         :projects ["payments"]
                         :environments ["production"]}))

(deftest create-key-returns-secret-only-at-creation-test
  (let [created (create-test-key)
        listed (first (api-keys/list-keys))]
    (is (re-matches #"ak_[^_]+_[A-Za-z0-9_-]+" (:apiKey created)))
    (is (= "payments-api" (:client_id created)))
    (is (= ["org-acme"] (:organizations created)))
    (is (nil? (:apiKey listed)))
    (is (nil? (:secret_hash listed)))
    (is (= (:key_id created) (:key_id listed)))))

(deftest authenticate-returns-scoped-identity-test
  (let [created (create-test-key)
        identity (api-keys/authenticate (:apiKey created))]
    (is (= :api-key-registry (:auth-method identity)))
    (is (= "payments-api" (:client-id identity)))
    (is (= ["acme"] (:tenants identity)))
    (is (= ["org-acme"] (:organizations identity)))
    (is (= ["payments"] (:projects identity)))
    (is (= ["production"] (:environments identity)))
    (is (= "payments-api" (get-in identity [:subject :id])))))

(deftest authenticate-rejects-invalid-revoked-and-expired-keys-test
  (let [created (create-test-key)
        invalid (str (:apiKey created) "tampered")]
    (is (nil? (api-keys/authenticate invalid)))
    (api-keys/revoke-key! (:key_id created))
    (is (nil? (api-keys/authenticate (:apiKey created))))
    (let [expired (api-keys/create-key! {:name "expired"
                                         :clientId "expired-client"
                                         :expiresAt "2000-01-01T00:00:00"})]
      (is (nil? (api-keys/authenticate (:apiKey expired)))))))

(deftest revoke-key-rejects-a-second-revocation-test
  (let [created (create-test-key)
        revoked (api-keys/revoke-key! (:key_id created))]
    (is (some? (:revoked_at revoked)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"not found or already revoked"
                          (api-keys/revoke-key! (:key_id created))))))
