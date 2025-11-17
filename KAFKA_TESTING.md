# Tests d'intégration Kafka - Objets Métiers

Ce document explique comment exécuter des tests d'intégration avec un vrai cluster Kafka pour les **objets métiers** (factures, engagements juridiques, contrats).

## Prérequis

- Docker et Docker Compose installés
- Java 11+ configuré
- Leiningen installé

## Architecture du système

```
┌──────────────────────────────────────────────────────────────────┐
│                    Architecture XACML/ABAC                       │
└──────────────────────────────────────────────────────────────────┘

SUBJECTS (Utilisateurs)                RESOURCES (Objets Métiers)
┌─────────────────┐                    ┌─────────────────────┐
│   LDAP Server   │                    │  Kafka Topics       │
│                 │                    │  - invoices         │
│  - Utilisateurs │                    │  - contracts        │
│  - Groupes      │                    │  - legal-commits    │
│  - Attributs    │                    └─────────────────────┘
└─────────────────┘                              │
         │                                       │
         │                                       v
         │                             ┌─────────────────────┐
         │                             │  Kafka PIP          │
         │                             │  (consumer)         │
         │                             └─────────────────────┘
         │                                       │
         │                                       v
         │                             ┌─────────────────────┐
         │                             │  RocksDB            │
         │                             │  (cache local)      │
         │                             └─────────────────────┘
         │                                       │
         v                                       v
┌──────────────────────────────────────────────────────────────────┐
│                Policy Decision Point (PDP)                       │
│   - Charge les attributs Subject depuis LDAP                    │
│   - Charge les attributs Resource depuis Kafka PIP              │
│   - Évalue les règles XACML                                     │
│   - Retourne Permit/Deny                                        │
└──────────────────────────────────────────────────────────────────┘
```

## Pourquoi cette architecture ?

- **LDAP** : Source d'autorité pour les utilisateurs et leurs attributs (rôles, département, clearance)
- **Kafka** : Stream temps-réel des objets métiers qui changent fréquemment
- **RocksDB** : Cache local pour accès rapide aux attributs des objets métiers
- **Séparation des préoccupations** : Chaque source gère ce qu'elle connaît le mieux

## 🚀 Démarrage rapide

### 1. Démarrer Kafka avec Docker Compose

```bash
# Démarrer le cluster Kafka (Zookeeper + Kafka + Kafka UI)
docker-compose up -d

# Vérifier que les services sont démarrés
docker-compose ps

# Voir les logs si nécessaire
docker-compose logs -f kafka
```

Les services suivants seront disponibles :
- **Kafka Broker**: `localhost:9092`
- **Zookeeper**: `localhost:2181`
- **Kafka UI**: http://localhost:8090

### 2. Configurer l'environnement

```bash
# Configurer Java 21 (requis)
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.3.9-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"

# Variables d'environnement pour les tests
export JWT_SECRET="test-jwt-secret-for-testing"
export API_KEY="test-api-key-for-testing"
```

### 3. Exécuter les tests d'intégration

```bash
# Tests avec objets métiers (factures, contrats, engagements juridiques)
./lein test :integration

# Ou lancer des tests spécifiques
./lein test autho.kafka-business-objects-test
./lein test autho.kafka-real-integration-test
```

### 4. Publier des données de test

Utilisez le producer pour envoyer des objets métiers à Kafka :

```bash
# Scénario basique : publier factures, contrats et engagements juridiques
./lein run -m autho.kafka-business-producer localhost:9092 basic

# Scénario workflow : simuler approbations, rejets, renouvellements
./lein run -m autho.kafka-business-producer localhost:9092 workflow
```

### 4. Arrêter les services

```bash
# Arrêter Kafka
docker-compose down

# Arrêter et nettoyer les volumes (supprime toutes les données)
docker-compose down -v
```

## 📋 Tests disponibles

### Tests objets métiers (`kafka_business_objects_test.clj`) ⭐ RECOMMANDÉ

| Test | Description | Objets testés |
|------|-------------|---------------|
| `invoice-authorization-test` | Autorisation basée sur montant et limites d'approbation | Factures |
| `legal-commitment-authorization-test` | Accès basé sur classification et clearance | Engagements juridiques (NDA, MOU) |
| `contract-authorization-test` | Règles multi-attributs (RGPD, criticité, PII) | Contrats (SaaS, consulting, lease) |
| `mixed-business-objects-test` | Plusieurs types d'objets avec topics séparés | Factures + Contrats + Engagements |
| `real-world-authorization-scenario-test` | Scénario complet avec workflow d'approbation | Factures avec limites managers/CFO |

### Tests techniques (`kafka_real_integration_test.clj`)

