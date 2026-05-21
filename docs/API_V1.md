# Autho API v1 Documentation

RESTful API v1 for the autho authorization server.

## Base URL

```
http://localhost:8080/v1
```

## Authentication

All endpoints require authentication via JWT token or API key.

**Headers:**
```
Authorization: Token <jwt-token>
X-API-Key: <api-key>
```

### Subject Resolution

Autho does not trust an arbitrary `subject` value sent by an unauthenticated caller.

With `Authorization: Token <jwt-token>`, the effective subject is derived from the verified JWT identity.

With `X-API-Key`, the effective subject is the application bound to the API key on the server side. The body field `subject` is ignored for normal API-key calls, so a caller cannot impersonate another application by posting:

```json
{"subject": {"id": "app-A", "class": "Application"}}
```

The API-key application identity is configured with:

```bash
API_CLIENT_ID=app-A
API_CLIENT_CLASS=Application
API_CLIENT_ROLES=policy-admin,policy-deployer
```

For service-to-service authorization, write policies against that application subject:

```clojure
{:name "APP-A-CAN-READ-B"
 :resourceClass "InformationB"
 :operation "read"
 :conditions [[= [Application $s client-id] "app-A"]]
 :effect "allow"
 :priority 0}
```

If a trusted backend must evaluate permissions for a user on behalf of an application, the backend must authenticate the application first and then use a server-side delegated/composite subject. The internal compatibility flag `:allow-subject-delegation true` is reserved for trusted Ring identities and must not be exposed as a client-controlled parameter.

### Governance Roles

Policy governance write endpoints require an authenticated identity with a governance role. `governance-admin` grants every governance operation. More specific roles are:

| Role | Allows |
|------|--------|
| `policy-admin` | Create, update, delete, and import policies |
| `risk-profile-admin` | Upsert and delete policy impact risk profiles |
| `policy-reviewer` | Review persisted policy impact analyses |
| `policy-deployer` | Roll out approved impact analyses and perform rollbacks |
| `relation-admin` | Create and delete ReBAC relation tuples |

For API-key clients, roles are loaded from `API_CLIENT_ROLES` as a comma-separated list. The default is `governance-admin` for compatibility; production deployments should set least-privilege roles explicitly.

## Response Format

All responses follow a standard format:

Authorization decision endpoints expose the canonical decision contract documented in `docs/DECISION_CONTRACT.md`. New clients should prefer `allowed?`, `decisionType`, `effectiveSubject`, `matchedRuleNames`, `strategy`, and `policySource`. Compatibility fields such as `allowed`, `decision`, `results`, and `matchedRules` are still returned.

### Success Response
```json
{
  "status": "success",
  "data": {
    // Response data
  },
  "timestamp": "2026-03-18T10:30:00Z"
}
```

### Paginated Response
```json
{
  "status": "success",
  "data": [...],
  "meta": {
    "page": 1,
    "per-page": 20,
    "total": 150,
    "total-pages": 8
  },
  "links": {
    "self": "/v1/policies?page=1&per-page=20",
    "next": "/v1/policies?page=2&per-page=20",
    "prev": null,
    "first": "/v1/policies?page=1&per-page=20",
    "last": "/v1/policies?page=8&per-page=20"
  },
  "timestamp": "2026-03-18T10:30:00Z"
}
```

### Error Response
```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Invalid request body",
    "details": [
      {
        "field": "subject.id",
        "message": "required"
      }
    ],
    "timestamp": "2026-03-18T10:30:00Z"
  }
}
```

## Endpoints

### Authorization Endpoints

#### POST /v1/authz/decisions

Make an authorization decision.

For API-key calls, `subject` in this example is not the source of truth. The evaluated subject is the application identity bound to the API key. For JWT calls, the evaluated subject is derived from the JWT identity.

**Request:**
```json
{
  "subject": {
    "id": "user123",
    "attributes": {
      "role": "admin",
      "department": "engineering"
    }
  },
  "resource": {
    "class": "Document",
    "id": "doc456",
    "attributes": {
      "owner": "user123",
      "classification": "internal"
    }
  },
  "operation": "write"
}
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "allowed": true,
    "decision": "allow",
    "results": ["pol-001"]
  },
  "timestamp": "2026-03-18T10:30:00Z"
}
```

#### POST /v1/authz/subjects

