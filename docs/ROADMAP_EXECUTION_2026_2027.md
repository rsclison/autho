# Roadmap d'Execution 2026-2027

## Autho

Date: 26 mars 2026

Document derive de l'audit strategique 2026 et de la roadmap historique du projet.

---

## 1. Objectif de la roadmap

Cette roadmap a pour but de transformer Autho:

- d'un serveur d'autorisation ABAC avance;
- en une plateforme d'Authorization Operations;
- capable de concurrencer les references du marche sur la decision, la preuve, la simulation, la gouvernance et l'analyse d'impact.

Elle ne remplace pas la roadmap technique historique. Elle la reorganise dans une logique d'execution produit, avec priorites, dependances, livrables et criteres de succes.

---

## 2. Vision cible

A horizon 12 a 18 mois, Autho doit etre capable de proposer nativement:

- un moteur de decision canonique, coherent et testable;
- un lifecycle complet de politiques: edition, validation, simulation, replay, promotion, rollback;
- un moteur hybride ABAC + ReBAC + temporal;
- une architecture enterprise multi-tenant et distribuable;
- une couche de gouvernance d'acces a forte valeur metier et conformite.

Positionnement cible:

"Autho est une plateforme d'Authorization Operations qui combine decision temps reel, explication, audit verifiable, time-travel, simulation d'impact et gouvernance de l'acces."

---

## 3. Principes directeurs

### Principe 1: un seul coeur de decision

Tous les endpoints critiques doivent reposer sur le meme pipeline semantique.

### Principe 2: ne jamais deployer une policy aveuglement

Toute politique doit pouvoir etre validee, testee, comparee et simulee avant activation.

### Principe 3: favoriser les capacites defendables

Les travaux priorises doivent renforcer ce que le marche propose peu ou mal:

- impact analysis;
- replay des decisions;
- preuve d'integrite;
- time-travel;
- explain complet.

### Principe 4: separer le control plane du data plane

Les evolutions enterprise devront progressivement decoupler:

- l'administration;
- la distribution des politiques;
- l'execution de decision;
- l'historique;
- l'observabilite.

---

## 4. Axes strategiques

## Axe A - Fiabilite du moteur

Objectif: rendre la decision canonique, stable, prouvable et uniforme sur toute la surface API.

## Axe B - Policy lifecycle et impact analysis

Objectif: faire d'Autho un produit ou l'on teste et compare les politiques avant de les deployer.

## Axe C - Moteur hybride ABAC/ReBAC

Objectif: etendre la couverture fonctionnelle du produit a des modeles relationnels de type Zanzibar sans perdre la richesse ABAC.

## Axe D - Architecture enterprise

Objectif: preparer Autho a des deployments de plus grande taille, multi-tenant, self-hosted premium ou managed.

## Axe E - Gouvernance et compliance

Objectif: augmenter la valeur business en ajoutant des workflows de gouvernance autour de la decision d'autorisation.

---

## 5. Roadmap par phases

## Phase 0 - Stabilisation immediate

Horizon: 0 a 6 semaines

### Objectifs

- corriger les ambiguities semantiques du coeur de decision;
- documenter la semantique canonique;
- verrouiller les invariants de base.

### Chantiers

1. Unifier `isAuthorized`, `evalRequest`, `explain` et `simulate`.
2. Definir un contrat de reponse stable pour la decision.
3. Ajouter des tests de coherence entre endpoints.
4. Formaliser les strategies de conflit supportees.
5. Ajouter des cas de test deny/allow/priorite/delegation sur la voie publique principale.

### Livrables

- specification de semantique de decision;
- suite de tests de regression semantique;
- changelog de compatibilite API;
- note d'architecture du coeur PDP.

### KPI de succes

- 0 divergence connue entre endpoints de decision;
- 100% des strategies supportees testees sur le chemin public principal;
- documentation de semantique publiee et relue.

### Risques

- decouverte de comportements historiques implicites;
- possible besoin de versionner une partie de la reponse API.

---

## Phase 1 - Policy Safety et GitOps

Horizon: trimestre suivant, environ 2 a 3 mois

### Objectifs

- empecher l'introduction de politiques incoherentes;
- rendre le changement de policy testable en CI/CD.

### Chantiers

1. Introduire un schema de types pour sujets, ressources, attributs et operations.
2. Creer un compilateur ou linter de politiques.
3. Detecter:
   - attributs inconnus;
   - regles mortes;
   - conflits deny/allow suspects;
   - policies inatteignables;
   - references invalides.
