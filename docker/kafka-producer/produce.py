#!/usr/bin/env python3
"""
Autho Kafka Producer — injecte des objets de test dans les topics Kafka.

Topic cible : business-objects-compacted (unified PIP)
Format des messages :
  clé   = id de l'objet (ex: "FAC-0001")
  valeur = JSON {"class": "Facture", "id": "FAC-0001", ...attributs}

Usage :
  python produce.py                          # menu interactif
  python produce.py --class Facture          # 20 Factures
  python produce.py --class Contrat -n 50    # 50 Contrats
  python produce.py --all                    # toutes les classes
  python produce.py --class Facture --file factures.json  # depuis un fichier
  python produce.py --invalidate subject alice            # invalider cache
"""

import argparse
import json
import random
import sys
import time
from datetime import datetime, timedelta
from kafka import KafkaProducer
from kafka.errors import NoBrokersAvailable

# ─── Configuration ────────────────────────────────────────────────────────────

DEFAULT_BOOTSTRAP = "localhost:9092"
UNIFIED_TOPIC     = "business-objects-compacted"
INVALIDATION_TOPIC = "autho-cache-invalidation"

SERVICES     = ["service1", "service2", "service3", "service4"]
DEPARTMENTS  = ["dept1", "dept2", "dept3", "dept4", "dept5"]
STATUTS_FAC  = ["brouillon", "validée", "payée", "annulée", "en_attente"]
STATUTS_CTR  = ["en_cours", "signé", "résilié", "expiré", "en_négociation"]
STATUTS_EJ   = ["en_cours", "clôturé", "suspendu"]
TYPES_EJ     = ["marché", "convention", "accord_cadre", "délégation_service"]


# ─── Générateurs de données ───────────────────────────────────────────────────

def make_facture(i: int) -> dict:
    service = random.choice(SERVICES)
    return {
        "class":    "Facture",
        "id":       f"FAC-{i:04d}",
        "montant":  random.randint(500, 200_000),
        "statut":   random.choice(STATUTS_FAC),
        "service":  service,
        "emetteur": f"fournisseur-{random.randint(1, 30)}",
        "date":     rand_date(-365, 0),
        "echeance": rand_date(0, 90),
    }


def make_contrat(i: int) -> dict:
    dept = random.choice(DEPARTMENTS)
    return {
        "class":             "Contrat",
        "id":                f"CTR-{i:04d}",
        "montant":           random.randint(5_000, 500_000),
        "statut":            random.choice(STATUTS_CTR),
        "owner-department":  dept,
        "contains-pii":      random.choice([True, False]),
        "cocontractant":     f"partenaire-{random.randint(1, 20)}",
        "date-debut":        rand_date(-730, -30),
        "date-fin":          rand_date(30, 730),
    }


def make_engagement(i: int) -> dict:
    dept = random.choice(DEPARTMENTS)
    return {
        "class":                  "EngagementJuridique",
        "id":                     f"EJ-{i:04d}",
        "montant":                random.randint(10_000, 1_000_000),
        "statut":                 random.choice(STATUTS_EJ),
        "type":                   random.choice(TYPES_EJ),
        "responsible-department": dept,
        "required-clearance-level": random.randint(1, 5),
        "code":                   f"CODE-{random.randint(100, 999)}",
        "date-debut":             rand_date(-365, 0),
    }


GENERATORS = {
    "Facture":             make_facture,
    "Contrat":             make_contrat,
    "EngagementJuridique": make_engagement,
}


def rand_date(offset_from: int, offset_to: int) -> str:
    delta = random.randint(offset_from, offset_to)
    return (datetime.now() + timedelta(days=delta)).strftime("%Y-%m-%d")


# ─── Producteur Kafka ─────────────────────────────────────────────────────────

def make_producer(bootstrap: str) -> KafkaProducer:
    for attempt in range(1, 6):
        try:
            producer = KafkaProducer(
                bootstrap_servers=bootstrap,
                key_serializer=lambda k: k.encode("utf-8") if k else None,
                value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
                acks="all",
                retries=3,
                compression_type="gzip",
            )
            print(f"✓ Connecté à Kafka ({bootstrap})")
            return producer
        except NoBrokersAvailable:
            print(f"  Tentative {attempt}/5 — Kafka non disponible, attente 3s…")
            time.sleep(3)
    print(f"✗ Impossible de se connecter à Kafka ({bootstrap})", file=sys.stderr)
    sys.exit(1)


