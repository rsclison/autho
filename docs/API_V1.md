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
Authorization: Bearer <jwt-token>
X-API-Key: <api-key>
```

## Response Format

All responses follow a standard format:

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
  "action": "write"
}
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "decision": "allow",
    "matchingPolicies": ["pol-001"]
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
  "action": "read"
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
    "permissions": [
      {"action": "read", "resourceClass": "Document"},
      {"action": "write", "resourceClass": "Document"}
    ]
  }
}
```

#### POST /v1/authz/explain

Explain an authorization decision.

**Request:**
```json
{
  "subject": {"id": "user123", "attributes": {"role": "user"}},
  "resource": {"class": "Document", "id": "doc456"},
  "action": "write"
}
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "decision": "deny",
    "reason": "Insufficient permissions",
    "matchingPolicies": ["pol-002"],
    "explanation": "Subject role 'user' is not authorized for action 'write' on resource class 'Document'"
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
      "action": "read"
    },
    {
      "subject": {"id": "user123"},
      "resource": {"class": "Document", "id": "doc2"},
      "action": "write"
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
      {"request-id": 0, "decision": "allow"},
      {"request-id": 1, "decision": "deny", "reason": "Insufficient permissions"}
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

**Request:**
```json
{
  "resourceClass": "Document",
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

#### DELETE /v1/policies/:resource-class

Delete a policy.

**Example:**
```
DELETE /v1/policies/Document
```

**Response:** `204 No Content`

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
  -H "Authorization: Bearer <token>" \
  -d '{
    "subject": {"id": "user123", "attributes": {"role": "admin"}},
    "resource": {"class": "Document", "id": "doc456"},
    "action": "write"
  }'
```

**List Policies:**
```bash
curl http://localhost:8080/v1/policies?page=1&per-page=20 \
  -H "Authorization: Bearer <token>"
```

**Create Policy:**
```bash
curl -X POST http://localhost:8080/v1/policies \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "resourceClass": "Report",
    "version": "1.0",
    "rules": [{
      "effect": "allow",
      "subject": {"role": "analyst"},
      "actions": ["read", "generate"]
    }]
  }'
```

**Batch Decisions:**
```bash
curl -X POST http://localhost:8080/v1/authz/batch \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "requests": [
      {"subject": {"id": "user123"}, "resource": {"class": "Document", "id": "doc1"}, "action": "read"},
      {"subject": {"id": "user123"}, "resource": {"class": "Document", "id": "doc2"}, "action": "write"}
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
