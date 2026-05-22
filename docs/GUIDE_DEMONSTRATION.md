# Guide de demonstration Autho

Ce document decrit une demonstration pas a pas d'Autho pour un public technique ou decisionnaire. Le scenario montre le lancement du serveur, l'interface d'administration, la decision d'autorisation, l'explication, la simulation, le shadow testing, l'audit et quelques capacites de gouvernance.

## 1. Objectif de la demonstration

Le fil conducteur est le suivant :

- une application de confiance `app-demo` appelle Autho avec une API key ;
- Autho identifie l'application cote serveur, sans faire confiance au champ `subject` fourni dans le corps HTTP ;
- une politique autorise `app-demo` a lire des dossiers non secrets ;
- l'IHM permet de modifier la politique, tester une decision, simuler une politique candidate et consulter l'audit ;
- les APIs montrent les memes fonctions de facon reproductible en ligne de commande.

Les fonctionnalites `audit`, `explain`, `simulate`, `shadow` et `metrics` necessitent une licence Pro ou Enterprise. Sans `AUTHO_LICENSE_KEY`, le serveur demarre en mode Free et les endpoints proteges repondent `402 LICENSE_REQUIRED`.

## 2. Prerequis

- Java 11 ou plus.
- Node.js et npm pour lancer l'IHM React en mode developpement.
- Le wrapper Leiningen du depot : `./lein`.
- Une licence Pro ou Enterprise valide pour montrer l'audit, la simulation et le shadow testing.

Depuis la racine du depot :

```bash
cd /home/rsclison/autho
```

## 3. Lancer le serveur Autho

Dans un premier terminal, exporter les variables de demonstration :

```bash
export JWT_SECRET="01234567890123456789012345678901"
export API_KEY="abcdefghijklmnopqrstuvwxyz123456"
export API_CLIENT_ID="app-demo"
export API_CLIENT_CLASS="Application"
export API_CLIENT_ROLES="governance-admin,policy-admin,policy-reviewer,policy-deployer,relation-admin"
export API_CLIENT_TENANTS="demo"
export KAFKA_ENABLED="false"
export AUDIT_HMAC_SECRET="audit-test-hmac-secret-32-chars-min-ok!!"
export POLICY_BUNDLE_HMAC_SECRET="policy-bundle-hmac-secret-32-chars-min"
unset AUTHO_LICENSE_KEY
export AUTHO_DEMO_LICENSE_TIER="enterprise"
```

Lancer le serveur :

```bash
./lein run
```

La commande `./lein ring server-headless` ne s'applique pas a ce projet : `project.clj` ne declare pas le plugin `lein-ring`. Le point d'entree supporte est `autho.core`, lance par `./lein run`.

Si le serveur echoue ensuite avec une erreur `Connection refused` vers `localhost:389`, ce n'est plus un probleme Leiningen : la configuration `resources/pdp-prop.properties` pointe vers un LDAP local. Pour une demonstration sans LDAP, demarrer le LDAP de demonstration avant Autho ou adapter temporairement cette configuration pour ne pas utiliser `person.source = "ldap"`.

Pour une demonstration sans Kafka ni RocksDB, garder `KAFKA_ENABLED=false`. Sinon, Autho tente d'ouvrir les PIPs Kafka/RocksDB declares dans `resources/pips.edn`.

Verifier que le serveur repond :

```bash
curl http://localhost:8080/health
curl -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" http://localhost:8080/status
```

Point a expliquer pendant la demonstration : `API_KEY` authentifie l'application appelante. Les appels HTTP doivent transmettre cette cle avec l'en-tete `Authorization: X-API-Key <cle>`. L'identite effective injectee dans le PDP est construite depuis `API_CLIENT_ID`, `API_CLIENT_CLASS`, `API_CLIENT_ROLES` et `API_CLIENT_TENANTS`. Un utilisateur ne peut donc pas se faire passer pour `app-demo` en postant manuellement un champ `subject`.

## 4. Lancer l'interface homme-machine

Deux modes sont possibles.

Mode developpement, recommande pour la demonstration :

