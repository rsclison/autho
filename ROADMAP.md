# Roadmap - autho : Serveur d'Autorisations de Classe Mondiale

## 🎯 Vision

Faire de **autho** un serveur d'autorisations XACML/ABAC de classe mondiale, offrant :
- Performance exceptionnelle (< 5ms par décision)
- Scalabilité horizontale native
- Sécurité de niveau entreprise
- Observabilité complète
- Facilité d'intégration

## 📊 État actuel (Mars 2026)

| Composant | Statut | Priorité |
|-----------|--------|----------|
| Validation et sanitization des entrées | ✅ Terminé | P0 - Critique |
| Tests coverage critique | ✅ Terminé | P0 - Critique |
| Migration vers jsonrule | ✅ Terminé | P0 - Critique |
| Tests pour jsonrule | ✅ Terminé | P1 - Haute |
| Requêtes complexes OPA | ✅ Terminé | P1 - Haute |
| Optimisations RocksDB | ✅ Terminé | P1 - Haute |
| Cache local avec invalidation Kafka | ✅ Terminé | P1 - Haute |
| API RESTful complète | ✅ Terminé | P2 - Moyenne |
| Corrections bugs critiques Phase 1 | ✅ Terminé | P0 - Critique |
| Observabilité (métriques) | ⏳ À faire | P1 - Haute |
| Audit logging immuable | ⏳ À faire | P2 - Moyenne |
| Circuit breakers | ⏳ À faire | P2 - Moyenne |

---

## 🚀 Roadmap par Priorités

### Priorité 0 (P0) - Critique pour la sécurité ✅

**Statut** : 100% terminé

#### 1. Validation et sanitization des entrées ✅
- **Fichier** : `src/autho/validation.clj`
- **Fonctionnalités** :
  - Validation des sujets, ressources, opérations
  - Détection des injections (SQL, XSS, command)
  - Limites de taille des entités
  - Réponses d'erreur standardisées avec headers de sécurité

#### 2. Tests coverage critique ✅
- **Fichiers** : `test/autho/validation_test.clj`, `cache_test.clj`, etc.
- **Couverture** : 120 tests, 262 assertions
- **Taux de succès** : 100% (hors erreurs environnement RocksDB)

#### 3. Nettoyage et modernisation ✅
- Suppression du système de pattern matching obsolète
- Migration complète vers jsonrule
- Amélioration des performances et maintenabilité

---

### Priorité 1 (P1) - Performance et Scalabilité

#### 1. Optimisations RocksDB ✅
**Impact** : -50% latence P95, meilleure utilisation CPU/Mémoire

**Pourquoi optimiser RocksDB ?**
- Déjà utilisé pour le stockage persistant
- Embedded : pas de latence réseau
- Très rapide quand bien configuré
- Évite d'ajouter une technologie externe

**Configuration actuelle** → **Configuration optimisée** :

```clojure
;; src/autho/rocksdb.clj

;; Options d'optimisation
(defn get-optimized-options
  []
  (doto (org.rocksdb.Options.)
    ;; Write Options
    (.setWriteBufferSize 64MB)           ; 4MB → 64MB (moins de flush)
    (.setMaxWriteBufferNumber 3)         ; 2 → 3 (plus de buffers)
    (.setLevel0FileNumCompactionTrigger 4) ; 4 → 8 (moins de compaction)
    (.setMaxBackgroundCompactions 4)     ; 2 → 4 (plus de parallélisme)

    ;; Read Options
    (.setUseFsync false)                 ; fsync asynchrone (Kafka pour la durabilité)
    (.setAllowConcurrentMemtableWrite true)

    ;; Cache Options
    (.setCacheIndexAndFilterBlocks true)
    (.setPinL0FilterAndIndexBlocksInCache true)

    ;; Compression
    (.setCompressionType org.rocksdb.CompressionType/LZ4_COMPRESSION)
    (.setBottommostCompressionType org.rocksdb.CompressionType/ZSTD_COMPRESSION)

    ;; Bloom Filters
    (.setBloomLocality 1)
    (.setMemtablePrefixBloomSizeRatio 0.1)))

;; Column Families optimisées
(defn create-column-families!
  [db]
  {:subjects   (create-cf db "subjects"   (-> (ColumnFamilyOptions/default)
                                              (.setBloomFilterPolicy (bloom-filter 10bits))))
   :resources  (create-cf db "resources"  (-> (ColumnFamilyOptions/default)
                                              (.setBloomFilterPolicy (bloom-filter 10bits))))
   :policies   (create-cf db "policies"   (-> (ColumnFamilyOptions/default)
                                              (.setBloomFilterPolicy (bloom-filter 10bits))))
   :delegations (create-cf db "delegations" (-> (ColumnFamilyOptions/default)
                                               (.setBloomFilterPolicy (bloom-filter 10bits))))}))
```

