# Autho — Guide utilisateur de l'interface d'administration

## Accès

L'interface d'administration est une application web accessible à l'adresse :

```
http://<serveur>:8080/admin/ui
```

Elle nécessite une authentification par JWT ou API Key. Un formulaire de connexion est affiché au premier accès (ou après expiration de la session).

---

## Navigation

L'interface est organisée en six sections accessibles depuis le menu latéral :

| Section | Icône | Usage |
|---------|-------|-------|
| **Dashboard** | Activité | Vue temps réel des métriques |
| **Politiques** | Bouclier | Création et gestion des politiques |
| **Simulateur** | Éclair | Test interactif des décisions |
| **Audit** | Horloge | Journal des décisions |
| **Infrastructure** | Base de données | Cache, circuit breakers, actions admin |
| **Paramètres** | Engrenage | Thème, session |

---

## Dashboard

Le tableau de bord affiche en temps réel :

- **Décisions cachées** — nombre de décisions servies depuis le cache local
- **Politiques actives** — nombre de classes de ressources avec une politique définie
- **Taux de cache** — ratio hits/misses sur le cache de décisions
- **Statut du serveur** — version, statut du dépôt de règles, uptime
- **Graphique d'activité** — répartition allow/deny sur les dernières décisions
- **Circuit breakers** — état des PIPs REST (fermé / ouvert / semi-ouvert)
- **Décisions récentes** — dernières entrées du journal d'audit

---

## Politiques

### Vue d'ensemble

Le panneau gauche liste toutes les politiques par classe de ressource. Cliquer sur une classe ouvre son éditeur.

### Créer une politique

1. Cliquer sur le bouton **+** en haut du panneau gauche
2. Saisir le nom de la classe de ressource (ex. `Facture`, `DossierMedical`)
3. L'éditeur s'ouvre avec un squelette JSON à compléter

### Format d'une politique

```json
{
  "strategy": "almost_one_allow_no_deny",
  "rules": [
    {
      "name": "R-ALLOW",
      "priority": 0,
      "operation": "lire",
      "effect": "allow",
      "conditions": [
        ["=", ["Person", "$s", "service"], ["Facture", "$r", "service"]]
      ]
    }
  ]
}
```

**Champs d'une règle :**

| Champ | Type | Description |
|-------|------|-------------|
| `name` | string | Identifiant unique de la règle |
| `priority` | entier | Priorité pour la résolution de conflits (plus grand = priorité plus haute) |
| `operation` | string | Opération ciblée (`"lire"`, `"modifier"`, etc.) — absent = toutes les opérations |
| `effect` | `"allow"` \| `"deny"` | Effet de la règle si ses conditions sont vérifiées |
| `conditions` | tableau | Liste de conditions (toutes doivent être vraies) |

**Stratégie `almost_one_allow_no_deny` :**
La décision est **allow** si au moins une règle `allow` est vérifiée et que sa priorité est supérieure ou égale à celle de la règle `deny` la plus prioritaire. Un `deny` de priorité supérieure à tous les `allow` matche l'emporte.

**Format d'une condition :**
```json
["opérateur", "opérande1", "opérande2"]
```

Un opérande est soit une chaîne littérale, soit une référence à un attribut :
```json
["NomClasse", "$s", "nom-attribut"]   // $s = sujet
["NomClasse", "$r", "nom-attribut"]   // $r = ressource
```

**Opérateurs disponibles :**

| Opérateur | Exemple | Description |
|-----------|---------|-------------|
| `=` | `["=", ["Person", "$s", "service"], ["Facture", "$r", "service"]]` | Égalité |
| `diff` | `["diff", ["Person", "$s", "statut"], "suspendu"]` | Différence |
| `<`, `>`, `<=`, `>=` | `[">=", ["Person", "$s", "clearance"], ["Facture", "$r", "niveau"]]` | Comparaison numérique |
| `in` | `["in", ["Person", "$s", "role"], "DPO,chef_de_service"]` | Appartenance à une liste |
| `notin` | `["notin", ["Person", "$s", "role"], "stagiaire,externe"]` | Non-appartenance |
| `date>` | `["date>", ["Person", "$s", "date-fin"], "2026-01-01"]` | Comparaison de dates ISO |

### Enregistrer une politique

Cliquer sur **Enregistrer** (icône disquette). Si la politique est invalide (JSON malformé, schéma non respecté), un message d'erreur s'affiche.

Chaque enregistrement crée automatiquement une nouvelle version dans l'historique.

### Supprimer une politique

Cliquer sur l'icône **Corbeille** en haut de l'éditeur, puis confirmer.

### Import YAML

L'icône **Upload** permet d'importer une politique depuis un fichier YAML. Le format YAML est équivalent au JSON mais peut être plus lisible pour de grandes politiques.

