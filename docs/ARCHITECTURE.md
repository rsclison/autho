# Autho — Technical Architecture

## Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                          Client Applications                          │
│               (REST services, batch jobs, web applications)          │
└──────────────────────────────┬───────────────────────────────────────┘
                               │  HTTP/JSON
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                          Autho  :8080                                 │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                      Middleware Stack                             │ │
│  │  auth · rate-limit · request-size · validation · metrics ·      │ │
│  │  tracing · error-handling · graceful-shutdown                    │ │
│  └────────────────────────────┬────────────────────────────────────┘ │
│                               │                                       │
│     ┌─────────────────────────┼──────────────────────────┐           │
│     │                         │                           │           │
│  ┌──▼──────────┐   ┌─────────▼──────────┐   ┌──────────▼────────┐  │
│  │    PDP      │   │       PAP           │   │    Admin API      │  │
│  │ ─────────── │   │  ────────────────── │   │  ───────────────  │  │
│  │ evalRequest │   │  policy CRUD        │   │  cache mgmt       │  │
│  │ isAuthorized│   │  YAML import        │   │  reinit/reload    │  │
│  │ explain     │   │  versions/rollback  │   │  audit search     │  │
│  │ whoAuthorized│  │  diff               │   │  PIP schema       │  │
│  │ whatAuthorized  └────────┬───────────┘   └───────────────────┘  │
│  │ time-travel │            │                                        │
│  └──────┬──────┘            │                                        │
│         │              ┌────▼──────┐                                 │
│         │              │    PRP    │  ←── resources/jrules.edn       │
│         │              │ ───────── │  ←── PUT /policy/:class         │
│         │              │ policies  │  ←── POST /import-yaml          │
│         │              │ fillers   │                                  │
│         │              │ pips cfg  │                                  │
│         │              └───────────┘                                 │
│         │                                                             │
│         ▼  enrich-request (fillers + cache)                          │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │                       PIP Layer                               │    │
│  │  fillPerson (LDAP singleton)  │  Kafka unified  │  REST PIP  │    │
│  │  CSV PIP   │  Internal PIP   │  Java PIP       │  Cache L2  │    │
│  └──────┬─────────────┬──────────────────┬─────────────────────┘    │
│         │             │                  │                            │
└─────────┼─────────────┼──────────────────┼────────────────────────────┘
          │             │                  │
          ▼             ▼                  ▼
      LDAP :389     Kafka :9092        External REST
                    RocksDB            services
```

---

## Internal Components

### `autho.pdp` — Policy Decision Point

The core evaluation engine. Receives an enriched request and returns a decision.

**Evaluation flow:**
1. Retrieve the policy for the resource class (`prp/getGlobalPolicy`)
2. Enrich subject and resource via `enrich-request` (fillers + cache)
3. Filter rules applicable to the requested operation (`applicable-rules`)
4. Evaluate each rule (`rule/evaluateRule`)
5. Resolve conflicts between allow/deny rules (`resolve-conflict`)
6. On deny, attempt delegation (if configured)
7. Record the decision in the audit log

**Exposed functions:**

| Function | Description |
|----------|-------------|
| `evalRequest` | Core evaluation, returns `{:result bool :rules [...]}` |
| `isAuthorized` | HTTP wrapper with decision cache |
| `explain` | Like `isAuthorized` but returns matched rules |
| `simulate` | Evaluation with no audit recording and no caching |
| `whoAuthorized` | Subjects able to perform an operation |
| `whatAuthorized` | Resources accessible to a subject |

### `autho.prp` — Policy Repository Point

Holds all policies, PIP declarations, and fillers in memory (Clojure atoms).

**Main atoms:**

| Atom | Contents |
|------|----------|
| `policiesMap` | `{"Facture" {:global {:rules [...] :strategy ...}}, ...}` |
| `pips` | List of PIP declarations (loaded from `resources/pips.edn`) |
| `subjectFillers` | Class → subject filler map (loaded from `resources/fillers.edn`) |
| `resourceFillers` | Class → resource filler map |
| `personSingleton` | List of persons loaded from LDAP |

### `autho.jsonrule` — Rule Evaluation

Evaluates rules in the `:conditions` format.

**Condition format:**
```clojure
[operator  [ClassName $s|$r attribute]  operand2]
```

Examples:
```clojure
[=   [Person $s service]         [Facture $r service]]
[>=  [Person $s clearance-level] [Facture $r required-level]]
[in  [Person $s role]            "DPO,manager,legal-counsel"]
[=   [Facture $r status]         "archive"]
```

**Resolving an operand `[ClassName $var attribute]`:**
1. Look up the attribute directly in the object (after filler enrichment)
2. If absent, call `attfun/findAndCallPip` → `prp/findPip` → `pip/callPip`
3. If the PIP returns a map (e.g. REST PIP), extract the target attribute from it
4. Coerce numbers to strings for comparison (`coerce-str`)

### `autho.pip` — PIP Dispatch

Clojure multimethod dispatching on `:type`.

| Type | Implementation |
|------|----------------|
| `:rest` | `GET {url}/{id}` or `POST {url}`, returns `(:body response)` (parsed JSON map) |
| `:kafka-pip-unified` | `kpu/query-pip class id` → `(get result (keyword att))` |
| `:kafka-pip` | `kafka-pip/query-pip class id` with optional fallback |
| `:csv` | CSV file read, search by id column |
| `:internal` | Calls a Clojure function in `autho.attfun` by name |
| `:java` | Calls `resolveAttribute` on a Java instance |

### `autho.kafka-pip-unified` — Unified Kafka PIP

Consumes a single Kafka topic (`business-objects-compacted`). Messages carry a `class` field for routing to the appropriate Column Family.

**Data flow:**
```
Kafka topic  →  dedicated consumer thread  →  RocksDB (one Column Family per class)
                                                        ↑
                                              query-pip(class, id)