4. Ajouter un format declaratif de tests de politiques.
5. Fournir une commande ou endpoint de validation pre-deploiement.
6. Definir des environnements policy `dev`, `staging`, `prod`.

### Livrables

- `policy lint`;
- `policy test`;
- schema de types versionne;
- rapport de validation avant activation;
- guide GitOps pour policies.

### KPI de succes

- toute policy active passe par validation et tests;
- reduction forte des regressions de policies detectees en production;
- temps de revue de policy reduit grace aux rapports automatises.

### Risques

- modelisation initiale des types peut etre plus longue que prevu;
- choix de syntaxe a stabiliser pour eviter dette ergonomique.

---

## Phase 2 - Replay, Shadow et Impact Analysis

Horizon: 1 trimestre supplementaire

### Objectifs

- faire d'Autho un produit qui sait predire l'effet d'une policy avant activation;
- transformer audit, versioning et time-travel en avantage concurrentiel visible.

### Chantiers

1. Rejouer des decisions historiques a partir des logs d'audit.
2. Comparer policy actuelle vs policy candidate.
3. Produire un rapport d'impact:
   - acces gagnes;
   - acces perdus;
   - populations affectees;
   - ressources sensibles impactees.
4. Ajouter le mode shadow evaluation.
5. Exposer ces capacites en API et dans l'UI admin.
6. Ajouter des seuils de blocage avant merge ou deployment.

### Livrables

- `policy replay`;
- `policy diff-impact`;
- rapport de blast radius;
- vue UI de comparaison de versions;
- garde-fous CI/CD.

### KPI de succes

- tout changement majeur de policy peut etre compare avant activation;
- incidents lies a des regressions d'autorisation reduits significativement;
- time-to-troubleshoot diminue grace a replay + explain.

### Risques

- qualite et structure de l'audit existant a renforcer pour certains cas d'usage;
- cout de calcul potentiellement eleve sur gros volumes, a cadrer par echantillonnage et batch.

---

## Phase 3 - Moteur hybride ABAC + ReBAC

Horizon: 2 trimestres

### Objectifs

- etendre Autho aux cas d'usage relationnels modernes;
- conserver le meilleur de l'ABAC tout en ajoutant un vrai modele de graphe d'acces.

### Chantiers

1. Definir le modele relationnel cible:
   - tuples;
   - relations;
   - permissions derivees;
   - hierarchies;
   - ownership;
   - delegation relationnelle.
2. Introduire des APIs de type:
   - write relationship;
   - check permission;
   - list objects;
   - list users.
3. Combiner evaluation relationnelle, attributs et temps.
4. Ajouter un explain relationnel comprehensible.
5. Ajouter des tests de modele relationnel.

### Livrables

- schema relationnel v1;
- moteur relationnel minimum viable;
- APIs ReBAC v1;
- documentation de modelisation hybride;
- benchmarks comparatifs sur cas d'usage cibles.

### KPI de succes

- couverture d'au moins 3 cas d'usage relationnels de reference;
- explain hybride lisible;
- performances acceptables sur checks et list operations.

### Risques

- complexite conceptuelle elevee;
- risque de produire un ReBAC trop limite ou trop complexe si le perimetre n'est pas cadre;
- besoin probable d'une abstraction interne plus forte du moteur.

---

## Phase 4 - Architecture enterprise et multi-tenant

Horizon: en parallele de la Phase 3 puis industrialisation continue

### Objectifs

- rendre le produit deployable a plus grande echelle;
- preparer une offre enterprise ou managed serieuse.

### Chantiers

1. Introduire une isolation multi-tenant explicite.
2. Rendre ports, runtime et stockage plus configurables.
3. Separer control plane et data plane.
4. Packager la distribution des policies sous forme de bundles signes.
5. Clarifier la strategie de coherence inter-noeuds.
6. Externaliser ou distribuer les etats qui ne doivent plus rester purement locaux.
7. Formaliser les SLA, SLO et modes de degradation.

### Livrables

- architecture cible enterprise;
- mode multi-tenant v1;
- bundles signes de policies;
- documentation de deployment HA;
- matrice de resilience et modes de panne.

### KPI de succes

- deploiement multi-instance documente et reproductible;
- isolation tenant testee;
- distribution de policies sans ambiguite de version.

### Risques

- demande une refonte partielle de certains composants historiques;
- peut ralentir temporairement la velocity produit si mal sequencee.

---

## Phase 5 - Gouvernance, workflows et compliance

