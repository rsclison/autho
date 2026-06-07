# Autho Deployment Reference

This document describes the current deployment reference for Autho. It does not pretend that every enterprise concern is already solved; it records the practical topology that exists today and the direction for more advanced deployments.

## 1. Current reference topology

The supported baseline is:

- one Autho JVM process;
- one H2 policy store;
- one H2 audit store;
- reverse proxy in front of Autho for TLS termination;
- optional Kafka and LDAP integrations when the deployment needs them;
- Admin UI served by the Clojure application.

This baseline matches the current repo state and the operational guidance in the security docs.

## 2. Environment variables

Minimum:

- `JWT_SECRET`
- `API_KEY`
- `AUDIT_HMAC_SECRET`

Recommended for production-like use:

- `H2_AUDIT_CIPHER_KEY`
- `H2_POLICY_CIPHER_KEY`
- `AUTHO_LICENSE_KEY`
- `API_CLIENT_ID`
- `API_CLIENT_CLASS`
- `API_CLIENT_ROLES`
- `API_CLIENT_TENANTS` or `API_CLIENT_TENANT_ID`
- `AUTHO_DEFAULT_TENANT_ID`
- `AUTHO_ENABLED_PLANES`

`AUTHO_ENABLED_PLANES` controls which deployment planes are exposed by the
instance. Accepted values are `data`, `control`, and `evidence`, separated by
commas or spaces. When unset, Autho enables all three planes. For example:

```bash
AUTHO_ENABLED_PLANES=data,control
```

In this mode, evidence export endpoints return `503 PLANE_DISABLED` until the
evidence plane is re-enabled.

Advanced features:

- `KAFKA_ENABLED`
- `KAFKA_BOOTSTRAP_SERVERS`
- LDAP-related settings used by the PIP

## 3. Ports and traffic flow

- `8080`: Autho HTTP API and admin UI
- `80/443`: reverse proxy public entry point
- `9092`: Kafka when enabled in demos or enterprise-like environments
- `389/636`: LDAP / LDAPS when the PIP uses directory lookups

Recommended traffic flow:

1. clients connect to the reverse proxy over TLS;
2. the reverse proxy forwards HTTP internally to Autho;
3. Autho resolves authentication, tenant scope and decision cache;
4. optional supporting systems feed PIP and cache invalidation paths.

## 4. Environments

### Local development

- single process
- local secrets
- H2 files in the working tree or in-memory test mode

### Demo stack

- Docker-based composition
- enterprise demo license
- Kafka, LDAP and RocksDB support enabled where the demo needs them

### Production baseline

- reverse proxy mandatory
- secrets from a secret manager
- explicit license tier
- operational runbooks available

### Enterprise target

- multi-instance support
- stronger data-plane separation
- more durable storage and distribution of state

The enterprise target is a roadmap direction, not a claim that every element is already complete.

## 5. What to verify after deployment

- `/health`
- `/readiness`
- `/status`
- `/metrics`
- audit integrity verification
- one representative `v1` decision request

The `/status` endpoint now includes a `topology` section that reports the
supported planes, the active planes, the disabled planes, and the canonical
route grouping used by the server.

## 6. Notes for operators

- prefer `/v1/*` for new integrations;
- keep the legacy routes only for compatibility and migration;
- never expose Autho directly without TLS termination in front of it;
- document the exact license tier used by the deployment.