| Test | Description |
|------|-------------|
| `basic-kafka-message-flow-test` | Flux complet : Producer → Kafka → Consumer → RocksDB → Query |
| `kafka-merge-on-read-test` | Fusion d'attributs lors de mises à jour |
| `multiple-classes-kafka-test` | Plusieurs classes d'entités avec topics séparés |
| `high-volume-kafka-test` | Traite 100 messages pour tester les performances |
| `compacted-topic-test` | Comportement des topics compactés |
| `nested-json-kafka-test` | Structures JSON complexes et imbriquées |
| `kafka-real-performance-test` | Benchmark avec 1000 enregistrements |

### Exécuter des tests spécifiques

```bash
# Tous les tests d'intégration
lein test :integration

# Seulement les benchmarks
lein test :benchmark :integration

# Un test spécifique
lein test :only autho.kafka-real-integration-test/basic-kafka-message-flow-test
```

## 🔧 Configuration

### Topics Kafka

Les tests créent automatiquement des topics pour chaque type d'objet métier :

**Topics de production (compactés)** :
- `invoices-compacted` : Factures
- `contracts-compacted` : Contrats
- `legal-commitments-compacted` : Engagements juridiques (NDA, MOU, partenariats)

**Topics de test** :
- `invoice-events`, `invoice-events-test-X`
- `contract-events`, `contract-events-test-X`
- `legal-commitment-events`, `legal-commitment-events-test-X`

### Créer un topic manuellement

```bash
# Se connecter au conteneur Kafka
docker exec -it autho-kafka bash

# Créer un topic compacté
kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic user-attributes-compacted \
  --partitions 3 \
  --replication-factor 1 \
  --config cleanup.policy=compact \
  --config min.cleanable.dirty.ratio=0.01 \
  --config segment.ms=10000

# Lister les topics
kafka-topics --list --bootstrap-server localhost:9092

# Voir les détails d'un topic
kafka-topics --describe --bootstrap-server localhost:9092 --topic user-attributes-compacted
```

## 🧪 Utilisation du Producer dans le REPL

### Producer d'objets métiers (recommandé)

```clojure
(require '[autho.kafka-business-producer :as bp])

;; Créer un producer
(def p (bp/create-business-producer "localhost:9092"))

;; Publier toutes les factures de test
(bp/publish-test-invoices p "invoices-compacted")

;; Publier tous les contrats de test
(bp/publish-test-contracts p "contracts-compacted")

;; Publier tous les engagements juridiques
(bp/publish-test-legal-commitments p "legal-commitments-compacted")

;; Simuler une approbation de facture
(bp/simulate-invoice-approval p "invoices-compacted"
                               "INV-2024-001" "manager@company.com")

;; Simuler un rejet de facture
(bp/simulate-invoice-rejection p "invoices-compacted"
                                "INV-2024-004" "Budget exceeded")

;; Simuler un renouvellement de contrat
(bp/simulate-contract-renewal p "contracts-compacted"
                               "CT-2024-SOFT-001" "2026-12-31" 55000.00)

;; Simuler l'expiration d'un engagement
(bp/simulate-commitment-expiry p "legal-commitments-compacted"
                                "LC-2024-NDA-001")

;; Publier un message personnalisé
(bp/publish-message p "invoices-compacted" "INV-CUSTOM-001"
                    {:invoice-number "INV-CUSTOM-001"
                     :amount 15000.00
                     :status "pending"
                     :department "finance"})

;; Ne pas oublier de fermer
(.close p)
```

## 🔍 Monitoring avec Kafka UI

Kafka UI est disponible sur http://localhost:8090

Fonctionnalités :
- Visualiser les topics et leurs messages
- Voir les consumer groups et leur lag
- Inspecter les messages individuels
- Monitorer les performances

## 🐛 Dépannage

### Kafka ne démarre pas

```bash
# Vérifier les logs
docker-compose logs kafka

# Nettoyer et redémarrer
docker-compose down -v
docker-compose up -d
```

### Les tests sont ignorés (skipped)

Si vous voyez `⚠️ Skipping test: Kafka not available`, cela signifie que :
- Kafka n'est pas démarré : lancez `docker-compose up -d`
- Kafka n'est pas encore prêt : attendez quelques secondes après le démarrage

### Les messages ne sont pas consommés

```bash
# Vérifier les consumer groups
docker exec -it autho-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --list

# Voir le lag d'un consumer group
docker exec -it autho-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group autho-pip-user-attributes-compacted \
  --describe
```

### Nettoyer RocksDB

Les tests nettoient automatiquement RocksDB, mais si nécessaire :

```bash
# Sur Windows
rmdir /s /q %TEMP%\rocksdb-real-kafka-test

# Sur Linux/Mac
rm -rf /tmp/rocksdb-real-kafka-test
```

## 📊 Exemples de scénarios d'autorisation

### Scénario 1 : Approbation de facture basée sur le montant

