# Contrat de decision canonique

Ce document definit les invariants communs aux endpoints qui produisent une decision d'autorisation.

## Endpoints concernes

- `POST /isAuthorized`
- `POST /v1/authz/decisions`
- `POST /v1/authz/explain`
- `POST /v1/authz/simulate`
- `POST /v1/authz/batch`, pour chaque element de `data.results`

Les endpoints time-travel ne sont pas encore dans ce contrat tant que l'evaluation PDP historique n'est pas branchee. Ils doivent etre traites comme des requetes historiques en construction, pas comme des decisions canoniques finales.

## Champs canoniques

Tout endpoint de decision doit exposer les champs suivants :

| Champ | Type | Sens |
|---|---|---|
| `allowed?` | boolean | Decision booleenne canonique |
| `decisionType` | string | `allow` ou `deny` |
| `tenantId` | string | Tenant effectif utilise pour la decision |
| `subjectId` | string | Identifiant du sujet effectif |
| `effectiveSubject` | object | Sujet reel utilise par le PDP apres authentification/enrichissement |
| `resourceClass` | string | Classe de ressource |
| `resourceId` | string/null | Identifiant de ressource quand disponible |
| `operation` | string | Operation evaluee |
| `strategy` | string/keyword | Strategie de conflit appliquee |
| `matchedRuleNames` | array | Noms des regles matchees |
| `policySource` | string/keyword | `current`, `provided` ou `version` |
| `policyVersion` | integer/null | Version de politique quand disponible |

## Compatibilite

Les champs historiques restent supportes :

- `allowed`
- `decision`
- `results`
- `matchedRules`

Ils ne doivent pas etre utilises comme base de nouveaux developpements. Les nouveaux clients doivent lire `allowed?`, `decisionType` et `matchedRuleNames`.

## Invariants

1. `decisionType` vaut `allow` si et seulement si `allowed?` vaut `true`.
2. `matchedRuleNames` contient les memes regles logiques sur `isAuthorized`, `explain` et `simulate` pour une meme politique.
3. `effectiveSubject` est le sujet authentifie ou delegue par le serveur, pas une valeur client non fiable.
4. `policySource` vaut `current` pour une decision normale, `provided` pour une simulation inline, `version` pour une simulation sur version archivee.
5. Le batch v1 renvoie une liste de decisions canoniques dans `data.results`.
6. `tenantId` est resolu cote serveur depuis l'identite, `X-Tenant-ID`, les parametres ou le contexte de requete; si l'identite declare une liste de tenants, le tenant demande doit en faire partie.

## Strategie de conflit supportee

Le PDP supporte actuellement une strategie de conflit en production :

| Strategie | Semantique |
|---|---|
| `almost_one_allow_no_deny` | Autorise si au moins une regle `allow` matche et si aucune regle `deny` de priorite strictement superieure ne gagne. |

Les autres noms de strategie ne doivent pas etre persistes via l'API tant que leur semantique n'est pas implementee et testee dans le PDP. La validation statique des politiques rejette donc les strategies absentes ou non supportees.
