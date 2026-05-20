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

## Erreurs produites

| Code | Sens |
|---|---|
| `UNKNOWN_OPERATION` | La regle utilise une operation absente du schema |
| `UNKNOWN_SUBJECT_CLASS` | Une condition reference une classe sujet absente du schema |
| `UNKNOWN_RESOURCE_CLASS` | Une condition ou la policy reference une classe ressource absente du schema |
| `UNKNOWN_SUBJECT_ATTRIBUTE` | Une condition reference un attribut sujet absent du schema |
| `UNKNOWN_RESOURCE_ATTRIBUTE` | Une condition reference un attribut ressource absent du schema |

## Limite actuelle

Les references de chemin legacy comme `$.role`, `$s.role` ou `$r.owner` ne portent pas toujours la classe. Autho les valide seulement lorsque le schema ne declare qu'une seule classe pour le scope concerne. Les nouvelles policies doivent preferer le format explicite :

```clojure
["=" ["Person" "$s" "role"] "admin"]
```