List all subjects authorized for a resource.

**Request:**
```json
{
  "resource": {
    "class": "Document",
    "id": "doc456",
    "attributes": {
      "owner": "user123"
    }
  },
  "operation": "read"
}
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "subjects": [
      {"id": "user123", "role": "owner"},
      {"id": "user456", "role": "admin"}
    ]
  }
}
```

#### POST /v1/authz/permissions

List all permissions for a subject.

**Request:**
```json
{
  "subject": {
    "id": "user123",
    "attributes": {
      "role": "admin"
    }
  },
  "resource": {
    "class": "Document"
  }
}
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "allow": [],
    "deny": []
  }
}
```

#### POST /v1/authz/explain

Explain an authorization decision.

When a policy rule uses a ReBAC clause such as `["relation", "$s", "viewer", "$r"]`, each evaluated rule can include `relationProofs`. A proof shows whether the relation matched directly or through a parent resource:

```json
{
  "relationProofs": [
    {
      "allowed": true,
      "relation": "viewer",
      "matchedResource": {"class": "Folder", "id": "folder-1"},
      "inherited": true,
      "path": [
        {"class": "Document", "id": "doc-1"},
        {"class": "Folder", "id": "folder-1"}
      ]
    }
  ]
}
```

**Request:**
```json
{
  "subject": {"id": "user123", "attributes": {"role": "user"}},
  "resource": {"class": "Document", "id": "doc456"},
  "operation": "write"
}
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "decision": false,
    "allowed?": false,
    "decisionType": "deny",
    "matchedRuleNames": ["pol-002"],
    "rules": []
  }
}
```

#### POST /v1/authz/shadow

Return the production authorization decision and compare it with a candidate policy in dry-run mode. The production decision remains authoritative; the shadow policy is reported only under `shadowEvaluation`.

**Request:**
```json
{
  "subject": {"id": "user123"},
  "resource": {"class": "Document", "id": "doc456"},
  "operation": "read",
  "shadowPolicyVersion": 12
}
```

Use `shadowPolicy` instead of `shadowPolicyVersion` to test an inline candidate policy.

**Response excerpt:**
```json
{
  "status": "success",
  "data": {
    "allowed?": false,
    "decisionType": "deny",
    "shadowEvaluation": {
      "changed": true,
      "changeCategory": "deny_to_allow",
      "production": {"allowed": false},
      "shadow": {"allowed": true, "policySource": "version"}
    }
  }
}
```

#### POST /v1/authz/batch

Make multiple authorization decisions in a single request.

**Request:**
```json
{
  "requests": [
    {
      "subject": {"id": "user123"},
      "resource": {"class": "Document", "id": "doc1"},
      "operation": "read"
    },
    {
      "subject": {"id": "user123"},
      "resource": {"class": "Document", "id": "doc2"},
      "operation": "write"
    }
  ]
}
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "results": [
      {
        "allowed?": true,
        "decisionType": "allow",
        "subjectId": "user123",
        "resourceClass": "Document",
        "resourceId": "doc1",
        "operation": "read",
        "matchedRuleNames": ["allow-read"],
        "policySource": "current"
      },
      {
        "allowed?": false,
        "decisionType": "deny",
        "subjectId": "user123",
        "resourceClass": "Document",
        "resourceId": "doc2",
        "operation": "write",
        "matchedRuleNames": [],
        "policySource": "current"
      }
    ]
  }
}
```

### Policy Management Endpoints

#### GET /v1/policies

List all policies with pagination and filtering.

**Query Parameters:**
- `page` (integer, default: 1) - Page number
- `per-page` (integer, default: 20, max: 100) - Items per page
- `sort` (string) - Field to sort by
- `order` (string, values: asc, desc, default: asc) - Sort order
- `filter` (string) - Filter in format `key=value,key2=value2`

**Example:**
```
GET /v1/policies?page=1&per-page=20&sort=resourceClass&order=asc
```

**Response:**
```json
{
  "status": "success",
  "data": [
    {"resourceClass": "Document", "version": "1.0", "rules": [...]},
    {"resourceClass": "Account", "version": "1.0", "rules": [...]}
  ],
  "meta": {
    "page": 1,
    "per-page": 20,
    "total": 50,
    "total-pages": 3
  },
  "links": {
    "self": "/v1/policies?page=1&per-page=20",
    "next": "/v1/policies?page=2&per-page=20",
    "prev": null,
    "first": "/v1/policies?page=1&per-page=20",
    "last": "/v1/policies?page=3&per-page=20"
  }
}
```

