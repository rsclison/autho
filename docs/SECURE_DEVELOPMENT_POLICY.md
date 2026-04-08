# Politique de Développement Sécurisé — Autho Authorization Server

**Version** : 1.0
**Classification** : Document de sécurité — usage interne et évaluation CSPN
**Date** : 2026-03-25
**Responsable** : Mainteneur principal du projet Autho

---

## 1. Objectif

Ce document définit les règles et procédures applicables au cycle de développement sécurisé (SDLC — Secure Development Lifecycle) d'Autho. Il constitue un document requis dans le cadre d'une évaluation CSPN (Certification de Sécurité de Premier Niveau) par l'ANSSI.

---

## 2. Périmètre

Cette politique s'applique à :
- Tout le code source du dépôt `autho` (branches `main` et `develop`)
- Les dépendances déclarées dans `project.clj`
- Les fichiers de configuration et scripts de déploiement
- Les ressources embarquées (`resources/`)

---

## 3. Principes directeurs

1. **Défense en profondeur** : chaque couche de l'application applique ses propres contrôles de sécurité indépendamment des couches adjacentes.
2. **Moindre privilège** : le code n'acquiert que les droits strictement nécessaires à sa fonction.
3. **Fail secure** : en cas d'erreur ou de condition imprévue, le serveur refuse l'accès plutôt que de l'accorder.
4. **Séparation des responsabilités** : PDP, PRP, PIP et audit sont des composants distincts avec des interfaces bien définies.

---

## 4. Gestion du code source

### 4.1 Branches et revues

| Branche | Protection | Revue requise |
|---|---|---|
| `main` | Protégée, pas de push direct | 1 review approuvée minimum |
| `develop` | Semi-protégée | Recommandé |
| `feature/*` | Libre | Auto-revue avant PR |

**Règle** : toute modification touchant `src/autho/auth.clj`, `src/autho/audit.clj`, `src/autho/handler.clj`, ou `src/autho/validation.clj` (fichiers de sécurité critiques) exige une revue explicite orientée sécurité.

### 4.2 Messages de commit

Format conventionnel : `type(scope): description`

Types : `feat`, `fix`, `chore`, `docs`, `test`, `refactor`, `security`

Le type `security` est réservé aux corrections de vulnérabilités. Les commits `security` doivent référencer l'identifiant CVE ou le rapport interne.

---

## 5. Revue de sécurité du code

### 5.1 Checklist de revue (Pull Request)

Toute PR doit valider les points suivants avant merge :

**Entrées utilisateur**
- [ ] Toutes les entrées sont validées avant traitement (`validation.clj`)
- [ ] Les champs texte ont des limites de taille définies
- [ ] Pas de construction dynamique de requêtes/commandes à partir d'entrées non filtrées

**Authentification et autorisation**
- [ ] Les nouvelles routes sont classifiées publiques ou protégées explicitement
- [ ] Les routes admin utilisent `wrap-admin-auth`
- [ ] Aucun secret n'est loggué (même en mode DEBUG)

**Cryptographie**
- [ ] Aucun algorithme non approuvé n'est introduit (MD5, SHA-1, DES, RC4 interdits)
- [ ] Les secrets ne sont pas codés en dur dans le code source
- [ ] Les comparaisons de secrets utilisent des fonctions en temps constant

**Journalisation**
- [ ] Les événements de sécurité significatifs sont tracés via `audit.clj`
- [ ] Les logs ne contiennent pas de données personnelles non pseudonymisées
- [ ] Les logs ne contiennent pas de tokens ou mots de passe en clair

**Dépendances**
- [ ] Toute nouvelle dépendance a été vérifiée dans la NVD (nvd.nist.gov)
- [ ] La licence est compatible avec EPL-2.0

### 5.2 Revue spécifique sécurité

Pour les PR impactant les fichiers de sécurité critiques, la revue doit explicitement couvrir :
- Modèle de menace : quelle nouvelle surface d'attaque est introduite ?
- Régression de sécurité : aucun contrôle existant n'est affaibli ou contourné ?
- Tests de sécurité : les cas limites (valeurs nulles, valeurs extrêmes, entrées malformées) sont testés ?

---

## 6. Gestion des dépendances

### 6.1 Principe d'ajout de dépendance

Avant d'ajouter une nouvelle dépendance dans `project.clj` :
1. Vérifier l'existence de vulnérabilités connues sur [nvd.nist.gov](https://nvd.nist.gov) et [OSV](https://osv.dev)
2. Évaluer l'activité du projet (dernière release, nombre de mainteneurs)
3. Vérifier la compatibilité de licence
4. Préférer les bibliothèques Java/JVM standard plutôt que les alternatives moins connues pour les fonctions cryptographiques

### 6.2 Mise à jour des dépendances

- **Patch** (x.y.Z) : applicable sans revue approfondie si les tests passent
- **Minor** (x.Y.z) : revue du changelog pour identifier les changements de comportement sécurité
- **Major** (X.y.z) : revue complète, tests d'intégration obligatoires

**Fréquence** : vérification mensuelle des CVE sur les dépendances déclarées.

### 6.3 SBOM (Software Bill of Materials)

Générer un SBOM à chaque release :

```bash
# Via leiningen (avec plugin cyclonedx)
./lein cyclonedx

# Ou via syft (outil externe)
syft packages dir:. -o cyclonedx-json > sbom.json
```

Le SBOM doit être archivé avec chaque release et transmis à l'évaluateur CSPN.

