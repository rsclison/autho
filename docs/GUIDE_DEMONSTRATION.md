# Guide de demonstration Autho

Ce guide decrit une seule maniere de lancer et d'arreter la demonstration : la stack Docker complete via `demo_start.sh` et `demo_stop.sh`.

La demonstration lance tous les composants utiles :

- Autho avec licence de demonstration `enterprise` ;
- Admin UI embarquee ;
- Kafka et Kafka UI ;
- OpenLDAP et phpLDAPadmin ;
- RocksDB dans le container Autho ;
- producteur Kafka de donnees de test ;
- politiques et decisions initiales pour remplir l'IHM.

## 1. Prerequis

- Docker avec le plugin `docker compose`.
- `curl`.
- Acces aux ports locaux `8080`, `8090`, `8091`, `9092` et `389`.

Depuis la racine du depot :

```bash
cd /home/rsclison/autho
```

## 2. Lancer toute la demonstration

Executer uniquement :

```bash
./demo_start.sh
```

Le script effectue tout le demarrage :

1. construit et lance Kafka, OpenLDAP, Autho, Kafka UI et phpLDAPadmin ;
2. attend que le serveur Autho soit pret ;
3. produit des objets `Facture` dans Kafka ;
4. laisse le consumer Autho mettre a jour RocksDB ;
5. cree les politiques `DossierDemo` et `FacturePurposeDemo` ;
6. execute des decisions `allow` et `deny` pour alimenter l'audit et le Dashboard.

La stack utilise une licence de demonstration `enterprise`, ce qui active audit, explain, simulate, shadow, metrics, Kafka PIP et fonctions de gouvernance.

## 3. Acces utiles

| Service | URL |
| --- | --- |
| Autho API | `http://localhost:8080` |
| Admin UI | `http://localhost:8080/admin/ui` |
| Kafka UI | `http://localhost:8090` |
| phpLDAPadmin | `http://localhost:8091` |

Identifiants :

| Usage | Valeur |
| --- | --- |
| Mode de connexion Admin UI | `API Key` |
| API key | `abcdefghijklmnopqrstuvwxyz123456` |
| Tenant | `demo` |
| LDAP login DN | `cn=admin,dc=example,dc=com` |
| LDAP password | `admin` |

Point a commenter : l'API key de demonstration est liee cote serveur au sujet LDAP `Person` `001`. Les champs `subject` envoyes dans les requetes de test sont volontairement ignores pour les appels API key. L'identite effective vient du serveur.

## 4. Parcours IHM

### 4.1 Connexion

1. ouvrir `http://localhost:8080/admin/ui` ;
2. choisir le mode `API Key` ;
3. saisir `abcdefghijklmnopqrstuvwxyz123456` ;
4. valider.

### 4.2 Dashboard

1. ouvrir `Dashboard` ;
2. verifier l'indicateur de sante du serveur dans la barre laterale ;
3. montrer les cartes d'etat ;
4. montrer les dernieres decisions deja generees par `demo_start.sh`.

Points a commenter :

- la licence de demonstration active les fonctions avancees ;
- l'audit est deja alimente par des decisions `allow` et `deny` ;
- le Dashboard sert de vue d'exploitation rapide.

### 4.3 Politiques

1. ouvrir `Politiques` ;
2. selectionner `DossierDemo` ;
3. montrer la strategie `almost_one_allow_no_deny` ;
4. montrer les regles `ALLOW-DEMO-CLIENT-READ-INTERNAL` et `DENY-SECRET` ;
5. ouvrir l'historique de versions ;
6. selectionner deux versions si elles existent et afficher le diff.

Point a commenter : la creation et la sauvegarde d'une politique passent par validation, versioning et audit. La strategie proposee par l'IHM est une strategie supportee par le backend.

### 4.4 Creation d'une politique depuis l'IHM

1. dans `Politiques`, cliquer sur `Nouvelle politique` ;
2. saisir `DemoIhmTemp` ;
3. valider ;
4. ouvrir la politique creee ;
5. verifier que la strategie par defaut vaut `almost_one_allow_no_deny` ;
6. supprimer cette politique si elle n'est plus utile.

Point a commenter : cette manipulation verifie que l'IHM ne cree plus de policy avec l'ancienne strategie `deny-unless-permit`.

### 4.5 Simulateur et explication

1. ouvrir `Simulateur` ;
2. saisir le sujet `alice` ;
3. saisir la classe `DossierDemo` ;
4. saisir la ressource `DOS-002` ;
5. saisir l'operation `lire` ;
6. cliquer sur `Expliquer` ;
7. montrer le badge `Acces refuse` ;
8. ouvrir le bloc `Explication de la decision` ;
9. montrer la regle matchante `DENY-SECRET` ;
10. cliquer sur `Simuler` pour montrer l'evaluation dry-run.

