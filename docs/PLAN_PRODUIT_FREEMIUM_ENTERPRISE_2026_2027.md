# Plan produit Freemium et Enterprise 2026-2027

## Autho Authorization Operations

**Statut** : plan directeur d'execution  
**Date de reference** : 12 aout 2026  
**Horizon** : 12 mois  
**Responsable** : a designer  
**Documents sources** :

- `docs/AUTHORIZATION_OPERATIONS_PLAN.md` ;
- `docs/AUDIT_STRATEGIQUE_AUTHO_2026.md` ;
- `docs/AUDIT_COMMERCIALISATION_2026-04-27.md` ;
- `docs/ROADMAP_EXECUTION_2026_2027.md` ;
- `docs/SECURITY_TARGET.md` ;
- `docs/OPERATIONS_RUNBOOK.md`.

---

## 1. Objectif

Transformer Autho, aujourd'hui serveur d'autorisation techniquement riche et utilisable en pilote, en une offre freemium B2B exploitable, administrable et achetable.

Le produit cible doit permettre a une entreprise de :

1. centraliser ses decisions d'autorisation ;
2. integrer Autho sans assistance directe de l'equipe produit ;
3. isoler strictement ses organisations, projets et environnements ;
4. administrer ses utilisateurs, applications, politiques et secrets ;
5. tester et mesurer l'effet d'une politique avant son activation ;
6. prouver a posteriori pourquoi un acces a ete accorde ou refuse ;
7. deployer Autho en haute disponibilite ou utiliser une offre managee ;
8. souscrire, changer d'edition et suivre sa consommation ;
9. obtenir les garanties de securite, de support et de conformite attendues en entreprise.

Le positionnement retenu est :

> Autho est une plateforme d'Authorization Operations qui permet de decider, expliquer, tester, rejouer, comparer, gouverner et prouver chaque decision d'autorisation.

---

## 2. Etat initial confirme

### 2.1 Capacites deja livrees

Le plan part du principe que les capacites suivantes existent deja et doivent etre consolidees, non reimplementees :

- moteur ABAC/XACML et contrat de decision canonique ;
- endpoints de decision, batch, explain, simulate et shadow ;
- validation statique et tests declaratifs de politiques ;
- versioning, diff, rollback et environnements de politiques ;
- replay initial depuis l'audit et analyse d'impact ;
- profils de risque, review et garde-fous de rollout ;
- journal d'audit chaine par HMAC et evidence bundle ;
- ReBAC avec tuples persistants, groupes imbriques, heritage parent, rewrites, list objects, list subjects et traversal ;
- resolution initiale du tenant et cache de decisions cloisonne ;
- bundles signes, verification et application cote runtime ;
- cache local, invalidation Kafka et PIP Kafka/RocksDB ;
- metriques Prometheus et instrumentation OpenTelemetry initiale ;
- console d'administration React ;
- licences signees Ed25519 avec tiers Free, Pro et Enterprise ;
- documentation d'exploitation, sauvegarde, restauration et upgrade ;
- clients ou exemples Clojure, Java et Python.

### 2.2 Validation de reference

Au 12 aout 2026, la suite backend execute :

- 473 tests ;
- 1 743 assertions ;
- 0 echec ;
- 0 erreur.

Cette baseline doit etre conservee ou amelioree a chaque jalon.

### 2.3 Limites structurantes restantes

- le tenant n'est pas encore une cle de partition obligatoire dans tous les stores persistants ;
- H2 reste le stockage principal de plusieurs fonctions critiques ;
- le control plane, le data plane et l'evidence plane ne sont pas reellement separes ;
- les limites de licence `instances` et `decisions` ne sont pas appliquees ;
- il n'existe pas de metering commercial, portail client ou facturation ;
- l'identite d'entreprise ne couvre pas encore OIDC/JWKS, SAML et SCIM ;
- les API keys ne disposent pas d'un lifecycle multi-cle complet ;
- le chemin Kubernetes/HA n'est pas livre comme produit supporte ;
- les tests automatises ne valident pas encore toute la stack Kafka/LDAP ;
- les SDK ne constituent pas encore une gamme officielle maintenue ;
- les workflows GRC restent incomplets.

---

## 3. Principes de conception

### 3.1 Isolation par defaut

Toute donnee metier ou technique doit appartenir explicitement a une organisation, un projet, un environnement et un tenant. Une operation sans contexte valide doit echouer en deny-by-default.

### 3.2 Un coeur, plusieurs modes de deploiement

Le meme contrat de decision doit fonctionner en service central, data plane local, sidecar ou offre managee.