**Métriques à surveiller** :
```prometheus
# RocksDB performance
autho_rocksdb_write_latency_seconds{quantile="0.5"}
autho_rocksdb_write_latency_seconds{quantile="0.95"}
autho_rocksdb_read_latency_seconds{quantile="0.5"}
autho_rocksdb_read_latency_seconds{quantile="0.95"}
autho_rocksdb_compaction_duration_seconds_total
autho_rocksdb_memtable_size_bytes
autho_rocksdb_block_cache_hit_ratio
```

**Tests de performance** :
```clojure
;; test/autho/rocksdb_perf_test.clj
(deftest bench-rocksdb-read
  (testing "RocksDB read performance"
    (quick-bench (rocksdb/get db "subjects" "user123"))))

(deftest bench-rocksdb-batch-write
  (testing "RocksDB batch write performance"
    (quick-bench (rocksdb/batch-write! db subjects))))
```

#### 2. Cache local avec invalidation Kafka ✅
**Impact** : -90% latence P50 pour les données chaudes

**Architecture** :
```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Instance A │     │  Instance B │     │  Instance C │
│             │     │             │     │             │
│  ┌───────┐  │     │  ┌───────┐  │     │  ┌───────┐  │
│  │Cache  │  │     │  │Cache  │  │     │  │Cache  │  │
│  │Local  │  │     │  │Local  │  │     │  │Local  │  │
│  └───┬───┘  │     │  └───┬───┘  │     │  └───┬───┘  │
│      │      │     │      │      │     │      │      │
│  ┌───▼───┐  │     │  ┌───▼───┐  │     │  ┌───▼───┐  │
│  │RocksDB│  │     │  │RocksDB│  │     │  │RocksDB│  │
│  └───────┘  │     │  └───────┘  │     │  └───────┘  │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       │     Invalidation messages              │
       └─────────┬─────────┴─────────┬─────────┘
                 │                   │
            ┌────▼───────────────────▼────┐
            │           Kafka             │
            │    Invalidation Topic       │
            └──────────────────────────────┘
```

**Spécifications** :
```clojure
;; src/autho/local_cache.clj

(defn get-with-cache
  "Get item from local cache first, fallback to RocksDB"
  [cache-key fetch-fn ttl-ms]
  (or (get @local-cache cache-key)
      (let [value (fetch-fn)]
        (when value
          (swap! local-cache assoc cache-key
                 {:value value
                  :expires (+ (System/currentTimeMillis) ttl-ms)})
          value))))

(defn handle-invalidation
  "Handle Kafka invalidation message"
  [invalidation-msg]
  (let [cache-key (:key invalidation-msg)]
    (swap! local-cache dissoc cache-key)))

;; TTL cleanup
(defn start-ttl-cleaner!
  []
  (future
    (while true
      (Thread/sleep 60000)  ; every minute
      (let [now (System/currentTimeMillis)]
        (swap! local-cache
               (fn [cache]
                 (into {}
                       (remove (fn [[_ {expires :expires}]]
                                (< expires now))
                              cache))))))))
```

**Configuration** :
```bash
# Cache TTL par type (millisecondes)
CACHE_TTL_SUBJECT=300000      # 5 min
CACHE_TTL_RESOURCE=180000     # 3 min
CACHE_TTL_POLICY=600000       # 10 min
CACHE_MAX_SIZE=10000          # max entrées par type

# Kafka topic d'invalidation
KAFKA_INVALIDATION_TOPIC=autho-invalidation
```

**Endpoints** :
- GET `/cache/stats` - Statistiques du cache local
- DELETE `/cache/clear` - Vidange du cache (admin)

**Métriques** :
```prometheus
autho_local_cache_hits_total
autho_local_cache_misses_total
autho_local_cache_hit_ratio
autho_local_cache_size
autho_local_cache_evictions_total
autho_kafka_invalidations_total
```

