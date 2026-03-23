# Autho — Présentation générale

## Qu'est-ce qu'Autho ?

Autho est un **serveur d'autorisation centralisé** basé sur les standards XACML et ABAC (Attribute-Based Access Control). Il fournit une décision d'autorisation unique et indépendante pour l'ensemble des applications d'un système d'information, en s'appuyant sur des politiques de contrôle d'accès riches et versionnées.

L'idée centrale : les applications métier **délèguent entièrement la décision d'autorisation** à Autho. Elles n'embarquent plus de logique de droits — elles posent une question (`Alice peut-elle lire la facture FAC-123 ?`) et reçoivent une réponse (`allow` ou `deny`) avec la liste des règles qui ont conduit à cette décision.

---

## Concepts fondamentaux

### ABAC — Attribute-Based Access Control

Contrairement au RBAC (rôles fixes), l'ABAC évalue des attributs dynamiques du sujet, de la ressource et du contexte. Une règle peut exprimer des conditions comme :

- le service du demandeur correspond au service du document
- le niveau d'habilitation est suffisant
- la date courante est dans la plage d'autorisation
- le rôle appartient à une liste définie

### Architecture XACML (PDP / PRP / PIP / PAP)

| Composant | Rôle |
|-----------|------|
| **PDP** — Policy Decision Point | Évalue la requête, retourne allow/deny |
| **PRP** — Policy Repository Point | Stocke et fournit les politiques |
| **PIP** — Policy Information Point | Enrichit le sujet/ressource avec des attributs externes |
| **PAP** — Policy Administration Point | Interface d'administration des politiques (API + UI) |

---

## Fonctionnalités principales

### Évaluation d'autorisation
- **`POST /isAuthorized`** — décision simple (allow/deny)
- **`POST /explain`** — décision avec détail des règles évaluées et de leur résultat
- **`POST /whoAuthorized`** — quels sujets peuvent effectuer une opération sur une ressource ?
- **`POST /whatAuthorized`** — quelles ressources un sujet peut-il accéder ?
- **Évaluation par lot** (`/v1/batch`) — jusqu'à 100 requêtes en un seul appel

### Gestion des politiques
- CRUD complet des politiques par classe de ressource
- Import depuis YAML (format déclaratif)
- **Versionnage automatique** — chaque modification conserve l'historique
- **Rollback** vers n'importe quelle version antérieure
- **Diff** entre deux versions

### Time-travel
- **`POST /isAuthorized-at-time`** — décision telle qu'elle aurait été rendue à un instant T passé
- **`POST /who-was-authorized-at`** — qui était autorisé à T ?
- **`POST /what-could-access-at`** — quelles ressources étaient accessibles à T ?

Fonctionnalité rare parmi les serveurs d'autorisation du marché : possible grâce au stockage immuable des événements Kafka.

### Audit
- Chaque décision est enregistrée (sujet, ressource, opération, décision, règles correspondantes)
- Recherche par sujet, classe, décision, plage de dates
- Vérification d'intégrité de la chaîne d'audit (hash SHA-256 chaîné)
- Export CSV

### Cache et performance
- Cache multi-niveaux : décisions, sujets, ressources
- Invalidation ciblée par sujet, ressource ou classe
- Métriques Prometheus (`/metrics`)

### Observabilité
- Traces OpenTelemetry (OTLP)
- Métriques Micrometer / Prometheus
- Logs structurés (SLF4J / Logback)
- Circuit breakers sur les PIPs HTTP
- Dashboard temps réel dans l'UI admin

---

## Format d'une requête d'autorisation

```json
{
  "subject":    { "id": "001", "class": "Person" },
  "resource":   { "id": "FAC-123", "class": "Facture" },
  "operation":  "lire",
  "context":    { "date": "2026-03-23" }
}
```

### Réponse `isAuthorized`
```json
{
  "status": "success",
  "data": { "result": true }
}
```

### Réponse `explain`
```json
{
  "status": "success",
  "data": {
    "result": true,
    "rules": [
      { "name": "R1", "effect": "allow", "priority": 0 }
    ]
  }
}
```

---

## Format des politiques

Une politique est associée à une **classe de ressource**. Elle contient un ensemble de règles et une stratégie de résolution de conflits.

```json
{
  "strategy": "almost_one_allow_no_deny",
  "rules": [
    {
      "name": "R-ALLOW",
      "priority": 0,
      "operation": "lire",
      "effect": "allow",
      "conditions": [
        ["=",  ["Person",  "$s", "service"],        ["Facture", "$r", "service"]],
        [">=", ["Person",  "$s", "clearance-level"], ["Facture", "$r", "niveau-requis"]]
      ]
    },
    {
      "name": "R-DENY-ARCHIVE",
      "priority": 10,
      "operation": "lire",
      "effect": "deny",
      "conditions": [
        ["=", ["Facture", "$r", "statut"], "archive"]
      ]
    }
  ]
}
```

### Stratégie `almost_one_allow_no_deny`

La décision est **allow** si et seulement si :
- au moins une règle `allow` est vérifiée, **et**
- la priorité maximale des règles `allow` est ≥ à la priorité maximale des règles `deny`

Une règle `deny` de priorité supérieure écrase donc un `allow` de priorité inférieure.

### Format des conditions

Chaque condition est un triplet `[opérateur opérande1 opérande2]`.

Un opérande est soit une valeur littérale (`"archive"`, `5000`), soit une référence à un attribut :
`[NomClasse $s|$r nom-attribut]` où `$s` désigne le sujet et `$r` la ressource.

**Opérateurs disponibles :** `=`, `diff`, `<`, `>`, `<=`, `>=`, `in`, `notin`, `date>`

---

## Authentification

Deux méthodes supportées sur tous les endpoints protégés :

| Méthode | En-tête | Usage |
|---------|---------|-------|
| API Key | `Authorization: X-API-Key <clé>` | Services internes, appels machine-à-machine |
| JWT Bearer | `Authorization: Token <jwt>` | Applications avec identité utilisateur |

Les endpoints `/health`, `/readiness`, `/status`, `/metrics` sont publics.

---

## Démarrage rapide

```bash
# Variables d'environnement obligatoires
export JWT_SECRET="clé-secrète-min-32-caractères"
export API_KEY="clé-api-interne"

# Démarrage
lein run

# Vérification
curl http://localhost:8080/health
# → {"status":"UP"}
```

L'interface d'administration est accessible sur `http://localhost:8080/admin/ui`.

---

## Dépendances de déploiement

| Service | Rôle | Obligatoire |
|---------|------|-------------|
| **LDAP** | Source des attributs personnes | Oui si `person.source=ldap` |
| **Kafka** | Flux des objets métier pour les PIPs | Non (désactivable via `KAFKA_ENABLED=false`) |
| **RocksDB** | Cache local des objets Kafka | Automatique si Kafka activé |
| Base H2 | Stockage de l'audit et des versions | Embarquée, aucune installation requise |

Pour le développement, un `docker-compose.yml` fourni démarre OpenLDAP et Kafka avec leurs interfaces d'administration.