#### GET /v1/policies/:resource-class

Get a specific policy by resource class.

**Example:**
```
GET /v1/policies/Document
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "resourceClass": "Document",
    "version": "1.0",
    "rules": [
      {
        "effect": "allow",
        "subject": {"role": "admin"},
        "actions": ["read", "write", "delete"]
      }
    ]
  }
}
```

#### POST /v1/policies

Create a new policy.

**Request:**
```json
{
  "resourceClass": "Report",
  "version": "1.0",
  "rules": [
    {
      "effect": "allow",
      "subject": {"role": "analyst"},
      "resource": {"classification": "internal"},
      "actions": ["read", "generate"]
    }
  ]
}
```

**Response:** `201 Created`
```json
{
  "status": "created",
  "data": {
    "resourceClass": "Report",
    "version": "1.0",
    "rules": [...]
  }
}
```

**Headers:**
```
Location: /v1/policies/Report
```

#### PUT /v1/policies/:resource-class

Update an existing policy.

Policies can target `dev`, `staging` or `prod` with the `environment` field or the `?environment=` query parameter. If omitted, Autho uses `prod`.

**Request:**
```json
{
  "resourceClass": "Document",
  "environment": "staging",
  "version": "1.1",
  "rules": [
    {
      "effect": "allow",
      "subject": {"role": "editor"},
      "actions": ["read", "write"]
    }
  ]
}
```

**Response:** `200 OK`
```json
{
  "status": "success",
  "data": {
    "resourceClass": "Document",
    "version": "1.1",
    "rules": [...]
  }
}
```

#### POST /v1/policies/:resource-class/validate

Validate a candidate policy without persisting it. This endpoint runs JSON Schema validation, static policy safety checks and declarative policy tests, but does not create a policy version and does not invalidate decision caches.

**Request:**
```json
{
  "environment": "staging",
  "strategy": "almost_one_allow_no_deny",
  "rules": [
    {
      "name": "document-read",
      "priority": 10,
      "operation": "read",
      "effect": "allow",
      "conditions": [["=", ["Person", "$s", "role"], "editor"]]
    }
  ],
  "tests": [
    {
      "name": "editor can read",
      "subject": {"id": "alice", "class": "Person", "role": "editor"},
      "resource": {"id": "doc-1", "class": "Document"},
      "operation": "read",
      "expect": "allow"
    }
  ]
}
```

Policy conditions can also use a ReBAC predicate:

```json
{
  "conditions": [["relation", "$s", "viewer", "$r"]]
}
```

This checks that the effective subject has the requested relation to the requested resource in the relation graph. Autho checks the direct tuple first, expands persisted relation rewrites, follows group membership through `member` tuples, then walks resource ancestry through `parent` tuples. For example, `can-read` can be rewritten to `viewer` or `editor`; if `alice member team-a` and `team-a viewer folder-1`, then Alice can read `folder-1`; if `doc-1 parent folder-1`, Alice can also read `doc-1`.

Relation tuples are managed through `GET/POST/DELETE /v1/relations`. Mutating tuples requires `governance-admin` or `relation-admin`. Tuples are persisted in the policy H2 database and reloaded into in-memory indexes by `rebac/init!` during PDP startup.

**Response:** `200 OK`
```json
{
  "status": "success",
  "data": {
    "valid": true,
    "resourceClass": "Document",
    "environment": "staging",
    "report": {
      "status": "passed",
      "summary": {
        "errors": 0,
        "warnings": 0,
        "policyTests": {
          "count": 1,
          "passed": 1,
          "failed": 0
        }
      }
    },
    "validation": {
      "valid": true,
      "errors": [],
      "warnings": [],
      "tests": {
        "count": 1,
        "passed": 1,
        "failed": 0,
        "errors": []
      },
      "report": {
        "status": "passed"
      }
    }
  }
}
```

#### POST /v1/policies/:resource-class/impact

Compare the current or versioned baseline with a candidate policy over a request batch. The response includes `impactReport` for change review and rollout gates.

