# Autho Operations Runbook

This document is the operator entry point for running Autho in a durable way. It groups the reference deployment, backup / restore, upgrade / rollback and secret-handling procedures in one place.

## 1. What this runbook covers

- current reference deployment and environment variables;
- health verification after startup;
- backup and restore for H2-backed stores;
- upgrade and rollback;
- secret rotation and operational guardrails.

For a security-focused deployment checklist, see `docs/SECURITY_ADMIN_GUIDE.md` and `docs/SECURITY_TARGET.md`.

## 2. Reference deployment

The current reference deployment is:

- Autho server on port `8080`;
- reverse proxy in front of Autho for TLS termination;
- H2-backed policy and audit stores;
- optional Kafka / RocksDB / LDAP support for advanced demos and enterprise features.

Use `docs/DEPLOYMENT_REFERENCE.md` for the topology and environment-variable matrix.

## 3. Startup checklist

Before starting the server, ensure these minimum checks:

- `JWT_SECRET` is set and at least 32 characters long;
- `API_KEY` is set and at least 32 characters long;
- `AUDIT_HMAC_SECRET` is set and at least 32 characters long;
- if production-grade encryption at rest is required, `H2_AUDIT_CIPHER_KEY` and `H2_POLICY_CIPHER_KEY` are defined;
- the selected license tier matches the intended usage.

Typical startup:

```bash
export JWT_SECRET="..."
export API_KEY="..."
export AUDIT_HMAC_SECRET="..."
./lein run
```

## 4. Post-start verification

Verify the server is alive:

```bash
curl -s http://localhost:8080/health
curl -s http://localhost:8080/readiness
curl -s http://localhost:8080/status
```

For production or staging, also verify:

- `/metrics` is reachable from the monitoring zone;
- the audit verification endpoint works with a governance identity;
- the admin UI loads and can authenticate with the intended mode.

## 5. Backup and restore

Backup and restore procedures are documented in `docs/BACKUP_RESTORE.md`. Use that document as the source of truth for:

- audit store backup;
- policy store backup;
- controlled restore;
- verification after restore.

## 6. Upgrade and rollback

Upgrade and rollback procedures are documented in `docs/UPGRADE_ROLLBACK.md`.

Operational rule:

- if a change is only documentation or configuration, upgrade in place;
- if a change can affect stored data or policy compatibility, take a full backup first and validate the rollback path before production use.

## 7. Secret rotation

The current runtime requires a restart for secret rotation. Rotate:

- `JWT_SECRET`
- `API_KEY`
- `AUDIT_HMAC_SECRET`
- `POLICY_BUNDLE_HMAC_SECRET`

After rotation:

- expect in-flight JWTs to become invalid if the JWT secret changed;
- expect the audit chain to require the correct historical secret to verify old segments;
- record the rotation time in the operational log or change-management system.

## 8. Operator exit criteria

Autho is ready for routine operation only when:

- startup is repeatable from a clean environment;
- the backup and restore path has been exercised;
- rollback has been validated;
- the chosen license tier is documented for the environment;
- the current limitations are accepted explicitly and not inferred.
