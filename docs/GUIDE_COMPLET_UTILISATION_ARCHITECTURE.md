# Guide complet Autho : principes, architecture et utilisation

Ce guide présente Autho comme un service d'autorisation pour applications B2B : il centralise les décisions d'accès, rend les politiques vérifiables et apporte une traçabilité exploitable par les équipes produit, sécurité et conformité.

Il décrit l'état fonctionnel actuel du dépôt. Les éléments annoncés comme futurs ne doivent pas être considérés comme disponibles en production.

## 1. À quoi sert Autho ?

Une application appelle Autho avant une opération sensible : consulter une facture, modifier un dossier, partager un document, administrer une organisation ou produire un export. Autho répond par une décision (`allow` ou `deny`) et, lorsque nécessaire, les obligations qui doivent accompagner l'accès.

Autho évite que chaque microservice réimplémente ses propres règles, souvent dispersées dans le code. Les politiques deviennent versionnées, testables, simulables et auditables.

Les publics principaux sont :

- les équipes produit et métier, qui formulent les règles ;
- les développeurs, qui intègrent le point de décision ;
- les administrateurs d'autorisation, qui publient et contrôlent les politiques ;
- les équipes sécurité, conformité et support, qui investiguent les accès.

Autho ne remplace pas l'authentification. L'application ou le fournisseur d'identité établit qui est l'utilisateur ; Autho détermine ce que ce sujet peut faire dans un contexte donné.

## 2. Les concepts fondamentaux

Une demande de décision est composée de quatre dimensions :

| Élément | Question | Exemples |
| --- | --- | --- |
| Sujet | Qui agit ? | utilisateur, service, partenaire, groupe |
| Action | Que veut-il faire ? | `read`, `create`, `approve`, `delete` |
| Ressource | Sur quoi ? | facture, document, dossier patient, projet |
| Contexte | Dans quelles conditions ? | tenant, pays, heure, risque, environnement |

Une règle exprime généralement un effet (`allow` ou `deny`) et une condition. Par exemple : « un membre finance peut lire une facture de son organisation ».

### ABAC et ReBAC

Autho permet principalement une autorisation fondée sur les attributs (ABAC) : les propriétés du sujet, de la ressource et du contexte sont comparées par les règles. C'est adapté aux rôles, à la propriété, à la classification de données, au pays ou à l'état métier.

Les relations entre entités (ReBAC) permettent de représenter des faits tels que « Alice est éditrice du document X » ou « l'équipe A est responsable du projet B ». Elles sont utiles pour le partage collaboratif et les délégations. Dans l'implémentation actuelle, ces relations sont persistées et indexées localement ; il ne s'agit pas encore d'un graphe distribué.

### PDP, PAP, PRP et PIP

Ces acronymes séparent les responsabilités :

- **PDP** (Policy Decision Point) : évalue une requête et retourne la décision ;
- **PAP** (Policy Administration Point) : permet d'écrire, valider, versionner et publier les politiques ;
- **PRP** (Policy Retrieval Point) : stocke et fournit les politiques actives ;
- **PIP** (Policy Information Point) : enrichit l'évaluation avec des données externes ou locales.

Cette séparation permet de faire évoluer les sources de données sans modifier l'application qui demande une décision.

## 3. Architecture

### Chemin d'une décision

```text
Application / API gateway
          |
          v
POST /v1/decisions  -- authentification et résolution du scope
          |
          v
PDP -- cache de décision -- PRP (politiques actives)
 |                         |
 |---- PIP / relations ----|
          |
          v
Contrat de décision + audit + métriques + comptage d'usage
```

Le point d'entrée moderne est l'API `/v1/*`. Les endpoints historiques restent présents pour compatibilité mais ne doivent pas être privilégiés dans une nouvelle intégration.

Le PDP construit un contexte comprenant notamment `tenantId`, `organizationId`, `projectId` et `environment` lorsque ces valeurs sont fournies ou portées par une clé API. Il sélectionne les politiques pertinentes, résout les informations PIP nécessaires, applique la stratégie de combinaison puis retourne le contrat de décision.

Une réponse doit être traitée comme une décision ponctuelle : l'application applique le résultat, les obligations et les contraintes, mais ne le transforme pas en permission permanente côté client.

### Composants et persistance actuelle