```bash
cd /home/rsclison/autho/admin-ui
npm ci
npm run dev
```

Ouvrir ensuite :

```text
http://localhost:3000/admin/ui
```

Mode embarque dans le serveur Clojure :

```bash
cd /home/rsclison/autho/admin-ui
npm ci
npm run build
```

Puis ouvrir :

```text
http://localhost:8080/admin/ui
```

Sur l'ecran de connexion :

1. choisir le mode `API Key` ;
2. saisir `abcdefghijklmnopqrstuvwxyz123456` ;
3. valider.

L'IHM contient les sections suivantes :

| Section | Demonstration |
| --- | --- |
| Dashboard | Etat du serveur, politiques actives, cache, dernieres decisions et disponibilite de l'audit. |
| Policies | Creation, edition, validation, historique, comparaison et gouvernance des politiques. |
| Simulator | Test interactif d'une decision et simulation d'une politique candidate. |
| Audit | Recherche des decisions, export CSV et verification d'integrite de la chaine d'audit. |
| Infrastructure | Statut des caches, circuit breakers et actions d'administration. |
| Settings | Theme et session courante. |

## 4 bis. Parcours IHM a derouler pendant la demonstration

Ce parcours peut etre execute apres la creation de `DossierDemo` et apres quelques appels `/v1/authz/decisions`, afin que le Dashboard et l'Audit contiennent deja des donnees a montrer.

### 4 bis.1 Connexion et session

1. ouvrir `http://localhost:3000/admin/ui` en mode developpement ou `http://localhost:8080/admin/ui` en mode embarque ;
2. selectionner `API Key` ;
3. saisir `abcdefghijklmnopqrstuvwxyz123456` ;
4. valider.

Points a commenter :

- l'IHM n'utilise pas une identite declaree dans un formulaire metier ;
- l'API key authentifie l'application d'administration ;
- les roles de gouvernance viennent de `API_CLIENT_ROLES`.

### 4 bis.2 Dashboard

1. ouvrir `Dashboard` ;
2. verifier l'indicateur de sante du serveur dans la barre laterale ;
3. montrer les cartes d'etat : serveur, politiques, audit et cache ;
4. montrer les dernieres decisions apres avoir execute une decision `allow` et une decision `deny` ;
5. expliquer qu'un message de licence sur l'audit indique que la demo n'est pas lancee en Pro ou Enterprise.

Resultat attendu avec la configuration de ce guide : les fonctions Enterprise sont visibles, et les dernieres decisions apparaissent apres rafraichissement.

### 4 bis.3 Politiques

1. ouvrir `Politiques` ;
2. verifier que `DossierDemo` apparait dans la liste laterale ;
3. selectionner `DossierDemo` ;
4. montrer le document JSON de politique ;
5. verifier la strategie `almost_one_allow_no_deny` ;
6. montrer les regles `ALLOW-APP-DEMO-READ` et `DENY-SECRET` ;
7. ouvrir l'historique des versions ;
8. selectionner deux versions si elles existent, puis afficher le diff ;
9. faire une modification mineure de commentaire ou de regle en demonstration uniquement si un rollback est prevu.

Points a commenter :

- la strategie proposee par l'IHM est celle que le backend valide ;
- chaque sauvegarde cree une version auditable ;
- le diff permet de relire les changements avant revue ou deploiement.

### 4 bis.4 Creation d'une politique depuis l'IHM

1. dans `Politiques`, cliquer sur `Nouvelle politique` ;
2. saisir `DemoIhmTemp` ;
3. valider ;
4. ouvrir la politique creee ;
5. verifier que la strategie par defaut vaut `almost_one_allow_no_deny` ;
6. supprimer cette politique a la fin de la demonstration si elle ne sert plus.

Point a commenter : la creation depuis l'IHM ne doit plus produire l'erreur `unsupported conflict resolution strategy 'deny-unless-permit'`.

### 4 bis.5 Simulateur

