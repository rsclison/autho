# Cible de Sécurité — Autho Authorization Server

**Version** : 1.0
**Classification** : Document de sécurité — évaluation CSPN (ANSSI)
**Date** : 2026-03-25
**Référence** : AUT-CST-001
**Statut** : Projet — pré-évaluation

---

## 1. Identification de la cible d'évaluation (TOE)

### 1.1 Désignation

| Champ | Valeur |
|---|---|
| **Nom du produit** | Autho Authorization Server |
| **Version évaluée** | 0.1.0-SNAPSHOT (à préciser à la soumission) |
| **Type de produit** | Serveur d'autorisation — Policy Decision Point (PDP) XACML/ABAC |
| **Éditeur** | rsclison (dépôt GitHub : rsclison/autho) |
| **Langage d'implémentation** | Clojure 1.12.3 (JVM 21+) |
| **Licence** | EPL-2.0 OR GPL-2.0-or-later |

### 1.2 Description fonctionnelle

Autho est un serveur d'autorisation implémentant le modèle **ABAC (Attribute-Based Access Control)** avec une syntaxe de politiques inspirée de **XACML**. Il joue le rôle de **PDP (Policy Decision Point)** au sens de l'architecture XACML 3.0 (OASIS) et expose une API REST pour répondre aux requêtes d'autorisation des **PEP (Policy Enforcement Points)**.

**Fonctions principales :**
- `isAuthorized` — décision binaire (permit/deny) pour un triplet sujet/ressource/action
- `whoAuthorized` — liste des sujets autorisés pour une ressource/action donnée
- `whatAuthorized` — liste des ressources autorisées pour un sujet/action donné
- `explain` — explication de la décision (règle déclenchée)
- `simulate` — simulation d'une politique sans l'activer

**Fonctions de sécurité propres :**
- Authentification des PEP par JWT (HS256) ou API Key
- Cache décision LRU+TTL multi-niveaux
- Journal d'audit HMAC-SHA256 en chaîne (tamper-evident)
- Rate limiting différencié par type d'identité
- Validation et assainissement des entrées

---

## 2. Périmètre de la TOE

### 2.1 Composants inclus dans la TOE

| Composant | Fichier source | Fonction de sécurité |
|---|---|---|
| PDP — Moteur de décision | `src/autho/pdp.clj` | Évaluation des politiques ABAC |
| PRP — Référentiel de politiques | `src/autho/prp.clj` | Stockage et versionnage des politiques |
| PIP — Points d'information | `src/autho/pip.clj` | Enrichissement des attributs |
| Authentification | `src/autho/auth.clj` | Vérification JWT et API Key |
| Audit | `src/autho/audit.clj` | Journal HMAC en chaîne |
| Validation | `src/autho/validation.clj` | Filtrage des entrées |
| Handler HTTP | `src/autho/handler.clj` | Routage, middleware sécurité |
| Cache local | `src/autho/local_cache.clj` | Cache LRU+TTL |
| API v1 | `src/autho/api/v1.clj` | Gestion des politiques et sujets |

### 2.2 Composants exclus de la TOE

| Composant | Justification |
|---|---|
| Terminaison TLS | Déléguée au reverse proxy (nginx, HAProxy, Ingress K8s) |
| Infrastructure Kafka | Utilisée pour l'invalidation de cache — hors logique de décision |
| Base H2 (audit et politiques) | Composant persistance — évalué comme dépendance |
| JVM (Java Virtual Machine) | Composant d'exécution — hors périmètre produit |
| Système d'exploitation hôte | Hors périmètre produit |
| Reverse proxy | Hors périmètre produit |

### 2.3 Interfaces de la TOE

```
Entrées (interfaces utilisées par les PEP) :
  POST /isAuthorized       — Décision d'autorisation
  POST /whoAuthorized      — Sujets autorisés
  POST /whatAuthorized     — Ressources autorisées
  POST /explain            — Explication de décision
  POST /simulate           — Simulation

Interfaces d'administration (protégées par wrap-admin-auth) :
  POST /admin/reinit        — Réinitialisation du PDP
  POST /admin/reload_rules  — Rechargement des politiques
  GET  /audit/verify        — Vérification de la chaîne d'audit
  GET  /audit               — Consultation du journal
  GET  /metrics             — Métriques Prometheus
  GET  /status              — État du serveur

Interfaces de gestion des politiques (via API v1) :
  PUT  /v1/policy/:class    — Soumission d'une politique
  GET  /v1/policy/:class    — Lecture d'une politique
  GET  /v1/policies         — Liste des politiques
```

