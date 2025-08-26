import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;

public class Client {

    private static String postRequest(String endpoint, String requestBody) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/" + endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    public static String isAuthorized(String subjectJson, String resourceJson, String operation)
            throws IOException, InterruptedException {
        String requestBody = String.format(
            "{\"subject\": %s, \"resource\": %s, \"operation\": \"%s\"}",
            subjectJson, resourceJson, operation
        );
        return postRequest("isAuthorized", requestBody);
    }

    public static String whoAuthorized(String resourceJson, String operation)
            throws IOException, InterruptedException {
        String requestBody = String.format(
            "{\"resource\": %s, \"operation\": \"%s\"}",
            resourceJson, operation
        );
        return postRequest("whoAuthorized", requestBody);
    }

    public static String whichAuthorized(String subjectJson, String operation)
            throws IOException, InterruptedException {
        String requestBody = String.format(
            "{\"subject\": %s, \"operation\": \"%s\"}",
            subjectJson, operation
        );
        return postRequest("whichAuthorized", requestBody);
    }

    public static void main(String[] args) {
        try {
            System.out.println("=== isAuthorized ===");
            String subject1 = "{\"class\": \"Person\", \"role\": \"professeur\"}";
            String resource1 = "{\"class\": \"Diplome\"}";
            String operation1 = "lire";
            System.out.println("Checking authorization for: " + subject1 + ", " + resource1 + ", " + operation1);
            String response1 = isAuthorized(subject1, resource1, operation1);
            System.out.println("Response: " + response1);

            String subject2 = "{\"class\": \"Person\", \"role\": \"etudiant\"}";
            System.out.println("\nChecking authorization for: " + subject2 + ", " + resource1 + ", " + operation1);
            String response2 = isAuthorized(subject2, resource1, operation1);
            System.out.println("Response: " + response2);

            System.out.println("\n--------------------\n");

            System.out.println("=== whoAuthorized ===");
            String resource3 = "{\"class\": \"Diplome\"}";
            String operation3 = "lire";
            System.out.println("Finding who is authorized for: " + resource3 + ", " + operation3);
            String response3 = whoAuthorized(resource3, operation3);
            System.out.println("Response: " + response3);

            System.out.println("\n--------------------\n");

            System.out.println("=== whichAuthorized ===");
            String subject4 = "{\"class\": \"Person\", \"role\": \"professeur\"}";
            String operation4 = "lire";
            System.out.println("Finding which resources are authorized for: " + subject4 + ", " + operation4);
            String response4 = whichAuthorized(subject4, operation4);
            System.out.println("Response: " + response4);

        } catch (IOException | InterruptedException e) {
            System.err.println("Error connecting to the server: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\n--------------------\n");
        System.out.println("=== Kafka Producer Example ===");
        publishToKafka("user-attributes-compacted", "user456", "{\"name\": \"Bob\", \"role\": \"developer\", \"team\": \"backend\"}");
    }

    /**
     * Publishes a key-value message to a specified Kafka topic.
     * This example can be used to feed data to the KafkaPIP.
     * Note: To run this, you need the kafka-clients dependency in your project.
     */
    public static void publishToKafka(String topic, String key, String value) {
        // --- Configuration ---
        String bootstrapServers = "localhost:9092";

        // --- Create Kafka Producer Properties ---
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // --- Create and use the Producer ---
        try (Producer<String, String> producer = new KafkaProducer<>(props)) {
            System.out.println("Publishing message to topic '" + topic + "':");
            System.out.println("Key: " + key);
            System.out.println("Value: " + value);

            producer.send(new ProducerRecord<>(topic, key, value));
            producer.flush();
            System.out.println("Message sent successfully.");
        } catch (Exception e) {
            System.err.println("Error publishing to Kafka: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
