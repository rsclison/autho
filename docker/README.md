# Environnement de test Autho

## Démarrage

```bash
cd docker
docker compose up -d
```

## Services

| Service        | URL / Port                    | Credentials         |
|----------------|-------------------------------|---------------------|
| Kafka          | `localhost:9092`              | —                   |
| Kafka UI       | http://localhost:8090         | —                   |
| OpenLDAP       | `localhost:389`               | admin / admin       |
| phpLDAPadmin   | http://localhost:8091         | voir ci-dessous      |

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

---

## Configuration Autho (resources/pdp-prop.properties)

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
