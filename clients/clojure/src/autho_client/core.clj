(ns autho-client.core
  "Client Clojure pour le serveur d'autorisation Autho.

   Fonctionnalités couvertes :
   - isAuthorized        — décision binaire
   - whoAuthorized       — sujets autorisés
   - whatAuthorized      — ressources accessibles
   - explain             — trace de décision
   - simulate            — simulation dry-run
   - batch               — évaluation en lot
   - CRUD politiques     — créer / lire / mettre à jour / supprimer
   - Versionnage         — lister / récupérer / diff / rollback
   - Cache               — statistiques / vider / invalider
   - Audit               — recherche et vérification d'intégrité

   Usage minimal :
     (def client (make-client \"http://localhost:8080\" {:api-key \"my-key\"}))
     (authorized? client {:id \"alice\" :role \"chef_de_service\"} {:class \"Facture\" :id \"INV-1\"} \"lire\")"
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]))

;; =============================================================================
;; Configuration du client
;; =============================================================================

(defn make-client
  "Crée un client Autho.

   Options :
     :api-key       — clé API (X-API-Key)
     :jwt-token     — jeton JWT (Authorization: Token ...)
     :timeout-ms    — timeout HTTP (défaut 10 000 ms)
     :throw-errors? — lance une exception sur erreur HTTP (défaut false)

   Exemple :
     (make-client \"http://autho:8080\" {:api-key \"secret\"})"
  ([base-url opts]
   {:base-url      (str/replace base-url #"/$" "")
    :api-key       (:api-key opts)
    :jwt-token     (:jwt-token opts)
    :timeout-ms    (get opts :timeout-ms 10000)
    :throw-errors? (get opts :throw-errors? false)})
  ([base-url api-key]
   (make-client base-url {:api-key api-key})))

;; =============================================================================
;; Couche HTTP interne
;; =============================================================================

(defn- auth-headers
  "Construit les en-têtes d'authentification."
  [{:keys [api-key jwt-token]}]
  (cond
    api-key    {"X-API-Key" api-key}
    jwt-token  {"Authorization" (str "Token " jwt-token)}
    :else      {}))

(defn- json-post
  "POST JSON, retourne le corps parsé ou nil en cas d'erreur."
  [client path body]
  (try
    (let [resp (http/post
                 (str (:base-url client) path)
                 {:headers         (merge {"Content-Type" "application/json"
                                           "Accept"       "application/json"}
                                          (auth-headers client))
                  :body            (json/write-str body)
                  :socket-timeout  (:timeout-ms client)
                  :conn-timeout    (:timeout-ms client)
                  :throw-exceptions false})]
      (when-let [body-str (:body resp)]
        (json/read-str body-str :key-fn keyword)))
    (catch Exception e
      (when (:throw-errors? client)
        (throw e))
      {:error (.getMessage e)})))

(defn- json-get
  "GET, retourne le corps parsé ou nil en cas d'erreur."
  [client path & [query-params]]
  (try
    (let [resp (http/get
                 (str (:base-url client) path)
                 {:headers         (merge {"Accept" "application/json"}
                                          (auth-headers client))
                  :query-params    query-params
                  :socket-timeout  (:timeout-ms client)
                  :conn-timeout    (:timeout-ms client)
                  :throw-exceptions false})]
      (when-let [body-str (:body resp)]
        (json/read-str body-str :key-fn keyword)))
    (catch Exception e
      (when (:throw-errors? client)
        (throw e))
      {:error (.getMessage e)})))

(defn- json-put
  "PUT JSON."
  [client path body]
  (try
    (let [resp (http/put
                 (str (:base-url client) path)
                 {:headers         (merge {"Content-Type" "application/json"
                                           "Accept"       "application/json"}
                                          (auth-headers client))
                  :body            (json/write-str body)
                  :socket-timeout  (:timeout-ms client)
                  :conn-timeout    (:timeout-ms client)
                  :throw-exceptions false})]
      (when-let [body-str (:body resp)]
        (json/read-str body-str :key-fn keyword)))
    (catch Exception e
      (when (:throw-errors? client)
        (throw e))
      {:error (.getMessage e)})))

(defn- json-delete
  "DELETE."
  [client path]
  (try
    (let [resp (http/delete
                 (str (:base-url client) path)
                 {:headers         (merge {"Accept" "application/json"}
                                          (auth-headers client))
                  :socket-timeout  (:timeout-ms client)
                  :conn-timeout    (:timeout-ms client)
                  :throw-exceptions false})]
      (when-let [body-str (:body resp)]
        (json/read-str body-str :key-fn keyword)))
    (catch Exception e
      (when (:throw-errors? client)
        (throw e))
      {:error (.getMessage e)})))

;; =============================================================================
;; Décisions d'autorisation
;; =============================================================================

(defn is-authorized
  "Évalue si subject peut effectuer operation sur resource.
   Retourne la réponse brute {:results [...]} du serveur.

   Exemple :
     (is-authorized client
       {:id \"alice\" :role \"chef_de_service\" :service \"compta\"}
       {:class \"Facture\" :id \"INV-001\" :service \"compta\" :montant 500}
       \"lire\")"
  ([client subject resource operation]
   (is-authorized client subject resource operation nil))
  ([client subject resource operation context]
   (json-post client "/isAuthorized"
              (cond-> {:subject subject :resource resource :operation operation}
                context (assoc :context context)))))

(defn authorized?
  "Retourne true si le sujet est autorisé (au moins une règle allow correspond).
   Forme simplifiée de is-authorized.

   Exemple :
     (when (authorized? client {:id \"alice\" :role \"chef_de_service\"}
                                {:class \"Facture\" :id \"INV-001\"}
                                \"lire\")
       (read-facture \"INV-001\"))"
  ([client subject resource operation]
   (authorized? client subject resource operation nil))
  ([client subject resource operation context]
   (let [result (is-authorized client subject resource operation context)]
     (boolean (seq (:results result))))))

(defn matched-rules
  "Retourne les noms des règles qui ont correspondu (vecteur de strings).
   Vecteur vide = refus.

   Exemple :
     (matched-rules client {:id \"alice\"} {:class \"Facture\" :id \"1\"} \"lire\")
     ;; => [\"R1\"]"
  [client subject resource operation]
  (let [result (is-authorized client subject resource operation)]
    (vec (:results result))))

(defn who-authorized
  "Retourne les sujets autorisés pour une ressource et une opération.
   Retourne la réponse brute (vecteur de conditions de sujet).

   Exemple :
     (who-authorized client {:class \"Facture\" :id \"INV-001\"} \"lire\")"
  [client resource operation]
  (json-post client "/whoAuthorized"
             {:resource resource :operation operation}))

(defn what-authorized
  "Retourne les ressources accessibles à un sujet.
   opts peut contenir :page et :page-size.

   Exemple :
     (what-authorized client {:id \"alice\" :role \"chef_de_service\"} \"Facture\" \"lire\")
     (what-authorized client subject \"Facture\" \"lire\" {:page 2 :page-size 50})"
  ([client subject resource-class operation]
   (what-authorized client subject resource-class operation {}))
  ([client subject resource-class operation opts]
   (json-post client "/whatAuthorized"
              (cond-> {:subject subject
                       :resource {:class resource-class}
                       :operation operation}
                (:page opts)      (assoc :page (:page opts))
                (:page-size opts) (assoc :pageSize (:page-size opts))))))

(defn explain
  "Retourne une trace détaillée de la décision (règle par règle).

   Exemple :
     (explain client
       {:id \"alice\" :role \"chef_de_service\"}
       {:class \"Facture\" :id \"INV-001\"}
       \"lire\")"
  [client subject resource operation]
  (json-post client "/explain"
             {:subject subject :resource resource :operation operation}))

(defn simulate
  "Simule une décision en dry-run (sans cache ni audit).

   Options :
     :simulated-policy — politique inline à tester
     :policy-version   — numéro de version archivée à utiliser

   Exemples :
     ; Simulation avec politique inline
     (simulate client
       {:id \"alice\" :role \"chef_de_service\"}
       {:class \"Facture\" :id \"INV-001\"}
       \"lire\"
       {:simulated-policy {:resourceClass \"Facture\"
                           :rules [{:name \"TEST\" :effect \"allow\"
                                    :operation \"lire\" :priority 0
                                    :conditions [[\"=\" \"$.role\" \"chef_de_service\"]]}]}})

     ; Simulation avec version archivée
     (simulate client subject resource operation {:policy-version 3})"
  ([client subject resource operation]
   (simulate client subject resource operation {}))
  ([client subject resource operation opts]
   (json-post client "/v1/authz/simulate"
              (cond-> {:subject subject :resource resource :operation operation}
                (:simulated-policy opts) (assoc :simulatedPolicy (:simulated-policy opts))
                (:policy-version opts)   (assoc :policyVersion (:policy-version opts))))))

(defn batch-decisions
  "Évalue un lot de demandes en parallèle (max 100 par défaut).

   Chaque demande est {:subject {...} :resource {...} :operation \"...\"}

   Exemple :
     (batch-decisions client
       [{:subject {:id \"alice\"} :resource {:class \"Facture\" :id \"1\"} :operation \"lire\"}
        {:subject {:id \"bob\"}   :resource {:class \"Facture\" :id \"2\"} :operation \"lire\"}])"
  [client requests]
  (json-post client "/v1/authz/batch" {:requests requests}))

;; =============================================================================
;; Gestion des politiques
;; =============================================================================

(defn get-policy
  "Récupère une politique par resourceClass.

   Exemple :
     (get-policy client \"Facture\")"
  [client resource-class]
  (json-get client (str "/policy/" resource-class)))

(defn list-policies
  "Liste toutes les politiques.

   Exemple :
     (list-policies client)"
  [client]
  (json-get client "/policies"))

(defn submit-policy
  "Crée ou met à jour une politique.

   La politique doit contenir :resourceClass, :rules, et optionnellement :strategy.

   Exemple :
     (submit-policy client \"Facture\"
       {:resourceClass \"Facture\"
        :strategy \"almost_one_allow_no_deny\"
        :rules [{:name \"R1\" :operation \"lire\" :priority 0 :effect \"allow\"
                 :conditions [[\"=\" \"$.role\" \"chef_de_service\"]]}]})"
  [client resource-class policy]
  (json-put client (str "/policy/" resource-class) policy))

(defn delete-policy
  "Supprime une politique.

   Exemple :
     (delete-policy client \"Facture\")"
  [client resource-class]
  (json-delete client (str "/policy/" resource-class)))

(defn import-yaml-policy
  "Importe une politique depuis une chaîne YAML.

   Exemple :
     (import-yaml-policy client
       \"resourceClass: Facture\\nrules:\\n  - name: R1\\n    operation: lire\\n    effect: allow\")"
  [client yaml-str]
  (try
    (let [resp (http/post
                 (str (:base-url client) "/v1/policies/import")
                 {:headers         (merge {"Content-Type" "text/yaml"
                                           "Accept"       "application/json"}
                                          (auth-headers client))
                  :body            yaml-str
                  :socket-timeout  (:timeout-ms client)
                  :conn-timeout    (:timeout-ms client)
                  :throw-exceptions false})]
      (when-let [body-str (:body resp)]
        (json/read-str body-str :key-fn keyword)))
    (catch Exception e
      (when (:throw-errors? client) (throw e))
      {:error (.getMessage e)})))

;; =============================================================================
;; Versionnage des politiques
;; =============================================================================

(defn list-versions
  "Liste les versions d'une politique (plus récente en premier).

   Exemple :
     (list-versions client \"Facture\")"
  [client resource-class]
  (json-get client (str "/v1/policies/" resource-class "/versions")))

(defn get-version
  "Récupère la version v d'une politique.

   Exemple :
     (get-version client \"Facture\" 3)"
  [client resource-class version]
  (json-get client (str "/v1/policies/" resource-class "/versions/" version)))

(defn diff-versions
  "Compare deux versions d'une politique.
   Retourne {:added [...] :removed [...] :changed [...]}

   Exemple :
     (diff-versions client \"Facture\" 3 5)"
  [client resource-class from-v to-v]
  (json-get client (str "/v1/policies/" resource-class "/diff")
            {"from" from-v "to" to-v}))

(defn rollback
  "Restaure une politique à la version v.

   Exemple :
     (rollback client \"Facture\" 3)"
  [client resource-class version]
  (json-post client (str "/v1/policies/" resource-class "/rollback/" version) {}))

;; =============================================================================
;; Cache
;; =============================================================================

(defn cache-stats
  "Retourne les statistiques du cache (hits, misses, tailles).

   Exemple :
     (cache-stats client)"
  [client]
  (json-get client "/v1/cache/stats"))

(defn clear-cache
  "Vide tous les caches.

   Exemple :
     (clear-cache client)"
  [client]
  (json-delete client "/v1/cache"))

(defn invalidate-cache-entry
  "Invalide une entrée précise du cache.
   type est une string : \"subject\", \"resource\", \"policy\" ou \"decision\".

   Exemple :
     (invalidate-cache-entry client \"subject\" \"alice\")"
  [client cache-type cache-key]
  (json-delete client (str "/v1/cache/" cache-type "/" cache-key)))

;; =============================================================================
;; Audit
;; =============================================================================

(defn search-audit
  "Recherche dans le journal d'audit.

   Options :
     :subject-id     — filtrer par sujet
     :resource-class — filtrer par classe de ressource
     :decision       — \"allow\" ou \"deny\"
     :from           — date ISO début (ex: \"2026-01-01\")
     :to             — date ISO fin
     :page           — numéro de page (défaut 1)
     :page-size      — entrées par page (défaut 20)

   Exemple :
     (search-audit client {:subject-id \"alice\" :resource-class \"Facture\"
                           :from \"2026-01-01\" :to \"2026-03-31\"})"
  [client & [opts]]
  (json-get client "/admin/audit/search"
            (cond-> {}
              (:subject-id opts)     (assoc "subjectId" (:subject-id opts))
              (:resource-class opts) (assoc "resourceClass" (:resource-class opts))
              (:decision opts)       (assoc "decision" (:decision opts))
              (:from opts)           (assoc "from" (:from opts))
              (:to opts)             (assoc "to" (:to opts))
              (:page opts)           (assoc "page" (:page opts))
              (:page-size opts)      (assoc "pageSize" (:page-size opts)))))

(defn verify-audit-chain
  "Vérifie l'intégrité de la chaîne HMAC du journal d'audit.
   Retourne {:valid true} ou {:valid false :broken-at N :reason \"...\"}

   Exemple :
     (verify-audit-chain client)"
  [client]
  (json-get client "/admin/audit/verify"))

;; =============================================================================
;; Santé & statut
;; =============================================================================

(defn health
  "Vérification de santé du serveur.

   Exemple :
     (health client)"
  [client]
  (json-get client "/health"))

(defn status
  "Statut détaillé : version, uptime, circuit breakers, stats cache.

   Exemple :
     (status client)"
  [client]
  (json-get client "/status"))

;; =============================================================================
;; Utilitaires
;; =============================================================================

(defn allow?
  "Alias idiomatique de authorized? pour une lecture plus naturelle."
  [client subject resource operation]
  (authorized? client subject resource operation))

(defn deny?
  "Retourne true si le sujet est refusé."
  [client subject resource operation]
  (not (authorized? client subject resource operation)))

(defn require-authorization!
  "Vérifie l'autorisation et lance une exception en cas de refus.
   Utile dans les middlewares ou les fonctions de service.

   Exemple :
     (require-authorization! client {:id user-id :role role} resource \"lire\")
     ; Lance ExceptionInfo {:type :forbidden} si refusé"
  [client subject resource operation]
  (when-not (authorized? client subject resource operation)
    (throw (ex-info "Accès refusé"
                    {:type      :forbidden
                     :subject   (:id subject)
                     :resource  resource
                     :operation operation}))))

;; =============================================================================
;; Exemples d'utilisation
;; =============================================================================

(comment
  ;; Créer un client
  (def client (make-client "http://localhost:8080" {:api-key "my-api-key"}))

  ;; -- Décisions de base --

  ; Vérification simple
  (authorized? client
    {:id "alice" :role "chef_de_service" :service "comptabilite"}
    {:class "Facture" :id "INV-001" :service "comptabilite" :montant 500}
    "lire")
  ;; => true

  ; Avec les règles qui ont correspondu
  (matched-rules client
    {:id "alice" :role "chef_de_service"}
    {:class "Facture" :id "INV-001"}
    "lire")
  ;; => ["R1"]

  ; Trace de décision
  (explain client
    {:id "alice" :role "chef_de_service"}
    {:class "Facture" :id "INV-001"}
    "lire")
  ;; => {:decision true :totalRules 2 :matchedRules 1 :rules [...]}

  ; Simulation dry-run avec politique inline
  (simulate client
    {:id "alice" :role "chef_de_service"}
    {:class "Facture" :id "INV-001"}
    "lire"
    {:simulated-policy
     {:resourceClass "Facture"
      :rules [{:name "TEST" :effect "allow" :operation "lire" :priority 0
               :conditions [["=" "$.role" "chef_de_service"]]}]}})

  ; Batch
  (batch-decisions client
    [{:subject {:id "alice" :role "chef_de_service"}
      :resource {:class "Facture" :id "INV-001"}
      :operation "lire"}
     {:subject {:id "bob" :role "stagiaire"}
      :resource {:class "Facture" :id "INV-002"}
      :operation "lire"}])
  ;; => {:results [{:request-id 0 :decision {:results ["R1"]}}
  ;;               {:request-id 1 :decision {:results []}}]
  ;;     :count 2}

  ; Ressources accessibles
  (what-authorized client
    {:id "alice" :role "chef_de_service" :service "comptabilite"}
    "Facture"
    "lire"
    {:page 1 :page-size 20})

  ; Lever une exception en cas de refus
  (require-authorization! client
    {:id "bob" :role "stagiaire"}
    {:class "Facture" :id "INV-001"}
    "lire")
  ;; => lance ExceptionInfo {:type :forbidden}

  ;; -- Gestion des politiques --

  (submit-policy client "MonDocument"
    {:resourceClass "MonDocument"
     :strategy "almost_one_allow_no_deny"
     :rules [{:name "MD1" :operation "lire" :priority 0 :effect "allow"
              :conditions [["=" "$.role" "admin"]]}]})

  (list-versions client "Facture")
  ;; => [{:version 5 :author "alice" :createdAt "2026-03-18T09:00:00Z"} ...]

  (diff-versions client "Facture" 3 5)
  ;; => {:added ["R-DPO"] :removed [] :changed ["R1"]}

  (rollback client "Facture" 3)
  ;; => {:resourceClass "Facture" :rolledBackTo 3 :newVersion 6}

  ;; -- Cache --

  (cache-stats client)
  (invalidate-cache-entry client "subject" "alice")
  (clear-cache client)

  ;; -- Audit --

  (search-audit client {:subject-id "alice" :from "2026-01-01" :to "2026-03-31"})
  (verify-audit-chain client)
  ;; => {:valid true}

  ;; -- Santé --

  (health client)
  ;; => {:status "ok"}

  (status client)
  ;; => {:status "ok" :version "0.1.0-SNAPSHOT" :uptime_seconds 3612
  ;;     :circuit_breakers {:user-service "closed"} ...}
  )
