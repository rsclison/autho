# Plan Authorization Operations

## Vision

Autho ne doit pas etre positionne comme un simple moteur ABAC/XACML. Le marche contient deja de tres bons moteurs de policy et de ReBAC. Le positionnement defendable est plus large :

> Autho est une plateforme d'Authorization Operations qui permet de decider, expliquer, tester, rejouer, comparer, gouverner et prouver chaque decision d'autorisation.

L'objectif est de faire d'Autho le produit qui reduit les incidents d'autorisation en production, pas seulement le composant qui repond `allow` ou `deny`.

## Etat d'implementation actuel

Les priorites 1 a 3 disposent maintenant d'un socle operationnel :

- contrat de decision canonique expose par les endpoints de decision principaux;
- validation pre-deploiement, tests declaratifs de politiques et environnements `dev`, `staging`, `prod`;
- replay audit initial pour construire des batches d'impact depuis les decisions historiques;
- shadow evaluation via `POST /v1/authz/shadow`;
- impact analysis avec profils de risque persistables, revisions append-only, approbations et garde-fous de rollout;
- lifecycle auditable des politiques : versions, diff, rollback, rollout depuis analyse d'impact et timeline;
- RBAC de gouvernance sur les mutations critiques via `API_CLIENT_ROLES` ou roles JWT;
- premier socle ReBAC durable : tuples sujet-relation-ressource persistés en H2, predicat `relation` dans les politiques, API `/v1/relations`.

Les limites connues restent :

- le ReBAC supporte l'heritage par ressources parentes, groupes imbriques et rewrites persistés de relations, mais pas encore les traversals relationnels generiques;
- les tuples relationnels sont persistés localement en H2, mais doivent encore etre externalises ou distribués pour un usage enterprise multi-instance;
- le control plane, le data plane et l'evidence plane ne sont pas encore separes;
- le multi-tenant, les bundles signes et les workflows GRC complets restent a construire.

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

Etat d'avancement :

- contrat canonique initial ajoute sur `isAuthorized`, `explain` et `simulate`;
- coherence croisee testee entre ces trois endpoints;
- batch v1 cadre pour retourner des decisions canoniques dans `data.results`;
- invariants documentes dans `docs/DECISION_CONTRACT.md`;
- strategie de conflit supportee formalisee et verifiee par la validation statique;
- time-travel explicitement exclu du contrat tant que l'integration PDP historique reste en attente.

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

Etat d'avancement :

- validation statique existante : doublons, contradictions, operateurs invalides, regles trop larges et shadowed;
- schema optionnel ajoute pour valider classes, attributs et operations connus;
- tests declaratifs embarques dans les politiques et executes avant persistence via `submit-policy`;
- endpoint de validation pre-deploiement `POST /v1/policies/:resourceClass/validate` sans persistence;
- commande CI/CLI `./lein policy:validate --file candidate-policy.json`;
- environnements de politique `dev`, `staging`, `prod` avec fallback historique sur `prod`;
- rapport de validation agrege avec statut, resume et gates `schema`, `policy-safety`, `policy-tests`;
- documentation du schema, des tests declaratifs et des erreurs dans `docs/POLICY_SAFETY.md`;
- prochaine etape : preparer la priorite 3 avec comparaison policy courante vs candidate.

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

Etat d'avancement :

