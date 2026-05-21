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

La clé API identifie une application de confiance. Le sujet effectif n'est pas lu depuis le champ `subject` du corps de la requête : il est dérivé côté serveur depuis la clé API.

Par défaut, l'identité applicative est :

```clojure
{:auth-method :api-key
 :client-id "trusted-internal-app"
 :subject {:id "trusted-internal-app"
           :class "Application"
           :client-id "trusted-internal-app"}}
```

Les variables d'environnement `API_CLIENT_ID` et `API_CLIENT_CLASS` permettent de changer cette identité sans modifier le code :

```bash
export API_CLIENT_ID="app-A"
export API_CLIENT_CLASS="Application"
export API_CLIENT_ROLES="policy-admin,policy-deployer"
```

Conséquence de sécurité : un appelant ne peut pas envoyer `{"subject": {"id": "app-A"}}` dans le body pour se faire passer pour `app-A`. Pour une API key standard, `body.subject` est ignoré lors de la résolution du sujet.

Le mode où un composant backend de confiance fournit explicitement un sujet dans le body n'est possible que si l'identité Ring interne contient `:allow-subject-delegation true`. Ce mode doit rester réservé à un composant serveur authentifié, jamais à un client public.

### Rôles de gouvernance

Les endpoints de gouvernance qui modifient l'état exigent aussi un rôle applicatif ou JWT. `governance-admin` autorise toutes les opérations de gouvernance. Les rôles plus fins sont :

| Rôle | Autorise |
|------|----------|
| `policy-admin` | Créer, mettre à jour, supprimer ou importer des politiques |
| `risk-profile-admin` | Modifier ou supprimer les profils de risque d'impact |
| `policy-reviewer` | Approuver ou rejeter une analyse d'impact |
| `policy-deployer` | Déployer une analyse d'impact approuvée ou faire un rollback |
| `relation-admin` | Créer ou supprimer des tuples relationnels ReBAC |

Pour une clé API, les rôles viennent de `API_CLIENT_ROLES` sous forme de liste séparée par des virgules. Par défaut, l'identité applicative reçoit `governance-admin` pour préserver la compatibilité des installations existantes ; en production, configurez explicitement les rôles minimaux nécessaires.

### JWT Bearer

```
Authorization: Token <jwt>
```

L'identité est dérivée des claims du JWT.

---

## Format standard des réponses

## Politique d'API

Les nouvelles intégrations doivent utiliser les endpoints `/v1/*`, documentés dans `resources/openapi.yaml` et servis par `GET /openapi.yaml`. Les endpoints historiques (`/isAuthorized`, `/whoAuthorized`, `/whatAuthorized`, `/policy/:resourceClass`) restent disponibles pour compatibilité et sont indiqués comme legacy lorsqu'ils apparaissent ci-dessous.

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
| 401 | `AUTHENTICATION_REQUIRED` | Aucun sujet authentifié ne peut être établi |
| 401 | `UNBOUND_API_KEY_IDENTITY` | La clé API est valide mais aucune identité applicative n'est liée |
| 403 | `FORBIDDEN` | Accès admin requis |
| 403 | `GOVERNANCE_FORBIDDEN` | Rôle de gouvernance manquant |
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

Les nouveaux clients doivent utiliser le contrat canonique documente dans `docs/DECISION_CONTRACT.md`. En particulier, les champs de reference sont `allowed?`, `decisionType`, `effectiveSubject`, `matchedRuleNames`, `strategy` et `policySource`. Les champs historiques `allowed`, `decision`, `results` et `matchedRules` restent presents pour compatibilite.

### POST /isAuthorized (legacy)

Évalue si un sujet peut effectuer une opération sur une ressource.

Avec `X-API-Key`, le sujet évalué est l'application liée à la clé API. Avec JWT, le sujet évalué est dérivé de l'identité JWT vérifiée. Le champ `subject` ci-dessous peut être requis par le format de requête ou servir aux appels internes explicitement autorisés à déléguer le sujet, mais il ne doit pas être traité comme une preuve d'identité. Pour évaluer un utilisateur final `U` derrière une application `A`, l'application métier `B` doit authentifier `A`, puis appeler Autho avec une identité serveur sûre ou construire un sujet composite validé côté serveur, par exemple `DelegatedPrincipal`.

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

**Requête application-à-application avec API key :**

Si Autho est démarré avec :

