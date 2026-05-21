(ns autho.tenant
  (:require [clojure.string :as str]))

(defn default-tenant-id
  []
  (or (System/getenv "AUTHO_DEFAULT_TENANT_ID")
      "default"))

(defn normalize-tenant-id
  [tenant-id]
  (let [value (some-> tenant-id str str/trim)]
    (when-not (str/blank? value)
      value)))

(defn- collect-tenant-values
  [value]
  (cond
    (nil? value) []
    (string? value) (->> (str/split value #",")
                         (map normalize-tenant-id)
                         (remove nil?))
    (keyword? value) [(name value)]
    (sequential? value) (mapcat collect-tenant-values value)
    (set? value) (mapcat collect-tenant-values value)
    :else [(str value)]))

(defn identity-tenants
  [identity]
  (let [subject (:subject identity)]
    (->> [(:tenant-id identity)
          (:tenantId identity)
          (:tenant identity)
          (:tenant-ids identity)
          (:tenantIds identity)
          (:tenants identity)
          (:tenant-id subject)
          (:tenantId subject)
          (:tenant subject)
          (:tenant-ids subject)
          (:tenantIds subject)
          (:tenants subject)]
         (mapcat collect-tenant-values)
         (remove str/blank?)
         set)))

(defn requested-tenant-id
  [request body]
  (or (normalize-tenant-id (:tenantId body))
      (normalize-tenant-id (:tenant-id body))
      (normalize-tenant-id (get-in body [:context :tenantId]))
      (normalize-tenant-id (get-in body [:context :tenant-id]))
      (normalize-tenant-id (get-in request [:params :tenantId]))
      (normalize-tenant-id (get-in request [:params :tenant-id]))
      (normalize-tenant-id (get-in request [:params "tenantId"]))
      (normalize-tenant-id (get-in request [:params "tenant-id"]))
      (normalize-tenant-id (get-in request [:headers "x-tenant-id"]))
      (normalize-tenant-id (get-in request [:headers "X-Tenant-ID"]))))

(defn resolve-tenant
  "Resolves the effective tenant for a request.
   If the authenticated identity declares tenant claims, an explicit requested
   tenant must be one of them."
  [request body]
  (let [identity (:identity request)
        allowed-tenants (identity-tenants identity)
        requested (requested-tenant-id request body)
        selected (or requested
                     (when (= 1 (count allowed-tenants))
                       (first allowed-tenants))
                     (default-tenant-id))]
    (when (and requested
               (seq allowed-tenants)
               (not (contains? allowed-tenants requested)))
      (throw (ex-info "Tenant access denied"
                      {:status 403
                       :error-code "TENANT_FORBIDDEN"
                       :tenantId requested
                       :allowedTenants (sort allowed-tenants)})))
    {:tenantId selected
     :requestedTenantId requested
     :allowedTenants (sort allowed-tenants)
     :source (cond
               requested :request
               (= 1 (count allowed-tenants)) :identity
               :else :default)}))

(defn with-tenant-context
  [context tenant]
  (assoc (or context {})
         :tenantId (:tenantId tenant)
         :tenant-id (:tenantId tenant)))