1. ouvrir `Simulateur` ;
2. saisir le sujet `alice` ;
3. selectionner ou saisir la classe `DossierDemo` ;
4. saisir la ressource `DOS-002` ;
5. saisir l'operation `lire` ;
6. cliquer sur `Expliquer` ;
7. montrer le badge `Acces refuse` ;
8. ouvrir le bloc `Explication de la decision` ;
9. montrer la strategie, la ressource, l'operation, les regles evaluees et la regle matchante `DENY-SECRET` ;
10. cliquer ensuite sur `Simuler` pour montrer le mode dry-run.

Points a commenter :

- `Expliquer` analyse la policy active ;
- `Simuler` execute une evaluation sans modifier la policy active ;
- le JSON brut reste disponible pour un profil technique.

### 4 bis.6 Audit

1. ouvrir `Audit` ;
2. filtrer `Classe ressource` avec `DossierDemo` ;
3. cliquer sur `Rechercher` ;
4. filtrer successivement sur `Autorise` puis `Refuse` ;
5. montrer les colonnes horodatage, sujet, ressource, operation, decision et regles ;
6. cliquer sur `Verifier l'integrite` ;
7. exporter en CSV.

Points a commenter :

- l'audit permet de retrouver pourquoi une decision a ete prise ;
- la verification d'integrite detecte une rupture de chaine ;
- l'export CSV sert aux revues de securite ou de conformite.

### 4 bis.7 Gouvernance et analyse d'impact

1. ouvrir `Politiques` puis `DossierDemo` ;
2. ouvrir l'onglet ou l'action de gouvernance ;
3. dans le panneau de preparation, garder la baseline `Courante` ;
4. coller un jeu de requetes representatif si le champ n'est pas deja rempli ;
5. cliquer sur `Generer une preview d impact` ;
6. montrer les compteurs : changements, revocations, sujets touches, ressources touchees ;
7. dans l'historique, montrer le statut de review et le statut de rollout ;
8. si le scenario le permet, marquer la preview comme revue ou approuvee, puis expliquer le rollout.

Jeu de requetes simple a coller dans le panneau de gouvernance :

```json
[
  {
    "subject": {"id": "alice", "class": "Person"},
    "resource": {"class": "DossierDemo", "id": "DOS-001", "classification": "internal"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"}
  },
  {
    "subject": {"id": "alice", "class": "Person"},
    "resource": {"class": "DossierDemo", "id": "DOS-002", "classification": "secret"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"}
  }
]
```

Point a commenter : l'IHM ne se limite pas a editer une policy ; elle aide a mesurer l'impact avant de deployer.

### 4 bis.8 Infrastructure et parametres

1. ouvrir `Infrastructure` ;
2. montrer l'etat des composants, caches et actions d'administration disponibles ;
3. ouvrir `Parametres` ;
4. changer le theme si utile ;
5. montrer les informations de session.

Point a commenter : ces ecrans servent a rendre l'exploitation visible pendant une demo technique, sans devoir lire les logs serveur.

## 4 ter. Variante recommandee : demonstration complete en containers

Pour montrer Kafka, LDAP et RocksDB dans le meme scenario, utiliser la stack Docker :

```bash
./examples/container_kafka_rocksdb_demo.sh
```

Le script lance :

- Kafka et Kafka UI ;
- OpenLDAP et phpLDAPadmin ;
- Autho en container ;
- le producteur Kafka de demonstration.

Il injecte ensuite les objets de `docker/kafka-producer/test-factures.json` dans le topic `business-objects-compacted`. Le consumer Kafka d'Autho lit ces messages et met a jour RocksDB dans le container Autho. Les appels d'autorisation ne transmettent ensuite que `{"class": "Facture", "id": "FAC-TEST-01"}` : les attributs `service` et `montant` sont lus depuis RocksDB pendant l'evaluation de la regle.

Cette stack active `AUTHO_DEMO_LICENSE_TIER=enterprise`. Elle donne acces aux fonctionnalites maximales de demonstration : audit, versioning, explain, simulate, shadow, metrics, Kafka PIP et multi-instance. Ne pas utiliser cette variable pour un environnement de production.

Services exposes :

| Service | URL |
| --- | --- |
| Autho | `http://localhost:8080` |
| Admin UI | `http://localhost:8080/admin/ui` |
| Kafka UI | `http://localhost:8090` |
| phpLDAPadmin | `http://localhost:8091` |

