# Demo Scenario Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rework the demonstration into a commercial, developer-facing story that highlights PIP enrichment, signed evidence/auditability, impact/simulation, and Kafka-backed business-object ingestion.

**Architecture:** Keep the runtime behavior unchanged and limit the work to the demo entrypoints and documentation. `demo_start.sh` becomes the narrated primary flow, while `demo_inject_kafka.sh` becomes the optional Kafka-backed follow-up chapter. The docs explain the order and the purpose of each chapter so the demo can be presented consistently.

**Tech Stack:** Bash, curl, Docker Compose, Markdown.

---

### Task 1: Rewrite the main demo narrative in `demo_start.sh`

**Files:**
- Modify: `demo_start.sh`

- [ ] **Step 1: Inspect the current script structure**

```bash
sed -n '1,260p' demo_start.sh
```

Confirm the existing `create_demo_policies`, `seed_audit`, and `show_evidence_bundle` functions before editing.

- [ ] **Step 2: Rework the flow into four explicit chapters**

Replace the current linear flow with a narrated sequence:

```bash
create_demo_policies
show_pip_decision_flow
show_evidence_bundle
show_impact_simulation
show_kafka_mode_preview
```

Add short `echo` headers before each chapter so the demo reads like a presentation, not a smoke test.

- [ ] **Step 3: Add a dedicated impact/simulation chapter**

Use the existing policy-impact API to show a policy candidate being analyzed before rollout:

```bash
curl_json -X POST "$BASE_URL/v1/policies/Facture/impact" -d '{
  "candidatePolicy": {
    "strategy": "almost_one_allow_no_deny",
    "rules": [
      {
        "name": "ALLOW-DEMO-CLIENT-AGGREGATE",
        "operation": "process",
        "priority": 10,
        "effect": "allow",
        "conditions": [
          ["=", ["Person", "$s", "client-id"], "001"],
          ["=", ["Context", "$c", "purpose"], "aggregate_invoice_total"]
        ]
      }
    ],
    "tests": []
  },
  "auditReplay": {
    "decision": "deny",
    "limit": 20
  }
}'
```

The output should call out the risk summary and why simulation is useful before deployment.

- [ ] **Step 4: Keep the evidence chapter but make it presentation-ready**

Retain the signed evidence export and verification, but print a brief sentence before each call that explains the value:

```bash
echo "Exporting a signed evidence bundle to prove the decision trail is tamper-evident..."
echo "Verifying the same bundle to show the proof is machine-checkable..."
```

- [ ] **Step 5: Run syntax validation**

Run:

```bash
bash -n demo_start.sh
```

Expected: no syntax errors.


### Task 2: Reframe the Kafka script as a secondary ingestion mode

**Files:**
- Modify: `demo_inject_kafka.sh`

- [ ] **Step 1: Inspect the current injection flow**

```bash
sed -n '1,220p' demo_inject_kafka.sh
```

Check the current messaging around RocksDB updates and decision re-evaluation.

- [ ] **Step 2: Update the script narration**

Make it explicit that Kafka is an alternative ingestion path for applications that can publish their business objects:

```bash
echo "Kafka mode: publishing business objects so Autho can resolve attributes without calling a PIP..."
echo "This demonstrates the second ingestion model, not the primary PIP-based flow."
```

Keep the existing decision checks, but frame them as the “after publication” state.

- [ ] **Step 3: Run syntax validation**

Run:

```bash
bash -n demo_inject_kafka.sh
```

Expected: no syntax errors.


### Task 3: Update the demo documentation

**Files:**
- Modify: `docker/README.md`

- [ ] **Step 1: Inspect the current demo README**

```bash
sed -n '1,220p' docker/README.md
```

Confirm where the script order and the scenario explanation currently live.

- [ ] **Step 2: Rewrite the narrative to match the new chapter order**

Describe the demo as:

```markdown
1. PIP-enriched decision on `Facture`
2. Signed evidence export and verification
3. Impact analysis / simulation before policy rollout
4. Kafka-backed business-object ingestion as a secondary mode
```

Keep the same commands, but explain what each one demonstrates.

- [ ] **Step 3: Verify the Markdown stays readable**

Open the updated README and ensure the sequence is understandable without reading the scripts.

---

### Task 4: Final verification and commit

**Files:**
- Validate: `demo_start.sh`
- Validate: `demo_inject_kafka.sh`
- Validate: `docker/README.md`

- [ ] **Step 1: Run the targeted checks**

```bash
bash -n demo_start.sh
bash -n demo_inject_kafka.sh
```

Expected: both commands succeed without output.

- [ ] **Step 2: Run the existing test suite**

```bash
JWT_SECRET=test-jwt-secret-32-chars-minimum-okay!! API_KEY=test-api-key-32-chars-minimum-okay!! AUDIT_HMAC_SECRET=audit-hmac-secret-32-chars-min-ok!! ./lein test
```

Expected: all tests pass.

- [ ] **Step 3: Commit the revision**

```bash
git add demo_start.sh demo_inject_kafka.sh docker/README.md
git commit -m "Revise demo narrative for commercial storytelling"
```
