# Environnement de demonstration Autho

La demonstration se lance depuis la racine du depot avec un seul script :

```bash
./demo_start.sh
```

Elle s'arrete avec :

```bash
./demo_stop.sh
```

Pour supprimer aussi les volumes persistants :

```bash
./demo_stop.sh --volumes
```

## Services exposes

| Service | URL / Port | Credentials |
| --- | --- | --- |
| Autho API | `http://localhost:8080` | API key ci-dessous |
| Admin UI | `http://localhost:8080/admin/ui` | API key ci-dessous |
| Kafka | `localhost:9092` | - |
| Kafka UI | `http://localhost:8090` | - |
| OpenLDAP | `localhost:389` | `admin` / `admin` |
| phpLDAPadmin | `http://localhost:8091` | voir ci-dessous |

API key de demonstration :

```text
abcdefghijklmnopqrstuvwxyz123456
```

phpLDAPadmin :

- Login DN : `cn=admin,dc=example,dc=com`
- Mot de passe : `admin`

## Ce que prepare `demo_start.sh`

- Kafka et les topics compactes ;
- OpenLDAP avec les personnes de demonstration ;
- Autho en container avec `AUTHO_DEMO_LICENSE_TIER=enterprise` ;
- RocksDB embarque dans le container Autho, persiste dans le volume Docker `autho_data` ;
- les objets `FAC-TEST-01` et `FAC-TEST-02` dans Kafka ;
- les politiques `DossierDemo` et `FacturePurposeDemo` ;
- des decisions initiales pour alimenter le Dashboard et l'Audit.

## Donnees LDAP

| uid | Nom | Role | Service | Dept | Seuil | Clearance |
| --- | --- | --- | --- | --- | --- | --- |
| 001 | Paul Martin | chef_de_service | service1 | dept1 | 50000 | 2 |
| 002 | John Dupont | agent | service2 | dept2 | 0 | 1 |
| 003 | Alice Bernard | chef_de_service | service2 | dept2 | 100000 | 3 |
| 004 | Sophie Laurent | DPO | service1 | dept3 | 0 | 4 |
| 005 | Marc Leclerc | legal-counsel | service3 | dept1 | 0 | 5 |
| 006 | Emma Rousseau | comptable | service1 | dept1 | 0 | 2 |
| 007 | Pierre Moreau | professeur | service4 | dept4 | 0 | 1 |
| 008 | Clara Simon | gestionnaire RH | service5 | dept5 | 0 | 2 |

## Scenario Kafka -> RocksDB -> autorisation

`demo_start.sh` produit deux factures deterministes :

- `FAC-TEST-01` : service `service1`, montant `30000` ;
- `FAC-TEST-02` : service `service1`, montant `80000`.

L'API key Autho est liee au sujet `Person` `001`, charge depuis LDAP :

- role `chef_de_service` ;
- service `service1` ;
- seuil `50000`.

La regle `R1` de `resources/jrules.edn` autorise la lecture d'une `Facture` si le sujet est chef du meme service et si le montant est inferieur au seuil.

Resultat attendu :

- `FAC-TEST-01` est autorisee, car `30000 < 50000` ;
- `FAC-TEST-02` est refusee, car `80000 > 50000`.

## Configuration utilisee

La stack Docker utilise :

- `resources/pdp-prop.docker.properties` via `PDP_CONFIG_PATH` ;
- `resources/pips.docker.edn` via `PIPS_CONFIG_PATH`.

Ces fichiers pointent vers les noms de services Docker (`openldap`, `kafka:29092`) au lieu de `localhost`.