**Request:**
```json
{
  "candidatePolicy": {
    "strategy": "almost_one_allow_no_deny",
    "rules": []
  },
  "thresholds": {
    "maxRevokes": 0,
    "maxChangedDecisions": 50,
    "allowSensitiveResourceChanges": false
  },
  "riskProfiles": {
    "default": {"maxRevokes": 0},
    "environments": {
      "staging": {"maxRevokes": 2}
    },
    "resourceClasses": {
      "Document": {"allowSensitiveResourceChanges": false}
    }
  },
  "requests": [
    {
      "subject": {"id": "alice"},
      "resource": {"class": "Document", "id": "doc-1", "classification": "secret"},
      "operation": "read"
    }
  ]
}
```

`impactReport.status` is one of `no_impact`, `review_required`, `high_risk`, `blocked`; `recommendation` is one of `approve`, `review`, `block`.

Effective thresholds are resolved in this order: `riskProfiles.default`, `riskProfiles.environments[environment]`, `riskProfiles.resourceClasses[resourceClass]`, then request-level `thresholds`. The response includes `riskProfile` so reviewers can audit which sources were applied.

Persisted profiles are available through:

- `GET /v1/policies/risk-profiles`
- `GET /v1/policies/risk-profiles/revisions`
- `PUT/DELETE /v1/policies/risk-profiles/default`
- `PUT/DELETE /v1/policies/risk-profiles/environments/:environment`
- `PUT/DELETE /v1/policies/risk-profiles/resource-classes/:resourceClass`

Inline `riskProfiles` and request-level `thresholds` still override persisted profiles for one-off analyses.
Every profile change appends a revision with the action, previous profile, new profile, author and timestamp.
Critical changes require explicit approval by a different person: increasing `maxRevokes`, increasing `maxChangedDecisions`, enabling `allowSensitiveResourceChanges`, or deleting an existing profile. Send `approval.approved = true` with `approval.approvedBy` and an optional `approval.note`.
Risk profile revisions are included in `GET /v1/policies/:resource-class/timeline` as `risk_profile_changed` events. `default` and `environment` revisions are visible for every class; `resource_class` revisions are visible only for their class.

Rollout gates are enforced when promoting an impact preview:

- `block` or `blocked` impact reports cannot be deployed;
- `review` / `review_required` / `high_risk` reports require `reviewStatus = approved`;
- `approve` / `no_impact` reports can be deployed without manual review.

Policy versions expose lifecycle metadata: `lifecycleStatus`, `workflowAction`, `deploymentKind`, and rollback lineage through `rollbackFromVersion`. Rollback deploys a new active version instead of rewriting history.

The request batch can also come from audit replay:

```json
{
  "candidatePolicy": {
    "strategy": "almost_one_allow_no_deny",
    "rules": []
  },
  "auditReplay": {
    "decision": "deny",
    "limit": 100
  }
}
```

Audit replay uses the full audited request snapshot when available. For older audit rows, it rebuilds a minimal authorization request from audited identifiers and operation. PIPs may enrich missing attributes during simulation.

#### DELETE /v1/policies/:resource-class

Delete a policy.

**Example:**
```
DELETE /v1/policies/Document
```

**Response:** `204 No Content`

### Relationship Endpoints

#### GET /v1/relations

List ReBAC tuples currently loaded by the server. The list is loaded from the durable `REBAC_RELATIONS` table at startup and kept in memory for fast checks.

**Response:** `200 OK`
```json
{
  "status": "success",
  "data": {
    "relations": [
      {
        "subject": {"class": "Person", "id": "alice"},
        "relation": "viewer",
        "resource": {"class": "Document", "id": "doc-1"}
      }
    ]
  }
}
```

#### POST /v1/relations

Create a subject-relation-resource tuple. Requires `governance-admin` or `relation-admin`.

**Request:**
```json
{
  "subject": {"class": "Person", "id": "alice"},
  "relation": "viewer",
  "resource": {"class": "Document", "id": "doc-1"}
}
```

**Response:** `201 Created`

#### GET /v1/relations/rewrites

List persisted relation rewrites loaded by the server.

**Response:** `200 OK`
```json
{
  "status": "success",
  "data": {
    "rewrites": {
      "can-read": ["viewer", "editor"]
    }
  }
}
```

#### PUT /v1/relations/rewrites/:relation

Create or replace a relation rewrite. Requires `governance-admin` or `relation-admin`.

**Request:**
```json
{
  "relations": ["viewer", "editor"]
}
```

