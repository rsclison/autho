package autho.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Exemples d'utilisation du client Java Autho.
 *
 * <p>Exécution :
 * <pre>
 *   mvn compile exec:java -Dexec.mainClass="autho.client.AuthoClientExample"
 * </pre>
 */
public class AuthoClientExample {

    public static void main(String[] args) {
        // =====================================================================
        // Initialisation du client
        // =====================================================================
        AuthoClient client = AuthoClient.builder("http://localhost:8080")
                .apiKey("my-api-key")
                .timeoutSeconds(10)
                .build();

        // =====================================================================
        // 1. Vérification de santé
        // =====================================================================
        System.out.println("=== Santé ===");
        boolean healthy = client.isHealthy();
        System.out.println("Serveur opérationnel : " + healthy);

        JsonNode status = client.getStatus();
        System.out.println("Version : " + status.path("version").asText());
        System.out.println("Uptime  : " + status.path("uptime_seconds").asLong() + " s");

        // =====================================================================
        // 2. Décisions d'autorisation
        // =====================================================================
        System.out.println("\n=== Autorisations ===");

        Map<String, Object> alice = Map.of(
                "id",      "alice",
                "role",    "chef_de_service",
                "service", "comptabilite",
                "seuil",   2000
        );
        Map<String, Object> facture001 = Map.of(
                "class",   "Facture",
                "id",      "INV-001",
                "service", "comptabilite",
                "montant", 500
        );

        // Vérification simple
        boolean allowed = client.isAuthorized(alice, facture001, "lire");
        System.out.println("alice peut lire INV-001 : " + allowed);

        // Règles correspondantes
        List<String> rules = client.matchedRules(alice, facture001, "lire");
        System.out.println("Règles : " + rules);

        // Refus
        Map<String, Object> bob = Map.of("id", "bob", "role", "stagiaire");
        boolean bobAllowed = client.isAuthorized(bob, facture001, "lire");
        System.out.println("bob peut lire INV-001 : " + bobAllowed);

        // =====================================================================
        // 3. Explain — trace de décision
        // =====================================================================
        System.out.println("\n=== Explain ===");
        JsonNode trace = client.explain(alice, facture001, "lire");
        System.out.println("Décision     : " + trace.path("decision").asBoolean());
        System.out.println("Règles total : " + trace.path("totalRules").asInt());
        System.out.println("Règles match : " + trace.path("matchedRules").asInt());
        trace.path("rules").forEach(rule ->
                System.out.printf("  %-10s %-5s match=%s%n",
                        rule.path("name").asText(),
                        rule.path("effect").asText(),
                        rule.path("matched").asBoolean())
        );

        // =====================================================================
        // 4. Simulation dry-run
        // =====================================================================
        System.out.println("\n=== Simulation ===");
        Map<String, Object> testPolicy = Map.of(
                "resourceClass", "Facture",
                "strategy",      "almost_one_allow_no_deny",
                "rules", List.of(Map.of(
                        "name",       "TEST-R1",
                        "operation",  "lire",
                        "effect",     "allow",
                        "priority",   0,
                        "conditions", List.of(List.of("=", "$.role", "chef_de_service"))
                ))
        );
        JsonNode simResult = client.simulate(alice, facture001, "lire", testPolicy, null);
        System.out.println("Résultat simulation    : " + simResult.path("decision").asBoolean());
        System.out.println("Source politique       : " + simResult.path("policySource").asText());

        // Simulation avec version archivée
        JsonNode simV3 = client.simulate(alice, facture001, "lire", null, 3);
        System.out.println("Simulation v3          : " + simV3.path("decision").asBoolean());

        // =====================================================================
        // 5. Batch
        // =====================================================================
        System.out.println("\n=== Batch ===");
        List<Map<String, Object>> batchRequests = List.of(
                Map.of("subject", alice,   "resource", facture001, "operation", "lire"),
                Map.of("subject", bob,     "resource", facture001, "operation", "lire"),
                Map.of("subject", Map.of("id", "charlie", "role", "DPO"),
                       "resource", Map.of("class", "Facture", "id", "INV-002", "confidentiel", true),
                       "operation", "lire")
        );
        JsonNode batchResult = client.batchDecisions(batchRequests);
        System.out.println("Résultats batch : " + batchResult.path("count").asInt() + " demandes");
        batchResult.path("results").forEach(r -> {
            String decision = r.has("error")
                    ? "ERREUR: " + r.path("error").asText()
                    : (r.path("decision").path("results").size() > 0 ? "ALLOW" : "DENY");
            System.out.println("  [" + r.path("request-id").asInt() + "] " + decision);
        });

        // =====================================================================
        // 6. Who/What Authorized
        // =====================================================================
        System.out.println("\n=== Who/What Authorized ===");
        JsonNode who = client.whoAuthorized(Map.of("class", "Facture"), "lire");
        System.out.println("Conditions pour accéder à Facture/lire : " + who);

        JsonNode what = client.whatAuthorized(alice, "Facture", "lire", 1, 20);
        System.out.println("Ressources accessibles à alice : " + what.path("allow").size() + " règles allow");

        // =====================================================================
        // 7. Gestion des politiques
        // =====================================================================
        System.out.println("\n=== Gestion des politiques ===");
        JsonNode policy = client.getPolicy("Facture");
        System.out.println("Politique Facture chargée : " + !policy.isMissingNode());

        // Soumettre une nouvelle politique
        Map<String, Object> newPolicy = Map.of(
                "resourceClass", "TestDoc",
                "strategy",      "almost_one_allow_no_deny",
                "rules", List.of(Map.of(
                        "name",       "TD1",
                        "operation",  "lire",
                        "effect",     "allow",
                        "priority",   0,
                        "conditions", List.of(List.of("=", "$.role", "admin"))
                ))
        );
        client.submitPolicy("TestDoc", newPolicy);
        System.out.println("Politique TestDoc créée");

        // Supprimer
        client.deletePolicy("TestDoc");
        System.out.println("Politique TestDoc supprimée");

        // =====================================================================
        // 8. Versionnage
        // =====================================================================
        System.out.println("\n=== Versionnage ===");
        JsonNode versions = client.listVersions("Facture");
        System.out.println("Versions de Facture : " + versions.size());
        if (versions.size() > 0) {
            JsonNode latest = versions.get(0);
            System.out.println("  Dernière : v" + latest.path("version").asInt()
                    + " par " + latest.path("author").asText()
                    + " le " + latest.path("createdAt").asText());
        }

        // Diff entre versions (si au moins 2 versions)
        if (versions.size() >= 2) {
            int v1 = versions.get(versions.size() - 1).path("version").asInt();
            int v2 = versions.get(0).path("version").asInt();
            JsonNode diff = client.diffVersions("Facture", v1, v2);
            System.out.println("Diff v" + v1 + "→v" + v2 + " : "
                    + "added=" + diff.path("added").size()
                    + " removed=" + diff.path("removed").size()
                    + " changed=" + diff.path("changed").size());
        }

        // =====================================================================
        // 9. Cache
        // =====================================================================
        System.out.println("\n=== Cache ===");
        JsonNode stats = client.cacheStats();
        System.out.printf("Cache décision : %d hits / %d misses (ratio %.1f%%)%n",
                stats.path("decision-hits").asLong(),
                stats.path("decision-misses").asLong(),
                stats.path("decision-ratio").asDouble() * 100);

        client.invalidateCacheEntry("subject", "alice");
        System.out.println("Cache sujet alice invalidé");

        // =====================================================================
        // 10. Audit
        // =====================================================================
        System.out.println("\n=== Audit ===");
        JsonNode auditResult = client.searchAudit(
                AuthoClient.AuditSearchParams.builder()
                        .subjectId("alice")
                        .resourceClass("Facture")
                        .page(1)
                        .pageSize(10)
                        .build()
        );
        System.out.println("Entrées audit alice/Facture : " + auditResult.path("total").asInt());

        JsonNode verification = client.verifyAuditChain();
        System.out.println("Intégrité chaîne audit : "
                + (verification.path("valid").asBoolean() ? "VALIDE" : "COMPROMISE !"));

        // =====================================================================
        // 11. requireAuthorization — pattern middleware
        // =====================================================================
        System.out.println("\n=== Pattern middleware ===");
        try {
            client.requireAuthorization(alice, facture001, "lire");
            System.out.println("alice autorisée → traitement métier...");
        } catch (AuthoClient.AuthorizationException e) {
            System.err.println("Refus : " + e.getMessage());
        }

        try {
            client.requireAuthorization(bob, facture001, "lire");
        } catch (AuthoClient.AuthorizationException e) {
            System.out.println("bob refusé (attendu) : " + e.getSubjectId()
                    + " ne peut pas " + e.getOperation());
        }

        System.out.println("\n=== Fin des exemples ===");
    }
}