### Historique des versions

L'icône **Horloge** ouvre le panneau d'historique. Il liste toutes les versions enregistrées avec leur auteur, commentaire et horodatage.

Actions disponibles par version :
- **Voir** — affiche le contenu JSON de cette version
- **Comparer** — ouvre un diff côte-à-côte entre deux versions (icône GitCompare)
- **Restaurer** — rétablit cette version comme version active (rollback)

---

## Simulateur

Le simulateur permet de tester une décision d'autorisation sans impact sur l'audit ni le cache.

### Formulaire de requête

| Champ | Description |
|-------|-------------|
| **Sujet — id** | Identifiant du sujet (ex. `001`) |
| **Sujet — attributs** | Attributs supplémentaires en JSON (optionnel) |
| **Ressource — classe** | Classe de la ressource (liste déroulante des politiques existantes) |
| **Ressource — id** | Identifiant de la ressource |
| **Opération** | Liste déroulante des opérations définies dans la politique sélectionnée |
| **Contexte** | Attributs contextuels en JSON (date, application, etc.) — optionnel |

### Résultat

Après exécution (bouton **Simuler**), l'interface affiche :

- La **décision** (ALLOW en vert / DENY en rouge) avec un badge visuel
- La **liste des règles évaluées** avec pour chacune :
  - son nom et effet
  - son résultat (match / no-match)
  - ses conditions détaillées

Le simulateur appelle `POST /explain` en interne — il retourne exactement la même décision que le moteur en production pour la même requête.

---

## Audit

### Recherche

Le formulaire de recherche accepte les filtres suivants :

| Filtre | Description |
|--------|-------------|
| **Sujet** | Id du sujet (recherche exacte) |
| **Classe ressource** | Classe de la ressource (ex. `Facture`) |
| **Décision** | `allow`, `deny` ou tous |
| **De / À** | Plage de dates (format `YYYY-MM-DD`) |

Cliquer **Rechercher** lance la requête paginée.

### Tableau des résultats

Colonnes affichées : horodatage, sujet, classe ressource, id ressource, opération, décision, règles matchées.

- Cliquer sur un en-tête de colonne pour trier
- Navigation par pages avec les flèches en bas du tableau

### Export CSV

Le bouton **Export CSV** télécharge toutes les entrées correspondant au filtre courant au format CSV.

### Vérification d'intégrité

Le bouton **Vérifier la chaîne** lance une vérification cryptographique de la chaîne d'audit (hash SHA-256 chaîné). Un message de succès ou d'erreur indique si des entrées ont été modifiées ou supprimées.

---

## Infrastructure

### Métriques de cache

Trois barres de progression affichent le taux de hits/misses pour :
- le cache de **décisions**
- le cache de **sujets**
- le cache de **ressources**

### Circuit breakers

Liste des PIPs REST avec leur état :
- **Fermé** (vert) — fonctionnement normal
- **Ouvert** (rouge) — PIP en erreur, requêtes bloquées temporairement
- **Semi-ouvert** (orange) — phase de rétablissement

### Actions administrateur

| Action | Effet |
|--------|-------|
| **Vider le cache** | Supprime toutes les entrées du cache (décisions, sujets, ressources) |
| **Invalider une entrée** | Supprime l'entrée de cache pour un sujet ou une ressource spécifique |
| **Recharger les règles** | Relit `resources/jrules.edn` et met à jour les politiques en mémoire |
| **Recharger les personnes** | Relit l'annuaire LDAP et met à jour `personSingleton` |
| **Réinitialiser** | Réinitialise complètement le PDP (règles + PIPs + cache) |

> Ces actions sont immédiates et sans confirmation. Vider le cache entraîne une baisse temporaire des performances le temps de le repeupler.

---

## Paramètres

### Apparence

Bascule entre le mode **clair** et le mode **sombre**. La préférence est sauvegardée dans le navigateur (localStorage).

### Session

Affiche le token JWT courant (masqué). Le bouton **Se déconnecter** supprime le token et redirige vers la page de connexion.

---

## Raccourcis clavier

| Raccourci | Action |
|-----------|--------|
| `Ctrl+S` (dans l'éditeur de politique) | Enregistrer la politique |

---

## Résolution de problèmes courants

| Symptôme | Cause probable | Action |
|----------|---------------|--------|
| Décision inattendue | Cache périmé | Infrastructure → Invalider l'entrée |
| Politique non prise en compte | Règles non rechargées | Infrastructure → Recharger les règles |
| Attributs sujet manquants | personSingleton obsolète | Infrastructure → Recharger les personnes |
| Circuit breaker ouvert | PIP REST indisponible | Vérifier le service PIP externe |
| `POLICY_NOT_FOUND` | Classe de ressource sans politique | Politiques → Créer une politique pour cette classe |
