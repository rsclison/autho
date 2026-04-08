# Plan Technique d'Execution

## Autho

Date: 26 mars 2026

Ce document convertit la roadmap d'execution en plan technique directement exploitable sur le code actuel.

---

## 1. Objectif du plan technique

Ce plan technique a un objectif simple:

- transformer la roadmap strategique en lots de travail implementables;
- prioriser ce qui peut etre execute immediatement dans le depot actuel;
- reduire le risque de refonte mal sequencee;
- permettre un demarrage immediat par le code.

Le plan est volontairement centre d'abord sur la Phase 0, puis sur les prealables de la Phase 1, car ce sont les chantiers les plus rentables et les plus urgents pour la credibilite du produit.

---

## 2. Constat technique de depart

Le premier probleme structurant a corriger est deja visible dans le code:

- `evalRequest` contient la logique la plus proche d'une vraie decision canonique;
- `isAuthorized` reutilise une logique partielle et retourne actuellement les regles matchantes;
- `explain` et `simulate` reevaluent de leur cote une grande partie du pipeline.

References:

- [pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L144)
- [pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L201)
- [pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L357)
- [pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L400)
- [handler.clj](/home/rsclison/autho/src/autho/handler.clj#L418)
- [v1.clj](/home/rsclison/autho/src/autho/api/v1.clj#L13)

En pratique, cela signifie qu'avant de construire des fonctions avancees comme le replay ou l'impact analysis, il faut d'abord unifier le coeur de decision.

---

## 3. Strategie d'execution

Le plan technique est organise en 4 lots:

1. Canonical Decision Core
2. Contract Stabilization
3. Policy Safety Foundations
4. Replay and Impact Foundations

Chaque lot est decoupe en sous-taches concretes avec:

- fichiers cibles;
- objectif de modification;
- criteres d'acceptation;
- dependances.

---

## 4. Lot 1 - Canonical Decision Core

## 4.1 Objectif

Creer un pipeline unique de decision que tous les endpoints utilisent:

- `isAuthorized`
- `explain`
- `simulate`
- `batch`
- futur `replay`

## 4.2 Probleme actuel

Aujourd'hui, la logique de decision est dispersee entre plusieurs fonctions. Cela cree:

- duplication;
- risque d'incoherence;
- complexite de test;
- difficulte a ajouter des modes d'execution comme dry-run ou shadow.

## 4.3 Refactoring cible

Introduire une fonction interne centrale, par exemple:

- `evaluate-decision*`

Cette fonction devra produire une structure riche et canonique, de type:

```clojure
{:decision           :allow|:deny
 :allowed?           true|false
 :matched-rules      [...]
 :evaluated-rules    [...]
 :conflict-strategy  :almost_one_allow_no_deny
 :subject            {...}
 :resource           {...}
 :delegation-path    [...]
 :metadata           {...}}
```

## 4.4 Fichiers cibles

- [pdp.clj](/home/rsclison/autho/src/autho/pdp.clj)
- [handler.clj](/home/rsclison/autho/src/autho/handler.clj)
- [src/autho/api/handlers.clj](/home/rsclison/autho/src/autho/api/handlers.clj)
- tests:
  - [pdp_test.clj](/home/rsclison/autho/test/autho/pdp_test.clj)
  - [handler_test.clj](/home/rsclison/autho/test/autho/handler_test.clj)
  - [v1_test.clj](/home/rsclison/autho/test/autho/api/v1_test.clj)

## 4.5 Sous-taches

### Tache 1.1 - Extraire la normalisation de requete d'autorisation

Creer une fonction unique qui construit la requete interne:

- resolution du sujet via `get-subject`;
- assemblage `:subject`, `:resource`, `:operation`, `:context`;
- validations communes de presence.

### Tache 1.2 - Extraire l'evaluation brute des regles

Creer une fonction qui:

- charge la policy pertinente;
- enrichit la requete;
- filtre les regles applicables par operation;
- evalue toutes les regles;
- renvoie la trace complete.

### Tache 1.3 - Extraire la resolution canonique de decision

Creer une fonction qui prend les regles evaluees et renvoie:

- la decision finale;
- les matched rules;
- les deny applicables;
- les metadonnees de strategie;
- la raison de la decision.

### Tache 1.4 - Integrer les delegations dans le pipeline canonique

Le pipeline canonique doit savoir:

- detecter la delegation;
- tracer le chemin de delegation;
- differencier decision directe et decision par delegation;
- eviter les boucles.

### Tache 1.5 - Faire de `isAuthorized` un simple adaptateur API

`isAuthorized` ne doit plus contenir sa propre logique d'evaluation. Elle doit:

- appeler le coeur canonique;
- appliquer le cache si pertinent;
- retourner une vue contractuelle du resultat.

### Tache 1.6 - Faire converger `explain`

`explain` doit reutiliser exactement la meme evaluation canonique, avec une projection plus verbeuse du resultat.

### Tache 1.7 - Faire converger `simulate`

`simulate` doit etre le meme pipeline avec options:

- `:audit? false`
- `:cache? false`
- `:policy-source :active|:version|:inline`

## 4.6 Criteres d'acceptation

- un seul pipeline interne de decision;
- `isAuthorized`, `explain` et `simulate` reposent dessus;
- plus de logique de conflit dupliquee;
- tests de non-regression couvrant allow, deny, equal priority, delegation et no-policy.

## 4.7 Dependances

Aucune. C'est le point de depart.

---

## 5. Lot 2 - Contract Stabilization

## 5.1 Objectif

Stabiliser les contrats de reponse API pour que le moteur interne puisse evoluer sans casser les usages externes.

## 5.2 Probleme actuel

Le format de reponse n'est pas encore suffisamment explicite sur:

- `allowed?`
- `decision`
- `matched-rules`
- `reason`
- `trace-id`
- `source-policy`

## 5.3 Refactoring cible

Definir deux niveaux de contrat:

### Contrat court pour decision API

```clojure
{:allowed true
 :decision "allow"
 :matchedRules ["R1" "R2"]
 :reason "matched_allow_without_higher_deny"}
```

### Contrat detaille pour explain et simulate

```clojure
{:decision "deny"
 :allowed false
 :strategy "almost_one_allow_no_deny"
 :matchedRules ["R4"]
 :evaluatedRules [...]
 :delegationPath [...]
 :reason "higher_priority_deny"
 :policySource {:type "active" :resourceClass "Facture"}}
```

## 5.4 Fichiers cibles

- [pdp.clj](/home/rsclison/autho/src/autho/pdp.clj)
- [src/autho/api/response.clj](/home/rsclison/autho/src/autho/api/response.clj)
- [src/autho/api/handlers.clj](/home/rsclison/autho/src/autho/api/handlers.clj)
- [resources/openapi.yaml](/home/rsclison/autho/resources/openapi.yaml)
- tests API correspondants

## 5.5 Sous-taches

### Tache 2.1 - Centraliser la projection de reponse

Introduire des fonctions de projection:

- `decision->api-response`
- `decision->explain-response`
- `decision->simulate-response`

### Tache 2.2 - Mettre a jour OpenAPI

Documenter explicitement:

- les champs stables;
- les codes d'erreur;
- les differences entre `/isAuthorized`, `/v1/authz/decisions`, `/explain`, `/simulate`.

### Tache 2.3 - Ajouter des tests de contrat

Ajouter des tests qui verrouillent:

- la presence des champs obligatoires;
- la compatibilite backward minimale;
- la semantique de `allowed` et `decision`.

## 5.6 Criteres d'acceptation

- les formats de sortie sont documentes et testes;
- les endpoints v1 ont des schemas clairs;
- le changement de coeur interne n'impose plus de flottement API.

## 5.7 Dependances

Depend du Lot 1.

---

## 6. Lot 3 - Policy Safety Foundations

## 6.1 Objectif

Construire les fondations minimales pour empecher les policies dangereuses ou incoherentes d'etre activees.

## 6.2 Portee initiale

Ce lot ne cherche pas encore la verification formelle complete. Il construit le socle:

- typage de domaine;
- lint semantique;
- tests declaratifs de policies.

## 6.3 Fichiers cibles

- nouveau module propose: [src/autho/policy_safety.clj](/home/rsclison/autho/src/autho/policy_safety.clj)
- [src/autho/prp.clj](/home/rsclison/autho/src/autho/prp.clj)
- [src/autho/policy_language.clj](/home/rsclison/autho/src/autho/policy_language.clj)
- nouveau fichier de schema propose:
  - [resources/policy-types.edn](/home/rsclison/autho/resources/policy-types.edn)
- nouveaux tests proposes:
  - [test/autho/policy_safety_test.clj](/home/rsclison/autho/test/autho/policy_safety_test.clj)

## 6.4 Sous-taches

### Tache 3.1 - Definir un schema de types minimal

Definir pour chaque classe:

- attributs autorises;
- type de chaque attribut;
- operations supportees.

### Tache 3.2 - Implementer un linter semantique v1

Detecter au minimum:

- attribut inconnu;
- operation inconnue;
- classe incoherente;
- priorite invalide;
- policy vide;
- effet invalide.

### Tache 3.3 - Brancher le linter sur `submit-policy`

Avant activation d'une policy:

- validation schema JSON existante;
- validation semantique nouvelle;
- reponse d'erreur explicite.

### Tache 3.4 - Introduire un format de tests declaratifs

Format cible minimal:

```clojure
{:policy "Facture"
 :cases [{:name "chef de service peut lire"
          :request {...}
          :expect {:allowed true}}
         {:name "stagiaire refuse"
          :request {...}
          :expect {:allowed false}}]}
```

### Tache 3.5 - Ajouter un runner interne de tests de policy

Pas encore besoin d'une CLI parfaite. Il faut d'abord un noyau appelable en code et testable.

## 6.5 Criteres d'acceptation

- une policy invalide semantiquement ne peut plus etre activee silencieusement;
- le schema de types couvre au moins les ressources critiques d'exemple;
- un premier format de tests de policy existe et tourne en test.

## 6.6 Dependances

Lot 1 et Lot 2 recommandes avant branchement fort dans l'API.

---

## 7. Lot 4 - Replay and Impact Foundations

## 7.1 Objectif

Preparer le futur moteur d'impact analysis sans attendre la totalite de la refonte produit.

## 7.2 Idee

Une fois le coeur canonique etabli, il devient possible de rejouer une decision historique avec:

- une policy active;
- une policy inline;
- une policy versionnee.

## 7.3 Fichiers cibles

- nouveau module propose: [src/autho/replay.clj](/home/rsclison/autho/src/autho/replay.clj)
- [src/autho/audit.clj](/home/rsclison/autho/src/autho/audit.clj)
- [src/autho/policy_versions.clj](/home/rsclison/autho/src/autho/policy_versions.clj)
- tests proposes:
  - [test/autho/replay_test.clj](/home/rsclison/autho/test/autho/replay_test.clj)

## 7.4 Sous-taches

### Tache 4.1 - Definir le format de record rejouable

Le journal doit fournir ou pouvoir reconstruire:

- sujet;
- ressource;
- operation;
- contexte;
- version de policy si disponible.

### Tache 4.2 - Construire un service de replay minimal

Capable de:

- prendre une decision historique;
- la rejouer contre policy active;
- renvoyer old decision vs new decision.

### Tache 4.3 - Construire un diff d'impact minimal

Agreger:

- nombre de decisions changees;
- decisions deny -> allow;
- decisions allow -> deny;
- regles impliquees.

## 7.5 Criteres d'acceptation

- une campagne de replay minimale fonctionne sur des donnees de test;
- un diff de decision est obtenu de facon deterministe;
- le code reutilise le coeur canonique du Lot 1.

## 7.6 Dependances

Depend fortement du Lot 1.

---

## 8. Ordre reel d'implementation

Ordre recommande dans le depot:

1. extraire le coeur canonique de decision dans `pdp.clj`;
2. faire converger `isAuthorized`;
3. faire converger `whoAuthorized`;
4. faire converger `whatAuthorized`;
5. faire converger `explain`;
6. faire converger `simulate`;
7. ajouter les tests de coherence;
8. centraliser les projections de reponse API;
9. mettre a jour OpenAPI;
10. introduire `policy_safety.clj`;
11. brancher la validation semantique dans `prp.clj`;
12. creer les fondations de replay.

---

## 9. Definition du prochain batch executable

Le prochain batch que je peux commencer immediatement dans ce depot est le suivant:

### Batch A - Canonical Decision Core

#### Modifications prevues

- refactor de [pdp.clj](/home/rsclison/autho/src/autho/pdp.clj) pour introduire un coeur de decision unique;
- alignement de `isAuthorized`, `whoAuthorized`, `whatAuthorized`, `explain` et `simulate` sur ce coeur ou sur ses projections partagees;
- ajout de tests dans:
  - [pdp_test.clj](/home/rsclison/autho/test/autho/pdp_test.clj)
  - [handler_test.clj](/home/rsclison/autho/test/autho/handler_test.clj)
  - [v1_test.clj](/home/rsclison/autho/test/autho/api/v1_test.clj)

#### Resultat attendu

- meme semantique de decision sur tous les endpoints critiques;
- base saine pour les phases suivantes.

#### Risque

- ajustement possible du format de reponse pour le rendre plus explicite.

#### Valeur

- c'est le meilleur ratio impact/risque du projet a ce stade.

---

## 10. Recommandation d'execution immediate

La recommandation technique est claire:

- ne pas commencer par ReBAC;
- ne pas commencer par la gouvernance;
- ne pas commencer par les bundles enterprise.

La bonne premiere execution est de fiabiliser le coeur de decision et d'en faire la source unique de verite.

Une fois ce lot termine, il deviendra rationnel d'attaquer:

- policy safety;
- replay;
- impact analysis.

---

## 11. Conclusion

Ce plan technique est volontairement concret et sequence. Il permet d'attaquer le projet sans ambiguite.

Si l'objectif est d'entrer en execution immediatement, le bon point de depart est le Batch A: Canonical Decision Core.

C'est le chantier que je peux commencer a implementer ensuite directement dans le code.
