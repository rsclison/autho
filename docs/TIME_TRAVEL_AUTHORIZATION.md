# Time-Travel Authorization

## Vue d'ensemble

L'autorisation temporelle permet de répondre à des questions d'autorisation **à n'importe quel moment du passé** :

- "Qui était autorisé à accéder à cette facture le 15 mars 2024 ?"
- "Quels documents l'utilisateur X pouvait-il voir il y a 6 mois ?"
- "Recréer l'état exact des autorisations au moment de l'incident"

## Architecture

### Topics Duaux (Transparents pour les Producteurs)

```
PRODUCTEURS (aucun changement)
        ↓
Topics Compactés (performance)
        ↓
    ┌───────┴────────┐
    ↓                ↓
Autho Server    Mirror Service
(temps réel)    (background)
                    ↓
              Topics History
              (retention 365j)
                    ↓
            Time-Travel Engine
```

**Avantages** :
- ✅ **Transparent** : Les producteurs continuent d'envoyer sur les topics compactés
- ✅ **Performance** : Les requêtes temps réel utilisent RocksDB (rapide)
- ✅ **Audit complet** : Historique complet conservé dans topics history
- ✅ **Conforme GDPR** : Rétention configurable

## Installation

### 1. Créer les Topics History

```bash
# Rendre le script exécutable
chmod +x scripts/create-history-topics.sh

# Créer les topics avec rétention de 1 an (par défaut)
./scripts/create-history-topics.sh

# Ou avec rétention personnalisée (90 jours)
RETENTION_DAYS=90 ./scripts/create-history-topics.sh
```

Cela crée automatiquement :
- `invoices-history` (retention: 365 jours)
- `contracts-history` (retention: 365 jours)
- `legal-commitments-history` (retention: 365 jours)

### 2. Démarrer le Mirror Service

Le service de mirroring duplique automatiquement les messages des topics compactés vers les topics history :

```bash
# Démarrer en arrière-plan
lein run -m autho.topic-mirror &

# Ou avec docker-compose (à venir)
docker-compose up -d topic-mirror
```

**Logs à surveiller** :
```
INFO  autho.topic-mirror - Starting Topic Mirror Service...
INFO  autho.topic-mirror - Building mirror topology: invoices-compacted -> invoices-history
INFO  autho.topic-mirror - Topic Mirror Service started successfully
```

### 3. Démarrer le Serveur Autho

```bash
# Le serveur démarre normalement
lein run
```

Les nouveaux endpoints sont automatiquement disponibles.

## Utilisation

### Comment une décision passée est reconstruite

Pour une date T, les endpoints 1 à 3 :

1. **Reconstruisent l'état des objets métier à T**, en rejouant le topic d'historique (`business-objects-history`, ou `KAFKA_HISTORY_TOPIC`) jusqu'à T.
2. **Retrouvent la politique en vigueur à T** : la dernière version déployée avant T pour l'environnement demandé (`environment`, `prod` par défaut), lue dans `POLICY_VERSIONS`. Si aucune version n'a jamais été enregistrée, par exemple pour une politique chargée depuis un fichier, la politique courante est utilisée avec l'avertissement `POLICY_VERSION_UNAVAILABLE`.
3. **Évaluent la règle avec ces seuls attributs.** Aucun PIP, filler ni cache n'est consulté, pour qu'aucune valeur actuelle ne se glisse dans une décision passée. Les attributs envoyés par l'appelant sont écrasés par ceux de l'historique : l'appelant ne fait qu'identifier les objets.

Ces évaluations n'écrivent ni dans le journal d'audit ni dans les métriques de décision.

**Ce qui n'a pas d'historique :**
- **Relations ReBAC** : les clauses `relation` sont évaluées sur l'état *courant* des relations, avec l'avertissement `RELATIONS_EVALUATED_ON_CURRENT_STATE`.
- **Délégations** : elles ne sont pas appliquées.
- **Sujets** : ils ne sont trouvés que s'ils sont publiés dans le topic d'historique. Une personne connue seulement via LDAP déclenche `SUBJECT_NOT_IN_SNAPSHOT`.

