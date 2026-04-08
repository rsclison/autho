# Policies Sidebar Navigation Design

Date: 2026-03-30
Topic: Admin UI policies navigation refactor
Status: Draft approved in chat, pending user review of written spec

## Goal

Remove the dedicated policies list column from the `Politiques` page and move policy-class navigation into the main application sidebar under the `Politiques` item.

The new navigation should:
- free horizontal space for the policy editor and governance screen
- keep policy selection easy and fast
- avoid duplicating policy navigation in both the main sidebar and the page body
- support a manually collapsible policies sub-list

## Why Change

The current `Politiques` area uses a full page-left column to list resource classes while the main application already has a top-level `Politiques` navigation item.

This creates two issues:
- redundant navigation concepts for the same area
- wasted horizontal space on screens where the editor and governance layout need more room

Moving policy-class navigation into the sidebar solves both without changing policy behavior.

## Proposed UX

The main sidebar keeps `Politiques` as a parent navigation item.

When the user is in the policies section, the item can be expanded manually to reveal the list of policy resource classes underneath it.

Behavior:
- the policies group is manually collapsible via a chevron control
- expanded/collapsed state is controlled by the user, not forced by route changes
- when expanded, each resource class is shown as a nested clickable item
- the currently selected policy is visibly highlighted
- the main `Politiques` item still navigates to the policies section root

## Navigation Model

### Sidebar

`Politiques` becomes a group with two behaviors:
- click label area: navigate to `/policies`
- click chevron: expand or collapse the nested resource-class list

Nested items:
- appear only when the group is expanded
- are loaded from the existing policies query
- navigate directly to `/policies/:resourceClass`
- preserve existing navigation to `/policies/:resourceClass/governance`

### Policies Page

The page no longer renders its own left-side policy list panel.

The main content area starts directly with:
- strategy strip
- policy editor, or governance page, depending on route

If no policy is selected, the empty state remains in the main content area.

## Interaction Rules

- expanded state must be manual and repliable by the user
- nested items should visually read as children of `Politiques`, not as separate top-level navigation items
- nested items should use tighter spacing and smaller typography than top-level items
- if the policies query has no items, the sidebar should not render an empty nested list

## Layout Impact

Removing the page-local policies column gives more space to:
- the rule editor
- version history side panel
- governance screen content

This refactor should not change policy CRUD, history, diff, governance, or routing semantics.

## Data Flow

The sidebar will reuse the existing policies query already used in the policies page.

The policies page can stop fetching policy classes solely for the removed local sidebar if that query is no longer needed there.

## Error Handling

If policy list loading fails in the sidebar:
- keep the top-level `Politiques` item usable
- omit or degrade the nested list gracefully
- do not block navigation to the policies section root

## Testing Scope

Implementation should cover at least:
- expand/collapse behavior of the `Politiques` group
- nested item navigation to a selected resource class
- removal of the dedicated policy list column from the policies page
- active highlighting for the selected policy item in the sidebar

If UI test infrastructure remains incomplete, build verification and manual route checks are still required.

## Non-Goals

This change does not redesign non-policy navigation groups.

This change does not alter the policy editor, governance business logic, or versioning behavior.

This change does not introduce multi-level nesting elsewhere in the app.

## Implementation Notes

Preferred implementation:
- update the shared sidebar component to support an expandable `Politiques` group
- keep policy routing unchanged
- simplify `PoliciesPage` by removing the dedicated left column and letting the main content use the recovered width

## Review Checklist

Spec review results:
- no placeholders remain
- no contradictions found between sidebar behavior and page layout changes
- scope remains focused on navigation and layout only
- routing remains compatible with the current dedicated governance screen
