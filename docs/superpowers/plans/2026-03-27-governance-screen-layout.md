# Governance Screen Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the cramped in-editor governance side panel with a dedicated governance screen for a selected policy resource class.

**Architecture:** Keep the existing governance data hooks and business actions, but move the layout into a dedicated page component routed from the policies area. Reuse the current editor page for rule editing, and pass resource class through the URL so navigation is stable and shareable.

**Tech Stack:** React 19, TypeScript, React Router, TanStack Query, existing admin UI component library, Vitest where feasible, Vite build verification.

---

## File Structure

- Modify: `admin-ui/src/App.tsx`
  Responsibility: ensure the router continues to mount the policies section cleanly.
- Modify: `admin-ui/src/pages/PoliciesPage.tsx`
  Responsibility: split editor and governance views by route, remove docked governance layout, preserve selected resource class navigation.
- Create: `admin-ui/src/pages/PolicyGovernancePage.tsx`
  Responsibility: dedicated governance screen container with header, layout regions, and route-driven loading.
- Modify: `admin-ui/src/components/policies/PolicyGovernancePanel.tsx`
  Responsibility: simplify into a page-friendly governance content component or reusable inner section without editor-coupled chrome.
- Test: `admin-ui/src/test/policyDocument.test.ts`
  Responsibility: keep the save normalization regression coverage already added.
- Test: `admin-ui/src/test/policyFormatLayout.test.ts` or equivalent pure helper test if UI runner remains blocked.
  Responsibility: cover route or layout helper logic if possible within current test tooling.

## Task 1: Add Dedicated Governance Route

**Files:**
- Modify: `admin-ui/src/pages/PoliciesPage.tsx`
- Create: `admin-ui/src/pages/PolicyGovernancePage.tsx`

- [ ] **Step 1: Write the failing test or route smoke scaffold**

```ts
// If routing tests are feasible:
it('navigates to the governance screen for a resource class', () => {
  // render policies routes
  // navigate to /policies/Facture/governance
  // expect governance heading and back action
})
```

If the current Vitest/jsdom setup still blocks component tests, record that limitation and proceed with build verification for this task.

- [ ] **Step 2: Run verification for the red state**

Run one of:
```bash
cd /home/rsclison/autho/admin-ui
npm exec vitest run --config vitest.node.config.ts src/test/<route-test>.ts
```
Or, if UI tests are still blocked by environment gaps:
```bash
cd /home/rsclison/autho/admin-ui
npm run build
```

Expected before implementation:
- test missing or route not found, or
- build still succeeds but no dedicated route exists yet

- [ ] **Step 3: Create the dedicated governance page**

```tsx
export default function PolicyGovernancePage() {
  const { resourceClass = '' } = useParams()
  const navigate = useNavigate()

  return (
    <div className="flex h-full flex-col">
      <header className="flex items-center justify-between border-b border-border px-4 py-3">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Gouvernance</p>
          <h1 className="text-lg font-semibold text-foreground">{resourceClass}</h1>
        </div>
        <button onClick={() => navigate(`/policies/${resourceClass}`)}>
          Retour a la policy
        </button>
      </header>
      <PolicyGovernancePanel resourceClass={resourceClass} onClose={() => navigate(`/policies/${resourceClass}`)} />
    </div>
  )
}
```

- [ ] **Step 4: Add nested policies routes**

Implement route handling inside `PoliciesPage` so these two URLs work:
```tsx
<Route path=":resourceClass" element={<PolicyEditorRoute />} />
<Route path=":resourceClass/governance" element={<PolicyGovernancePage />} />
```

Keep default selection behavior by redirecting the base policies route to the first available policy when possible, or rendering the existing empty state.

- [ ] **Step 5: Run verification**

Run:
```bash
cd /home/rsclison/autho/admin-ui
npm run build
```

Expected: build passes and the governance route compiles.

- [ ] **Step 6: Commit**

```bash
git add admin-ui/src/pages/PoliciesPage.tsx admin-ui/src/pages/PolicyGovernancePage.tsx
 git commit -m "feat: add dedicated governance page route"
```

## Task 2: Remove Docked Governance Layout From Policy Editor

**Files:**
- Modify: `admin-ui/src/pages/PoliciesPage.tsx`

- [ ] **Step 1: Write the failing test or route expectation**

```ts
it('opens governance in a dedicated page instead of inline panel', () => {
  // click Gouvernance from policy editor
  // expect URL to change to /policies/Facture/governance
  // expect inline editor area to no longer render the docked panel
})
```

- [ ] **Step 2: Run verification for the red state**

Run the feasible check:
```bash
cd /home/rsclison/autho/admin-ui
npm run build
```

Expected before implementation: button still toggles local panel state or route behavior missing.

- [ ] **Step 3: Replace local panel toggle with navigation**

