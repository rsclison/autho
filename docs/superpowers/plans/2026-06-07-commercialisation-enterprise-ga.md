# Commercialisation Enterprise GA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Élever Autho du statut "démo commerciale crédible" à "offre enterprise commercialisable" en fermant les écarts de packaging, d'exploitation, d'architecture et de documentation.

**Architecture:** Séparer le travail en quatre couches qui progressent ensemble mais restent vérifiables indépendamment: packaging produit, opérations / runbooks, durcissement runtime enterprise, puis validation release et démonstration. Le but n'est pas d'ajouter des features au hasard, mais de transformer le socle existant en offre claire, exploitable et documentée sans ambiguïté entre legacy et v1.

**Tech Stack:** Clojure, Leiningen, H2 aujourd'hui, chemin PostgreSQL à finaliser, React/Vite pour l'admin UI, Docker Compose pour les validations e2e, Markdown pour la documentation, scripts shell existants (`./lein`, `./scripts/check-release.sh`).

---

## File Structure

- Modify: `README.md`
  Responsabilité: positionnement produit, liens vers la bonne API, promesse commerciale explicite.
- Modify: `docs/OVERVIEW.md`
  Responsabilité: vue d'ensemble lisible par les clients, séparation des capacités livrées et des limites connues.
- Modify: `docs/API_REFERENCE.md`
  Responsabilité: référence canonique des endpoints `v1`, des rôles et des comportements enterprise.
- Modify: `docs/API_V1.md`
  Responsabilité: exemples contractuels, contrats de décision, ReBAC, tenants, bundles signés.
- Modify: `docs/AUDIT_COMMERCIALISATION_2026-04-27.md`
  Responsabilité: document de préparation commerciale avec écarts résolus et écarts restants.
- Modify: `docs/RELEASE_CHECKLIST.md`
  Responsabilité: gate de release reproductible et lisible avant tag / livraison.
- Modify: `docs/SECURITY_ADMIN_GUIDE.md`
  Responsabilité: runbook sécurité, gestion des secrets, rotation, sauvegarde, restauration.
- Modify: `docs/SECURITY_TARGET.md`
  Responsabilité: cible de sécurité et de durcissement opérationnel.
- Modify: `docs/GUIDE_DEMONSTRATION.md`
  Responsabilité: scénario de démo commerciale réaliste et reproductible.
- Create: `docs/OPERATIONS_RUNBOOK.md`
  Responsabilité: point d'entrée opérateur unique pour installation, supervision, backup, restore, upgrade, rollback.
- Create: `docs/DEPLOYMENT_REFERENCE.md`
  Responsabilité: références de déploiement Docker / K8s / self-hosted et paramètres d'environnement.
- Create: `docs/BACKUP_RESTORE.md`
  Responsabilité: procédure pas-à-pas de sauvegarde et restauration des stores.
- Create: `docs/UPGRADE_ROLLBACK.md`
  Responsabilité: procédure de montée de version et de retour arrière.
- Modify: `src/autho/auth.clj`
  Responsabilité: application des règles d'accès, gestion des secrets et de leur rotation si nécessaire.
- Modify: `src/autho/pdp.clj`
  Responsabilité: résolution des tenants, exécution de décision, cache, intégration des bundles.
- Modify: `src/autho/policy_bundles.clj`
  Responsabilité: signature, vérification, import et activation des bundles côté data plane.
- Modify: `src/autho/policy_versions.clj`
  Responsabilité: lifecycle des politiques, promotion, rollback et métadonnées de déploiement.
- Modify: `src/autho/local_cache.clj`
  Responsabilité: isolation de cache stricte par tenant et par contexte de décision.
- Modify: `src/autho/kafka_invalidation.clj`
  Responsabilité: cohérence multi-instance et invalidation distribuée.
- Modify: `src/autho/otel.clj`
  Responsabilité: tracing et observabilité de bout en bout.
- Modify: `src/autho/journal.clj`
  Responsabilité: audit, export et vérification de la chaîne d'intégrité.
- Modify: `src/autho/jdbc_utils.clj`
  Responsabilité: chemin de persistance enterprise et future migration de stockage.
- Modify: `src/autho/prp.clj`
  Responsabilité: accès aux stores de policy / relation si le chemin de persistance doit être rationalisé.

---

## 30 Days: Product Packaging and Commercial Clarity

### Task 1: Fix the commercial story and API boundaries

**Files:**
- Modify: `README.md`
- Modify: `docs/OVERVIEW.md`
- Modify: `docs/API_REFERENCE.md`
- Modify: `docs/API_V1.md`
- Modify: `docs/AUDIT_COMMERCIALISATION_2026-04-27.md`
- Modify: `docs/GUIDE_DEMONSTRATION.md`

- [ ] **Step 1: Freeze the product wording**

