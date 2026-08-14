# Plan d'implémentation — ReBAC hybride

## Statut et objectif

**Statut : implémenté dans le produit ; il reste à raccorder et exploiter les
sources métier réelles de chaque déploiement.**

L'objectif est de faire d'Autho un moteur hybride : les systèmes métier restent propriétaires des données métier et des faits relationnels ; Autho les résout soit à la demande via un PIP, soit depuis une projection relationnelle locale optimisée pour les décisions, les traversées et les explications.

Autho ne doit jamais devenir propriétaire implicite d'un fait tel que « Alice appartient à l'équipe Finance ». Lorsqu'il stocke ce fait, il stocke une projection technique provenant d'une source identifiée et réconciliable.

## 1. Décisions d'architecture

### 1.1 Modèle cible

```text
Systèmes métier / IAM (sources de vérité)
        |                         |
        | PIP synchrone           | Outbox / Kafka / import
        v                         v
PDP Autho <-------------- Relation projection store
    |                              |
    +-- règles ABAC + ReBAC -------+
    |
    v
Décision, obligations, explain, audit
```

Les attributs sont normalement lus dans la requête ou via PIP. Les relations peuvent être résolues selon trois modes :

| Mode | Usage | Source de vérité |
| --- | --- | --- |
| `pip` | relation peu fréquente, forte fraîcheur requise | système métier interrogé à la demande |
| `projection` | graphe traversable, décisions fréquentes | projection Autho alimentée par le métier |
| `hybrid` | compromis configurable entre fraîcheur et performance | projection, avec vérification PIP selon la règle |

Le choix du mode est explicite dans une politique ou dans sa configuration associée. Aucun fallback silencieux ne doit transformer l'indisponibilité d'un PIP en autorisation inattendue.

### 1.2 Principes non négociables

- Les identifiants de sujet et ressource restent les identifiants stables des domaines métiers.
- Toute relation projetée porte son `tenantId` et ne peut pas traverser un tenant.
- Chaque écriture projetée est idempotente et attribuable à une source.
- Les suppressions et révocations sont des événements de première classe.
- Une réponse relationnelle distingue `allowed`, `denied`, `unknown` et `error`.
- Les accès sensibles peuvent exiger une fraîcheur forte et un refus en cas d'incertitude.
- L'API manuelle de tuples est réservée aux démonstrations, réparations tracées ou relations explicitement gérées par Autho.

## 2. Lot 0 — Cadrage des données d'autorisation

Avant de modifier le moteur, établir une fiche pour chaque relation utilisée par une politique :

- nom de la relation et classes concernées ;
- système métier propriétaire ;
- mode choisi (`pip`, `projection`, `hybrid`) ;
- sujet, ressource et tenant de référence ;
- événement ou endpoint de synchronisation ;
- SLA de fraîcheur ;
- comportement en cas de donnée manquante ou de panne ;
- règles de suppression, expiration et réconciliation ;
- propriétaire opérationnel.

### Critère d'acceptation

Les relations existantes (`member`, `viewer`, `editor`, `parent`, etc.) ont une source et une sémantique documentées. Aucune relation critique n'est alimentée manuellement sans responsable ni processus de réconciliation.

## 3. Lot 1 — Abstraction `RelationProvider`

**État : implémenté.** Le PDP, l'évaluateur de clauses et les endpoints de
lecture relationnelle utilisent désormais `autho.relation-provider`. Le
provider compatible par défaut est la projection locale existante.

Créer une abstraction interne qui sépare le PDP du stockage actuel `autho.rebac`.

API conceptuelle :

```clojure
(check-relation provider request subject relation resource options)
(list-objects provider request subject relation options)
(list-subjects provider request resource relation options)
(traverse provider request start steps options)
```

Le résultat de `check-relation` doit inclure une décision tri-état et les métadonnées utiles :

```clojure
{:status :allowed | :denied | :unknown | :error
 :source :projection | :pip | :hybrid
 :proof ...
 :observedAt ...
 :version ...
 :stalenessMs ...}
```

Implémenter ensuite :

1. `local-projection-provider`, adaptateur du graphe H2/index mémoire actuel ;
2. `pip-relation-provider`, pour les PIP relationnels ;
3. `hybrid-relation-provider`, qui applique la stratégie demandée.

Migrer le PDP, l'explain, les endpoints `check`, `list-objects`, `list-subjects` et `traverse` vers cette abstraction. Conserver le comportement existant comme valeur compatible temporaire, mais le documenter comme `projection`.

### Critères d'acceptation

