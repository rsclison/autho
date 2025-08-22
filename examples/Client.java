import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Client {

    public static String checkAuthorization(String subjectJson, String resourceJson, String operation)
            throws IOException, InterruptedException {
        String requestBody = String.format(
            "{\"subject\": %s, \"resource\": %s, \"operation\": \"%s\"}",
            subjectJson, resourceJson, operation
        );

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/isAuthorized"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    public static void main(String[] args) {
        try {
            // Example 1: Should be authorized
            String subject1 = "{\"class\": \"Person\", \"role\": \"professeur\"}";
            String resource1 = "{\"class\": \"Diplome\"}";
            String operation1 = "lire";

            System.out.println("Checking authorization for:");
            System.out.println("  Subject: " + subject1);
            System.out.println("  Resource: " + resource1);
            System.out.println("  Operation: " + operation1);
            String response1 = checkAuthorization(subject1, resource1, operation1);
            System.out.println("Response: " + response1);

            System.out.println("--------------------");

            // Example 2: Should be denied
            String subject2 = "{\"class\": \"Person\", \"role\": \"etudiant\"}";
            String resource2 = "{\"class\": \"Diplome\"}";
            String operation2 = "lire";

            System.out.println("Checking authorization for:");
            System.out.println("  Subject: " + subject2);
            System.out.println("  Resource: " + resource2);
            System.out.println("  Operation: " + operation2);
            String response2 = checkAuthorization(subject2, resource2, operation2);
            System.out.println("Response: " + response2);

        } catch (IOException | InterruptedException e) {
            System.err.println("Error connecting to the server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