---

## 7. Tests de sécurité

### 7.1 Tests unitaires de sécurité (obligatoires)

Les tests unitaires couvrent les comportements de sécurité suivants :

| Comportement | Fichier de test | Couverture attendue |
|---|---|---|
| Rejet des requêtes sans auth | `handler_test.clj` | Routes protégées → 401 |
| Rejet body vide | `handler_test.clj` | POST sans body → 400 |
| Validation des entrées | `validation_test.clj` | Injection SQL/XSS/commandes |
| Chaîne d'audit | `audit_test.clj` | 14 tests : HMAC correct, détection altération entrée simple/milieu, chaînage multi-entrées, pagination |
| Comparaison temps-constant | `auth_test.clj` | API key valide/invalide |

**Règle** : aucun merge vers `main` si les tests de sécurité échouent.

### 7.2 Analyse statique (SAST)

Outils recommandés à intégrer en CI :

```bash
# clj-kondo — linter Clojure (détecte les anti-patterns)
clj-kondo --lint src/

# kibit — suggestions de code idiomatique (inclut des patterns sécurité)
./lein kibit

# eastwood — linter avancé avec détection de bugs
./lein eastwood
```

### 7.3 Analyse des dépendances (SCA)

```bash
# nvd-clojure — scan CVE des dépendances Maven/Lein
./lein nvd check

# ou via OWASP Dependency-Check
dependency-check --project autho --scan . --format JSON
```

### 7.4 Tests de pénétration

Avant toute release majeure (et obligatoirement avant soumission CSPN) :
- Test de fuzzing sur les endpoints `/isAuthorized`, `/whoAuthorized`, `/whatAuthorized`
- Test de bypass d'authentification (headers manquants, malformés, JWT expirés, `alg: none`)
- Test de validation des politiques XACML (policies EDN malformées)
- Test de déni de service (rate limiting, payload surdimensionné)

---

## 8. Gestion des vulnérabilités

### 8.1 Politique de divulgation (Responsible Disclosure)

Les vulnérabilités doivent être signalées **en privé** avant toute divulgation publique :

- **Canal** : issue GitHub privée (Security Advisory) ou email au mainteneur principal
- **Délai de réponse** : accusé de réception sous 72h
- **Délai de correction** : 30 jours pour les vulnérabilités critiques/hautes, 90 jours pour les autres
- **Divulgation coordonnée** : publication conjointe après correction disponible

### 8.2 Classification des vulnérabilités

| Sévérité | Score CVSS | Délai de correction | Notification |
|---|---|---|---|
| Critique | ≥ 9.0 | 15 jours | ANSSI + utilisateurs immédiats |
| Haute | 7.0–8.9 | 30 jours | Utilisateurs via release note |
| Moyenne | 4.0–6.9 | 90 jours | Release note prochaine version |
| Faible | < 4.0 | Prochaine release | Changelog |

### 8.3 Traçabilité

Chaque vulnérabilité corrigée doit :
1. Avoir un identifiant interne (VUL-AAAA-NNN)
2. Être référencée dans le commit de correction
3. Être documentée dans `CHANGELOG.md` (sévérité, impact, version corrigée)
4. Donner lieu à un CVE si la sévérité ≥ Haute

---

## 9. Gestion des releases

### 9.1 Processus de release

1. **Gel de code** : aucune nouvelle fonctionnalité, correctifs sécurité uniquement
2. **Tests complets** : `./lein test :all`
3. **Scan dépendances** : `./lein nvd check` — zéro CVE critique/haute
4. **Revue du SBOM** : générer et archiver
5. **Mise à jour `CHANGELOG.md`**
6. **Tag Git signé** : `git tag -s v0.x.y -m "Release v0.x.y"`
7. **Build de l'uberjar** : `./lein uberjar`
8. **Vérification du hash** : publier les checksums SHA-256 de l'uberjar

### 9.2 Vérification d'intégrité de la release

```bash
# Générer le hash de l'uberjar
sha256sum target/autho-0.x.y-standalone.jar > target/autho-0.x.y-standalone.jar.sha256

# Vérifier côté utilisateur
sha256sum --check autho-0.x.y-standalone.jar.sha256
```

---

## 10. Environnements

| Environnement | Secrets | TLS | Rate limiting | Logs |
|---|---|---|---|---|
| Développement | Générés localement (≥ 32 chars) | Facultatif | Facultatif | DEBUG |
| Test/CI | Générés par le pipeline, non partagés | Recommandé | Activé | INFO |
| Staging | Secrets distincts de la prod | Obligatoire | Activé | INFO |
| Production | Vault ou équivalent, rotation périodique | Obligatoire | Activé | WARN |

**Règle absolue** : les secrets de production ne transitent jamais dans le dépôt Git, les logs, ni les canaux de communication non chiffrés.

---

## 11. Formation et sensibilisation

Les contributeurs au projet doivent avoir connaissance de :
- L'[OWASP Top 10](https://owasp.org/www-project-top-ten/) (injection, broken auth, etc.)
- Les [recommandations cryptographiques de l'ANSSI](https://cyber.gouv.fr/les-mecanismes-cryptographiques)
- Le modèle de menace d'Autho (cf. `docs/SECURITY_TARGET.md`)

---

*Document établi dans le cadre de la démarche de qualification CSPN d'Autho.*
*Révision annuelle ou après tout incident de sécurité significatif.*