```

**Message format:**
```json
{ "class": "Facture", "id": "FAC-123", "amount": 4500, "service": "service1" }
```

`class` and `id` fields are removed before storage. Updates are merged (merge-on-read).

**State management:**
- `db-state` — atom holding the RocksDB instance and Column Family handles
- `consumer-handle` — atom holding the consumer thread and its stop function

### `autho.attfun` — Attribute Functions and Operators

Contains the comparison operators used in rule evaluation and the internal fillers.

**Operators:**

| Operator | Signature | Behaviour |
|----------|-----------|-----------|
| `=` | `(= a b)` | Equality (delegates to `clojure.core/=`) |
| `diff` | `(diff a b)` | Inequality |
| `<`, `>`, `<=`, `>=` | `(op a b)` | Numeric comparison via `edn/read-string` |
| `in` | `(in val set)` | Membership; `set` may be a comma-separated string `"a,b,c"` |
| `notin` | `(notin val set)` | Non-membership |
| `date>` | `(date> d1 d2)` | ISO date comparison (`yyyy-MM-dd`) |

**Internal fillers:**
- `fillPerson` — merges the subject with LDAP person data from `prp/personSingleton`
- `internalFiller` — dispatches to a named filler function

### `autho.local-cache` — Multi-Level Cache

In-memory cache with three independent stores:

| Cache | Key | Contents |
|-------|-----|----------|
| Decisions | `(subject-id, resource-class, resource-id, operation)` | Decision result |
| Subjects | `subject-id` | Enriched subject attributes |
| Resources | `(resource-class, resource-id)` | Enriched resource attributes |

Can be fully cleared or invalidated per entry via the admin API.

### `autho.audit` — Decision Log

Every decision is persisted in an embedded H2 database with:
- subject, resource class, resource id, operation, decision, matched rules
- timestamp, chained SHA-256 hash (for integrity verification)

Search API with pagination, filters, and CSV export.

### `autho.delegation` — Authorisation Delegation

When a subject is not directly authorised, the PDP checks whether they hold a delegation from another authorised subject. Delegation is recursive with cycle detection.

---

## External Service Communication

### LDAP

**Role:** source of person attributes (role, department, clearance level, etc.)

**Connection properties:**
```properties
ldap.server        = localhost
ldap.port          = 389
ldap.connectstring = cn=admin,dc=example,dc=com
ldap.password      = admin
ldap.basedn        = ou=people,dc=example,dc=com
ldap.filter        = (objectClass=inetOrgPerson)
ldap.attributes    = uid,cn,role,service,seuil,clearance-level
```

**Integration:**
- At startup: `person/loadPersons` loads the directory into memory (`prp/personSingleton`)
- During evaluation: `attfun/fillPerson` merges the subject with in-memory LDAP data
- Library: `puppetlabs/clj-ldap`
- Hot reload: `POST /admin/reload_persons`

**Flow:**
```
Startup  → ldap/init → ldap/search → person/loadPersons → prp/personSingleton
Request  → callFillers → fillPerson → merge(subject, LDAP person)
```

### Kafka + RocksDB

**Role:** enrichment of business objects (invoices, contracts, etc.) with their current attributes

**Architecture:**
```
Business producers  →  Kafka topic "business-objects-compacted"
                                   ↓
                         Dedicated consumer thread
                                   ↓
                         RocksDB (Column Families per class)
                         /tmp/rocksdb/shared
                                   ↑
                         callPip :kafka-pip-unified
