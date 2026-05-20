# Policy Safety

Autho valide les politiques avant persistence afin d'eviter de deployer une policy incoherente ou non executable par le PDP.

## Validations toujours actives

- noms de regles dupliques;
- `resourceClass` de regle incompatible avec la classe soumise;
- strategie de conflit absente ou non supportee;
- operateur inconnu;
- operande invalide;
- conditions contradictoires;
- warnings sur regles sans operation, regles inconditionnelles et regles shadowed.

## Schema optionnel

Une politique peut declarer un schema local. Si le schema est absent, Autho conserve le comportement historique. Si le schema est present, la validation statique rejette les references inconnues.

Exemple :

```json
{
  "strategy": "almost_one_allow_no_deny",
  "schema": {
    "subjects": {
      "Person": ["role", "department", "clearance"]
    },
    "resources": {
      "Document": ["owner", "classification"]
    },
    "operations": ["read", "write"]
  },
  "rules": [
    {
      "name": "allow-admin-read",
      "priority": 10,
      "effect": "allow",
      "resourceClass": "Document",
      "operation": "read",
      "conditions": [
        ["=", ["Person", "$s", "role"], "admin"],
        [">=", ["Person", "$s", "clearance"], ["Document", "$r", "classification"]]
      ]
    }
  ]
}
```

## Tests declaratifs

Une politique peut embarquer une liste `tests`. Ces scenarios sont executes par `submit-policy` apres la validation statique et avant la persistence. Une politique dont au moins un scenario echoue est rejetee avec le code `POLICY_TESTS_FAILED`.

Exemple :

```json
{
  "strategy": "almost_one_allow_no_deny",
  "rules": [
    {
      "name": "allow-admin-read",
      "priority": 10,
      "effect": "allow",
      "resourceClass": "Document",
      "operation": "read",
      "conditions": [
        ["=", ["Person", "$s", "role"], "admin"]
      ]
    },
    {
      "name": "deny-archived",
      "priority": 20,
      "effect": "deny",
      "resourceClass": "Document",
      "operation": "read",
      "conditions": [
        ["=", ["Document", "$r", "status"], "archived"]
      ]
    }
  ],
  "tests": [
    {
      "name": "admin can read active document",
      "subject": {
        "id": "alice",
        "class": "Person",
        "role": "admin"
      },
      "resource": {
        "id": "doc-1",
        "class": "Document",
        "status": "active"
      },
      "operation": "read",
      "expect": "allow"
    },
    {
      "name": "archived document is denied",
      "subject": {
        "id": "alice",
        "class": "Person",
        "role": "admin"
      },
      "resource": {
        "id": "doc-2",
        "class": "Document",
        "status": "archived"
      },
      "operation": "read",
      "expect": "deny"
    }
  ]
}
```

Chaque scenario doit fournir :

- `subject` : sujet utilise pour evaluer les regles;
- `resource` : ressource utilisee pour evaluer les regles;
- `operation` : operation demandee;
- `expect` : decision attendue.

`expect` accepte `"allow"`, `"deny"`, un booleen, ou un objet contenant `decisionType`, `decision`, `allowed?` ou `allowed`.

En cas d'echec, chaque issue contient le nom du test, la decision attendue, la decision obtenue et les regles matchees :

```json
{
  "code": "POLICY_TEST_FAILED",
  "test-name": "admin can read active document",
  "expected": "deny",
  "actual": "allow",
  "matchedRuleNames": ["allow-admin-read"]
}
```

Ces tests sont des tests unitaires de politique candidate. Ils evaluent les regles avec les attributs fournis dans le scenario. Ils ne remplacent pas les futurs tests d'integration avec enrichissement PIP, delegation, replay d'audit ou shadow evaluation.

## Erreurs produites

| Code | Sens |
|---|---|
| `UNKNOWN_OPERATION` | La regle utilise une operation absente du schema |
| `UNKNOWN_SUBJECT_CLASS` | Une condition reference une classe sujet absente du schema |
| `UNKNOWN_RESOURCE_CLASS` | Une condition ou la policy reference une classe ressource absente du schema |
| `UNKNOWN_SUBJECT_ATTRIBUTE` | Une condition reference un attribut sujet absent du schema |
| `UNKNOWN_RESOURCE_ATTRIBUTE` | Une condition reference un attribut ressource absent du schema |
| `POLICY_TESTS_FAILED` | Au moins un test declaratif de politique a echoue |
| `INVALID_POLICY_TEST` | Un scenario de test declaratif est incomplet ou invalide |
| `POLICY_TEST_EVALUATION_ERROR` | Un scenario declaratif a declenche une erreur pendant l'evaluation |

## Limite actuelle

Les references de chemin legacy comme `$.role`, `$s.role` ou `$r.owner` ne portent pas toujours la classe. Autho les valide seulement lorsque le schema ne declare qu'une seule classe pour le scope concerne. Les nouvelles policies doivent preferer le format explicite :

```clojure
["=" ["Person" "$s" "role"] "admin"]
```