Scenario montre par le script :

1. l'API key identifie le sujet `Person` `001`, enrichi depuis LDAP ;
2. `FAC-TEST-01` est produite dans Kafka avec `service = service1` et `montant = 30000` ;
3. Autho consomme le message et le stocke dans RocksDB ;
4. la regle `R1` autorise la lecture, car Paul est `chef_de_service`, appartient a `service1` et son seuil `50000` couvre le montant ;
5. `FAC-TEST-02` est refusee, car son montant `80000` depasse le seuil.

Arret :

```bash
cd docker
docker compose down
```

Reinitialisation complete :

```bash
cd docker
docker compose down -v
```

## 5. Preparer une politique de demonstration

Creer la politique `DossierDemo` via l'API. Cette politique autorise `app-demo` a lire les dossiers non secrets et refuse explicitement les dossiers secrets.

```bash
curl -X PUT http://localhost:8080/v1/policies/DossierDemo \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "resourceClass": "DossierDemo",
    "strategy": "almost_one_allow_no_deny",
    "rules": [
      {
        "name": "ALLOW-APP-DEMO-READ",
        "operation": "lire",
        "priority": 10,
        "effect": "allow",
        "conditions": [
          ["=", "$s.client-id", "app-demo"],
          ["diff", "$r.classification", "secret"]
        ]
      },
      {
        "name": "DENY-SECRET",
        "operation": "lire",
        "priority": 100,
        "effect": "deny",
        "conditions": [
          ["=", "$r.classification", "secret"]
        ]
      }
    ],
    "tests": [
      {
        "name": "app-demo lit un dossier interne",
        "subject": {"id": "app-demo", "class": "Application", "client-id": "app-demo"},
        "resource": {"id": "DOS-001", "class": "DossierDemo", "classification": "internal"},
        "operation": "lire",
        "expect": "allow"
      },
      {
        "name": "app-demo ne lit pas un dossier secret",
        "subject": {"id": "app-demo", "class": "Application", "client-id": "app-demo"},
        "resource": {"id": "DOS-002", "class": "DossierDemo", "classification": "secret"},
        "operation": "lire",
        "expect": "deny"
      }
    ]
  }'
```

Dans l'IHM :

1. ouvrir `Policies` ;
2. selectionner `DossierDemo` ;
3. montrer les regles, la strategie, les tests embarques et le bouton de sauvegarde ;
4. ouvrir l'historique de versions pour montrer que chaque sauvegarde devient tracable.

## 6. Montrer une decision d'autorisation

Decision autorisee :

```bash
curl -X POST http://localhost:8080/v1/authz/decisions \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "alice", "class": "Person", "role": "admin"},
    "resource": {"class": "DossierDemo", "id": "DOS-001", "classification": "internal"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"}
  }'
```

Point a commenter : meme si le corps contient `alice`, l'appel est authentifie par API key. Le sujet effectivement evalue correspond a l'application `app-demo`. Le champ `context.on-behalf-of` sert a porter l'information metier, pas a prouver l'identite de l'appelant.

Decision refusee :

```bash
curl -X POST http://localhost:8080/v1/authz/decisions \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "alice", "class": "Person"},
    "resource": {"class": "DossierDemo", "id": "DOS-002", "classification": "secret"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"}
  }'
```

Dans le Dashboard, rafraichir la page et montrer les dernieres decisions.

## 6 bis. Tester un `purpose` applicatif controle

Ce scenario montre le cas ou `app-demo` traite des donnees que l'utilisateur courant ne peut pas consulter directement. Le champ `purpose` n'est pas documentaire : il est evalue par la policy et couple a l'identite applicative authentifiee.

Creer la politique `FacturePurposeDemo` :