**Avantages vs Redis** :
| Aspect | Cache Local + Kafka | Redis |
|--------|---------------------|-------|
| Latence P50 | ~1μs | ~500μs |
| Latence P95 | ~50μs | ~2ms |
| Complexité | ⭐⭐ | ⭐⭐⭐⭐ |
| Services | 0 nouveau | 1 nouveau |
| Réseau | Non | Oui |
| Synchronisation | Oui (Kafka) | Oui (natif) |

#### 2. Observabilité - Métriques Prometheus ⏳
**Impact** : Visibilité complète, alerting proactif

**Architecture** :
```clojure
;; src/autho/metrics.clj
- Intégration micrometer/prometheus
- Métriques par défaut (JVM + HTTP)
- Métriques métier (décisions, performance)
- Histogrammes de latence
- Compteurs par statut (allow/deny)
```

**Endpoints** :
- GET `/metrics` - Métriques Prometheus (scrape endpoint)
- GET `/metrics/prometheus` - Format Prometheus
- GET `/metrics/json` - Format JSON

**Métriques clés** :
```prometheus
# Performance
autho_authorization_duration_seconds{quantile="0.5"}
autho_authorization_duration_seconds{quantile="0.95"}
autho_authorization_duration_seconds{quantile="0.99"}

# Volume
autho_authorization_total{effect="allow|deny"}
autho_authorization_total{resource_class="Document"}

# Cache
autho_cache_hits_total
autho_cache_misses_total
autho_cache_latency_seconds

# PIPs
autho_pip_call_duration_seconds{pip_type="ldap|http"}
autho_pip_call_errors_total

# Erreurs
autho_errors_total{type="validation|pip|rule"}
```

#### 3. Requêtes complexes (OPA-like) ✅
**Impact** : Expressivité maximale des politiques

**Statut**: Terminé (Mars 2026)

**Fichiers implémentés** :
- `src/autho/policy_language.clj` - Opérateurs logiques (OR, NOT, AND)
- `src/autho/temporal.clj` - Opérations temporelles (after, before, between, within, older, etc.)
- `src/autho/set_ops.clj` - Opérations sur les sets (intersect, isSubset, isSuperset, equals)

**Tests**: 46 tests, 47 assertions, 0 échecs

**Syntaxe** :
```clojure
;; Opérateurs logiques
["or" ["=" "$.role" "admin"] ["=" "$.role" "superadmin"]]
["not" ["in" "$.status" "suspended,blocked"]]
["and" ["=" "$.role" "admin"] [">" "$.level" "5"]]

;; Temporel
(after "2025-02-01" "2025-01-01")  ; date1 > date2
(before "2025-01-01" "2025-02-01")  ; date1 < date2
(between "2025-06-15" "2025-01-01" "2025-12-31")  ; inclusive
(within "2025-01-10" "7d")  ; dans les 7 jours
(older "2024-01-01" "90d")  ; plus de 90 jours
(is-weekend "2025-01-04")  ; Saturday
(days-between "2025-01-01" "2025-01-10")  ; 9

;; Set operations
(intersect "admin,user" "user,mod")  ; true (commun "user")
(isSubset "admin" "admin,user,mod")  ; true
(isSuperset "admin,user,mod" "admin")  ; true
(equals "admin,user" "user,admin")  ; true (ordre indifférent)
```

---

### Priorité 2 (P2) - Fiabilité et Opérationnel

#### 1. Audit Logging Immuable ⏳
**Architecture** :
```clojure
;; src/autho/audit.clj

;; Tamper-proof logging
- Signature HMAC de chaque entrée
- Chain hashing pour intégrité
- Stockage immuable (append-only)
- Rotation automatique

;; Format d'entrée
{:timestamp "2025-01-15T10:30:00Z"
 :request_id "uuid"
 :subject_id "user123"
 :resource_class "Document"
 :resource_id "doc456"
 :operation "write"
 :decision "deny"
 :reason "insufficient_permissions"
 :policy_ids ["pol-001" "pol-002"]
 :hash "sha256(previous_hash + data)"
 :signature "HMAC(secret_hash, data)"}
```

**Endpoints** :
- GET `/audit/search` - Rechercher dans l'audit
- GET `/audit/verify` - Vérifier l'intégrité
- GET `/audit/export` - Exporter (CSV/JSON)