- Le PDP ne dépend plus directement de `rebac/has-relation?`.
- Les tests ReBAC existants restent verts.
- Une décision/explain expose la source utilisée et le statut de résolution.
- Le comportement de `unknown` et `error` est couvert par des tests.

## 4. Lot 2 — PIP relationnel

**État : partiellement implémenté.** Un registre de resolvers relationnels
nommés, les modes `pip` et `hybrid`, ainsi qu'un connecteur REST configurable
sont disponibles. La configuration est chargée depuis `relation-pips.edn` (ou
`RELATION_PIPS_CONFIG_PATH`) ; elle impose un timeout et traite les échecs en
`error`, donc sans autorisation. Le circuit breaker partagé est actif. Les
connecteurs supplémentaires restent une décision d'intégration par source
métier (REST couvre le cas de référence).

Définir un contrat de PIP relationnel, distinct d'un attribut scalaire :

```text
check(subject, relation, resource, context) ->
  allowed | denied | unknown | error,
  avec version/horodatage/preuve facultative
```

Le contrat doit permettre d'identifier le fournisseur et de borner le délai d'appel. Les données retournées ne doivent jamais contenir de secrets ou de données métier inutiles à la décision.

Ajouter à la configuration :

- sélection du PIP par relation/classe ou par politique ;
- timeout, circuit breaker et budget de latence ;
- politique de panne (`fail-closed`, `unknown`, ou exception explicitement autorisée) ;
- cache optionnel, avec TTL et clé incluant le tenant.

### Critères d'acceptation

- Une règle peut évaluer une relation exclusivement depuis un PIP.
- Une panne PIP ne devient jamais une autorisation par défaut.
- Les traces et métriques permettent de distinguer refus métier, relation inconnue et échec technique.

## 5. Lot 3 — Projection ReBAC gouvernée

**État : en cours.** La projection locale est maintenant partitionnée par
tenant, y compris dans les index mémoire, les vérifications, les listes, les
traversées et la persistance H2. Les tuples sans tenant antérieurs à cette
évolution sont conservés dans la partition de compatibilité `__legacy__`. Les
tuples persistent désormais leur provenance, identifiant/version d'événement,
dates d'occurrence/réception et expiration ; une relation expirée ne peut plus
autoriser, être listée ou participer à une traversée. Un état de version est
conservé par tuple, y compris après suppression, afin d'ignorer les événements
reçus en retard. Le point d'entrée idempotent et persistant
`apply-projection-event!` est disponible pour les événements d'outbox/Kafka ;
le consommateur Kafka dédié est optionnel et opérationnel. Les réécritures sont
également tenantisées ; les anciennes valeurs sont confinées à `__legacy__`.

Faire évoluer `REBAC_RELATIONS` afin de stocker une projection d'autorisation, et non un simple tuple non attribué.

Champs à ajouter ou représenter :

- `tenant_id` ;
- `source` ;
- `source_event_id` ;
- `source_version` ;
- `occurred_at` ;
- `received_at` ;
- `expires_at` facultatif ;
- état de suppression logique si nécessaire.

Adapter les index mémoire pour inclure le tenant dans toutes les clés. Les rewrites doivent également être tenantisés ou explicitement globaux, sans ambiguïté.

Écrire des opérations atomiques : insertion/mise à jour conditionnée par version, suppression idempotente, purge d'expiration et nettoyage de ressource/sujet supprimé.

### Critères d'acceptation

- Deux tenants ne peuvent ni se voir ni être joints par une traversée.
- Un événement dupliqué ou plus ancien ne modifie pas la projection.
- Une relation expirée ne peut plus autoriser une décision.
- Toute mutation conserve sa provenance et est auditée.

## 6. Lot 4 — Ingestion événementielle et contrat Kafka/outbox

**État : socle implémenté.** Autho accepte désormais les événements génériques
`authorization.relationship.upserted` et `authorization.relationship.deleted`
via un point d'entrée indépendant du transport. Les `eventId` sont dédupliqués
dans H2 (`REBAC_PROJECTION_EVENTS`) et la provenance est transférée au tuple.
Un consommateur Kafka optionnel est disponible via `REBAC_KAFKA_ENABLED=true`;
il délègue au noyau idempotent et place les messages JSON invalides en
quarantaine durable dans H2 (`REBAC_PROJECTION_QUARANTINE`). Le module offre
la consultation et le rejeu unitaire contrôlé, désormais exposés par l'API de
gouvernance et la GUI **Relations**. Les compteurs Prometheus couvrent les
événements de projection par résultat et les créations/rejeux de quarantaine.
La GUI expose l'état du consommateur, l'âge de projection et le retard maximal.
Les seuils sont configurables par `REBAC_KAFKA_MAX_IDLE_MS` et
`REBAC_KAFKA_MAX_LAG_RECORDS`. Restent les règles d'alerte de la plateforme
d'observabilité.

