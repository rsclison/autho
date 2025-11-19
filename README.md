# autho: An Authorization Server

`autho` is a flexible and extensible authorization server built in Clojure. It provides a RESTful API to manage access control policies and evaluate authorization requests, making it easy to integrate with other services to secure your applications.

The core of `autho` is a Policy Decision Point (PDP) that evaluates incoming requests against a set of policies to determine if access should be granted or denied. Policies are defined using a simple JSON-based rule language.

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/Geek13)

## Concepts

`autho` is built around the XACML (eXtensible Access Control Markup Language) standard, which defines a reference architecture for attribute-based access control (ABAC). The key components in this architecture are:

*   **Policy Decision Point (PDP):** The core of the authorization server. The PDP evaluates authorization requests against a set of policies and returns a decision (Permit, Deny, NotApplicable, or Indeterminate).
*   **Policy Administration Point (PAP):** The component responsible for managing policies. In `autho`, this is handled by the policy management API endpoints.
*   **Policy Information Point (PIP):** The component that provides external information to the PDP. This allows the PDP to make decisions based on attributes that are not present in the original request. For example, a PIP could fetch user roles from an LDAP directory or a database.
*   **Policy Enforcement Point (PEP):** The component that enforces the PDP's decision. The PEP is typically part of the application that is being secured. It sends an authorization request to the PDP and, based on the decision, either grants or denies access to the user. `autho` itself is not a PEP.

## Features

*   **Centralized Authorization:** Manage authorization logic in a single service.
*   **Policy-Based Access Control:** Define fine-grained access control policies.
*   **REST API:** Easy to integrate with any application that can make HTTP requests.
*   **Delegation of Rights:** Users can delegate their access rights to other users.
*   **Extensible:** Connect to various attribute sources like databases or LDAP to enrich authorization requests.

## Implementation Status

**Note:** The Datomic/Kafka implementation is currently under development and is not yet ready for production use.

## Getting Started

### Prerequisites

*   Java (version 8 or later)

### Running from the JAR

A pre-compiled, standalone JAR is available in the `bin` directory. To run the server, simply execute the following command:

```bash
java -jar bin/autho.jar
```

The server will start on port 8080 by default.

### Building from Source

