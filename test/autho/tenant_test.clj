(ns autho.tenant-test
  (:require [clojure.test :refer :all]
            [autho.tenant :as tenant]))

(deftest resolve-tenant-uses-request-tenant-when-allowed-test
  (let [request {:identity {:tenantIds ["acme" "globex"]}
                 :headers {"x-tenant-id" "globex"}}
        result (tenant/resolve-tenant request {})]
    (is (= "globex" (:tenantId result)))
    (is (= :request (:source result)))))

(deftest resolve-tenant-denies-requested-tenant-outside-identity-test
  (let [request {:identity {:tenantIds ["acme"]}
                 :headers {"x-tenant-id" "globex"}}]
    (try
      (tenant/resolve-tenant request {})
      (is false "Expected tenant access denial")
      (catch clojure.lang.ExceptionInfo e
        (is (= 403 (get-in (ex-data e) [:status])))
        (is (= "TENANT_FORBIDDEN" (get-in (ex-data e) [:error-code])))))))

(deftest resolve-tenant-falls-back-to-single-identity-tenant-test
  (let [request {:identity {:subject {:tenant-id "acme"}}}
        result (tenant/resolve-tenant request {})]
    (is (= "acme" (:tenantId result)))
    (is (= :identity (:source result)))))

(deftest with-tenant-context-adds-canonical-and-legacy-keys-test
  (is (= {:purpose "test"
          :tenantId "acme"
          :tenant-id "acme"}
         (tenant/with-tenant-context {:purpose "test"} {:tenantId "acme"}))))