Avertissements possibles : `POLICY_VERSION_UNAVAILABLE`, `NO_POLICY_AT_TIME` (aucune politique à T, décision `deny`), `SUBJECT_NOT_IN_SNAPSHOT`, `RESOURCE_NOT_IN_SNAPSHOT`, `RELATIONS_EVALUATED_ON_CURRENT_STATE`.

### Endpoint 1 : `/isAuthorized-at-time`

Évalue une autorisation à un moment T dans le passé.

**Requête** :
```bash
curl -X POST http://localhost:3000/isAuthorized-at-time \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -d '{
    "timestamp": "2024-03-15T10:30:00Z",
    "subject": {"id": "alice@company.com"},
    "action": "approve",
    "resource": {"class": "Facture", "id": "INV-12345"}
  }'
```

`action` est un alias de `operation`. `subject.class` est facultatif : il est déduit de l'historique, et une erreur 400 `AMBIGUOUS_SUBJECT_CLASS` est renvoyée si l'identifiant existe dans plusieurs classes.

**Réponse** :
```json
{
  "timestamp": "2024-03-15T10:30:00Z",
  "decision": "allow",
  "allowed": true,
  "matchedRules": ["R-approve"],
  "subject": {"class": "Person", "id": "alice@company.com", "role": "manager"},
  "resource": {"class": "Facture", "id": "INV-12345", "amount": 5000},
  "operation": "approve",
  "policy": {"source": "version", "version": 7, "createdAt": "2024-03-01T09:00:00Z"},
  "snapshot-size": 1523,
  "warnings": []
}
```

### Endpoint 2 : `/who-was-authorized-at`

Liste les sujets de l'historique qui étaient autorisés sur une ressource à un moment T.

Les candidats sont les objets des classes dont la politique lit des attributs de sujet (`["Person", "$s", "role"]` → `Person`), ou de la classe donnée par `subjectClass`. Le paramètre `limit` (1000 par défaut, 10000 au maximum) borne le nombre de candidats évalués ; `truncated` indique s'il a été atteint.

**Requête** :
```bash
curl -X POST http://localhost:3000/who-was-authorized-at \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -d '{
    "resourceClass": "Facture",
    "resourceId": "INV-12345",
    "action": "approve",
    "timestamp": "2024-03-15T10:30:00Z"
  }'
```

**Réponse** :
```json
{
  "query": "who-was-authorized-at",
  "resource": {"class": "Facture", "id": "INV-12345"},
  "action": "approve",
  "timestamp": "2024-03-15T10:30:00Z",
  "policy": {"source": "version", "version": 7, "createdAt": "2024-03-01T09:00:00Z"},
  "authorized-subjects": [
    {"class": "Person", "id": "alice@company.com", "matchedRules": ["R-approve"]},
    {"class": "Person", "id": "bob@company.com", "matchedRules": ["R-approve"]}
  ],
  "evaluated": 240,
  "truncated": false,
  "warnings": []
}
```

### Endpoint 3 : `/what-could-access-at`

Liste les objets de l'historique auxquels un sujet pouvait accéder à un moment T.

Les candidats sont les objets de toutes les classes qui avaient une politique à T, ou seulement ceux de `resourceClass`. Les paramètres `subjectClass` et `limit` fonctionnent comme pour l'endpoint 2.

**Requête** :
```bash
curl -X POST http://localhost:3000/what-could-access-at \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -d '{
    "subjectId": "alice@company.com",
    "action": "view",
    "timestamp": "2024-03-15T10:30:00Z"
  }'
```