If you want to build the project from source, you will need to install [Leiningen](https://leiningen.org/) (the Clojure build tool).

1.  **Clone the repository:**
    ```bash
    git clone <repository-url>
    cd autho
    ```

2.  **Build the standalone JAR:**
    ```bash
    ./lein uberjar
    ```
    This will create a file named `autho-0.1.0-SNAPSHOT-standalone.jar` in the `target/uberjar` directory.

3.  **Run the server:**
    ```bash
    java -jar target/uberjar/autho-0.1.0-SNAPSHOT-standalone.jar
    ```
    The server will start on port 8080 by default.

## Environment Variables

For security reasons, sensitive configuration values must be provided as environment variables. The following environment variables are **required**:

### Required Environment Variables

*   **`JWT_SECRET`**: The secret key used for JWT token signing and verification. This should be a strong, randomly generated string.
    ```bash
    export JWT_SECRET="your-very-secure-jwt-secret-here"
    ```

*   **`API_KEY`**: The API key for trusted application authentication. This should be a strong, randomly generated string.
    ```bash
    export API_KEY="your-very-secure-api-key-here"
    ```

### Optional Environment Variables

*   **`LDAP_PASSWORD`**: The password for the LDAP bind DN. If not set, the password from `pdp-prop.properties` will be used (not recommended for production).
    ```bash
    export LDAP_PASSWORD="your-ldap-password"
    ```

*   **`MAX_REQUEST_SIZE`**: Maximum request body size in bytes. Default is 1048576 (1MB).
    ```bash
    export MAX_REQUEST_SIZE=2097152  # 2MB
    ```

*   **`KAFKA_ENABLED`**: Enable or disable Kafka-related features (PIPs, time-travel endpoints, RocksDB). Default is `true`.
    ```bash
    export KAFKA_ENABLED=false  # Disable Kafka features
    ```
    When set to `false`, the following features will be disabled:
    - Kafka PIPs for business object retrieval
    - Time-travel authorization endpoints (`/isAuthorized-at-time`, `/who-was-authorized-at`, `/what-could-access-at`, `/audit-trail`)
    - RocksDB admin endpoints (`/admin/listRDB`, `/admin/clearRDB/:class-name`)

*   **`RATE_LIMIT_ENABLED`**: Enable or disable rate limiting. Default is `true`.
    ```bash
    export RATE_LIMIT_ENABLED=false  # Disable rate limiting
    ```

*   **`RATE_LIMIT_REQUESTS_PER_MINUTE`**: Maximum number of requests allowed per minute per IP address. Default is 100.
    ```bash
    export RATE_LIMIT_REQUESTS_PER_MINUTE=200  # Allow 200 requests per minute
    ```

*   **`KAFKA_BOOTSTRAP_SERVERS`**: Kafka bootstrap servers for time-travel features. Default is `localhost:9092`.
    ```bash
    export KAFKA_BOOTSTRAP_SERVERS="kafka1:9092,kafka2:9092"
    ```

### Example: Running with Environment Variables

```bash
export JWT_SECRET="my-super-secret-jwt-key-change-this-in-production"
export API_KEY="my-super-secret-api-key-change-this-in-production"
export LDAP_PASSWORD="ldap-password-if-using-ldap"
java -jar bin/autho.jar
```

**Important Security Notes:**
- Never commit these secrets to version control
- Use different secrets for development, staging, and production environments
- Rotate secrets regularly
- Consider using a secrets management system (e.g., HashiCorp Vault, AWS Secrets Manager) in production

## Authentication

Most API endpoints are protected and require authentication. The server supports two methods of authentication, designed for different use cases.

### 1. JWT Token (OAuth2)

This method is intended for end-users or services that have obtained a JSON Web Token (JWT) from an OAuth2 provider. The token must be sent in the `Authorization` header with the `Token` scheme.

**Header:**
`Authorization: Token <your-jwt-here>`

When using this method, the user's identity (the `subject` for the authorization check) is securely derived from the claims inside the validated JWT. Any `subject` provided in the request body will be ignored.

### 2. API Key

This method is intended for trusted backend applications. The application must send a pre-shared secret key in the `X-API-Key` header.

**Header:**
`X-API-Key: <your-secret-api-key>`

When a request is authenticated with a valid API key, the server considers it to be from a trusted source. In this case, the application is allowed to specify the `subject` for the authorization check directly in the request body. This is useful for internal services that need to check permissions on behalf of different users.

## API Documentation

The `autho` server exposes a REST API for managing policies and evaluating authorization requests. All API endpoints accept and return JSON.

### Authorization Endpoints

The authorization endpoints are used to ask the PDP for an authorization decision.

#### `POST /isAuthorized`

This is the main endpoint for checking permissions. It takes a subject, a resource, and an action, and returns a boolean decision indicating whether the action is permitted.

**Request Body:**

The request body must be a JSON object with the following keys:

*   `subject`: An object representing the user or service making the request. **Note:** This field is only used when authenticating with an API Key. When using JWT authentication, the subject is derived from the token and this field is ignored.
*   `resource`: An object representing the resource being accessed.
*   `action`: A string representing the action being performed.

```json
{
  "subject": { "id": "user1", "attributes": { "role": "editor" } },
  "resource": { "class": "document", "id": "doc123", "attributes": { "owner": "user1" } },
  "action": "edit"
}
```

**Response Body:**

The response body is a JSON object with the following keys:

*   `result`: A boolean value. `true` if the action is permitted, `false` otherwise.
*   `rules`: A list of the rules that were matched to produce the decision.

```json
{
  "result": true,
  "rules": ["rule1", "rule2"]
}
```

#### `POST /whoAuthorized`

This endpoint is used to find out which subjects are allowed to perform a certain action on a given resource. This is useful for "what-if" scenarios or for building user interfaces that show who has access to what.

**Request Body:**

The request body must be a JSON object with the following keys:

*   `resource`: An object representing the resource being accessed.
*   `action`: A string representing the action being performed.

```json
{
  "resource": { "class": "document", "id": "doc123" },
  "action": "view"
}
```

**Response Body:**

The response is a JSON array of conditions that describe the subjects who are authorized. Each condition is a logical expression that can be evaluated to determine if a subject is authorized.

```json
[
  {
    "resourceClass": "document",
    "subjectCond": ["=", ["att", "role"], "viewer"],
    "operation": "view"
  }
]
```

#### `POST /whatAuthorized`

This endpoint is the inverse of `whoAuthorized`. It retrieves the resources that a subject is authorized to perform a certain action on. This is useful for building user interfaces that show a user all the resources they can access.

**Enhanced with Kafka PIP:** When Kafka is enabled (`KAFKA_ENABLED=true`) and a resource class has a Kafka PIP configured, this endpoint automatically retrieves and filters the actual objects from RocksDB that match the authorization conditions, with pagination support.

**Request Body:**

The request body must be a JSON object with the following keys:

*   `subject`: An object representing the user or service making the request. **Note:** This field is only used when authenticating with an API Key. When using JWT authentication, the subject is derived from the token and this field is ignored.
*   `resource`: An object with at least `class` to specify the resource type
*   `operation`: A string representing the action being performed (optional, depending on rules)
*   `page` (optional): Page number for object pagination (default: 1)
*   `pageSize` (optional): Number of objects per page (default: 20)

```json
{
  "subject": { "id": "user1", "role": "accountant" },
  "resource": { "class": "Facture" },
  "operation": "edit",
  "page": 1,
  "pageSize": 20
}
```

**Response Body:**

The response is a JSON object with two keys, `allow` and `deny`. Each key contains a list of resource conditions.

**For classes without Kafka PIP (criteria only):**
```json
{
  "allow": [
    {
      "resourceClass": "document",
      "resourceCond": ["and", ["=", ["att", "owner"], "user1"]],
      "operation": "edit"
    }
  ],
  "deny": []
}
```

**For classes with Kafka PIP (criteria + objects + pagination):**
```json
{
  "allow": [
    {
      "resourceClass": "Facture",
      "resourceCond": ["and", ["=", ["att", "accountant"], "user1"]],
      "operation": "edit",
      "objects": {
        "items": [
          {
            "id": "INV-001",
            "accountant": "user1",
            "amount": 5000,
            "status": "pending"
          },
          {
            "id": "INV-002",
            "accountant": "user1",
            "amount": 3000,
            "status": "approved"
          }
        ],
        "pagination": {
          "page": 1,
          "pageSize": 20,
          "total": 45,
          "hasMore": true
        }
      }
    }
  ],
  "deny": []
}
```

**Note:** Objects are only included when:
- `KAFKA_ENABLED=true`
- The resource class has a Kafka PIP configured in `pips.edn`
- RocksDB contains objects for that class

### Policy Management Endpoints

The policy management endpoints are used to manage the policies that the PDP uses to make decisions.

#### `GET /policies`

Retrieves a list of all loaded policies.

**Response Body:**

A JSON array containing the names of all loaded policies.

```json
[
  "document",
  "user"
]
```

#### `GET /policy/:resourceClass`

Retrieves the policy for a specific resource class. The `:resourceClass` is the name of the policy.

**Response Body:**

The response is a JSON object representing the policy.

```json
{
  "resourceClass": "document",
  "rules": [
    {
      "id": "rule1",
      "effect": "allow",
      "conditions": {
        "subject": { "role": "editor" },
        "action": "edit"
      }
    }
  ]
}
```

#### `PUT /policy/:resourceClass`

Submits or updates a policy for a specific resource class. The `:resourceClass` is the name of the policy. The policy itself should be in the request body as a JSON object.

**Request Body:**

A JSON object representing the policy.

```json
{
  "resourceClass": "document",
  "rules": [
    {
      "id": "rule1",
      "effect": "allow",
      "conditions": {
        "subject": { "role": "editor" },
        "action": "edit"
      }
    }
  ]
}
```

**Response Body:**

A JSON object confirming the submission.

```json
{
  "status": "success",
  "message": "Policy 'document' updated."
}
```

#### `DELETE /policy/:resourceClass`

Deletes the policy for a specific resource class. The `:resourceClass` is the name of the policy.

**Response Body:**

A JSON object confirming the deletion.

```json
{
  "status": "success",
  "message": "Policy 'document' deleted."
}
```

## Configuration

The `autho` server is configured through the `resources/pdp-prop.properties` file. This file uses a simple key-value format.

### Deployment Mode

*   `autho.mode`: Specifies the deployment mode. Can be one of the following:
    *   `rest`: (Default) The server will start the REST API.
    *   `embedded`: The server will not start the REST API. This is useful when using the server as a library in another application.

### Policy and Rule Storage

*   `rules.repository`: The path to the file containing the authorization policies. By default, this is `resources/jrules.edn`.

### LDAP Configuration

`autho` can be configured to fetch user attributes from an LDAP directory. This is useful for integrating with existing user directories.

*   `ldap.server`: The hostname or IP address of the LDAP server.
*   `ldap.port`: The port number of the LDAP server.
*   `ldap.basedn`: The base DN for LDAP searches.
*   `ldap.filter`: The LDAP filter to use when searching for users.
*   `ldap.attributes`: A comma-separated list of attributes to fetch from the LDAP directory.
*   `ldap.connectstring`: The DN to use for binding to the LDAP server.
*   `ldap.password`: The password for the bind DN. **Note:** For production environments, it is strongly recommended to use the `LDAP_PASSWORD` environment variable instead of storing the password in this configuration file.

### Person Source

*   `person.source`: Specifies the source for person data. This can be `ldap`, `refng`, or `grhum`.

### Delegation

*   `delegation.type`: The type of delegation store to use. Currently, only `file` is supported.
*   `delegation.path`: The path to the file containing the delegation rules.

## Policy Information Points (PIPs)

PIPs (Policy Information Points) are external data sources that provide additional attributes to enrich authorization requests. Autho supports multiple PIP types including REST APIs, Kafka streams, CSV files, LDAP, and custom Java/Clojure implementations.

### Kafka PIPs with Fallback

When using Kafka PIPs, the system maintains a local RocksDB cache populated by Kafka consumers. To handle scenarios where RocksDB is empty (e.g., cold start) or when Kafka is disabled, you can configure **fallback PIPs**.

**Configuration Example:**

```clojure
{:class "Facture"
 :pip {:type :kafka-pip
       :id-key :id
       :fallback {:type :rest
                  :url "http://backend-service:8080/api/factures"
                  :verb "get"}}}
```

**When is the fallback used?**
- On first application startup (before Kafka consumers populate RocksDB)
- When an object is not found in RocksDB
- When `KAFKA_ENABLED=false`

**Benefits:**
- ✅ Guarantees high availability even with empty cache
- ✅ Graceful degradation when Kafka infrastructure is unavailable
- ✅ Transparent to authorization logic - no rule changes needed

For detailed PIP configuration and best practices, see [docs/PIPS.md](docs/PIPS.md).

## License

Copyright © 2024

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