### 3.3 Adoption avant monetisation

Le Free doit permettre a un developpeur de comprendre et valider Autho. Les limitations doivent porter sur le volume, la retention, le nombre d'environnements et les garanties operationnelles, pas supprimer les outils necessaires a une premiere integration.

### 3.4 Securite verifiable

Une garantie de securite doit etre accompagnee d'un test automatise, d'une metrique ou d'une preuve d'audit.

### 3.5 Pas de promesse sans gate

Une capacite n'est commercialement disponible que si son chemin de deploiement, son monitoring, son backup, sa documentation et son support sont definis.

### 3.6 Compatibilite explicite

L'API `v1` est la voie recommandee. Les endpoints historiques sont maintenus uniquement selon une politique de compatibilite et de deprecation publiee.

---

## 4. Architecture produit cible

```text
                           Portail client / Admin UI
                                      |
                         +------------v------------+
                         |      Control plane       |
                         | organisations / projets |
                         | policies / bundles      |
                         | licences / metering     |
                         +------------+------------+
                                      |
                       bundles signes | evenements
                                      |
             +------------------------+------------------------+
             |                                                 |
  +----------v-----------+                         +-----------v----------+
  | Data plane / PDP A   |                         | Data plane / PDP B  |
  | decisions / cache    |                         | decisions / cache   |
  | PIP / ReBAC local    |                         | PIP / ReBAC local   |
  +----------+-----------+                         +-----------+----------+
             |                                                 |
             +------------------------+------------------------+
                                      |
                         +------------v------------+
                         |      Evidence plane      |
                         | audit / replay / export |
                         | retention / SIEM        |
                         +-------------------------+

  Stores partages : PostgreSQL, relation store, object storage, Kafka
  Observabilite   : OpenTelemetry, Prometheus, logs structures, alertes
```

### 4.1 Control plane

Responsable des organisations, projets, environnements, politiques, workflows, bundles, identites administratives, abonnements et licences.

### 4.2 Data plane

Responsable uniquement de l'evaluation a faible latence. Il doit pouvoir continuer a prendre des decisions pendant une indisponibilite temporaire du control plane, avec la derniere politique valide connue.

### 4.3 Evidence plane

Responsable de l'audit, de la retention, du replay, des exports et des preuves de conformite. Ses pannes ne doivent pas modifier silencieusement une decision ; le mode de degradation doit etre configurable et visible.

---

## 5. Packaging cible

Les quotas chiffres ci-dessous sont des hypotheses de lancement. Ils devront etre valides par les benchmarks et le cout d'exploitation avant publication.

| Capacite | Free | Pro | Enterprise |
|---|---|---|---|
| Usage cible | evaluation et prototype | production d'une equipe | systemes critiques et multi-equipes |
| Organisations | 1 | 1 a plusieurs selon offre | illimite ou contrat |
| Projets | 1 | plusieurs | plusieurs |
| Environnements | 1 | dev, staging, prod | personnalisables |
| Decisions | quota mensuel limite | quota extensible | engagement contractuel |
| ABAC/ReBAC | oui | oui | oui |
| Explain/simulate | oui, limites de volume | oui | oui |
| Audit | retention courte | retention configurable | retention contractuelle et export externe |
| Versioning/rollback | limite | complet | complet avec workflow |
| Shadow/impact/replay | echantillon ou quota | complet | complet et automatise |
| SSO | non | OIDC optionnel | OIDC/SAML |
| SCIM | non | non | oui |
| Haute disponibilite | non garantie | option self-hosted | supportee avec SLA |
| Kafka/multi-instance | non | option | oui |
| SIEM/webhooks | non | webhooks | SIEM et connecteurs |
| Support | communaute | heures ouvreables | SLA contractuel |

### 5.1 Metrique de facturation recommandee

La metrique principale est le nombre de decisions traitees par mois. Elle est completee par :

- le nombre d'environnements pour l'offre managee ;
- le nombre d'instances ou de clusters pour le self-hosted ;
- la duree de retention de l'audit ;
- les options de support et de conformite.

Eviter une facturation par nombre de politiques ou d'utilisateurs finaux : elle penaliserait l'adoption et serait difficile a anticiper.

### 5.2 Regles d'entitlement

- les droits sont determines cote serveur par un entitlement signe ;
- un entitlement contient edition, dates, quotas et options explicites ;
- les compteurs de volume ne doivent pas etre contournables par redemarrage ;
- une panne du service de licence ne doit pas interrompre instantanement les decisions ;
- une periode de grace documentee s'applique aux licences expirees ;
- tout changement d'entitlement est audite ;
- le client peut consulter sa consommation et ses limites ;
- aucun payload d'autorisation sensible n'est envoye au service commercial de metering.

