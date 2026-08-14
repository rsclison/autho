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

(def ^:private scope-dimensions
  {:organization {:id-key :organizationId
                  :legacy-id-key :organization-id
                  :identity-keys [:organizationId :organization-id :organization
                                  :organizationIds :organization-ids :organizations]
                  :header "x-organization-id"
                  :error-code "ORGANIZATION_FORBIDDEN"
                  :selection-error-code "ORGANIZATION_SELECTION_REQUIRED"}
   :project {:id-key :projectId
             :legacy-id-key :project-id
             :identity-keys [:projectId :project-id :project
                             :projectIds :project-ids :projects]
             :header "x-project-id"
             :error-code "PROJECT_FORBIDDEN"
             :selection-error-code "PROJECT_SELECTION_REQUIRED"}
   :environment {:id-key :environment
                 :legacy-id-key :environment-id
                 :identity-keys [:environment :environmentId :environment-id
                                 :environments]
                 :header "x-environment"
                 :error-code "ENVIRONMENT_FORBIDDEN"
                 :selection-error-code "ENVIRONMENT_SELECTION_REQUIRED"}})

(defn- identity-scope-values
  [identity dimension]
  (let [{:keys [identity-keys]} (get scope-dimensions dimension)
        subject (:subject identity)]
    (->> (concat (map #(get identity %) identity-keys)
                 (map #(get subject %) identity-keys))
         (mapcat collect-tenant-values)
         (remove str/blank?)
         set)))

(defn- requested-scope-value
  [request body dimension]
  (let [{:keys [id-key legacy-id-key header]} (get scope-dimensions dimension)
        id-name (name id-key)
        legacy-name (name legacy-id-key)]
    (or (normalize-tenant-id (get body id-key))
        (normalize-tenant-id (get body legacy-id-key))
        (normalize-tenant-id (get-in body [:context id-key]))
        (normalize-tenant-id (get-in body [:context legacy-id-key]))
        (normalize-tenant-id (get-in request [:params id-key]))
        (normalize-tenant-id (get-in request [:params legacy-id-key]))
        (normalize-tenant-id (get-in request [:params id-name]))
        (normalize-tenant-id (get-in request [:params legacy-name]))
        (normalize-tenant-id (get-in request [:headers header])))))

(defn- resolve-scope-dimension
  [request body dimension]
  (let [{:keys [id-key error-code selection-error-code]} (get scope-dimensions dimension)
        identity (:identity request)
        allowed-values (identity-scope-values identity dimension)
        requested (requested-scope-value request body dimension)
        selected (or requested
                     (when (= 1 (count allowed-values))
                       (first allowed-values)))]
    (when (and requested
               (seq allowed-values)
               (not (contains? allowed-values requested)))
      (throw (ex-info (str (name dimension) " access denied")
                      {:status 403
                       :error-code error-code
                       id-key requested
                       :allowedValues (sort allowed-values)})))
    ;; A scoped identity with several values must make the selection explicit.
    (when (and (nil? selected) (> (count allowed-values) 1))
      (throw (ex-info (str "A " (name dimension) " must be selected")
                      {:status 400
                       :error-code selection-error-code
                       :allowedValues (sort allowed-values)})))
    {:value selected
     :requestedValue requested
     :allowedValues (sort allowed-values)
     :source (cond
               requested :request
               (= 1 (count allowed-values)) :identity
               :else nil)}))

(defn resolve-scope
  "Resolves the organization, project and environment associated with a request.
   Claims on the authenticated identity are authoritative when present. An
   unscoped legacy identity remains compatible until the persistent API-key
   registry is enabled."
  [request body]
  (let [organization (resolve-scope-dimension request body :organization)
        project (resolve-scope-dimension request body :project)
        environment (resolve-scope-dimension request body :environment)]
    {:organizationId (:value organization)
     :projectId (:value project)
     :environment (:value environment)
     :organization organization
     :project project
     :environmentScope environment}))

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
    (merge
     {:tenantId selected
      :requestedTenantId requested
      :allowedTenants (sort allowed-tenants)
      :source (cond
                requested :request
                (= 1 (count allowed-tenants)) :identity
                :else :default)}
     (resolve-scope request body))))

(defn with-tenant-context
  [context tenant]
  (cond-> (assoc (or context {})
                 :tenantId (:tenantId tenant)
                 :tenant-id (:tenantId tenant))
    (:organizationId tenant)
    (assoc :organizationId (:organizationId tenant)
           :organization-id (:organizationId tenant))
    (:projectId tenant)
    (assoc :projectId (:projectId tenant)
           :project-id (:projectId tenant))
    (:environment tenant)
    (assoc :environment (:environment tenant))))