```bash
export API_CLIENT_ID="app-A"
export API_CLIENT_CLASS="Application"
```

alors une requête comme celle-ci évalue les droits de `app-A`. Le champ `subject` peut rester requis par le format de requête, mais il n'est pas utilisé comme preuve d'identité avec `X-API-Key` :

```json
{
  "subject": {
    "id": "client-declared-value",
    "class": "Application"
  },
  "resource": {
    "class": "InformationB",
    "id": "info-123"
  },
  "operation": "lire",
  "context": {
    "on-behalf-of": "user-U"
  }
}
```

Exemple de règle correspondante :

```clojure
{:name "APP-A-CAN-READ-B"
 :resourceClass "InformationB"
 :operation "lire"
 :conditions [[= [Application $s client-id] "app-A"]
              [= [InformationB $r id] "info-123"]]
 :effect "allow"
 :priority 0}
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

### POST /v1/authz/shadow

Évalue la décision réelle avec la politique active, puis compare en dry-run avec une politique shadow. La décision retournée au client reste toujours la décision de production ; la politique shadow ne peut pas accorder ou refuser l'accès.

```json
{
  "subject": {"id": "alice", "role": "chef_de_service"},
  "resource": {"class": "Facture", "id": "INV-001"},
  "operation": "lire",
  "shadowPolicyVersion": 4
}
```

`shadowPolicy` peut remplacer `shadowPolicyVersion` pour tester une politique inline. La réponse contient les champs habituels de `/v1/authz/decisions` et un bloc `shadowEvaluation` avec `changed`, `changeCategory`, `production` et `shadow`.

---

### ReBAC dans explain

Les réponses de `POST /v1/authz/explain` et `POST /v1/authz/simulate` exposent `relationProofs` sur les règles évaluées qui utilisent une clause ReBAC. Chaque preuve indique la relation testée, la ressource qui porte effectivement le droit, si le droit est hérité, et le chemin de ressources utilisé.

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
    {
      "allowed": true,
      "allowed?": true,
      "decision": "allow",
      "decisionType": "allow",
      "subjectId": "alice",
      "resourceClass": "Facture",
      "resourceId": "INV-001",
      "operation": "lire",
      "matchedRuleNames": ["R1"],
      "policySource": "current",
      "results": ["R1"],
      "matchedRules": ["R1"]
    },
    {
      "allowed": false,
      "allowed?": false,
      "decision": "deny",
      "decisionType": "deny",
      "subjectId": "bob",
      "resourceClass": "Facture",
      "resourceId": "INV-002",
      "operation": "lire",
      "matchedRuleNames": [],
      "policySource": "current",
      "results": [],
      "matchedRules": []
    }
  ],
  "count": 2
}
```

Les resultats sont retournes dans le meme ordre que les requetes d'entree.

---

## Gestion des politiques

### GET /policies (legacy)

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

### GET /policies/:resourceClass (legacy)

```bash
curl -H "X-API-Key: key" http://localhost:8080/policies/Facture
```

### PUT /policies/:resourceClass (legacy)

Crée ou met à jour une politique. Autho valide le JSON contre `policySchema.json`, execute la validation statique de policy safety, execute les tests declaratifs embarques dans `tests` s'ils existent, puis sauvegarde une version. Voir `docs/POLICY_SAFETY.md`.
Requiert `governance-admin` ou `policy-admin`.

```bash
curl -X PUT http://localhost:8080/policies/Facture \
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
    ],
    "tests": [
      {
        "name": "chef de service can read",
        "subject": {"id": "alice", "class": "Person", "role": "chef_de_service"},
        "resource": {"id": "F-001", "class": "Facture"},
        "operation": "lire",
        "expect": "allow"
      }
    ]
  }'
```

### POST /v1/policies/:resourceClass/validate

Valide une politique candidate sans la persister. Cet endpoint applique les memes controles que la soumission de politique : schema JSON, validation statique de policy safety et tests declaratifs embarques.
Comme il ne persiste aucun etat, il ne requiert pas de role de gouvernance en plus de l'authentification.

```bash
curl -X POST http://localhost:8080/v1/policies/Facture/validate \
  -H "Content-Type: application/json" \
  -H "X-API-Key: key" \
  -d @candidate-policy.json
```

Reponse de succes :

