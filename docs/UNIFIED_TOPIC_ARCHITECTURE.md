# Architecture Simplifiée : Topic Kafka Unifié

## Vue d'Ensemble

**Architecture simplifiée avec un seul topic** pour tous les objets métier au lieu de topics séparés par type.

### Avant (Complexe)
```
Producteurs
    ├─→ invoices-compacted (Factures)
    ├─→ contracts-compacted (Contrats)
    └─→ legal-commitments-compacted (Engagements)
```

### Après (Simplifié) ✅
```
Producteurs
    └─→ business-objects-compacted (TOUS les objets)
            ↓
        RocksDB Column Families
            ├─ Facture
            ├─ Contrat
            └─ EngagementJuridique
```

## Avantages

✅ **Plus simple** : 1 topic au lieu de 3+
✅ **Plus scalable** : Facile d'ajouter de nouveaux types d'objets
✅ **Configuration simplifiée** : Moins de configuration Kafka
✅ **Compatible RocksDB** : Routage vers Column Families via champ `class`
✅ **Merge-on-read** : Toujours supporté pour mises à jour partielles

## Format des Messages

### Message Requis

Chaque message **doit contenir** deux champs obligatoires :

```json
{
  "class": "Facture",        // REQUIS: nom de la classe pour routing
  "id": "INV-123",            // REQUIS: identifiant unique de l'objet
  "amount": 5000,             // Attributs métier...
  "currency": "EUR",
  "status": "approved"
}
```

### Champs Obligatoires

| Champ | Type | Description |
|-------|------|-------------|
| `class` | String | Nom de la classe : `Facture`, `Contrat`, `EngagementJuridique` |
| `id` | String | Identifiant unique de l'objet dans sa classe |

Tous les autres champs sont des attributs métier et seront stockés dans RocksDB.

## Routing vers Column Families

Le consumer Kafka lit le topic unifié et route chaque message vers la **Column Family RocksDB** appropriée :

```
Message reçu:
{
  "class": "Facture",
  "id": "INV-123",
  "amount": 5000
}

↓ Router extrait "class" = "Facture"

↓ Recherche Column Family "Facture" dans RocksDB

↓ Stockage dans CF "Facture" avec clé "INV-123"

→ Attributs disponibles pour PDP via query-pip
```

## Exemples de Production

### 1. Envoyer une Facture

```bash
echo '{
  "class": "Facture",
  "id": "INV-123",
  "amount": 5000,
  "currency": "EUR",
  "status": "approved",
  "customer": "ACME Corp"
}' | kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic business-objects-compacted
```

### 2. Envoyer un Contrat

```bash
echo '{
  "class": "Contrat",
  "id": "CTR-456",
  "classification": "confidential",
  "gdpr-compliant": true,
  "effective-date": "2024-01-01"
}' | kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic business-objects-compacted
```

### 3. Mise à Jour Partielle (Merge)

```bash
# Message initial
echo '{
  "class": "Facture",
  "id": "INV-123",
  "amount": 5000,
  "status": "pending"
}' | kafka-console-producer ...

# Mise à jour partielle (seulement status)
echo '{
  "class": "Facture",
  "id": "INV-123",
  "status": "paid",
  "payment-date": "2024-11-18"
}' | kafka-console-producer ...

# Résultat dans RocksDB (merge-on-read):
{
  "amount": 5000,              # De message 1
  "status": "paid",             # Mis à jour par message 2
  "payment-date": "2024-11-18"  # Ajouté par message 2
}
```

## Configuration

### Topic Kafka

```bash
# Créer le topic unifié
kafka-topics --create \
  --topic business-objects-compacted \
  --partitions 3 \
  --replication-factor 1 \
  --config cleanup.policy=compact \
  --config compression.type=lz4
```

### Configuration PIP (`resources/pips.edn`)

```clojure
{:type :kafka-pip-unified
 :kafka-topic "business-objects-compacted"
 :kafka-bootstrap-servers "localhost:9092"
 :classes ["Facture" "Contrat" "EngagementJuridique"]}
```

### RocksDB Column Families

Les Column Families sont automatiquement créées au démarrage :

```clojure
;; Dans pdp-prop.properties
kafka.pip.rocksdb.path=/tmp/autho-rocksdb
kafka.pip.classes=Facture,Contrat,EngagementJuridique
```

## Migration depuis Topics Séparés

Si vous utilisez déjà des topics séparés, voici la stratégie de migration :

### Option 1 : Migration Immédiate

```bash
# 1. Arrêter les producteurs
# 2. Créer le topic unifié
./scripts/create-history-topics.sh

# 3. Migrer les données existantes (script de migration)
# Pour chaque ancien topic, ajouter le champ "class" aux messages

# 4. Reconfigurer les producteurs pour utiliser business-objects-compacted
# 5. Redémarrer
```

### Option 2 : Migration Progressive (Double Write)

```bash
# 1. Producteurs écrivent dans BOTH anciens et nouveau topic
# 2. Basculer consumers vers nouveau topic
# 3. Arrêter écriture sur anciens topics
# 4. Supprimer anciens topics après rétention
```

