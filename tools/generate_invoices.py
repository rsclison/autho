#!/usr/bin/env python

#
# A tool for generating random 'Facture' (Invoice) objects and publishing them to a Kafka topic.
#
# Usage:
#   python tools/generate_invoices.py <number_of_invoices>
#
# Example:
#   python tools/generate_invoices.py 100
#
# Dependencies:
#   This script requires the 'kafka-python' library.
#   Install it using: pip install kafka-python
#

import argparse
import json
import random
import uuid
from datetime import date, timedelta

def publish_to_kafka(topic, messages):
    """
    Publishes a list of key-value messages to a specified Kafka topic.
    """
    from kafka import KafkaProducer
    from kafka.errors import KafkaError

    producer = KafkaProducer(
        bootstrap_servers=['localhost:9092'],
        key_serializer=lambda k: k.encode('utf-8'),
        value_serializer=lambda v: json.dumps(v).encode('utf-8')
    )

    print(f"Connecting to Kafka and preparing to send {len(messages)} messages to topic '{topic}'...")

    for msg in messages:
        try:
            key = msg.get("id")
            if not key:
                print(f"Skipping message due to missing 'id': {msg}")
                continue

            future = producer.send(topic, key=key, value=msg)
            # Optionally block for a result to ensure messages are sent
            # record_metadata = future.get(timeout=10)
        except KafkaError as e:
            print(f"Error publishing message with key {key} to Kafka: {e}")

    # Block until all async messages are sent
    producer.flush()
    producer.close()
    print(f"Finished sending messages. Producer closed.")

def generate_random_invoice():
    """
    Generates a single invoice with random data.
    """
    # Generate a random date in 2024
    start_date = date(2024, 1, 1)
    end_date = date(2024, 12, 31)
    time_between_dates = end_date - start_date
    days_between_dates = time_between_dates.days
    random_number_of_days = random.randrange(days_between_dates)
    random_date = start_date + timedelta(days=random_number_of_days)

    return {
        "id": str(uuid.uuid4()),
        "montant": round(random.uniform(50.0, 5000.0), 2),
        "fournisseur": random.choice(["Fournisseur A", "Fournisseur B", "Services XYZ", "Tech Corp"]),
        "date": random_date.isoformat(),
        "nom_de_service": random.choice(["IT", "Marketing", "RH", "Ventes", "Ingénierie"])
    }

def main():
    """
    Main function to parse arguments and run the generator.
    """
    parser = argparse.ArgumentParser(description="Generate and publish random 'Facture' objects to Kafka.")
    parser.add_argument("count", type=int, help="The number of invoices to generate and publish.")
    parser.add_argument("--topic", type=str, default="facture-attributes", help="The Kafka topic to publish to.")
    args = parser.parse_args()

    print(f"Generating {args.count} invoices...")
    invoices = [generate_random_invoice() for _ in range(args.count)]

    publish_to_kafka(args.topic, invoices)

    print(f"\nSuccessfully generated and sent {args.count} invoices to topic '{args.topic}'.")

if __name__ == "__main__":
    main()