| Composant | Rôle | État actuel |
| --- | --- | --- |
| API HTTP | décisions, politiques, audit, administration | API versionnée `/v1` |
| Moteur de règles | évaluation ABAC/ReBAC et combinaison | Clojure |
| PRP | politiques, versions et déploiement | stockage H2 |
| Audit | preuves de décisions et événements | stockage H2 |
| Registre de clés API | création, révocation, expiration, scopes | stockage H2, secrets hachés |
| Usage | compteurs de décisions et quotas | stockage H2 |
| PIP | enrichissements | sources locales et connecteurs Kafka/RocksDB selon configuration |
| Interface d'administration | gouvernance et exploitation | application React `admin-ui` |

H2 est adapté au démarrage, aux démonstrations et aux déploiements simples. Une base PostgreSQL reste la cible naturelle pour une exploitation multi-instance et des exigences d'entreprise : haute disponibilité, sauvegardes, administration et concurrence renforcées. La stricte partition par tenant de tous les stockages n'est pas encore complète ; il faut donc considérer les scopes actuels comme un mécanisme de contexte et de contrôle de requête, pas comme une isolation SaaS achevée.

### Politique de conflit et obligations

Quand plusieurs règles correspondent, la stratégie de combinaison détermine le résultat. Une stratégie prudente privilégie le refus lorsqu'une règle de refus applicable existe. Les politiques doivent documenter explicitement la stratégie retenue pour éviter les surprises lors d'un ajout de règle.

Une décision peut contenir des obligations : journaliser un accès, masquer un champ, imposer une justification ou limiter un export. Le PDP les exprime ; le service qui protège la ressource est responsable de leur application effective.

## 4. Démarrage et première décision

Les prérequis sont Java et le wrapper Leiningen fourni par le dépôt. Depuis la racine :

```bash
./lein run
```

Utilisez les scripts de démonstration décrits dans [GUIDE_DEMONSTRATION.md](GUIDE_DEMONSTRATION.md) pour un parcours reproductible. La référence HTTP est dans [API_V1.md](API_V1.md).

Une application envoie ensuite une requête de décision. La forme exacte des attributs dépend des politiques publiées ; l'exemple suivant illustre le principe :

```json
{
  "subject": {"id": "u-42", "roles": ["finance"]},
  "action": "invoice.read",
  "resource": {"type": "invoice", "id": "inv-2026-17", "organizationId": "org-a"},
  "context": {"tenantId": "tenant-a", "environment": "production"}
}
```

L'appel doit être authentifié avec une clé API ou le mécanisme configuré. Une clé peut aussi imposer des claims de scope (`tenantId`, organisation, projet, environnement) ; ne faites pas confiance à une valeur de scope fournie par un client si elle contredit celle portée par sa clé.

Lisez et validez le [contrat de décision](DECISION_CONTRACT.md) avant l'intégration. En particulier, gérez les refus, les erreurs techniques, les obligations et les identifiants de corrélation d'audit de manière distincte.

## 5. Écrire des politiques fiables

Une politique lisible commence par un objectif métier, puis se décompose en règles courtes. Préférez les règles explicites : type de ressource, action, organisation, condition de propriété ou rôle. Évitez les règles universelles telles que « tout administrateur peut tout faire » sans garde-fou de tenant ou d'environnement.

Exemple conceptuel :

```text
allow invoice.read
si subject.roles contient finance
et resource.organizationId = context.organizationId

deny invoice.read
si resource.classification = restricted
et subject.clearance != restricted
```

Pour chaque politique :

1. Définir les actions et attributs attendus avec les propriétaires métier.
2. Écrire des cas autorisés, refusés et limites.
3. Simuler les cas avant publication.
4. Valider la syntaxe et les dépendances PIP.
5. Publier une version identifiable, avec commentaire de changement.
6. Examiner l'impact et surveiller l'audit après le déploiement.

Les politiques doivent être traitées comme du code de sécurité : revue à deux personnes pour les zones sensibles, versionnement, tests de non-régression, traçabilité des publications et procédure de retour arrière.

## 6. Utiliser l'interface d'administration

L'interface `admin-ui` est le poste de travail des administrateurs. Elle regroupe les routes suivantes : Dashboard, Policies, Simulator, Audit, PIP Data, Infrastructure et Settings. L'accès à l'interface ne remplace pas les contrôles applicatifs : une politique publiée doit toujours être appelée par le service qui protège la ressource.

### Dashboard

Utilisez le tableau de bord pour prendre le pouls du service : volume de décisions, tendances de refus, santé des composants et raccourcis vers les investigations. Une hausse inhabituelle des refus peut signaler un défaut de déploiement, un attribut manquant ou une tentative d'accès abusive.