```bash
curl -X PUT http://localhost:8080/v1/policies/FacturePurposeDemo \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "resourceClass": "FacturePurposeDemo",
    "strategy": "almost_one_allow_no_deny",
    "schema": {
      "subjects": {"Application": ["client-id"]},
      "resources": {"FacturePurposeDemo": ["id"]},
      "contexts": {"Context": ["purpose", "requestingUser"]},
      "operations": ["process", "lire"]
    },
    "rules": [
      {
        "name": "ALLOW-APP-DEMO-AGGREGATE",
        "operation": "process",
        "priority": 10,
        "effect": "allow",
        "conditions": [
          ["=", ["Application", "$s", "client-id"], "app-demo"],
          ["=", ["Context", "$c", "purpose"], "aggregate_invoice_total"]
        ]
      },
      {
        "name": "DENY-APP-DEMO-EXPORT",
        "operation": "process",
        "priority": 100,
        "effect": "deny",
        "conditions": [
          ["=", ["Application", "$s", "client-id"], "app-demo"],
          ["=", ["Context", "$c", "purpose"], "export_invoice_details"]
        ]
      }
    ],
    "tests": [
      {
        "name": "app-demo peut agreger les factures pour Alice",
        "subject": {"id": "app-demo", "class": "Application", "client-id": "app-demo"},
        "resource": {"id": "FAC-001", "class": "FacturePurposeDemo"},
        "operation": "process",
        "context": {"purpose": "aggregate_invoice_total", "requestingUser": "alice"},
        "expect": "allow"
      },
      {
        "name": "app-demo ne peut pas exporter les lignes de facture",
        "subject": {"id": "app-demo", "class": "Application", "client-id": "app-demo"},
        "resource": {"id": "FAC-001", "class": "FacturePurposeDemo"},
        "operation": "process",
        "context": {"purpose": "export_invoice_details", "requestingUser": "alice"},
        "expect": "deny"
      },
      {
        "name": "Alice ne lit pas la facture brute via cette policy",
        "subject": {"id": "alice", "class": "Person"},
        "resource": {"id": "FAC-001", "class": "FacturePurposeDemo"},
        "operation": "lire",
        "context": {"purpose": "consultation", "requestingUser": "alice"},
        "expect": "deny"
      }
    ]
  }'
```

Test 1, `purpose` autorise : `app-demo` peut traiter les factures pour calculer un agregat.

```bash
curl -X POST http://localhost:8080/v1/authz/decisions \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "alice", "class": "Person"},
    "resource": {"class": "FacturePurposeDemo", "id": "FAC-001"},
    "operation": "process",
    "context": {
      "purpose": "aggregate_invoice_total",
      "requestingUser": "alice"
    }
  }'
```

Resultat attendu : `decision` vaut `allow`. Le corps declare `alice`, mais l'API key fait que le sujet effectif est `app-demo`.

Test 2, `purpose` non autorise : la meme application ne peut pas utiliser un autre objectif pour exporter les lignes de facture.

```bash
curl -X POST http://localhost:8080/v1/authz/decisions \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "alice", "class": "Person"},
    "resource": {"class": "FacturePurposeDemo", "id": "FAC-001"},
    "operation": "process",
    "context": {
      "purpose": "export_invoice_details",
      "requestingUser": "alice"
    }
  }'
```

Resultat attendu : `decision` vaut `deny`.

Test 3, consultation utilisateur : le fait que `app-demo` puisse traiter les factures pour un agregat ne donne pas a Alice le droit de lire la facture brute.

```bash
curl -X POST http://localhost:8080/v1/authz/decisions \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "alice", "class": "Person"},
    "resource": {"class": "FacturePurposeDemo", "id": "FAC-001"},
    "operation": "lire",
    "context": {
      "purpose": "consultation",
      "requestingUser": "alice"
    }
  }'
```

Resultat attendu : `decision` vaut `deny`.

Point a commenter : une application ne peut pas obtenir un acces plus large en inventant un `purpose`. La policy verifie le couple `application authentifiee + purpose + operation + ressource`. Les donnees sources peuvent etre traitees par A pour produire une somme, mais seules les donnees de sortie autorisees doivent etre presentees a l'utilisateur.

## 7. Montrer l'explication d'une decision

L'explication sert a comprendre pourquoi Autho autorise ou refuse.