---

## 3. Hypothèses d'environnement

Ces hypothèses sont des conditions préalables au bon fonctionnement sécurisé de la TOE. Elles sont en dehors du périmètre d'évaluation mais **doivent être garanties par l'opérateur**.

| ID | Hypothèse | Description |
|---|---|---|
| H.TLS | Terminaison TLS obligatoire | Tout trafic entre les PEP et Autho transite via TLS 1.2 minimum (TLS 1.3 recommandé) géré par un reverse proxy dédié |
| H.NET | Réseau interne protégé | Le port 8080 d'Autho n'est accessible qu'au(x) reverse proxy autorisé(s) — pas d'exposition directe sur Internet |
| H.SECRETS | Secrets robustes | JWT_SECRET, API_KEY et AUDIT_HMAC_SECRET sont d'au moins 256 bits d'entropie et gérés via un gestionnaire de secrets (Vault ou équivalent) |
| H.HOST | Hôte durci | Le système d'exploitation hôte est maintenu à jour (CVE), Autho s'exécute sous un compte dédié sans privilèges root |
| H.ADMIN | Administrateurs de confiance | Les administrateurs ayant accès aux variables d'environnement et aux routes `/admin/*` sont des personnes habilitées |
| H.KAFKA | Canal Kafka authentifié | Si Kafka est activé, le canal de communication est authentifié et chiffré (TLS + SASL) — hors TOE |
| H.LDAP | LDAP sécurisé | Si le PIP LDAP est activé, la connexion utilise LDAPS (636/TCP) ou STARTTLS — hors TOE |

---

## 4. Menaces couvertes

### 4.1 Agents menaçants considérés

- **Attaquant externe non authentifié** : accès réseau à l'API Autho via le reverse proxy
- **Attaquant interne authentifié** : titulaire d'un JWT ou API Key valide tentant d'outrepasser ses droits
- **Attaquant sur le journal d'audit** : tentative d'altération ou d'effacement des traces d'audit

*Hors périmètre* : attaquant disposant d'un accès physique ou d'un accès root au système hôte.

### 4.2 Menaces adressées par la TOE

| ID | Menace | Fonctions de sécurité couvrantes |
|---|---|---|
| M.USURP | Usurpation d'identité d'un PEP | Authentification JWT (HS256, clé ≥ 256 bits) + comparaison API Key en temps constant |
| M.BYPASS | Contournement de la décision d'autorisation | Logique PDP fail-deny, validation XACML stricte, cache invalidé sur mise à jour des politiques |
| M.INJECT | Injection via les paramètres de la requête | Validation et assainissement systématiques (`validation.clj`) — protection injection SQL/XSS/commandes |
| M.FLOOD | Déni de service par saturation | Rate limiting différencié (API Key 10k/min, JWT 1k/min, anonyme 100/min), limite taille requête (1 MB) |
| M.TAMPER | Altération du journal d'audit | Chaîne HMAC-SHA256 — toute modification d'une entrée est détectable par `/audit/verify` |
| M.ALG | Attaque par substitution d'algorithme JWT | Algorithme HS256 déclaré explicitement — `alg: none` et autres algorithmes rejetés |
| M.TIMING | Attaque temporelle sur l'API Key | Comparaison en temps constant via `MessageDigest.isEqual` |
| M.WEAKKEY | Secret trop court (attaque par force brute) | Validation obligatoire ≥ 32 caractères au démarrage |
| M.OVERLOAD | Saturation par payload surdimensionné | Limite configurable (défaut 1 MB) appliquée avant parsing |

### 4.3 Menaces non couvertes (hors TOE)

| Menace | Raison de l'exclusion |
|---|---|
| Interception réseau (MITM) | Couverte par H.TLS (reverse proxy) |
| Compromission de la JVM ou de l'OS hôte | Couverte par H.HOST |
| Attaque sur la base H2 (accès fichier direct) | Couverte par H.HOST (permissions système) |
| Replay d'un JWT valide | Limité par l'expiration du JWT — anti-replay strict non implémenté (évolution identifiée) |