- comparaison policy courante/versionnee vs policy candidate disponible via `POST /v1/policies/:resourceClass/impact`;
- replay audit initial disponible comme source de requetes via `auditReplay`;
- nouvelles entrees d'audit enrichies avec snapshots complets de requete et decision;
- shadow evaluation disponible via `POST /v1/authz/shadow` : la decision de production reste authoritative et la politique candidate est comparee en dry-run;
- rapport d'impact ajoute avec recommendation `approve`, `review`, `block`;
- seuils de blocage configurables : `maxRevokes`, `maxChangedDecisions`, `allowSensitiveResourceChanges`;
- profils de risque configurables par defaut, environnement et classe de ressource, avec override explicite par requete;
- profils de risque persistables et administrables via `GET/PUT/DELETE /v1/policies/risk-profiles...`;
- revisions append-only des changements de profils de risque consultables via `GET /v1/policies/risk-profiles/revisions`;
- revisions de profils de risque integrees a la timeline globale avec `eventType = risk_profile_changed`;
- approbation obligatoire pour les assouplissements critiques de profils de risque et les suppressions de profils existants;
- separation auteur/approbateur imposee pour ces changements critiques;
- garde-fous de rollout : les analyses `block` sont non deployables, les analyses `review` exigent une approbation, les analyses `approve/no_impact` peuvent etre deployees directement;
- versions de politique enrichies avec metadata de lifecycle (`lifecycleStatus`, `workflowAction`, `rollbackFromVersion`) pour tracer les deployments directs, rollouts et rollbacks;
- rapport agrege des ressources sensibles touchees, populations touchees et regles responsables;
- controles RBAC reels sur les endpoints de gouvernance : `policy-admin`, `risk-profile-admin`, `policy-reviewer`, `policy-deployer`, avec bypass explicite `governance-admin`;
- role `relation-admin` ajoute pour administrer les tuples relationnels;
- prochaine etape : poursuivre la priorite 4 avec stockage durable des relations et explain relationnel.

## Priorite 4 - Moteur hybride ABAC/ReBAC/temporal

Objectif : couvrir les cas relationnels de type Zanzibar sans perdre la richesse ABAC.

Chantiers :

1. Ajouter un modele relationnel : tuples sujet-relation-objet.
2. Ajouter des predicates de relation dans les conditions.
3. Combiner relations, attributs et temps dans le meme pipeline.
4. Indexer les relations pour les requetes directes et inverses.
5. Tester les cas organisation, dossier parent, ownership, viewer/editor.

Critere de succes : Autho couvre les cas ReBAC courants tout en conservant ABAC et time-travel.

Etat d'avancement :

- modele relationnel direct ajoute sous forme de tuples sujet-relation-ressource;
- predicat de politique `["relation", "$s", "viewer", "$r"]` disponible dans `conditions`;
- API minimale `GET/POST/DELETE /v1/relations` pour administrer les tuples directs;
- controle RBAC des mutations relationnelles via `relation-admin` ou `governance-admin`;
- heritage par relation `parent` ajoute : un droit accorde sur une ressource parente s'applique a ses descendants;
- index en memoire des relations `parent` pour eviter un scan complet pendant la resolution d'heritage;
- explain relationnel minimal disponible via `POST /v1/relations/check` avec `matchedResource`, `inherited` et `path`;
- persistence H2 des tuples via `REBAC_RELATIONS`, rechargee par `rebac/init!` au demarrage PDP;
- explain des decisions completes enrichi avec `relationProofs` pour les regles qui utilisent une clause ReBAC;
- groupes imbriques ajoutes via la relation `member`, avec `subjectPath` dans les preuves relationnelles;
- rewrites de usersets persistés via `REBAC_RELATION_REWRITES`, administrables par `GET/PUT/DELETE /v1/relations/rewrites...`;
- `matchedRelation` et `relationPath` ajoutés dans les preuves;
- mode list objects/list subjects ReBAC expose via `POST /v1/relations/list-objects` et `POST /v1/relations/list-subjects`;
- index inverses en memoire pour retrouver descendants de ressources parentes et membres de groupes;
- traversal relationnel generique expose via `POST /v1/relations/traverse`, avec chemins explicites et directions sortante/inverse;
- prochaine etape : préparer l'architecture enterprise et le stockage relationnel distribué.

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

1. Stabilise : contrat de decision canonique, validation, tests declaratifs et impact analysis initial.
2. Stabilise : gouvernance RBAC minimale et lifecycle policy auditable.
3. En cours : moteur hybride ABAC/ReBAC avec tuples directs, héritage, rewrites persistés, list objects/list subjects et traversals explicites.
4. Prochaine tranche : architecture enterprise et stockage relationnel distribué.
5. Prochaine tranche : demo commerciale complete avec API key application, utilisateur delegue, explain, simulate, audit, replay, rapport d'impact et relation ReBAC.
6. Prochaine tranche : comparaison produit honnete Autho vs OPA, Cedar, OpenFGA, SpiceDB.

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