**Réponse** :
```json
{
  "query": "what-could-access-at",
  "subject": "alice@company.com",
  "action": "view",
  "timestamp": "2024-03-15T10:30:00Z",
  "policies": {
    "Facture": {"source": "version", "version": 7, "createdAt": "2024-03-01T09:00:00Z"},
    "Contrat": {"source": "current"}
  },
  "accessible-resources": [
    {"class": "Facture", "id": "INV-12345", "matchedRules": ["R-view"]},
    {"class": "Contrat", "id": "CTR-789", "matchedRules": ["R-public"]}
  ],
  "evaluated": 812,
  "truncated": false,
  "warnings": ["POLICY_VERSION_UNAVAILABLE"]
}
```

### Endpoint 4 : `/audit-trail`

Retourne l'historique des accès enregistrés pour une ressource sur une période, du plus ancien au plus récent. Il couvre les décisions du PDP et les vérifications ReBAC directes.

Contrairement aux autres endpoints time-travel, celui-ci **lit le journal d'audit** et ne nécessite pas Kafka. Il exige une licence Pro/Enterprise et une identité admin (clé API `governance-admin` ou JWT avec le rôle `admin`), comme `/admin/audit/search`.

**Requête** :
```bash
curl -X POST http://localhost:3000/audit-trail \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -d '{
    "resourceClass": "Facture",
    "resourceId": "INV-12345",
    "startTime": "2024-03-01T00:00:00Z",
    "endTime": "2024-03-31T23:59:59Z",
    "page": 1,
    "pageSize": 100
  }'
```

**Réponse** :
```json
{
  "query": "audit-trail",
  "source": "audit-log",
  "resource": {"class": "Facture", "id": "INV-12345"},
  "time-range": {
    "start": "2024-03-01T00:00:00Z",
    "end": "2024-03-31T23:59:59Z"
  },
  "total": 2,
  "page": 1,
  "pageSize": 100,
  "events": [
    {
      "auditId": 1042,
      "timestamp": "2024-03-15T10:30:00Z",
      "subject": "alice@company.com",
      "action": "view",
      "decision": "allow",
      "plane": "abac",
      "matchedRules": ["R-view-own-invoices"]
    },
    {
      "auditId": 1187,
      "timestamp": "2024-03-20T14:22:13Z",
      "subject": "bob@company.com",
      "action": "approve",
      "decision": "allow",
      "plane": "abac",
      "matchedRules": ["R-approve"],
      "delegationChain": ["carol@company.com"],
      "grantedVia": "carol@company.com"
    }
  ]
}
```

`delegationChain` et `grantedVia` n'apparaissent que si l'accès a été accordé par délégation. L'intégrité des entrées se vérifie avec `GET /admin/audit/verify`.

## Cas d'Usage

### 1. Investigation Forensique

```bash
# Qui pouvait accéder au document sensible au moment de la fuite ?
curl -X POST http://localhost:3000/who-was-authorized-at \
  -d '{
    "resourceClass": "Contrat",
    "resourceId": "CTR-CONFIDENTIEL",
    "action": "view",
    "timestamp": "2024-11-01T03:22:00Z"
  }'
```

### 2. Audit de Conformité GDPR

```bash
# Prouver qui a accédé aux données personnelles d'un utilisateur
curl -X POST http://localhost:3000/audit-trail \
  -d '{
    "resourceClass": "DonnéesPersonnelles",
    "resourceId": "USER-12345",
    "startTime": "2024-01-01T00:00:00Z",
    "endTime": "2024-12-31T23:59:59Z"
  }'
```

### 3. Validation Post-Promotion

```bash
# Vérifier qu'un manager avait bien les droits après sa promotion
curl -X POST http://localhost:3000/what-could-access-at \
  -d '{
    "subjectId": "newmanager@company.com",
    "action": "approve",
    "timestamp": "2024-10-01T09:00:00Z"
  }'
```

### 4. Rejeu de Décision

```bash
# Reproduire exactement la décision d'autorisation prise à un moment T
curl -X POST http://localhost:3000/isAuthorized-at-time \
  -d '{
    "timestamp": "2024-06-15T14:30:00Z",
    "subject": {"id": "auditor@company.com"},
    "action": "export",
    "resource": {"class": "EngagementJuridique", "id": "LEG-9876"}
  }'
```