---

## 6. Lots de travail

## Lot A - Multi-tenancy et modele de domaine

**Priorite** : P0  
**Objectif** : supprimer tout risque de fuite inter-tenant et fournir les objets necessaires au produit SaaS.

### Travaux

1. Introduire les entites `organization`, `project`, `environment` et `tenant`.
2. Definir des identifiants opaques, non reutilisables et stables.
3. Ajouter les cles de partition aux tables de :
   - politiques actives ;
   - versions et bundles ;
   - relations ReBAC et rewrites ;
   - analyses d'impact et profils de risque ;
   - audit et evidence ;
   - cles API et identites applicatives ;
   - compteurs d'usage.
4. Ajouter contraintes uniques et index composites incluant le tenant.
5. Interdire les requetes de store non scopees.
6. Propager le contexte dans Kafka, caches, traces et logs.
7. Ajouter une purge tenant complete et auditable.
8. Tester les tentatives d'acces croise sur chaque API et store.

### Criteres d'acceptation

- aucune table fonctionnelle partagee ne peut etre lue sans `tenant_id` ;
- un test automatise de non-divulgation existe pour chaque famille de donnees ;
- les cles de cache et messages Kafka incluent le tenant ;
- les logs n'exposent pas de donnees d'un autre tenant ;
- la suppression d'un tenant produit un rapport de purge verifiable.

### Dependances

- modele PostgreSQL du lot B ;
- modele d'identite du lot C.

---

## Lot B - PostgreSQL, migrations et durabilite

**Priorite** : P0  
**Objectif** : fournir un stockage supportable en production multi-instance.

### Travaux

1. Definir une abstraction de repository pour sortir la logique SQL des modules metier.
2. Ajouter PostgreSQL pour les donnees de control plane et ReBAC.
3. Conserver H2 comme option de developpement ou Free local si pertinent.
4. Choisir et integrer un outil de migrations versionnees.
5. Implementer pool de connexions, timeouts et health checks.
6. Gerer transactions et idempotence des mutations.
7. Fournir une migration H2 vers PostgreSQL documentee et testee.
8. Tester backup, point-in-time recovery et restauration partielle.
9. Ajouter chiffrement en transit et integration aux gestionnaires de secrets.

### Criteres d'acceptation

- deux PDP partagent les memes politiques et relations sans divergence ;
- une migration peut etre executee puis annulee selon la procedure supportee ;
- backup et restore sont verifies automatiquement ;
- aucun schema n'est cree implicitement au demarrage en production ;
- la perte d'une connexion produit un comportement degrade observable, sans corruption.

---

## Lot C - Identite, organisations et secrets

**Priorite** : P0/P1  
**Objectif** : administrer Autho selon les standards des entreprises.

### Travaux

1. Ajouter plusieurs comptes administrateurs par organisation.
2. Implementer invitations et desactivation.
3. Definir les roles :
   - organization owner ;
   - billing admin ;
   - security admin ;
   - policy author ;
   - policy reviewer ;
   - policy deployer ;
   - auditor ;
   - read-only viewer.
4. Implementer OIDC Authorization Code avec PKCE pour la console.
5. Verifier les JWT via JWKS, avec rotation de cles et claims configurables.
6. Ajouter des connecteurs documentes Entra ID, Okta et Keycloak.
7. Ajouter SAML 2.0 pour Enterprise.
8. Ajouter SCIM 2.0 pour utilisateurs et groupes Enterprise.
9. Remplacer la cle API unique par un registre de cles :
   - prefixe et nom ;
   - hash irreversiblement stocke ;
   - scopes, tenants et roles ;
   - date de creation, derniere utilisation et expiration ;
   - revocation et rotation chevauchante.
10. Auditer connexions, echecs, creation et revocation de secrets.

### Criteres d'acceptation

- aucun secret d'API n'est stocke en clair ;
- une cle peut etre revoquee sans redemarrer le serveur ;
- une rotation sans interruption est documentee et testee ;
- le retrait SCIM d'un utilisateur coupe son acces administratif ;
- les permissions de la console et des API reposent sur le meme modele.

---

## Lot D - Metering, licences et facturation

**Priorite** : P0/P1  
**Objectif** : rendre les editions applicables et commercialisables.

### Travaux