---

## 5. Politiques de sécurité de la TOE (TSP)

### 5.1 Politique d'authentification

- Toute requête sur les routes protégées doit présenter soit un JWT HS256 valide (header `Authorization: Token <jwt>`), soit une API Key valide (header `X-API-Key: <key>`).
- Un JWT est valide si : signature HMAC-SHA256 vérifiée avec `JWT_SECRET`, non expiré, algorithme HS256 uniquement.
- Les routes publiques (`/health`, `/metrics`, `/openapi.yaml`) ne requièrent pas d'authentification.

### 5.2 Politique d'autorisation administrative

- Les routes `/admin/*` requièrent soit une API Key, soit un JWT avec claim `role: admin`.
- La logique est implémentée dans `wrap-admin-auth` (handler.clj).

### 5.3 Politique d'audit

- Toute décision d'autorisation (`isAuthorized`, `whoAuthorized`, `whatAuthorized`) est enregistrée dans le journal d'audit.
- Le journal est une chaîne HMAC-SHA256 : toute altération d'une entrée est détectable.
- L'intégrité peut être vérifiée à tout moment via `GET /audit/verify`.

### 5.4 Politique de protection des entrées

- Toutes les données reçues dans le corps des requêtes POST sont validées et assainies avant traitement.
- Les patterns d'injection (SQL, shell, XSS) sont détectés et la requête rejetée avec HTTP 400.
- La taille du corps est limitée (défaut 1 MB).

### 5.5 Politique de rate limiting

- Chaque identité (API Key, JWT, anonyme) est soumise à des limites de débit configurables.
- Le dépassement de la limite retourne HTTP 429 sans traitement de la requête.

---

## 6. Fonctions de sécurité de la TOE (TSF)

### FST.1 — Authentification JWT

| Attribut | Valeur |
|---|---|
| Algorithme | HMAC-SHA256 (HS256) |
| Bibliothèque | buddy-auth 3.0.323 |
| Clé | JWT_SECRET (≥ 256 bits, obligatoire) |
| Rejet alg:none | Oui (option `{:alg :hs256}`) |
| Fichier | `src/autho/auth.clj` |

### FST.2 — Authentification API Key

| Attribut | Valeur |
|---|---|
| Transport | Header HTTP `X-API-Key` |
| Comparaison | Temps constant (MessageDigest.isEqual) |
| Stockage | Variable d'environnement (≥ 32 chars) |
| Fichier | `src/autho/auth.clj` |

### FST.3 — Journal d'audit

| Attribut | Valeur |
|---|---|
| Algorithme hash | SHA-256 (javax.crypto) |
| Algorithme HMAC | HMAC-SHA256 (javax.crypto.Mac) |
| Clé HMAC | AUDIT_HMAC_SECRET (≥ 256 bits, obligatoire) |
| Stockage | Base H2 dédiée (`resources/auditdb`) |
| Chiffrement au repos | AES-128/CIPHER=AES via `H2_AUDIT_CIPHER_KEY` (optionnel, recommandé prod) |
| Écriture | Asynchrone (agent Clojure) |
| Vérification | `GET /audit/verify` |
| Tests | `test/autho/audit_test.clj` — 14 tests / 30 assertions |
| Fichier | `src/autho/audit.clj` |

### FST.4 — Validation des entrées

| Attribut | Valeur |
|---|---|
| Patterns filtrés | Injection SQL, XSS, injection shell, path traversal |
| Limite taille | 1 MB (configurable via MAX_REQUEST_SIZE) |
| Réponse en cas d'échec | HTTP 400 avec code d'erreur structuré |
| Fichier | `src/autho/validation.clj` |

### FST.5 — Rate limiting

| Attribut | Valeur |
|---|---|
| Granularité | Par identité (API Key, JWT, anonyme) ou IP en fallback |
| Défauts | API Key : 10 000/min, JWT : 1 000/min, anonyme : 100/min |
| Réponse dépassement | HTTP 429 |
| Fichier | `src/autho/handler.clj` (wrap-rate-limit) |

### FST.6 — Cache décision

