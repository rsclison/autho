# Synthèse des concepts et de l'auditabilité d'Autho

Autho est un serveur central d'autorisation. Les applications lui délèguent la décision d'accès, puis récupèrent un verdict `allow` ou `deny` avec les éléments qui expliquent cette décision.

## En termes simples

- Autho répond à la question: "cet utilisateur peut-il faire cette action sur cette ressource ?"
- Le moteur regarde des attributs, des relations et le contexte avant de décider.
- Chaque décision peut être expliquée, rejouée, historisée et vérifiée.
- L'objectif n'est pas seulement de décider, mais aussi de **prouver** pourquoi la décision a été prise.

## Concepts principaux

### ABAC

ABAC signifie que la décision dépend d'attributs dynamiques:

- identité du sujet
- attributs de la ressource
- contexte de la requête

Exemple: un document peut être accessible seulement si le service du demandeur correspond à celui de la ressource.

### ReBAC

ReBAC ajoute la notion de relations:

- `member`
- `parent`
- relations personnalisées comme `viewer`

Cela permet de gérer les groupes imbriqués, l'héritage par ressource parente et les chemins relationnels explicites.

### PDP / PRP / PIP / PAP

- **PDP**: calcule la décision
- **PRP**: stocke les politiques
- **PIP**: enrichit la requête avec des données externes
- **PAP**: permet d'administrer les politiques via API et UI

## Ce que couvre l'auditabilité

### 1. Journaliser les décisions

Chaque décision est enregistrée avec le sujet, la ressource, l'opération, la décision, les règles qui ont matché, et un instantané de la requête et de la décision.

- Décisions du PDP (ABAC, y compris les prédicats de relation dans les politiques).
- Vérifications ReBAC directes (`POST /v1/relations/check`), avec l'opération `relation:<nom>`. Tout résultat autre que `allowed` est journalisé `deny`.
- Délégation : une seule entrée, au nom du demandeur. La chaîne de délégués est conservée dans `decisionSnapshot.delegationChain` et `grantedVia`.

Le stockage se fait dans une base dédiée, H2 (chiffrée si `H2_AUDIT_CIPHER_KEY` est défini) ou PostgreSQL. L'écriture est asynchrone pour ne pas ralentir la décision.

### 2. Rendre le journal vérifiable

Chaque entrée porte :

- `payload_hash`, un SHA-256 calculé sur les colonnes telles qu'elles sont stockées (`hash_version` = 2) ;
- `previous_hash`, le hash de l'entrée précédente ;
- `hmac`, un HMAC-SHA256 de `v2|previous_hash|payload_hash` calculé avec `AUDIT_HMAC_SECRET`.

`GET /admin/audit/verify` recalcule tout et détecte :

- une colonne modifiée (décision, sujet, horodatage, snapshot…) ;
- une entrée supprimée ou réordonnée au milieu de la chaîne ;
- la suppression des dernières entrées. Pour cela, la tête enregistrée dans `AUDIT_CHAIN_HEAD` et la dernière tête écrite par l'instance doivent être présentes dans la chaîne.

Pour se protéger d'un attaquant qui réécrirait aussi la table de tête, il faut conserver hors de la base le couple `headId`/`headHash`, par exemple via un paquet de preuve signé. On le repasse ensuite en ancre : `?anchorId=…&anchorHash=…`.

Les entrées écrites avant `hash_version` 2 sont comptées dans `legacyEntries` : leur chaînage est vérifié, mais pas leur contenu.

Plusieurs instances peuvent partager la même base : chaque ajout verrouille la tête de chaîne (`SELECT … FOR UPDATE`), si bien que la chaîne reste linéaire.

### 3. Surveiller l'écriture

- La métrique Prometheus `autho_audit_writes_total{outcome="success|failure"}` compte les écritures réussies et échouées.
- `GET /admin/audit/health` et le bloc `audit` de `/status` indiquent les écritures réussies et échouées, la dernière erreur et les écritures en attente.
- Avec `AUDIT_REQUIRED=true`, un journal en échec fait passer `/readiness` en 503 : l'instance est retirée du load balancer.

### 4. Rechercher l'historique

