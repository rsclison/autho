# Gouvernance Policy Screen Design

Date: 2026-03-27
Topic: Admin UI governance layout refactor
Status: Draft approved in chat, pending user review of written spec

## Goal

Move policy governance out of the cramped in-editor side panel into a dedicated screen that is easier to understand and safer to use for occasional governance tasks.

The new screen should optimize for rare but important actions:
- compare baseline and candidate policy versions
- run impact previews on reference requests
- inspect the latest impact result
- review, approve, or reject a preview
- deploy an approved preview
- inspect recent governance timeline events

## Why Change

The current layout mixes three dense concerns in one workspace:
- rule editing
- governance operations
- timeline/history inspection

This produces readability problems:
- visual collisions in the top toolbar
- narrow rule cards with crowded conditions
- governance controls compressed into a panel that feels secondary despite having critical actions
- timeline disconnected from the workflow

Because governance is used rarely, the UI should privilege clarity over compactness.

## Proposed UX

Clicking `Gouvernance` from the policy editor opens a dedicated governance screen for the current resource class instead of a docked panel.

The governance screen keeps a strong task-first structure:
- top header with resource class, current strategy, and a back button to the policy editor
- left column for comparison setup and reference request input
- central area for latest preview summary and preview actions
- lower section for persisted preview history with review and rollout actions
- right column for timeline events and filters

This layout keeps the workflow visible without forcing the user to manage editing and governance at the same time.

## Screen Structure

### 1. Header

Contents:
- resource class name
- current strategy badge
- optional current version badge if already available
- `Retour a la policy` action

Rules:
- remove redundant repeated governance titles from nested cards
- keep the page title singular and obvious: `Gouvernance` or `Gouvernance de la policy`

### 2. Preview Setup Column

Contents:
- baseline version select
- candidate version select
- reference requests editor
- primary action button to generate impact preview

Rules:
- labels should read as a sequence, not as unrelated fields
- textarea should be large enough to edit realistic JSON without cramped scrolling
- helper text should explain that requests must reflect realistic subject/resource/operation combinations

### 3. Latest Preview Summary

Contents:
- changed decisions
- revocations
- impacted subjects
- impacted resources
- high-risk visual state if present

Rules:
- this block sits near the generate button and updates immediately after preview generation
- if no preview exists yet, show an informative empty state instead of a low-contrast blank card

### 4. Preview History

Contents:
- persisted preview cards
- review state
- rollout state
- actions: mark reviewed, approve, reject, deploy
- linked deployed versions if any

Rules:
- actions remain on the same card as the preview they affect
- cards should visually distinguish draft, approved, rejected, and deployed states
- the list must remain readable even with many entries

### 5. Timeline Column

Contents:
- filter controls: all, reviews, deploys
- chronological event cards

Rules:
- timeline remains visible while reviewing history
- event cards should use the backend event model directly and tolerate missing optional fields
- empty state should clearly state that there are no matching events

## Navigation Behavior

From the policies page:
- clicking `Gouvernance` navigates to a dedicated route scoped to the selected resource class
- returning to the editor should preserve the selected policy

Likely route shape:
- `/policies/:resourceClass/governance`

Alternative acceptable shape:
- `/admin/policies/:resourceClass/governance`

The exact route should follow the router structure already present in the admin UI.

## Data Flow

The new screen should reuse existing API hooks where possible:
- versions
- impact analysis
- impact history
- review update
- rollout
- timeline

No backend behavior change is required for the layout refactor itself.

The screen should continue using the normalized policy/version data paths already fixed during this session.

## Error Handling

The screen must show errors inline for:
- invalid JSON in reference requests
- failed impact analysis
- failed review update
- failed rollout
- failed timeline loading

Errors should stay near the affected section instead of appearing as disconnected page-level failures.

## Testing Scope

Implementation should include at least:
- route rendering for the dedicated governance screen
- navigation from policies editor to governance screen and back
- layout sanity for main regions
- smoke coverage for existing governance actions still rendering in the new screen

If test infrastructure is incomplete, build verification is still required and any missing test dependency should be called out explicitly.

## Non-Goals

This change does not redesign the policy rule editor itself.

This change does not alter governance business logic, approval rules, or rollout semantics.

This change does not introduce a wizard flow unless later requested.

## Implementation Notes

Preferred approach:
- extract governance into a dedicated page component
- keep `PolicyGovernancePanel` only if reused internally after simplification, otherwise rename to page-oriented component
- simplify the policies editor so opening governance no longer squeezes the rule editor

## Review Checklist

Spec review results:
- no placeholders remain
- no contradictions found between layout, data flow, and navigation
- scope remains focused on layout/navigation and not broader governance logic
- route naming left intentionally flexible but bounded by current router structure