**Response:** `200 OK`

#### DELETE /v1/relations/rewrites/:relation

Delete a relation rewrite. Requires `governance-admin` or `relation-admin`.

**Response:** `200 OK`

#### POST /v1/relations/check

Check a relation and return the matched tuple explanation. This endpoint reports whether the relation is direct, inherited through `member` groups, or inherited from a parent resource.

**Request:**
```json
{
  "subject": {"class": "Person", "id": "alice"},
  "relation": "viewer",
  "resource": {"class": "Document", "id": "doc-1"}
}
```

**Response:** `200 OK`
```json
{
  "status": "success",
  "data": {
    "allowed": true,
    "subject": {"class": "Person", "id": "alice"},
    "relation": "can-read",
    "resource": {"class": "Document", "id": "doc-1"},
    "matchedRelation": "viewer",
    "matchedResource": {"class": "Folder", "id": "folder-1"},
    "matchedSubject": {"class": "Group", "id": "team-a"},
    "inherited": true,
    "subjectPath": [
      {"class": "Person", "id": "alice"},
      {"class": "Group", "id": "team-a"}
    ],
    "path": [
      {"class": "Document", "id": "doc-1"},
      {"class": "Folder", "id": "folder-1"}
    ],
    "relationPath": ["can-read", "viewer"]
  }
}
```

#### DELETE /v1/relations

Delete a direct subject-relation-resource tuple. Requires `governance-admin` or `relation-admin`. The request body has the same shape as `POST /v1/relations`.

**Response:** `200 OK`
```json
{
  "status": "success",
  "data": {
    "deleted": true,
    "relation": {
      "subject": {"class": "Person", "id": "alice"},
      "relation": "viewer",
      "resource": {"class": "Document", "id": "doc-1"}
    }
  }
}
```

Current limitation: Autho supports direct checks, persisted relation rewrites, nested groups through `member` tuples, and resource-parent inheritance through `parent` tuples. Arbitrary recursive traversals and distributed external relation storage are not implemented yet.

### Cache Management Endpoints

#### GET /v1/cache/stats

Get cache statistics.

**Example:**
```
GET /v1/cache/stats
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "hits": 1523,
    "misses": 234,
    "evictions": 45
  }
}
```

#### DELETE /v1/cache

Clear all caches.

**Example:**
```
DELETE /v1/cache
```

**Response:** `200 OK`
```json
{
  "status": "success",
  "data": {
    "status": "cleared"
  }
}
```

#### DELETE /v1/cache/:type/:key

Invalidate a specific cache entry.

**Example:**
```
DELETE /v1/cache/subjects/user123
```

**Response:** `200 OK`
```json
{
  "status": "success",
  "data": {
    "status": "cleared",
    "type": "subjects",
    "key": "user123"
  }
}
```

### Subject Management Endpoints

#### GET /v1/subjects

List all subjects with pagination and filtering.

**Query Parameters:**
- `page` (integer, default: 1) - Page number
- `per-page` (integer, default: 20, max: 100) - Items per page
- `sort` (string) - Field to sort by
- `order` (string, values: asc, desc, default: asc) - Sort order

**Example:**
```
GET /v1/subjects?page=1&per-page=20&sort=name&order=asc
```

**Response:**
```json
{
  "status": "success",
  "data": [
    {"id": "user123", "name": "Alice", "role": "admin", "department": "engineering"},
    {"id": "user456", "name": "Bob", "role": "user", "department": "sales"}
  ],
  "meta": {
    "page": 1,
    "per-page": 20,
    "total": 150,
    "total-pages": 8
  }
}
```

#### GET /v1/subjects/:id

Get a specific subject by ID.

**Example:**
```
GET /v1/subjects/user123
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "id": "user123",
    "name": "Alice",
    "role": "admin",
    "department": "engineering",
    "email": "alice@example.com"
  }
}
```

#### GET /v1/subjects/search

Search subjects by attributes or text.

**Query Parameters:**
- `q` (string) - Text search across all attributes
- OR attribute-specific parameters (e.g., `role=admin`, `department=engineering`)

**Examples:**
```
GET /v1/subjects/search?q=alice
GET /v1/subjects/search?role=admin&department=engineering
```

