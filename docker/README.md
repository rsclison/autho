# Environnement de demonstration Autho

La demonstration se lance depuis la racine du depot avec un seul script :

```bash
./demo_start.sh
```

Les données Kafka ne sont pas injectées au démarrage. Cela permet de montrer
d'abord le parcours principal sans objets métier ni projection relationnelle,
puis de basculer vers le mode Kafka dans un second chapitre.

Injecter ensuite les donnees Kafka avec :

```bash
./demo_inject_kafka.sh
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
| PostgreSQL (optionnel) | `localhost:5432/autho` | `autho` / `autho-dev-password` |
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
- RocksDB embarque dans le container Autho, vide au lancement de la demo ;
- les politiques `DossierDemo`, `FacturePurposeDemo` et `DocumentPartageDemo` ;
- le rewrite relationnel tenantisé `can-read -> viewer` ;
- un premier chapitre d'auditabilite avec decisions, bundle signe et verification machine ;
- une analyse d'impact avant changement de politique ;
- un chapitre de decisions pour montrer le comportement de base et l'enrichissement PIP ;
- un chapitre Kafka pour l'alimentation d'objets métier et de projections ReBAC.

Ordre de lecture recommande :

1. auditabilite ;
2. impact/simulation ;
3. PIP et enrichissement ;
4. Kafka comme second mode d'alimentation ;
5. ReBAC hybride : projection, santé, journal et réconciliation.

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

`demo_inject_kafka.sh` produit deux factures deterministes :

- `FAC-TEST-01` : service `service1`, montant `30000` ;
- `FAC-TEST-02` : service `service1`, montant `80000`.

L'API key Autho est liee au sujet `Person` `001`, charge depuis LDAP :

- role `chef_de_service` ;
- service `service1` ;
- seuil `50000`.

La regle `R1` de `resources/jrules.edn` autorise la lecture d'une `Facture` si le sujet est chef du meme service et si le montant est inferieur au seuil.

Resultat attendu :

- avant `demo_inject_kafka.sh`, `FAC-TEST-01` est refusee car les attributs ne sont pas encore disponibles ;
- `FAC-TEST-01` est autorisee, car `30000 < 50000` ;
- `FAC-TEST-02` est refusee, car `80000 > 50000`.

Dans l'Admin UI, l'ecran `Données PIP` permet ensuite de selectionner la classe `Facture` et de visualiser les objets presents dans RocksDB.

## Scénario ReBAC hybride -> projection -> autorisation

Le même `demo_inject_kafka.sh` publie trois événements d’outbox idempotents
dans le topic compacté `authorization-relationships` : un membre `001` du
groupe `finance-demo`, ce groupe lecteur de `workspace-demo`, et le document
`DOC-PARTAGE-001` rattaché à cet espace. Chaque événement est tenantisé,
attribué à sa source et versionné.

La politique `DocumentPartageDemo` vérifie `can-read`. Son rewrite vers
`viewer`, les groupes et l’héritage `parent` conduisent à un accès autorisé pour
`Person:001`; un document sans relation reste refusé. La source métier reste
propriétaire des faits : Autho ne maintient qu’une projection locale. L’écran
**Relations** expose son état Kafka, le journal, la quarantaine et une
réconciliation en lecture seule de `demo-iam`.

## Scenario evidence -> preuve verifiable

`demo_start.sh` exporte un paquet d'evidence signe pour `DossierDemo`, puis le revalide via `GET /v1/evidence` et `POST /v1/evidence/verify`.

Le paquet contient :

- la verification de la chaine d'audit ;
- le replay d'audit ;
- la recherche d'audit ;
- la timeline de politique pour la classe choisie ;
- une section `integrity` avec digest canonique et signature HMAC.

## Configuration utilisee

## PostgreSQL optionnel

H2 reste le stockage par défaut, y compris lorsque PostgreSQL est lancé par
Docker Compose. Pour utiliser PostgreSQL, définissez explicitement :

```bash
export AUTHO_DB_KIND=postgres
export AUTHO_DATABASE_URL=jdbc:postgresql://localhost:5432/autho
export AUTHO_DATABASE_USER=autho
export AUTHO_DATABASE_PASSWORD=autho-dev-password
```

L’audit utilise la même base par défaut ; `AUTHO_AUDIT_DATABASE_URL`,
`AUTHO_AUDIT_DATABASE_USER` et `AUTHO_AUDIT_DATABASE_PASSWORD` permettent de
le séparer. En production, utilisez des secrets et un compte SQL à privilèges
limités : le mot de passe ci-dessus ne convient qu’au poste de développement.

La stack Docker utilise :

- `resources/pdp-prop.docker.properties` via `PDP_CONFIG_PATH` ;
- `resources/pips.docker.edn` via `PIPS_CONFIG_PATH`.

Ces fichiers pointent vers les noms de services Docker (`openldap`, `kafka:29092`) au lieu de `localhost`.
