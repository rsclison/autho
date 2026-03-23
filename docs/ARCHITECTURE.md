# Autho — Architecture technique

## Vue d'ensemble

```
┌──────────────────────────────────────────────────────────────────────┐
│                       Applications clientes                           │
│              (services REST, batch, applications web)                │
└──────────────────────────────┬───────────────────────────────────────┘
                               │  HTTP/JSON
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                        Autho  :8080                                   │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                    Middleware Stack                               │ │
│  │  auth · rate-limit · request-size · validation · metrics ·      │ │
│  │  tracing · error-handling · graceful-shutdown                    │ │
│  └────────────────────────────┬────────────────────────────────────┘ │
│                               │                                       │
│     ┌─────────────────────────┼──────────────────────────┐           │
│     │                         │                           │           │
│  ┌──▼──────────┐   ┌─────────▼──────────┐   ┌──────────▼────────┐  │
│  │    PDP      │   │       PAP           │   │    Admin API      │  │
│  │ ─────────── │   │  ────────────────── │   │  ───────────────  │  │
│  │ evalRequest │   │  CRUD politiques    │   │  cache mgmt       │  │
│  │ isAuthorized│   │  import YAML        │   │  reinit/reload    │  │
│  │ explain     │   │  versions/rollback  │   │  audit search     │  │
│  │ whoAuthorized│  │  diff               │   │  PIP schema       │  │
│  │ whatAuthorized  └────────┬───────────┘   └───────────────────┘  │
│  │ time-travel │            │                                        │
│  └──────┬──────┘            │                                        │
│         │              ┌────▼──────┐                                 │
│         │              │    PRP    │  ←── resources/jrules.edn       │
│         │              │ ───────── │  ←── PUT /policy/:class         │
│         │              │ policies  │  ←── POST /import-yaml          │
│         │              │ fillers   │                                  │
│         │              │ pips cfg  │                                  │
│         │              └───────────┘                                 │
│         │                                                             │
│         ▼  enrich-request (fillers + cache)                          │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │                    PIP Layer                                   │    │
│  │  fillPerson (LDAP singleton)  │  Kafka unified  │  REST PIP  │    │
│  │  CSV PIP   │  Internal PIP   │  Java PIP       │  Cache L2  │    │
│  └──────┬─────────────┬──────────────────┬─────────────────────┘    │
│         │             │                  │                            │
└─────────┼─────────────┼──────────────────┼────────────────────────────┘
          │             │                  │
          ▼             ▼                  ▼
      LDAP :389     Kafka :9092        Services REST
                    RocksDB            externes
```

---

## Composants internes

### `autho.pdp` — Policy Decision Point

Cœur du moteur d'évaluation. Reçoit une requête enrichie et retourne une décision.

**Flux d'évaluation :**
1. Récupère la politique pour la classe de ressource (`prp/getGlobalPolicy`)
2. Enrichit le sujet et la ressource via `enrich-request` (fillers + cache)
3. Filtre les règles applicables à l'opération demandée (`applicable-rules`)
4. Évalue chaque règle (`rule/evaluateRule`)
5. Résout les conflits entre règles allow/deny (`resolve-conflict`)
6. En cas de deny, tente la délégation (si configurée)
7. Enregistre la décision dans l'audit

**Fonctions exposées :**

| Fonction | Description |
|----------|-------------|
| `evalRequest` | Évaluation principale, retourne `{:result bool :rules [...]}` |
| `isAuthorized` | Wrapper HTTP avec cache de décision |
| `explain` | Comme `isAuthorized` mais retourne les règles matchées |
| `simulate` | Simulation sans enregistrement audit ni mise en cache |
| `whoAuthorized` | Sujets pouvant effectuer une opération |
| `whatAuthorized` | Ressources accessibles à un sujet |

### `autho.prp` — Policy Repository Point

Stocke en mémoire (atoms Clojure) l'ensemble des politiques, PIPs et fillers.

**Atoms principaux :**

| Atom | Contenu |
|------|---------|
| `policiesMap` | `{"Facture" {:global {:rules [...] :strategy ...}}, ...}` |
| `pips` | Liste des déclarations PIP (chargée depuis `resources/pips.edn`) |
| `subjectFillers` | Map classe → filler sujet (chargée depuis `resources/fillers.edn`) |
| `resourceFillers` | Map classe → filler ressource |
| `personSingleton` | Liste des personnes chargées depuis LDAP |

### `autho.jsonrule` — Évaluation des règles

Évalue les règles au format `:conditions`.

**Format d'une condition :**
```clojure
[opérateur  [NomClasse $s|$r attribut]  opérande2]
```

Exemples :
```clojure
[=   [Person $s service]        [Facture $r service]]
[>=  [Person $s clearance-level] [Facture $r niveau-requis]]
[in  [Person $s role]           "DPO,chef_de_service,legal-counsel"]
[=   [Facture $r statut]        "archive"]
```

