# Autho — Serveur d'Autorisation XACML/ABAC

**Autho** est un serveur d'autorisation haute performance basé sur les modèles XACML et ABAC (Attribute-Based Access Control). Il fournit une décision d'autorisation centralisée, indépendante des applications métier, avec une capacité d'audit, de versionnage et d'observabilité complète.

---

## Table des matières

1. [Architecture](#architecture)
2. [Démarrage rapide](#démarrage-rapide)
3. [Fonctionnalités](#fonctionnalités)
4. [Langage de politique](#langage-de-politique)
5. [PIPs — Policy Information Points](#pips--policy-information-points)
6. [Cache et performance](#cache-et-performance)
7. [Observabilité](#observabilité)
8. [Configuration](#configuration)
9. [API Reference](#api-reference)
10. [Programmes clients](#programmes-clients)
11. [Structure du projet](#structure-du-projet)

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Applications métier                       │
│         (services, APIs, applications web, batch jobs)          │
└────────────────────────┬────────────────────────────────────────┘
                         │  HTTP (REST JSON)
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                         Autho Server :8080                       │
│                                                                  │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │  Middleware  │  │     PDP      │  │        PAP           │   │
│  │ ─────────── │  │ ──────────── │  │ ──────────────────── │   │
│  │ Auth JWT/Key │  │ isAuthorized │  │ CRUD policies        │   │
│  │ Rate limit  │  │ whoAuthorized│  │ YAML import          │   │
│  │ Validation  │  │ whatAuthorized│ │ Versioning           │   │
│  │ Graceful SD │  │ explain      │  │ Rollback             │   │
│  │ Tracing     │  │ simulate     │  │ Dry-run simulation   │   │
│  └─────────────┘  └──────┬───────┘  └──────────────────────┘   │
│                          │                                       │
│  ┌─────────────┐  ┌──────▼───────┐  ┌──────────────────────┐   │
│  │    Cache    │  │     PRP      │  │        PIP           │   │
│  │ ─────────── │  │ ──────────── │  │ ──────────────────── │   │
│  │ Decisions   │  │ policiesMap  │  │ REST PIP             │   │
│  │ Subjects    │  │ delegations  │  │ Kafka/RocksDB PIP    │   │
│  │ Resources   │  │ persons      │  │ Internal PIP         │   │
│  │ Policies    │  │ fillers      │  │ CSV / Java PIP       │   │
│  └──────┬──────┘  └──────────────┘  └──────────────────────┘   │
│         │                                                        │
│  ┌──────▼──────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │   Kafka     │  │    Audit     │  │      Metrics         │   │
│  │ Invalidation│  │ Tamper-proof │  │ Prometheus /metrics  │   │
│  │ Time-Travel │  │ HMAC chain   │  │ OpenTelemetry spans  │   │
│  └─────────────┘  └──────────────┘  └──────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
         │                                      │
         ▼                                      ▼
   Apache Kafka                           H2 Database
   RocksDB (local)                        Audit + Policy versions
```

### Composants principaux

| Composant | Rôle |
|-----------|------|
| **PDP** (Policy Decision Point) | Évalue les demandes d'autorisation contre les politiques |
| **PRP** (Policy Repository Point) | Stocke et gère les politiques en mémoire |
| **PAP** (Policy Administration Point) | API CRUD pour administrer les politiques |
| **PIP** (Policy Information Point) | Enrichit les sujets/ressources avec des attributs externes |
| **Audit** | Journal immuable signé HMAC-SHA256 en chaîne |
| **Cache** | LRU+TTL à 4 niveaux : décision, sujet, ressource, politique |

---

## Démarrage rapide

### Prérequis

- Java 11+
- Leiningen 2.x

### Lancement

```bash
export JWT_SECRET="my-strong-secret-key-32-chars-min"
export API_KEY="my-api-key"

lein ring server-headless
# Serveur démarré sur http://localhost:8080
```

### Premier appel

```bash
# Vérifier que le serveur est prêt
curl http://localhost:8080/health

# Décision d'autorisation (avec API Key)
curl -X POST http://localhost:8080/isAuthorized \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key my-api-key" \
  -d '{
    "subject":   {"id": "alice", "role": "chef_de_service", "service": "comptabilite"},
    "resource":  {"class": "Facture", "id": "INV-001", "service": "comptabilite", "montant": 500},
    "operation": "lire"
  }'
# → {"results": ["R1"]}

# Décision refusée
curl -X POST http://localhost:8080/isAuthorized \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key my-api-key" \
  -d '{
    "subject":   {"id": "bob", "role": "stagiaire"},
    "resource":  {"class": "Facture", "id": "INV-001"},
    "operation": "lire"
  }'
# → {"results": []}
```

---

## Fonctionnalités

### Décisions d'autorisation

| Endpoint | Description |
|----------|-------------|
| `POST /isAuthorized` | Décision binaire allow/deny pour un triplet sujet-ressource-opération |
| `POST /whoAuthorized` | Sujets autorisés pour une ressource et une opération |
| `POST /whatAuthorized` | Ressources accessibles à un sujet (avec pagination et fetch Kafka) |
| `POST /explain` | Trace détaillée règle par règle de la décision |
| `POST /v1/authz/simulate` | Simulation dry-run (sans cache ni audit) |
| `POST /v1/authz/batch` | Évaluation en lot de jusqu'à 100 demandes en parallèle |

### Gestion des politiques

| Endpoint | Description |
|----------|-------------|
| `GET  /policies` | Lister toutes les politiques |
| `GET  /policies/:rc` | Lire une politique |
| `PUT  /policies/:rc` | Créer ou mettre à jour une politique |
| `DELETE /policies/:rc` | Supprimer une politique |
| `POST /v1/policies/import` | Import depuis YAML |
| `GET  /v1/policies/:rc/versions` | Historique des versions |
| `GET  /v1/policies/:rc/versions/:v` | Récupérer la version v |
| `GET  /v1/policies/:rc/diff?from=1&to=3` | Diff entre deux versions |
| `POST /v1/policies/:rc/rollback/:v` | Rollback à la version v |

### Time-Travel (Kafka activé)

| Endpoint | Description |
|----------|-------------|
| `POST /isAuthorized-at-time` | Décision au temps T |
| `POST /who-was-authorized-at` | Qui pouvait accéder au temps T |
| `POST /what-could-access-at` | Accès du sujet au temps T |
| `POST /audit-trail` | Historique des changements d'autorisation |

### Administration

| Endpoint | Description |
|----------|-------------|
| `GET  /admin/ui` | Dashboard HTML |
| `GET  /admin/ui/policies` | Gestion des politiques (UI) |
| `GET  /admin/ui/circuit-breakers` | État des circuit breakers |
| `GET  /admin/audit/search` | Recherche dans l'audit |
| `GET  /admin/audit/verify` | Vérification de l'intégrité de la chaîne |
| `POST /admin/reload_rules` | Rechargement des règles |
| `GET  /v1/cache/stats` | Statistiques du cache |
| `DELETE /v1/cache` | Vidage du cache |

---

## Langage de politique

### Structure d'une règle

```clojure
{:name          "NomRegle"           ; Identifiant unique
 :resourceClass "Facture"            ; Classe de ressource ciblée
 :domain        "GFC"                ; Domaine fonctionnel (optionnel)
 :operation     "lire"               ; Opération ciblée
 :priority      0                    ; Priorité (entier, plus grand = prioritaire)
 :effect        "allow"              ; "allow" ou "deny"
 :conditions    [clause1 clause2]    ; Toutes doivent être vraies (AND implicite)
 :startDate     "inf"                ; Date de début (ou "inf")
 :endDate       "inf"}               ; Date de fin (ou "inf")
```

### Syntaxe des clauses

Une clause est un vecteur `[opérateur opérande1 opérande2]`.

```clojure
; Attribut sujet = valeur littérale
[= [Person $s role] "chef_de_service"]

; Attribut sujet = attribut ressource (jointure)
[= [Person $s service] [Facture $r service]]

; Comparaison numérique
[< [Facture $r montant] [Person $s seuil]]
[>= [Person $s clearance-level] 3]

; Négation
[diff [Person $s role] "stagiaire"]
```

**Syntaxe JSONPath (alternative) :**

```clojure
["=" "$.role"       "chef_de_service"]   ; attribut du contexte courant
["=" "$s.service"   "$r.service"]        ; $s = sujet, $r = ressource
["<" "$r.montant"   "$s.seuil"]
["in" "$s.role"     "admin,manager"]     ; appartenance CSV
```

### Opérateurs

| Opérateur | Description | Exemple |
|-----------|-------------|---------|
| `=`       | Égalité | `[= [Person $s role] "admin"]` |
| `diff`    | Différence | `[diff [Person $s status] "disabled"]` |
| `<`       | Inférieur | `[< [Facture $r montant] 1000]` |
| `>`       | Supérieur | `[> [Person $s seuil] 500]` |
| `<=`      | Inf. ou égal | `[<= [Facture $r montant] [Person $s seuil]]` |
| `>=`      | Sup. ou égal | `[>= [Person $s clearance-level] 3]` |
| `in`      | Valeur dans ensemble CSV | `["in" "$s.role" "admin,manager,dpo"]` |
| `notin`   | Valeur hors ensemble | `["notin" "$s.status" "suspended,blocked"]` |
| `or`      | OU logique | `["or" clause1 clause2]` |
| `not`     | Négation | `["not" clause]` |
| `and`     | ET étendu | `["and" clause1 clause2 clause3]` |

### Stratégie de conflit

La stratégie `almost_one_allow_no_deny` (défaut) : **autoriser si et seulement si au moins une règle `allow` s'applique ET qu'aucune règle `deny` de priorité supérieure ne s'applique.**

### Exemple complet

```clojure
{"Facture" {:global {
  :strategy :almost_one_allow_no_deny
  :rules [
    {:name "R1"
     :resourceClass "Facture"
     :operation "lire"
     :priority 0
     :conditions [[= [Person $s role]    "chef_de_service"]
                  [= [Person $s service] [Facture $r service]]
                  [< [Facture $r montant] [Person $s seuil]]]
     :effect "allow"}

    {:name "R2"
     :resourceClass "Facture"
     :operation "lire"
     :priority 1
     :conditions [[= [Facture $r confidentiel] "true"]
                  [diff [Person $s role] "DPO"]]
     :effect "deny"}
  ]}}}
```

### Import YAML

```yaml
resourceClass: Facture
strategy: almost_one_allow_no_deny
rules:
  - name: R1
    operation: lire
    priority: 0
    effect: allow
    conditions:
      - ["=",  "$s.role",    "chef_de_service"]
      - ["=",  "$s.service", "$r.service"]
      - ["<",  "$r.montant", "$s.seuil"]
  - name: R2
    operation: lire
    priority: 1
    effect: deny
    conditions:
      - ["=",    "$r.confidentiel", "true"]
      - ["diff", "$s.role",         "DPO"]
```

---

## PIPs — Policy Information Points

Les PIPs enrichissent les sujets et les ressources avec des attributs externes **avant** l'évaluation des règles. Configurés dans `resources/pips.edn`.

### PIP REST

```clojure
{:class "Person"
 :attributes [:role :department :seuil]
 :pip {:type :rest
       :url "http://user-service:8080/api/persons"
       :verb "get"         ; GET /{id}  ou  POST avec body
       :timeout-ms 10000}}
```

Circuit breaker : 3 échecs → ouverture, retry après 10 s.

### PIP Kafka/RocksDB

```clojure
{:class "Facture"
 :pip {:type :kafka-pip
       :id-key :id
       :fallback {:type :rest
                  :url "http://erp:8080/api/factures"
                  :verb "get"}}}
```

Retour < 1 ms (lecture RocksDB local). Fallback REST si objet absent.

### PIP Kafka unifié (multi-classes)

```clojure
{:type :kafka-pip-unified
 :kafka-topic "business-objects-compacted"
 :kafka-bootstrap-servers "localhost:9092"
 :classes ["Facture" "Contrat" "EngagementJuridique"]}
```

Un seul topic Kafka, une seule instance RocksDB (column families par classe).

Format message Kafka :
```json
{"class": "Facture", "id": "INV-123", "montant": 5000, "service": "compta"}
```

### PIP CSV

```clojure
{:class "User"
 :attributes [:name :email :city]
 :pip {:type :csv
       :path "resources/users.csv"
       :id-key :id}}
```

### Fillers (`resources/fillers.edn`)

Les fillers pré-remplissent les entités **avant** l'appel aux PIPs (appels parallèles, timeout 5 s) :

```clojure
{:subject  {"Person"  {:type "urlFiller" :url "http://ldap-proxy/persons"}}
 :resource {"Facture" {:type "urlFiller" :url "http://erp/factures"}}}
```

---

## Cache et performance

| Niveau | TTL défaut | Taille max | Rôle |
|--------|-----------|-----------|------|
| **Décision** | 60 s | 50 000 | Court-circuite les re-évaluations identiques |
| **Sujet** | 5 min | 10 000 | Attributs enrichis par les PIPs |
| **Ressource** | 3 min | 10 000 | Attributs enrichis des ressources |
| **Politique** | 10 min | 5 000 | Politiques résolues |

**Invalidation distribuée** (multi-instances via Kafka) : chaque invalidation est publiée sur le topic `autho-cache-invalidation` et consommée par toutes les instances.

**Éviction** : à l'accès (lazy TTL) + suppression des 25 % entrées les plus anciennes si dépassement de la taille max.

---

## Observabilité

### Prometheus

```
GET /metrics
```

Métriques disponibles :
- `autho_pdp_decisions_total{decision, resource_class}`
- `autho_pip_duration_seconds{type}`
- `autho_cache_events_total{event, type}`
- `autho_http_requests_seconds{method, uri, status}`
- Métriques JVM (mémoire, GC, threads, CPU)

### OpenTelemetry

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.service.name=autho \
     -Dotel.exporter.otlp.endpoint=http://jaeger:4317 \
     -jar autho.jar
```

Spans : `authz.isAuthorized`, `authz.simulate`, `authz.whoAuthorized`, `authz.whatAuthorized`, `authz.explain`.

### Audit immuable

Journal signé HMAC-SHA256 en chaîne. Vérifiable à tout moment :
```
GET /admin/audit/verify
→ {"valid": true}
→ {"valid": false, "broken-at": 4521, "reason": "HMAC mismatch"}
```

### Corrélation des requêtes

En-tête `X-Request-Id` propagé dans tous les logs et retourné dans les réponses.

---

## Configuration

### Variables d'environnement

| Variable | Défaut | Description |
|----------|--------|-------------|
| `JWT_SECRET` | **REQUIS** | Clé de signature JWT |
| `API_KEY` | **REQUIS** | Clé API pour les clients de confiance |
| `KAFKA_ENABLED` | `true` | Active les fonctionnalités Kafka |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Cluster Kafka |
| `KAFKA_INVALIDATION_TOPIC` | `autho-cache-invalidation` | Topic d'invalidation |
| `KAFKA_INVALIDATION_ENABLED` | `true` | Synchronisation cache multi-instances |
| `CACHE_ENABLED` | `true` | Active le cache |
| `DECISION_CACHE_ENABLED` | `true` | Active le cache de décisions |
| `DECISION_CACHE_TTL_MS` | `60000` | TTL du cache décision (ms) |
| `DECISION_CACHE_MAX_SIZE` | `50000` | Taille max cache décision |
| `CACHE_TTL_SUBJECT` | `300000` | TTL cache sujets (ms) |
| `CACHE_TTL_RESOURCE` | `180000` | TTL cache ressources (ms) |
| `CACHE_TTL_POLICY` | `600000` | TTL cache politiques (ms) |
| `RATE_LIMIT_ENABLED` | `true` | Active le rate limiting |
| `RATE_LIMIT_APIKEY_RPM` | `10000` | Requêtes/min par API Key |
| `RATE_LIMIT_JWT_RPM` | `1000` | Requêtes/min par JWT |
| `RATE_LIMIT_ANON_RPM` | `100` | Requêtes/min anonymes |
| `MAX_REQUEST_SIZE` | `1048576` | Taille max du body (octets) |
| `MAX_BATCH_SIZE` | `100` | Taille max d'un batch |
| `MAX_OBJECTS_QUERY_LIMIT` | `10000` | Max objets par whatAuthorized |
| `AUDIT_HMAC_SECRET` | (voir code) | Clé HMAC de l'audit |
| `POLICIES_DIR` | *(désactivé)* | Répertoire YAML policies (hot reload) |

### pdp-prop.properties

```properties
rules.repository = resources/jrules.edn
person.source = file
delegation.type = file
delegation.path = resources/delegations.edn
kafka.pip.rocksdb.path = /tmp/rocksdb/shared
```

---

## API Reference

Voir [`docs/API_REFERENCE.md`](docs/API_REFERENCE.md) pour la référence complète avec tous les exemples.

L'OpenAPI 3.0 est disponible au runtime :
```
GET /openapi.yaml
GET /openapi.json
```

---

## Programmes clients

| Langage | Fichier |
|---------|---------|
| Clojure | [`clients/clojure/src/autho_client/core.clj`](clients/clojure/src/autho_client/core.clj) |
| Java    | [`clients/java/src/main/java/autho/client/AuthoClient.java`](clients/java/src/main/java/autho/client/AuthoClient.java) |

---

## Structure du projet

```
autho/
├── resources/
│   ├── jrules.edn              Règles de politique (format EDN)
│   ├── pips.edn                Configuration des PIPs
│   ├── fillers.edn             Configuration des fillers
│   ├── delegations.edn         Délégations
│   ├── persons.edn             Personnes (mode fichier)
│   ├── policySchema.json       Schéma JSON de validation des politiques
│   └── openapi.yaml            Spécification OpenAPI 3.0
├── src/autho/
│   ├── handler.clj             Point d'entrée HTTP, middleware, routes
│   ├── pdp.clj                 Policy Decision Point
│   ├── prp.clj                 Policy Repository Point
│   ├── pip.clj                 Policy Information Point
│   ├── auth.clj                Authentification JWT / API Key
│   ├── audit.clj               Journal d'audit immuable HMAC
│   ├── local_cache.clj         Cache LRU+TTL 4 niveaux
│   ├── circuit_breaker.clj     Circuit breakers (diehard)
│   ├── kafka_invalidation.clj  Invalidation cache distribuée
│   ├── metrics.clj             Prometheus/Micrometer
│   ├── otel.clj                OpenTelemetry spans
│   ├── tracing.clj             Corrélation X-Request-Id
│   ├── policy_versions.clj     Versionnage des politiques (H2)
│   ├── policy_yaml.clj         Import YAML + directory watcher
│   ├── validation.clj          Validation et protection anti-injection
│   ├── delegation.clj          Système de délégation
│   ├── temporal.clj            Opérations sur les dates
│   ├── set_ops.clj             Opérations ensemblistes
│   ├── jsonrule.clj            Évaluateur de règles JSON/JSONPath
│   ├── attfun.clj              Fonctions opérateurs (=, <, in…)
│   └── api/
│       ├── v1.clj              Routes API v1 RESTful
│       ├── handlers.clj        Gestionnaires de requêtes
│       ├── response.clj        Formatage réponses standardisé
│       ├── pagination.clj      Pagination
│       └── admin_ui.clj        Interface admin HTML (Hiccup)
├── test/autho/                 Tests unitaires et d'intégration
├── docs/
│   └── API_REFERENCE.md        Référence complète de l'API
├── clients/
│   ├── clojure/                Client Clojure de référence
│   └── java/                   Client Java de référence
└── project.clj                 Dépendances et configuration Leiningen
```

---

## Licence

EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0