```json
{
  "status": "success",
  "data": {
    "valid": true,
    "resourceClass": "Facture",
    "report": {
      "status": "passed",
      "summary": {
        "errors": 0,
        "warnings": 0,
        "policyTests": {
          "count": 2,
          "passed": 2,
          "failed": 0
        }
      }
    },
    "validation": {
      "valid": true,
      "errors": [],
      "warnings": [],
      "safety": {
        "errors": [],
        "warnings": []
      },
      "tests": {
        "count": 2,
        "passed": 2,
        "failed": 0,
        "errors": []
      },
      "report": {
        "status": "passed"
      }
    }
  }
}
```

En cas d'echec, la reponse est un `400` avec les issues et le rapport agrege dans `error.details`.

Les politiques peuvent etre ciblees par environnement avec le champ `environment` ou avec le query param `?environment=dev|staging|prod`. Sans valeur explicite, Autho utilise `prod`.

```bash
curl -X PUT "http://localhost:8080/v1/policies/Facture?environment=staging" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: key" \
  -d @candidate-policy.json
```

Pour un usage CI sans serveur HTTP, la meme chaine est disponible via :

```bash
./lein policy:validate --resource-class Facture --file candidate-policy.json --format json
```

### POST /v1/policies/:resourceClass/impact

Compare une politique courante ou versionnee avec une politique candidate sur un batch de requetes. Le resultat inclut `impactReport`, un resume exploitable en revue de changement.

```bash
curl -X POST http://localhost:8080/v1/policies/Facture/impact \
  -H "Content-Type: application/json" \
  -H "X-API-Key: key" \
  -d '{
    "candidatePolicy": {
      "strategy": "almost_one_allow_no_deny",
      "rules": []
    },
    "thresholds": {
      "maxRevokes": 0,
      "maxChangedDecisions": 50,
      "allowSensitiveResourceChanges": false
    },
    "riskProfiles": {
      "default": {"maxRevokes": 0},
      "environments": {
        "staging": {"maxRevokes": 2}
      },
      "resourceClasses": {
        "Facture": {"allowSensitiveResourceChanges": false}
      }
    },
    "requests": [
      {
        "subject": {"id": "alice"},
        "resource": {"class": "Facture", "id": "F-001", "classification": "confidential"},
        "operation": "lire"
      }
    ]
  }'
```

`impactReport.status` vaut `no_impact`, `review_required`, `high_risk` ou `blocked`. `recommendation` vaut `approve`, `review` ou `block`. Les blockers standards couvrent les revocations, le volume de decisions changees et les ressources sensibles touchees.

Les seuils effectifs sont resolus dans cet ordre : profil `default`, profil `environments[environment]`, profil `resourceClasses[resourceClass]`, puis `thresholds` de la requete. Le resultat expose `riskProfile` pour auditer les sources appliquees.

Ces profils peuvent etre persistés :

```bash
curl -X PUT http://localhost:8080/v1/policies/risk-profiles/environments/prod \
  -H "Content-Type: application/json" \
  -H "X-API-Key: key" \
  -d '{"maxRevokes": 0, "maxChangedDecisions": 25, "allowSensitiveResourceChanges": false}'

curl -X PUT http://localhost:8080/v1/policies/risk-profiles/environments/prod \
  -H "Content-Type: application/json" \
  -H "X-API-Key: key" \
  -d '{
    "thresholds": {"maxRevokes": 2},
    "approval": {"approved": true, "approvedBy": "risk-owner", "note": "Fenetre de migration controlee"}
  }'

curl -H "X-API-Key: key" \
  http://localhost:8080/v1/policies/risk-profiles

curl -H "X-API-Key: key" \
  http://localhost:8080/v1/policies/risk-profiles/revisions
```

Endpoints disponibles : `PUT/DELETE /v1/policies/risk-profiles/default`, `PUT/DELETE /v1/policies/risk-profiles/environments/:environment`, `PUT/DELETE /v1/policies/risk-profiles/resource-classes/:resourceClass`.
Les mutations de profils de risque requierent `governance-admin` ou `risk-profile-admin`.
Chaque modification de profil produit une revision append-only avec `action`, `previousProfile`, `newProfile`, `changedBy` et `changedAt`.
Les changements critiques, comme augmenter `maxRevokes`, augmenter `maxChangedDecisions`, autoriser les changements de ressources sensibles ou supprimer un profil existant, exigent `approval.approved = true` et un `approval.approvedBy` different de l'auteur.
Ces revisions apparaissent aussi dans `GET /v1/policies/:resourceClass/timeline` avec `eventType = risk_profile_changed`. Les revisions `default` et `environment` sont visibles pour toutes les classes ; les revisions `resource_class` sont visibles seulement pour la classe concernee.

