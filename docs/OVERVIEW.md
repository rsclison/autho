# Autho — Overview

## What is Autho?

Autho is a **centralised authorisation server** built on the XACML and ABAC (Attribute-Based Access Control) standards. It provides a single, application-independent authorisation decision for all services in an information system, backed by rich, versioned access-control policies.

The core idea: business applications **fully delegate the authorisation decision** to Autho. They embed no access logic of their own — they ask a question (`Can Alice read invoice FAC-123?`) and receive an answer (`allow` or `deny`) together with the list of rules that drove the decision.

---

## Core Concepts

### ABAC — Attribute-Based Access Control

Unlike RBAC (fixed roles), ABAC evaluates dynamic attributes of the subject, the resource, and the context. A rule can express conditions such as:

- the requester's department matches the document's department
- the clearance level is sufficient
- the current date falls within the authorisation window
- the role belongs to a defined list

### XACML Architecture (PDP / PRP / PIP / PAP)

| Component | Role |
|-----------|------|
| **PDP** — Policy Decision Point | Evaluates the request, returns allow/deny |
| **PRP** — Policy Repository Point | Stores and serves policies |
| **PIP** — Policy Information Point | Enriches subject/resource with external attributes |
| **PAP** — Policy Administration Point | Policy management interface (API + UI) |

---

## Key Features

### Authorisation Evaluation
- **`POST /isAuthorized`** — simple allow/deny decision
- **`POST /explain`** — decision with the full list of evaluated rules and their outcome
- **`POST /whoAuthorized`** — which subjects can perform an operation on a resource?
- **`POST /whatAuthorized`** — which resources can a subject access?
- **Batch evaluation** (`/v1/batch`) — up to 100 requests in a single call

### Policy Management
- Full CRUD per resource class
- YAML import (declarative format)
- **Automatic versioning** — every change preserves history
- **Rollback** to any previous version
- **Diff** between two versions

### Time-Travel
- **`POST /isAuthorized-at-time`** — decision as it would have been at a past instant T
- **`POST /who-was-authorized-at`** — who was authorised at T?
- **`POST /what-could-access-at`** — which resources were accessible at T?

A rare capability among authorisation servers, made possible by the immutable event log in Kafka.

### Audit
- Every decision is recorded (subject, resource, operation, decision, matched rules)
- Search by subject, class, decision, date range
- Cryptographic integrity verification of the audit chain (chained SHA-256 hashes)
- CSV export

### Cache & Performance
- Multi-level cache: decisions, subjects, resources
- Targeted invalidation by subject, resource, or class
- Prometheus metrics (`/metrics`)

### Observability
- OpenTelemetry traces (OTLP)
- Micrometer / Prometheus metrics
- Structured logging (SLF4J / Logback)
- Circuit breakers on HTTP PIPs
- Real-time dashboard in the admin UI

### Licensing

Autho uses an **open-core** model. The core PDP (decisions) is free; advanced features require a Pro or Enterprise licence activated via the `AUTHO_LICENSE_KEY` environment variable.

| Feature | Free | Pro | Enterprise |
|---|:---:|:---:|:---:|
| `isAuthorized`, `whoAuthorized`, `whatAuthorized` | ✓ | ✓ | ✓ |
| Audit trail with HMAC integrity chain | — | ✓ | ✓ |
| Policy versioning, diff & rollback | — | ✓ | ✓ |
| `explain` & `simulate` | — | ✓ | ✓ |
| Prometheus metrics (`/metrics`) | — | ✓ | ✓ |
| Kafka PIP / RocksDB | — | — | ✓ |
| Multi-instance cache synchronisation | — | — | ✓ |

Without `AUTHO_LICENSE_KEY`, the server starts in Free mode — no error, no crash. A gated endpoint returns HTTP 402 with a clear message when accessed without the required licence.

---

## Authorisation Request Format

```json
{
  "subject":   { "id": "001", "class": "Person" },
  "resource":  { "id": "FAC-123", "class": "Facture" },
  "operation": "read",
  "context":   { "date": "2026-03-23" }
}
```

### `isAuthorized` response
```json
{
  "status": "success",
  "data": { "result": true }
}
```

### `explain` response
```json
{
  "status": "success",
  "data": {
    "result": true,
    "rules": [
      { "name": "R1", "effect": "allow", "priority": 0 }
    ]
  }
}
```

---

## Policy Format

A policy is bound to a **resource class**. It contains a set of rules and a conflict-resolution strategy.

```json
{
  "strategy": "almost_one_allow_no_deny",
  "rules": [
    {
      "name": "R-ALLOW",
      "priority": 0,
      "operation": "read",
      "effect": "allow",
      "conditions": [
        ["=",  ["Person", "$s", "service"],        ["Facture", "$r", "service"]],
        [">=", ["Person", "$s", "clearance-level"], ["Facture", "$r", "required-level"]]
      ]
    },
    {
      "name": "R-DENY-ARCHIVE",
      "priority": 10,
      "operation": "read",
      "effect": "deny",
      "conditions": [
        ["=", ["Facture", "$r", "status"], "archive"]
      ]
    }
  ]
}
```

### Strategy `almost_one_allow_no_deny`

The decision is **allow** if and only if:
- at least one `allow` rule matches, **and**
- the highest priority among matching `allow` rules is ≥ the highest priority among matching `deny` rules

A `deny` rule with higher priority therefore overrides a lower-priority `allow`.

### Condition Format

Each condition is a triple `[operator operand1 operand2]`.

An operand is either a literal value (`"archive"`, `5000`) or an attribute reference:
`[ClassName $s|$r attribute-name]` where `$s` refers to the subject and `$r` to the resource.

**Available operators:** `=`, `diff`, `<`, `>`, `<=`, `>=`, `in`, `notin`, `date>`

---

## Authentication

Two methods are supported on all protected endpoints:

| Method | Header | Usage |
|--------|--------|-------|
| API Key | `Authorization: X-API-Key <key>` | Internal services, machine-to-machine calls |
| JWT Bearer | `Authorization: Token <jwt>` | Applications with a user identity |

The endpoints `/health`, `/readiness`, `/status`, and `/metrics` are public.

---

## Quick Start

```bash
# Required environment variables
export JWT_SECRET="secret-key-min-32-characters"
export API_KEY="internal-api-key"

# Start
lein run

# Health check
curl http://localhost:8080/health
# → {"status":"UP"}
```

The administration UI is available at `http://localhost:8080/admin/ui`.

---

## Deployment Dependencies

| Service | Role | Required |
|---------|------|----------|
| **LDAP** | Source of person attributes | Yes if `person.source=ldap` |
| **Kafka** | Business object streams for PIPs | No (disable via `KAFKA_ENABLED=false`) |
| **RocksDB** | Local cache for Kafka objects | Automatic when Kafka is enabled |
| H2 database | Audit log and policy version storage | Embedded, no installation required |

For development, a `docker-compose.yml` file is provided that starts OpenLDAP and Kafka together with their administration UIs.