**Response:**
```json
{
  "status": "success",
  "data": [
    {"id": "user123", "name": "Alice", "role": "admin", "department": "engineering"}
  ],
  "meta": {
    "page": 1,
    "per-page": 20,
    "total": 1,
    "total-pages": 1
  }
}
```

#### POST /v1/subjects/batch-get

Batch get multiple subjects by IDs.

**Request:**
```json
{
  "ids": ["user123", "user456", "user789"]
}
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "results": [
      {"id": "user123", "found": true, "subject": {...}},
      {"id": "user456", "found": true, "subject": {...}},
      {"id": "user789", "found": false}
    ]
  }
}
```

### Resource Management Endpoints

#### GET /v1/resources

List all available resource classes.

**Example:**
```
GET /v1/resources
```

**Response:**
```json
{
  "status": "success",
  "data": [
    {"class": "Document", "description": "Resource class: Document"},
    {"class": "Account", "description": "Resource class: Account"},
    {"class": "Report", "description": "Resource class: Report"}
  ]
}
```

#### GET /v1/resources/:class

List all resources of a specific class with pagination.

**Query Parameters:**
- `page` (integer, default: 1) - Page number
- `per-page` (integer, default: 20, max: 100) - Items per page
- `sort` (string) - Field to sort by
- `order` (string, values: asc, desc, default: asc) - Sort order

**Example:**
```
GET /v1/resources/Document?page=1&per-page=20
```

**Response:**
```json
{
  "status": "success",
  "data": [
    {"class": "Document", "id": "doc123", "attributes": {"owner": "user123", "classification": "internal"}},
    {"class": "Document", "id": "doc456", "attributes": {"owner": "user789", "classification": "public"}}
  ],
  "meta": {
    "page": 1,
    "per-page": 20,
    "total": 100,
    "total-pages": 5
  }
}
```

#### GET /v1/resources/:class/:id

Get a specific resource by class and ID.

**Example:**
```
GET /v1/resources/Document/doc123
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "class": "Document",
    "id": "doc123",
    "attributes": {
      "owner": "user123",
      "classification": "internal",
      "created_at": "2026-03-18T10:00:00Z"
    }
  }
}
```

#### GET /v1/resources/search

Search resources by attributes or text.

**Query Parameters:**
- `class` (string, required) - Resource class to search
- `q` (string) - Text search across all attributes
- OR attribute-specific parameters (e.g., `owner=user123`)

**Examples:**
```
GET /v1/resources/search?class=Document&q=confidential
GET /v1/resources/search?class=Document&owner=user123
```

**Response:**
```json
{
  "status": "success",
  "data": [
    {"class": "Document", "id": "doc456", "attributes": {"owner": "user123", "classification": "confidential"}}
  ],
  "meta": {
    "page": 1,
    "per-page": 20,
    "total": 1,
    "total-pages": 1
  }
}
```

#### POST /v1/resources/batch-get

Batch get multiple resources by class and ID.

**Request:**
```json
{
  "resources": [
    {"class": "Document", "id": "doc123"},
    {"class": "Document", "id": "doc456"},
    {"class": "Account", "id": "acc789"}
  ]
}
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "results": [
      {"class": "Document", "id": "doc123", "found": true, "resource": {...}},
      {"class": "Document", "id": "doc456", "found": true, "resource": {...}},
      {"class": "Account", "id": "acc789", "found": false}
    ]
  }
}
```

## HTTP Status Codes

| Code | Description |
|------|-------------|
| 200  | OK - Request succeeded |
| 201  | Created - Resource created successfully |
| 204  | No Content - Successful delete/update |
| 400  | Bad Request - Invalid request body or parameters |
| 401  | Unauthorized - Authentication required |
| 403  | Forbidden - Insufficient permissions |
| 404  | Not Found - Resource not found |
| 429  | Too Many Requests - Rate limit exceeded |
| 500  | Internal Server Error - Server error |
| 503  | Service Unavailable - Service temporarily unavailable |

## Error Codes

