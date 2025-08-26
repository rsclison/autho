# AGENTS.md

Bonjour agent IA ! Ce fichier contient des informations pour vous aider à comprendre et à travailler sur ce projet.

## Vue d'ensemble du projet

Ce projet est un moteur d'autorisation basé sur des règles, écrit en Clojure. Il utilise Leiningen comme outil de build et de gestion des dépendances. La logique principale semble être centrée sur l'évaluation des politiques (PDP - Policy Decision Point) et des règles pour accorder ou refuser l'accès aux ressources.

## Stack technique

- **Langage**: Clojure
- **Outil de build**: Leiningen

## Structure du projet

- `src/autho/`: Contient le code source principal de l'application.
  - `attfun.clj`: Contient des fonctions d'opérateurs utilisées dans les règles d'autorisation.
  - `pdp.clj`, `prp.clj`, `rule.clj`: Semblent contenir la logique principale du moteur de règles.
- `test/autho/`: Contient les tests pour l'application. Les tests sont écrits avec `clojure.test`.
- `resources/`: Contient des fichiers de configuration et de données, comme des exemples de règles (`rules.edn`) et de politiques.
- `project.clj`: Le fichier de définition du projet Leiningen.
- `lein`: Un script wrapper pour Leiningen. Utilisez-le pour exécuter les commandes `lein`.

## Comment exécuter les tests

Pour lancer la suite de tests, exécutez la commande suivante à la racine du projet :

```bash
./lein test
```

**Important**: Assurez-vous d'utiliser le script `./lein` fourni dans le dépôt plutôt que la commande `lein` de votre système pour garantir la cohérence de l'environnement.

Pendant l'exécution des tests, vous pourriez voir des avertissements concernant la redéfinition de vars (par exemple, `WARNING: = already refers to: #'clojure.core/=...`). Ceux-ci semblent être normaux pour ce projet et peuvent être ignorés s'ils ne provoquent pas d'erreurs.
