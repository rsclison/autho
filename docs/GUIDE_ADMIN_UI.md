# Autho — Administration UI User Guide

## Access

The administration interface is a web application available at:

```
http://<server>:8080/admin/ui
```

It requires authentication via JWT or API Key. A login form is shown on first access or after session expiry.

---

## Navigation

The interface is organised into six sections accessible from the side menu:

| Section | Icon | Purpose |
|---------|------|---------|
| **Dashboard** | Activity | Real-time metrics overview |
| **Policies** | Shield | Create and manage access policies |
| **Simulator** | Lightning | Interactive decision testing |
| **Audit** | Clock | Decision log |
| **Infrastructure** | Database | Cache, circuit breakers, admin actions |
| **Settings** | Gear | Theme, session |

---

## Dashboard

The dashboard displays in real time:

- **Cached decisions** — number of decisions served from the local cache
- **Active policies** — number of resource classes with a defined policy
- **Cache hit rate** — hits/misses ratio on the decision cache
- **Server status** — version, rules repository status, uptime
- **Activity chart** — allow/deny breakdown over recent decisions
- **Circuit breakers** — state of REST PIPs (closed / open / half-open)
- **Recent decisions** — latest entries from the audit log

---

## Policies

### Overview

The left panel lists all policies by resource class. Clicking on a class opens its editor.

### Creating a Policy

1. Click the **+** button at the top of the left panel
2. Enter the resource class name (e.g. `Invoice`, `MedicalRecord`)
3. The editor opens with a JSON skeleton to fill in

### Policy Format

```json
{
  "strategy": "almost_one_allow_no_deny",
  "rules": [
    {
      "name": "R-ALLOW",
      "priority": 0,
      "operation": "read",
      "effect": "allow",
      "conditions": [
        ["=", ["Person", "$s", "service"], ["Invoice", "$r", "service"]]
      ]
    }
  ]
}
```

**Rule fields:**

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Unique rule identifier |
| `priority` | integer | Conflict resolution priority (higher value = higher priority) |
| `operation` | string | Target operation (`"read"`, `"write"`, etc.) — omit to match all operations |
| `effect` | `"allow"` \| `"deny"` | Effect applied when all conditions are met |
| `conditions` | array | List of conditions (all must be true) |

**Strategy `almost_one_allow_no_deny`:**
The decision is **allow** if at least one `allow` rule matches and its priority is greater than or equal to the highest-priority matching `deny` rule. A `deny` rule with higher priority than all matching `allow` rules wins.

**Condition format:**
```json
["operator", "operand1", "operand2"]
```

An operand is either a string literal or an attribute reference:
```json
["ClassName", "$s", "attribute-name"]   // $s = subject
["ClassName", "$r", "attribute-name"]   // $r = resource
```

**Available operators:**

| Operator | Example | Description |
|----------|---------|-------------|
| `=` | `["=", ["Person", "$s", "service"], ["Invoice", "$r", "service"]]` | Equality |
| `diff` | `["diff", ["Person", "$s", "status"], "suspended"]` | Inequality |
| `<`, `>`, `<=`, `>=` | `[">=", ["Person", "$s", "clearance"], ["Invoice", "$r", "level"]]` | Numeric comparison |
| `in` | `["in", ["Person", "$s", "role"], "DPO,manager,legal-counsel"]` | Set membership |
| `notin` | `["notin", ["Person", "$s", "role"], "intern,contractor"]` | Non-membership |
| `date>` | `["date>", ["Person", "$s", "end-date"], "2026-01-01"]` | ISO date comparison |

### Saving a Policy

Click **Save** (floppy disk icon). If the policy is invalid (malformed JSON or schema violation), an error message is shown.

Every save automatically creates a new entry in the version history.

### Deleting a Policy

Click the **Trash** icon at the top of the editor, then confirm.

### YAML Import

The **Upload** icon allows importing a policy from a YAML file. The YAML format is equivalent to JSON but may be more readable for large policies.

### Version History

The **Clock** icon opens the history panel, listing all saved versions with their author, comment, and timestamp.

Actions available per version:
- **View** — displays the JSON content of that version
- **Compare** — opens a side-by-side diff between two versions (GitCompare icon)
- **Restore** — reinstates that version as the active policy (rollback)

---

## Simulator

The simulator lets you test an authorisation decision with no impact on the audit log or the cache.

### Request Form

| Field | Description |
|-------|-------------|
| **Subject — id** | Subject identifier (e.g. `001`) |
| **Subject — attributes** | Additional attributes as JSON (optional) |
| **Resource — class** | Resource class (dropdown populated from existing policies) |
| **Resource — id** | Resource identifier |
| **Operation** | Dropdown of operations defined in the selected policy |
| **Context** | Contextual attributes as JSON (date, application, etc.) — optional |

### Result

After clicking **Simulate**, the interface displays:

- The **decision** (ALLOW in green / DENY in red) with a visual badge
- The **list of evaluated rules**, each showing:
  - its name and effect
  - its result (match / no-match)
  - its detailed conditions

The simulator calls `POST /explain` internally — it returns exactly the same decision as the production engine for the same request.

---

## Audit

### Search

The search form accepts the following filters:

| Filter | Description |
|--------|-------------|
| **Subject** | Subject id (exact match) |
| **Resource class** | Resource class (e.g. `Invoice`) |
| **Decision** | `allow`, `deny`, or all |
| **From / To** | Date range (format `YYYY-MM-DD`) |

Click **Search** to run the paginated query.

### Results Table

Columns: timestamp, subject, resource class, resource id, operation, decision, matched rules.

- Click a column header to sort
- Navigate pages with the arrows at the bottom of the table

### CSV Export

The **Export CSV** button downloads all entries matching the current filter as a CSV file.

### Integrity Verification

The **Verify chain** button runs a cryptographic check of the audit chain (chained SHA-256 hashes). A success or error message indicates whether any entries have been tampered with or deleted.

---

## Infrastructure

### Cache Metrics

Three progress bars show the hit/miss rate for:
- the **decision** cache
- the **subject** cache
- the **resource** cache

### Circuit Breakers

List of REST PIPs with their current state:
- **Closed** (green) — normal operation
- **Open** (red) — PIP in error, requests temporarily blocked
- **Half-open** (orange) — recovery phase

### Admin Actions

| Action | Effect |
|--------|--------|
| **Clear cache** | Removes all cache entries (decisions, subjects, resources) |
| **Invalidate entry** | Removes the cache entry for a specific subject or resource |
| **Reload rules** | Re-reads `resources/jrules.edn` and updates in-memory policies |
| **Reload persons** | Re-reads the LDAP directory and updates `personSingleton` |
| **Reinitialise** | Full PDP reset (rules + PIPs + cache) |

> These actions are immediate and require no confirmation. Clearing the cache causes a temporary performance drop while it repopulates.

---

## Settings

### Appearance

Toggle between **light** and **dark** mode. The preference is saved in the browser (localStorage).

### Session

Displays the current JWT token (masked). The **Log out** button removes the token and redirects to the login page.

---

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+S` (inside the policy editor) | Save the policy |

---

## Troubleshooting

| Symptom | Likely cause | Action |
|---------|-------------|--------|
| Unexpected decision | Stale cache | Infrastructure → Invalidate the entry |
| Policy change not taking effect | Rules not reloaded | Infrastructure → Reload rules |
| Missing subject attributes | Stale `personSingleton` | Infrastructure → Reload persons |
| Circuit breaker open | REST PIP unavailable | Check the external PIP service |
| `POLICY_NOT_FOUND` error | No policy for that resource class | Policies → Create a policy for that class |