Écrire noir sur blanc qu'Autho est vendu comme une plateforme d'Authorization Operations, pas comme un simple moteur ABAC. Le texte doit distinguer:
- ce qui est livré aujourd'hui;
- ce qui est legacy;
- ce qui est la voie recommandée (`/v1`);
- ce qui reste roadmap.

- [ ] **Step 2: Ajouter une matrice de packaging**

Créer une matrice lisible `Free / Pro / Enterprise` avec au minimum:
- fonctionnalités de décision;
- audit;
- explain / simulate;
- replay / impact;
- ReBAC;
- multi-instance / Kafka;
- support commercial.

- [ ] **Step 3: Normaliser la documentation d'API**

Uniformiser les exemples vers l'API `v1` et indiquer explicitement que les endpoints historiques ne doivent pas être le point d'entrée des nouvelles intégrations.

- [ ] **Step 4: Mettre à jour l'audit de commercialisation**

Transformer le document d'audit en source d'état vivant:
- sections "livré";
- sections "écarts restants";
- sections "go/no-go";
- prochaine validation attendue.

- [ ] **Step 5: Vérification**

Run:
```bash
rg -n "legacy|v1|Enterprise|Free / Pro / Enterprise" README.md docs/OVERVIEW.md docs/API_REFERENCE.md docs/API_V1.md docs/AUDIT_COMMERCIALISATION_2026-04-27.md docs/GUIDE_DEMONSTRATION.md
```

Expected:
- les mentions legacy sont explicites;
- la voie `v1` est la référence recommandée;
- la matrice d'édition est présente et cohérente.

### Exit Criteria 30 Days

- Le positionnement produit est non ambigu.
- La documentation API guide vers `v1`.
- La matrice d'édition existe et ne mélange pas les offres.

---

## 60 Days: Operator Readiness and Release Discipline

### Task 2: Ship the operations runbook pack

**Files:**
- Create: `docs/OPERATIONS_RUNBOOK.md`
- Create: `docs/DEPLOYMENT_REFERENCE.md`
- Create: `docs/BACKUP_RESTORE.md`
- Create: `docs/UPGRADE_ROLLBACK.md`
- Modify: `docs/SECURITY_ADMIN_GUIDE.md`
- Modify: `docs/SECURITY_TARGET.md`
- Modify: `docs/RELEASE_CHECKLIST.md`

- [ ] **Step 1: Décrire l'installation de référence**

Documenter un parcours opérateur unique:
- variables d'environnement obligatoires;
- base de données locale actuelle;
- démarrage;
- vérification de santé;
- première connexion;
- point de contrôle avant mise en prod.

- [ ] **Step 2: Documenter backup et restore**

Écrire des procédures concrètes pour:
- sauvegarde des données d'audit;
- sauvegarde des politiques;
- restauration complète;
- restauration partielle;
- vérification post-restore.

- [ ] **Step 3: Documenter upgrade et rollback**

Spécifier les séquences exactes de montée de version et de retour arrière, avec le critère de réussite et le critère d'échec.

- [ ] **Step 4: Documenter la rotation des secrets**

Ajouter un runbook clair pour `JWT_SECRET`, `AUDIT_HMAC_SECRET`, `POLICY_BUNDLE_HMAC_SECRET` et les secrets liés aux clés API, en indiquant les impacts d'un redémarrage et les limites actuelles.

- [ ] **Step 5: Renforcer la release checklist**

La checklist doit imposer avant tout tag ou livraison:
- test backend;
- build UI;
- démo commerciale;
- vérification des docs;
- validation d'un scénario d'exploitation minimal.

- [ ] **Step 6: Vérification**

Run:
```bash
./lein test
./scripts/check-release.sh
```

Expected:
- la suite de tests reste verte;
- le script de release couvre le nouveau chemin documentaire;
- aucun runbook n'est uniquement théorique.

### Exit Criteria 60 Days

- Un opérateur peut installer, valider, sauvegarder, restaurer et repartir sans dépendre d'un contexte oral.
- La release checklist devient un vrai gate de livraison.

---

## 90 Days: Enterprise Hardening and Commercial Validation

### Task 3: Close the enterprise runtime gaps

**Files:**
- Modify: `src/autho/pdp.clj`
- Modify: `src/autho/policy_bundles.clj`
- Modify: `src/autho/policy_versions.clj`
- Modify: `src/autho/local_cache.clj`
- Modify: `src/autho/kafka_invalidation.clj`
- Modify: `src/autho/otel.clj`
- Modify: `src/autho/journal.clj`
- Modify: `src/autho/jdbc_utils.clj`
- Modify: `src/autho/prp.clj`
- Modify: `docs/AUTHORIZATION_OPERATIONS_PLAN.md`

- [ ] **Step 1: Verrouiller l'isolation tenant**