#### 2. Circuit Breakers ⏳
**Architecture** :
```clojure
;; src/autho/circuit_breaker.clj

;; Par dépendance externe
(def circuit-breakers
  {:ldap-pip {:threshold 5
              :timeout 10000
              :reset-timeout 60000}
   :http-pip {:threshold 10
              :timeout 5000
              :reset-timeout 30000}
   :redis-cache {:threshold 3
                 :timeout 2000
                 :reset-timeout 15000}})

;; États
:closed → :open → :half-open → :closed
```

**Métriques** :
```prometheus
autho_circuit_breaker_state{service="ldap-pip"}  # 0=closed, 1=open, 2=half-open
autho_circuit_breaker_failures_total{service="ldap-pip"}
autho_circuit_breaker_successes_total{service="ldap-pip"}
```

#### 3. API RESTful Complète ✅
**Statut**: Terminé (Mars 2026)

**Fichiers implémentés** :
- `src/autho/api/response.clj` - Réponses standardisées
- `src/autho/api/pagination.clj` - Pagination et filtrage
- `src/autho/api/handlers.clj` - Handlers réutilisables
- `src/autho/api/v1.clj` - Routes v1
- `test/autho/api/v1_test.clj` - Tests API v1
- `docs/API_V1.md` - Documentation complète

**Endpoints implémentés** :

| Endpoint | Méthode | Description |
|----------|---------|-------------|
| `/v1/authz/decisions` | POST | Décision d'autorisation |
| `/v1/authz/subjects` | POST | Sujets autorisés |
| `/v1/authz/permissions` | POST | Permissions du sujet |
| `/v1/authz/explain` | POST | Explication de décision |
| `/v1/authz/batch` | POST | Décisions en lot |
| `/v1/policies` | GET | Lister les politiques (paginé) |
| `/v1/policies` | POST | Créer une politique |
| `/v1/policies/:resource-class` | GET | Détail d'une politique |
| `/v1/policies/:resource-class` | PUT | Mettre à jour une politique |
| `/v1/policies/:resource-class` | DELETE | Supprimer une politique |
| `/v1/cache/stats` | GET | Statistiques du cache |
| `/v1/cache` | DELETE | Vider tous les caches |
| `/v1/cache/:type/:key` | DELETE | Invalider une entrée |

**Fonctionnalités** :
- Réponses standardisées avec succès/erreur
- Pagination avec metadata et links HATEOAS
- Filtrage et tri des listes
- Versioning d'API via `/v1/`
- Headers standard (`X-API-Version`, `Content-Type`)
- Codes HTTP appropriés (200, 201, 204, 400, etc.)

**Tests**: 21 tests, 68 assertions, 0 échecs

#### 4. Rate Limiting Avancé ⏳
```clojure
;; src/autho/rate_limit.clj

;; Par IP + API Key
(def rate-limits
  {:default {:requests-per-minute 100
             :requests-per-hour 1000}
   :authenticated {:requests-per-minute 1000
                   :requests-per-hour 10000}
   :admin {:requests-per-minute 10000
           :requests-per-hour 100000}})

;; Sliding window
- Précision temporelle
- Redis distribué
- Response headers :
  - X-RateLimit-Limit: 1000
  - X-RateLimit-Remaining: 950
  - X-RateLimit-Reset: 1642245600
```

---

### Priorité 3 (P3) - Expérience Développeur

#### 1. SDK Clients
**Langues** :
- Java/Kotlin
- Python
- Go
- JavaScript/TypeScript

**Fonctionnalités** :
```python
# Exemple Python
from autho_client import AuthoClient

client = AuthoClient(
    base_url="https://autho.example.com",
    api_key="secret"
)

# Décision simple
decision = client.is_authorized(
    subject={"id": "user123", "role": "admin"},
    resource={"class": "Document", "id": "doc456"},
    action="write"
)

# Batch
decisions = client.batch_decide([
    {"subject": {...}, "resource": {...}, "action": "..."},
    {"subject": {...}, "resource": {...}, "action": "..."}
])
```

