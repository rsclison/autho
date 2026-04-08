# Guide d'Administration Sécurisé — Autho Authorization Server

**Version** : 0.1.0-SNAPSHOT
**Classification** : Document de sécurité — usage interne et évaluation CSPN
**Date** : 2026-03-25

---

## 1. Objectif et périmètre

Ce guide décrit les mesures d'administration sécurisée à appliquer lors du déploiement et de l'exploitation d'Autho. Il constitue un document requis dans le cadre d'une évaluation CSPN (Certification de Sécurité de Premier Niveau) par l'ANSSI.

**Public cible** : administrateurs système, DSI, équipes SecOps.

---

## 2. Architecture de déploiement attendue

Autho est un composant **PDP (Policy Decision Point)** au sens XACML. Il expose une API HTTP sur le port 8080 et **ne gère pas lui-même la terminaison TLS**. Le déploiement sécurisé impose l'architecture suivante :

```
[Clients PEP]
     │  HTTPS (TLS 1.2 min., TLS 1.3 recommandé)
     ▼
[Reverse proxy TLS] ← nginx / HAProxy / Kong / Ingress K8s
     │  HTTP (réseau interne protégé uniquement)
     ▼
[Autho :8080]
     │
     ├── H2 audit DB     (./resources/auditdb)
     └── H2 policy DB    (./resources/h2db)
```

**Hypothèse d'environnement obligatoire** : Autho ne doit jamais être exposé directement sur Internet ou sur un réseau non maîtrisé sans terminaison TLS en amont.

---

## 3. Génération et gestion des secrets

### 3.1 Variables obligatoires

Trois secrets sont obligatoires au démarrage. Leur absence ou leur longueur insuffisante (< 32 caractères) provoque l'arrêt immédiat du serveur.

| Variable | Usage | Longueur minimale |
|---|---|---|
| `JWT_SECRET` | Signature/vérification HMAC-SHA256 des tokens JWT (HS256) | 32 caractères (256 bits) |
| `API_KEY` | Authentification des applications de confiance via `X-API-Key` | 32 caractères |
| `AUDIT_HMAC_SECRET` | Chaîne HMAC-SHA256 du journal d'audit (tamper-evident) | 32 caractères (256 bits) |

### 3.1.1 Variable de licence (optionnelle)

`AUTHO_LICENSE_KEY` active les fonctionnalités Pro ou Enterprise. Sans cette variable, le serveur démarre en mode **Free** (décisions de base uniquement).

| Variable | Usage |
|---|---|
| `AUTHO_LICENSE_KEY` | Token de licence Ed25519 signé fourni par Autho |

**Fonctionnalités par tier :**

| Feature | Free | Pro | Enterprise |
|---|:---:|:---:|:---:|
| `isAuthorized`, `whoAuthorized`, `whatAuthorized` | ✓ | ✓ | ✓ |
| Audit HMAC-SHA256 (`/admin/audit/*`) | — | ✓ | ✓ |
| Versionnage des politiques + rollback | — | ✓ | ✓ |
| Explain & Simulate | — | ✓ | ✓ |
| Métriques Prometheus (`/metrics`) | — | ✓ | ✓ |
| Kafka PIP / RocksDB | — | — | ✓ |
| Synchronisation cache multi-instances | — | — | ✓ |

Un token invalide ou expiré est signalé dans les logs au démarrage ; le serveur bascule automatiquement en mode Free.

### 3.2 Génération des secrets

