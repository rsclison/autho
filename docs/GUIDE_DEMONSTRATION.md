# Guide de démonstration Autho

Ce guide décrit le parcours de démonstration complet d’Autho : politiques ABAC,
audit et preuve, ReBAC hybride, ingestion Kafka, gouvernance et interface
d’administration. Il est conçu pour un échange de 15 à 20 minutes avec une
équipe sécurité, IAM ou produit.

Le scénario garde une frontière importante : les applications métier restent
propriétaires de leurs objets et relations. Autho évalue les règles et maintient
une projection d’autorisation locale, alimentée ici par des événements Kafka
qui représenteraient en production une outbox métier.

## Démarrage

Prérequis : Docker avec le plugin `docker compose`, `curl`, et les ports
locaux `8080`, `8090`, `8091`, `9092` et `389` disponibles.

Depuis la racine du dépôt :

```bash
./demo_start.sh
```

Le script repart volontairement de volumes neufs, démarre Autho, Kafka,
OpenLDAP, Kafka UI et phpLDAPadmin, puis crée les politiques et décisions de
base. Les objets métier et les relations ne sont pas injectés à ce stade : cela
permet de rendre visible l’arrivée contrôlée des projections.

Pour injecter les données Kafka du second chapitre :

```bash
./demo_inject_kafka.sh
```

Pour arrêter la démo sans perdre les volumes :

```bash
./demo_stop.sh
```

Ajouter `--volumes` uniquement pour supprimer les données persistantes de la
démonstration.

| Service | Adresse |
| --- | --- |
| API et Admin UI | `http://localhost:8080` et `/admin/ui` |
| Kafka UI | `http://localhost:8090` |
| phpLDAPadmin | `http://localhost:8091` |

Dans l’Admin UI, choisir **API Key**, saisir
`abcdefghijklmnopqrstuvwxyz123456` et sélectionner le tenant `demo`. Cette
clé représente le sujet LDAP `Person` `001` et porte les rôles de gouvernance
nécessaires à la démonstration. Le sujet fourni dans le JSON des appels API key
ne permet donc pas d’usurper une autre identité.

## Données préparées

`demo_start.sh` installe les politiques suivantes :

| Politique | Cas illustré |
| --- | --- |
| `DossierDemo` | lecture d’un dossier interne et refus explicite d’un dossier secret |
| `FacturePurposeDemo` | finalité (`purpose`) contrôlée par la politique |
| `DocumentPartageDemo` | accès relationnel `can-read`, dérivé de `viewer` |

Le second script publie ensuite des données déterministes :

- `FAC-TEST-01`, montant `30000` : autorisée après enrichissement RocksDB ;
- `FAC-TEST-02`, montant `80000` : refusée, au-dessus du seuil LDAP `50000` ;
- `Person:001 --member--> Group:finance-demo` ;
- `Group:finance-demo --viewer--> Folder:workspace-demo` ;
- `DocumentPartageDemo:DOC-PARTAGE-001 --parent--> Folder:workspace-demo`.

Le rewrite `can-read -> viewer` permet à la règle de rester orientée métier.
La décision finale démontre groupe, héritage parent et rewrite, sans que la
politique n’embarque ces faits métier.

## Parcours dans la GUI

L’interface a été conçue pour rester lisible en démonstration : navigation
latérale repliable sur petit écran, tailles de texte relevées, états de focus
visibles et surfaces homogènes.

### 1. Dashboard et audit

1. Ouvrir **Dashboard** après `demo_start.sh`.
2. Montrer les cartes de santé et les dernières décisions déjà produites.
3. Ouvrir **Audit**, filtrer la classe `DossierDemo`, puis successivement les
   décisions autorisées et refusées.
4. Utiliser **Vérifier l’intégrité** et l’export CSV.

À commenter : l’audit est append-only et chaîné ; le script exporte aussi un
bundle d’evidence signé, puis le vérifie via l’API. La preuve est donc à la
fois lisible dans l’IHM et vérifiable par une machine.

### 2. Politique, simulateur et impact

1. Dans **Politiques**, ouvrir `DossierDemo`.
2. Montrer la stratégie `almost_one_allow_no_deny`, les règles d’autorisation
   et de refus, puis l’historique de versions.
3. Dans **Simulateur**, évaluer `DossierDemo` / `DOS-002` / `lire` : le refus
   est expliqué par `DENY-SECRET`.
4. Revenir sur `DossierDemo`, ouvrir la vue de gouvernance et lancer la
   prévisualisation d’impact ; le script a déjà généré une analyse candidate.

Le simulateur est un dry-run, tandis que la gouvernance quantifie les décisions
changées, les révocations et les sujets ou ressources concernés avant rollout.