```bash
curl -X POST http://localhost:8080/v1/authz/explain \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "alice", "class": "Person"},
    "resource": {"class": "DossierDemo", "id": "DOS-002", "classification": "secret"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"}
  }'
```

Elements a montrer dans la reponse ou dans l'IHM `Simulator` :

- decision finale ;
- regles evaluees ;
- regles matchees ;
- priorite du `deny` ;
- conditions vraies ou fausses.

## 8. Montrer la simulation

La simulation evalue une politique candidate en dry-run : elle ne remplace pas la politique active, n'alimente pas le cache de decision et ne doit pas produire d'evenement d'audit de production.

Dans l'IHM :

1. ouvrir `Simulator` ;
2. choisir la ressource `DossierDemo` ;
3. saisir `DOS-002`, operation `lire`, classification `secret` ;
4. lancer une evaluation pour montrer le refus actuel ;
5. modifier la politique candidate pour tester une exception temporaire ;
6. relancer la simulation et montrer le resultat sans deploiement.

Equivalent API :

```bash
curl -X POST http://localhost:8080/v1/authz/simulate \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "alice", "class": "Person"},
    "resource": {"class": "DossierDemo", "id": "DOS-002", "classification": "secret"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"},
    "simulatedPolicy": {
      "resourceClass": "DossierDemo",
      "strategy": "almost_one_allow_no_deny",
      "rules": [
        {
          "name": "ALLOW-APP-DEMO-READ-ALL",
          "operation": "lire",
          "priority": 10,
          "effect": "allow",
          "conditions": [
            ["=", "$s.client-id", "app-demo"]
          ]
        }
      ]
    }
  }'
```

Message cle : la simulation permet de repondre a la question "que se passerait-il si cette politique etait active ?" sans prendre le risque de modifier la production.

## 9. Montrer le shadow testing

Le shadow testing compare la decision de production avec une politique candidate. La decision retournee au client reste celle de production.

```bash
curl -X POST http://localhost:8080/v1/authz/shadow \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "subject": {"id": "alice", "class": "Person"},
    "resource": {"class": "DossierDemo", "id": "DOS-002", "classification": "secret"},
    "operation": "lire",
    "context": {"on-behalf-of": "alice"},
    "shadowPolicy": {
      "resourceClass": "DossierDemo",
      "strategy": "almost_one_allow_no_deny",
      "rules": [
        {
          "name": "ALLOW-APP-DEMO-READ-ALL",
          "operation": "lire",
          "priority": 10,
          "effect": "allow",
          "conditions": [
            ["=", "$s.client-id", "app-demo"]
          ]
        }
      ]
    }
  }'
```

Montrer le bloc `shadowEvaluation` : il indique si la politique candidate changerait la decision, sans impacter le client.

## 10. Montrer l'audit

Generer quelques decisions de production avec `/v1/authz/decisions`, puis ouvrir `Audit` dans l'IHM.

Manipulations IHM :

1. filtrer sur `DossierDemo` ;
2. filtrer sur une decision `allow`, puis `deny` ;
3. montrer le detail des regles matchees ;
4. exporter le resultat en CSV ;
5. cliquer sur `Verify chain` pour verifier l'integrite cryptographique de la chaine d'audit.

Equivalent API :

```bash
curl -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  "http://localhost:8080/admin/audit/search?resourceClass=DossierDemo&page=1&pageSize=20"
```

Verification d'integrite :

```bash
curl -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  http://localhost:8080/admin/audit/verify
```

Points a commenter :

- l'audit est append-only ;
- chaque entree est chainee par HMAC pour detecter la suppression ou la modification ;
- la recherche d'audit est utile pour investiguer une decision apres coup ;
- la simulation ne remplace pas les decisions de production dans le journal d'audit.

## 11. Montrer l'analyse d'impact avant deploiement

L'analyse d'impact compare une politique candidate avec la politique active sur un jeu de requetes.