def produce_objects(producer: KafkaProducer, class_name: str, count: int, topic: str) -> int:
    if class_name not in GENERATORS:
        print(f"✗ Classe inconnue : {class_name}. Disponibles : {list(GENERATORS)}", file=sys.stderr)
        return 0

    gen = GENERATORS[class_name]
    sent = 0
    start_idx = random.randint(1, 9000)

    print(f"  → {count} objets {class_name} sur '{topic}'…")
    for i in range(start_idx, start_idx + count):
        obj = gen(i)
        future = producer.send(topic, key=obj["id"], value=obj)
        future.get(timeout=10)
        sent += 1

    producer.flush()
    print(f"  ✓ {sent} messages envoyés")
    return sent


def produce_from_file(producer: KafkaProducer, path: str, topic: str) -> int:
    with open(path, encoding="utf-8") as f:
        objects = json.load(f)

    if not isinstance(objects, list):
        objects = [objects]

    sent = 0
    for obj in objects:
        key = str(obj.get("id", f"obj-{sent}"))
        future = producer.send(topic, key=key, value=obj)
        future.get(timeout=10)
        sent += 1

    producer.flush()
    print(f"  ✓ {sent} messages envoyés depuis {path}")
    return sent


def invalidate_cache(producer: KafkaProducer, type_: str, key: str) -> None:
    msg = {"type": type_, "key": key, "timestamp": int(time.time() * 1000)}
    future = producer.send(INVALIDATION_TOPIC, key=key, value=msg)
    future.get(timeout=10)
    producer.flush()
    print(f"  ✓ Invalidation envoyée : {type_}={key}")


# ─── Menu interactif ─────────────────────────────────────────────────────────

def interactive_menu(producer: KafkaProducer) -> None:
    classes = list(GENERATORS.keys())
    while True:
        print("\n╔════════════════════════════════════════╗")
        print("║      Autho Kafka Producer — Menu       ║")
        print("╠════════════════════════════════════════╣")
        for i, c in enumerate(classes, 1):
            print(f"║  {i}. Injecter des {c:<26}║")
        print(f"║  {len(classes)+1}. Injecter toutes les classes        ║")
        print(f"║  {len(classes)+2}. Invalider une entrée de cache       ║")
        print(f"║  {len(classes)+3}. Quitter                             ║")
        print("╚════════════════════════════════════════╝")

        try:
            choice = int(input("Choix : "))
        except (ValueError, EOFError):
            continue

        if 1 <= choice <= len(classes):
            cls = classes[choice - 1]
            try:
                n = int(input(f"Nombre d'objets {cls} (défaut 20) : ") or "20")
            except ValueError:
                n = 20
            produce_objects(producer, cls, n, UNIFIED_TOPIC)

        elif choice == len(classes) + 1:
            try:
                n = int(input("Nombre par classe (défaut 20) : ") or "20")
            except ValueError:
                n = 20
            for cls in classes:
                produce_objects(producer, cls, n, UNIFIED_TOPIC)

        elif choice == len(classes) + 2:
            type_ = input("Type (subject/resource/policy/decision) : ").strip() or "subject"
            key   = input("Clé : ").strip()
            if key:
                invalidate_cache(producer, type_, key)

        elif choice == len(classes) + 3:
            print("Au revoir.")
            break


# ─── Point d'entrée ───────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="Autho Kafka Producer")
    parser.add_argument("--bootstrap", default=DEFAULT_BOOTSTRAP,
                        help=f"Kafka bootstrap servers (défaut: {DEFAULT_BOOTSTRAP})")
    parser.add_argument("--topic", default=UNIFIED_TOPIC,
                        help=f"Topic cible (défaut: {UNIFIED_TOPIC})")
    parser.add_argument("--class", dest="class_name",
                        choices=list(GENERATORS.keys()),
                        help="Classe d'objets à injecter")
    parser.add_argument("-n", "--count", type=int, default=20,
                        help="Nombre d'objets à générer (défaut: 20)")
    parser.add_argument("--all", action="store_true",
                        help="Injecter toutes les classes")
    parser.add_argument("--file",
                        help="Chemin vers un fichier JSON à injecter")
    parser.add_argument("--invalidate", nargs=2, metavar=("TYPE", "KEY"),
                        help="Invalider une entrée de cache (ex: --invalidate subject alice)")

    args = parser.parse_args()
    producer = make_producer(args.bootstrap)

    try:
        if args.invalidate:
            invalidate_cache(producer, args.invalidate[0], args.invalidate[1])

        elif args.file:
            produce_from_file(producer, args.file, args.topic)

        elif args.all:
            total = 0
            for cls in GENERATORS:
                total += produce_objects(producer, cls, args.count, args.topic)
            print(f"\n✓ Total : {total} messages envoyés")

        elif args.class_name:
            produce_objects(producer, args.class_name, args.count, args.topic)

        else:
            interactive_menu(producer)

    finally:
        producer.close()


if __name__ == "__main__":
    main()
