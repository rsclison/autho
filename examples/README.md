# Exemples de Clients

Ce répertoire contient des exemples de clients dans différents langages pour interagir avec le service d'autorisation.

Les exemples montrent comment utiliser les trois principaux points d'entrée de l'API :
- `POST /isAuthorized`: Vérifie si un sujet donné est autorisé à effectuer une opération sur une ressource donnée.
- `POST /whoAuthorized`: Renvoie les conditions sur le sujet pour être autorisé à effectuer une opération sur une ressource donnée.
- `POST /whichAuthorized`: Renvoie les conditions sur la ressource pour qu'un sujet donné soit autorisé à effectuer une opération.

Avant d'exécuter ces exemples, assurez-vous que le service d'autorisation est en cours d'exécution sur `http://localhost:8080`. Vous pouvez le démarrer avec la commande suivante à la racine du projet :

```bash
./lein run
```

## Client Clojure

Le client Clojure (`client.clj`) utilise la bibliothèque `clj-http`.

### Prérequis

`clj-http` est déjà une dépendance du projet principal. Vous pouvez l'exécuter en utilisant `lein`.

### Exécution

```bash
lein run -m client
```

## Client Python

Le client Python (`client.py`) utilise la bibliothèque `requests`.

### Prérequis

Installez la bibliothèque `requests` :
```bash
pip install requests
```

### Exécution

```bash
python client.py
```

## Client Java

Le client Java (`Client.java`) utilise le `HttpClient` intégré de Java (depuis Java 11).

### Prérequis

Un JDK 11 ou plus récent est nécessaire.

### Exécution

Compilez et exécutez le fichier Java :
```bash
javac Client.java
java Client
```
