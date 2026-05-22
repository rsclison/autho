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

## 4 bis. Variante recommandee : demonstration complete en containers

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
3. Presenter le Dashboard.
4. Ouvrir `Policies` et montrer `DossierDemo`.
5. Executer une decision `allow`, puis une decision `deny`.
6. Ouvrir `Simulator` et expliquer la decision refusee.
7. Simuler une politique candidate qui changerait le refus en autorisation.
8. Montrer le shadow testing via API.
9. Ouvrir `Audit`, filtrer les decisions, exporter en CSV et verifier la chaine.
10. Montrer l'analyse d'impact et conclure sur le cycle professionnel : tester, simuler, auditer, approuver, deployer.