| Attribut | Valeur |
|---|---|
| Type | LRU + TTL (4 niveaux) |
| Invalidation | Kafka (multi-instances) ou redémarrage |
| TTL décision | 60 secondes |
| Fichier | `src/autho/local_cache.clj` |

### FST.7 — Chiffrement au repos des bases H2

| Attribut | Valeur |
|---|---|
| Algorithme | AES-128 (H2 `CIPHER=AES`) |
| Base audit | `H2_AUDIT_CIPHER_KEY` → `resources/auditdb;CIPHER=AES` |
| Base policy | `H2_POLICY_CIPHER_KEY` → `resources/h2db;CIPHER=AES` |
| Activation | Optionnelle — warning au démarrage si non défini |
| Statut prod | Recommandé obligatoire (conformité RGS — protection vol de disque) |
| Fichiers | `src/autho/audit.clj`, `src/autho/prp.clj`, `src/autho/policy_versions.clj` |

---

## 7. Algorithmes cryptographiques

Conformément à la liste des algorithmes approuvés par l'ANSSI (RGS v2.0 Annexe B1) :

| Usage | Algorithme | Taille de clé | Statut ANSSI |
|---|---|---|---|
| Signature JWT | HMAC-SHA256 (HS256) | ≥ 256 bits | Approuvé |
| Chaîne d'audit (hash) | SHA-256 | — | Approuvé (SHA-2) |
| Chaîne d'audit (MAC) | HMAC-SHA256 | ≥ 256 bits | Approuvé |
| Chiffrement H2 au repos | AES-128 (H2 CIPHER=AES) | clé variable | Approuvé (AES ∈ liste ANSSI) |
| TLS (hors TOE) | TLS 1.2/1.3, AES-256-GCM | — | Approuvé |

Algorithmes explicitement absents/rejetés :
- MD5, SHA-1 : non utilisés
- DES, 3DES, RC4 : non utilisés
- RSA < 3072 bits : non utilisé
- `alg: none` dans JWT : rejeté

---

## 8. Éléments hors périmètre d'évaluation CSPN

Les éléments suivants sont hors périmètre mais couverts par les hypothèses d'environnement :

- Protocoles TLS (gérés par le reverse proxy, H.TLS)
- Sécurité physique des serveurs (H.HOST)
- Infrastructure Kafka (H.KAFKA)
- Authentification de l'annuaire LDAP (H.LDAP)
- Chiffrement au repos des bases H2 — **implémenté** (FST.7, `H2_AUDIT_CIPHER_KEY` / `H2_POLICY_CIPHER_KEY`)

---

## 9. Évolutions de sécurité identifiées (hors version évaluée)

| Priorité | Évolution | Motivation |
|---|---|---|
| ~~Haute~~ | ~~Chiffrement au repos des bases H2~~ | **Implémenté** — FST.7 |
| Haute | Anti-replay JWT (blacklist ou jti tracking) | Limiter l'impact d'un token volé |
| Moyenne | Support RS256/ES256 (asymétrique) pour JWT | Permettre aux PEP de vérifier le token sans le secret |
| Moyenne | Connecteur Pro Santé Connect (PIP) | Accès marché PGSSI-S / santé |
| Basse | Implémentation AuthZEN Authorization API 1.0 | Interopérabilité avec les PEP conformes OpenID Foundation |
| Basse | Support XACML 4.0 / JSON natif | Modernisation syntaxe politiques |

---

## 10. Références

| Document | Référence |
|---|---|
| Guide d'administration sécurisé | `docs/SECURITY_ADMIN_GUIDE.md` |
| Politique de développement sécurisé | `docs/SECURE_DEVELOPMENT_POLICY.md` |
| Architecture technique | `docs/ARCHITECTURE.md` |
| RGS v2.0 | ANSSI — cyber.gouv.fr |
| XACML 3.0 | OASIS Standard, 2013 |
| CSPN — présentation | cyber.gouv.fr/presentation-de-la-certification-de-securite-de-premier-niveau-cspn |
| buddy-auth | github.com/funcool/buddy-auth |

---

*Ce document constitue la cible de sécurité préliminaire d'Autho dans le cadre d'une démarche CSPN.*
*Il doit être finalisé en collaboration avec un CESTI agréé ANSSI avant soumission officielle.*
