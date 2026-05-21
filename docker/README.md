# Environnement de test Autho

## Démarrage

```bash
cd docker
docker compose up -d --build
```

## Services

| Service        | URL / Port                    | Credentials         |
|----------------|-------------------------------|---------------------|
| Kafka          | `localhost:9092`              | —                   |
| Kafka UI       | http://localhost:8090         | —                   |
| OpenLDAP       | `localhost:389`               | admin / admin       |
| phpLDAPadmin   | http://localhost:8091         | voir ci-dessous      |
| Autho          | http://localhost:8080         | API key ci-dessous   |
| Admin UI       | http://localhost:8080/admin/ui | API key ci-dessous  |

API key de démonstration :

```text
abcdefghijklmnopqrstuvwxyz123456
```

Dans cette stack, Autho tourne aussi en container. RocksDB est embarqué dans le container Autho et stocké dans le volume Docker `autho_data`, sous `/data/rocksdb/shared`.

### phpLDAPadmin — connexion
- Login DN : `cn=admin,dc=example,dc=com`
- Mot de passe : `admin`

---

## Injecter des objets dans Kafka

### Depuis le host (Python installé)
```bash
cd kafka-producer
pip install -r requirements.txt

python produce.py                          # menu interactif
python produce.py --class Facture          # 20 Factures
python produce.py --class Contrat -n 50   # 50 Contrats
python produce.py --all -n 30             # toutes les classes, 30 par classe
python produce.py --invalidate subject 001 # invalider cache sujet 001
```

### Via Docker
```bash
# Menu interactif
docker run --rm -it --network autho-dev \
  $(docker build -q ./kafka-producer) \
  --bootstrap kafka:29092

# Injecter toutes les classes (50 objets chacune)
docker run --rm --network autho-dev \
  $(docker build -q ./kafka-producer) \
  --bootstrap kafka:29092 --all -n 50
```

### Scenario bout en bout Kafka -> RocksDB -> regle Autho

Depuis la racine du depot, le script suivant lance la stack, injecte des factures deterministes dans Kafka, laisse le consumer Autho mettre a jour RocksDB, puis execute deux decisions :

```bash
./examples/container_kafka_rocksdb_demo.sh
```

La demonstration utilise `docker/kafka-producer/test-factures.json` :

- `FAC-TEST-01` : service `service1`, montant `30000` ;
- `FAC-TEST-02` : service `service1`, montant `80000`.

L'API key Autho est liee au sujet `Person` `001`, charge depuis LDAP :

- role `chef_de_service` ;
- service `service1` ;
- seuil `50000`.

La regle `R1` de `resources/jrules.edn` autorise la lecture d'une `Facture` si :

```clojure
[= [Person $s role] "chef_de_service"]
[= [Person $s service] [Facture $r service]]
[< [Facture $r montant] [Person $s seuil]]
```

Resultat attendu :

- `FAC-TEST-01` est autorisee, car `30000 < 50000` ;
- `FAC-TEST-02` est refusee, car `80000 > 50000`.
- le script appelle ensuite l'endpoint legacy `/explain` pour montrer la trace de decision sans exiger de licence Pro. L'endpoint stable `/v1/authz/explain` reste disponible pour les environnements licencies.

Commandes manuelles equivalentes :

```bash
cd docker
docker compose up -d --build kafka kafka-init kafka-ui openldap phpldapadmin autho

docker compose --profile tools run --rm \
  -v "$PWD/kafka-producer/test-factures.json:/data/test-factures.json:ro" \
  kafka-producer \
  --bootstrap kafka:29092 \
  --file /data/test-factures.json

curl -X POST http://localhost:8080/v1/authz/decisions \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "Facture", "id": "FAC-TEST-01"},
    "operation": "lire"
  }'
```

---

## Configuration Autho (resources/pdp-prop.properties)

La stack Docker utilise :

- `resources/pdp-prop.docker.properties` via `PDP_CONFIG_PATH` ;
- `resources/pips.docker.edn` via `PIPS_CONFIG_PATH`.

Ces fichiers pointent vers les noms de services Docker (`openldap`, `kafka:29092`) au lieu de `localhost`.

Pour utiliser le LDAP Docker :

```properties
ldap.server = localhost
ldap.port = 389
ldap.basedn = dc=example,dc=com
ldap.filter = (objectClass=inetOrgPerson)
ldap.attributes = uid,cn,sn,mail,role,service,seuil,department,code,clearance-level
ldap.connectstring = cn=admin,dc=example,dc=com
ldap.password = admin
person.source = ldap
```

Pour utiliser Kafka :
```properties
kafka.pip.rocksdb.path = /tmp/rocksdb/shared
# KAFKA_ENABLED=true (variable d'environnement, défaut true)
# KAFKA_BOOTSTRAP_SERVERS=localhost:9092 (variable d'environnement)
```

---

## Personnes créées dans LDAP

| uid | Nom          | Rôle            | Service  | Dept  | Seuil  | Clearance |
|-----|--------------|-----------------|----------|-------|--------|-----------|
| 001 | Paul Martin  | chef_de_service | service1 | dept1 | 50000  | 2         |
| 002 | John Dupont  | agent           | service2 | dept2 | 0      | 1         |
| 003 | Alice Bernard| chef_de_service | service2 | dept2 | 100000 | 3         |
| 004 | Sophie Laurent | DPO           | service1 | dept3 | 0      | 4         |
| 005 | Marc Leclerc | legal-counsel   | service3 | dept1 | 0      | 5         |
| 006 | Emma Rousseau | comptable      | service1 | dept1 | 0      | 2         |
| 007 | Pierre Moreau | professeur     | service4 | dept4 | 0      | 1         |
| 008 | Clara Simon  | gestionnaire RH | service5 | dept5 | 0      | 2         |

---

## Arrêt et nettoyage

```bash
docker compose down          # arrêt (données conservées)
docker compose down -v       # arrêt + suppression des volumes
```
