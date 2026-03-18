# Autho — Référence API complète

**Version :** 0.1.0-SNAPSHOT
**Base URL :** `http://localhost:8080`
**OpenAPI :** `GET /openapi.yaml` | `GET /openapi.json`

---

## Authentification

Tous les endpoints (sauf `/health`, `/readiness`, `/status`, `/metrics`) requièrent l'une des méthodes suivantes :

### API Key (clients de confiance)

```
X-API-Key: <votre-api-key>
```

Le sujet est lu depuis le corps de la requête. L'identité est `: {auth-method: api-key, client-id: trusted-internal-app}`.

### JWT Bearer

```
Authorization: Token <jwt>
```

L'identité est dérivée des claims du JWT.

---

## Format standard des réponses

### Succès

```json
{
  "status": "success",
  "data": { ... }
}
```

### Erreur

```json
{
  "status": "error",
  "error": {
    "code": "ERROR_CODE",
    "message": "Description lisible",
    "timestamp": "2026-03-18T10:00:00Z"
  }
}
```

### Réponse paginée

```json
{
  "status": "success",
  "data": { "items": [...], "page": 1, "perPage": 20, "total": 142 }
}
```

---

## Codes d'erreur

| Code HTTP | Code d'erreur | Cause |
|-----------|--------------|-------|
| 400 | `INVALID_REQUEST_BODY` | Body JSON invalide ou absent |
| 400 | `VALIDATION_ERROR` | Validation échouée (champ manquant, format invalide) |
| 400 | `MISSING_RESOURCE` | Champ `resource` absent |
| 400 | `MISSING_SUBJECT` | Champ `subject` absent |
| 400 | `INVALID_BATCH_REQUEST` | Tableau `requests` absent ou vide |
| 400 | `BATCH_TOO_LARGE` | Dépasse `MAX_BATCH_SIZE` (défaut 100) |
| 400 | `YAML_IMPORT_FAILED` | YAML invalide ou erreur d'import |
| 401 | `UNAUTHORIZED` | Authentification manquante ou invalide |
| 403 | `FORBIDDEN` | Accès admin requis |
| 404 | `POLICY_NOT_FOUND` | Politique inexistante |
| 404 | `VERSION_NOT_FOUND` | Version de politique inexistante |
| 413 | `REQUEST_TOO_LARGE` | Body dépasse `MAX_REQUEST_SIZE` |
| 429 | `RATE_LIMIT_EXCEEDED` | Rate limit dépassé |
| 500 | `AUTHORIZATION_ERROR` | Erreur interne lors de l'évaluation |
| 503 | `RULES_NOT_LOADED` | Dépôt de règles non chargé |
| 503 | `SERVER_SHUTTING_DOWN` | Serveur en cours d'arrêt |
| 503 | `KAFKA_DISABLED` | Fonctionnalité Kafka désactivée |

---

## Endpoints de santé

### GET /health

```
curl http://localhost:8080/health
```

```json
{"status": "ok"}
```

### GET /readiness

Renvoie 503 si le dépôt de règles n'est pas chargé.

```json
{"status": "ready", "rules": "loaded"}
```

### GET /status

```json
{
  "status": "ok",
  "version": "0.1.0-SNAPSHOT",
  "uptime_seconds": 3612,
  "rules_status": "loaded",
  "cache_stats": {
    "decision-hits": 12450,
    "decision-misses": 3210,
    "subject-hits": 8900,
    "subject-misses": 420
  },
  "circuit_breakers": {
    "user-service": "closed",
    "erp-service": "half-open"
  }
}
```

---

## Décisions d'autorisation

### POST /isAuthorized

Évalue si un sujet peut effectuer une opération sur une ressource.

**Requête :**

```json
{
  "subject": {
    "id": "alice",
    "role": "chef_de_service",
    "service": "comptabilite",
    "seuil": 2000
  },
  "resource": {
    "class": "Facture",
    "id": "INV-001",
    "service": "comptabilite",
    "montant": 500
  },
  "operation": "lire",
  "context": {
    "ip": "10.0.0.1",
    "userAgent": "MyApp/1.0"
  }
}
```

**Réponse — Autorisé :**

```json
{"results": ["R1"]}
```

**Réponse — Refusé :**

```json
{"results": []}
```

Le champ `results` contient les noms des règles `allow` qui ont correspondu. Un tableau vide signifie un refus.