```bash
curl -X POST http://localhost:8080/v1/policies/DossierDemo/impact \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "candidatePolicy": {
      "resourceClass": "DossierDemo",
      "strategy": "almost_one_allow_no_deny",
      "rules": [
        {
          "name": "ALLOW-APP-DEMO-READ-ALL",
          "operation": "lire",
          "priority": 10,
          "effect": "allow",
          "conditions": [
            ["=", "$s.client-id", "app-demo"]
          ]
        }
      ]
    },
    "thresholds": {
      "maxRevokes": 0,
      "maxChangedDecisions": 10,
      "allowSensitiveResourceChanges": false
    },
    "requests": [
      {
        "subject": {"id": "alice", "class": "Person"},
        "resource": {"class": "DossierDemo", "id": "DOS-001", "classification": "internal"},
        "operation": "lire"
      },
      {
        "subject": {"id": "alice", "class": "Person"},
        "resource": {"class": "DossierDemo", "id": "DOS-002", "classification": "secret"},
        "operation": "lire"
      }
    ]
  }'
```

Dans `Policies`, ouvrir le panneau de gouvernance de `DossierDemo` pour montrer l'historique des analyses, le statut de risque et les actions de revue.

## 12. Montrer la securite applicative

Faire volontairement un appel sans API key :

```bash
curl -X POST http://localhost:8080/v1/authz/decisions \
  -H "Content-Type: application/json" \
  -d '{
    "subject": {"id": "app-demo", "class": "Application", "client-id": "app-demo"},
    "resource": {"class": "DossierDemo", "id": "DOS-001", "classification": "internal"},
    "operation": "lire"
  }'
```

Puis refaire l'appel avec une API key incorrecte :

```bash
curl -X POST http://localhost:8080/v1/authz/decisions \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key mauvaise-cle-api" \
  -d '{
    "subject": {"id": "app-demo", "class": "Application", "client-id": "app-demo"},
    "resource": {"class": "DossierDemo", "id": "DOS-001", "classification": "internal"},
    "operation": "lire"
  }'
```

Conclusion a donner : l'identite applicative ne vient pas du JSON envoye par le client. Elle vient d'un mecanisme d'authentification controle par le serveur.

## 13. Script de fin de demonstration

Revenir a une politique stricte si la demonstration a modifie la politique :

```bash
curl -X PUT http://localhost:8080/v1/policies/DossierDemo \
  -H "Content-Type: application/json" \
  -H "Authorization: X-API-Key abcdefghijklmnopqrstuvwxyz123456" \
  -H "X-Tenant-ID: demo" \
  -d '{
    "resourceClass": "DossierDemo",
    "strategy": "almost_one_allow_no_deny",
    "rules": [
      {
        "name": "ALLOW-APP-DEMO-READ",
        "operation": "lire",
        "priority": 10,
        "effect": "allow",
        "conditions": [
          ["=", "$s.client-id", "app-demo"],
          ["diff", "$r.classification", "secret"]
        ]
      },
      {
        "name": "DENY-SECRET",
        "operation": "lire",
        "priority": 100,
        "effect": "deny",
        "conditions": [
          ["=", "$r.classification", "secret"]
        ]
      }
    ]
  }'
```

Arreter les processus avec `Ctrl+C` dans les terminaux du serveur et de l'IHM.

## 14. Ordre conseille pour une demo de 20 minutes

1. Lancer le serveur et l'IHM.
2. Se connecter avec l'API key.
3. Presenter le `Dashboard` et l'etat du serveur.
4. Ouvrir `Politiques`, montrer `DossierDemo`, la strategie et l'historique.
5. Executer une decision `allow`, puis une decision `deny`.
6. Rafraichir le `Dashboard` pour montrer les dernieres decisions.
7. Executer les trois tests `purpose` : agregation autorisee, export refuse, lecture brute refusee.
8. Ouvrir `Simulateur`, expliquer la decision refusee et montrer le JSON brut.
9. Simuler une politique candidate qui changerait le refus en autorisation.
10. Montrer le shadow testing via API.
11. Ouvrir `Audit`, filtrer les decisions, exporter en CSV et verifier la chaine.
12. Ouvrir la gouvernance de `DossierDemo`, generer une preview d'impact et commenter les compteurs.
13. Ouvrir `Infrastructure` puis `Parametres` pour conclure sur l'exploitation.
