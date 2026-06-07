# Autho Backup and Restore

This document describes the current backup and restore approach for Autho's H2-backed stores. It is intentionally explicit because the repository still uses embedded persistence for key operational data.

## 1. What must be backed up

- policy database files;
- audit database files;
- optional demo data when the deployment uses Kafka, RocksDB or LDAP-backed fixtures;
- configuration files needed to reproduce the deployment;
- license and secret references, not the secrets themselves.

## 2. Before you back up

Recommended precautions:

- stop the server or put it in a maintenance window;
- ensure no write activity is occurring on the H2 files;
- record the exact release version and configuration hash;
- verify the backup destination is on separate storage.

## 3. Backup procedure

At a minimum:

1. stop Autho cleanly;
2. copy the H2 audit store directory;
3. copy the H2 policy store directory;
4. archive the configuration used to start the server;
5. record the backup timestamp and version in the change log.

Example shell outline:

```bash
tar -czf autho-backup-$(date +%Y%m%d-%H%M%S).tar.gz \
  resources/auditdb* \
  resources/h2db* \
  resources/pdp-prop.properties
```

Adapt the paths to your deployment if the store locations differ.

## 4. Restore procedure

1. stop Autho;
2. move the current store files out of the way;
3. restore the archived files into the original locations;
4. ensure file permissions match the service account;
5. restart Autho;
6. verify `/readiness`, `/status`, the audit chain and one representative decision.

## 5. Restore validation

After restore, check:

- audit entries are readable;
- policy versions are present;
- a known decision still returns the expected result;
- the admin UI loads and the expected tier is active.

## 6. Operational notes

- do not treat an untested restore as a valid backup;
- if you change the H2 cipher or secret material, old files may require the matching historical configuration to be readable;
- if a future deployment moves to a different persistence backend, keep this document updated rather than copying assumptions forward.