1. Appliquer les claims de licence `instances` et `decisions`.
2. Construire un compteur durable de decisions par organisation et periode.
3. Rendre l'enregistrement idempotent et resistant aux retries.
4. Definir soft limits, hard limits et alertes de seuil.
5. Implementer essai, upgrade, downgrade, expiration et periode de grace.
6. Construire un service d'emission/revocation des licences signees.
7. Ajouter une API d'entitlements et de consommation.
8. Afficher edition, consommation et limites dans la console.
9. Integrer un fournisseur de paiement pour l'offre managee.
10. Gerer factures, taxes, coupons et changements de formule.
11. Proposer un mode offline pour les clients Enterprise self-hosted.
12. Auditer toutes les transitions commerciales.

### Criteres d'acceptation

- les limites annoncees sont effectivement appliquees ;
- un retry de decision ne double pas arbitrairement le comptage ;
- un client voit sa consommation avant blocage ;
- une panne de billing ne bloque pas le PDP ;
- le downgrade ne supprime aucune donnee sans confirmation et delai ;
- le mode offline ne requiert pas l'envoi de decisions au fournisseur.

---

## Lot E - Separation control/data/evidence planes

**Priorite** : P1  
**Objectif** : rendre le runtime distribuable et resilient.

### Travaux

1. Formaliser les contrats entre les trois planes.
2. Extraire le lifecycle de politiques du chemin critique de decision.
3. Distribuer les bundles signes avec version, checksum et tenant.
4. Implementer activation atomique et conservation de la derniere version saine.
5. Ajouter acknowledgement de deploiement par instance.
6. Implementer rollback coordonne et canary rollout.
7. Definir le comportement hors ligne du data plane.
8. Separer l'ecriture d'audit du traitement commercial des evidences.
9. Ajouter une dead-letter queue et une procedure de rejeu.
10. Permettre le mode sidecar/local PDP.

### Criteres d'acceptation

- un PDP redemarre avec un bundle valide sans joindre le control plane ;
- une signature, un checksum ou un tenant incorrect bloque l'activation ;
- un rollout partiel est visible et peut etre annule ;
- aucune politique intermediaire n'est observable pendant l'activation ;
- les modes fail-open/fail-closed de l'audit sont explicites et testes.

---

## Lot F - Haute disponibilite et plateforme Kubernetes

**Priorite** : P1  
**Objectif** : proposer un chemin de deploiement Enterprise supporte.

### Travaux

1. Produire des images minimales, non-root et multi-architecture.
2. Signer les images et publier SBOM et provenance.
3. Creer un Helm chart versionne.
4. Configurer readiness, liveness et startup probes.
5. Ajouter PodDisruptionBudget, anti-affinite et autoscaling.
6. Documenter ingress TLS et network policies.
7. Tester rolling upgrade et rollback.
8. Tester perte d'un PDP, d'un broker et d'une connexion PostgreSQL.
9. Mesurer latences P50/P95/P99 et debit soutenu.
10. Definir capacite, limites et dimensionnement.

### Criteres d'acceptation

- aucune interruption visible pendant un rolling upgrade conforme ;
- la perte d'un PDP ne bloque pas le service ;
- les objectifs de performance sont reproduits en CI de performance ;
- le chart peut etre installe par un client a partir de la documentation seule ;
- les configurations supportees font l'objet d'une matrice publiee.

---

## Lot G - Observabilite, SIEM et exploitation

**Priorite** : P1  
**Objectif** : permettre aux equipes clientes d'operer Autho sans connaissance interne.

### Travaux

1. Completer les spans decision, enrichissement PIP, cache, policy load, ReBAC et audit append.
2. Standardiser correlation ID, tenant ID, policy version et decision ID.
3. Publier dashboards Grafana de reference.
4. Definir alertes et runbooks associes.
5. Ajouter webhooks signes pour evenements critiques.
6. Ajouter exports syslog, Elastic et Splunk.
7. Mesurer retard d'audit et fraicheur des politiques.
8. Tester la stack Docker Compose Kafka/LDAP de bout en bout en CI.
9. Automatiser les exercices backup/restore et upgrade/rollback.
10. Definir SLI, SLO et SLA par edition.

### Criteres d'acceptation

- chaque alerte publiee renvoie a un runbook teste ;
- une decision est tracable de l'appel a la preuve sans journaliser de secret ;
- la CI valide periodiquement Kafka, LDAP, PostgreSQL et restauration ;
- les clients peuvent exporter leurs evenements sans acces au filesystem Autho.

---

## Lot H - Experience developpeur et SDK

**Priorite** : P1/P2  
**Objectif** : obtenir une premiere decision utile en moins de quinze minutes.

### Travaux