### Policies : édition, versions et gouvernance

La page **Policies** sert à créer et modifier les politiques. Sélectionnez une politique, éditez les règles, validez-les et conservez une description métier précise. Ne publiez pas directement un changement large sans simulation.

Les vues de gouvernance complètent l'édition : historique, comparaison de versions, revue, analyse d'impact, approbation et déploiement. Le parcours recommandé est : brouillon → validation → simulation → revue → approbation → déploiement → surveillance. En cas de régression, comparez la version active avec la précédente et utilisez le retour arrière documenté.

### Simulator

Le simulateur exécute une demande hypothétique sans exposer une ressource réelle. Saisissez le sujet, l'action, la ressource et le contexte, puis vérifiez le résultat, les règles contributrices et les obligations. Créez au minimum trois simulations pour une règle : cas autorisé, cas refusé et valeur limite (autre tenant, rôle absent, état inattendu).

Le simulateur est l'outil à privilégier avant un changement de politique, mais il n'est pas un substitut à des tests automatisés ni à l'observation post-déploiement.

### Audit

La page **Audit** permet de filtrer les événements de décision et de gouvernance : période, sujet, ressource, action, effet, corrélation ou politique. Pour une investigation, partez d'un identifiant de requête, reconstituez le contexte et vérifiez la version de politique active à cet instant. Limitez l'accès à cette page : les traces peuvent contenir des métadonnées sensibles.

### PIP Data

**PIP Data** permet d'inspecter les données servant à enrichir les décisions. Vérifiez que les attributs sont frais, cohérents et associés aux bonnes clés. Lorsque Kafka ou RocksDB sont utilisés, une donnée absente, en retard ou mal sérialisée peut produire un refus prudent ou un résultat inattendu. Les règles critiques doivent prévoir ce comportement de panne.

### Infrastructure et Settings

**Infrastructure** expose les informations de connectivité et de santé des dépendances. Utilisez-la pour diagnostiquer un connecteur ou vérifier une configuration avant un déploiement.

**Settings** rassemble les paramètres d'administration disponibles dans l'interface. Les valeurs secrètes, clés API, paramètres de quota et paramètres de production doivent être administrés par les mécanismes sécurisés et l'API prévus, jamais copiés dans une politique ou un ticket.

À ce stade, la gestion complète des clés API persistantes, des claims de scope produit et de la facturation/usage est principalement exposée côté API ; elle n'est pas encore entièrement représentée dans l'interface graphique.

### Relations

La page **Relations** supervise la projection ReBAC et, surtout, les messages
Kafka qui n'ont pas pu être intégrés. Chaque entrée de quarantaine affiche son
identifiant, la raison du rejet et son payload. Après correction dans le
système source, un administrateur `relation-admin` peut lancer un rejeu
unitaire. Cette action ne modifie pas le métier : elle redemande simplement à
Autho de retraiter le même événement. N'utilisez pas cette page pour créer des
relations métier manuellement. La même page affiche l'état du consommateur,
l'âge de la projection et son retard, le journal d'opérations projetées, les
rapports de réconciliation, ainsi que les sources de snapshot configurées.
Un import compare le snapshot à la projection sans la corriger : les écarts
doivent être réparés par les événements du système métier propriétaire.

#### Exploiter une relation hybride

1. Déclarez dans la politique si la relation est lue depuis la `projection`, un
   `pip` nommé ou le mode `hybrid`. Utilisez `fresh` ou `fail-closed` pour une
   action sensible ; une indisponibilité du PIP ne devient jamais une
   autorisation.
2. Faites publier les changements métier dans l’outbox/Kafka avec un `eventId`,
   le `tenantId`, la `source`, la `version` et le tuple. Autho déduplique les
   événements et ignore les versions en retard.
3. Surveillez **Relations** : un âge de projection élevé, du retard ou de la
   quarantaine demande une investigation de la source. Corrigez la donnée dans
   le métier puis utilisez seulement le rejeu unitaire si le message est devenu
   valide.
4. Configurez une source REST de snapshot lorsque le risque de dérive le
   justifie. Un `interval-ms` et des `tenant-ids` déclenchent des comparaisons
   périodiques ; les écarts, conflits et erreurs sont des signaux de correction
   côté source, pas des ordres de mutation locale.

Les relations manuelles et les rewrites sont des actions d’exception, soumises
au rôle `relation-admin` et isolées par tenant. Ne les utilisez pas pour
remplacer le cycle de vie métier d’une appartenance ou d’un partage.

## 7. Parcours par cas d'usage