**Notes :**
- La décision est mise en cache (`DECISION_CACHE_TTL_MS`, défaut 60 s)
- Le cache est court-circuité si `context.timestamp` est présent (time-travel)
- L'enrichissement sujet/ressource par les fillers s'effectue en parallèle (timeout 5 s)

---

### POST /whoAuthorized

Retourne les conditions de sujet permettant l'accès à une ressource pour une opération.

**Requête :**

```json
{
  "resource": {
    "class": "Facture",
    "id": "INV-001"
  },
  "operation": "lire"
}
```

**Réponse :**

```json
[
  {
    "resourceClass": "Facture",
    "operation": "lire",
    "subjectCond": [["=", "$.role", "chef_de_service"], ["=", "$s.service", "$r.service"]]
  }
]
```

---

### POST /whatAuthorized

Retourne les ressources auxquelles un sujet a accès, avec pagination et récupération optionnelle des objets réels depuis Kafka/RocksDB.

**Requête :**

```json
{
  "subject": {
    "id": "alice",
    "role": "chef_de_service",
    "service": "comptabilite"
  },
  "resource": {"class": "Facture"},
  "operation": "lire",
  "page": 1,
  "pageSize": 20
}
```

**Réponse (sans Kafka) :**

```json
{
  "allow": [
    {
      "resourceClass": "Facture",
      "operation": "lire",
      "resourceCond": [["=", "$r.service", "$s.service"], ["<", "$r.montant", "$s.seuil"]]
    }
  ],
  "deny": []
}
```

**Réponse (avec Kafka/RocksDB) :**

```json
{
  "allow": [
    {
      "resourceClass": "Facture",
      "operation": "lire",
      "resourceCond": [["=", "$r.service", "comptabilite"]],
      "objects": {
        "items": [
          {"id": "INV-001", "montant": 500, "service": "comptabilite", "status": "pending"},
          {"id": "INV-045", "montant": 1200, "service": "comptabilite", "status": "approved"}
        ],
        "pagination": {
          "page": 1,
          "pageSize": 20,
          "total": 2,
          "hasMore": false
        }
      }
    }
  ],
  "deny": []
}
```

---

### POST /explain

Trace détaillée règle par règle de la décision.

**Requête :** identique à `/isAuthorized`

**Réponse :**

```json
{
  "decision": true,
  "strategy": "almost_one_allow_no_deny",
  "totalRules": 3,
  "matchedRules": 1,
  "rules": [
    {
      "name": "R1",
      "effect": "allow",
      "operation": "lire",
      "matched": true,
      "subjectCond": [["=", "$s.role", "chef_de_service"], ["=", "$s.service", "$r.service"]],
      "resourceCond": [["<", "$r.montant", "$s.seuil"]]
    },
    {
      "name": "R2",
      "effect": "deny",
      "operation": "lire",
      "matched": false,
      "subjectCond": [],
      "resourceCond": [["=", "$r.confidentiel", "true"]]
    }
  ]
}
```

---

### POST /v1/authz/simulate

Évalue une demande en dry-run (sans cache ni audit). Utile pour tester une politique avant de la déployer.

**Requête — simulation avec politique inline :**

```json
{
  "subject":   {"id": "alice", "role": "chef_de_service", "service": "compta"},
  "resource":  {"class": "Facture", "id": "INV-001", "service": "compta", "montant": 300},
  "operation": "lire",
  "simulatedPolicy": {
    "resourceClass": "Facture",
    "strategy": "almost_one_allow_no_deny",
    "rules": [
      {
        "name": "TEST-R1",
        "operation": "lire",
        "priority": 0,
        "effect": "allow",
        "conditions": [["=", "$s.role", "chef_de_service"]]
      }
    ]
  }
}
```

**Requête — simulation avec version archivée :**

```json
{
  "subject":        {"id": "alice", "role": "chef_de_service"},
  "resource":       {"class": "Facture", "id": "INV-001"},
  "operation":      "lire",
  "policyVersion":  3
}
```

**Réponse :**

```json
{
  "decision": true,
  "simulated": true,
  "policySource": "provided",
  "policyVersion": null,
  "totalRules": 1,
  "matchedRules": 1,
  "rules": [
    {"name": "TEST-R1", "effect": "allow", "matched": true}
  ]
}
```