**Résolution d'un opérande `[NomClasse $var attribut]` :**
1. Cherche l'attribut directement dans l'objet (après enrichissement par les fillers)
2. Si absent, appelle `attfun/findAndCallPip` → `prp/findPip` → `pip/callPip`
3. Si le PIP retourne une map (ex. REST PIP), extrait l'attribut ciblé
4. Convertit les nombres en string pour la comparaison (`coerce-str`)

### `autho.pip` — Appel des PIPs

Multiméthode Clojure dispatching sur `:type`.

| Type | Implémentation |
|------|---------------|
| `:rest` | `GET {url}/{id}` ou `POST {url}`, retourne `(:body response)` (map JSON parsée) |
| `:kafka-pip-unified` | `kpu/query-pip class id` → `(get result (keyword att))` |
| `:kafka-pip` | `kafka-pip/query-pip class id` avec fallback optionnel |
| `:csv` | Lecture fichier CSV, recherche par colonne id |
| `:internal` | Appel d'une fonction Clojure dans `autho.attfun` |
| `:java` | Appel de `resolveAttribute` sur une instance Java |

### `autho.kafka-pip-unified` — PIP Kafka unifié

Consomme un topic Kafka unique (`business-objects-compacted`). Les messages contiennent un champ `class` pour le routage.

**Flux de données :**
```
Kafka topic  →  consommateur dédié  →  RocksDB (Column Families par classe)
                                              ↑
                                     query-pip(class, id)
```

**Format des messages :**
```json
{ "class": "Facture", "id": "FAC-123", "montant": 4500, "service": "service1" }
```

Les champs `class` et `id` sont retirés avant stockage. Les mises à jour sont fusionnées (merge-on-read).

**State management :**
- `db-state` — atom contenant l'instance RocksDB et les handles des Column Families
- `consumer-handle` — atom contenant le thread consommateur et la fonction d'arrêt

### `autho.attfun` — Fonctions d'attributs et opérateurs

Contient les opérateurs de comparaison et les fillers internes.

**Opérateurs :**

| Opérateur | Signature | Comportement |
|-----------|-----------|-------------|
| `=` | `(= a b)` | Égalité (délègue à `clojure.core/=`) |
| `diff` | `(diff a b)` | Différence |
| `<`, `>`, `<=`, `>=` | `(op a b)` | Comparaison numérique via `edn/read-string` |
| `in` | `(in val set)` | Appartenance ; `set` peut être une chaîne `"a,b,c"` |
| `notin` | `(notin val set)` | Non-appartenance |
| `date>` | `(date> d1 d2)` | Comparaison de dates ISO (`yyyy-MM-dd`) |

**Fillers internes :**
- `fillPerson` — fusionne le sujet avec les données LDAP depuis `prp/personSingleton`
- `internalFiller` — dispatcher vers une fonction filler par son nom

### `autho.local-cache` — Cache multi-niveaux

Cache en mémoire à deux couches :

| Cache | Clé | Contenu |
|-------|-----|---------|
| Décisions | `(subject-id, resource-class, resource-id, operation)` | Résultat de la décision |
| Sujets | `subject-id` | Attributs enrichis du sujet |
| Ressources | `(resource-class, resource-id)` | Attributs enrichis de la ressource |

Invalidation possible en totalité ou par entrée depuis l'API admin.

### `autho.audit` — Journal des décisions

