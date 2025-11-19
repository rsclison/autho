# Policy Information Points (PIPs)

## Overview

Policy Information Points (PIPs) are external data sources that provide additional attributes to enrich authorization requests. When evaluating authorization rules, the PDP may need attributes that are not present in the original request. PIPs allow the system to fetch these attributes from various sources dynamically.

## PIP Types

Autho supports multiple types of PIPs:

### 1. REST PIP (`:rest`)
Fetches attributes from a REST API endpoint.

```clojure
{:class "User"
 :attributes [:email :department]
 :pip {:type :rest
       :url "http://user-service:8080/api/users"
       :verb "get"  ; or "post"
       :cacheable false}}
```

### 2. Kafka PIP (`:kafka-pip`)
Retrieves business objects from a local RocksDB cache populated by Kafka consumers.

```clojure
{:class "Facture"
 :pip {:type :kafka-pip
       :id-key :id}}
```

### 3. CSV PIP (`:csv`)
Reads attributes from a CSV file.

```clojure
{:class "Product"
 :attributes [:name :price :category]
 :pip {:type :csv
       :path "resources/products.csv"
       :id-key :productId}}
```

### 4. Internal PIP (`:internal`)
Calls an internal Clojure function to compute attributes.

```clojure
{:class "Request"
 :attributes [:priority]
 :pip {:type :internal
       :method "calculatePriority"}}
```

### 5. Java PIP (`:java`)
Integrates with Java-based attribute providers.

```clojure
{:class "Employee"
 :pip {:type :java
       :instance my-java-pip-instance}}
```

## Kafka PIP with Fallback

### The Problem

When using Kafka PIPs, the local RocksDB cache is populated asynchronously by consuming Kafka messages. This creates a challenge:

1. **Cold Start**: On first application startup, RocksDB is empty
2. **Missing Objects**: Some objects might not yet be in the cache
3. **Disabled Kafka**: When `KAFKA_ENABLED=false`, Kafka PIPs cannot function

Without a fallback mechanism, authorization requests would fail for objects not in the cache.

### The Solution: Fallback PIPs

You can configure a fallback PIP that will be used when the primary Kafka PIP returns `nil`. The fallback is triggered in three scenarios:

1. **Kafka Disabled**: When `KAFKA_ENABLED=false`
2. **Empty Cache**: When RocksDB doesn't contain the requested object
3. **Object Not Found**: When the object ID is not found in the Kafka topic

### Configuration Example

```clojure
{:class "Facture"
 :pip {:type :kafka-pip
       :id-key :id
       :fallback {:type :rest
                  :url "http://backend-service:8080/api/factures"
                  :verb "get"}}}
```

### How It Works

1. **Primary PIP (Kafka)**: First, the system attempts to retrieve the object from RocksDB
2. **Fallback Trigger**: If RocksDB returns `nil`, the fallback PIP is invoked
3. **Fallback Execution**: The REST endpoint is called to fetch the object
4. **Result**: The object from the fallback source is used for authorization

### Execution Flow

```
┌─────────────────────────────────────────────────┐
│  Authorization Request Needs Attribute          │
└────────────────┬────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────┐
│  Check if attribute is in request object        │
└────────┬───────────────────┬────────────────────┘
         │ Found             │ Not Found
         ▼                   ▼
    ┌────────┐      ┌────────────────────┐
    │ Return │      │  Find PIP for      │
    │ Value  │      │  class + attribute │
    └────────┘      └──────┬─────────────┘
                           │
                           ▼
                  ┌────────────────────┐
                  │ KAFKA_ENABLED?     │
                  └──┬──────────────┬──┘
                     │ true         │ false
                     ▼              ▼
            ┌────────────────┐  ┌──────────────┐
            │ Query RocksDB  │  │ Use Fallback │
            └───┬────────┬───┘  │ (if config)  │
                │ Found  │ nil  └──────────────┘
                ▼        ▼
           ┌────────┐  ┌──────────────┐
           │ Return │  │ Use Fallback │
           │ Object │  │ (if config)  │
           └────────┘  └──────────────┘
```

### Logging

The fallback mechanism provides detailed logging for troubleshooting:

- **Kafka Disabled**: `"Using fallback PIP because Kafka is disabled"`
- **Object Not Found**: `"Object not found in RocksDB, trying fallback PIP"`
- **Class and ID**: Logs include the class name and object ID for debugging

### Performance Considerations

**Advantages:**
- ✅ Guarantees availability even with empty RocksDB
- ✅ Graceful degradation when Kafka is disabled
- ✅ Transparent to authorization logic
- ✅ No code changes needed in rules

**Disadvantages:**
- ⚠️ Fallback REST calls add latency (network round-trip)
- ⚠️ Increased load on backend services during cold start
- ⚠️ Potential for inconsistency between Kafka and REST data

**Mitigation Strategies:**
1. **Cache Warming**: Pre-populate RocksDB before routing production traffic
2. **Circuit Breaker**: Implement timeouts and circuit breakers on REST PIPs
3. **Monitoring**: Track fallback usage to identify cache coverage issues

## Best Practices

### 1. Cache Warming Strategy

Before routing production traffic to a new instance:

```bash
# Wait for Kafka consumers to populate RocksDB
# Monitor consumer lag to ensure cache is populated
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group autho-pip-business-objects-compacted \
  --describe
```

### 2. Fallback Configuration

Always configure fallbacks for critical business objects:

```clojure
;; Critical objects - must have fallback
{:class "Facture"
 :pip {:type :kafka-pip
       :id-key :id
       :fallback {:type :rest
                  :url "http://backend-service:8080/api/factures"
                  :verb "get"}}}

;; Non-critical objects - can skip fallback
{:class "AuditLog"
 :pip {:type :kafka-pip
       :id-key :id}}
```