`policySource` vaut `"provided"` (inline), `"version"` (version archivée) ou `"current"` (politique active).

---

### POST /v1/authz/batch

Évalue jusqu'à 100 demandes en parallèle.

**Requête :**

```json
{
  "requests": [
    {
      "subject":   {"id": "alice", "role": "chef_de_service"},
      "resource":  {"class": "Facture", "id": "INV-001"},
      "operation": "lire"
    },
    {
      "subject":   {"id": "bob", "role": "stagiaire"},
      "resource":  {"class": "Facture", "id": "INV-002"},
      "operation": "lire"
    }
  ]
}
```

**Réponse :**

```json
{
  "results": [
    {"request-id": 0, "decision": {"results": ["R1"]}},
    {"request-id": 1, "decision": {"results": []}}
  ],
  "count": 2
}
```

En cas d'erreur sur un élément du batch :

```json
{"request-id": 1, "error": "Validation failed: subject.id is required"}
```

---

## Gestion des politiques

### GET /policies

Liste toutes les politiques chargées.

```bash
curl -H "X-API-Key: key" http://localhost:8080/policies
```

```json
{
  "Facture": {
    "global": {
      "strategy": "almost_one_allow_no_deny",
      "rules": [...]
    }
  },
  "Contrat": { ... }
}
```

### GET /policy/:resourceClass

```bash
curl -H "X-API-Key: key" http://localhost:8080/policy/Facture
```

### PUT /policy/:resourceClass

Crée ou met à jour une politique. Valide contre `policySchema.json`, sauvegarde une version.

```bash
curl -X PUT http://localhost:8080/policy/Facture \
  -H "Content-Type: application/json" \
  -H "X-API-Key: key" \
  -d '{
    "resourceClass": "Facture",
    "strategy": "almost_one_allow_no_deny",
    "rules": [
      {
        "name": "R1",
        "operation": "lire",
        "priority": 0,
        "effect": "allow",
        "conditions": [["=", "$s.role", "chef_de_service"]]
      }
    ]
  }'
```

### DELETE /policy/:resourceClass

```bash
curl -X DELETE -H "X-API-Key: key" http://localhost:8080/policy/Facture
```

### POST /v1/policies/import

Import depuis YAML.

```bash
curl -X POST http://localhost:8080/v1/policies/import \
  -H "Content-Type: text/yaml" \
  -H "X-API-Key: key" \
  --data-binary @ma-politique.yaml
```

**Réponse :**

```json
{"ok": true, "resourceClass": "Facture", "rulesLoaded": 3}
```

---

## Versionnage des politiques

### GET /v1/policies/:rc/versions

```bash
curl -H "X-API-Key: key" \
  http://localhost:8080/v1/policies/Facture/versions
```

```json
[
  {"version": 5, "author": "alice@company.com", "comment": "Ajout règle DPO", "createdAt": "2026-03-18T09:00:00Z"},
  {"version": 4, "author": "bob@company.com",   "comment": "Fix seuil",       "createdAt": "2026-03-15T14:30:00Z"}
]
```

### GET /v1/policies/:rc/versions/:v

```bash
curl -H "X-API-Key: key" \
  http://localhost:8080/v1/policies/Facture/versions/3
```

Retourne le corps complet de la politique à la version 3.

### GET /v1/policies/:rc/diff?from=3&to=5

```bash
curl -H "X-API-Key: key" \
  "http://localhost:8080/v1/policies/Facture/diff?from=3&to=5"
```

```json
{
  "added":   ["R-DPO"],
  "removed": [],
  "changed": ["R1"]
}
```

### POST /v1/policies/:rc/rollback/:v

```bash
curl -X POST -H "X-API-Key: key" \
  http://localhost:8080/v1/policies/Facture/rollback/3
```

```json
{
  "resourceClass": "Facture",
  "rolledBackTo": 3,
  "newVersion": 6
}
```

---

## Cache

### GET /v1/cache/stats

```bash
curl -H "X-API-Key: key" http://localhost:8080/v1/cache/stats
```

```json
{
  "decision-hits":   12450,
  "decision-misses": 3210,
  "decision-ratio":  0.795,
  "subject-hits":    8900,
  "subject-misses":  420,
  "resource-hits":   5600,
  "resource-misses": 890,
  "policy-hits":     21000,
  "policy-misses":   12,
  "decision-size":   4231,
  "subject-size":    312,
  "resource-size":   189
}
```