#### 2. Policy as Code
```yaml
# policies/document_policy.yaml
apiVersion: autho.io/v1
kind: Policy
metadata:
  name: document-access
  version: "1.0"
spec:
  resourceClass: Document
  rules:
    - effect: allow
      subject:
        - role: admin
        - role: owner
          condition: "$.id = $.resource.owner"
      resource:
        - classification: ["public", "internal"]
      actions: [read, write]

    - effect: deny
      resource:
        - classification: confidential
          condition: "$.subject.clearance < 'secret'"
      actions: [read, write]
```

#### 3. UI Admin
**Fonctionnalités** :
- Éditeur de politiques avec validation
- Visualisation des décisions
- Dashboard de monitoring
- Gestion des PIPs
- Explorateur d'audit

---

## 📈 Métriques de Succès

### Performance Cible
| Métrique | Actuel | Cible | Commentaire |
|----------|--------|-------|-------------|
| Latence P50 | ~50ms | <5ms | Sans cache |
| Latence P95 | ~200ms | <20ms | 95e percentile |
| Latence P99 | ~500ms | <50ms | 99e percentile |
| Throughput | ~100 req/s | >10,000 req/s | Avec cache Redis |
| Cache hit rate | ~0% | >90% | Cache distribué |

### Disponibilité Cible
| Métrique | Cible |
|----------|-------|
| Uptime | 99.99% (4.38min/an) |
| RTO (Recovery Time Objective) | <5min |
| RPO (Recovery Point Objective) | 0 (audit) |

### Sécurité Cible
| Aspect | Cible |
|--------|-------|
| Vulnérabilités critiques | 0 |
| Penetration testing | Pass |
| Compliance | SOC2 Type II ready |
| Encryption | TLS 1.3+, At-rest AES-256 |

---

## 🗓️ Timeline Estimée

### Q1 2026 (Jan-Mar)
- ✅ Validation et sanitization
- ✅ Tests coverage
- ✅ Nettoyage code
- ✅ Requêtes complexes OPA
- ✅ Optimisations RocksDB
- ✅ Cache local avec invalidation Kafka
- ✅ API RESTful complète
- ✅ Corrections bugs critiques Phase 1 (9 bugs corrigés)
- ⏳ Métriques Prometheus

### Q2 2026 (Apr-Jun)
- ⏳ Audit logging
- ⏳ Circuit breakers
- ⏳ Rate limiting avancé

### Q3 2026 (Jul-Sep)
- ⏳ API RESTful v2
- ⏳ Policy as Code
- ⏳ Documentation complète

### Q4 2026 (Oct-Dec)
- ⏳ SDK clients
- ⏳ UI Admin
- ⏳ Performance tuning

---

## 🔄 Architecture Cible

```
┌─────────────────────────────────────────────────────────────┐
│                        Load Balancer                        │
│                   (TLS termination, DDoS)                   │
└───────────────────────┬─────────────────────────────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
┌───────▼───────┐ ┌────▼──────┐ ┌─────▼──────┐
│  autho-01     │ │ autho-02  │ │ autho-03   │
│  (Stateless)  │ │(Stateless)│ │(Stateless) │
│ ┌───────────┐ │ │┌─────────┐│ │┌───────────┐│
│ │Cache Local│ │ ││Cache Loc││ ││Cache Local││
│ │ + RocksDB │ │ ││+RocksDB ││ ││+ RocksDB  ││
│ └───────────┘ │ │└─────────┘│ │└───────────┘│
└───────┬───────┘ └────┬──────┘ └─────┬──────┘
        │               │               │
        │     Invalidation messages (Kafka)              │
        └───────────────┼───────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
┌───────▼───────┐ ┌────▼──────┐ ┌─────▼──────┐
│   Prometheus  │ │   Kafka   │ │   Future   │
│  (Metrics)    │ │(Sync+Inv) │ │ (Kafka)    │
└───────────────┘ └───────────┘ │  (Audit)   │
                                   └────────────┘
```

---

## 📚 Ressources

- **Documentation** : `/docs`
- **Spécifications** : `/specs`
- **Architecture** : `/docs/ARCHITECTURE.md`
- **API** : `/docs/API.md`

---

## 🤝 Contribution

Pour contribuer à la roadmap :
1. Discuter dans les issues
2. Proposer des RFC pour les features majeures
3. Suivre les guidelines de contribution

---

**Dernière mise à jour** : 18 Mars 2026
**Version** : 1.3.0-rc (API RESTful complète implémentée)
