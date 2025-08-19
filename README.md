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

## API Documentation

The `autho` server exposes a REST API for managing policies and evaluating authorization requests. All API endpoints accept and return JSON.

### Authorization Endpoints

The authorization endpoints are used to ask the PDP for an authorization decision.

#### `POST /isAuthorized`

This is the main endpoint for checking permissions. It takes a subject, a resource, and an action, and returns a boolean decision indicating whether the action is permitted.

**Request Body:**

The request body must be a JSON object with the following keys:

*   `subject`: An object representing the user or service making the request.
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

#### `POST /whichAuthorized`

This endpoint is the inverse of `whoAuthorized`. It retrieves the resources that a subject is authorized to perform a certain action on. This is useful for building user interfaces that show a user all the resources they can access.

**Request Body:**

The request body must be a JSON object with the following keys:

*   `subject`: An object representing the user or service making the request.
*   `action`: A string representing the action being performed.

```json
{
  "subject": { "id": "user1", "attributes": { "role": "editor" } },
  "action": "edit"
}
```

**Response Body:**

The response is a JSON object with two keys, `allow` and `deny`. Each key contains a list of resource conditions.

```json
{
  "allow": [
    {
      "resourceClass": "document",
      "resourceCond": ["=", ["att", "owner"], "user1"],
      "operation": "edit"
    }
  ],
  "deny": []
}
```

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
*   `ldap.password`: The password for the bind DN.

### Person Source

*   `person.source`: Specifies the source for person data. This can be `ldap`, `refng`, or `grhum`.

### Delegation

*   `delegation.type`: The type of delegation store to use. Currently, only `file` is supported.
*   `delegation.path`: The path to the file containing the delegation rules.

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