1. Fournir un onboarding guide par cas d'usage.
2. Creer un quickstart Docker unique et reproductible.
3. Publier une specification OpenAPI complete et verifiee en CI.
4. Generer ou maintenir des SDK officiels :
   - Java ;
   - TypeScript/JavaScript ;
   - Python ;
   - Go ;
   - Clojure.
5. Ajouter retries bornes, timeouts, circuit breaker et propagation de trace aux SDK.
6. Fournir middleware/PEP pour frameworks courants.
7. Ajouter exemples GitHub Actions et GitOps.
8. Publier une politique de versioning et deprecation.
9. Fournir sandbox, politiques exemples et donnees de demonstration.
10. Mesurer le temps jusqu'a la premiere decision.

### Criteres d'acceptation

- le quickstart fonctionne sur une machine propre ;
- tous les SDK passent les memes tests de contrat ;
- les snippets de documentation sont executes en CI ;
- une incompatibilite API genere une alerte avant release.

---

## Lot I - Securite, supply chain et conformite

**Priorite** : P0 continu  
**Objectif** : satisfaire les controles de securite et d'achat des entreprises.

### Travaux

1. Maintenir un threat model par plane.
2. Ajouter SAST, dependency scanning, secret scanning et container scanning.
3. Generer SBOM CycloneDX ou SPDX a chaque release.
4. Signer artefacts, images, bundles et manifestes de release.
5. Mettre en place une politique de correction des vulnerabilites.
6. Realiser un pentest independant avant GA Enterprise.
7. Tester rotation de tous les secrets sans perte d'integrite.
8. Implementer politiques de retention, legal hold et purge.
9. Produire DPA, modele de responsabilite partagee et questionnaire securite.
10. Construire la trajectoire SOC 2/ISO 27001 et poursuivre la pre-evaluation CSPN si commercialement justifiee.

### Criteres d'acceptation

- aucune vulnerabilite critique connue non acceptee avant release ;
- chaque artefact est traçable a un commit et un build ;
- les findings de pentest critiques et hauts sont corriges ou formellement acceptes ;
- la retention et la purge sont testees par tenant ;
- les preuves de controle peuvent etre rassemblees sans intervention sur le code.

---

## Lot J - Gouvernance et compliance produit

**Priorite** : P2  
**Objectif** : faire acheter Autho par les equipes securite et conformite, pas seulement par les developpeurs.

### Travaux

1. Completer le workflow draft, review, approve, deploy et rollback.
2. Ajouter ownership et separation des responsabilites.
3. Ajouter demandes et approbations d'acces.
4. Implementer acces temporaire/JIT avec expiration automatique.
5. Implementer break-glass avec justification et alerte.
6. Ajouter campagnes de recertification.
7. Produire rapports d'acces par utilisateur, ressource et organisation.
8. Ajouter export CSV/PDF signe des preuves.
9. Detecter acces inhabituels et changements de politique a risque.
10. Ajouter comparaison temporelle T1/T2 et what-if historique.

### Criteres d'acceptation

- aucune personne ne peut approuver sa propre mutation critique ;
- un acces temporaire expire sans action manuelle ;
- un break-glass est visible en temps reel et dans l'audit ;
- une campagne de recertification produit une preuve exportable ;
- les rapports indiquent leur source, leur periode et leur version de politique.

---

## Lot K - Portail client et experience commerciale

**Priorite** : P1  
**Objectif** : permettre l'essai, l'achat et le support sans operation manuelle systematique.

### Travaux

1. Construire inscription, verification d'adresse et creation d'organisation.
2. Ajouter assistant de premier projet et premier environnement.
3. Afficher checklist d'onboarding et etat des integrations.
4. Ajouter page usage, edition, factures et moyens de paiement.
5. Ajouter upgrade, downgrade et annulation.
6. Integrer documentation contextuelle et diagnostic partageable.
7. Ajouter gestion des tickets et statut de service.
8. Implementer consentements et preferences de communication.
9. Ajouter suppression et export de compte.
10. Instrumenter le funnel d'activation sans collecter de payload sensible.

### Criteres d'acceptation

- un utilisateur Free obtient une premiere decision sans intervention humaine ;
- le passage Free vers Pro conserve projets et politiques ;
- l'annulation et l'export sont accessibles depuis le portail ;
- le support peut diagnostiquer avec un bundle expurge et consenti.

---

## 7. Plan d'execution par horizon

## Jalon 0 - Deux semaines : alignement et mesure

### Livrables