Chaque décision est enregistrée dans une base H2 embarquée avec :
- sujet, classe ressource, id ressource, opération, décision, règles matchées
- horodatage, hash SHA-256 chaîné (pour vérification d'intégrité)

API de recherche avec pagination, filtres et export CSV.

### `autho.delegation` — Délégation d'autorisation

Si un sujet n'est pas autorisé, le PDP vérifie s'il bénéficie d'une délégation depuis un autre sujet autorisé. La délégation est récursive avec détection de cycles.

---

## Communication avec les services externes

### LDAP

**Rôle :** source des attributs personnes (rôle, service, habilitation, etc.)

**Connexion :**
```properties
ldap.server = localhost
ldap.port   = 389
ldap.connectstring = cn=admin,dc=example,dc=com
ldap.password      = admin
ldap.basedn        = ou=people,dc=example,dc=com
ldap.filter        = (objectClass=inetOrgPerson)
ldap.attributes    = uid,cn,role,service,seuil,clearance-level
```

**Intégration :**
- Au démarrage : `person/loadPersons` charge l'annuaire en mémoire (`prp/personSingleton`)
- Pendant l'évaluation : `attfun/fillPerson` fusionne le sujet avec les données LDAP en mémoire
- Librairie : `puppetlabs/clj-ldap`
- Rechargement à chaud : `POST /admin/reload_persons`

**Flux :**
```
Démarrage → ldap/init → ldap/search → person/loadPersons → prp/personSingleton
Requête   → callFillers → fillPerson → merge(sujet, personne LDAP)
```

### Kafka + RocksDB

**Rôle :** enrichissement des objets métier (Factures, Contrats, etc.) avec leurs attributs courants

**Architecture :**
```
Producteurs métier  →  Kafka topic "business-objects-compacted"
                                  ↓
                        Consommateur dédié (thread)
                                  ↓
                        RocksDB (Column Families par classe)
                        /tmp/rocksdb/shared
                                  ↑
                        callPip :kafka-pip-unified
```

**Configuration (`resources/pips.edn`) :**
```clojure
{:type :kafka-pip-unified
 :kafka-topic "business-objects-compacted"
 :kafka-bootstrap-servers "localhost:9092"
 :classes ["Facture" "Contrat" "EngagementJuridique"]}
```

**Format des messages Kafka :**
```json
{
  "class": "Facture",
  "id":    "FAC-123",
  "montant": 4500,
  "service": "service1",
  "statut":  "validée"
}
```

**Désactivation :** `KAFKA_ENABLED=false` désactive le PIP `:kafka-pip` (pas `:kafka-pip-unified`).

**RocksDB :** base embarquée (pas de serveur externe). Chemin configurable : `kafka.pip.rocksdb.path`.

### PIPs REST externes

**Rôle :** enrichissement depuis des services applicatifs existants

**Configuration :**
```clojure
{:class "Rapport"
 :pip {:type :rest
       :url  "http://service-rapports:8080/api/rapports"
       :verb "get"           ; ou "post"
       :timeout-ms 5000}}   ; timeout optionnel (défaut 10 000 ms)
```

**Comportement :**
- `GET` : appelle `{url}/{id}` — attend un objet JSON complet
- `POST` : envoie le contexte objet en body
- Circuit breaker par URL (Diehard) — ouvre après N échecs consécutifs
- Pool de connexions HTTP partagé (clj-http + connection manager)
- Métriques Prometheus par PIP

**Retour :** le corps JSON est parsé comme map keyword → valeur. L'attribut demandé est extrait depuis cette map.

### Base H2 (audit et versionnage)

**Rôle :** persistance de l'audit des décisions et des versions de politiques

- Base embarquée (fichier `resources/h2db`)
- Accès via `clojure.java.jdbc`
- Pas de service externe requis

---

## Middleware stack (ordre d'application)

```
requête entrante
    ↓
wrap-graceful-shutdown   — rejette les nouvelles requêtes pendant l'arrêt
    ↓
wrap-error-handling      — catch toutes les exceptions, retourne JSON structuré
    ↓
tracing/wrap-tracing     — injection du trace-id OpenTelemetry
    ↓
wrap-metrics             — comptage des requêtes Prometheus
    ↓
wrap-request-size-limit  — limite la taille du body (défaut configurable)
    ↓
wrap-rate-limit          — limitation de débit par IP
    ↓
wrap-input-validation    — validation JSON du body
    ↓
auth/wrap-authentication — vérifie JWT ou API Key, injecte :identity dans la requête
    ↓
handler (PDP / PAP / Admin)
```

---

## Structure des fichiers de configuration

| Fichier | Contenu |
|---------|---------|
| `resources/pdp-prop.properties` | Configuration principale (LDAP, Kafka, chemins) |
| `resources/jrules.edn` | Politiques initiales chargées au démarrage |
| `resources/pips.edn` | Déclarations des PIPs |
| `resources/fillers.edn` | Fillers sujet/ressource |
| `resources/delegations.edn` | Déclarations de délégation |
| `resources/policySchema.json` | Schéma JSON de validation des politiques |

---

## Variables d'environnement

| Variable | Obligatoire | Description |
|----------|-------------|-------------|
| `JWT_SECRET` | Oui | Clé de signature JWT (min. 32 caractères) |
| `API_KEY` | Oui | Clé API pour les clients de confiance |
| `KAFKA_ENABLED` | Non | `false` pour désactiver le PIP Kafka (défaut `true`) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Non | Endpoint OTLP pour les traces |

---

## Déploiement

### Jar autonome

```bash
lein uberjar
java -jar target/uberjar/autho-0.1.0-SNAPSHOT-standalone.jar
```

### Environnement de développement

```bash
# Démarrer LDAP + Kafka
docker-compose -f docker/docker-compose.yml up -d

# Démarrer le serveur
JWT_SECRET=dev-secret API_KEY=dev-key lein run
```

### Tests d'intégration

```bash
# Requiert docker-compose up -d
lein test :integration
```

Les tests d'intégration créent leurs propres topics Kafka, bases RocksDB et serveur HTTP — ils ne partagent pas l'état du serveur en production.
