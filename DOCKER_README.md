# Utilisation avec Docker

Ce document explique comment construire et utiliser l'image Docker pour cette application.

## Prérequis

- Docker doit être installé sur votre machine.

## Build de l'image Docker

Pour construire l'image Docker, exécutez la commande suivante à la racine du projet :

```bash
docker build -t autho-pdp .
```

## Lancement du conteneur Docker

### Lancement simple

Pour lancer le conteneur avec la configuration par défaut (celle présente dans le dépôt), exécutez :

```bash
docker run --rm -p 8080:8080 autho-pdp
```
Le service sera accessible sur `http://localhost:8080`. L'option `--rm` supprime le conteneur lorsque vous l'arrêtez.

### Lancement avec configuration personnalisée

Pour tester des configurations différentes, vous pouvez monter vos propres fichiers de règles et de propriétés dans le conteneur. Cela vous permet d'éditer ces fichiers localement et de voir les changements pris en compte au redémarrage du conteneur, sans avoir à reconstruire l'image.

1.  **Éditez les fichiers locaux** : Modifiez les fichiers `resources/rules.edn` et/ou `resources/pdp-prop.properties` sur votre machine.

2.  **Lancez le conteneur en montant les fichiers** :

    ```bash
    docker run --rm -p 8080:8080 \
      -v "$(pwd)/resources/rules.edn:/usr/src/app/resources/rules.edn" \
      -v "$(pwd)/resources/pdp-prop.properties:/usr/src/app/resources/pdp-prop.properties" \
      autho-pdp
    ```
    *Note pour Windows* : Remplacez `$(pwd)` par `%cd%` si vous utilisez l'invite de commande standard.

## Tester le service

Une fois le conteneur lancé, vous pouvez tester le point d'entrée `/isAuthorized` avec `curl` :

```bash
curl -X POST -H "Content-Type: application/json" \
  -d '{"subject": {"class": "Person", "role": "professeur"}, "resource": {"class": "Diplome"}, "operation": "lire"}' \
  http://localhost:8080/isAuthorized
```
