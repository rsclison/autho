import requests
import json

def _post_request(endpoint, data):
    """
    Helper function to send a POST request to a given endpoint.
    """
    url = f"http://localhost:8080/{endpoint}"
    # Add the API key for authentication. In a real application,
    # this secret should be handled securely.
    headers = {
        "Content-Type": "application/json",
        "X-API-Key": "trusted-app-secret"
    }

    try:
        response = requests.post(url, data=json.dumps(data), headers=headers)
        response.raise_for_status()
        return response.json()
    except requests.exceptions.RequestException as e:
        print(f"Error connecting to the server: {e}")
        return None

def is_authorized(subject, resource, operation):
    """
    Sends an isAuthorized request to the server.
    """
    return _post_request("isAuthorized", {
        "subject": subject,
        "resource": resource,
        "operation": operation
    })

def who_authorized(resource, operation):
    """
    Sends a whoAuthorized request to the server.
    """
    return _post_request("whoAuthorized", {
        "resource": resource,
        "operation": operation
    })

def which_authorized(subject, operation):
    """
    Sends a whichAuthorized request to the server.
    """
    return _post_request("whichAuthorized", {
        "subject": subject,
        "operation": operation
    })

def publish_to_kafka(topic, key, value):
    """
    Publishes a key-value message to a specified Kafka topic.
    This example requires a Kafka client library, like 'kafka-python'.
    Install it using: pip install kafka-python
    """
    from kafka import KafkaProducer
    from kafka.errors import KafkaError

    print("\n" + "-" * 20 + "\n")
    print("=== Kafka Producer Example ===")

    producer = KafkaProducer(
        bootstrap_servers=['localhost:9092'],
        key_serializer=lambda k: k.encode('utf-8'),
        value_serializer=lambda v: json.dumps(v).encode('utf-8')
    )

    print(f"Publishing message to topic '{topic}':")
    print(f"Key: {key}")
    print(f"Value: {json.dumps(value)}")

    try:
        # asychronous send
        future = producer.send(topic, key=key, value=value)
        # block for 'synchronous' sends
        record_metadata = future.get(timeout=10)
        print("Message sent successfully to partition {} at offset {}".format(
            record_metadata.partition, record_metadata.offset))
    except KafkaError as e:
        print(f"Error publishing to Kafka: {e}")
    finally:
        producer.flush()
        producer.close()
        print("Producer closed.")


if __name__ == "__main__":
    # isAuthorized examples
    print("=== isAuthorized ===")
    subject1 = {"class": "Person", "role": "professeur"}
    resource1 = {"class": "Diplome"}
    operation1 = "lire"
    print("Checking authorization for:", subject1, resource1, operation1)
    response1 = is_authorized(subject1, resource1, operation1)
    print(f"Response: {response1}\n")

    subject2 = {"class": "Person", "role": "etudiant"}
    resource2 = {"class": "Diplome"}
    operation2 = "lire"
    print("Checking authorization for:", subject2, resource2, operation2)
    response2 = is_authorized(subject2, resource2, operation2)
    print(f"Response: {response2}")

    print("\n" + "-" * 20 + "\n")

    # whoAuthorized example
    print("=== whoAuthorized ===")
    resource3 = {"class": "Diplome"}
    operation3 = "lire"
    print("Finding who is authorized for:", resource3, operation3)
    response3 = who_authorized(resource3, operation3)
    print(f"Response: {response3}")

    print("\n" + "-" * 20 + "\n")

    # whichAuthorized example
    print("=== whichAuthorized ===")
    subject4 = {"class": "Person", "role": "professeur"}
    operation4 = "lire"
    print("Finding which resources are authorized for:", subject4, operation4)
    response4 = which_authorized(subject4, operation4)
    print(f"Response: {response4}")

    # Kafka producer example
    publish_to_kafka(
        topic="user-attributes-compacted",
        key="user1011",
        value={"name": "David", "role": "auditor", "region": "emea"}
    )