### 7.1 Accès aux factures d'une organisation

Objectif : les membres finance lisent les factures de leur organisation, les responsables peuvent les approuver et les autres organisations sont refusées.

1. Modélisez `organizationId` sur la ressource et dans le contexte résolu.
2. Créez une règle `allow` pour `invoice.read` et le rôle finance dans la même organisation.
3. Ajoutez une règle de refus pour l'accès inter-organisation ou les factures sensibles non habilitées.
4. Simulez un membre finance, un collaborateur sans rôle et un membre d'une autre organisation.
5. Publiez, puis vérifiez les décisions dans Audit.

### 7.2 Partage de documents collaboratifs

Objectif : le propriétaire et les éditeurs peuvent modifier un document ; les lecteurs ne peuvent que le consulter.

1. Représentez les relations `owner`, `editor` et `viewer` entre sujets et document.
2. Écrivez les règles relationnelles : propriétaire/éditeur → `document.update`, lecteur → `document.read`.
3. Préservez le contrôle de tenant pour empêcher qu'une relation ne traverse une organisation.
4. Testez la révocation d'une relation et vérifiez qu'elle produit immédiatement le refus attendu.

### 7.3 Données personnelles ou de santé

Objectif : limiter l'accès aux données classifiées aux personnes habilitées, dans leur finalité de traitement.

1. Ajoutez à la ressource une classification et au sujet l'habilitation nécessaire.
2. Ajoutez au contexte la finalité, la zone géographique et éventuellement le niveau de risque.
3. Créez un refus explicite en cas d'habilitation insuffisante ; complétez par une autorisation étroite.
4. Retournez des obligations telles que l'audit renforcé ou le masquage de champs.
5. Contrôlez régulièrement les traces et revoyez les habilitations.

### 7.4 Délégation temporaire

Objectif : un responsable délègue l'approbation à un remplaçant pendant une période limitée.

1. Enregistrez une relation de délégation avec date de début, date de fin et périmètre.
2. Autorisez `approve` seulement si la délégation est active et dans le même périmètre.
3. Simulez avant, pendant et après la période ; incluez une tentative hors périmètre.
4. Révoquez la délégation dès que nécessaire et conservez la preuve d'audit.

### 7.5 Enrichissement à partir d'événements ou de référentiels

Objectif : décider selon le statut de compte, la région ou l'appartenance d'équipe, sans dupliquer ces données dans chaque application.

1. Configurez la source PIP pertinente et sa clé de recherche.
2. Vérifiez les données dans **PIP Data** et la santé dans **Infrastructure**.
3. Écrivez des règles tolérantes aux données inconnues et choisissez explicitement le comportement de panne.
4. Surveillez fraîcheur, latence et taux d'échec du connecteur.

### 7.6 Déploiement sûr d'une règle à fort impact

Objectif : modifier l'accès à une ressource sensible sans interruption ni élargissement involontaire.

1. Créez un brouillon et documentez la raison métier.
2. Comparez la nouvelle version à la version active.
3. Utilisez le simulateur sur des cas normaux, négatifs et historiques représentatifs.
4. Exécutez la revue/approbation de gouvernance.
5. Déployez, surveillez Audit et les refus, puis préparez le retour arrière si nécessaire.

## 8. Identités, clés API et scopes

Les clés API persistantes servent aux intégrations machine-à-machine. Elles disposent de scopes, d'une date d'expiration et d'une révocation. Leur secret n'est pas stocké en clair ; affichez-le seulement lors de sa création, placez-le dans un coffre-fort et effectuez une rotation régulière.

Les endpoints `/v1/api-keys` permettent de créer, lister et révoquer les clés selon les permissions d'administration. La variable historique `API_KEY` reste un mécanisme de démarrage compatible, mais elle ne remplace pas un registre de clés avec rotation et révocation.

Les claims de scope peuvent être alimentés par `API_CLIENT_*`. Ils cadrent les requêtes pour un tenant, une organisation, un projet ou un environnement. Toute intégration doit faire correspondre ces claims à ses propres frontières de données.

L'OIDC/JWKS, SAML et SCIM sont des capacités Enterprise envisagées, pas des intégrations à supposer présentes aujourd'hui.

## 9. Usage, entitlements et freemium

Autho mesure les décisions par scope et au niveau total du déploiement. L'endpoint `/v1/usage/decisions` expose ces données aux administrateurs autorisés. Les entitlements comprennent notamment `decisions` et `instances`.

