# Autho Upgrade and Rollback

This document defines the current reference process for upgrading and rolling back Autho.

## 1. Scope

The process covers:

- code upgrades;
- documentation-only releases;
- configuration changes;
- license-tier changes;
- changes that may affect stored policy or audit data.

## 2. Preconditions

Before any upgrade:

- take a backup using `docs/BACKUP_RESTORE.md`;
- confirm the exact target version;
- confirm whether the release changes stored data or only runtime behavior;
- verify the rollback artifact is available;
- schedule a maintenance window if the deployment is not disposable.

## 3. Upgrade procedure

1. read the release notes;
2. stop the current server cleanly;
3. deploy the new code or build artifact;
4. update configuration only if the release notes require it;
5. start Autho;
6. verify `/health`, `/readiness`, `/status` and a representative `v1` decision;
7. verify audit access and the admin UI.

Example verification:

```bash
curl -s http://localhost:8080/health
curl -s http://localhost:8080/readiness
curl -s http://localhost:8080/status
```

## 4. Rollback procedure

Rollback is the reverse of the upgrade path:

1. stop Autho;
2. restore the previous build or package;
3. restore the previous configuration if it changed;
4. if needed, restore the data backup taken before the upgrade;
5. restart Autho;
6. verify the same health and decision checks again.

## 5. Rollback decision rule

Rollback immediately if any of the following occurs:

- the server does not reach readiness;
- a known decision changes unexpectedly;
- audit verification fails;
- the admin UI or the `v1` API becomes unavailable;
- the change introduced unplanned data migration and the migration cannot be reversed.

## 6. Notes

- documentation-only releases should still pass the release checklist;
- when a future version introduces schema migration support, this document must be expanded with exact migration and rollback semantics;
- do not assume that every change is safe to roll forward without a tested recovery path.
