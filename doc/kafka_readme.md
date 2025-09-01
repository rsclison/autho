# Kafka PIP: Real-time Attribute Updates

The `autho` server includes a Policy Information Point (PIP) that uses Apache Kafka to receive real-time updates for entity attributes. This allows the Policy Decision Point (PDP) to make authorization decisions based on the most current data available.

This PIP uses a local RocksDB database to store the state of the attributes, ensuring that the PDP has fast, local access to the data it needs.

## How it Works

The Kafka PIP operates as a consumer that continuously reads from a specified Kafka topic. This topic should contain messages where:
- The **key** is the unique identifier of the entity (e.g., a user ID, a resource ID).
- The **value** is a JSON object containing the attributes to be updated for that entity.

The core of the process is a merge-on-read logic implemented in the consumer:

1.  **Consume Message:** The PIP's Kafka consumer reads a message from its designated topic.
2.  **Fetch Local State:** It uses the message key to look up the current state of the entity in its local RocksDB store.
3.  **Merge Attributes:** The consumer parses the JSON from both the existing record in RocksDB and the new message from Kafka. It then performs a deep merge of the two, with the new attributes from Kafka overwriting any existing ones with the same name.
4.  **Update Local State:** The resulting merged JSON object is written back to RocksDB, replacing the previous state for that entity.

This ensures that the local attribute store is always kept up-to-date with the latest information pushed to the Kafka topic. When the PDP needs to evaluate a policy, it can query this local RocksDB store to get the required attributes for a subject or resource.

## Configuration

To enable the Kafka PIP, you need to configure it in two places:

1.  **`resources/pips.edn`**: Define a PIP of type `:kafka-pip` and specify its configuration.

    ```edn
    {:class "user"
     :type :kafka-pip
     :kafka-topic "user-attributes-compacted"
     :kafka-bootstrap-servers "localhost:9092"}
    ```
    - `:class`: A name for this attribute set, which corresponds to a RocksDB column family.
    - `:type`: Must be `:kafka-pip`.
    - `:kafka-topic`: The Kafka topic to consume from. It is recommended to use a compacted topic.
    - `:kafka-bootstrap-servers`: The address of the Kafka brokers.

2.  **`resources/pdp-prop.properties`**: Specify the path where the RocksDB database will be stored.

    ```properties
    kafka.pip.rocksdb.path = "/tmp/rocksdb/shared"
    ```
    This path points to a shared RocksDB instance that can house multiple column families, one for each configured Kafka PIP.

By combining Kafka for real-time messaging and RocksDB for a fast local cache, `autho` can make fine-grained authorization decisions that react instantly to changes in your environment.
