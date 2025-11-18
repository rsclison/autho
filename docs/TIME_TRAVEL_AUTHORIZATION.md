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
    "action": "view",
    "resource": {"class": "Facture", "id": "INV-12345"}
  }'
```

**Réponse** :
```json
{
  "timestamp": "2024-03-15T10:30:00Z",
  "decision": "PERMIT",
  "snapshot-size": 1523,
  "reason": "User had role:manager and invoice amount was 5000€ at that time"
}
```

### Endpoint 2 : `/who-was-authorized-at`

Liste tous les sujets autorisés à accéder une ressource à un moment T.

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
  "authorized-subjects": [
    {"id": "alice@company.com", "role": "manager"},
    {"id": "bob@company.com", "role": "finance-director"}
  ]
}
```

### Endpoint 3 : `/what-could-access-at`

Liste toutes les ressources qu'un sujet pouvait accéder à un moment T.

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
  "accessible-resources": [
    {"class": "Facture", "id": "INV-12345", "amount": 5000},
    {"class": "Facture", "id": "INV-12346", "amount": 7500},
    {"class": "Contrat", "id": "CTR-789", "classification": "public"}
  ]
}
```

### Endpoint 4 : `/audit-trail`

Retourne l'historique complet des accès à une ressource sur une période.

**Requête** :
```bash
curl -X POST http://localhost:3000/audit-trail \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -d '{
    "resourceClass": "Facture",
    "resourceId": "INV-12345",
    "startTime": "2024-03-01T00:00:00Z",
    "endTime": "2024-03-31T23:59:59Z"
  }'
```

**Réponse** :
```json
{
  "query": "audit-trail",
  "resource": {"class": "Facture", "id": "INV-12345"},
  "time-range": {
    "start": "2024-03-01T00:00:00Z",
    "end": "2024-03-31T23:59:59Z"
  },
  "events": [
    {
      "timestamp": "2024-03-15T10:30:00Z",
      "subject": "alice@company.com",
      "action": "view",
      "decision": "PERMIT"
    },
    {
      "timestamp": "2024-03-20T14:22:13Z",
      "subject": "bob@company.com",
      "action": "approve",
      "decision": "PERMIT"
    }
  ]
}
```

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

1. **Pas de données avant activation** : L'historique commence quand le mirror service démarre
2. **Replay complet** : Chaque requête rejoue depuis le début (optimisation future: snapshots)
3. **Subjects non versionnés** : Les attributs utilisateurs (LDAP) ne sont pas historisés pour l'instant

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