Points a commenter :

- `Expliquer` analyse la politique active ;
- `Simuler` n'ecrit pas une nouvelle politique et ne remplace pas la production ;
- le JSON brut reste disponible pour une demonstration technique.

### 4.6 Audit

1. ouvrir `Audit` ;
2. filtrer `Classe ressource` avec `DossierDemo` ;
3. cliquer sur `Rechercher` ;
4. filtrer successivement sur `Autorise` puis `Refuse` ;
5. montrer les colonnes horodatage, sujet, ressource, operation, decision et regles ;
6. cliquer sur `Verifier l'integrite` ;
7. exporter en CSV.

Points a commenter :

- l'audit est append-only ;
- chaque entree est chainee pour detecter une modification ;
- l'export CSV sert aux revues de securite ou de conformite.

### 4.7 Kafka, RocksDB et LDAP

1. ouvrir Kafka UI sur `http://localhost:8090` ;
2. ouvrir le topic `business-objects-compacted` ;
3. montrer les objets `FAC-TEST-01` et `FAC-TEST-02` produits par `demo_start.sh` ;
4. revenir dans l'Admin UI ;
5. ouvrir `Audit` et filtrer sur `Facture` ;
6. montrer que les decisions ont ete prises sans transmettre `montant` ni `service` dans la requete HTTP.

Points a commenter :

- Kafka recoit les objets metier ;
- Autho les consomme et les stocke dans RocksDB ;
- pendant l'autorisation, Autho enrichit la ressource `Facture` depuis RocksDB ;
- le sujet `001` est enrichi depuis LDAP.

### 4.8 Purpose controle

La politique `FacturePurposeDemo` montre qu'un `purpose` n'est pas seulement documentaire. Il est evalue par la policy.

Dans `Audit`, filtrer sur `FacturePurposeDemo` et montrer :

- une decision `allow` pour `aggregate_invoice_total` ;
- une decision `deny` pour `export_invoice_details`.

Point a commenter : une application ou un client authentifie ne peut pas obtenir plus de droits en inventant un `purpose`. La policy verifie le couple identite authentifiee, operation, ressource et `context.purpose`.

### 4.9 Gouvernance et impact

1. ouvrir `Politiques` puis `DossierDemo` ;
2. ouvrir l'action ou l'onglet de gouvernance ;
3. garder la baseline `Courante` ;
4. lancer une preview d'impact avec le jeu de requetes propose dans l'ecran ;
5. montrer les compteurs : changements, revocations, sujets touches, ressources touchees ;
6. montrer l'historique, le statut de review et le statut de rollout.

Point a commenter : l'IHM ne sert pas seulement a editer une politique. Elle aide a mesurer l'impact avant de deployer.

### 4.10 Infrastructure et parametres

1. ouvrir `Infrastructure` ;
2. montrer l'etat des composants, caches et actions d'administration ;
3. ouvrir `Parametres` ;
4. montrer la session et le theme.

## 5. Tests API rapides

Ces commandes sont facultatives : `demo_start.sh` les execute deja pour initialiser la demonstration.

Decision autorisee :

```bash
curl -X POST http://localhost:8080/v1/authz/decisions \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "DossierDemo", "id": "DOS-001", "classification": "internal"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"}
  }'
```

Decision refusee :

```bash
curl -X POST http://localhost:8080/v1/authz/decisions \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "DossierDemo", "id": "DOS-002", "classification": "secret"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"}
  }'
```

Decision Kafka/RocksDB autorisee :

```bash
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

Purpose refuse :

```bash
curl -X POST http://localhost:8080/v1/authz/decisions \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "ignored-with-api-key", "class": "Person"},
    "resource": {"class": "FacturePurposeDemo", "id": "FAC-002"},
    "operation": "process",
    "context": {
      "purpose": "export_invoice_details",
      "requestingUser": "alice"
    }
  }'
```

## 6. Arreter toute la demonstration

Executer uniquement :

```bash
./demo_stop.sh
```

Pour arreter et supprimer aussi les volumes persistants de demonstration :

```bash
./demo_stop.sh --volumes
```

`demo_stop.sh` appelle `docker compose down --remove-orphans` puis supprime les containers nommes de la demo s'ils existent encore.

## 7. Deroulement conseille en 15 minutes

1. `./demo_start.sh`.
2. Connexion a l'Admin UI.
3. Dashboard.
4. Politiques `DossierDemo` et historique.
5. Simulateur sur `DOS-002`.
6. Audit filtre sur `DossierDemo`.
7. Kafka UI puis audit `Facture`.
8. Audit `FacturePurposeDemo`.
9. Gouvernance et preview d'impact.
10. `./demo_stop.sh`.
