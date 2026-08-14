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

Chaque décision est enregistrée avec:

- le sujet
- la ressource
- l'opération
- la décision
- les règles qui ont matché

Le stockage est fait dans une base H2 dédiée, séparée du store de politiques. L'écriture est asynchrone pour ne pas ralentir le chemin critique de décision.

### 2. Rendre le journal vérifiable

Le journal d'audit est chaîné par hash et protégé par HMAC-SHA256.

En pratique:

- chaque entrée contient un hash du contenu
- chaque entrée référence implicitement la précédente via `previous_hash`
- une vérification complète peut détecter une modification ou une suppression

### 3. Rechercher l'historique

L'audit peut être filtré par:

- sujet
- classe de ressource
- décision
- intervalle de dates

L'interface admin expose aussi un export CSV.

### 4. Exporter une preuve

Autho peut générer un paquet d'évidence signé qui combine:

- l'état du journal d'audit
- un replay des décisions enregistrées
- la timeline des changements de politiques si une ressource est fournie

Ce paquet peut ensuite être vérifié.

## Fonctions et endpoints utiles

- `GET /admin/audit/search`: recherche dans le journal
- `GET /admin/audit/verify`: vérification de la chaîne d'audit
- `GET /v1/evidence`: export d'un paquet d'évidence
- `POST /v1/evidence/verify`: vérification d'un paquet d'évidence

Dans le code:

- la persistance et la vérification du journal sont dans [`src/autho/audit.clj`](/home/rsclison/autho/src/autho/audit.clj)
- l'export et la vérification des preuves sont dans [`src/autho/evidence.clj`](/home/rsclison/autho/src/autho/evidence.clj)
- les handlers API sont dans [`src/autho/api/handlers.clj`](/home/rsclison/autho/src/autho/api/handlers.clj)
- les routes sont dans [`src/autho/api/v1.clj`](/home/rsclison/autho/src/autho/api/v1.clj)

## Résumé opérationnel

Autho ne se limite pas à décider.

Il permet aussi de:

- expliquer une décision
- garder une trace fiable
- vérifier qu'elle n'a pas été altérée
- reconstruire un contexte d'audit pour gouvernance et conformité

Autrement dit, le projet vise une autorisation "observable et prouvable", pas seulement un simple `allow` ou `deny`.