Par défaut, le seuil de quota est observé : une notification est attachée aux décisions et lots à partir de 80 % de consommation. Cela permet d'observer l'usage et de corriger le dimensionnement avant de bloquer un flux métier.

Le mode strict est activable avec `AUTHO_QUOTA_ENFORCEMENT=hard`. Il réserve le quota de manière atomique et renvoie HTTP 429 avec `DECISION_QUOTA_EXCEEDED` lorsque la limite est atteinte. Comme cette fonction repose actuellement sur H2, activez-la après une période de réconciliation des compteurs et avec une procédure opérationnelle claire.

## 10. Audit, preuves et investigation

Pour qu'une décision soit défendable, conservez l'effet, le moment, l'action, les références sujet/ressource, le scope, la politique/version pertinente et l'identifiant de corrélation. Ne journalisez pas inutilement des secrets, jetons ou données personnelles complètes.

Lors d'un incident :

1. Récupérez l'identifiant de corrélation depuis l'application ou la réponse Autho.
2. Recherchez l'événement dans **Audit**.
3. Vérifiez les attributs effectivement utilisés, la source PIP et la version de politique.
4. Distinguez un refus conforme, une donnée manquante et une défaillance technique.
5. Corrigez par une nouvelle version de politique ou de donnée, jamais par une exception manuelle non tracée.

Les exigences de conservation, d'accès aux journaux et de pseudonymisation dépendent de votre réglementation. Consultez le guide de sécurité avant de fixer une politique de rétention.

## 11. Sécurité et exploitation

Les règles de base sont les suivantes :

- utiliser TLS et des secrets hors du code source ;
- appliquer le moindre privilège aux administrateurs, clés API et PIP ;
- séparer développement, préproduction et production par environnement et secrets ;
- sauvegarder et tester la restauration des données H2 ;
- surveiller erreurs PDP, fraîcheur PIP, refus anormaux et consommation de quota ;
- organiser rotation des clés, revue de droits et revue des politiques sensibles ;
- prévoir un comportement explicite lorsque le PDP ou un PIP est indisponible.

Les procédures détaillées figurent dans [SECURITY_ADMIN_GUIDE.md](SECURITY_ADMIN_GUIDE.md) et [OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md).

## 12. Limites connues et trajectoire

La version actuelle fournit une fondation opérationnelle d'autorisation, mais les travaux Enterprise restent importants : base de données de production partagée, isolation SaaS stricte, fédération d'identité OIDC/SAML, provisioning SCIM, gestion graphique complète des clés et de l'usage, et garanties multi-instance pour les mécanismes de quota et de cache.

N'annoncez pas ces fonctions comme disponibles avant leur implémentation et leurs tests de charge, sécurité et reprise. Le plan produit détaillé est disponible dans [PLAN_PRODUIT_FREEMIUM_ENTERPRISE_2026_2027.md](PLAN_PRODUIT_FREEMIUM_ENTERPRISE_2026_2027.md).

## 13. Check-lists par rôle

### Développeur intégrateur

- Appeler `/v1/decisions` avant toute opération protégée.
- Fournir des identifiants stables pour sujet et ressource.
- Appliquer les obligations retournées.
- Propager l'identifiant de corrélation dans les logs applicatifs.
- Ne jamais traiter une erreur technique comme une autorisation.

### Administrateur de politiques

- Écrire une intention métier et des cas de test avant l'édition.
- Simuler les cas autorisés et refusés.
- Faire relire les changements sensibles.
- Publier une version documentée et surveiller ses effets.
- Utiliser le retour arrière plutôt qu'une correction non revue en urgence.

### Responsable sécurité / conformité

- Revoir périodiquement les clés API, administrateurs et habilitations.
- Contrôler les décisions sur données sensibles et les exports.
- Vérifier la conservation, la protection et la restauration des audits.
- Examiner les limites de tenant et les dépendances de données avant un usage multi-client.

## 14. Documentation associée

- [Vue d'ensemble](OVERVIEW.md)
- [Architecture technique](ARCHITECTURE.md)
- [Guide de l'interface d'administration](GUIDE_ADMIN_UI.md)
- [API v1](API_V1.md)
- [Référence API](API_REFERENCE.md)
- [Contrat de décision](DECISION_CONTRACT.md)
- [Guide sécurité administrateur](SECURITY_ADMIN_GUIDE.md)
- [Runbook opérations](OPERATIONS_RUNBOOK.md)
- [Plan d'opérations d'autorisation](AUTHORIZATION_OPERATIONS_PLAN.md)