- designer un responsable produit et un responsable technique ;
- adopter ce document comme backlog directeur ;
- requalifier `ROADMAP.md` et les plans historiques ;
- inventorier les schemas et chemins non tenant-aware ;
- etablir les benchmarks de cout et de performance ;
- definir les hypotheses de quotas Free et Pro ;
- rendre obligatoires backend tests, UI tests, build et scans en CI ;
- ouvrir un registre des risques et decisions d'architecture.

### Gate

- backlog chiffre et assigne ;
- aucune roadmap concurrente presentee comme source de verite ;
- baseline technique reproductible.

## Jalon 1 - 30 jours : pilote commercial maitrisable

### Livrables

- modele organization/project/environment/tenant valide ;
- schema PostgreSQL initial et migrations ;
- preuve de partitionnement des politiques, relations et audit ;
- registre multi-cle API minimal ;
- matrice Free/Pro/Enterprise publique ;
- compteur de decisions en mode observation ;
- OpenAPI et quickstart verifies ;
- CI end-to-end Docker Compose planifiee ou partiellement active.

### Go/no-go

- **Go** : demonstration, design partners et pilotes controles ;
- **No-go** : hebergement de plusieurs clients non fiables dans le meme store tant que l'isolation n'est pas prouvee.

## Jalon 2 - 60 jours : beta freemium

### Livrables

- isolation tenant sur tous les stores P0 ;
- PostgreSQL supporte en beta ;
- metering durable et consultation de consommation ;
- quotas en soft limit avec alertes ;
- creation/revocation/rotation des cles API ;
- onboarding Free autonome ;
- OIDC/JWKS beta ;
- CI Kafka/LDAP/PostgreSQL de bout en bout ;
- SDK Java, TypeScript et Python en beta.

### Go/no-go

- **Go** : beta Free invitee et Pro design partners ;
- **No-go** : promesse HA/SLA ou Enterprise GA.

## Jalon 3 - 90 jours : offre Pro commercialisable

### Livrables

- paiement et lifecycle d'abonnement ;
- enforcement des quotas avec periode de grace ;
- portail usage/facturation ;
- environnements dev/staging/prod ;
- OIDC stable ;
- webhooks signes ;
- dashboards et alertes de reference ;
- backup/restore automatiquement verifies ;
- documentation support et deprecation ;
- demo commerciale reproductible et release gate complet.

### Go/no-go

- **Go** : vente Pro avec support standard et limites publiees ;
- **No-go** : Enterprise GA tant que HA, SAML/SCIM, stockage distribue et pentest ne sont pas termines.

## Jalon 4 - Six mois : Enterprise preview

### Livrables

- separation fonctionnelle control/data/evidence planes ;
- distribution multi-instance des bundles ;
- activation atomique, canary et rollback coordonne ;
- Helm chart preview ;
- relation store partage ;
- SAML et SCIM beta ;
- SIEM Elastic/Splunk ;
- tests de charge et de chaos ;
- mode de licence Enterprise offline ;
- premier pentest externe.

### Go/no-go

- **Go** : previews Enterprise contractuellement encadrees ;
- **No-go** : SLA general tant que les SLO ne sont pas atteints sur plusieurs cycles.

## Jalon 5 - Neuf mois : Enterprise release candidate

### Livrables

- architecture HA supportee ;
- rolling upgrades verifies ;
- SSO/SCIM stables ;
- rotation des secrets et cles sans interruption ;
- retention et legal hold ;
- support SIEM et webhooks complet ;
- SBOM, signature et provenance sur chaque artefact ;
- documentation de dimensionnement et matrice de compatibilite ;
- correction des findings critiques/hauts du pentest.

### Go/no-go

- **Go** : release candidate chez plusieurs clients representatifs ;
- **No-go** : GA si restauration, upgrade ou isolation tenant echoue lors d'un exercice.

## Jalon 6 - Douze mois : Enterprise GA

### Livrables

- SLA et politique de support publies ;
- installation Kubernetes et self-hosted certifiee ;
- offre managee industrialisee si retenue ;
- workflows de gouvernance prioritaires ;
- campagnes de recertification beta ou stable ;
- rapports de conformite exportables ;
- SDK Java, TypeScript, Python et Go stables ;
- benchmarks publics reproductibles ;
- processus de release et securite auditable.

### Gate GA

- zero fuite inter-tenant connue ;
- zero vulnerabilite critique ouverte ;
- SLO atteints pendant au moins 30 jours sur les environnements pilotes ;
- backup et restore reussis sur une version de production ;
- upgrade et rollback reussis sans perte de donnees ;
- pentest externe accepte ;
- documentation, support, pricing et contrats disponibles ;
- au moins trois clients pilotes ont valide le parcours cible.

