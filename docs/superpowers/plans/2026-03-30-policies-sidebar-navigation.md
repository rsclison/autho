# Policies Sidebar Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move policy-class navigation from the policies page body into a manually collapsible `Politiques` group in the main sidebar.

**Architecture:** Reuse the existing policies query in the shared sidebar to render a nested list of resource classes under the `Politiques` item. Simplify `PoliciesPage` by removing the local policy list column so the editor and governance screen use the recovered width.

**Tech Stack:** React 19, TypeScript, React Router, TanStack Query, existing admin UI layout components, Vite build verification.

---

## File Structure

- Modify: `admin-ui/src/components/layout/Sidebar.tsx`
  Responsibility: add expandable `Politiques` group, nested policy-class items, active highlighting, and graceful loading/error behavior.
- Modify: `admin-ui/src/pages/PoliciesPage.tsx`
  Responsibility: remove the dedicated policy list column and let the content area start directly with policy content.
- Test: `admin-ui/src/test/policyDocument.test.ts`
  Responsibility: keep existing regression coverage untouched.
- Optional Test: `admin-ui/src/test/policiesSidebar.test.ts` or a pure helper test if UI tests become feasible.
  Responsibility: cover expand/collapse and path behavior if the frontend test environment allows it.

## Task 1: Add Expandable Policies Group In Sidebar

**Files:**
- Modify: `admin-ui/src/components/layout/Sidebar.tsx`

- [ ] **Step 1: Write the failing test or route helper scaffold**

```ts
it('renders policy items under the Politiques group when expanded', () => {
  // render sidebar with policies data
  // expand group
  // expect nested resource class links
})
```

If frontend UI tests are still blocked by missing environment dependencies, note that and use build + manual verification instead.

- [ ] **Step 2: Run verification for the red state**

Run one of:
```bash
cd /home/rsclison/autho/admin-ui
npm exec vitest run --config vitest.node.config.ts src/test/<sidebar-test>.ts
```
Or fallback:
```bash
cd /home/rsclison/autho/admin-ui
npm run build
```

Expected before implementation:
- sidebar has no nested policy list yet, or
- build passes but the feature is still absent

- [ ] **Step 3: Add local expanded state and policies query to the sidebar**

Implement the sidebar structure with a special `Politiques` group:
```tsx
const [policiesOpen, setPoliciesOpen] = useState(true)
const { data: policies } = usePolicies()
const policyClasses = policies ? Object.keys(policies) : []
```

Render:
- top-level `Politiques` nav row
- chevron button for manual expand/collapse
- nested list only when `policiesOpen === true` and policy classes exist

- [ ] **Step 4: Add active nested item highlighting**

Nested links should:
```tsx
<NavLink to={`/policies/${rc}`}>...</NavLink>
```

Mark active when the current path starts with `/policies/${rc}` so governance subroutes also highlight the selected policy.

- [ ] **Step 5: Keep root navigation usable even if the list is unavailable**

Ensure:
- `Politiques` top-level link still goes to `/policies`
- loading or missing policies does not break the parent item
- no empty nested container is rendered when there are no policies

- [ ] **Step 6: Run verification**

Run:
```bash
cd /home/rsclison/autho/admin-ui
npm run build
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add admin-ui/src/components/layout/Sidebar.tsx
 git commit -m "feat: add collapsible policy navigation to sidebar"
```

## Task 2: Remove Dedicated Policy List Column From Policies Page

**Files:**
- Modify: `admin-ui/src/pages/PoliciesPage.tsx`

- [ ] **Step 1: Write the failing test or layout expectation scaffold**

```ts
it('renders policies content without a dedicated left-side policy list column', () => {
  // render policies page
  // expect main content without page-local policy list sidebar
})
```

- [ ] **Step 2: Run verification for the red state**

Run fallback verification if UI tests remain unavailable:
```bash
cd /home/rsclison/autho/admin-ui
npm run build
```

Expected before implementation: page still includes a left column with local policy navigation.

- [ ] **Step 3: Remove `PolicyList` from the page layout**

Refactor `PoliciesPage` so the main wrapper starts directly with the policy content region:
```tsx
<div className="h-[calc(100vh-3.5rem)] flex border border-border rounded-xl overflow-hidden bg-card">
  <div className="flex-1 flex flex-col min-w-0">
    ...
  </div>
</div>
```

Keep route-driven resource selection intact.

- [ ] **Step 4: Preserve creation and YAML import affordances**

Move the actions that were tied to the removed local sidebar into the empty state and/or a compact top toolbar action area.

Do not remove the ability to:
- create a new policy
- import YAML
- navigate directly to an existing policy route

- [ ] **Step 5: Run verification**

Run:
```bash
cd /home/rsclison/autho/admin-ui
npm run build
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add admin-ui/src/pages/PoliciesPage.tsx
 git commit -m "refactor: remove redundant policy list column from policies page"
```

## Task 3: Final Integration Verification

**Files:**
- Modify: no additional files unless fixes are needed

- [ ] **Step 1: Manual route verification**

Check in browser:
- `/admin/ui/policies` still resolves cleanly
- expanding `Politiques` reveals nested resource classes
- clicking a resource class opens `/admin/ui/policies/:resourceClass`
- clicking governance from that policy still opens `/admin/ui/policies/:resourceClass/governance`
- nested active state remains correct on governance route

- [ ] **Step 2: Manual collapse behavior verification**

Check:
- chevron toggles the group open and closed
- collapse state is user-controlled and not forced shut by route changes

- [ ] **Step 3: Run final build verification**

Run:
```bash
cd /home/rsclison/autho/admin-ui
npm run build
```

Expected: PASS

- [ ] **Step 4: Note tooling limitations if still present**

If frontend UI tests remain blocked by missing `jsdom`, state that explicitly in the final summary instead of claiming automated sidebar coverage.

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/components/layout/Sidebar.tsx admin-ui/src/pages/PoliciesPage.tsx docs/superpowers/specs/2026-03-30-policies-sidebar-navigation-design.md docs/superpowers/plans/2026-03-30-policies-sidebar-navigation.md
 git commit -m "feat: move policy navigation into collapsible sidebar group"
```

## Self-Review

Spec coverage:
- collapsible manual group: Task 1
- nested navigation items and highlighting: Task 1 and Task 3
- removal of page-local policy list: Task 2
- preserved route compatibility with governance: Task 3

Placeholder scan:
- no TODO/TBD markers remain
- exact files and commands are provided
- fallback verification is explicit for incomplete frontend test infrastructure

Type consistency:
- policy item routes consistently use `/policies/:resourceClass`
- nested active highlighting is designed to include governance subroutes
- page-local policy navigation is removed rather than duplicated
