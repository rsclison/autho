# Demo Scenario Redesign Spec

**Goal:** Rework the demonstration so it tells a commercial, developer-facing story around PIP enrichment, auditability, impact/simulation, and Kafka-backed business-object ingestion.

**Scope:** This revision only changes the demo scripts and their accompanying documentation. It does not change authorization behavior or backend APIs.

**Expected user story:** A viewer can run the demo and see, in one coherent sequence, how Autho handles:
- a decision that depends on PIP-enriched data,
- a signed evidence export and verification flow,
- an impact analysis / simulation before a policy change,
- and a Kafka ingestion flow that populates business objects for a second operational mode.

**Non-goals:**
- No new backend endpoints.
- No change to policy semantics.
- No redesign of the admin UI.

**Success criteria:**
- The main demo script reads like a presentation, not a raw smoke test.
- The Kafka demo is clearly positioned as a second mode of object ingestion, not the main narrative.
- The documentation explains the order of the demo and why each chapter matters.
- The scripts remain runnable with the existing environment variables and Docker stack.