Définir un schéma versionné d'événements, publié par les systèmes sources. Exemple :

```json
{
  "eventId": "01J...",
  "eventType": "authorization.relationship.upserted",
  "occurredAt": "2026-08-13T10:15:00Z",
  "tenantId": "tenant-a",
  "source": "iam",
  "version": 48,
  "tuple": {
    "subject": {"class": "Person", "id": "alice"},
    "relation": "member",
    "resource": {"class": "Group", "id": "finance"}
  }
}
```

Prendre en charge au minimum :

- création/mise à jour de relation ;
- suppression de relation ;
- suppression de sujet ou ressource ;
- événements de snapshot/reconstruction si le transport le permet.

Les trois premières familles sont prises en charge par le noyau d'ingestion.
Les suppressions de sujet ou de ressource retirent uniquement les tuples du
tenant ciblé et mémorisent la version de chaque tuple concerné afin qu'un
événement relationnel plus ancien ne le recrée pas.

Le consommateur doit gérer la validation de schéma, la déduplication par `eventId`, le contrôle de version, les messages invalides en quarantaine, le rejeu et l'audit des mutations.

Les producteurs doivent utiliser une outbox transactionnelle lorsque le tuple découle d'une mutation métier : l'écriture métier et l'événement sont alors atomiquement couplés côté source.

### Critères d'acceptation

- Une suppression de membre publiée par la source révoque l'accès dans la projection.
- Le rejeu complet d'un topic ne produit pas de divergence.
- Les messages invalides sont visibles et ne bloquent pas le flux sain.
- Le retard de consommation est mesuré par source et tenant.

## 7. Lot 5 — Réconciliation et reconstruction

**État : implémenté.** `POST /v1/relations/reconcile` compare, sans
mutation, un snapshot fourni par une source métier à la projection d'un tenant
et retourne les tuples `missing` et `obsolete`. La correction doit encore être
publiée par la source via les événements normaux. Les sources REST configurées
peuvent être importées à la demande ou exécutées périodiquement, pour les
tenants explicitement déclarés. Les résumés de comparaison sont persistés dans
`REBAC_RECONCILIATION_REPORTS` et consultables par tenant.

Les événements ne suffisent pas à garantir l'absence de dérive. Chaque source doit offrir un moyen de reconstruire ou vérifier les relations : export paginé, endpoint incrémental, snapshot compacté ou import contrôlé.

Implémenter un processus de réconciliation capable de :

1. sélectionner une source et un tenant ;
2. obtenir l'état de référence ;
3. comparer la projection et la référence ;
4. produire un rapport d'écarts ;
5. appliquer une correction avec audit, après validation selon le niveau de risque.

Le rapport de comparaison expose les relations `missing` et `obsolete`, les
conflits de version lorsqu'ils sont comparables, ainsi qu'une collection
`errors` réservée aux erreurs de lecture remontées par un connecteur.

### Critères d'acceptation

- Une relation manquante ou obsolète est détectée puis corrigée.
- Une reconstruction peut être exécutée sans mélange entre tenants.
- Le rapport identifie les ajouts, suppressions, conflits de version et erreurs.

## 8. Lot 6 — Fraîcheur et cohérence par décision

Ajouter une stratégie de cohérence aux clauses ReBAC ou à la politique :

| Politique | Effet attendu |
| --- | --- |
| `eventual` | accepter la projection locale |
| `bounded-staleness` | refuser/retourner inconnu si elle dépasse un seuil |
| `fresh` | vérifier la source PIP avant une action sensible |
| `fail-closed` | refuser quand fraîcheur ou source ne sont pas garanties |

Le mode `hybrid` doit être explicite sur sa séquence : projection d'abord puis PIP si stale, ou PIP systématique pour validation. Cette stratégie est à choisir par cas d'usage, pas globalement.

### Critères d'acceptation

- Une règle critique peut bloquer une décision lorsque sa relation projetée est trop ancienne.
- Les explications indiquent la fraîcheur et le chemin de résolution réellement appliqué.
- Les tests couvrent toutes les stratégies de cohérence.

## 9. Lot 7 — API, sécurité et GUI

Séparer les interfaces :

- **lecture relationnelle** : check, listes, traversal et explain ;
- **ingestion technique** : accès réservé aux connecteurs, avec provenance/version ;
- **administration exceptionnelle** : démonstration, réparation contrôlée ou relations Autho-owned ;
- **réconciliation** : lancement, progression, rapport et correction ;
- **observabilité** : sources, fraîcheur, erreurs, derniers offsets et état de projection.

