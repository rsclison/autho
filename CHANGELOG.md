# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Rate limiting middleware to prevent DoS attacks (configurable via `RATE_LIMIT_ENABLED` and `RATE_LIMIT_REQUESTS_PER_MINUTE`)
- Health monitoring endpoints:
  - `/health` - Liveness probe for Kubernetes/Docker
  - `/readiness` - Readiness probe checking rule repository status
  - `/status` - Detailed service status (version, uptime, configuration)
- `/explain` endpoint (POST) - Provides detailed rule evaluation information for debugging authorization decisions
- `GET /whoAuthorized/:resourceClass` endpoint - Returns all authorization rules for a given resource class
- Standardized error response format with error codes, messages, and timestamps
- Professional SLF4J logging throughout the codebase (replaced all `println` statements)

### Changed
- Updated `project.clj` metadata with proper description and GitHub URL
- All error responses now follow a consistent format across all endpoints
- `/explain` endpoint changed from GET to POST for consistency with other endpoints
- Improved error handling with specific error codes:
  - `REQUEST_TOO_LARGE` (413)
  - `RATE_LIMIT_EXCEEDED` (429)
  - `INTERNAL_ERROR` (500)
  - `RULES_NOT_LOADED` (503)
  - `EMPTY_REQUEST_BODY` (400)
  - `NOT_IMPLEMENTED` (501)
  - `MISSING_RESOURCE` / `MISSING_SUBJECT` / `MISSING_RESOURCE_CLASS` (400/404)

### Fixed
- Race conditions in cache operations using atomic `swap!`
- Circular delegation detection to prevent infinite recursion
- Request size validation to prevent memory exhaustion attacks

### Security
- Externalized secrets to environment variables (`JWT_SECRET`, `API_KEY`, `LDAP_PASSWORD`)
- Replaced unsafe `read-string` with `edn/read-string` to prevent code injection
- Added request size limiting (default 1MB, configurable via `MAX_REQUEST_SIZE`)
- Implemented per-IP rate limiting (default 100 requests/minute)

### Performance
- Added HTTP connection pooling for REST PIPs (20 threads, 10s timeout)
- Improved cache operations with atomic updates
- Optimized delegation chain evaluation with cycle detection

## [0.1.0] - 2024-09-17

### Added
- Initial release
- XACML-based authorization server
- Policy Decision Point (PDP) implementation
- Policy Administration Point (PAP) implementation
- REST API endpoints:
  - `POST /isAuthorized` - Check authorization for a request
  - `POST /whoAuthorized` - Get subject conditions for resource access
  - `POST /whichAuthorized` - Get resource conditions for subject access
  - `GET /policies` - List all policies
  - `GET /policy/:resourceClass` - Get policy for a resource class
  - `PUT /policy/:resourceClass` - Update policy
  - `DELETE /policy/:resourceClass` - Delete policy
- Policy Information Points (PIPs):
  - REST PIP - Fetch attributes from REST endpoints
  - LDAP PIP - Fetch attributes from LDAP directory
  - Kafka PIP - Fetch attributes from Kafka topics with RocksDB persistence
  - Java PIP - Custom Java-based attribute providers
- Authentication methods:
  - JWT token authentication (OAuth2)
  - API Key authentication
- Caching layer for subjects and resources
- Delegation support with conflict resolution strategies
- Admin endpoints for RocksDB management
- Docker support with Dockerfile
- Examples in Python, Java, and Clojure

[Unreleased]: https://github.com/rsclison/autho/compare/0.1.0...HEAD
[0.1.0]: https://github.com/rsclison/autho/releases/tag/0.1.0