---

## 8. Backlog priorise

### P0 - Bloque la beta commerciale

1. Isolation tenant de bout en bout.
2. PostgreSQL et migrations.
3. Registre multi-cle API et stockage de secrets securise.
4. Metering durable et non contournable.
5. CI end-to-end des dependances reelles.
6. Gates de securite et de release.
7. Matrice d'edition et contrat de quotas.

### P1 - Bloque Pro ou Enterprise preview

1. Organisations, projets et environnements.
2. OIDC/JWKS et roles administratifs.
3. Portail client et facturation.
4. Distribution multi-instance des bundles.
5. Helm, HA, tests de panne et observabilite.
6. SIEM, webhooks et retention.
7. SDK officiels et onboarding autonome.

### P2 - Differenciation Enterprise

1. SAML et SCIM.
2. Separation complete des planes.
3. Relation store distribue.
4. Workflow GRC, JIT et break-glass.
5. Recertification et rapports de conformite.
6. Time-travel T1/T2 et detection d'anomalies.

### P3 - Extension de marche

1. Offre managee multi-region.
2. Private networking et private link.
3. Marketplace cloud.
4. Connecteurs et PEP sectoriels.
5. Aide a la conception de politiques et recommandations.

---

## 9. Dependances critiques

```text
Modele tenant
    +--> PostgreSQL/migrations
    |       +--> control plane
    |       +--> metering durable
    |       +--> relation store partage
    |
    +--> organisations/identites
            +--> OIDC/SAML/SCIM
            +--> cles API
            +--> portail/billing

Bundles signes existants
    +--> distribution multi-instance
            +--> activation atomique
                    +--> HA/Helm/SLA

Audit/evidence existants
    +--> retention tenant-aware
            +--> SIEM/export
                    +--> gouvernance/compliance
```

Le multi-tenant et PostgreSQL doivent preceder le SaaS public. La separation des planes doit preceder toute promesse forte de haute disponibilite. Les workflows GRC peuvent avancer en parallele, mais ne doivent pas retarder le socle d'exploitation.

---

## 10. Strategie de test

### 10.1 Niveaux obligatoires

- tests unitaires du domaine ;
- tests de contrat API `v1` ;
- tests de coherence inter-endpoints ;
- tests d'isolation tenant ;
- tests d'integration PostgreSQL/Kafka/LDAP/RocksDB ;
- tests de compatibilite des SDK ;
- tests de migration et rollback ;
- tests de backup/restore ;
- tests de charge et endurance ;
- tests de chaos et de partition ;
- tests de securite et fuzzing des politiques ;
- tests end-to-end du parcours inscription-paiement-premiere decision.

### 10.2 Gate de pull request

- tests backend ;
- tests et lint UI ;
- verification OpenAPI ;
- migrations testees ;
- scans secrets/dependances/SAST ;
- aucune baisse non approuvee de couverture critique ;
- revue obligatoire des changements de tenant, auth, licence et decision.

### 10.3 Gate de release

- `./scripts/check-release.sh` vert ;
- image construite et signee ;
- SBOM publiee ;
- migrations et rollback verifies ;
- smoke test d'une installation propre ;
- scenario commercial execute ;
- changelog et notes d'upgrade ;
- vulnerabilites analysees ;
- artefacts de documentation alignes sur les fonctions livrees.

---

## 11. SLO et objectifs techniques

Les valeurs definitives seront fixees apres benchmark. Les objectifs initiaux proposes sont :

| Indicateur | Free/Pro cible | Enterprise cible |
|---|---:|---:|
| Disponibilite mensuelle | best effort / 99,9 % Pro manage | 99,95 % ou contrat |
| Latence PDP P95 hors PIP distant | < 10 ms | < 5 ms selon profil |
| Latence PDP P99 hors PIP distant | < 25 ms | < 15 ms |
| Fraicheur policy apres rollout | < 60 s | < 10 s configurable |
| RPO control plane | 24 h Free, inferieur en Pro | contractuel, cible <= 5 min |
| RTO | documente par offre | contractuel |
| Verification audit | quotidienne ou a la demande | continue/planifiee |

Les benchmarks doivent indiquer materiel, JVM, taille des politiques, taux de cache, distribution des operations et presence des PIP.

---

## 12. KPI produit et commerciaux

### Activation

- temps median jusqu'a la premiere decision ;
- pourcentage d'inscriptions obtenant une decision en 24 heures ;
- pourcentage creant une premiere politique ;
- pourcentage utilisant explain ou simulate ;
- taux de succes du quickstart.