### 3. Monitoring Fallback Usage

Use structured logging to track fallback usage:

```bash
# Count fallback invocations
grep "kafka-fallback" application.log | wc -l

# Identify objects frequently missing from cache
grep "Object not found in RocksDB" application.log | \
  jq '.class, .id'
```

### 4. Testing Fallback Behavior

Test both scenarios:

```bash
# Test with Kafka enabled (should use RocksDB)
export KAFKA_ENABLED=true
curl -X POST http://localhost:8080/isAuthorized \
  -H "Authorization: Token $JWT" \
  -d '{"resource": {"class": "Facture", "id": "INV-123"}, ...}'

# Test with Kafka disabled (should use fallback)
export KAFKA_ENABLED=false
curl -X POST http://localhost:8080/isAuthorized \
  -H "Authorization: Token $JWT" \
  -d '{"resource": {"class": "Facture", "id": "INV-123"}, ...}'
```

## Complete Configuration Examples

### Example 1: Unified Kafka PIP (No Fallback)

Simplest configuration for development or when you can guarantee cache is populated:

```clojure
{:type :kafka-pip-unified
 :kafka-topic "business-objects-compacted"
 :kafka-bootstrap-servers "localhost:9092"
 :classes ["Facture" "Contrat" "EngagementJuridique"]}
```

### Example 2: Individual Kafka PIPs with REST Fallback

Production configuration with fallback for high availability:

```clojure
[
  {:class "Facture"
   :pip {:type :kafka-pip
         :id-key :id
         :fallback {:type :rest
                    :url "http://finance-service:8080/api/factures"
                    :verb "get"}}}

  {:class "Contrat"
   :pip {:type :kafka-pip
         :id-key :id
         :fallback {:type :rest
                    :url "http://contract-service:8080/api/contrats"
                    :verb "get"}}}

  {:class "EngagementJuridique"
   :pip {:type :kafka-pip
         :id-key :contractId  ; Custom ID field
         :fallback {:type :rest
                    :url "http://legal-service:8080/api/engagements"
                    :verb "get"}}}
]
```

### Example 3: Mixed PIP Strategy

Combine Kafka with other PIP types based on data characteristics:

```clojure
[
  ;; High-volume, frequently updated - use Kafka
  {:class "Facture"
   :pip {:type :kafka-pip
         :id-key :id
         :fallback {:type :rest
                    :url "http://backend:8080/api/factures"
                    :verb "get"}}}

  ;; Static reference data - use CSV
  {:class "Country"
   :attributes [:name :code :region]
   :pip {:type :csv
         :path "resources/countries.csv"
         :id-key :countryCode}}

  ;; User attributes - use LDAP
  {:class "Person"
   :pip {:type :ldap
         :ldap-server "ldap://ldap.company.com:389"
         :base-dn "ou=users,dc=company,dc=com"
         :filter "(uid={id})"
         :attributes [:name :email :department :role]}}
]
```

## Troubleshooting

### Problem: Fallback Always Being Used

**Symptoms:**
- Logs show continuous fallback invocations
- High latency on authorization requests

**Possible Causes:**
1. Kafka consumers not running
2. RocksDB not being populated
3. Wrong object IDs in Kafka messages
4. RocksDB path misconfigured

**Solutions:**
```bash
# Check Kafka consumer status
docker logs autho-app | grep "Kafka consumer"

# Verify RocksDB contents
curl http://localhost:8080/admin/listRDB \
  -H "X-API-Key: $API_KEY"

# Check Kafka topic
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic business-objects-compacted \
  --from-beginning --max-messages 10
```

### Problem: Fallback Not Working

**Symptoms:**
- Authorization fails with missing attributes
- No fallback logs appear

**Possible Causes:**
1. Fallback not configured in pips.edn
2. Fallback REST endpoint unreachable
3. Network issues

**Solutions:**
```bash
# Verify PIP configuration
cat resources/pips.edn | grep -A 5 "fallback"

# Test fallback REST endpoint directly
curl http://backend-service:8080/api/factures/INV-123

# Check network connectivity
nc -zv backend-service 8080
```

### Problem: Inconsistent Results

**Symptoms:**
- Different authorization decisions for same request
- Objects have outdated attributes

**Possible Causes:**
1. Kafka lag causing stale RocksDB data
2. Fallback REST returning different data than Kafka
3. Data synchronization issues

**Solutions:**
- Monitor Kafka consumer lag
- Ensure Kafka topics and REST APIs use same data source
- Implement eventual consistency monitoring

## Migration Guide

### From REST-Only to Kafka with Fallback

**Step 1:** Keep existing REST PIPs as-is
```clojure
{:class "Facture"
 :pip {:type :rest
       :url "http://backend:8080/api/factures"
       :verb "get"}}
```

**Step 2:** Add Kafka PIP with REST fallback (safe to deploy)
```clojure
{:class "Facture"
 :pip {:type :kafka-pip
       :id-key :id
       :fallback {:type :rest
                  :url "http://backend:8080/api/factures"
                  :verb "get"}}}
```

**Step 3:** Monitor fallback usage, ensure Kafka cache is populated

**Step 4:** (Optional) Remove fallback once confident in cache coverage

## See Also

- [Kafka Integration Guide](KAFKA_TESTING_GUIDE.md)
- [Time Travel Authorization](TIME_TRAVEL_AUTHORIZATION.md)
- [Unified Topic Architecture](UNIFIED_TOPIC_ARCHITECTURE.md)
