# PostgreSQL — guide de compatibilité

## Comportement par défaut

Autho démarre toujours avec H2 si `AUTHO_DB_KIND` est absent ou vaut `h2`.
Cette compatibilité préserve les chemins `AUTHO_POLICY_DB_PATH`,
`H2_POLICY_CIPHER_KEY` et `H2_AUDIT_CIPHER_KEY` existants.

## Activer PostgreSQL

```bash
export AUTHO_DB_KIND=postgres
export AUTHO_DATABASE_URL=jdbc:postgresql://localhost:5432/autho
export AUTHO_DATABASE_USER=autho
export AUTHO_DATABASE_PASSWORD=autho-dev-password
```

Le conteneur de développement est fourni dans `docker-compose.yml` :

```bash
docker compose up -d postgres
```

Les tables de politiques, versions, impact, profil de risque, API keys, usage,
audit et projection ReBAC sont initialisées au démarrage. Les types et DDL sont
choisis selon le dialecte ; les clauses PostgreSQL `ON CONFLICT` sont utilisées
pour le compteur d'usage, tandis que H2 conserve son `MERGE` historique.

## Audit séparé

Par défaut, l'audit partage la base PostgreSQL principale. Pour l'isoler :

```bash
export AUTHO_AUDIT_DATABASE_URL=jdbc:postgresql://audit-db.internal:5432/autho_audit
export AUTHO_AUDIT_DATABASE_USER=autho_audit
export AUTHO_AUDIT_DATABASE_PASSWORD='secret'
```

## Migration depuis H2

Il n’existe pas de migration automatique des données H2. Procéder ainsi :

1. arrêter les écritures et exporter les politiques, versions, audit et tuples ;
2. démarrer une base PostgreSQL vide avec les variables ci-dessus ;
3. démarrer Autho une fois pour créer le schéma ;
4. importer les données avec un script contrôlé et vérifier la chaîne d’audit ;
5. tester les décisions et la projection ReBAC avant de router le trafic.

La réversibilité est simple tant que H2 reste intacte : retirer
`AUTHO_DB_KIND=postgres` et redémarrer sur le stockage H2 précédent.

## Validation

La suite standard valide H2. Pour valider PostgreSQL localement :

```bash
AUTHO_DB_KIND=postgres \
AUTHO_DATABASE_URL=jdbc:postgresql://localhost:5432/autho \
AUTHO_DATABASE_USER=autho \
AUTHO_DATABASE_PASSWORD=autho-dev-password \
AUDIT_HMAC_SECRET='un-secret-d-audit-de-test-d-au-moins-32-caracteres' \
JWT_SECRET='autho-test-secret-which-is-32-bytes' \
API_KEY='autho-test-api-key-which-is-32-bytes' \
./lein test autho.rebac-test autho.audit-test autho.api-keys-test autho.usage-test
```