### Engagement

- organisations actives hebdomadaires ;
- decisions par organisation ;
- politiques actives par environnement ;
- analyses d'impact executees avant rollout ;
- utilisateurs administratifs actifs.

### Conversion

- Free vers essai Pro ;
- essai Pro vers abonnement ;
- raisons de conversion et d'abandon ;
- revenu recurrent et expansion ;
- cout d'infrastructure par million de decisions.

### Fiabilite

- disponibilite et latence par edition ;
- taux d'erreur et timeout PIP ;
- retard de distribution des politiques ;
- incidents d'isolation ou de coherence ;
- reussite des exercices de restauration.

### Securite et gouvernance

- pourcentage de rollouts precedes d'une analyse d'impact ;
- changements bloques par policy safety ;
- cles expirees ou inutilisees ;
- temps de revocation ;
- findings de securite ouverts par severite.

---

## 13. Risques et mitigations

| Risque | Impact | Mitigation |
|---|---|---|
| Construire trop tot une offre SaaS sur H2 | fuite ou perte de donnees | bloquer le multi-client avant PostgreSQL et tests tenant |
| Free trop limite | faible adoption | conserver explain/simulate et audit court avec quotas |
| Free trop genereux | cout sans conversion | mesurer le cout par million de decisions et ajuster les volumes |
| Metering dans le chemin critique | latence ou indisponibilite | collecte asynchrone durable et grace en cas de panne |
| Couplage control/data plane | incident global | bundles locaux verifies et fonctionnement degrade documente |
| Promesse ReBAC excessive | attentes non tenues | publier limites, profondeur et semantique supportees |
| Dette des endpoints historiques | cout de maintenance | politique de deprecation et tests de contrat v1 |
| SSO/SCIM sous-estime | retard Enterprise | utiliser standards et bibliotheques auditees, limiter les variantes initiales |
| Roadmaps contradictoires | mauvaise priorisation | ce plan devient la source de verite, les anciens documents sont archives ou marques historiques |
| Compliance avant product-market fit | dispersion | sequencer controles essentiels puis certifications selon demandes clients |

---

## 14. Organisation recommandee

### Flux de travail

1. **Core authorization** : decision, policy safety, ReBAC, performance.
2. **Platform** : tenant, PostgreSQL, planes, Kafka, Kubernetes.
3. **Identity and security** : OIDC, cles, SAML/SCIM, supply chain.
4. **Product experience** : console, onboarding, SDK et documentation.
5. **Commercial operations** : licences, metering, billing, support.

Avec une equipe reduite, l'ordre strict est : tenant/storage, identite/cles, metering, onboarding, HA, puis gouvernance avancee.

### Rituels

- revue hebdomadaire des risques P0 ;
- demonstration produit toutes les deux semaines ;
- exercice mensuel de release et restauration ;
- revue trimestrielle packaging/pricing ;
- mise a jour de ce document a chaque jalon.

---

## 15. Definition of Done produit

Une fonctionnalite commercialisable est terminee lorsque :

1. le code et les migrations sont livres ;
2. les tests unitaires, integration et end-to-end sont verts ;
3. l'isolation tenant et les permissions ont ete verifiees ;
4. metriques, traces, logs et alertes sont disponibles ;
5. backup, restore et rollback sont documentes si la fonction persiste des donnees ;
6. API, UI et SDK sont coherents ;
7. documentation utilisateur et operateur est publiee ;
8. edition, quota et comportement de depassement sont definis ;
9. support et limitations connues sont explicites ;
10. la release checklist a ete executee.

---

## 16. Prochaines actions immediates

1. Valider le choix open-core self-hosted plus offre managee, ou self-hosted uniquement pour la premiere phase.
2. Designer ce document comme source de verite de la roadmap.
3. Creer les epics correspondant aux lots A a K.
4. Inventorier chaque table, atom, cache et topic devant etre tenant-aware.
5. Rediger l'ADR du modele organization/project/environment/tenant.
6. Rediger l'ADR PostgreSQL et migrations.
7. Prototyper le registre multi-cle API.
8. Instrumenter le compteur de decisions sans enforcement.
9. Mesurer cout et performance pour fixer les quotas de lancement.
10. Mettre a jour la matrice de fonctionnalites et la demo commerciale.

Le premier increment a livrer doit etre un chemin vertical complet : creation d'une organisation, creation d'un projet, emission d'une cle API, decision tenant-aware, comptage, affichage de consommation et preuve d'audit. Ce chemin valide simultanement le modele produit, l'isolation, l'identite et le socle freemium.
