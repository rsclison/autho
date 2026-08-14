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

(deftest resolve-tenant-includes-identity-bound-product-scope-test
  (let [request {:identity {:tenantIds ["acme"]
                            :organizationId "org-acme"
                            :projectId "payments"
                            :environment "production"}}
        result (tenant/resolve-tenant request {})]
    (is (= "acme" (:tenantId result)))
    (is (= "org-acme" (:organizationId result)))
    (is (= "payments" (:projectId result)))
    (is (= "production" (:environment result)))
    (is (= :identity (get-in result [:organization :source])))
    (is (= :identity (get-in result [:project :source])))
    (is (= :identity (get-in result [:environmentScope :source])))))

(deftest resolve-tenant-rejects-scope-outside-identity-claims-test
  (let [request {:identity {:organizations ["org-acme"]
                            :projects ["payments"]
                            :environments ["production"]}}
        body {:organizationId "org-other"
              :projectId "payments"
              :environment "production"}]
    (try
      (tenant/resolve-tenant request body)
      (is false "Expected organization access denial")
      (catch clojure.lang.ExceptionInfo e
        (is (= 403 (:status (ex-data e))))
        (is (= "ORGANIZATION_FORBIDDEN" (:error-code (ex-data e))))))))

(deftest resolve-tenant-requires-selection-for-multiple-identity-environments-test
  (let [request {:identity {:environments ["staging" "production"]}}]
    (try
      (tenant/resolve-tenant request {})
      (is false "Expected environment selection requirement")
      (catch clojure.lang.ExceptionInfo e
        (is (= 400 (:status (ex-data e))))
        (is (= "ENVIRONMENT_SELECTION_REQUIRED" (:error-code (ex-data e))))))))

(deftest with-tenant-context-adds-product-scope-test
  (is (= {:purpose "test"
          :tenantId "acme"
          :tenant-id "acme"
          :organizationId "org-acme"
          :organization-id "org-acme"
          :projectId "payments"
          :project-id "payments"
          :environment "staging"}
         (tenant/with-tenant-context {:purpose "test"}
                                     {:tenantId "acme"
                                      :organizationId "org-acme"
                                      :projectId "payments"
                                      :environment "staging"}))))