```tsx
const navigate = useNavigate()
<button
  onClick={() => navigate(`/policies/${resourceClass}/governance`)}
  className="..."
>
  <ShieldAlert size={12} /> Gouvernance
</button>
```

Remove the local `showGovernance` state and the inline conditional rendering block for the side panel.

- [ ] **Step 4: Keep history and diff behavior unchanged**

Ensure only governance navigation changes. `Historique`, diff, save, delete, and editor tabs should continue to behave exactly as before.

- [ ] **Step 5: Run verification**

Run:
```bash
cd /home/rsclison/autho/admin-ui
npm run build
```

Expected: build passes with no references to removed `showGovernance` logic.

- [ ] **Step 6: Commit**

```bash
git add admin-ui/src/pages/PoliciesPage.tsx
 git commit -m "refactor: move governance access out of inline editor panel"
```

## Task 3: Rework Governance Content Layout For Page Use

**Files:**
- Modify: `admin-ui/src/components/policies/PolicyGovernancePanel.tsx`
- Modify: `admin-ui/src/types/policy.ts` if needed for page-only display consistency

- [ ] **Step 1: Write the failing test or layout assertion scaffold**

```ts
it('renders governance as a page-friendly three-region layout', () => {
  // expect setup area, summary/history area, and timeline area labels
})
```

If UI tests remain blocked, document that the fallback verification is build + manual browser validation.

- [ ] **Step 2: Run verification for the red state**

Run:
```bash
cd /home/rsclison/autho/admin-ui
npm run build
```

Expected before implementation: current component still uses the narrow split-panel structure sized for inline embedding.

- [ ] **Step 3: Replace the embedded split layout with page-friendly regions**

Target structure:
```tsx
<div className="grid h-full min-h-0 grid-cols-[22rem_minmax(24rem,1fr)_26rem] gap-4 p-4">
  <section>{/* setup */}</section>
  <section>{/* latest preview + history */}</section>
  <aside>{/* timeline */}</aside>
</div>
```

Key requirements:
- remove nested close/header chrome that only made sense inside the editor
- keep action buttons near the preview they affect
- enlarge the JSON request editor
- make timeline cards readable without stretching horizontally across the whole page

- [ ] **Step 4: Improve empty states and spacing**

Apply these UI rules:
```tsx
// empty state cards use dashed border + compact helper copy
// card headers use one title + one supporting line maximum
// controls align vertically with predictable spacing classes
```

Do not change API hooks or backend behavior in this task.

- [ ] **Step 5: Run verification**

Run:
```bash
cd /home/rsclison/autho/admin-ui
npm run build
```

Expected: build passes and page layout compiles with existing governance hooks.

- [ ] **Step 6: Commit**

```bash
git add admin-ui/src/components/policies/PolicyGovernancePanel.tsx admin-ui/src/types/policy.ts
 git commit -m "feat: redesign governance screen layout for dedicated page"
```

## Task 4: Final Integration Verification

**Files:**
- Modify: no additional files unless fixes are needed

- [ ] **Step 1: Verify key routes manually**

Check in browser:
- `/admin/ui/policies/<resourceClass>` opens the editor
- clicking `Gouvernance` opens `/admin/ui/policies/<resourceClass>/governance`
- `Retour a la policy` returns to the editor

- [ ] **Step 2: Verify governance actions still render**

Manual checklist:
- baseline and candidate selects visible
- request editor usable
- latest preview summary visible after generation
- history actions visible
- timeline cards visible without overlap

- [ ] **Step 3: Run final build verification**

Run:
```bash
cd /home/rsclison/autho/admin-ui
npm run build
```

Expected: PASS

- [ ] **Step 4: Note test/tooling limitations if still present**

If Vitest UI tests are still blocked by missing `jsdom`, record that explicitly in the final summary instead of claiming route/layout test coverage.

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/pages/PoliciesPage.tsx admin-ui/src/pages/PolicyGovernancePage.tsx admin-ui/src/components/policies/PolicyGovernancePanel.tsx docs/superpowers/specs/2026-03-27-governance-screen-design.md docs/superpowers/plans/2026-03-27-governance-screen-layout.md
 git commit -m "feat: move policy governance to a dedicated screen"
```

## Self-Review

Spec coverage:
- dedicated route and navigation: Task 1 and Task 2
- page structure and readability: Task 3
- back navigation and validation flow: Task 4
- reuse of existing hooks with no backend changes for layout only: Task 3 and Task 4

Placeholder scan:
- no TODO/TBD markers remain
- every task lists exact files and commands
- fallback verification is explicit where UI test infrastructure is incomplete

Type consistency:
- route path uses `:resourceClass` consistently
- governance page always receives `resourceClass` from route params
- policy editor route remains separate from governance route