```

**Configuration (`resources/pips.edn`):**
```clojure
{:type :kafka-pip-unified
 :kafka-topic "business-objects-compacted"
 :kafka-bootstrap-servers "localhost:9092"
 :classes ["Facture" "Contrat" "EngagementJuridique"]}
```

**Kafka message format:**
```json
{
  "class":   "Facture",
  "id":      "FAC-123",
  "amount":  4500,
  "service": "service1",
  "status":  "approved"
}
```

**Disabling Kafka:** `KAFKA_ENABLED=false` disables the `:kafka-pip` type (not `:kafka-pip-unified`).

**RocksDB:** embedded database, no external server required. Path: `kafka.pip.rocksdb.path`.

### External REST PIPs

**Role:** attribute enrichment from existing application services

**Configuration:**
```clojure
{:class "Report"
 :pip {:type       :rest
       :url        "http://report-service:8080/api/reports"
       :verb       "get"       ; or "post"
       :timeout-ms 5000}}      ; optional, default 10 000 ms
```

**Behaviour:**
- `GET`: calls `{url}/{id}` — expects a full JSON object in response
- `POST`: sends the object context as body
- Per-URL circuit breaker (Diehard) — opens after N consecutive failures
- Shared HTTP connection pool (clj-http + connection manager)
- Per-PIP Prometheus metrics

**Return value:** the JSON body is parsed into a keyword-keyed map. The requested attribute is extracted from that map.

### H2 Database (audit and versioning)

**Role:** persistence of the decision audit log and policy versions

- Embedded database (file `resources/h2db`)
- Accessed via `clojure.java.jdbc`
- No external service required

---

## Middleware Stack (application order)

```
incoming request
    ↓
wrap-graceful-shutdown   — rejects new requests during shutdown
    ↓
wrap-error-handling      — catches all exceptions, returns structured JSON
    ↓
tracing/wrap-tracing     — injects OpenTelemetry trace id
    ↓
wrap-metrics             — Prometheus request counter
    ↓
wrap-request-size-limit  — enforces maximum body size
    ↓
wrap-rate-limit          — per-IP rate limiting
    ↓
wrap-input-validation    — validates JSON body
    ↓
auth/wrap-authentication — verifies JWT or API Key, injects :identity into request
    ↓
handler (PDP / PAP / Admin)
```

---

## Configuration Files

| File | Contents |
|------|----------|
| `resources/pdp-prop.properties` | Main configuration (LDAP, Kafka, paths) |
| `resources/jrules.edn` | Initial policies loaded at startup |
| `resources/pips.edn` | PIP declarations |
| `resources/fillers.edn` | Subject/resource filler configuration |
| `resources/delegations.edn` | Delegation declarations |
| `resources/policySchema.json` | JSON Schema used to validate submitted policies |

---

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `JWT_SECRET` | Yes | JWT signing key (min. 32 characters) |
| `API_KEY` | Yes | API key for trusted clients |
| `KAFKA_ENABLED` | No | Set to `false` to disable the Kafka PIP (default `true`) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | No | OTLP endpoint for distributed traces |

---

## Deployment

### Standalone JAR

```bash
lein uberjar
java -jar target/uberjar/autho-0.1.0-SNAPSHOT-standalone.jar
```

### Development Environment

```bash
# Start LDAP + Kafka
docker-compose -f docker/docker-compose.yml up -d

# Start the server
JWT_SECRET=dev-secret API_KEY=dev-key lein run
```

### Integration Tests

```bash
# Requires docker-compose up -d
lein test :integration
```

Integration tests create their own Kafka topics, RocksDB databases, and HTTP server — they do not share state with a running production server.
