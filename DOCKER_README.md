# Utilisation avec Docker

Ce document explique comment construire et utiliser l'image Docker pour cette application.

## Prérequis

- Docker doit être installé sur votre machine.
- Leiningen doit être installé localement pour utiliser les commandes `lein docker`.

## Build de l'image Docker

Le projet est configuré avec le plugin `lein-docker`. Pour construire l'image, exécutez :

```bash
lein docker build
```
Cette commande utilise le nom d'image `autho-pdp` configuré dans `project.clj`.

## Push de l'image

Si vous avez les droits nécessaires sur un registre Docker (par exemple, Docker Hub), vous pouvez pousser l'image avec :

```bash
lein docker push
```

## Lancement du conteneur Docker

Pour lancer le conteneur, vous pouvez utiliser la commande `docker run` standard :

```bash
docker run --rm -p 8080:8080 autho-pdp
```
Le service sera accessible sur `http://localhost:8080`.

### Lancement avec configuration personnalisée

Pour utiliser des fichiers de configuration locaux, vous pouvez les monter dans le conteneur :

```bash
docker run --rm -p 8080:8080 \
  -v "$(pwd)/resources/rules.edn:/usr/src/app/resources/rules.edn" \
  -v "$(pwd)/resources/pdp-prop.properties:/usr/src/app/resources/pdp-prop.properties" \
  autho-pdp
```
