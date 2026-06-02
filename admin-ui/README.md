# Autho Admin UI

Interface d'administration React utilisée par Autho.

## Ce que fait l'UI

- consultation du statut serveur
- navigation dans les politiques et leurs versions
- audit et vérification de la chaîne d'intégrité
- écrans de gouvernance et de revue

L'application est servie par Autho sur :

- `GET /admin/ui`
- `GET /admin/ui/*`

## Développement local

Depuis le répertoire `admin-ui` :

```bash
npm install
npm run dev
```

## Build

```bash
npm run build
```

## Tests

```bash
npm test
```

## Remarques

- L'UI consomme les API Autho exposées par le backend principal.
- `GET /admin/audit/verify` est l'endpoint utilisé pour la vérification de la chaîne d'audit.
- Le build de production est servi par le backend lorsque les assets sont présents sous `resources/public/admin/`.