### 3. Finalité contrôlée

Dans **Audit**, filtrer `FacturePurposeDemo`. Le jeu initial contient :

- `aggregate_invoice_total` : `allow` ;
- `export_invoice_details` : `deny`.

La finalité est un attribut de contexte évalué par la policy, non une simple
étiquette déclarative du client.

### 4. Relations ReBAC hybrides

Avant l’injection Kafka, ouvrir **Relations** : le consommateur Kafka est actif
mais la projection est vide. Ouvrir ensuite **Politiques** >
`DocumentPartageDemo` et montrer la clause :

```json
["relation", "$s", "can-read", "$r"]
```

Exécuter alors `./demo_inject_kafka.sh`, puis revenir dans **Relations** :

1. consulter les tuples projetés et leurs métadonnées de source/version ;
2. montrer l’état du consommateur, l’âge de projection et le lag ;
3. consulter le journal de projection ;
4. ouvrir la réconciliation et le rapport de la source `demo-iam` : aucun
   écart est attendu ;
5. vérifier que la quarantaine est vide.

Dans **Audit**, filtrer `DocumentPartageDemo` :

- `DOC-PARTAGE-001` est autorisé ;
- `DOC-PARTAGE-REFUSE` est refusé.

La page Relations est une vue d’exploitation de projection, non l’outil qui
devrait administrer les droits métier au quotidien. En production, une
correction se fait à la source, qui publie un nouvel événement idempotent ; la
réconciliation compare seulement les états et ne modifie jamais la projection.

### 5. Kafka, RocksDB et LDAP

Dans Kafka UI, ouvrir les topics `business-objects-compacted` et
`authorization-relationships`. Le premier contient les factures ; le second
contient les événements d’outbox relationnels.

Dans l’Admin UI, ouvrir **Données PIP**, sélectionner `Facture` et montrer les
objets reçus dans RocksDB. Dans **Audit**, filtrer `Facture` :

- avant injection, `FAC-TEST-01` est refusée car ses attributs sont absents ;
- après injection, elle est autorisée (`30000 < 50000`) ;
- `FAC-TEST-02` demeure refusée (`80000 > 50000`).

Kafka sert donc ici aux données à forte volumétrie et aux projections
asynchrones ; LDAP reste le PIP des attributs du sujet. Le script vide le cache
avant les décisions finales pour rendre ce changement visible immédiatement.

### 6. Infrastructure et paramètres

Terminer par **Infrastructure** pour les composants et caches, puis
**Paramètres** pour la session et le thème. Cela positionne Autho comme un
produit opéré, pas seulement comme une bibliothèque d’évaluation de règles.

## Vérifications API utiles

Après injection, le contrôle ReBAC suivant doit retourner une relation
satisfaite, avec une preuve de parcours :

```bash
curl -X POST http://localhost:8080/v1/relations/check \
  -H 'Content-Type: application/json' \
  -H 'Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456' \
  -H 'X-Tenant-ID: demo' \
  -d '{
    "subject": {"class": "Person", "id": "001"},
    "relation": "can-read",
    "resource": {"class": "DocumentPartageDemo", "id": "DOC-PARTAGE-001"}
  }'
```

L’état opérationnel est disponible via :

```bash
curl -H 'Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456' \
  -H 'X-Tenant-ID: demo' \
  http://localhost:8080/v1/relations/status
```

## PostgreSQL en option

La démo utilise H2 par défaut : cela reste le comportement normal si aucune
variable n’est positionnée. PostgreSQL est compatible et se prépare à part :

```bash
docker compose up -d postgres
export AUTHO_DB_KIND=postgres
export AUTHO_DATABASE_URL=jdbc:postgresql://localhost:5432/autho
export AUTHO_DATABASE_USER=autho
export AUTHO_DATABASE_PASSWORD=autho-dev-password
```

Ces variables sont destinées à un démarrage Autho hors de la stack de démo
Docker actuelle. La procédure complète de validation et de retour à H2 se
trouve dans [POSTGRESQL_COMPATIBILITY.md](POSTGRESQL_COMPATIBILITY.md).

## Fil narratif court

1. Démarrer avec `./demo_start.sh` et se connecter à l’Admin UI.
2. Dashboard, Audit et evidence signée.
3. Politique `DossierDemo`, Simulateur et impact.
4. Purpose contrôlé.
5. Montrer `DocumentPartageDemo` puis lancer `./demo_inject_kafka.sh`.
6. Kafka UI, Données PIP, Relations et la décision relationnelle auditée.
7. Infrastructure, puis `./demo_stop.sh`.