| Code | Description |
|------|-------------|
| `INVALID_REQUEST_BODY` | Request body is not valid JSON |
| `VALIDATION_ERROR` | Request validation failed |
| `AUTHORIZATION_ERROR` | Failed to process authorization |
| `POLICY_NOT_FOUND` | Policy not found |
| `POLICIES_ERROR` | Failed to list policies |
| `POLICY_CREATE_ERROR` | Failed to create policy |
| `POLICY_UPDATE_ERROR` | Failed to update policy |
| `POLICY_DELETE_ERROR` | Failed to delete policy |
| `CACHE_STATS_ERROR` | Failed to get cache stats |
| `CACHE_CLEAR_ERROR` | Failed to clear cache |
| `CACHE_INVALIDATE_ERROR` | Failed to invalidate cache entry |
| `SUBJECTS_LIST_ERROR` | Failed to list subjects |
| `SUBJECT_NOT_FOUND` | Subject not found |
| `SUBJECTS_SEARCH_ERROR` | Failed to search subjects |
| `SUBJECTS_BATCH_GET_ERROR` | Failed to batch get subjects |
| `RESOURCES_LIST_ERROR` | Failed to list resources |
| `RESOURCE_NOT_FOUND` | Resource not found |
| `RESOURCE_CLASS_NOT_FOUND` | Resource class not found |
| `RESOURCES_SEARCH_ERROR` | Failed to search resources |
| `RESOURCES_BATCH_GET_ERROR` | Failed to batch get resources |
| `DB_NOT_INITIALIZED` | Database not initialized |
| `BATCH_ERROR` | Failed to process batch request |
| `INVALID_BATCH_REQUEST` | Batch request format is invalid |

## Rate Limiting

Default rate limits:
- **Unauthenticated**: 100 requests per minute
- **Authenticated**: 1000 requests per minute
- **Admin**: 10000 requests per minute

Rate limit headers are included in responses:
```
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 950
X-RateLimit-Reset: 1642245600
```

When rate limit is exceeded:
```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Rate limit exceeded. Maximum 1000 requests per minute allowed.",
    "timestamp": "2026-03-18T10:30:00Z"
  }
}
```

## Examples

### cURL Examples

**Authorization Decision:**
```bash
curl -X POST http://localhost:8080/v1/authz/decisions \
  -H "Content-Type: application/json" \
  -H "Authorization: Token <token>" \
  -d '{
    "subject": {"id": "user123", "attributes": {"role": "admin"}},
    "resource": {"class": "Document", "id": "doc456"},
    "operation": "write"
  }'
```

**List Policies:**
```bash
curl http://localhost:8080/v1/policies?page=1&per-page=20 \
  -H "Authorization: Token <token>"
```

**Create Policy:**
```bash
curl -X POST http://localhost:8080/v1/policies \
  -H "Content-Type: application/json" \
  -H "Authorization: Token <token>" \
  -d '{
    "resourceClass": "Report",
    "strategy": "almost_one_allow_no_deny",
    "rules": [{
      "name": "report-read",
      "priority": 10,
      "operation": "read",
      "effect": "allow",
      "conditions": [["=", ["Person", "$s", "role"], "analyst"]]
    }]
  }'
```

**Batch Decisions:**
```bash
curl -X POST http://localhost:8080/v1/authz/batch \
  -H "Content-Type: application/json" \
  -H "Authorization: Token <token>" \
  -d '{
    "requests": [
      {"subject": {"id": "user123"}, "resource": {"class": "Document", "id": "doc1"}, "operation": "read"},
      {"subject": {"id": "user123"}, "resource": {"class": "Document", "id": "doc2"}, "operation": "write"}
    ]
  }'
```

## Versioning

The API is versioned using the `/v1/` prefix. Future versions will be `/v2/`, `/v3/`, etc.

Clients can specify the API version using the `API-Version` header:
```
API-Version: v1
```

## Backward Compatibility

Legacy endpoints (`/isAuthorized`, `/whoAuthorized`, etc.) are still supported but deprecated. They will be removed in a future release. New integrations should use the v1 API endpoints.

Migration guide:
| Legacy Endpoint | v1 Endpoint |
|----------------|-------------|
| `POST /isAuthorized` | `POST /v1/authz/decisions` |
| `POST /whoAuthorized` | `POST /v1/authz/subjects` |
| `POST /whatAuthorized` | `POST /v1/authz/permissions` |
| `POST /explain` | `POST /v1/authz/explain` |
| `GET /policies` | `GET /v1/policies` |
| `GET /policy/:class` | `GET /v1/policies/:resource-class` |
| `PUT /policy/:class` | `PUT /v1/policies/:resource-class` |
| `DELETE /policy/:class` | `DELETE /v1/policies/:resource-class` |