### DELETE /v1/cache

Vide tous les caches.

```bash
curl -X DELETE -H "X-API-Key: key" http://localhost:8080/v1/cache
```

```json
{"status": "cleared"}
```

### DELETE /v1/cache/:type/:key

Invalide une entrée précise.

```bash
curl -X DELETE -H "X-API-Key: key" \
  http://localhost:8080/v1/cache/subject/alice
```

```json
{"status": "cleared", "type": "subject", "key": "alice"}
```

Types valides : `subject`, `resource`, `policy`, `decision`.

---

## Time-Travel (Kafka requis)

### POST /isAuthorized-at-time

```json
{
  "subject":    {"id": "alice"},
  "resource":   {"class": "Facture", "id": "INV-001"},
  "operation":  "lire",
  "timestamp":  "2026-02-01T09:00:00Z"
}
```

**Réponse :** identique à `/isAuthorized` (basée sur l'état du système à cette date).

### POST /audit-trail

```json
{
  "resourceClass": "Facture",
  "resourceId":    "INV-001",
  "startTime":     "2026-01-01T00:00:00Z",
  "endTime":       "2026-03-31T23:59:59Z"
}
```

```json
[
  {
    "timestamp":  "2026-01-15T10:23:00Z",
    "subjectId":  "alice",
    "operation":  "lire",
    "decision":   "allow",
    "matchedRules": ["R1"]
  }
]
```

---

## Administration

Tous les endpoints `/admin/*` requièrent un rôle `admin` dans le JWT ou une authentification par API Key.

### POST /admin/reinit

Réinitialise entièrement le PDP (recharge règles, personnes, délégations).

```bash
curl -X POST -H "X-API-Key: key" http://localhost:8080/admin/reinit
```

### POST /admin/reload_rules

Recharge uniquement le dépôt de règles.

### GET /admin/audit/search

```
GET /admin/audit/search?subjectId=alice&resourceClass=Facture&decision=allow&from=2026-01-01&to=2026-03-31&page=1&pageSize=20
```

```json
{
  "items": [
    {
      "id": 1234,
      "ts": "2026-03-18T09:00:00Z",
      "requestId": "550e8400-e29b-41d4-a716-446655440000",
      "subjectId": "alice",
      "resourceClass": "Facture",
      "resourceId": "INV-001",
      "operation": "lire",
      "decision": "allow",
      "matchedRules": ["R1"]
    }
  ],
  "page": 1,
  "perPage": 20,
  "total": 1
}
```

### GET /admin/audit/verify

Vérifie l'intégrité de la chaîne HMAC.

```json
{"valid": true}
```

```json
{"valid": false, "broken-at": 4521, "reason": "HMAC mismatch"}
```

---

## Sujets et ressources (endpoints v1)

### GET /v1/subjects

```
GET /v1/subjects?page=1&per-page=20&sort=id&order=asc
```

### GET /v1/subjects/:id

### GET /v1/subjects/search?q=alice&page=1

### POST /v1/subjects/batch-get

```json
{"ids": ["alice", "bob", "charlie"]}
```

### GET /v1/resources

### GET /v1/resources/:class

### GET /v1/resources/:class/:id

---

## Métriques Prometheus

```
GET /metrics
```

```
# HELP autho_pdp_decisions_total Total PDP authorization decisions
# TYPE autho_pdp_decisions_total counter
autho_pdp_decisions_total{decision="allow",resource_class="Facture"} 15234.0
autho_pdp_decisions_total{decision="deny",resource_class="Facture"} 4521.0

# HELP autho_pip_duration_seconds PIP call latency
# TYPE autho_pip_duration_seconds summary
autho_pip_duration_seconds{type="rest",quantile="0.5"} 0.023
autho_pip_duration_seconds{type="kafka-pip",quantile="0.5"} 0.0008

# HELP autho_cache_events_total Cache hit/miss events
# TYPE autho_cache_events_total counter
autho_cache_events_total{event="hit",type="decision"} 12450.0
autho_cache_events_total{event="miss",type="decision"} 3210.0
```

---

## OpenAPI

```bash
# YAML
curl http://localhost:8080/openapi.yaml > openapi.yaml

# JSON
curl http://localhost:8080/openapi.json | jq .
```

Compatible avec Swagger UI, Redoc, Postman, etc.
