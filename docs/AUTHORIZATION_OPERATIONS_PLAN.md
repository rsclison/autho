# Plan Authorization Operations

## Vision

Autho ne doit pas etre positionne comme un simple moteur ABAC/XACML. Le marche contient deja de tres bons moteurs de policy et de ReBAC. Le positionnement defendable est plus large :

> Autho est une plateforme d'Authorization Operations qui permet de decider, expliquer, tester, rejouer, comparer, gouverner et prouver chaque decision d'autorisation.

L'objectif est de faire d'Autho le produit qui reduit les incidents d'autorisation en production, pas seulement le composant qui repond `allow` ou `deny`.

## Priorite 1 - Socle de confiance

Objectif : aucune ambiguite sur une decision.

Chantiers :

1. Unifier la semantique de decision entre `isAuthorized`, `explain`, `simulate`, batch et time-travel.
2. Definir un contrat canonique commun :
   - decision `allow` ou `deny`;
   - booleen `allowed?` / `allowed`;
   - sujet effectif;
   - ressource effective;
   - operation;
   - strategie de conflit;
   - regles evaluees;
   - regles matchees;
   - source de politique;
   - version de politique quand disponible.
3. Preserver la compatibilite des champs historiques (`results`, `matchedRules`, etc.).
4. Ajouter des tests de coherence croisee entre endpoints.
5. Documenter les invariants de decision et les cas legacy.

Critere de succes : une meme requete donne la meme decision logique et les memes regles matchees sur tous les endpoints de decision.

## Priorite 2 - Policy Safety

Objectif : empecher de deployer une mauvaise politique.

Chantiers :

1. Schema de types pour sujets, ressources, attributs, operations et contexte.
2. Linter de politiques :
   - attribut inconnu;
   - classe inconnue;
   - operation inconnue;
   - regle morte;
   - regle impossible;
   - conflit deny/allow suspect.
3. Tests declaratifs de politiques en YAML/EDN.
4. Commandes et endpoints de validation pre-deploiement.
5. Environnements de politique : `dev`, `staging`, `prod`.

Critere de succes : aucune politique ne passe en production sans validation et tests.

## Priorite 3 - Replay, shadow et impact analysis

Objectif : predire l'effet d'une politique avant activation.

Chantiers :

1. Rejouer les decisions historiques depuis l'audit.
2. Comparer politique courante et politique candidate.
3. Produire un rapport d'impact :
   - acces gagnes;
   - acces perdus;
   - ressources sensibles impactees;
   - populations touchees;
   - regles responsables.
4. Ajouter le mode shadow evaluation.
5. Ajouter des seuils de blocage avant rollout.

Critere de succes : chaque changement majeur de politique peut etre compare avant activation.

## Priorite 4 - Moteur hybride ABAC/ReBAC/temporal

Objectif : couvrir les cas relationnels de type Zanzibar sans perdre la richesse ABAC.

Chantiers :

1. Ajouter un modele relationnel : tuples sujet-relation-objet.
2. Ajouter des predicates de relation dans les conditions.
3. Combiner relations, attributs et temps dans le meme pipeline.
4. Indexer les relations pour les requetes directes et inverses.
5. Tester les cas organisation, dossier parent, ownership, viewer/editor.

Critere de succes : Autho couvre les cas ReBAC courants tout en conservant ABAC et time-travel.

## Priorite 5 - Architecture enterprise

Objectif : rendre Autho deployable dans des environnements critiques.

Chantiers :

1. Separer control plane, data plane et evidence plane.
2. Ajouter le multi-tenant.
3. Distribuer des politiques signees vers les PDP.
4. Supporter un mode sidecar/local PDP.
5. Remplacer ou completer H2 par PostgreSQL pour les usages enterprise.
6. Ajouter migrations, runbooks, backups, rotation de secrets.
7. Exposer OpenTelemetry de bout en bout.

Critere de succes : un client enterprise peut deployer Autho sans bricolage.

## Priorite 6 - Gouvernance et compliance

Objectif : transformer l'autorisation en processus gouvernable.

Chantiers :

1. Workflow de politique : draft, review, approved, deployed, rollback.
2. Approbation obligatoire si impact eleve.
3. Ownership des politiques.
4. Attestation periodique des acces.
5. Rapports conformite :
   - audit exportable;
   - preuve HMAC;
   - historique de decision;
   - justification de regle.

Critere de succes : Autho parle aux equipes securite, conformite, DPO et RSSI, pas seulement aux developpeurs.

## Plan 90 jours

1. Stabiliser le contrat de decision canonique.
2. Corriger les divergences legacy/v1.
3. Ajouter `policy lint`.
4. Ajouter les tests declaratifs de politiques.
5. Industrialiser l'impact analysis.
6. Ajouter une demo commerciale complete : API key application, utilisateur delegue, explain, simulate, audit, replay, rapport d'impact.
7. Publier une comparaison produit honnete : Autho vs OPA, Cedar, OpenFGA, SpiceDB.

## Plan 12 mois

1. Moteur canonique et policy safety.
2. Replay historique et shadow evaluation.
3. ABAC/ReBAC hybride.
4. Control plane / data plane.
5. Multi-tenant.
6. UI de gouvernance complete.
7. SDKs officiels Java, TypeScript, Python et Go.
8. Benchmarks publics.
9. Deploiement Helm/Kubernetes.
10. Offre open-core claire.