L'audit peut être filtré par sujet, classe de ressource, décision, intervalle de dates (ISO-8601) et plan (`plane=abac|rebac`). Les requêtes sont paramétrées et une valeur invalide renvoie une erreur 400. L'interface admin propose un export CSV.

Le rejeu (`auditReplay`, utilisé par l'analyse d'impact et les paquets de preuve) ne reprend que les décisions ABAC.

### 5. Rétention

- `POST /admin/audit/purge` avec `{"before": "2026-01-01T00:00:00Z"}`, ou `AUDIT_RETENTION_DAYS=N` pour une purge quotidienne automatique.
- La purge supprime le début de la chaîne et enregistre un checkpoint signé par HMAC (`AUDIT_CHECKPOINT`) à partir duquel la vérification reprend.
- La purge est refusée (409) si la chaîne ne se vérifie pas : elle ne peut pas servir à effacer une falsification.

### 6. Exporter une preuve

`GET /v1/evidence` génère un paquet signé qui combine :

- l'état de la chaîne d'audit, avec `headId`/`headHash` (utilisables comme ancre) ;
- le replay des décisions enregistrées ;
- la timeline des changements de politiques si une ressource est fournie.

Il y a deux signatures :

- **HMAC-SHA256** (toujours présente) : vérifiable par le serveur via `POST /v1/evidence/verify`.
- **Ed25519** (si une paire de clés est configurée) : vérifiable par un tiers sans aucun secret. Il récupère la clé publique via `GET /v1/evidence/public-key`, puis lance :

```bash
lein evidence:verify bundle.json public-key.pem
```

Pour générer les clés :

```bash
openssl genpkey -algorithm ed25519 -out evidence-signing.pem
openssl pkey -in evidence-signing.pem -pubout -out evidence-signing.pub.pem
export EVIDENCE_SIGNING_PRIVATE_KEY="$(cat evidence-signing.pem)"
export EVIDENCE_SIGNING_PUBLIC_KEY="$(cat evidence-signing.pub.pem)"
```

## Fonctions et endpoints utiles

- `GET /admin/audit/search` : recherche dans le journal
- `GET /admin/audit/verify` : vérification de la chaîne (ancre optionnelle)
- `GET /admin/audit/health` : santé de l'écriture
- `POST /admin/audit/purge` : rétention
- `GET /v1/evidence` : export d'un paquet de preuve
- `POST /v1/evidence/verify` : vérification d'un paquet de preuve
- `GET /v1/evidence/public-key` : clé publique Ed25519

Dans le code :

- la persistance, la vérification, la purge et la santé du journal sont dans [`src/autho/audit.clj`](/home/rsclison/autho/src/autho/audit.clj) ;
- l'export et la vérification des preuves sont dans [`src/autho/evidence.clj`](/home/rsclison/autho/src/autho/evidence.clj), et la vérification hors ligne dans [`src/autho/evidence_cli.clj`](/home/rsclison/autho/src/autho/evidence_cli.clj) ;
- les handlers API sont dans [`src/autho/api/handlers.clj`](/home/rsclison/autho/src/autho/api/handlers.clj) ;
- les routes sont dans [`src/autho/api/v1.clj`](/home/rsclison/autho/src/autho/api/v1.clj) et [`src/autho/handler.clj`](/home/rsclison/autho/src/autho/handler.clj).

### Limites connues

- Un attaquant qui a accès à la base **et** connaît `AUDIT_HMAC_SECRET` peut réécrire la chaîne. Seule une ancre conservée à l'extérieur (paquet signé en Ed25519) le révèle.
- L'écriture reste asynchrone : une décision est rendue même si son audit échoue ensuite. L'échec est visible (métriques, santé, readiness), mais il n'y a pas de mode « fail-closed » par décision.
- La purge supprime un préfixe contigu par identifiant ; avec des horloges décalées entre instances, quelques entrées proches de la date limite peuvent être purgées.
- Les endpoints time-travel `audit-trail` et `what-could-access-at` renvoient encore des listes vides.

## Résumé opérationnel

Autho ne se limite pas à décider.

Il permet aussi de:

- expliquer une décision
- garder une trace fiable
- vérifier qu'elle n'a pas été altérée
- reconstruire un contexte d'audit pour gouvernance et conformité

Autrement dit, le projet vise une autorisation "observable et prouvable", pas seulement un simple `allow` ou `deny`.
