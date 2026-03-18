package autho.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client Java pour le serveur d'autorisation Autho.
 *
 * <p>Ce client couvre l'ensemble des fonctionnalités du serveur :
 * <ul>
 *   <li>Décisions d'autorisation (isAuthorized, whoAuthorized, whatAuthorized, explain, simulate, batch)</li>
 *   <li>Gestion des politiques (CRUD, import YAML)</li>
 *   <li>Versionnage (liste, récupération, diff, rollback)</li>
 *   <li>Cache (statistiques, vidage, invalidation)</li>
 *   <li>Audit (recherche, vérification d'intégrité)</li>
 *   <li>Santé et statut</li>
 * </ul>
 *
 * <p>Utilisation minimale :
 * <pre>{@code
 * AuthoClient client = AuthoClient.builder("http://localhost:8080")
 *     .apiKey("my-api-key")
 *     .build();
 *
 * boolean allowed = client.isAuthorized(
 *     Map.of("id", "alice", "role", "chef_de_service"),
 *     Map.of("class", "Facture", "id", "INV-001"),
 *     "lire"
 * );
 * }</pre>
 *
 * <p>Dépendances Maven :
 * <pre>{@code
 * <dependency>
 *   <groupId>com.fasterxml.jackson.core</groupId>
 *   <artifactId>jackson-databind</artifactId>
 *   <version>2.17.0</version>
 * </dependency>
 * }</pre>
 *
 * <p>Requires Java 11+.
 */
public class AuthoClient {

    private final String baseUrl;
    private final String apiKey;
    private final String jwtToken;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    // =========================================================================
    // Constructeur et Builder
    // =========================================================================

    private AuthoClient(Builder builder) {
        this.baseUrl    = builder.baseUrl.replaceAll("/$", "");
        this.apiKey     = builder.apiKey;
        this.jwtToken   = builder.jwtToken;
        this.timeout    = builder.timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
        this.mapper = new ObjectMapper();
    }

    /** Crée un builder pour construire le client. */
    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    /** Crée un client avec une API Key (raccourci). */
    public static AuthoClient withApiKey(String baseUrl, String apiKey) {
        return builder(baseUrl).apiKey(apiKey).build();
    }

    /** Crée un client avec un JWT (raccourci). */
    public static AuthoClient withJwt(String baseUrl, String jwtToken) {
        return builder(baseUrl).jwtToken(jwtToken).build();
    }

    // =========================================================================
    // Décisions d'autorisation
    // =========================================================================

    /**
     * Évalue si un sujet peut effectuer une opération sur une ressource.
     *
     * @param subject   attributs du sujet (doit contenir "id")
     * @param resource  attributs de la ressource (doit contenir "class")
     * @param operation opération demandée (ex: "lire", "modifier")
     * @return true si autorisé (au moins une règle allow correspond)
     *
     * <pre>{@code
     * boolean ok = client.isAuthorized(
     *     Map.of("id", "alice", "role", "chef_de_service", "service", "compta"),
     *     Map.of("class", "Facture", "id", "INV-001", "service", "compta", "montant", 500),
     *     "lire"
     * );
     * }</pre>
     */
    public boolean isAuthorized(Map<String, Object> subject,
                                 Map<String, Object> resource,
                                 String operation) {
        return isAuthorized(subject, resource, operation, null);
    }

    /**
     * Évalue l'autorisation avec un contexte optionnel.
     *
     * @param context contexte additionnel (IP, timestamp, userAgent…) — peut être null
     */
    public boolean isAuthorized(Map<String, Object> subject,
                                 Map<String, Object> resource,
                                 String operation,
                                 Map<String, Object> context) {
        try {
            ObjectNode body = buildAuthRequest(subject, resource, operation, context);
            JsonNode response = post("/isAuthorized", body);
            JsonNode results = response.path("results");
            return results.isArray() && results.size() > 0;
        } catch (Exception e) {
            throw new AuthoClientException("Erreur lors de l'évaluation d'autorisation", e);
        }
    }

    /**
     * Retourne les noms des règles qui ont correspondu.
     * Un résultat vide signifie un refus.
     *
     * <pre>{@code
     * List<String> rules = client.matchedRules(subject, resource, "lire");
     * // => ["R1"]  ou  []  (refus)
     * }</pre>
     */
    public List<String> matchedRules(Map<String, Object> subject,
                                      Map<String, Object> resource,
                                      String operation) {
        try {
            ObjectNode body = buildAuthRequest(subject, resource, operation, null);
            JsonNode response = post("/isAuthorized", body);
            ArrayNode results = (ArrayNode) response.path("results");
            return mapper.convertValue(results, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new AuthoClientException("Erreur lors de la récupération des règles correspondantes", e);
        }
    }

    /**
     * Retourne les conditions de sujet requises pour accéder à une ressource.
     *
     * <pre>{@code
     * JsonNode who = client.whoAuthorized(
     *     Map.of("class", "Facture", "id", "INV-001"), "lire"
     * );
     * }</pre>
     */
    public JsonNode whoAuthorized(Map<String, Object> resource, String operation) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.set("resource", mapper.valueToTree(resource));
            body.put("operation", operation);
            return post("/whoAuthorized", body);
        } catch (Exception e) {
            throw new AuthoClientException("Erreur whoAuthorized", e);
        }
    }

    /**
     * Retourne les ressources accessibles à un sujet, avec pagination optionnelle.
     *
     * <pre>{@code
     * JsonNode result = client.whatAuthorized(
     *     Map.of("id", "alice", "role", "chef_de_service"),
     *     "Facture", "lire", 1, 20
     * );
     * }</pre>
     */
    public JsonNode whatAuthorized(Map<String, Object> subject,
                                    String resourceClass,
                                    String operation,
                                    int page,
                                    int pageSize) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.set("subject", mapper.valueToTree(subject));
            ObjectNode res = mapper.createObjectNode();
            res.put("class", resourceClass);
            body.set("resource", res);
            body.put("operation", operation);
            body.put("page", page);
            body.put("pageSize", pageSize);
            return post("/whatAuthorized", body);
        } catch (Exception e) {
            throw new AuthoClientException("Erreur whatAuthorized", e);
        }
    }

    /**
     * Retourne la trace détaillée de la décision (règle par règle).
     *
     * <pre>{@code
     * JsonNode trace = client.explain(
     *     Map.of("id", "alice", "role", "chef_de_service"),
     *     Map.of("class", "Facture", "id", "INV-001"),
     *     "lire"
     * );
     * System.out.println("Décision : " + trace.path("decision").asBoolean());
     * System.out.println("Règles vérifiées : " + trace.path("totalRules").asInt());
     * }</pre>
     */
    public JsonNode explain(Map<String, Object> subject,
                             Map<String, Object> resource,
                             String operation) {
        try {
            ObjectNode body = buildAuthRequest(subject, resource, operation, null);
            return post("/explain", body);
        } catch (Exception e) {
            throw new AuthoClientException("Erreur explain", e);
        }
    }

    /**
     * Simulation dry-run contre la politique courante.
     * N'écrit ni cache ni audit.
     */
    public JsonNode simulate(Map<String, Object> subject,
                              Map<String, Object> resource,
                              String operation) {
        return simulate(subject, resource, operation, null, null);
    }

    /**
     * Simulation dry-run avec politique inline ou version archivée.
     *
     * @param simulatedPolicy politique inline à tester (peut être null)
     * @param policyVersion   numéro de version archivée à utiliser (peut être null)
     *
     * <pre>{@code
     * // Avec politique inline
     * Map<String, Object> testPolicy = Map.of(
     *     "resourceClass", "Facture",
     *     "rules", List.of(Map.of(
     *         "name", "TEST", "operation", "lire",
     *         "effect", "allow", "priority", 0,
     *         "conditions", List.of(List.of("=", "$.role", "chef_de_service"))
     *     ))
     * );
     * JsonNode result = client.simulate(subject, resource, "lire", testPolicy, null);
     *
     * // Avec version archivée
     * JsonNode result2 = client.simulate(subject, resource, "lire", null, 3);
     * }</pre>
     */
    public JsonNode simulate(Map<String, Object> subject,
                              Map<String, Object> resource,
                              String operation,
                              Map<String, Object> simulatedPolicy,
                              Integer policyVersion) {
        try {
            ObjectNode body = buildAuthRequest(subject, resource, operation, null);
            if (simulatedPolicy != null) {
                body.set("simulatedPolicy", mapper.valueToTree(simulatedPolicy));
            }
            if (policyVersion != null) {
                body.put("policyVersion", policyVersion);
            }
            return post("/v1/authz/simulate", body);
        } catch (Exception e) {
            throw new AuthoClientException("Erreur simulate", e);
        }
    }

    /**
     * Évalue un lot de demandes en parallèle (max MAX_BATCH_SIZE, défaut 100).
     *
     * <pre>{@code
     * List<Map<String, Object>> requests = List.of(
     *     Map.of(
     *         "subject",   Map.of("id", "alice", "role", "chef_de_service"),
     *         "resource",  Map.of("class", "Facture", "id", "INV-001"),
     *         "operation", "lire"
     *     ),
     *     Map.of(
     *         "subject",   Map.of("id", "bob", "role", "stagiaire"),
     *         "resource",  Map.of("class", "Facture", "id", "INV-002"),
     *         "operation", "lire"
     *     )
     * );
     * JsonNode results = client.batchDecisions(requests);
     * // => {"results": [{"request-id": 0, "decision": {"results": ["R1"]}},
     * //                  {"request-id": 1, "decision": {"results": []}}],
     * //     "count": 2}
     * }</pre>
     */
    public JsonNode batchDecisions(List<Map<String, Object>> requests) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.set("requests", mapper.valueToTree(requests));
            return post("/v1/authz/batch", body);
        } catch (Exception e) {
            throw new AuthoClientException("Erreur batch", e);
        }
    }

    /**
     * Lance une exception si le sujet n'est pas autorisé.
     * Pratique à intégrer dans un middleware.
     *
     * <pre>{@code
     * client.requireAuthorization(subject, resource, "lire");
     * // Lance AuthorizationException si refusé
     * }</pre>
     */
    public void requireAuthorization(Map<String, Object> subject,
                                      Map<String, Object> resource,
                                      String operation) {
        if (!isAuthorized(subject, resource, operation)) {
            throw new AuthorizationException(
                    "Accès refusé",
                    (String) subject.get("id"),
                    resource,
                    operation
            );
        }
    }

    // =========================================================================
    // Gestion des politiques
    // =========================================================================

    /**
     * Récupère une politique par sa classe de ressource.
     *
     * <pre>{@code
     * JsonNode policy = client.getPolicy("Facture");
     * }</pre>
     */
    public JsonNode getPolicy(String resourceClass) {
        try {
            return get("/policy/" + resourceClass);
        } catch (Exception e) {
            throw new AuthoClientException("Erreur getPolicy", e);
        }
    }

    /**
     * Liste toutes les politiques.
     */
    public JsonNode listPolicies() {
        try {
            return get("/policies");
        } catch (Exception e) {
            throw new AuthoClientException("Erreur listPolicies", e);
        }
    }

    /**
     * Crée ou met à jour une politique.
     *
     * <pre>{@code
     * client.submitPolicy("MonDoc", Map.of(
     *     "resourceClass", "MonDoc",
     *     "strategy", "almost_one_allow_no_deny",
     *     "rules", List.of(Map.of(
     *         "name", "MD1", "operation", "lire",
     *         "effect", "allow", "priority", 0,
     *         "conditions", List.of(List.of("=", "$.role", "admin"))
     *     ))
     * ));
     * }</pre>
     */
    public JsonNode submitPolicy(String resourceClass, Map<String, Object> policy) {
        try {
            return put("/policy/" + resourceClass, mapper.valueToTree(policy));
        } catch (Exception e) {
            throw new AuthoClientException("Erreur submitPolicy", e);
        }
    }

    /**
     * Supprime une politique.
     */
    public void deletePolicy(String resourceClass) {
        try {
            delete("/policy/" + resourceClass);
        } catch (Exception e) {
            throw new AuthoClientException("Erreur deletePolicy", e);
        }
    }

    /**
     * Importe une politique depuis un contenu YAML.
     *
     * <pre>{@code
     * String yaml = """
     *     resourceClass: Facture
     *     rules:
     *       - name: R1
     *         operation: lire
     *         effect: allow
     *         priority: 0
     *         conditions:
     *           - ["=", "$.role", "chef_de_service"]
     *     """;
     * JsonNode result = client.importYamlPolicy(yaml);
     * }</pre>
     */
    public JsonNode importYamlPolicy(String yamlContent) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/policies/import"))
                    .timeout(timeout)
                    .headers(buildHeaders("text/yaml"))
                    .POST(HttpRequest.BodyPublishers.ofString(yamlContent))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new AuthoClientException("Erreur importYamlPolicy", e);
        }
    }

    // =========================================================================
    // Versionnage des politiques
    // =========================================================================

    /**
     * Liste les versions d'une politique (plus récente en premier).
     *
     * <pre>{@code
     * JsonNode versions = client.listVersions("Facture");
     * // => [{"version": 5, "author": "alice", "createdAt": "2026-03-18T09:00:00Z"}, ...]
     * }</pre>
     */
    public JsonNode listVersions(String resourceClass) {
        try {
            return get("/v1/policies/" + resourceClass + "/versions");
        } catch (Exception e) {
            throw new AuthoClientException("Erreur listVersions", e);
        }
    }

    /**
     * Récupère la version v d'une politique.
     */
    public JsonNode getVersion(String resourceClass, int version) {
        try {
            return get("/v1/policies/" + resourceClass + "/versions/" + version);
        } catch (Exception e) {
            throw new AuthoClientException("Erreur getVersion", e);
        }
    }

    /**
     * Compare deux versions d'une politique.
     * Retourne {@code {"added": [...], "removed": [...], "changed": [...]}}
     *
     * <pre>{@code
     * JsonNode diff = client.diffVersions("Facture", 3, 5);
     * System.out.println("Règles ajoutées : " + diff.path("added"));
     * }</pre>
     */
    public JsonNode diffVersions(String resourceClass, int fromVersion, int toVersion) {
        try {
            return get("/v1/policies/" + resourceClass + "/diff?from=" + fromVersion + "&to=" + toVersion);
        } catch (Exception e) {
            throw new AuthoClientException("Erreur diffVersions", e);
        }
    }

    /**
     * Restaure une politique à la version v.
     *
     * <pre>{@code
     * JsonNode result = client.rollback("Facture", 3);
     * System.out.println("Nouvelle version : " + result.path("newVersion").asInt());
     * }</pre>
     */
    public JsonNode rollback(String resourceClass, int version) {
        try {
            return post("/v1/policies/" + resourceClass + "/rollback/" + version,
                    mapper.createObjectNode());
        } catch (Exception e) {
            throw new AuthoClientException("Erreur rollback", e);
        }
    }

    // =========================================================================
    // Cache
    // =========================================================================

    /**
     * Retourne les statistiques du cache.
     *
     * <pre>{@code
     * JsonNode stats = client.cacheStats();
     * double ratio = stats.path("decision-ratio").asDouble();
     * }</pre>
     */
    public JsonNode cacheStats() {
        try {
            return get("/v1/cache/stats");
        } catch (Exception e) {
            throw new AuthoClientException("Erreur cacheStats", e);
        }
    }

    /**
     * Vide tous les caches.
     */
    public void clearCache() {
        try {
            delete("/v1/cache");
        } catch (Exception e) {
            throw new AuthoClientException("Erreur clearCache", e);
        }
    }

    /**
     * Invalide une entrée précise du cache.
     *
     * @param cacheType "subject", "resource", "policy" ou "decision"
     * @param cacheKey  identifiant de l'entrée
     *
     * <pre>{@code
     * client.invalidateCacheEntry("subject", "alice");
     * client.invalidateCacheEntry("decision", "alice:Facture:INV-001:lire");
     * }</pre>
     */
    public void invalidateCacheEntry(String cacheType, String cacheKey) {
        try {
            delete("/v1/cache/" + cacheType + "/" + cacheKey);
        } catch (Exception e) {
            throw new AuthoClientException("Erreur invalidateCacheEntry", e);
        }
    }

    // =========================================================================
    // Audit
    // =========================================================================

    /**
     * Recherche dans le journal d'audit avec filtres optionnels.
     *
     * <pre>{@code
     * JsonNode entries = client.searchAudit(
     *     AuditSearchParams.builder()
     *         .subjectId("alice")
     *         .resourceClass("Facture")
     *         .decision("allow")
     *         .from("2026-01-01")
     *         .to("2026-03-31")
     *         .page(1)
     *         .pageSize(50)
     *         .build()
     * );
     * }</pre>
     */
    public JsonNode searchAudit(AuditSearchParams params) {
        try {
            StringBuilder url = new StringBuilder("/admin/audit/search?page=")
                    .append(params.page).append("&pageSize=").append(params.pageSize);
            if (params.subjectId != null)     url.append("&subjectId=").append(params.subjectId);
            if (params.resourceClass != null) url.append("&resourceClass=").append(params.resourceClass);
            if (params.decision != null)      url.append("&decision=").append(params.decision);
            if (params.from != null)          url.append("&from=").append(params.from);
            if (params.to != null)            url.append("&to=").append(params.to);
            return get(url.toString());
        } catch (Exception e) {
            throw new AuthoClientException("Erreur searchAudit", e);
        }
    }

    /**
     * Vérifie l'intégrité de la chaîne HMAC du journal d'audit.
     *
     * <pre>{@code
     * JsonNode result = client.verifyAuditChain();
     * if (!result.path("valid").asBoolean()) {
     *     System.err.println("Audit compromis à l'entrée : " + result.path("broken-at").asLong());
     * }
     * }</pre>
     */
    public JsonNode verifyAuditChain() {
        try {
            return get("/admin/audit/verify");
        } catch (Exception e) {
            throw new AuthoClientException("Erreur verifyAuditChain", e);
        }
    }

    // =========================================================================
    // Santé & statut
    // =========================================================================

    /** Vérification de santé simple. */
    public boolean isHealthy() {
        try {
            JsonNode resp = get("/health");
            return "ok".equals(resp.path("status").asText());
        } catch (Exception e) {
            return false;
        }
    }

    /** Statut détaillé : version, uptime, circuit breakers, stats cache. */
    public JsonNode getStatus() {
        try {
            return get("/status");
        } catch (Exception e) {
            throw new AuthoClientException("Erreur getStatus", e);
        }
    }

    // =========================================================================
    // Couche HTTP privée
    // =========================================================================

    private JsonNode post(String path, JsonNode body) throws Exception {
        String json = mapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .headers(buildHeaders("application/json"))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        checkStatus(response);
        return mapper.readTree(response.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .headers(buildHeaders("application/json"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        checkStatus(response);
        return mapper.readTree(response.body());
    }

    private JsonNode put(String path, JsonNode body) throws Exception {
        String json = mapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .headers(buildHeaders("application/json"))
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        checkStatus(response);
        return mapper.readTree(response.body());
    }

    private void delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .headers(buildHeaders("application/json"))
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        checkStatus(response);
    }

    private String[] buildHeaders(String contentType) {
        if (apiKey != null) {
            return new String[]{"Content-Type", contentType, "Accept", "application/json",
                    "X-API-Key", apiKey};
        } else if (jwtToken != null) {
            return new String[]{"Content-Type", contentType, "Accept", "application/json",
                    "Authorization", "Token " + jwtToken};
        }
        return new String[]{"Content-Type", contentType, "Accept", "application/json"};
    }

    private void checkStatus(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 429) {
            throw new AuthoClientException("Rate limit dépassé (429)", null);
        }
        if (status == 503) {
            throw new AuthoClientException("Serveur indisponible (503)", null);
        }
        if (status >= 500) {
            throw new AuthoClientException("Erreur serveur " + status + ": " + response.body(), null);
        }
    }

    private ObjectNode buildAuthRequest(Map<String, Object> subject,
                                         Map<String, Object> resource,
                                         String operation,
                                         Map<String, Object> context) {
        ObjectNode body = mapper.createObjectNode();
        body.set("subject", mapper.valueToTree(subject));
        body.set("resource", mapper.valueToTree(resource));
        body.put("operation", operation);
        if (context != null) {
            body.set("context", mapper.valueToTree(context));
        }
        return body;
    }

    // =========================================================================
    // Builder
    // =========================================================================

    public static class Builder {
        private final String baseUrl;
        private String apiKey;
        private String jwtToken;
        private Duration timeout = Duration.ofSeconds(10);

        private Builder(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder jwtToken(String jwtToken) {
            this.jwtToken = jwtToken;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder timeoutSeconds(long seconds) {
            this.timeout = Duration.ofSeconds(seconds);
            return this;
        }

        public AuthoClient build() {
            if (apiKey == null && jwtToken == null) {
                throw new IllegalStateException("apiKey ou jwtToken requis");
            }
            return new AuthoClient(this);
        }
    }

    // =========================================================================
    // Paramètres de recherche d'audit
    // =========================================================================

    public static class AuditSearchParams {
        public final String subjectId;
        public final String resourceClass;
        public final String decision;
        public final String from;
        public final String to;
        public final int    page;
        public final int    pageSize;

        private AuditSearchParams(AuditBuilder b) {
            this.subjectId     = b.subjectId;
            this.resourceClass = b.resourceClass;
            this.decision      = b.decision;
            this.from          = b.from;
            this.to            = b.to;
            this.page          = b.page;
            this.pageSize      = b.pageSize;
        }

        public static AuditBuilder builder() {
            return new AuditBuilder();
        }

        public static class AuditBuilder {
            private String subjectId;
            private String resourceClass;
            private String decision;
            private String from;
            private String to;
            private int    page     = 1;
            private int    pageSize = 20;

            public AuditBuilder subjectId(String v)     { this.subjectId = v;     return this; }
            public AuditBuilder resourceClass(String v) { this.resourceClass = v; return this; }
            public AuditBuilder decision(String v)      { this.decision = v;      return this; }
            public AuditBuilder from(String v)          { this.from = v;          return this; }
            public AuditBuilder to(String v)            { this.to = v;            return this; }
            public AuditBuilder page(int v)             { this.page = v;          return this; }
            public AuditBuilder pageSize(int v)         { this.pageSize = v;      return this; }
            public AuditSearchParams build()            { return new AuditSearchParams(this); }
        }
    }

    // =========================================================================
    // Exceptions
    // =========================================================================

    /** Exception levée en cas d'erreur du client HTTP. */
    public static class AuthoClientException extends RuntimeException {
        public AuthoClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Exception levée lors d'un refus d'autorisation (via requireAuthorization). */
    public static class AuthorizationException extends RuntimeException {
        private final String subjectId;
        private final Map<String, Object> resource;
        private final String operation;

        public AuthorizationException(String message, String subjectId,
                                       Map<String, Object> resource, String operation) {
            super(message + " [sujet=" + subjectId + ", ressource=" + resource
                    + ", opération=" + operation + "]");
            this.subjectId = subjectId;
            this.resource  = resource;
            this.operation = operation;
        }

        public String getSubjectId() { return subjectId; }
        public Map<String, Object> getResource() { return resource; }
        public String getOperation() { return operation; }
    }
}