Dans la GUI, ne pas afficher les tuples comme des données métier modifiables par défaut. Afficher plutôt la source, l'état de synchronisation, le retard, les explications de chemins et les résultats de réconciliation. Toute mutation manuelle doit être signalée comme exceptionnelle, auditée et soumise à un rôle dédié.

**État : implémenté.** La page GUI **Relations** affiche l'état
Kafka/projection, la fraîcheur, le retard, la quarantaine durable et son rejeu,
les sources configurées, la réconciliation manuelle ou par import et le
journal de projection. Les API correspondantes sont réservées à
`relation-admin` ou `governance-admin`.

### Critères d'acceptation

- Les droits d'ingestion, d'administration et de lecture sont distincts.
- La GUI indique clairement si une relation vient d'une projection ou d'un PIP.
- Une mutation manuelle laisse une preuve complète et ne contourne pas silencieusement le métier.

## 10. Lot 8 — Observabilité et exploitation

Ajouter métriques, logs corrélés et alertes pour :

- taux et latence des checks PIP ;
- taux `allowed`/`denied`/`unknown`/`error` par provider ;
- âge de projection et retard Kafka ;
- événements rejetés ou mis en quarantaine ;
- échecs de réconciliation ;
- profondeur des traversées, cycles évités et limites atteintes ;
- distributions par tenant et source.

**État : implémenté côté produit.** Les compteurs
`autho_rebac_projection_events_total` et
`autho_rebac_quarantine_events_total`, les jauges de retard Kafka et les dates
de dernière projection/poll sont exposés. L'état API applique les seuils
`REBAC_KAFKA_MAX_IDLE_MS` et `REBAC_KAFKA_MAX_LAG_RECORDS`; les règles
Alertmanager sont à définir dans la plateforme d'observabilité du déploiement.

Documenter les procédures de reprise : indisponibilité PIP, retard Kafka, reconstruction d'un tenant, corruption locale, réconciliation et révocation urgente.

### Critères d'acceptation

- Une équipe d'exploitation peut identifier la source d'une décision erronée.
- Une révocation urgente a une procédure et un délai cible documentés.
- Les SLO de décision et de fraîcheur sont mesurables.

## 11. Stratégie de migration depuis l'implémentation actuelle

1. Conserver les tuples et rewrites existants comme provider `projection` compatible.
2. Ajouter `RelationProvider` sans changer les résultats actuels.
3. Ajouter provenance et tenantisation avec migration de schéma contrôlée.
4. Introduire le PIP relationnel sur un cas d'usage pilote.
5. Introduire l'ingestion Kafka/outbox sur ce même cas.
6. Activer la réconciliation et comparer PIP/projection.
7. Migrer progressivement les relations existantes vers des sources explicites.
8. Restreindre les mutations manuelles lorsque les flux source sont validés.

Ne migrer aucune relation sensible vers `eventual` sans validation métier et sécurité de la fenêtre de révocation acceptable.

## 12. Tests de recette obligatoires

- relation directe, groupe imbriqué, héritage parent et rewrite ;
- PIP seul, projection seule et stratégie hybride ;
- relation absente, inconnue, PIP indisponible et donnée périmée ;
- ajout, révocation, duplication, désordre et rejeu d'événements ;
- expiration d'une délégation ;
- réconciliation après perte d'événement ;
- isolation stricte de tenant dans check, listes et traversal ;
- cohérence entre décision, explain, audit et source déclarée ;
- limites de profondeur, cycles et charge de groupes volumineux.

## 13. Définition de terminé

Le modèle ReBAC hybride est considéré terminé lorsque :

1. le PDP dépend d'une abstraction de provider et non du store local ;
2. les relations peuvent être résolues par PIP, projection ou hybride ;
3. toute projection est tenantisée, attribuée, versionnée et idempotente ;
4. au moins une source métier est intégrée par événements/outbox et réconciliée ;
5. la stratégie de fraîcheur est appliquée aux cas sensibles ;
6. les opérations, l'audit et la GUI permettent d'expliquer une décision sans prétendre qu'Autho possède le métier ;
7. la suite de tests couvre les scénarios de sûreté et de dérive ci-dessus.

À cette condition, reprendre [PLAN_PRODUIT_FREEMIUM_ENTERPRISE_2026_2027.md](PLAN_PRODUIT_FREEMIUM_ENTERPRISE_2026_2027.md), en intégrant la projection relationnelle comme une capacité de data plane d'autorisation optionnelle.