## Configuration

### Variables d'Environnement

| Variable | Description | Défaut |
|----------|-------------|--------|
| `KAFKA_BOOTSTRAP_SERVERS` | Serveurs Kafka | `localhost:9092` |
| `KAFKA_HISTORY_TOPIC` | Topic d'historique rejoué par les endpoints 1 à 3 | `business-objects-history` |
| `RETENTION_DAYS` | Jours de rétention history | `365` |

### Monitoring

#### Vérifier l'état du Mirror Service

```bash
# Logs du mirror service
docker logs -f autho-topic-mirror

# Vérifier que les topics history reçoivent des messages
docker exec autho-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic invoices-history \
  --from-beginning \
  --max-messages 5
```

#### Métriques à surveiller

- **Lag du consumer** : Le mirror service doit rester à jour
- **Taille des topics history** : Vérifier l'espace disque
- **Latence des requêtes time-travel** : Peut être lente pour replays longs

## Performance

### Optimisations

1. **Partitionnement** : Les topics history sont partitionnés (3 partitions par défaut)
2. **Compression** : LZ4 activée pour réduire l'espace disque
3. **Caching** : Le snapshot historique peut être mis en cache pour requêtes répétées

### Temps de Réponse Estimés

| Opération | Snapshot Size | Temps |
|-----------|--------------|-------|
| `isAuthorized-at-time` | 1000 objets | ~2-5s |
| `who-was-authorized-at` | 1000 objets | ~5-10s |
| `audit-trail` (1 mois) | 10000 événements | ~10-30s |

## Limitations

1. **Pas de données avant activation** : l'historique commence quand le mirror service démarre.
2. **Replay complet** : chaque requête rejoue le topic depuis le début (optimisation future : snapshots).
3. **Sujets non versionnés** : les attributs utilisateurs (LDAP) ne sont pas historisés ; seuls les sujets publiés dans le topic d'historique sont connus.
4. **Relations et délégations** : les relations ReBAC sont évaluées sur l'état courant (avec un avertissement), et les délégations ne sont pas appliquées.
5. **Politiques non versionnées** : une politique chargée depuis un fichier et jamais mise à jour par l'API n'a pas de version ; la politique courante est alors utilisée, avec l'avertissement `POLICY_VERSION_UNAVAILABLE`.

En cas d'erreur, un topic d'historique absent renvoie 503 `HISTORY_TOPIC_NOT_FOUND`, et un Kafka injoignable 503 `HISTORY_UNAVAILABLE`.

Un test d'intégration rejoue un vrai topic Kafka :

```bash
TIME_TRAVEL_TEST_KAFKA=localhost:9092 lein test :integration :only autho.time-travel-kafka-integration-test
```

## Roadmap

- [ ] Snapshots horaires/quotidiens pour accélérer les replays
- [ ] Historisation des attributs utilisateurs (LDAP)
- [ ] Dashboard temps réel des requêtes time-travel
- [ ] Export des audit trails en PDF/CSV
- [ ] Intégration avec SIEM (Splunk, ELK)

## Dépannage

### Le mirror service ne démarre pas

```bash
# Vérifier les dépendances
lein deps

# Vérifier que Kafka est accessible
nc -zv localhost 9092

# Vérifier les logs
tail -f logs/autho.log | grep topic-mirror
```

### Les topics history sont vides

```bash
# Vérifier que les producteurs envoient sur topics compactés
docker exec autho-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic invoices-compacted \
  --from-beginning \
  --max-messages 1

# Vérifier que le mirror service tourne
ps aux | grep topic-mirror
```

### Requêtes time-travel lentes

```bash
# Réduire la plage de temps
# Utiliser des snapshots (feature à venir)
# Augmenter les partitions des topics history
```

## Support

Pour toute question ou problème :
- GitHub Issues : https://github.com/rsclison/autho/issues
- Documentation complète : https://github.com/rsclison/autho/wiki
