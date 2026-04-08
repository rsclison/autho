# Audit Strategique Expert

## Autho

Serveur d'autorisation XACML/ABAC

Date du rapport: 26 mars 2026

Auteur: Audit expert independant

---

## 1. Resume executif

Autho est deja bien plus qu'un moteur d'autorisation experimental. Le produit presente les fondations d'une plateforme d'autorisation moderne avec:

- un PDP central expose en HTTP;
- un debut de PAP avec CRUD, import YAML, versioning et rollback;
- des PIP multiples (LDAP, REST, Kafka, RocksDB, fillers internes);
- un audit immuable;
- des fonctions avancees de simulation, d'explication et de time-travel;
- une couche d'observabilite et d'administration.

Le niveau de maturite technique observe est superieur a celui d'un simple "policy engine maison". En l'etat, Autho peut deja interesser des organisations ayant besoin d'un serveur d'autorisation centralise, auditable et extensible.

En revanche, le projet n'est pas encore positionne comme un leader incontestable du marche. Le principal frein n'est pas le manque de fonctionnalites visibles, mais l'absence de quelques capacites differentiantes qui, aujourd'hui, separeront un bon moteur d'autorisation d'une plateforme de reference:

- unification stricte de la semantique de decision;
- validation statique et verification des politiques;
- support natif d'un modele relationnel de type ReBAC/Zanzibar;
- experience GitOps et tests de politiques de niveau produit;
- capacites d'analyse d'impact avant mise en production;
- mode multi-tenant et control plane de niveau enterprise.

Conclusion executive:

Autho a le potentiel pour devenir un produit majeur du marche si son positionnement evolue de "serveur ABAC puissant" vers "plateforme d'Authorization Operations", c'est-a-dire une solution capable de decider, expliquer, rejouer, comparer, gouverner et prouver l'autorisation a grande echelle.

---

## 2. Mandat et methode d'audit

Le present audit a ete realise comme une revue expert:

- de l'architecture et du code source;
- de la documentation technique et produit;
- de la surface API exposee;
- de la trajectoire roadmap visible dans le depot;
- de la position probable du produit face aux references du marche.

### Sources internes analysees

- [README.md](/home/rsclison/autho/README.md)
- [project.clj](/home/rsclison/autho/project.clj)
- [src/autho/core.clj](/home/rsclison/autho/src/autho/core.clj)
- [src/autho/handler.clj](/home/rsclison/autho/src/autho/handler.clj)
- [src/autho/pdp.clj](/home/rsclison/autho/src/autho/pdp.clj)
- [src/autho/prp.clj](/home/rsclison/autho/src/autho/prp.clj)
- [src/autho/policy_language.clj](/home/rsclison/autho/src/autho/policy_language.clj)
- [docs/ARCHITECTURE.md](/home/rsclison/autho/docs/ARCHITECTURE.md)
- [ROADMAP.md](/home/rsclison/autho/ROADMAP.md)
- [IMPROVEMENTS.md](/home/rsclison/autho/IMPROVEMENTS.md)

### Sources externes de comparaison marche