Le rollout applique ces garde-fous :

- `recommendation = block` ou `status = blocked` : rollout refuse avec `POLICY_IMPACT_BLOCKED`;
- `recommendation = review`, `status = review_required` ou `status = high_risk` : rollout autorise seulement apres `reviewStatus = approved`;
- `recommendation = approve` et `status = no_impact` : rollout possible sans revue manuelle.

La revue d'impact requiert `governance-admin` ou `policy-reviewer`. Le rollout d'impact et le rollback requierent `governance-admin` ou `policy-deployer`.

### ReBAC relations

Les tuples relationnels sont administrables via `GET/POST/DELETE /v1/relations`. Les mutations requierent `governance-admin` ou `relation-admin`. Les tuples sont persistés dans la base H2 des politiques, table `REBAC_RELATIONS`, puis rechargés en index mémoire par `rebac/init!` au démarrage PDP.

```bash
curl -H "X-API-Key: key" \
  http://localhost:8080/v1/relations
```

```bash
curl -X POST http://localhost:8080/v1/relations \
  -H "Content-Type: application/json" \
  -H "X-API-Key: key" \
  -d '{
    "subject": {"class": "Person", "id": "alice"},
    "relation": "viewer",
    "resource": {"class": "Document", "id": "doc-1"}
  }'
```

```bash
curl -X DELETE http://localhost:8080/v1/relations \
  -H "Content-Type: application/json" \
  -H "X-API-Key: key" \
  -d '{
    "subject": {"class": "Person", "id": "alice"},
    "relation": "viewer",
    "resource": {"class": "Document", "id": "doc-1"}
  }'
```

Une politique peut ensuite utiliser :

```json
{"conditions": [["relation", "$s", "viewer", "$r"]]}
```

Le check relationnel commence par le tuple direct, suit les groupes via des tuples `member`, puis remonte les ressources parentes via des tuples `parent`. Exemple : si `alice member team-a`, `team-a viewer folder-1` et `doc-1 parent folder-1`, alors `alice` est aussi `viewer` de `doc-1`.

Pour expliquer un check relationnel sans passer par une decision complete :

```bash
curl -X POST http://localhost:8080/v1/relations/check \
  -H "Content-Type: application/json" \
  -H "X-API-Key: key" \
  -d '{
    "subject": {"class": "Person", "id": "alice"},
    "relation": "viewer",
    "resource": {"class": "Document", "id": "doc-1"}
  }'
```

Limite actuelle : Autho ne resout pas encore les rewrites de usersets, les traversals relationnels arbitraires ni le stockage relationnel distribué externe.

Le batch peut aussi etre construit depuis l'audit avec `auditReplay` :

```bash
curl -X POST http://localhost:8080/v1/policies/Facture/impact \
  -H "Content-Type: application/json" \
  -H "X-API-Key: key" \
  -d '{
    "candidatePolicy": {
      "strategy": "almost_one_allow_no_deny",
      "rules": []
    },
    "auditReplay": {
      "decision": "deny",
      "limit": 100
    }
  }'
```

Le replay audit utilise le snapshot complet de requete quand l'entree d'audit en contient un. Pour les anciennes entrees, il reconstruit une requete minimale avec les identifiants audites (`subject.id`, `resource.class`, `resource.id`, `operation`) et attache les metadonnees d'audit dans `context`. Les attributs manquants peuvent ensuite etre enrichis par les PIP pendant la simulation.

### DELETE /policies/:resourceClass (legacy)

```bash
curl -X DELETE -H "X-API-Key: key" http://localhost:8080/policies/Facture
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
  "newVersion": 6,
  "workflowAction": "rollback",
  "versionLink": {
    "version": 6,
    "deployment_kind": "rollback",
    "lifecycle_status": "deployed",
    "workflow_action": "rollback",
    "rollback_from_version": 3
  }
}
```

Le rollback ne remplace pas l'historique : il cree une nouvelle version active marquee `workflowAction = rollback` et reference la version source via `rollbackFromVersion`.

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
