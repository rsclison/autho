# Analyse Technique Profonde : Autho → Serveur d'Autorisation de Classe Mondiale

**Date** : 18 Mars 2026
**Version analysée** : 1.3.0-rc
**Analyste** : Claude Sonnet 4.6

---

## Table des Matières

1. [État Actuel : Forces & Atouts Réels](#1-état-actuel--forces--atouts-réels)
2. [Bugs et Problèmes Critiques Détectés](#2-bugs-et-problèmes-critiques-détectés)
3. [Lacunes vs Concurrents de Référence](#3-lacunes-vs-concurrents-de-référence)
4. [Roadmap Proposée](#4-roadmap-proposée--de-bon-à-meilleur-du-marché)
5. [Quick Wins Architecturaux](#5-quick-wins-architecturaux-haute-valeur-faible-effort)
6. [Vision Finale : Ce Qui Peut Rendre Autho Unique](#6-vision-finale--ce-qui-peut-rendre-autho-unique)

---

## 1. État Actuel : Forces & Atouts Réels

Le projet a une base solide et distinctive :

- **Architecture XACML/ABAC complète** (PDP + PRP + PIP + PAP) correctement séparée
- **Feature time-travel unique** via Kafka — quasi-inexistante chez les concurrents
- **Support multi-PIPs** (REST, Kafka/RocksDB, CSV, LDAP, Java) très flexible
- **Délégation avec détection de cycles** — mécanisme rare dans les serveurs d'autorisation
- **Opérateurs temporels, ensembles, logiques** — richesse d'expression
- Code Clojure idiomatique et lisible

---

## 2. Bugs et Problèmes Critiques Détectés

### 🔴 CRITIQUE — Bloquants production

#### Bug 1 — `attfun.clj:49` — Stub de développement jamais retiré

```clojure
(defn role [obj]
  "Professeur")   ; ← retourne toujours "Professeur" !
```

Toute règle qui utilise la fonction `role` comme PIP interne retourne faussement "Professeur"
pour n'importe quel sujet. Si une politique s'appuie sur ce PIP, toutes ses décisions sont fausses.

---

#### Bug 2 — `prp.clj:165` — Strategy hardcodée à l'ingestion des politiques

```clojure
:strategy :almost_one_allow_no_deny  ; ← hardcodé, ignore la config du fichier
```

Même si une politique déclarée dans le fichier de règles spécifie une stratégie différente,
elle sera silencieusement écrasée par `almost_one_allow_no_deny`. Il est impossible de déployer
une autre stratégie sans modifier le code.

---

#### Bug 3 — `pdp.clj:230-231` — Pagination O(n) catastrophique

```clojure
;; "Get more objects than needed to filter, then paginate
;; This is not optimal but works for now"
(kafka-pip/query-all-objects resource-class {})  ; charge TOUT en mémoire
```

La fonction `enrich-with-objects` charge l'intégralité des objets d'une classe Kafka en mémoire
pour ensuite filtrer et paginer côté applicatif. Avec des millions d'objets, cela provoque un OOM
et une latence prohibitive.

---

#### Bug 4 — `handler.clj:51` — Memory leak sur `rate-limit-store`

```clojure
(defonce rate-limit-store (atom {}))
```

Les IPs s'accumulent indéfiniment dans la map. Le nettoyage ne porte que sur les timestamps
à l'intérieur d'une entrée IP existante, jamais sur les clés IP elles-mêmes. Un serveur
sous trafic modéré consommera des gigaoctets en quelques jours.

---

#### Bug 5 — Double système de cache non intégré

- `pdp/passThroughCache` utilise **`cache.clj`** (TTL 10s hardcodé, LRU 100 entrées max)
- `local_cache.clj` (configurable via env vars, avec invalidation Kafka) existe mais
  **n'est jamais appelé par le PDP**
- Résultat : toute l'infrastructure Kafka d'invalidation de cache est inopérante en production,
  le PDP continue d'utiliser le cache basique aux paramètres figés

---

#### Bug 6 — `pdp.clj:164` — Check `globalPolicy` null après son utilisation

```clojure
evalglob (reduce ... (:rules globalPolicy))  ; peut lever NullPointerException
...
(if-not globalPolicy                          ; check arrivé trop tard !
  (throw ...))
```

Si `getGlobalPolicy` retourne `nil` (classe inconnue), l'appel `(:rules nil)` retourne
`nil` sans erreur, et `reduce` sur `nil` produit une valeur vide, masquant l'erreur réelle.
Le check de garde arrive trois lignes trop tard.

---

#### Bug 7 — `attfun.clj:79-85` — Opérateur `in` cassé sur les valeurs comma-separated

```clojure
(defn in [arg1 list]
  (contains? list arg1))  ; contains? sur une String cherche une clé, pas une valeur !
```

`(in "admin" "admin,user")` retourne `false` car `contains?` sur une `String` Java cherche
si `"admin"` est une **position/clé** dans la chaîne, pas une valeur dans une liste. Toute
règle utilisant `in` avec une valeur CSV est silencieusement incorrecte. `notin` souffre
du même problème.

---

### 🟠 IMPORTANT — Qualité & Sécurité

#### Problème 8 — `prp.clj:94-97` — Fillers hardcodés en mémoire

```clojure
(def subjectFillers (atom {"Person" {:type "internalFiller" :method "fillPerson"}...}))
(def resourceFillers (atom {"Note" {:type "urlFiller" :url "http://localhost:8080/NoteFiller"}}))
```

Ces configurations de développement (avec l'URL `localhost`) sont initialisées au démarrage et
écrasent potentiellement les vraies configurations. Elles doivent être externalisées dans le
fichier de config ou chargées depuis les PIPs déclarés.

---

#### Problème 9 — `jsonrule.clj` — Appels PIPs sans timeout

```clojure
(every? #(evalClause % ctxtwtype :subject) subjectClauses)
```

`evalClause` peut déclencher des appels à des PIPs externes (REST, LDAP). Sans timeout
par clause, une seule règle dont le PIP est lent bloque toute la décision. Le timeout
de 10s est configuré sur le pool de connexions HTTP mais pas au niveau de l'évaluation
d'une règle individuelle.

---

#### Problème 10 — `pip.clj:119-142` — CSV PIP relit le fichier à chaque appel

```clojure
(with-open [reader (io/reader file-path)]...)  ; O(n) à chaque décision
```

Le fichier CSV est ouvert, lu intégralement et parcouru séquentiellement pour chaque
appel de PIP. Pour un fichier de 10 000 lignes et 1 000 req/s, cela génère 10 000 000
d'opérations I/O par seconde sur ce seul PIP.

---

#### Problème 11 — `prp.clj:14-23` — H2 avec credentials hardcodés

```clojure
:subname "./resources/h2db"
:user    "sa"
:password ""
```

Les credentials de la base H2 sont hardcodés dans le code source. Même pour une base
embarquée, cela constitue une mauvaise pratique et un risque si le fichier `h2db` est
exfiltré.

---

#### Problème 12 — Pas d'autorisation renforcée sur les routes `/admin`

Les routes `/admin/reinit`, `/admin/reload_rules`, `/admin/reload_persons`,
`/admin/clearRDB/:class-name` ne vérifient que l'authentification (JWT ou API Key valide),
pas que l'appelant ait un rôle admin. N'importe quel utilisateur authentifié peut
déclencher un rechargement complet du PDP.

---

#### Problème 13 — `pdp.clj` — `evalRequest` et `isAuthorized` dupliquent la logique

Les deux fonctions effectuent les mêmes opérations (validation de la requête, récupération
de la politique globale, évaluation des règles, application du cache) avec de légères
différences comportementales. Cette duplication crée un risque de divergence silencieuse.

---

#### Problème 14 — `auth.clj` — Pas de rotation des secrets JWT/API Key

```clojure
(def jwt-secret (or (System/getenv "JWT_SECRET") (throw ...)))
(def api-key    (or (System/getenv "API_KEY")    (throw ...)))
```

Les secrets sont des `def` évalués au démarrage de la JVM. Un changement de secret
nécessite un redémarrage complet du serveur. Il n'y a pas de mécanisme de rotation
à chaud, ce qui complique les procédures de rotation de secrets (SOC2, PCI-DSS).

---

#### Problème 15 — Pas de `jti` (JWT ID) pour la révocation de tokens

Les JWT acceptés ne contiennent pas d'identifiant unique (`jti`). Il est impossible
de révoquer un token compromis sans changer le secret global (qui invalide tous les
tokens en circulation).

---

## 3. Lacunes vs Concurrents de Référence

| Capacité | OPA | Cedar | Cerbos | SpiceDB | **Autho** |
|----------|:---:|:-----:|:------:|:-------:|:---------:|
| Langage de politiques expressif | Rego | Cedar typé | YAML+CEL | Schéma | JSON clauses ✅ |
| Schéma d'entités typé | ❌ | ✅ | Partiel | ✅ | ❌ |
| Stratégies de résolution multiples | 1 | N/A | Deny-override | N/A | **1 (hardcodé)** |
| Rôles dérivés (RBAC natif) | Via Rego | Via schema | ✅ natif | Via relations | ❌ |
| Moteur de relations (graph) | ❌ | Partiel | Partiel | ✅ natif | ❌ |
| Audit trail structuré | ✅ | ✅ | ✅ | ✅ | ❌ (planifié) |
| Métriques Prometheus | ✅ | ✅ | ✅ | ✅ | ❌ (planifié) |
| Framework de test de politiques | ✅ | ✅ | ✅ | ❌ | ❌ |
| Compilation de règles | Wasm/Go | Rust natif | Go | Go | ❌ (interprété) |
| Tracing distribué (OTEL) | ✅ | ✅ | ✅ | ✅ | ❌ |
| Hot-reload des politiques | ✅ | ❌ | ✅ | ❌ | Partiel |
| Multi-tenancy | ✅ | ✅ | ✅ | ✅ | ❌ |
| **Time-travel forensique** | ❌ | ❌ | ❌ | ❌ | **✅ UNIQUE** |
| **Délégation dynamique** | ❌ | ❌ | ❌ | ❌ | **✅ UNIQUE** |
| **Anti-cycles sur délégation** | ❌ | ❌ | ❌ | ❌ | **✅ UNIQUE** |

### Analyse des concurrents

#### OPA (Open Policy Agent)
Le leader incontesté pour les cas d'usage cloud-native. Son langage Rego est Turing-complet
et permet d'exprimer n'importe quelle politique. Son point faible : Rego a une courbe
d'apprentissage abrupte et les PIPs doivent être gérés en dehors du moteur. Autho surpasse
OPA sur l'intégration native des PIPs et la délégation.

#### Cedar (AWS)
Le plus rigoureux sur la correction des politiques. Son modèle d'entités typé et son analyse
statique de politiques garantissent la cohérence. Son point faible : fortement couplé à l'écosystème
AWS, pas de support natif des PIPs externes ni de délégation.

#### Cerbos
Le plus accessible pour les développeurs. Son format YAML est intuitif, ses rôles dérivés sont
puissants. Son point faible : pas de support du time-travel, pas de délégation.

#### SpiceDB / AuthZed
Le plus puissant pour les autorisations basées sur les relations (Zanzibar-style). Idéal pour
les graphes de permissions complexes. Son point faible : modèle uniquement relationnel, pas
d'évaluation d'attributs ABAC, pas de délégation temporelle.

---

## 4. Roadmap Proposée : De Bon à Meilleur du Marché

### Phase 1 — Correction des Bugs Critiques (Semaines 1-2)

Ces corrections sont **obligatoires avant tout déploiement production**. Elles ne changent
pas l'architecture, seulement la correction de comportements erronés.

| # | Action | Fichier | Effort |
|---|--------|---------|--------|
| 1 | Retirer `(defn role [] "Professeur")` ou implémenter via PIP réel | `attfun.clj:47-50` | 1h |
| 2 | Corriger `in`/`notin` : `str/split` + `set` pour la sémantique CSV | `attfun.clj:79-85` | 2h |
| 3 | Corriger le check `globalPolicy` null dans `isAuthorized` (avant usage) | `pdp.clj:159-165` | 1h |
| 4 | Fixer memory leak `rate-limit-store` : purger les IPs inactives (LRU sur IPs) | `handler.clj:51` | 2h |
| 5 | Brancher `local_cache.clj` dans le PDP à la place de `cache.clj` | `pdp.clj:95-100` | 3h |
| 6 | Corriger la pagination Kafka : push-down filtering vers RocksDB | `pdp.clj:220-242` | 4h |
| 7 | Externaliser les fillers hardcodés dans le fichier de config | `prp.clj:94-97` | 2h |
| 8 | Externaliser la strategy depuis le fichier de règles (ne plus la hardcoder) | `prp.clj:165` | 1h |
| 9 | Protéger les routes `/admin` avec vérification de rôle admin | `handler.clj:408-443` | 3h |

---

### Phase 2 — Observabilité & Résilience (Semaines 3-5)

Items P1 de la roadmap existante, avec specs précises.

#### Métriques Prometheus (`src/autho/metrics.clj`)

Intégration via `io.micrometer/micrometer-registry-prometheus` :

```clojure
;; Métriques métier prioritaires
autho_decision_duration_seconds{resource_class, effect}  ; histogram
autho_decision_total{effect="allow|deny", resource_class} ; counter
autho_pip_duration_seconds{pip_type="rest|kafka|csv|ldap"} ; histogram
autho_pip_errors_total{pip_type, error_type}              ; counter
autho_cache_hits_total{cache_type}                        ; counter
autho_cache_misses_total{cache_type}                      ; counter
autho_cache_size{cache_type}                              ; gauge
autho_rate_limit_rejected_total                           ; counter
autho_delegation_depth{resource_class}                    ; histogram
```

Endpoints à exposer :
- `GET /metrics` — format Prometheus scrape
- `GET /metrics/json` — format JSON pour dashboards custom

#### Circuit Breakers (`src/autho/circuit_breaker.clj`)

Par dépendance externe, avec états `closed → open → half-open → closed` :

```clojure
{:rest-pip  {:threshold 5  :timeout 5000  :reset-timeout 30000}
 :ldap-pip  {:threshold 3  :timeout 8000  :reset-timeout 60000}
 :kafka-pip {:threshold 10 :timeout 2000  :reset-timeout 15000}}
```

Comportement en `open` : retourner la dernière valeur connue (stale cache) ou lever
une exception gérée proprement selon la criticité du PIP.

#### Timeout par appel PIP

Chaque déclaration PIP dans `pips.edn` doit accepter un champ `:timeout-ms` :

```edn
{:class "User" :attributes [:role :department]
 :pip {:type :rest :url "http://ldap-service/users" :timeout-ms 3000}}
```

#### Tracing distribué OpenTelemetry

- Générer un `request-id` UUID à l'entrée de chaque requête HTTP
- Propager via header `X-Request-ID` aux appels PIPs REST
- Log structuré avec `request-id` sur chaque décision et appel PIP
- Export vers Jaeger/Zipkin via OTEL collector

---

### Phase 3 — Moteur de Règles Avancé (Semaines 6-10)

C'est ici qu'autho peut **dépasser OPA et Cedar** sur son terrain propre.

#### Stratégies de résolution complètes

Implémenter les 5 stratégies XACML standard dans `pdp.clj` :

| Stratégie | Sémantique |
|-----------|-----------|
| `deny-overrides` | Un seul deny suffit à refuser |
| `permit-overrides` | Un seul allow suffit à autoriser |
| `first-applicable` | Première règle applicable (par priorité) |
| `only-one-applicable` | Exactement une règle doit matcher |
| `almost_one_allow_no_deny` | Existant — allow sans deny concurrent |

La stratégie doit être lue depuis le fichier de règles, non hardcodée.

#### Schéma d'entités typé (`src/autho/schema.clj`)

Permettre de déclarer les attributs attendus par classe d'entité :

```json
{
  "entitySchemas": {
    "User": {
      "attributes": {
        "role":           {"type": "string", "enum": ["admin", "user", "guest"]},
        "department":     {"type": "string"},
        "clearance_level":{"type": "integer", "min": 0, "max": 5}
      }
    },
    "Document": {
      "attributes": {
        "classification": {"type": "string", "enum": ["public", "internal", "confidential"]},
        "owner":          {"type": "string"},
        "created_at":     {"type": "date"}
      }
    }
  }
}
```

Bénéfices :
- Validation des règles à la création (pas de typo silencieux)
- Meilleurs messages d'erreur lors des décisions
- Documentation auto-générée des politiques
- Foundation pour l'analyse statique de politiques

#### Compilation des règles au démarrage

Au lieu d'interpréter les clauses JSON à chaque décision, les compiler en fonctions Clojure
lors du chargement (`prp/initf`) :

```clojure
;; Avant (interprété à chaque décision)
(evalClause ["=" "$.role" "admin"] ctx :subject)

;; Après (compilé au démarrage)
(fn [ctx] (= (get-in ctx [:subject :role]) "admin"))
```

Gain estimé : **×10 à ×50 sur la latence d'évaluation des règles pures**.

#### RBAC natif en couche sur ABAC

Permettre de déclarer des rôles et leur héritage séparément des politiques :

```edn
{:roles
 {:admin      {:inherits [:moderator :user]}
  :moderator  {:inherits [:user]}
  :user       {:inherits []}
  :superadmin {:inherits [:admin]}}}
```

Les règles peuvent ensuite référencer les rôles hérités. Cela simplifie les politiques
sans perdre la puissance ABAC.

#### Framework de test de politiques

Endpoint `POST /v1/policies/simulate` :

```json
{
  "policy": { ... },
  "testCases": [
    {
      "subject":   {"id": "alice", "role": "admin"},
      "resource":  {"class": "Document", "classification": "internal"},
      "operation": "read",
      "expected":  "allow"
    }
  ]
}
```

Réponse détaillée avec règle matchée, stratégie appliquée, et diagnostic en cas d'échec.
Permet de valider les politiques **avant** leur déploiement.

---

### Phase 4 — Features Différenciantes (Semaines 11-20)

#### Audit Trail Immuable (`src/autho/audit.clj`)

Architecture append-only avec intégrité vérifiable :

```clojure
{:timestamp    "2026-03-18T10:30:00Z"
 :request_id   "uuid-v4"
 :subject_id   "user123"
 :resource_class "Document"
 :resource_id  "doc456"
 :operation    "write"
 :decision     "deny"
 :reason       "no_matching_allow_rule"
 :policy_ids   ["pol-001"]
 :pip_calls    [{:type :rest :url "..." :duration_ms 12}]
 :prev_hash    "sha256(entrée précédente)"
 :hash         "sha256(prev_hash + données)"
 :signature    "HMAC-SHA256(secret, hash)"}
```

Stockage Kafka (append-only, durable, rejouable). Endpoints :
- `GET /v1/audit/search?subject=&resource=&from=&to=` — recherche
- `GET /v1/audit/verify` — vérification d'intégrité de la chaîne
- `GET /v1/audit/export?format=json|csv|cef` — export SIEM

#### Capitaliser sur la Feature Unique de Time-Travel

Autho est le **seul serveur d'autorisation du marché** avec du forensic temporel natif.
Cette feature mérite d'être portée au premier plan :

- `POST /v1/forensic/replay` — reproduire exactement une décision passée avec les données
  du sujet/ressource telles qu'elles étaient à ce moment
- `POST /v1/forensic/diff` — comparer les décisions avant/après un changement de politique
  (shadow mode : ancienne et nouvelle politique évaluées en parallèle)
- `POST /v1/forensic/exposure` — "quelles ressources Alice aurait-elle pu accéder entre
  le 1er et le 15 mars ?"
- Dashboard de forensic pour les équipes de sécurité et compliance

#### Policy Hot-Reload avec Versioning

- Git-backed policy store : les politiques vivent dans un dépôt Git
- Reload automatique sur push (webhook)
- Rollback instantané vers version précédente via `POST /admin/rollback?version=abc123`
- Blue/green policy deployment : tester une nouvelle politique en shadow mode avant activation

#### Moteur de Relations Léger (Zanzibar-lite)

Complémentaire à l'ABAC existant, pour modéliser les relations entre entités :

```
tuple: (user:alice, owner, document:report1)
tuple: (group:admins, member, user:bob)
tuple: (document:report1, parent, folder:finance)
```

Check : `isAuthorized(alice, read, report1)` traverse le graphe :
- alice est owner de report1 → règle "owner peut lire" → allow

Cela couvre les cas d'usage Google Docs / GitHub-style inaccessibles avec ABAC pur.

#### Rate Limiting Avancé Multi-Niveau

```clojure
{:tiers
 {:anonymous     {:rpm 10   :rph 50}
  :authenticated {:rpm 100  :rph 1000}
  :trusted-app   {:rpm 5000 :rph 50000}
  :admin         {:rpm 10000 :rph 100000}}}
```

Avec sliding window précis, headers standard (`X-RateLimit-Limit`, `X-RateLimit-Remaining`,
`X-RateLimit-Reset`) et burst allowance configurable.

#### SDK Clients

Priorité aux langages les plus utilisés avec autho :

1. **Java/Kotlin** — client synchrone + asynchrone (CompletableFuture/Coroutines)
2. **Python** — client sync + async (asyncio)
3. **Go** — client idiomatique avec context/timeout
4. **TypeScript/Node.js** — client avec types générés depuis OpenAPI spec

---

## 5. Quick Wins Architecturaux (Haute Valeur, Faible Effort)

| Item | Impact | Effort estimé | Fichier cible |
|------|:------:|:-------------:|---------------|
| Retirer stub `role "Professeur"` | 🔴 Critique | 1h | `attfun.clj:47-50` |
| Corriger opérateur `in`/`notin` | 🔴 Critique | 2h | `attfun.clj:79-85` |
| Brancher `local_cache` dans PDP | 🟠 Haute | 3h | `pdp.clj:95-100` |
| Fixer memory leak rate-limit | 🟠 Haute | 2h | `handler.clj:51` |
| Externaliser strategy dans prp | 🟠 Haute | 1h | `prp.clj:165` |
| Corriger check `globalPolicy` null | 🟠 Haute | 1h | `pdp.clj:159-165` |
| Timeout PIP configurable par décl. | 🟠 Haute | 4h | `pip.clj` + `pips.edn` |
| Protéger routes admin (rôle requis) | 🟠 Haute | 3h | `handler.clj:408-443` |
| CSV PIP : charger en mémoire au démarrage | 🟡 Moyenne | 3h | `pip.clj:119-142` |
| Short-circuit évaluation jsonrule | 🟡 Moyenne | 4h | `jsonrule.clj:91-98` |
| Métriques Prometheus (set de base) | 🟡 Moyenne | 2j | nouveau `metrics.clj` |
| Circuit breaker PIPs | 🟡 Moyenne | 3j | nouveau `circuit_breaker.clj` |

---

## 6. Vision Finale : Ce Qui Peut Rendre Autho Unique

Aucun serveur d'autorisation du marché ne combine aujourd'hui :

| Pilier | Status |
|--------|--------|
| **ABAC expressif** — opérateurs temporels, ensembles, logiques, PIPs multi-sources | ✅ Déjà là |
| **Délégation dynamique avec anti-cycles** | ✅ Déjà là — seul sur le marché |
| **Time-travel forensique** — qui avait accès à quoi, à quelle date exacte | ✅ Déjà là — seul sur le marché |
| **Audit trail immuable vérifiable** | ⏳ À construire |
| **RBAC + ABAC hybride** | ⏳ À construire |
| **Moteur de relations léger** | ⏳ À construire |
| **Framework de test de politiques** | ⏳ À construire |

### La proposition de valeur unique d'autho

> *"Le seul serveur d'autorisation qui vous permet de savoir non seulement qui peut faire quoi maintenant,
> mais qui pouvait faire quoi à n'importe quel moment dans le passé, pourquoi une délégation a
> été accordée ou refusée, et de prouver l'intégrité de cet historique à un auditeur."*

**OPA** répond à "quelle est la politique ?"
**Cedar** répond à "cette politique est-elle correcte ?"
**SpiceDB** répond à "quelle est la relation ?"
**Autho** répond à "qu'est-ce qui s'est passé, pourquoi, et est-ce que c'était légitime ?"

C'est une niche à très haute valeur pour les secteurs régulés : finance, santé, industrie,
gouvernement — partout où la preuve de conformité et le forensic d'accès sont obligatoires.

---

## Annexe — Métriques de Performance Cibles après Optimisations

| Métrique | Actuel estimé | Cible Phase 1-2 | Cible Phase 3 |
|----------|:-------------:|:---------------:|:-------------:|
| Latence P50 (cache hit) | ~50ms | <5ms | <1ms |
| Latence P50 (cache miss) | ~200ms | <50ms | <10ms |
| Latence P95 | ~500ms | <100ms | <20ms |
| Latence P99 | >1s | <200ms | <50ms |
| Throughput (instance unique) | ~100 req/s | >2 000 req/s | >10 000 req/s |
| Cache hit rate | ~0% | >80% | >95% |
| Memory footprint | N/A | <512MB | <256MB |

Les gains de Phase 3 (compilation des règles, cache intégré) sont les plus significatifs
et peuvent réduire la latence d'un ordre de grandeur.

---

*Document maintenu dans `/docs/ANALYSE_TECHNIQUE.md`*
*Dernière mise à jour : 18 Mars 2026*
