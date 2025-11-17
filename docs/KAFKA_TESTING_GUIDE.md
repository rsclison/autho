# Guide de Test Kafka pour Autho

Ce guide explique comment tester le système Kafka PIP qui alimente la base d'objets métiers utilisée par les règles d'autorisation.

## 📑 Table des Matières

1. [Vue d'ensemble](#vue-densemble)
2. [Architecture de Test](#architecture-de-test)
3. [Tests Unitaires](#tests-unitaires)
4. [Tests d'Intégration](#tests-dintégration)
5. [Tests End-to-End](#tests-end-to-end)
6. [Tests avec Kafka Réel](#tests-avec-kafka-réel)
7. [Tests de Performance](#tests-de-performance)
8. [Dépannage](#dépannage)

---

## Vue d'ensemble

### Flux de Données à Tester

```
┌─────────────┐
│   Kafka     │  Messages JSON avec attributs métiers
│   Topic     │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Consumer   │  Consommation et parsing JSON
│   Thread    │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   RocksDB   │  Stockage local avec merge-on-read
│   State     │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│    Query    │  Résolution d'attributs pour règles
│     PIP     │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│    Rule     │  Évaluation d'autorisation
│   Engine    │
└─────────────┘
```

### Niveaux de Test

| Niveau | Fichier | Objectif | Dépendances |
|--------|---------|----------|-------------|
| **Unitaire** | `kafka_pip_test.clj` | Tester composants isolés (RocksDB, merge, query) | RocksDB uniquement |
| **Intégration** | `kafka_integration_test.clj` | Tester flux complet sans Kafka réel | RocksDB + simulation messages |
| **End-to-End** | `kafka_authorization_e2e_test.clj` | Tester impact sur décisions d'autorisation | Tous composants |
| **Production** | `kafka_test_producer.clj` | Tester avec Kafka réel | Kafka broker |

---

## Architecture de Test

### Stratégie Générale

```clojure
;; 1. UNIT TESTS - Composants isolés
;;    ├─ RocksDB initialization
;;    ├─ Column family operations
;;    ├─ JSON merge logic
;;    └─ Query PIP

;; 2. INTEGRATION TESTS - Simulation de messages Kafka
;;    ├─ Message processing flow
;;    ├─ Multiple entity classes
;;    └─ PIP dispatcher integration

;; 3. E2E TESTS - Scénarios d'autorisation complets
;;    ├─ Manager access control
;;    ├─ Dynamic role updates
;;    ├─ Threshold-based authorization
;;    └─ Multi-attribute rules

;; 4. PRODUCTION TESTS - Avec Kafka réel
;;    ├─ Test data producer
;;    ├─ Load testing
;;    └─ Performance benchmarks
```

---

## Tests Unitaires

### Fichier: `test/autho/kafka_pip_test.clj`

#### Ce qui est testé

1. **Initialisation RocksDB** (`rocksdb-initialization-test`)
   - Création de la base de données partagée
   - Gestion des column families
   - Cleanup propre

2. **Opérations RocksDB** (`rocksdb-column-family-operations-test`)
   - Écriture de données
   - Lecture de données
   - Sérialisation/désérialisation JSON

3. **Logique de Merge** (`json-merge-logic-test`)
   - Fusion d'attributs existants et nouveaux
   - Préservation des attributs non modifiés
   - Mise à jour des attributs modifiés

4. **Gestion des Null** (`json-merge-null-handling-test`)
   - Première insertion (pas d'état existant)
   - Gestion des valeurs null

5. **Query PIP** (`query-pip-test`)
   - Récupération d'attributs
   - Clés inexistantes
   - Classes invalides

6. **Gestion des Column Families**
   - Liste des classes
   - Nettoyage de données

7. **Gestion d'Erreurs** (`malformed-json-handling-test`)
   - JSON malformé
   - Récupération après erreur

#### Exécution

```bash
# Tous les tests unitaires
lein test autho.kafka-pip-test

# Test spécifique
lein test :only autho.kafka-pip-test/json-merge-logic-test

# Avec coverage
lein cloverage --ns-regex autho.kafka-pip
```

#### Exemple de Test Unitaire

```clojure
(deftest json-merge-logic-test
  (testing "New attributes overwrite existing ones"
    (kpip/open-shared-db test-db-path ["user"])
    (let [cf-handle (get @kpip/column-families "user")]

      ;; État initial
      (put-to-rocksdb cf-handle "user456"
                      {:name "Bob" :role "developer" :team "backend"})

      ;; Mise à jour (role change, nouveau champ)
      (merge-and-put cf-handle "user456"
                     {:role "senior-developer" :location "Paris"})

      ;; Vérification
      (let [result (query-rocksdb cf-handle "user456")]
        (is (= "Bob" (:name result)))               ;; Préservé
        (is (= "senior-developer" (:role result)))  ;; Mis à jour
        (is (= "backend" (:team result)))           ;; Préservé
        (is (= "Paris" (:location result)))))       ;; Ajouté

    (kpip/close-shared-db)))
```

---

## Tests d'Intégration

### Fichier: `test/autho/kafka_integration_test.clj`

#### Ce qui est testé

1. **Flux Complet de Messages** (`kafka-message-processing-flow-test`)
   - Message Kafka → RocksDB → Query PIP
   - Mises à jour successives
   - Merge-on-read

2. **Classes Multiples** (`multiple-entity-classes-test`)
   - Indépendance des classes (user, resource)
   - Pas de contamination croisée

3. **Intégration PIP Dispatcher** (`pip-dispatcher-integration-test`)
   - Routage correct vers Kafka PIP
   - Configuration PIP (id-key, class, etc.)

4. **Scénario Réaliste** (`dynamic-authorization-scenario-test`)
   - Mise à jour d'attributs utilisateur
   - Impact sur décisions d'autorisation

5. **Volume Élevé** (`high-volume-message-processing-test`)
   - 100+ messages
   - Performance de query

6. **Suppression d'Attributs** (`attribute-deletion-via-null-test`)
   - Valeurs null
   - Suppression de champs

7. **Attributs Complexes** (`nested-attributes-test`)
   - JSON imbriqué
   - Arrays et maps

8. **Mises à Jour Concurrentes** (`concurrent-updates-test`)
   - Mises à jour rapides successives
   - Cohérence des données

#### Exécution

```bash
# Tous les tests d'intégration
lein test autho.kafka-integration-test

# Test spécifique
lein test :only autho.kafka-integration-test/kafka-message-processing-flow-test
```

#### Helper: Simuler un Message Kafka

```clojure
(defn simulate-kafka-message
  "Simule le traitement d'un message Kafka"
  [class-name entity-id attributes]
  (let [cf-handle (get @kpip/column-families class-name)
        key entity-id
        ;; Récupérer état existant
        existing-bytes (.get @kpip/shared-db cf-handle
                             (.getBytes key StandardCharsets/UTF_8))
        existing-attrs (when existing-bytes
                         (json/read-value (String. existing-bytes)))
        ;; Fusionner
        merged-attrs (merge existing-attrs attributes)
        merged-json (json/write-value-as-string merged-attrs)]
    ;; Écrire résultat
    (.put @kpip/shared-db cf-handle
          (.getBytes key StandardCharsets/UTF_8)
          (.getBytes merged-json StandardCharsets/UTF_8))))

;; Usage
(simulate-kafka-message "user" "alice"
                        {:name "Alice" :role "developer"})
```

---

## Tests End-to-End

### Fichier: `test/autho/kafka_authorization_e2e_test.clj`

#### Scénarios Testés

1. **Contrôle d'Accès Manager** (`manager-access-with-kafka-attributes-test`)
   - Règle: Managers accèdent à ressources de leur département
   - Attributs depuis Kafka PIP
   - Matching multi-attributs

2. **Mise à Jour Dynamique de Rôle** (`dynamic-role-update-authorization-test`)
   - État initial: developer (accès refusé)
   - Promotion via Kafka: admin (accès accordé)
   - Impact immédiat sur autorisation

3. **Autorisation par Seuil** (`threshold-based-authorization-test`)
   - Limite d'approbation manager: $10K
   - Demande $5K: accordée
   - Demande $50K: refusée
   - Augmentation limite → nouvelle décision

4. **Règle Multi-Attributs** (`multi-attribute-rule-matching-test`)
   - Équipe, clearance, localisation
   - Toutes conditions doivent matcher

5. **Délégation** (`delegation-with-kafka-attributes-test`)
   - Attributs du délégateur
   - Chaîne de délégation

6. **Accès Temporel** (`temporal-access-with-kafka-test`)
   - Dates d'expiration
   - Statut actif/expiré

7. **ABAC Complet** (`abac-full-scenario-test`)
   - Scénario médical: docteur/patient
   - Spécialité, hôpital, vérification
   - Transferts dynamiques

8. **Performance** (`rapid-authorization-decisions-test`)
   - 1000 décisions d'autorisation
   - Avec queries Kafka PIP
   - Benchmark

#### Exécution

```bash
# Tous les tests E2E
lein test autho.kafka-authorization-e2e-test

# Scénario spécifique
lein test :only autho.kafka-authorization-e2e-test/abac-full-scenario-test

# Tests de performance uniquement
lein test :only autho.kafka-authorization-e2e-test/rapid-authorization-decisions-test
```

#### Exemple: Test ABAC Complet

```clojure
(deftest abac-full-scenario-test
  (testing "ABAC scenario with dynamic Kafka updates"
    (kpip/open-shared-db test-db-path ["user" "resource"])

    ;; Setup initial
    (simulate-kafka-message "user" "doctor"
      {:specialty "cardiology" :hospital "central-hospital" :verified true})

    (simulate-kafka-message "resource" "patient-record-123"
      {:department "cardiology" :hospital "central-hospital" :requires-verification true})

    ;; Scénario 1: Accès OK (même spécialité, même hôpital)
    (let [user (kpip/query-pip "user" "doctor")
          resource (kpip/query-pip "resource" "patient-record-123")]
      (is (= (:specialty user) (:department resource)))
      (is (= (:hospital user) (:hospital resource))))

    ;; Événement: Docteur transféré
    (simulate-kafka-message "user" "doctor" {:hospital "north-hospital"})

    ;; Scénario 2: Accès refusé (hôpital différent)
    (let [user (kpip/query-pip "user" "doctor")
          resource (kpip/query-pip "resource" "patient-record-123")]
      (is (not= (:hospital user) (:hospital resource))))

    (kpip/close-shared-db)))
```

---

## Tests avec Kafka Réel

### Fichier: `test/autho/kafka_test_producer.clj`

#### Prérequis

1. **Kafka en cours d'exécution**
   ```bash
   # Avec Docker
   docker-compose up -d kafka zookeeper

   # Ou Kafka local
   bin/kafka-server-start.sh config/server.properties
   ```

2. **Topics créés**
   ```bash
   # Topic avec log compaction (recommandé)
   kafka-topics.sh --create \
     --topic user-attributes-compacted \
     --partitions 3 \
     --replication-factor 1 \
     --config cleanup.policy=compact \
     --bootstrap-server localhost:9092

   kafka-topics.sh --create \
     --topic resource-attributes-compacted \
     --partitions 3 \
     --replication-factor 1 \
     --config cleanup.policy=compact \
     --bootstrap-server localhost:9092
   ```

#### Scénarios Disponibles

##### 1. Scénario Basique

Publie des données de test initiales.

```bash
# Ligne de commande
lein run -m autho.kafka-test-producer \
  localhost:9092 \
  user-attributes-compacted \
  resource-attributes-compacted \
  basic

# Ou depuis REPL
(require '[autho.kafka-test-producer :as producer])

(producer/run-basic-test-scenario
  "localhost:9092"
  "user-attributes-compacted"
  "resource-attributes-compacted")
```

**Données publiées:**
- 5 utilisateurs (alice, bob, charlie, diana, eve)
- 6 ressources (documents, bases de données, projets)

##### 2. Scénario Dynamique

Simule des mises à jour d'attributs en temps réel.

```bash
lein run -m autho.kafka-test-producer \
  localhost:9092 \
  user-attributes-compacted \
  _ \
  dynamic
```

**Événements simulés:**
- Promotion d'Alice (developer → senior-developer)
- Upgrade de clearance d'Alice (2 → 3)
- Réorganisation d'équipe (Bob, Diana)
- Révocation d'accès (Diana)

##### 3. Scénario de Charge

Test de performance avec données en masse.

```bash
# 1000 enregistrements
lein run -m autho.kafka-test-producer \
  localhost:9092 \
  user-attributes-compacted \
  _ \
  load \
  1000

# 10,000 enregistrements
lein run -m autho.kafka-test-producer \
  localhost:9092 \
  user-attributes-compacted \
  _ \
  load \
  10000
```

#### Vérification des Messages

```bash
# Consumer console Kafka
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic user-attributes-compacted \
  --from-beginning \
  --property print.key=true \
  --property key.separator=" -> "

# Avec jq pour JSON formaté
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic user-attributes-compacted \
  --from-beginning \
  --property print.key=true | jq .
```

#### Utilisation Programmatique

```clojure
(require '[autho.kafka-test-producer :as producer])

;; Créer un producer
(let [p (producer/create-test-producer "localhost:9092")]

  ;; Publier un message
  (producer/publish-message p "user-attributes-compacted"
                            "test-user"
                            {:name "Test User" :role "tester"})

  ;; Simuler une promotion
  (producer/simulate-user-promotion p "user-attributes-compacted"
                                    "alice" "manager" 90000)

  ;; Simuler upgrade clearance
  (producer/simulate-clearance-upgrade p "user-attributes-compacted"
                                       "bob" 5)

  ;; Cleanup
  (.close p))
```

---

## Tests de Performance

### Benchmarks Inclus

#### 1. Query Performance (Unit Test)

```clojure
(deftest kafka-pip-performance-test
  (testing "Query 1000 users"
    ;; Setup: Populate 1000 users
    ;; Benchmark: Query all 1000
    ;; Assertion: < 1 second total
    ))
```

**Objectif:** < 1ms par query en moyenne

#### 2. Authorization Performance (E2E Test)

```clojure
(deftest rapid-authorization-decisions-test
  (testing "1000 authorization decisions"
    ;; Setup: 100 users, 100 resources
    ;; Benchmark: 1000 auth checks with PIP queries
    ;; Assertion: < 2 seconds total
    ))
```

**Objectif:** < 2ms par décision d'autorisation

#### 3. Bulk Publishing (Producer)

```bash
lein run -m autho.kafka-test-producer \
  localhost:9092 \
  user-attributes-compacted \
  _ \
  load \
  10000
```

**Métriques affichées:**
- Temps total de publication
- Throughput (messages/sec)

### Exécution des Benchmarks

```bash
# Tests de performance uniquement (tag :benchmark)
lein test :only :benchmark

# Avec profiling
lein trampoline run -m clojure.main \
  -e "(require 'autho.kafka-integration-test)" \
  -e "(time (autho.kafka-integration-test/kafka-pip-performance-test))"
```

---

## Dépannage

### Problème: Tests RocksDB échouent avec "lock already held"

**Cause:** Instance RocksDB non fermée du test précédent

**Solution:**
```clojure
;; S'assurer que cleanup est appelé
(use-fixtures :each
  (fn [f]
    (cleanup-test-db)
    (f)
    (cleanup-test-db)
    ;; Force close si nécessaire
    (when @kpip/shared-db
      (kpip/close-shared-db))))
```

### Problème: "Column family not found"

**Cause:** Tentative d'accès à une column family non initialisée

**Solution:**
```clojure
;; Vérifier l'ordre d'initialisation
(kpip/open-shared-db test-db-path ["user" "resource"])  ;; D'abord
(kpip/query-pip "user" "alice")  ;; Ensuite
```

### Problème: Kafka consumer ne reçoit pas de messages

**Cause:** Topic pas créé ou mauvaise configuration

**Solution:**
```bash
# Vérifier existence du topic
kafka-topics.sh --list --bootstrap-server localhost:9092

# Vérifier messages dans topic
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic user-attributes-compacted \
  --from-beginning

# Vérifier consumer group
kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group autho-pip-user-attributes-compacted
```

### Problème: JSON parsing errors

**Cause:** Format de message invalide

**Solution:**
```clojure
;; Valider JSON avant publication
(require '[jsonista.core :as json])

(defn validate-and-publish [producer topic key attrs]
  (try
    (let [json-str (json/write-value-as-string attrs)]
      ;; Parse pour validation
      (json/read-value json-str)
      ;; Publier
      (publish-message producer topic key attrs))
    (catch Exception e
      (println "Invalid JSON:" attrs)
      (throw e))))
```

### Problème: Merge ne se comporte pas comme attendu

**Cause:** Merge Clojure ne supprime pas les clés

**Solution:**
```clojure
;; Pour suppression explicite de clés
(defn merge-with-deletes [existing new]
  (reduce-kv
    (fn [m k v]
      (if (nil? v)
        (dissoc m k)  ;; Supprimer si nil
        (assoc m k v)))  ;; Sinon mettre à jour
    existing
    new))
```

---

## Checklist de Test Complète

### Avant de Déployer en Production

- [ ] **Tests Unitaires**
  - [ ] RocksDB initialization
  - [ ] JSON merge logic
  - [ ] Query PIP avec clés existantes/inexistantes
  - [ ] Gestion des erreurs

- [ ] **Tests d'Intégration**
  - [ ] Flux message → RocksDB → query
  - [ ] Classes multiples indépendantes
  - [ ] Volume élevé (100+ messages)

- [ ] **Tests E2E**
  - [ ] Décisions d'autorisation basées sur Kafka
  - [ ] Mises à jour dynamiques d'attributs
  - [ ] Scénarios ABAC complets

- [ ] **Tests avec Kafka Réel**
  - [ ] Scénario basique (données initiales)
  - [ ] Scénario dynamique (mises à jour)
  - [ ] Test de charge (1000+ messages)

- [ ] **Tests de Performance**
  - [ ] Query: < 1ms/query
  - [ ] Authorization: < 2ms/décision
  - [ ] Bulk publishing: > 1000 msgs/sec

- [ ] **Tests de Robustesse**
  - [ ] Redémarrage consumer
  - [ ] Kafka indisponible (reconnexion)
  - [ ] RocksDB corruption recovery
  - [ ] JSON malformé

---

## Ressources Supplémentaires

- **Documentation Kafka PIP:** `resources/kafka_readme.md`
- **Configuration PIP:** `resources/pips.edn`
- **Propriétés PDP:** `resources/pdp-prop.properties`
- **Exemples de Règles:** `resources/rules.edn`

---

## Contact & Support

Pour questions ou problèmes:
1. Vérifier ce guide de test
2. Consulter `kafka_readme.md`
3. Examiner les logs d'erreur Kafka PIP
4. Ouvrir une issue GitHub avec détails de reproduction

---

**Version:** 1.0
**Dernière mise à jour:** 2024-06-17