Horizon: 1 a 2 trimestres apres stabilisation du socle enterprise

### Objectifs

- augmenter la valeur economique d'Autho;
- sortir du seul terrain de la decision technique.

### Chantiers

1. Access requests et approbations.
2. Just-in-time access.
3. Break-glass access avec audit renforce.
4. Segregation of duties.
5. Recertification periodique d'acces.
6. Evidence packs pour audit et conformite.

### Livrables

- workflow d'approbation v1;
- journal d'evidence exportable;
- policies de gouvernance predefinies;
- tableaux de bord de conformite.

### KPI de succes

- adoption par des parties prenantes non purement techniques;
- reduction du travail manuel de gouvernance;
- capacite a produire des preuves d'audit rapidement.

### Risques

- risque de dispersion si le socle produit n'est pas encore assez solide;
- besoin d'une UX soignee et d'un cadrage metier clair.

---

## 6. Ordre de priorite recommande

### Priorite absolue

- Phase 0: coeur canonique de decision
- Phase 1: policy safety et GitOps

### Priorite tres haute

- Phase 2: replay, shadow et impact analysis

### Priorite haute

- Phase 3: ReBAC hybride
- Phase 4: architecture enterprise

### Priorite moyenne mais strategique

- Phase 5: gouvernance et compliance

---

## 7. Dependances majeures

## Dependances techniques

- la Phase 2 depend fortement de la qualite du coeur canonique de decision;
- la Phase 3 depend d'une abstraction moteur plus nette;
- la Phase 4 depend de decisions d'architecture prises des la Phase 1;
- la Phase 5 depend d'un produit stable et observable.

## Dependances produit

- il faut figer le positionnement cible avant d'investir massivement dans l'UX et le go-to-market;
- il faut choisir les cas d'usage cibles pour le ReBAC avant de definir le modele final.

---

## 8. Equipes et capacites recommandees

## Noyau minimum recommande

- 1 responsable architecture / moteur;
- 1 ingenieur backend PDP / policy lifecycle;
- 1 ingenieur plateforme / distribution / observabilite;
- 1 ingenieur produit full-stack pour admin UI et parcours operateurs;
- 1 pilotage produit ou fondateur fortement implique.

## Renforts souhaitables

- 1 profil securite / GRC pour les workflows de gouvernance;
- 1 profil DX / documentation / SDK lorsque la chaine GitOps sera prete.

---

## 9. Mesures de succes globales

A 12 mois, la roadmap peut etre consideree comme en bonne voie si Autho atteint les resultats suivants:

- semantique de decision unique et prouvee;
- tests de politiques integres a la CI;
- replay et impact analysis utilisables en preproduction;
- premier support ReBAC operationnel sur cas cibles;
- architecture enterprise documentee et testee;
- premiers workflows de gouvernance exploitables.

A 18 mois, Autho doit pouvoir revendiquer:

- une differenciation visible sur la simulation et l'analyse d'impact;
- une couverture hybride ABAC/ReBAC/temporal;
- une capacite serieuse a servir des organisations soumises a audit ou conformite.

---

## 10. Travaux a ne pas prioriser trop tot

Pour proteger la trajectoire, les sujets suivants ne doivent pas prendre le dessus sur les fondations:

- sur-optimisations de performance avant semantique canonique;
- embellissements UI non relies aux parcours critiques;
- multiplication prematuree des connecteurs;
- workflows GRC lourds avant la stabilisation du moteur et du lifecycle policy.

---

## 11. Recommandation finale

La meilleure trajectoire pour Autho est la suivante:

1. fiabiliser le coeur de decision;
2. rendre les politiques testables, comparables et deployables en confiance;
3. transformer audit + time-travel + versioning en systeme d'impact analysis;
4. etendre le moteur au relationnel;
5. industrialiser l'architecture enterprise;
6. monetiser ensuite la couche gouvernance et compliance.

Cette sequence maximise a la fois:

- la reduction du risque technique;
- la creation d'un avantage concurrentiel defendable;
- la valeur business a moyen terme.

---

## 12. Synthese courte pour pilotage

### Maintenant

- unifier la decision;
- stabiliser l'API;
- verrouiller les tests semantiques.

### Ensuite

- policy lint;
- policy test;
- replay;
- impact analysis.

### Puis

- ReBAC hybride;
- architecture enterprise;
- bundles et multi-tenant.

### Enfin

- approbations;
- just-in-time access;
- recertification;
- evidence packs.