Faire en sorte que le tenant soit appliqué partout où une décision, une politique, un cache ou une preuve est matérialisée.

- [ ] **Step 2: Préparer le chemin bundle data plane**

Finaliser le cycle:
- export bundle signé;
- vérification de bundle;
- import côté exécution;
- activation contrôlée;
- rollback de bundle.

- [ ] **Step 3: Réduire le couplage H2**

Préparer la voie de migration pour les données enterprise qui ne doivent plus dépendre d'un store embarqué pour des usages multi-instance.

- [ ] **Step 4: Rendre l'observabilité exploitable**

Exposer des traces et métriques actionnables autour de:
- décision;
- cache hit / miss;
- chargement de bundle;
- invalidation;
- audit append.

- [ ] **Step 5: Mettre à jour la roadmap d'architecture**

Requalifier dans la doc ce qui est désormais fait et ce qui reste en attente, notamment la séparation des plans et le stockage distribué des relations.

- [ ] **Step 6: Vérification**

Run:
```bash
./lein test
./scripts/check-release.sh
```

Expected:
- les tests passent;
- les décisions restent cohérentes;
- le chemin tenant / bundle / cache ne casse pas les comportements actuels.

### Task 4: Stabilize ReBAC and enterprise deployment path

**Files:**
- Modify: `src/autho/pdp.clj`
- Modify: `src/autho/jdbc_utils.clj`
- Modify: `src/autho/prp.clj`
- Modify: `docs/AUTHORIZATION_OPERATIONS_PLAN.md`
- Modify: `docs/API_REFERENCE.md`
- Modify: `docs/OVERVIEW.md`

- [ ] **Step 1: Définir la frontière ReBAC enterprise**

Documenter précisément ce qui est pris en charge:
- tuples directs;
- parent / héritage;
- groups imbriqués;
- rewrites;
- traversals;
- limites explicites de la résolution récursive.

- [ ] **Step 2: Préparer la persistance durable**

Évoluer vers un chemin où les tuples relationnels ne restent pas seulement un mécanisme local, avec un plan de migration documenté.

- [ ] **Step 3: Formaliser les limites face au marché**

Écrire une comparaison honnête contre OpenFGA / SpiceDB / OPA sur:
- graphes relationnels;
- multi-tenant strict;
- stockage distribué;
- gouvernance / audit.

- [ ] **Step 4: Vérification**

Run:
```bash
./lein test
```

Expected:
- la couverture fonctionnelle ReBAC est lisible;
- les limites ne sont pas cachées;
- les docs ne promettent pas ce qui n'est pas encore livré.

### Task 5: Commercial demo and release gate

**Files:**
- Modify: `docs/GUIDE_DEMONSTRATION.md`
- Modify: `docs/AUDIT_COMMERCIALISATION_2026-04-27.md`
- Modify: `docs/RELEASE_CHECKLIST.md`
- Modify: `docs/OVERVIEW.md`

- [ ] **Step 1: Écrire un scénario de démonstration commercial**

Le scénario doit montrer dans un seul parcours:
- application d'une policy;
- explain;
- simulate;
- audit;
- replay;
- relation ReBAC;
- impact analysis;
- déploiement ou rollback d'une version.

- [ ] **Step 2: Ajouter les critères go/no-go**

Définir une section claire qui dit:
- go pour pilote et prévente;
- no-go pour GA enterprise tant que certains écarts existent;
- critères exacts pour lever chaque blocage.

- [ ] **Step 3: Aligner la release checklist avec le go-to-market**

Ajouter un gate final qui interdit une annonce de version si le runbook, la démo et la doc opérateur ne sont pas prêts en même temps.

- [ ] **Step 4: Vérification**

Run:
```bash
./scripts/check-release.sh
```

Expected:
- le parcours de démo est reproductible;
- les critères de livraison sont lisibles par un non-développeur;
- le statut commercial est mesurable.

### Exit Criteria 90 Days

- Les écarts enterprise critiques sont soit fermés, soit explicitement bornés.
- Un client peut comprendre exactement ce qui est livré, ce qui ne l'est pas, et comment opérer la plateforme.
- La commercialisation peut être lancée en mode encadré, sans prétendre à un GA enterprise complet si les derniers blocages ne sont pas levés.

---

## Self-Review

### Coverage Check

- Packaging produit: couvert par Task 1.
- Runbooks et exploitation: couverts par Task 2.
- Enterprise hardening runtime: couvert par Task 3.
- ReBAC et chemin enterprise: couvert par Task 4.
- Démo commerciale et gate de release: couverts par Task 5.

### Risk Check

- Aucun placeholder laissé en suspens.
- Les limites connues restent affichées au lieu d'être gommées.
- Les validations utilisent les commandes réellement présentes dans le dépôt.