- [AWS Verified Permissions FAQ](https://aws.amazon.com/verified-permissions/faqs/)
- [OpenFGA Testing Models](https://openfga.dev/docs/modeling/testing)
- [Permit.io Policy Testing](https://docs.permit.io/how-to/permit-cli/permit-cli-test/)
- [AuthZed / SpiceDB overview](https://authzed.com/products/authzed-serverless)

### Limites de l'audit

Le present rapport repose principalement sur l'analyse documentaire et la lecture du code. Il ne constitue pas:

- un test de charge complet;
- un audit de securite offensif;
- un audit juridique ou de conformite;
- une campagne de benchmarks comparee en laboratoire.

Les constats strategiques et architecturaux restent toutefois suffisants pour produire une recommandation de positionnement et de feuille de route credible.

---

## 3. Vue d'ensemble du produit audite

Autho apparait comme un serveur d'autorisation centralise fonde sur des politiques, avec une orientation ABAC forte et une ambition produit deja visible.

### Capacites observees

- Decision d'autorisation centralisee via `POST /isAuthorized`
- Interrogation inverse via `whoAuthorized` et `whatAuthorized`
- Explication detaillee de decision
- Simulation et batch
- Administration des politiques
- Historique de versions et rollback
- Audit et verification d'integrite
- Cache local et invalidation
- PIP enrichissant sujet et ressource
- Fonctions de time-travel sur historique d'autorisation

Le bootstrap principal du serveur est simple et direct dans [src/autho/core.clj](/home/rsclison/autho/src/autho/core.clj#L9). L'assemblage HTTP et middleware montre une vraie logique "produit serveur" dans [src/autho/handler.clj](/home/rsclison/autho/src/autho/handler.clj#L699). L'initialisation du moteur, des PIP, des personnes, de l'audit et du versioning est visible dans [src/autho/pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L479).

---

## 4. Forces majeures

## 4.1 Richesse fonctionnelle deja inhabituelle

Le projet rassemble dans un meme ensemble des fonctions que l'on voit souvent dispersees sur plusieurs composants:

- moteur de decision;
- administration de politiques;
- observabilite;
- audit;
- simulation;
- historique;
- time-travel.

Cette densite de capacites est un avantage strategique fort. Beaucoup de concurrents proposent soit un moteur excellent mais peu outille pour les operations, soit une plateforme agreable mais moins profonde sur le moteur.

## 4.2 Orientation serveur et non simple bibliotheque

Le projet n'est pas uniquement un evaluateur embarque. La presence d'un serveur HTTP, d'endpoints d'administration, d'une UI, de metriques, d'authentification, de rate limiting et de graceful shutdown est caracteristique d'un produit deployable. Cela augmente fortement la valeur percue pour un acheteur enterprise.

References:

- [src/autho/handler.clj](/home/rsclison/autho/src/autho/handler.clj#L321)
- [src/autho/handler.clj](/home/rsclison/autho/src/autho/handler.clj#L397)
- [src/autho/handler.clj](/home/rsclison/autho/src/autho/handler.clj#L699)

## 4.3 Bon potentiel d'observabilite et d'auditabilite

Le projet dispose deja:

- de traces;
- de metriques;
- d'un audit signe;
- d'outils de verification d'integrite;
- d'endpoints de sante et de readiness.

Cet axe est central pour gagner sur des dossiers reglementes, sensibles ou critiques. C'est un atout commercial autant que technique.

## 4.4 Architecture d'enrichissement tres prometteuse

L'approche par fillers et PIP permet de decoupler la logique de decision de la collecte de contexte. Cette architecture est saine pour des cas d'usage reels ou les donnees viennent de LDAP, Kafka, REST ou de caches locaux.

References:

- [src/autho/pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L80)
- [src/autho/pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L95)
- [src/autho/prp.clj](/home/rsclison/autho/src/autho/prp.clj#L98)
- [src/autho/prp.clj](/home/rsclison/autho/src/autho/prp.clj#L129)

## 4.5 Horizon produit deja plus large qu'un ABAC minimal

Le projet ne s'arrete pas a "allow/deny". La presence de:

- `explain`;
- `simulate`;
- `batch`;
- `policy versions`;
- `audit-trail`;
- `isAuthorized-at-time`;

montre une intuition produit juste: la valeur de l'autorisation ne se situe pas seulement dans la reponse, mais dans la comprehension, la preuve et l'exploitation operationnelle de cette reponse.

---

## 5. Constat d'architecture detaille

## 5.1 Maturite de la couche serveur

La couche HTTP est deja serieuse. On observe:

- limitation de taille des requetes;
- validation d'entree;
- rate limiting;
- auth admin;
- gestion centralisee des erreurs;
- graceful shutdown;
- endpoints de monitoring;
- exposition OpenAPI.

Ces elements montrent un niveau de maturite superieur a un prototype.

Reference principale:

- [src/autho/handler.clj](/home/rsclison/autho/src/autho/handler.clj#L29)

## 5.2 Initialisation et dependencies externes

L'initialisation assemble metrics, audit, versioning, LDAP, Kafka PIP, watcher YAML, delegations et chargement des personnes. Cette richesse est positive, mais elle traduit aussi un couplage de demarrage assez fort.

Reference:

- [src/autho/pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L479)

Risque associe:

- plus le bootstrap centralise de responsabilites, plus il devient delicat de faire evoluer le produit vers une architecture multi-tenant, HA ou control plane / data plane.

## 5.3 Repository de politiques

Le repository de politiques repose actuellement sur:

- une map en memoire pour les politiques actives;
- une persistence H2 pour les versions;
- un chargement depuis fichier de regles;
- un import depuis YAML.

References:

- [src/autho/prp.clj](/home/rsclison/autho/src/autho/prp.clj#L54)
- [src/autho/prp.clj](/home/rsclison/autho/src/autho/prp.clj#L164)
- [src/autho/prp.clj](/home/rsclison/autho/src/autho/prp.clj#L181)

Cette approche est efficace pour demarrer, mais sera trop limitee pour un produit enterprise distribue si elle reste le mecanisme principal.

## 5.4 Evaluation de decision

Le moteur de decision montre:

- enrichissement du contexte;
- evaluation des regles applicables;
- resolution de conflits;
- support des delegations;
- audit et metriques.

Reference:

- [src/autho/pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L144)

Le socle est bon, mais une incoherence importante apparait entre la fonction `evalRequest` et la fonction `isAuthorized`, ce qui constitue l'un des points de vigilance majeurs du present audit.

---

## 6. Ecarts critiques et limites strategiques

## 6.1 Ecart critique: semantique de decision non completement unifiee

Le moteur propose une resolution explicite de conflit via `resolve-conflict` dans `evalRequest`, avec une strategie `almost_one_allow_no_deny`.

Reference:

- [src/autho/pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L46)
- [src/autho/pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L144)

Cependant, l'endpoint principal `/isAuthorized` appelle `pdp/isAuthorized`, et cette fonction retourne actuellement la liste des regles matchantes sans repasser par la logique complete de resolution de conflit exposee dans `evalRequest`.

References:

- [src/autho/handler.clj](/home/rsclison/autho/src/autho/handler.clj#L418)
- [src/autho/pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L201)

### Impact

- risque de divergence entre semantique "moteur" et semantique "API publique";
- difficulte a prouver la coherence comportementale;
- faiblesse majeure si le produit veut monter en gamme enterprise;
- dette de conception visible par un client mature ou un auditeur externe.

### Niveau de priorite

Critique.

## 6.2 Multi-tenancy et segmentation encore insuffisamment industrialises

Le produit laisse entrevoir des notions de contexte et d'application, mais la structure n'apparait pas encore comme un veritable moteur multi-tenant de premier plan.

Signal notable:

- `policy` est recuperee dans `evalRequest` puis non exploitee dans le flux critique.

Reference:

- [src/autho/pdp.clj](/home/rsclison/autho/src/autho/pdp.clj#L154)

### Impact

- difficulte a vendre une offre SaaS B2B mature;
- difficulte a garantir une isolation forte entre tenants/environnements;
- impossibilite de construire un control plane moderne sans refonte partielle.

## 6.3 Distribution horizontale encore partielle

Le projet a deja des mecanismes utiles pour l'execution distribuee, notamment cache local, invalidation et Kafka. C'est tres positif. En revanche, plusieurs responsabilites restent locales au processus:

- rate limiting en memoire;
- politiques actives en `atom`;
- demarrage monolithique;
- port fixe;
- etat local non decouple.

References:

- [src/autho/handler.clj](/home/rsclison/autho/src/autho/handler.clj#L65)
- [src/autho/prp.clj](/home/rsclison/autho/src/autho/prp.clj#L54)
- [src/autho/handler.clj](/home/rsclison/autho/src/autho/handler.clj#L713)

### Impact

- limite l'elasticite et le self-healing a grande echelle;
- complexifie la coherence inter-noeuds;
- penalise les scenarios de control plane / data plane.

## 6.4 Manque d'analyse statique et de verification formelle des politiques

Le projet valide l'entree et le schema, ce qui est deja bien. Cependant, un produit de reference du marche doit aller plus loin:

- typage des entites et attributs;
- detection de champs inconnus;
- detection de regles inatteignables;
- detection des contradictions et masquages;
- garanties de securite exprimables et verifiables.

Les offres modernes s'orientent nettement vers cette dimension. AWS Verified Permissions met en avant Cedar et l'analyse assistee; OpenFGA insiste sur les tests de modele avant deploiement.

Sources:

- [AWS Verified Permissions FAQ](https://aws.amazon.com/verified-permissions/faqs/)
- [OpenFGA Testing Models](https://openfga.dev/docs/modeling/testing)

### Impact

- risque de regression silencieuse;
- freine l'adoption par des equipes gouvernance/securite exigeantes;
- empeche Autho de devenir une reference "safe by design".

## 6.5 Experience de test de politiques encore insuffisamment productisee

Le depot contient beaucoup de tests, ce qui est tres bon signe. En revanche, la chaine produit "policy testing" n'apparait pas encore comme une experience formelle et packagee destinee aux utilisateurs du produit.

Le marche bouge dans cette direction. Permit.io propose deja un mecanisme de replay de logs d'audit contre un PDP pour verifier la coherence entre environnements.

Source:

- [Permit.io Policy Testing](https://docs.permit.io/how-to/permit-cli/permit-cli-test/)

### Opportunite

Autho est particulierement bien positionne pour aller plus loin que ce standard grace a ses briques d'audit, de versioning et de time-travel.

## 6.6 Modele d'autorisation encore trop centre ABAC

Autho est fort sur l'ABAC enrichi. C'est une force. Mais le segment le plus defendable du marche se gagne aujourd'hui sur l'hybridation:

- ABAC pour le contexte;
- ReBAC pour les graphes de relations;
- temporal pour l'historique;
- gouvernance pour les workflows.

Les moteurs de type Zanzibar/OpenFGA/SpiceDB excellent sur la relation. Autho n'a pas encore de modele relationnel natif de meme niveau.

Sources:

- [OpenFGA Testing Models](https://openfga.dev/docs/modeling/testing)
- [AuthZed / SpiceDB overview](https://authzed.com/products/authzed-serverless)

### Impact

- limite les cas d'usage collaboratifs modernes;
- reduit la comparabilite favorable face aux acteurs ReBAC;
- laisse un espace concurrentiel important.

## 6.7 Positionnement produit encore trop "moteur", pas assez "plateforme"

Le projet a deja des indices de plateforme, mais la proposition de valeur n'est pas encore fermee sur les operations d'autorisation:

- access review;
- just-in-time access;
- approbation;
- segregation of duties;
- evidence de conformite;
- recertification.

Ce sont pourtant des points qui augmentent fortement la valeur economique et le pouvoir de differenciation.

---

## 7. Positionnement marche

## 7.1 Ce qu'Autho peut raisonnablement concurrencer aujourd'hui

En l'etat, Autho peut deja etre credible face a:

- des moteurs internes faits maison;
- des PDP artisanaux peu audites;
- des bibliotheques d'autorisation applicatives dispersant la logique;
- certains deploiements simples d'OPA quand l'exigence principale est un serveur d'autorisation central et non un moteur de policy generique.

## 7.2 Ce qui manque pour concurrencer les leaders du segment

Pour rivaliser frontalement avec les references modernes, Autho doit franchir un palier sur:

- l'analyse pre-deploiement;
- les tests de politiques industrialises;
- le multi-tenant;
- la haute disponibilite distribuable;
- les modeles relationnels;
- les workflows de gouvernance.

## 7.3 Positionnement strategique recommande

Le meilleur positionnement n'est pas "encore un moteur d'autorisation". Le meilleur positionnement est:

"La plateforme d'Authorization Operations qui combine decision en temps reel, audit verifiable, time-travel, simulation d'impact et gouvernance."

Ce positionnement est plus defendable car il s'appuie sur des forces deja presentes dans le code.

---

## 8. Vision differenciante recommandee

## 8.1 Cap sur un "Authorization Digital Twin"

Le plus grand levier strategique pour Autho est de devenir le premier produit qui maintient un jumeau numerique complet de l'autorisation.

Concretement, cela signifie:

- rejouer des decisions passees;
- comparer version courante et version candidate;
- mesurer qui gagne ou perd un acces avant deployment;
- expliquer les ecarts;
- produire des preuves d'audit;
- simuler des scenarios hypothetique a date T.

Autho a deja des briques uniques pour cela:

- audit;
- versions;
- explain;
- simulate;
- time-travel.

Tres peu d'acteurs ont ces elements deja reunis de cette maniere.

## 8.2 Construire un moteur hybride ABAC + ReBAC + Temporal

La seconde grande orientation recommandee est d'ajouter un coeur relationnel natif:

- tuples ou relations;
- permissions derivees;
- groupes, organisations, hierarchies, ownership;
- delegations contextualisees;
- evaluation relationnelle combinee aux attributs.

Cette hybridation permettrait a Autho de couvrir a la fois:

- les SI administratifs;
- les applications collaboratives;
- les API metier;
- les dossiers sensibles;
- les plateformes multi-organisationnelles.

## 8.3 Monter en gamme sur la "policy safety"

Un produit sans concurrence ne se contente pas de decider vite. Il empeche ses clients de deployer une mauvaise politique.

Il faut donc viser:

- un compilateur de politique type-safe;
- un linter semantique;
- un detecteur de conflits;
- un moteur de tests de non-regression;
- une verification de proprietes de securite.

---

## 9. Feuille de route recommandee

## 9.1 Priorite immediate: fiabiliser le coeur

### Objectifs

- unifier la semantique de decision;
- aligner `isAuthorized`, `explain`, `simulate` et audit sur le meme coeur;
- clarifier les strategies de conflit;
- introduire des contrats stables de reponse.

### Livrables attendus

- un seul pipeline canonique de decision;
- schema de reponse v1 stable;
- tests de coherence inter-endpoints;
- documentation de semantique formelle.

### Benefice business

Sans ce socle, toute ambition enterprise reste fragile.

## 9.2 Priorite haute: industrialiser le policy lifecycle

### Objectifs

- tests declaratifs de politiques;
- replay de logs d'audit;
- shadow evaluation;
- diff d'impact entre versions;
- promotion GitOps dev/staging/prod.

### Livrables attendus

- format de tests de politiques;
- CLI ou endpoints de replay;
- rapport d'impact avant merge;
- blocage automatique des regressions.

### Benefice business

Tres fort facteur de differenciation face aux moteurs qui ne donnent qu'une decision.

## 9.3 Priorite haute: ajouter un noyau relationnel

### Objectifs

- support natif de relations et permissions derivees;
- list users / list objects / check permission;
- combinaison avec ABAC et temps.

### Livrables attendus

- schema relationnel;
- API de tuples ou relations;
- requetes hybrides;
- explain relationnel.

### Benefice business

Ouverture du produit a des cas d'usage aujourd'hui tres bien servis par OpenFGA et SpiceDB.

## 9.4 Priorite moyenne-haute: architecture enterprise

### Objectifs

- multi-tenant strict;
- separation control plane / data plane;
- distribution des policies;
- bundles signes;
- ports et runtime configurables;
- externalisation d'etat local non critique.

### Benefice business

Condition necessaire pour une offre managede ou un deploiement a grande echelle.

## 9.5 Priorite moyenne: gouvernance et compliance

### Objectifs

- demandes d'acces;
- approbations;
- just-in-time access;
- break-glass;
- recertifications;
- evidences d'audit.

### Benefice business

Transformation du produit en plateforme achetable non seulement par les equipes techniques, mais aussi par la securite, la conformite et les metiers.

---

## 10. Recommandations detaillees

## 10.1 Recommandations techniques prioritaires

1. Creer un moteur canonique unique de decision utilise par tous les endpoints.
2. Formaliser la strategie de conflit et les invariants associes.
3. Introduire un schema de types pour sujets, ressources, attributs et operations.
4. Ajouter une phase de compilation/lint des politiques avant activation.
5. Construire un moteur de replay de decisions a partir de l'audit.
6. Ajouter un mode "impact analysis" avant merge ou rollback.
7. Isoler plus nettement la gestion du state actif et du stockage historique.
8. Rendre l'execution plus configurable et plus portable en environnement distribue.

## 10.2 Recommandations produit prioritaires

1. Positionner Autho comme plateforme d'Authorization Operations.
2. Communiquer sur le couple "decision + preuve + simulation".
3. Faire du time-travel et du replay un marquee feature, pas un addon.
4. Packager des parcours GitOps et CI/CD policy-first.
5. Ajouter une experience d'onboarding developpeur exemplaire.

## 10.3 Recommandations go-to-market

1. Cibler d'abord les domaines a forte exigence d'auditabilite.
2. Mettre en avant la reduction du risque de regression d'autorisations.
3. Demonstrer la valeur avec des cas "avant/apres changement de politique".
4. Produire des demos ou l'explication et l'impact analysis sont centrales.

---

## 11. Scenario cible a 12 mois

Si la feuille de route recommandee est executee avec rigueur, Autho peut devenir en 12 mois:

- un serveur d'autorisation enterprise credible;
- une plateforme de simulation et d'analyse d'impact d'autorisations;
- un produit hybride ABAC/ReBAC/temporal;
- une base serieuse pour une offre self-hosted premium ou managede.

Dans ce scenario, Autho ne serait plus compare uniquement sur:

- la vitesse d'evaluation;
- la syntaxe des regles;
- le nombre de connecteurs.

Il serait compare sur un territoire bien plus defensable:

- "Qui peut prouver l'effet d'un changement de politique avant qu'il casse la production ?"
- "Qui peut rejouer et expliquer une decision passee de maniere fiable ?"
- "Qui peut auditer, simuler et gouverner l'autorisation de bout en bout ?"

Sur ce terrain, Autho peut se construire une vraie singularite.

---

## 12. Conclusion generale

Le projet audite est solide, ambitieux et deja inhabituellement riche. La qualite generale du socle montre une vision technique serieuse. Autho n'est pas un simple moteur local; il est deja en transition vers une plateforme.

Le principal enseignement de cet audit est le suivant:

Autho ne deviendra pas sans concurrence en ajoutant des fonctionnalites standard de plus. Il peut en revanche devenir un acteur distinctif en assumant un positionnement plus ambitieux:

- moteur d'autorisation hybride;
- plateforme d'operations d'autorisation;
- systeme de preuve, simulation et gouvernance de l'acces.

La meilleure opportunite strategique est donc de capitaliser sur les briques deja presentes pour construire un produit centre sur:

- la confiance;
- la verification;
- l'analyse d'impact;
- l'historicite;
- l'explicabilite.

En synthese:

- la base technique est bonne;
- le potentiel produit est eleve;
- la differenciation decisive reste a construire;
- la trajectoire la plus prometteuse est claire.

---

## 13. Synthese des priorites

### Priorite 1

Unifier strictement le coeur de decision.

### Priorite 2

Lancer une chaine complete de test, replay et analyse d'impact des politiques.

### Priorite 3

Ajouter un modele relationnel natif pour devenir hybride ABAC/ReBAC.

### Priorite 4

Industrialiser multi-tenant, distribution et control plane.

### Priorite 5

Evoluer vers une plateforme de gouvernance et de compliance.

---

## 14. Annexes

### Annexes internes

- [src/autho/core.clj](/home/rsclison/autho/src/autho/core.clj)
- [src/autho/handler.clj](/home/rsclison/autho/src/autho/handler.clj)
- [src/autho/pdp.clj](/home/rsclison/autho/src/autho/pdp.clj)
- [src/autho/prp.clj](/home/rsclison/autho/src/autho/prp.clj)
- [ROADMAP.md](/home/rsclison/autho/ROADMAP.md)
- [docs/ARCHITECTURE.md](/home/rsclison/autho/docs/ARCHITECTURE.md)

### Annexes externes

- [AWS Verified Permissions FAQ](https://aws.amazon.com/verified-permissions/faqs/)
- [OpenFGA Testing Models](https://openfga.dev/docs/modeling/testing)
- [Permit.io Policy Testing](https://docs.permit.io/how-to/permit-cli/permit-cli-test/)
- [AuthZed / SpiceDB overview](https://authzed.com/products/authzed-serverless)
