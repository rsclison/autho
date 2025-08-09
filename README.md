# autho: An Authorization Server

`autho` is a flexible and extensible authorization server built in Clojure. It provides a RESTful API to manage access control policies and evaluate authorization requests, making it easy to integrate with other services to secure your applications.

The core of `autho` is a Policy Decision Point (PDP) that evaluates incoming requests against a set of policies to determine if access should be granted or denied. Policies are defined using a simple JSON-based rule language.

## Features

*   **Centralized Authorization:** Manage authorization logic in a single service.
*   **Policy-Based Access Control:** Define fine-grained access control policies.
*   **REST API:** Easy to integrate with any application that can make HTTP requests.
*   **Delegation of Rights:** Users can delegate their access rights to other users.
*   **Extensible:** Connect to various attribute sources like databases or LDAP to enrich authorization requests.

## Getting Started

### Prerequisites

*   Java (version 8 or later)
*   [Leiningen](https://leiningen.org/) (the Clojure build tool)

### Building and Running

1.  **Clone the repository:**
    ```bash
    git clone <repository-url>
    cd autho
    ```

2.  **Build the standalone JAR:**
    ```bash
    lein uberjar
    ```
    This will create a file named `autho-0.1.0-SNAPSHOT-standalone.jar` in the `target/uberjar` directory.

3.  **Run the server:**
    ```bash
    java -jar target/uberjar/autho-0.1.0-SNAPSHOT-standalone.jar
    ```
    The server will start on port 8080 by default.

## API Documentation

The `autho` server exposes the following REST API endpoints:

### Authorization Endpoints

#### `POST /isAuthorized`

Checks if a subject is authorized to perform an action on a resource.

**Request Body:**

```json
{
  "subject": { "id": "user1", "role": "editor" },
  "resource": { "class": "document", "id": "doc123" },
  "action": "edit"
}
```

**Response Body:**

```json
{
  "result": true,
  "rules": ["rule1", "rule2"]
}
```

#### `POST /whoAuthorized`

Retrieves the characteristics of subjects who are allowed to perform an action on a resource.

**Request Body:**

```json
{
  "resource": { "class": "document", "id": "doc123" },
  "action": "view"
}
```

**Response Body:**

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

Retrieves the resources that a subject is authorized to perform an action on.

**Request Body:**

```json
{
  "subject": { "id": "user1", "role": "editor" },
  "action": "edit"
}
```

**Response Body:**

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

#### `GET /policies`

Retrieves all loaded policies.

#### `GET /policy/:resourceClass`

Retrieves the policy for a specific resource class.

#### `PUT /policy/:resourceClass`

Submits or updates a policy for a specific resource class. The policy should be in the request body as a JSON string.

#### `DELETE /policy/:resourceClass`

Deletes the policy for a specific resource class.

## Configuration

The server can be configured via the `resources/pdp-prop.properties` file. This file allows you to set up connections to external attribute sources, such as an LDAP directory.

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
