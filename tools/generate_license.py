#!/usr/bin/env python3
"""
Autho licence generator — USAGE INTERNE UNIQUEMENT.
Ce script signe des tokens de licence avec la clé privée Ed25519.

La clé privée (license-private.pem) ne doit JAMAIS être commitée dans le repo.
Seule la clé publique (resources/license-public.pem) appartient au repo.

Usage:
    python3 tools/generate_license.py --customer "Mairie de Lyon" --tier pro --days 365
    python3 tools/generate_license.py --help

Dépendances:
    pip install cryptography
"""

import argparse
import base64
import datetime
import json
import sys
from pathlib import Path

try:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives.serialization import load_pem_private_key
except ImportError:
    print("ERROR: pip install cryptography", file=sys.stderr)
    sys.exit(1)

TIER_FEATURES = {
    "free":       [],
    "pro":        ["audit", "versioning", "explain", "simulate", "metrics"],
    "enterprise": ["audit", "versioning", "explain", "simulate", "metrics",
                   "kafka-pip", "multi-instance"],
}


def generate(private_key_path: Path, customer: str, tier: str,
             instances: int, decisions: int, days: int) -> str:
    if tier not in TIER_FEATURES:
        raise ValueError(f"Tier inconnu : {tier}. Valeurs : {list(TIER_FEATURES)}")

    raw = private_key_path.read_bytes()
    private_key: Ed25519PrivateKey = load_pem_private_key(raw, password=None)

    today = datetime.date.today()
    claims = {
        "customer":   customer,
        "tier":       tier,
        "features":   TIER_FEATURES[tier],
        "instances":  instances,
        "decisions":  decisions,
        "issued_at":  str(today),
        "expires_at": str(today + datetime.timedelta(days=days)),
    }

    payload_bytes = json.dumps(claims, separators=(",", ":")).encode("utf-8")
    payload_b64   = base64.urlsafe_b64encode(payload_bytes).rstrip(b"=").decode()

    sig_bytes = private_key.sign(payload_b64.encode("utf-8"))
    sig_b64   = base64.urlsafe_b64encode(sig_bytes).rstrip(b"=").decode()

    return f"{payload_b64}.{sig_b64}"


def main():
    parser = argparse.ArgumentParser(description="Génère un token de licence Autho signé Ed25519")
    parser.add_argument("--customer",    required=True,  help="Nom du client")
    parser.add_argument("--tier",        required=True,  choices=["free", "pro", "enterprise"])
    parser.add_argument("--days",        type=int, default=365, help="Durée de validité en jours")
    parser.add_argument("--instances",   type=int, default=1,   help="Nombre max d'instances")
    parser.add_argument("--decisions",   type=int, default=1_000_000, help="Décisions/mois (-1 = illimité)")
    parser.add_argument("--private-key", default="tools/license-private.pem",
                        help="Chemin vers la clé privée (défaut: tools/license-private.pem)")
    args = parser.parse_args()

    private_key_path = Path(args.private_key)
    if not private_key_path.exists():
        print(f"ERROR: clé privée introuvable : {private_key_path}", file=sys.stderr)
        sys.exit(1)

    token = generate(
        private_key_path=private_key_path,
        customer=args.customer,
        tier=args.tier,
        instances=args.instances,
        decisions=args.decisions,
        days=args.days,
    )

    print(token)


if __name__ == "__main__":
    main()