## Ajouter un Nouveau Type d'Objet

Très simple avec l'architecture unifiée :

```bash
# 1. Ajouter la classe dans pips.edn
{:type :kafka-pip-unified
 :kafka-topic "business-objects-compacted"
 :kafka-bootstrap-servers "localhost:9092"
 :classes ["Facture" "Contrat" "EngagementJuridique" "Commande"]}  # ← Nouveau

# 2. Ajouter dans pdp-prop.properties
kafka.pip.classes=Facture,Contrat,EngagementJuridique,Commande

# 3. Redémarrer le serveur (création auto de Column Family)

# 4. Commencer à produire
echo '{"class":"Commande","id":"CMD-001","total":1200}' | ...
```

**Aucune création de topic nécessaire !** Le même topic unifié gère tous les types.

## Performance

### Comparaison

| Métrique | Topics Séparés | Topic Unifié |
|----------|----------------|--------------|
| Nombre de topics | 3+ (scaling) | 1 (constant) |
| Consumers | 3+ | 1 |
| Latence | Similaire | Similaire |
| Débit | ~10k msg/s | ~10k msg/s |
| Complexité | Haute | Faible |

### Optimisations

- **Partitionnement** : 3 partitions par défaut, augmenter pour plus de débit
- **Compression** : LZ4 activée (30-50% économie espace)
- **Batch size** : Ajuster `batch.size` producteur si gros volumes

## Monitoring

### Vérifier les Messages

```bash
# Consommer du topic unifié
docker exec autho-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic business-objects-compacted \
  --from-beginning
```

### Vérifier RocksDB

```bash
# Lister les Column Families
curl http://localhost:3000/admin/listRDB

# Résultat:
["Facture", "Contrat", "EngagementJuridique"]
```

### Statistiques

```bash
# Consumer lag
kafka-consumer-groups --describe \
  --group autho-pip-unified \
  --bootstrap-server localhost:9092
```

## Exemples Complets

### Exemple 1 : Producteur Python

```python
from kafka import KafkaProducer
import json

producer = KafkaProducer(
    bootstrap_servers=['localhost:9092'],
    value_serializer=lambda v: json.dumps(v).encode('utf-8')
)

# Envoyer une facture
invoice = {
    "class": "Facture",
    "id": "INV-123",
    "amount": 5000,
    "currency": "EUR"
}

producer.send('business-objects-compacted', value=invoice)
producer.flush()
```

### Exemple 2 : Producteur Java

```java
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9092");
props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

KafkaProducer<String, String> producer = new KafkaProducer<>(props);

String invoice = "{\"class\":\"Facture\",\"id\":\"INV-123\",\"amount\":5000}";
ProducerRecord<String, String> record =
    new ProducerRecord<>("business-objects-compacted", invoice);

producer.send(record);
producer.close();
```

### Exemple 3 : Script Shell

```bash
#!/bin/bash
# Voir examples/unified_producer_example.sh pour exemple complet

chmod +x examples/unified_producer_example.sh
./examples/unified_producer_example.sh
```

## Time-Travel avec Topic Unifié

Le time-travel fonctionne exactement pareil :

```
business-objects-compacted  →  Mirror Service  →  business-objects-history
                                                           ↓
                                                    Time-Travel Engine
```

Un seul topic history au lieu de multiples !

## Dépannage

### Messages non routés

**Symptôme** : Messages consommés mais pas dans RocksDB

**Cause** : Champ `class` manquant ou invalide

**Solution** :
```bash
# Vérifier format des messages
docker exec autho-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic business-objects-compacted \
  --from-beginning \
  --max-messages 1
```

### Column Family manquante

**Symptôme** : `No Column Family found for class: X`

**Cause** : Classe pas dans configuration

**Solution** : Ajouter dans `pips.edn` et `pdp-prop.properties`, redémarrer

### Merge incorrect

**Symptôme** : Attributs écrasés au lieu de fusionnés

**Cause** : Topic pas en mode `compact`

**Solution** :
```bash
kafka-configs --alter \
  --topic business-objects-compacted \
  --add-config cleanup.policy=compact
```

## FAQ

**Q: Puis-je mixer types d'objets dans le même message ?**
R: Non. Un message = un objet = une classe.

**Q: Que se passe-t-il si j'oublie le champ "class" ?**
R: Message ignoré avec warning dans les logs.

**Q: Puis-je changer le nom de la classe après ?**
R: Non recommandé. Créer nouvelle classe et migrer données.

**Q: Performance avec 100+ types d'objets ?**
R: Pas de problème. RocksDB gère très bien nombreuses Column Families.

**Q: Compatible avec l'ancien code ?**
R: Oui ! Utiliser `kafka-pip-unified` au lieu de `kafka-pip` dans config.

## Résumé

✅ **1 topic** au lieu de 3+
✅ **Routing automatique** via champ `class`
✅ **Column Families RocksDB** pour séparation logique
✅ **Merge-on-read** maintenu
✅ **Time-travel** simplifié (1 topic history)
✅ **Scalable** : facile d'ajouter nouveaux types

**Migration recommandée** pour toute nouvelle installation !