**Méthode recommandée — openssl (256 bits d'entropie) :**

```bash
export JWT_SECRET=$(openssl rand -hex 32)
export API_KEY=$(openssl rand -hex 32)
export AUDIT_HMAC_SECRET=$(openssl rand -hex 32)
```

**Méthode alternative — /dev/urandom :**

```bash
export JWT_SECRET=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | head -c 64)
```

**Ne jamais utiliser :**
- Des mots du dictionnaire ou phrases
- Des valeurs prévisibles (dates, noms, UUIDs séquentiels)
- Les valeurs d'exemple de la documentation

### 3.3 Stockage des secrets

**En production :**
- Utiliser un gestionnaire de secrets : HashiCorp Vault, AWS Secrets Manager, Azure Key Vault, ou Kubernetes Secrets chiffrés (avec KMS provider).
- Ne jamais stocker les secrets en clair dans des fichiers de configuration versionnés (`.env`, `docker-compose.yml`, etc.).
- Injecter via des variables d'environnement au runtime.

**En environnement de développement :**
- Utiliser un fichier `.env` local (ajouté dans `.gitignore`).
- Respecter quand même la longueur minimale de 32 caractères.

### 3.4 Rotation des secrets

La rotation d'un secret nécessite le redémarrage du serveur :

1. Générer le nouveau secret.
2. Mettre à jour la variable d'environnement dans le gestionnaire de secrets.
3. Redémarrer Autho (les anciens JWT en cours de validité seront invalidés lors de la rotation de `JWT_SECRET`).

**Note AUDIT_HMAC_SECRET** : la chaîne d'audit existante ne sera plus vérifiable avec le nouveau secret. Archiver les entrées précédentes avant rotation, ou maintenir une table de rotation des secrets d'audit avec leurs plages de dates.

---

## 4. Configuration TLS (reverse proxy)

### 4.1 Exigences minimales

Conformément au RGS v2.0 (Annexe B1 sur les algorithmes cryptographiques) :

| Paramètre | Exigence minimale | Recommandé |
|---|---|---|
| Protocole | TLS 1.2 | TLS 1.3 |
| Suites de chiffrement (TLS 1.2) | ECDHE-RSA-AES256-GCM-SHA384 | TLS 1.3 uniquement |
| Certificat serveur | RSA 3072 bits ou ECDSA P-256 | ECDSA P-384 |
| HSTS | Activé (max-age ≥ 31536000) | Avec includeSubDomains |

### 4.2 Exemple nginx minimal

```nginx
server {
    listen 443 ssl http2;
    server_name autho.example.fr;

    ssl_certificate     /etc/ssl/autho.crt;
    ssl_certificate_key /etc/ssl/autho.key;

    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:TLS_AES_256_GCM_SHA384;
    ssl_prefer_server_ciphers on;
    ssl_session_timeout 1d;
    ssl_session_cache   shared:SSL:10m;

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options DENY;
    add_header X-Content-Type-Options nosniff;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_set_header   Host $host;
        proxy_set_header   X-Real-IP $remote_addr;
        proxy_set_header   X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto https;
        proxy_read_timeout 30s;
    }
}

# Redirection HTTP → HTTPS
server {
    listen 80;
    server_name autho.example.fr;
    return 301 https://$host$request_uri;
}
```

---

## 5. Durcissement de l'environnement JVM

### 5.1 Options JVM recommandées

```bash
java \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -Xms256m -Xmx1g \
  -Djava.security.manager=disallow \
  -Dfile.encoding=UTF-8 \
  -Djava.awt.headless=true \
  -jar autho-standalone.jar
```

### 5.2 Principe du moindre privilège

- Exécuter Autho sous un compte système dédié sans shell ni privilèges root.
- Exemple Linux :

```bash
useradd --system --no-create-home --shell /sbin/nologin autho
chown -R autho:autho /opt/autho
chmod 750 /opt/autho
chmod 640 /opt/autho/resources/*.properties
```

- Le répertoire `resources/` contenant les bases H2 doit être accessible en lecture/écriture **uniquement** par le compte `autho`.

---

## 6. Configuration réseau et pare-feu

### 6.1 Règles pare-feu minimales

| Direction | Source | Destination | Port | Protocole | Description |
|---|---|---|---|---|---|
| Entrante | Reverse proxy uniquement | Autho | 8080/TCP | HTTP | API PDP |
| Sortante | Autho | LDAP/AD | 389 ou 636/TCP | LDAP/LDAPS | PIP LDAP (si activé) |
| Sortante | Autho | Kafka | 9092/TCP | Kafka | Invalidation cache (si activé) |
| Sortante | Autho | Collector OTel | 4317/TCP | gRPC | Traces (si activé) |
| Bloquée | * | Autho | 8080/TCP | HTTP | Toute source directe hors proxy |

### 6.2 Segmentation réseau recommandée

Autho doit être déployé dans un **segment réseau dédié** (VLAN ou namespace réseau Kubernetes), avec accès entrant limité au(x) seul(s) reverse proxy autorisé(s).

---

## 7. Limites de taux (rate limiting)

Le rate limiting est activé par défaut. En production, les valeurs par défaut peuvent nécessiter un ajustement selon le volume attendu :

| Variable | Défaut | Description |
|---|---|---|
| `RATE_LIMIT_ENABLED` | `true` | Activer/désactiver |
| `RATE_LIMIT_APIKEY_RPM` | `10000` | Limite pour les clients API Key (req/min) |
| `RATE_LIMIT_JWT_RPM` | `1000` | Limite pour les utilisateurs JWT (req/min) |
| `RATE_LIMIT_ANON_RPM` | `100` | Limite pour les requêtes anonymes (req/min) |

**En production : ne jamais désactiver le rate limiting** (`RATE_LIMIT_ENABLED=false`).

---

## 8. Chiffrement au repos des bases H2

Autho utilise deux bases H2 persistantes :
- `./resources/auditdb` — journal d'audit HMAC
- `./resources/h2db` — politiques XACML et historique de versions

### 8.1 Activation du chiffrement AES

Le chiffrement est activé en définissant les variables d'environnement correspondantes. H2 utilise AES-128 en mode CBC pour chiffrer les fichiers `.mv.db`.

```bash
export H2_AUDIT_CIPHER_KEY=$(openssl rand -hex 32)
export H2_POLICY_CIPHER_KEY=$(openssl rand -hex 32)
```

Lorsque ces variables sont définies, Autho ouvre les bases avec `CIPHER=AES` dans l'URL JDBC. Si elles sont absentes, un avertissement est loggué au démarrage et les bases restent non chiffrées.

**En production, ces clés sont obligatoires** (*Non dans la table = non bloquant au démarrage, mais la conformité RGS l'exige).

### 8.2 Migration d'une base non chiffrée vers chiffrée

Si des bases H2 existent déjà sans chiffrement (fichiers `resources/*.mv.db` présents), suivre cette procédure **avant** de définir `H2_AUDIT_CIPHER_KEY` / `H2_POLICY_CIPHER_KEY` :

#### Migration de la base d'audit

```bash
# 1. Exporter les données (sans clé = connexion non chiffrée)
java -cp target/autho-*-standalone.jar org.h2.tools.Script \
  -url "jdbc:h2:./resources/auditdb;AUTO_SERVER=TRUE" \
  -user sa -password "" \
  -script /tmp/audit_backup.sql

# 2. Supprimer les fichiers non chiffrés
rm -f resources/auditdb.mv.db resources/auditdb.trace.db resources/auditdb.lock.db

# 3. Définir la clé et recréer la base (Autho recréera la table au prochain démarrage)
export H2_AUDIT_CIPHER_KEY=$(openssl rand -hex 32)

# 4. Réimporter les données dans la base chiffrée
java -cp target/autho-*-standalone.jar org.h2.tools.RunScript \
  -url "jdbc:h2:./resources/auditdb;AUTO_SERVER=TRUE;CIPHER=AES" \
  -user sa -password "${H2_AUDIT_CIPHER_KEY} " \
  -script /tmp/audit_backup.sql

# 5. Vérifier la chaîne d'audit après redémarrage
curl -H "X-API-Key: $API_KEY" https://autho.example.fr/audit/verify
```

#### Migration de la base des politiques

```bash
# 1. Exporter
java -cp target/autho-*-standalone.jar org.h2.tools.Script \
  -url "jdbc:h2:./resources/h2db" \
  -user sa -password "" \
  -script /tmp/policy_backup.sql

# 2. Supprimer les fichiers non chiffrés
rm -f resources/h2db.mv.db resources/h2db.trace.db

# 3. Définir la clé
export H2_POLICY_CIPHER_KEY=$(openssl rand -hex 32)

# 4. Réimporter dans la base chiffrée
java -cp target/autho-*-standalone.jar org.h2.tools.RunScript \
  -url "jdbc:h2:./resources/h2db;CIPHER=AES" \
  -user sa -password "${H2_POLICY_CIPHER_KEY} " \
  -script /tmp/policy_backup.sql
```

> **Important** : la base d'audit chiffrée avec une clé donnée ne peut être ouverte qu'avec cette même clé. Stocker `H2_AUDIT_CIPHER_KEY` et `H2_POLICY_CIPHER_KEY` dans le même gestionnaire de secrets que les autres variables obligatoires.

---

## 9. Journal d'audit

### 9.1 Architecture du journal

Le journal d'audit est une **chaîne de hachage HMAC-SHA256** stockée dans une base H2 dédiée (`./resources/auditdb`). Chaque entrée contient :
- Le payload JSON de la décision d'autorisation
- Le hash SHA-256 du payload
- Le HMAC-SHA256 de la concaténation du hash précédent et du hash courant
- Un horodatage ISO-8601

Ce mécanisme garantit la **détection de toute altération** des entrées d'audit.

### 9.2 Vérification de l'intégrité

```bash
curl -H "X-API-Key: $API_KEY" https://autho.example.fr/audit/verify
```

Réponse attendue :
```json
{"valid": true, "total": 1247}
```

**Fréquence recommandée** : vérification quotidienne automatisée en production.

### 9.3 Archivage

- Les bases H2 (`auditdb.mv.db`) doivent être sauvegardées quotidiennement.
- Durée de rétention recommandée pour le secteur public : **5 ans** (conforme à la PSSI-E interministérielle).
- Avant toute rotation de `AUDIT_HMAC_SECRET`, exporter et archiver les entrées existantes.

---

## 10. Variables d'environnement — référence complète

| Variable | Obligatoire | Défaut | Description |
|---|---|---|---|
| `JWT_SECRET` | **Oui** | — | Secret HMAC-SHA256 pour JWT (≥ 32 chars) |
| `API_KEY` | **Oui** | — | Clé API applications de confiance (≥ 32 chars) |
| `AUDIT_HMAC_SECRET` | **Oui** | — | Secret HMAC chaîne d'audit (≥ 32 chars) |
| `AUTHO_LICENSE_KEY` | Non | — | Token de licence Ed25519 (mode Free si absent) |
| `H2_AUDIT_CIPHER_KEY` | Non* | — | Clé chiffrement AES base audit (recommandé production) |
| `H2_POLICY_CIPHER_KEY` | Non* | — | Clé chiffrement AES base policy+versions (recommandé production) |
| `PDP_CONFIG` | Non | `resources/pdp-prop.properties` | Chemin fichier de configuration |
| `PORT` | Non | `8080` | Port d'écoute HTTP |
| `MAX_REQUEST_SIZE` | Non | `1048576` | Taille max du corps de requête (octets) |
| `RATE_LIMIT_ENABLED` | Non | `true` | Activer le rate limiting |
| `RATE_LIMIT_APIKEY_RPM` | Non | `10000` | Limite API Key (req/min) |
| `RATE_LIMIT_JWT_RPM` | Non | `1000` | Limite JWT (req/min) |
| `RATE_LIMIT_ANON_RPM` | Non | `100` | Limite anonyme (req/min) |
| `KAFKA_ENABLED` | Non | `true` | Activer Kafka (invalidation cache) |
| `KAFKA_BOOTSTRAP_SERVERS` | Non | `localhost:9092` | Serveurs Kafka |
| `LDAP_PASSWORD` | Non | — | Mot de passe compte LDAP (PIP LDAP) |

---

## 11. Procédures opérationnelles

### 11.1 Démarrage sécurisé

```bash
# 1. Définir les secrets depuis le gestionnaire de secrets
export JWT_SECRET=$(vault kv get -field=jwt_secret secret/autho)
export API_KEY=$(vault kv get -field=api_key secret/autho)
export AUDIT_HMAC_SECRET=$(vault kv get -field=audit_hmac secret/autho)
export H2_AUDIT_CIPHER_KEY=$(vault kv get -field=h2_audit_key secret/autho)
export H2_POLICY_CIPHER_KEY=$(vault kv get -field=h2_policy_key secret/autho)

# 2. Activer la licence (Pro/Enterprise) — optionnel, mode Free si absent
export AUTHO_LICENSE_KEY=$(vault kv get -field=license_key secret/autho)

# 3. Lancer (le script vérifie les secrets obligatoires et avertit sur les clés H2)
./start.sh --prod
```

### 11.2 Vérification post-démarrage

```bash
# Health check
curl -s https://autho.example.fr/health | jq .

# Vérifier la chaîne d'audit
curl -s -H "X-API-Key: $API_KEY" https://autho.example.fr/audit/verify | jq .

# Métriques Prometheus
curl -s https://autho.example.fr/metrics | grep autho_
```

### 11.3 Réponse à un incident de sécurité

En cas de suspicion de compromission d'un secret :

1. **Isoler immédiatement** Autho du réseau (retirer du load balancer).
2. **Révoquer** le secret compromis dans le gestionnaire de secrets.
3. **Générer** un nouveau secret.
4. **Vérifier** l'intégrité de la chaîne d'audit (`/audit/verify`).
5. **Analyser** les logs d'accès du reverse proxy pour identifier les requêtes suspectes.
6. **Redémarrer** avec les nouveaux secrets.
7. **Notifier** le RSSI et, si applicable, l'ANSSI (qualification CSPN impose notification sous 72h pour incidents affectant la TOE).

---

## 12. Algorithmes cryptographiques

Conformément au RGS v2.0 Annexe B1 (liste des algorithmes approuvés par l'ANSSI) :

| Usage | Algorithme | Statut RGS |
|---|---|---|
| Signature JWT | HS256 (HMAC-SHA256) | Approuvé (SHA-256 ∈ famille SHA-2) |
| Chaîne d'audit | HMAC-SHA256 + SHA-256 | Approuvé |
| TLS (recommandé) | TLS 1.3, AES-256-GCM, ECDHE | Approuvé |
| Certificats | RSA ≥ 3072 bits ou ECDSA P-384 | Approuvé |

**Algorithmes explicitement rejetés par Autho :**
- `alg: none` dans les JWT (rejeté par buddy-auth avec `{:options {:alg :hs256}}`)
- HS384, HS512, RS256, etc. (non configurés — rejetés implicitement)

---

*Document établi dans le cadre de la démarche de qualification CSPN d'Autho.*
*À mettre à jour à chaque évolution majeure de la configuration de sécurité.*
