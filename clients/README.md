# Clients Autho

Clients de référence pour le serveur d'autorisation Autho.

---

## Client Clojure

### Dépendances

```clojure
;; project.clj
[clj-http "3.13.1"]
[org.clojure/data.json "2.5.0"]
```

### Utilisation

```clojure
(require '[autho-client.core :as autho])

;; Créer un client
(def client (autho/make-client "http://localhost:8080" {:api-key "my-key"}))

;; Décision simple
(autho/authorized? client
  {:id "alice" :role "chef_de_service" :service "compta"}
  {:class "Facture" :id "INV-001" :service "compta" :montant 500}
  "lire")
;; => true

;; Avec les règles qui ont correspondu
(autho/matched-rules client subject resource "lire")
;; => ["R1"]

;; Trace de décision
(autho/explain client subject resource "lire")

;; Simulation dry-run
(autho/simulate client subject resource "lire"
  {:simulated-policy my-test-policy})

;; Batch
(autho/batch-decisions client [req1 req2 req3])

;; Lever une exception si refusé
(autho/require-authorization! client subject resource "lire")
```

### Lancer les exemples

```bash
cd clients/clojure
lein repl
```

Tous les exemples sont dans le bloc `(comment ...)` de `core.clj`.

---

## Client Java

### Dépendances Maven

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.17.0</version>
</dependency>
```

Requires **Java 11+** (utilise `java.net.http.HttpClient`).

### Utilisation

```java
// Créer un client
AuthoClient client = AuthoClient.builder("http://localhost:8080")
    .apiKey("my-api-key")
    .timeoutSeconds(10)
    .build();

// Ou avec JWT
AuthoClient client = AuthoClient.withJwt("http://localhost:8080", jwtToken);

// Décision simple
boolean allowed = client.isAuthorized(
    Map.of("id", "alice", "role", "chef_de_service"),
    Map.of("class", "Facture", "id", "INV-001"),
    "lire"
);

// Règles correspondantes
List<String> rules = client.matchedRules(subject, resource, "lire");

// Trace de décision
JsonNode trace = client.explain(subject, resource, "lire");

// Simulation
JsonNode sim = client.simulate(subject, resource, "lire", myTestPolicy, null);

// Batch
JsonNode results = client.batchDecisions(List.of(req1, req2, req3));

// Pattern middleware — lance AuthorizationException si refusé
client.requireAuthorization(subject, resource, "lire");

// Versionnage
JsonNode versions = client.listVersions("Facture");
JsonNode diff     = client.diffVersions("Facture", 3, 5);
client.rollback("Facture", 3);

// Cache
JsonNode stats = client.cacheStats();
client.invalidateCacheEntry("subject", "alice");

// Audit
JsonNode entries = client.searchAudit(
    AuthoClient.AuditSearchParams.builder()
        .subjectId("alice")
        .from("2026-01-01")
        .build()
);
JsonNode integrity = client.verifyAuditChain();
```

### Lancer les exemples

```bash
cd clients/java
mvn compile exec:java
```

Ou construire un fat jar :

```bash
mvn package
java -jar target/autho-java-client-0.1.0.jar
```

---

## Fonctionnalités couvertes par les deux clients

| Fonctionnalité | Clojure | Java |
|----------------|---------|------|
| `isAuthorized` / `authorized?` | ✓ | ✓ |
| `matchedRules` | ✓ | ✓ |
| `whoAuthorized` | ✓ | ✓ |
| `whatAuthorized` (pagination) | ✓ | ✓ |
| `explain` | ✓ | ✓ |
| `simulate` (inline / version) | ✓ | ✓ |
| `batch` | ✓ | ✓ |
| `requireAuthorization` | ✓ | ✓ |
| CRUD politiques | ✓ | ✓ |
| Import YAML | ✓ | ✓ |
| Versionnage (list/get/diff/rollback) | ✓ | ✓ |
| Cache (stats/clear/invalidate) | ✓ | ✓ |
| Audit (search/verify) | ✓ | ✓ |
| Health / Status | ✓ | ✓ |