```clojure
;; Règle d'autorisation :
;; - Manager peut approuver jusqu'à 10,000 EUR
;; - CFO peut approuver jusqu'à 100,000 EUR
;; - Board requis au-delà de 100,000 EUR

;; Publier une facture de 8,000 EUR
(bp/publish-message p "invoices-compacted" "INV-001"
                    {:invoice-number "INV-001"
                     :amount 8000.00
                     :status "pending"
                     :department "finance"})

;; Vérification dans le PDP :
;; - LDAP lookup: alice (role: "manager", approval-limit: 10000)
;; - Kafka PIP query: INV-001 (amount: 8000.00)
;; - Décision: PERMIT (8000 < 10000)

;; Approbation par le manager
(bp/simulate-invoice-approval p "invoices-compacted"
                               "INV-001" "alice@company.com")
```

### Scénario 2 : Accès à un engagement juridique confidentiel

```clojure
;; Règle d'autorisation :
;; - clearance-level >= required-clearance-level
;; - department == responsible-department OU role == "legal-counsel"

;; Publier un NDA confidentiel
(bp/publish-message p "legal-commitments-compacted" "LC-NDA-001"
                    {:commitment-id "LC-NDA-001"
                     :type "NDA"
                     :classification "confidential"
                     :required-clearance-level 3
                     :responsible-department "legal"})

;; Vérification dans le PDP :
;; - LDAP lookup: bob (clearance-level: 2, department: "legal")
;; - Kafka PIP query: LC-NDA-001 (required-clearance-level: 3)
;; - Décision: DENY (2 < 3)

;; - LDAP lookup: carol (clearance-level: 3, department: "legal")
;; - Kafka PIP query: LC-NDA-001 (required-clearance-level: 3)
;; - Décision: PERMIT (3 >= 3 AND department match)
```

### Scénario 3 : Contrat avec données personnelles (RGPD)

```clojure
;; Règle d'autorisation :
;; - Si contains-pii == true :
;;   - User doit avoir certification: "GDPR-trained"
;;   - OU role == "DPO" (Data Protection Officer)

;; Publier un contrat avec PII
(bp/publish-message p "contracts-compacted" "CT-CLOUD-001"
                    {:contract-id "CT-CLOUD-001"
                     :type "cloud-service"
                     :contains-pii true
                     :gdpr-compliant true
                     :data-classification "confidential"})

;; Vérification dans le PDP :
;; - LDAP lookup: diana (role: "admin", certifications: ["ISO27001"])
;; - Kafka PIP query: CT-CLOUD-001 (contains-pii: true)
;; - Décision: DENY (pas de certification GDPR)

;; - LDAP lookup: eve (role: "DPO", department: "legal")
;; - Kafka PIP query: CT-CLOUD-001 (contains-pii: true)
;; - Décision: PERMIT (role DPO autorisé pour accès PII)
```

### Scénario 4 : Workflow complet de facture

```clojure
;; 1. Création de facture (via système ERP → Kafka)
(bp/publish-message p "invoices-compacted" "INV-WF-001"
                    {:invoice-number "INV-WF-001"
                     :amount 45000.00
                     :status "draft"
                     :created-by "ap-clerk@company.com"
                     :department "procurement"})

;; 2. Soumission pour approbation
(bp/publish-message p "invoices-compacted" "INV-WF-001"
                    {:status "pending"
                     :submitted-date "2024-11-17"})

;; 3. Tentative d'approbation par manager (limite 10K)
;; → PDP retourne DENY car amount > approval-limit

;; 4. Escalade au CFO
(bp/publish-message p "invoices-compacted" "INV-WF-001"
                    {:status "pending-cfo-approval"
                     :escalated-date "2024-11-17"})

;; 5. Approbation par CFO
(bp/simulate-invoice-approval p "invoices-compacted"
                               "INV-WF-001" "cfo@company.com")

;; À chaque étape, le PDP vérifie les autorisations basées sur :
;; - Attributs utilisateur (LDAP) : role, approval-limit, department
;; - Attributs facture (Kafka) : amount, status, department
```

## 🔗 Liens utiles

- [Documentation Kafka](https://kafka.apache.org/documentation/)
- [Kafka UI GitHub](https://github.com/provectus/kafka-ui)
- [RocksDB Documentation](https://github.com/facebook/rocksdb/wiki)

## 📝 Notes importantes

1. **Topics compactés** : Pour un vrai PIP, utilisez des topics avec `cleanup.policy=compact`
2. **Performances** : Ajustez le nombre de partitions selon vos besoins de parallélisme
3. **Rétention** : Les topics compactés gardent uniquement la dernière valeur par clé
4. **Ordre des messages** : L'ordre est garanti uniquement au sein d'une partition
5. **Sérialisation** : Les tests utilisent JSON avec jsonista pour la sérialisation
