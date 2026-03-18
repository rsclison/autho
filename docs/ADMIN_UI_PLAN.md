# Plan d'implémentation — Interface d'administration production-ready

**Statut :** À démarrer
**Décision architecturale :** SPA intégrée dans le jar Autho (zéro infrastructure supplémentaire)
**Auteur du plan :** 2026-03-19

---

## Contexte

### Ce qui existe déjà

Le serveur Autho expose déjà tous les endpoints API nécessaires. L'UI actuelle
(`src/autho/api/admin_ui.clj`) est un prototype Hiccup côté serveur avec quatre pages
statiques non interactives. Elle sera remplacée intégralement.

**Backend prêt à l'emploi :**
- `GET /admin/audit/search?subjectId=&resourceClass=&decision=&from=&to=&page=&pageSize=`
- `GET /admin/audit/verify`
- `POST /admin/reinit`, `POST /admin/reload_rules`, `POST /admin/reload_persons`
- `GET /admin/listRDB`, `DELETE /admin/clearRDB/:class`
- `GET /policies`, `GET /policy/:rc`, `PUT /policy/:rc`, `DELETE /policy/:rc`
- `POST /v1/policies/import` (YAML)
- `GET /v1/policies/:rc/versions`, `GET /v1/policies/:rc/versions/:v`
- `GET /v1/policies/:rc/diff?from=&to=`, `POST /v1/policies/:rc/rollback/:v`
- `POST /isAuthorized`, `POST /explain`, `POST /v1/authz/simulate`
- `GET /v1/cache/stats`, `DELETE /v1/cache`, `DELETE /v1/cache/:type/:key`
- `GET /status`, `GET /health`, `GET /metrics`

**Auth existante :** `X-API-Key` header ou `Authorization: Token <jwt>`. L'UI stockera
le token dans `sessionStorage` (effacé à la fermeture de l'onglet, pas de localStorage
pour éviter les risques XSS persistants).

### Point d'intégration backend

Le fichier `src/autho/handler.clj` contient déjà `(route/resources "/")` (ligne 306)
qui sert les fichiers statiques depuis le classpath (`resources/public/`).

La SPA buildée sera placée dans `resources/public/admin/` par Vite.
Un seul ajout dans `handler.clj` est nécessaire pour la SPA :

```clojure
;; Dans public-routes, AVANT (route/resources "/")
(GET "/admin/ui" []          (serve-spa))
(GET "/admin/ui/*" []        (serve-spa))
```

Où `serve-spa` retourne `resources/public/admin/index.html` :

```clojure
(defn- serve-spa []
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (slurp (io/resource "public/admin/index.html"))})
```

**Important :** Les routes admin API (`/admin/audit`, `/admin/reinit`, etc.) restent
dans le contexte `/admin` protégé par `wrap-admin-auth`. La SPA appelle ces APIs
directement avec le token stocké côté client.

---

## Stack technologique — Décisions figées

| Outil | Version | Rôle |
|-------|---------|------|
| **Node.js** | 22.x (déjà installé) | Runtime build |
| **React** | 18.3 | Framework UI |
| **Vite** | 5.x | Build tool + dev server |
| **TypeScript** | 5.x | Typage |
| **Tailwind CSS** | 3.x | Styles utilitaires |
| **shadcn/ui** | latest | Composants accessibles (copiés, pas dépendance) |
| **Recharts** | 2.x | Graphiques (AreaChart, BarChart, PieChart) |
| **TanStack Query** | 5.x | Fetching + cache + polling automatique |
| **TanStack Table** | 8.x | Tables triables/filtrables/paginées |
| **React Router** | 6.x | Navigation SPA |
| **React Hook Form** | 7.x | Formulaires |
| **Zod** | 3.x | Validation des schémas |
| **Monaco Editor** | `@monaco-editor/react` | Éditeur JSON/YAML (moteur VS Code) |
| **date-fns** | 3.x | Manipulation de dates |
| **lucide-react** | latest | Icônes |
| **react-hot-toast** | 2.x | Notifications toast |
| **Vitest** | 1.x | Tests unitaires |

**Justification Monaco Editor :** L'édition des politiques JSON/YAML est le cœur
de l'outil. Monaco apporte coloration syntaxique, autocomplétion, validation inline
et diff view intégrée — irremplaçable pour une UX production-ready.

---

## Structure de fichiers complète

```
autho/
├── admin-ui/                          ← Projet Vite (racine du frontend)
│   ├── package.json
│   ├── tsconfig.json
│   ├── vite.config.ts                 ← proxy /api et /admin/* → :8080, build → resources/public/admin
│   ├── tailwind.config.ts
│   ├── postcss.config.js
│   ├── index.html
│   └── src/
│       ├── main.tsx                   ← Point d'entrée React
│       ├── App.tsx                    ← Router + QueryClientProvider + AuthGuard
│       │
│       ├── lib/
│       │   ├── auth.ts                ← getToken(), setToken(), clearToken(), isAuthenticated()
│       │   ├── api-client.ts          ← fetch wrapper avec auth header + gestion erreurs
│       │   └── utils.ts               ← cn() (classnames), formatDate(), formatNumber()
│       │
│       ├── api/                       ← Un fichier par domaine (hooks TanStack Query)
│       │   ├── auth.ts                ← useLogin()
│       │   ├── decisions.ts           ← useSimulate(), useExplain(), useBatchDecisions()
│       │   ├── policies.ts            ← usePolicies(), usePolicy(), useSubmitPolicy(),
│       │   │                             useDeletePolicy(), useImportYaml()
│       │   ├── versions.ts            ← useVersions(), useVersion(), useDiffVersions(),
│       │   │                             useRollback()
│       │   ├── audit.ts               ← useAudit(), useVerifyChain()
│       │   ├── cache.ts               ← useCacheStats(), useClearCache(), useInvalidateEntry()
│       │   └── status.ts             ← useHealth(), useStatus(), useMetrics()
│       │
│       ├── components/
│       │   ├── ui/                    ← Composants shadcn copiés (Button, Input, Card,
│       │   │                             Table, Badge, Dialog, Sheet, Tabs, Select,
│       │   │                             Tooltip, Progress, Skeleton, Alert, ScrollArea)
│       │   │
│       │   ├── layout/
│       │   │   ├── AppShell.tsx       ← Sidebar + header + contenu principal
│       │   │   ├── Sidebar.tsx        ← Navigation principale avec badges d'état
│       │   │   ├── Header.tsx         ← Breadcrumb + user info + dark mode
│       │   │   └── PageTitle.tsx      ← H1 + description + actions à droite
│       │   │
│       │   ├── auth/
│       │   │   ├── LoginPage.tsx      ← Formulaire (api-key OU jwt)
│       │   │   └── AuthGuard.tsx      ← Redirige vers /login si non authentifié
│       │   │
│       │   ├── dashboard/
│       │   │   ├── MetricCard.tsx     ← Carte stat avec icône + tendance
│       │   │   ├── DecisionsChart.tsx ← AreaChart allow/deny sur 24h (données /status)
│       │   │   ├── TopResourcesChart.tsx ← BarChart top resource classes
│       │   │   ├── RecentDecisions.tsx   ← Tableau 10 dernières entrées audit
│       │   │   └── CircuitBreakerList.tsx ← Liste statuts avec couleurs
│       │   │
│       │   ├── policies/
│       │   │   ├── PolicyList.tsx     ← Table filtrée avec recherche + bouton créer
│       │   │   ├── PolicyDetail.tsx   ← Onglets Règles / JSON / YAML / Versions / Diff
│       │   │   ├── RuleCard.tsx       ← Carte d'une règle avec conditions lisibles
│       │   │   ├── RuleForm.tsx       ← Formulaire guidé de création/édition de règle
│       │   │   ├── PolicyEditor.tsx   ← Monaco Editor JSON avec validation schema
│       │   │   ├── YamlEditor.tsx     ← Monaco Editor YAML
│       │   │   ├── YamlImport.tsx     ← Drag & drop + éditeur inline + bouton import
│       │   │   ├── VersionsList.tsx   ← Table des versions avec bouton rollback
│       │   │   └── DiffView.tsx       ← Monaco diff editor entre deux versions
│       │   │
│       │   ├── simulator/
│       │   │   ├── SimulatorForm.tsx  ← JSON editors sujet + ressource + opération
│       │   │   ├── PolicySourcePicker.tsx ← Courante / Version N / Inline
│       │   │   └── DecisionTrace.tsx  ← Résultat : badge + liste règles avec ✅/❌
│       │   │
│       │   ├── audit/
│       │   │   ├── AuditFilters.tsx   ← Filtres (sujet, classe, décision, plage date)
│       │   │   ├── AuditTable.tsx     ← TanStack Table virtualisée
│       │   │   ├── AuditEntryDrawer.tsx ← Sheet latéral avec détail complet
│       │   │   ├── ChainVerifier.tsx  ← Bouton + résultat vérification HMAC
│       │   │   └── ExportButton.tsx   ← Export CSV des résultats filtrés
│       │   │
│       │   └── infrastructure/
│       │       ├── CacheGauges.tsx    ← Progress bars hit/miss par niveau
│       │       ├── CacheActions.tsx   ← Boutons vider + invalider clé spécifique
│       │       ├── CircuitBreakerTable.tsx ← Table avec statut + couleur + reset
│       │       └── SystemActions.tsx  ← Reinit PDP, reload rules, reload persons
│       │
│       ├── pages/
│       │   ├── LoginPage.tsx
│       │   ├── DashboardPage.tsx
│       │   ├── PoliciesPage.tsx       ← Liste + detail en split view
│       │   ├── SimulatorPage.tsx
│       │   ├── AuditPage.tsx
│       │   ├── InfrastructurePage.tsx ← Cache + Circuit breakers + Actions système
│       │   └── SettingsPage.tsx       ← Variables env actives (lecture seule) + exports
│       │
│       └── types/
│           ├── policy.ts              ← Policy, Rule, Condition, Version, DiffResult
│           ├── audit.ts               ← AuditEntry, AuditSearchParams, ChainVerifyResult
│           ├── cache.ts               ← CacheStats
│           ├── decision.ts            ← AuthRequest, AuthResult, ExplainResult, SimulateResult
│           └── status.ts             ← ServerStatus, CircuitBreakerStatus
│
└── resources/
    └── public/
        └── admin/                     ← Généré par `npm run build` dans admin-ui/
            ├── index.html             ← SPA entry point (servi par Clojure)
            ├── assets/
            │   ├── index-[hash].js
            │   └── index-[hash].css
            └── favicon.ico
```

---

## Fichiers de configuration — Contenu exact

### `admin-ui/vite.config.ts`

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') }
  },
  // Dev : proxy toutes les requêtes API vers Autho
  server: {
    port: 3000,
    proxy: {
      '/isAuthorized':   'http://localhost:8080',
      '/whoAuthorized':  'http://localhost:8080',
      '/whatAuthorized': 'http://localhost:8080',
      '/explain':        'http://localhost:8080',
      '/policies':       'http://localhost:8080',
      '/policy':         'http://localhost:8080',
      '/v1':             'http://localhost:8080',
      '/admin':          'http://localhost:8080',
      '/status':         'http://localhost:8080',
      '/health':         'http://localhost:8080',
      '/metrics':        'http://localhost:8080',
    }
  },
  // Production : build vers resources/public/admin/
  build: {
    outDir: '../resources/public/admin',
    emptyOutDir: true,
    // Inline les petits assets (<4KB) pour réduire les requêtes
    assetsInlineLimit: 4096,
    rollupOptions: {
      output: {
        // Chunks séparés pour les libs lourdes (Monaco, Recharts)
        manualChunks: {
          'vendor-react':   ['react', 'react-dom', 'react-router-dom'],
          'vendor-query':   ['@tanstack/react-query'],
          'vendor-table':   ['@tanstack/react-table'],
          'vendor-monaco':  ['@monaco-editor/react', 'monaco-editor'],
          'vendor-charts':  ['recharts'],
          'vendor-forms':   ['react-hook-form', 'zod', '@hookform/resolvers'],
        }
      }
    }
  },
  // SPA : toutes les routes renvoient index.html (en dev)
  // En prod c'est géré par le handler Clojure
  appType: 'spa',
})
```

### `admin-ui/package.json`

```json
{
  "name": "autho-admin-ui",
  "version": "0.1.0",
  "private": true,
  "scripts": {
    "dev":     "vite",
    "build":   "tsc && vite build",
    "preview": "vite preview",
    "test":    "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "@hookform/resolvers":      "^3.3.4",
    "@monaco-editor/react":     "^4.6.0",
    "@tanstack/react-query":    "^5.28.0",
    "@tanstack/react-table":    "^8.15.0",
    "class-variance-authority": "^0.7.0",
    "clsx":                     "^2.1.0",
    "cmdk":                     "^1.0.0",
    "date-fns":                 "^3.6.0",
    "lucide-react":             "^0.363.0",
    "react":                    "^18.3.0",
    "react-dom":                "^18.3.0",
    "react-hook-form":          "^7.51.0",
    "react-hot-toast":          "^2.4.1",
    "react-router-dom":         "^6.22.3",
    "recharts":                 "^2.12.2",
    "tailwind-merge":           "^2.2.2",
    "tailwindcss-animate":      "^1.0.7",
    "zod":                      "^3.22.4"
  },
  "devDependencies": {
    "@types/react":             "^18.3.0",
    "@types/react-dom":         "^18.3.0",
    "@vitejs/plugin-react":     "^4.2.1",
    "autoprefixer":             "^10.4.19",
    "postcss":                  "^8.4.38",
    "tailwindcss":              "^3.4.3",
    "typescript":               "^5.4.3",
    "vite":                     "^5.2.6",
    "vitest":                   "^1.4.0",
    "@testing-library/react":   "^15.0.0",
    "@testing-library/user-event": "^14.5.2"
  }
}
```

### `admin-ui/tsconfig.json`

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "baseUrl": ".",
    "paths": { "@/*": ["./src/*"] }
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

### `admin-ui/tailwind.config.ts`

```typescript
import type { Config } from 'tailwindcss'
import animate from 'tailwindcss-animate'

export default {
  darkMode: ['class'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Couleurs de la marque Autho (reprises de l'UI Hiccup actuelle)
        autho: {
          dark:  '#1a1a2e',
          blue:  '#8be9fd',
          light: '#f5f5f5',
        }
      }
    }
  },
  plugins: [animate],
} satisfies Config
```

---

## Intégration backend — Modifications Clojure

### Modification de `src/autho/handler.clj`

**Retirer** le require `autho.api.admin-ui` (ligne 15) et les 4 routes UI Hiccup (lignes 624-632).

**Ajouter** avant `(route/resources "/")` :

```clojure
;; SPA Admin UI — toutes les routes /admin/ui/* renvoient index.html
;; Les routes /admin/* (API) restent dans le contexte wrap-admin-auth ci-dessous
(GET "/admin/ui" []     (serve-spa-page))
(GET "/admin/ui/*" []   (serve-spa-page))
```

**Ajouter** la fonction helper (dans le ns, avant les routes) :

```clojure
(defn- serve-spa-page []
  (if-let [res (io/resource "public/admin/index.html")]
    {:status  200
     :headers {"Content-Type"  "text/html; charset=utf-8"
               "Cache-Control" "no-cache, no-store, must-revalidate"}
     :body    (slurp res)}
    {:status 404 :body "Admin UI not built. Run: cd admin-ui && npm run build"}))
```

**Ajouter** dans `project.clj` sous `:ring` :

```clojure
:ring {:handler autho.handler/app
       :init    autho.handler/init
       :destroy autho.handler/destroy
       :resource-paths ["resources"]}  ; déjà présent normalement
```

**Note importante :** `(route/resources "/")` avec le ring-defaults va servir
`resources/public/admin/index.html` via `GET /admin/ui/index.html` mais PAS via
`GET /admin/ui` (sans fichier). C'est pourquoi la route Clojure explicite est
nécessaire. Elle a la priorité si elle est déclarée avant `route/resources`.

### Supprimer `src/autho/api/admin_ui.clj`

Ce fichier devient obsolète une fois la SPA déployée. Le supprimer lors du commit final.

---

## Intégration dans le processus de build (lein)

Ajouter dans `project.clj` un hook `lein-shell` pour builder le frontend avant le jar :

```clojure
:plugins [[lein-ring "0.12.6"]
          [lein-shell "0.5.0"]]       ; à ajouter

:aliases {"build-ui" ["shell" "npm" "run" "build" "--prefix" "admin-ui"]
          "uberjar"  ["do" "build-ui," "uberjar"]}
```

Ainsi `lein uberjar` builde automatiquement le frontend, puis package le tout.

---

## Implémentation phase par phase

---

### Phase 1 — Squelette, layout, authentification, dashboard (3-4 jours)

**Objectif :** Un shell fonctionnel avec login, navigation, dashboard live et page infrastructure.

#### Étape 1.1 — Initialisation du projet

```bash
cd /home/rsclison/autho
npm create vite@latest admin-ui -- --template react-ts
cd admin-ui
npm install
# Tailwind
npm install -D tailwindcss postcss autoprefixer tailwindcss-animate
npx tailwindcss init -p --ts
# shadcn init
npx shadcn-ui@latest init
# Dépendances métier
npm install @tanstack/react-query @tanstack/react-table react-router-dom
npm install recharts @monaco-editor/react react-hook-form zod @hookform/resolvers
npm install date-fns lucide-react react-hot-toast
npm install class-variance-authority clsx tailwind-merge
```

Copier les composants shadcn nécessaires :
```bash
npx shadcn-ui@latest add button input card table badge dialog sheet tabs \
  select tooltip progress skeleton alert scroll-area separator dropdown-menu \
  avatar popover command
```

Configurer `vite.config.ts`, `tsconfig.json`, `tailwind.config.ts` (contenu exact ci-dessus).

#### Étape 1.2 — Types TypeScript

Créer `src/types/policy.ts` :
```typescript
export interface Condition extends Array<string> {}

export interface Rule {
  name: string
  resourceClass: string
  operation: string
  priority: number
  effect: 'allow' | 'deny'
  conditions: Condition[]
  domain?: string
  startDate?: string
  endDate?: string
}

export interface Policy {
  resourceClass: string
  strategy: string
  rules: Rule[]
}

export interface PolicyVersion {
  version: number
  author: string | null
  comment: string | null
  createdAt: string
}

export interface DiffResult {
  added: string[]
  removed: string[]
  changed: string[]
}
```

Créer `src/types/audit.ts` :
```typescript
export interface AuditEntry {
  id: number
  ts: string
  request_id: string
  subject_id: string
  resource_class: string
  resource_id: string
  operation: string
  decision: 'allow' | 'deny'
  matched_rules: string[]
  payload_hash: string
  hmac: string
}

export interface AuditSearchParams {
  subjectId?: string
  resourceClass?: string
  decision?: 'allow' | 'deny' | ''
  from?: string
  to?: string
  page?: number
  pageSize?: number
}

export interface AuditChainResult {
  valid: boolean
  'broken-at'?: number
  reason?: string
}
```

Créer `src/types/status.ts` :
```typescript
export type CircuitBreakerState = 'open' | 'closed' | 'half-open'

export interface ServerStatus {
  status: string
  version: string
  uptime_seconds: number
  rules_status: string
  cache_stats: CacheStats
  circuit_breakers: Record<string, CircuitBreakerState>
}

export interface CacheStats {
  'decision-hits': number
  'decision-misses': number
  'decision-ratio': number
  'decision-size': number
  'subject-hits': number
  'subject-misses': number
  'resource-hits': number
  'resource-misses': number
  'policy-hits': number
  'policy-misses': number
}
```

#### Étape 1.3 — Client API et authentification

Créer `src/lib/auth.ts` :
```typescript
const TOKEN_KEY = 'autho-admin-token'
const TOKEN_TYPE_KEY = 'autho-admin-token-type'

export type TokenType = 'api-key' | 'jwt'

export function setToken(token: string, type: TokenType): void {
  sessionStorage.setItem(TOKEN_KEY, token)
  sessionStorage.setItem(TOKEN_TYPE_KEY, type)
}

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function getTokenType(): TokenType | null {
  return sessionStorage.getItem(TOKEN_TYPE_KEY) as TokenType | null
}

export function clearToken(): void {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(TOKEN_TYPE_KEY)
}

export function isAuthenticated(): boolean {
  return !!getToken()
}

export function getAuthHeader(): Record<string, string> {
  const token = getToken()
  const type = getTokenType()
  if (!token) return {}
  if (type === 'api-key') return { 'X-API-Key': token }
  return { 'Authorization': `Token ${token}` }
}
```

Créer `src/lib/api-client.ts` :
```typescript
import { getAuthHeader, clearToken } from './auth'
import toast from 'react-hot-toast'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message)
  }
}

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  contentType = 'application/json'
): Promise<T> {
  const headers: Record<string, string> = {
    'Accept': 'application/json',
    ...getAuthHeader(),
  }
  if (body !== undefined) {
    headers['Content-Type'] = contentType
  }

  const response = await fetch(path, {
    method,
    headers,
    body: body !== undefined
      ? (typeof body === 'string' ? body : JSON.stringify(body))
      : undefined,
  })

  if (response.status === 401 || response.status === 403) {
    clearToken()
    window.location.href = '/admin/ui/login'
    throw new ApiError(response.status, 'UNAUTHORIZED', 'Session expirée')
  }

  if (response.status === 429) {
    throw new ApiError(429, 'RATE_LIMIT', 'Trop de requêtes — réessayez dans quelques secondes')
  }

  if (!response.ok) {
    let code = 'UNKNOWN_ERROR'
    let message = `Erreur HTTP ${response.status}`
    try {
      const err = await response.json()
      code    = err?.error?.code ?? code
      message = err?.error?.message ?? message
    } catch (_) { /* ignore */ }
    throw new ApiError(response.status, code, message)
  }

  const text = await response.text()
  return text ? JSON.parse(text) : undefined as T
}

export const api = {
  get:    <T>(path: string) => request<T>('GET', path),
  post:   <T>(path: string, body: unknown) => request<T>('POST', path, body),
  put:    <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  delete: <T>(path: string) => request<T>('DELETE', path),
  postYaml: <T>(path: string, yaml: string) =>
    request<T>('POST', path, yaml, 'text/yaml'),
}
```

#### Étape 1.4 — App.tsx et Router

```typescript
// src/App.tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { Toaster } from 'react-hot-toast'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { AppShell } from '@/components/layout/AppShell'
import LoginPage from '@/pages/LoginPage'
import DashboardPage from '@/pages/DashboardPage'
import PoliciesPage from '@/pages/PoliciesPage'
import SimulatorPage from '@/pages/SimulatorPage'
import AuditPage from '@/pages/AuditPage'
import InfrastructurePage from '@/pages/InfrastructurePage'
import SettingsPage from '@/pages/SettingsPage'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
    }
  }
})

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter basename="/admin/ui">
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={
            <AuthGuard>
              <AppShell />
            </AuthGuard>
          }>
            <Route index element={<DashboardPage />} />
            <Route path="policies/*" element={<PoliciesPage />} />
            <Route path="simulator" element={<SimulatorPage />} />
            <Route path="audit" element={<AuditPage />} />
            <Route path="infrastructure" element={<InfrastructurePage />} />
            <Route path="settings" element={<SettingsPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
      <Toaster position="bottom-right" />
    </QueryClientProvider>
  )
}
```

#### Étape 1.5 — Hooks API (status.ts, cache.ts)

```typescript
// src/api/status.ts
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { ServerStatus } from '@/types/status'

export function useStatus() {
  return useQuery({
    queryKey: ['status'],
    queryFn: () => api.get<ServerStatus>('/status'),
    refetchInterval: 10_000,  // Polling toutes les 10s
  })
}

export function useHealth() {
  return useQuery({
    queryKey: ['health'],
    queryFn: () => api.get<{ status: string }>('/health'),
    refetchInterval: 30_000,
  })
}
```

```typescript
// src/api/cache.ts
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import toast from 'react-hot-toast'
import type { CacheStats } from '@/types/status'

export function useCacheStats() {
  return useQuery({
    queryKey: ['cache', 'stats'],
    queryFn: () => api.get<CacheStats>('/v1/cache/stats'),
    refetchInterval: 15_000,
  })
}

export function useClearCache() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.delete('/v1/cache'),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['cache'] })
      toast.success('Cache vidé avec succès')
    },
    onError: (e: Error) => toast.error(e.message),
  })
}

export function useInvalidateEntry() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ type, key }: { type: string; key: string }) =>
      api.delete(`/v1/cache/${type}/${key}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['cache'] })
      toast.success('Entrée invalidée')
    },
    onError: (e: Error) => toast.error(e.message),
  })
}
```

#### Étape 1.6 — Dashboard et Infrastructure pages

Le Dashboard affiche :
- 4 `MetricCard` (décisions/min, hit rate cache, latence p99 simulée, nb politiques)
- `DecisionsChart` : AreaChart Recharts basé sur les données cumulées de `/status`
  *(Remarque : pour les graphiques temporels réels il faudrait un endpoint
  `/admin/metrics/timeseries` — en attendant, simuler avec les compteurs Prometheus
  parsés depuis `/metrics`)*
- `CircuitBreakerList` : liste avec badges colorés
- Tableau des 10 dernières entrées audit (via `useAudit({ pageSize: 10 })`)

---

### Phase 2 — Éditeur de politiques (4-5 jours)

**Objectif :** CRUD complet, Monaco Editor, versionnage avec diff visuel.

#### Étape 2.1 — Hooks politiques

```typescript
// src/api/policies.ts
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import toast from 'react-hot-toast'
import type { Policy } from '@/types/policy'

export function usePolicies() {
  return useQuery({
    queryKey: ['policies'],
    queryFn: () => api.get<Record<string, { global: Policy }>>('/policies'),
  })
}

export function usePolicy(resourceClass: string | null) {
  return useQuery({
    queryKey: ['policy', resourceClass],
    queryFn: () => api.get<{ global: Policy }>(`/policy/${resourceClass}`),
    enabled: !!resourceClass,
  })
}

export function useSubmitPolicy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ resourceClass, policy }: { resourceClass: string; policy: Policy }) =>
      api.put(`/policy/${resourceClass}`, policy),
    onSuccess: (_, { resourceClass }) => {
      qc.invalidateQueries({ queryKey: ['policies'] })
      qc.invalidateQueries({ queryKey: ['policy', resourceClass] })
      qc.invalidateQueries({ queryKey: ['versions', resourceClass] })
      toast.success('Politique sauvegardée')
    },
    onError: (e: Error) => toast.error(e.message),
  })
}

export function useDeletePolicy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (resourceClass: string) =>
      api.delete(`/policy/${resourceClass}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['policies'] })
      toast.success('Politique supprimée')
    },
    onError: (e: Error) => toast.error(e.message),
  })
}

export function useImportYaml() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (yaml: string) =>
      api.postYaml<{ ok: boolean; resourceClass: string; rulesLoaded: number }>(
        '/v1/policies/import', yaml
      ),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ['policies'] })
      toast.success(`${data.rulesLoaded} règles importées pour ${data.resourceClass}`)
    },
    onError: (e: Error) => toast.error(e.message),
  })
}
```

#### Étape 2.2 — PolicyList

Table avec TanStack Table :
- Colonnes : resourceClass | strategy | nb règles | actions
- Filtre texte en temps réel
- Clic sur une ligne → navigation vers `/policies/:rc`
- Bouton "+ Nouvelle politique" → ouvre `PolicyEditor` avec template vide
- Bouton "Import YAML" → ouvre `YamlImport`

#### Étape 2.3 — PolicyDetail (layout split)

Route `/policies/:resourceClass` — layout deux colonnes :
- Colonne gauche (1/3) : liste des règles avec `RuleCard`
- Colonne droite (2/3) : onglets

**Onglet "Règles" :**
- Liste de `RuleCard` triée par priorité
- Chaque carte : nom, effet (badge vert/rouge), opération, conditions en chips lisibles
- Boutons : Éditer → `RuleForm` dans un Sheet | Dupliquer | Supprimer
- Bouton "+ Ajouter une règle"

**Onglet "JSON" :**
- `Monaco Editor` en mode JSON
- Validation du schéma Autho inline (erreurs soulignées)
- Bouton "Sauvegarder" → `useSubmitPolicy`
- Bouton "Formater" → `editor.getAction('editor.action.formatDocument').run()`

**Onglet "YAML" :**
- Monaco Editor en mode YAML (lecture seule au départ, éditable avec bouton)
- Conversion JSON→YAML côté client avec `js-yaml`

**Onglet "Versions" :**
- `VersionsList` : table des versions avec auteur, date, commentaire
- Bouton "Rollback" avec Dialog de confirmation (affiche résumé des changements)
- Bouton "Voir" → charge la version dans un Panel readonly

**Onglet "Diff" :**
- Deux Select (version A, version B)
- Monaco DiffEditor côté-à-côté entre les deux JSONs
- Résumé textuel : N règles ajoutées, N supprimées, N modifiées

#### Étape 2.4 — RuleForm

Formulaire guidé (React Hook Form + Zod) dans un Sheet latéral :

```typescript
const ruleSchema = z.object({
  name:       z.string().min(1).max(100),
  operation:  z.string().min(1).max(100),
  priority:   z.number().int().min(0).max(999),
  effect:     z.enum(['allow', 'deny']),
  conditions: z.array(z.tuple([z.string(), z.string(), z.string()])),
  domain:     z.string().optional(),
})
```

Interface de saisie des conditions :
- Liste de conditions (triplets [opérateur, opérande1, opérande2])
- Sélecteur d'opérateur (dropdown : =, diff, <, >, <=, >=, in, notin)
- Inputs pour opérande1 et opérande2 avec placeholder d'exemple
- Bouton "+ Ajouter une condition"
- Bouton "×" pour supprimer

#### Étape 2.5 — YamlImport

- Zone drag & drop (accepte `.yaml`, `.yml`)
- Monaco Editor YAML en preview éditable
- Bouton "Importer" → `useImportYaml`
- Résultat : badge succès avec nb règles, ou message d'erreur inline

---

### Phase 3 — Simulateur et audit (3-4 jours)

#### Étape 3.1 — Simulateur

```typescript
// src/api/decisions.ts
export function useSimulate() {
  return useMutation({
    mutationFn: (payload: SimulateRequest) =>
      api.post<SimulateResult>('/v1/authz/simulate', payload),
  })
}
```

**Layout :**
- Deux colonnes : Sujet (Monaco JSON) + Ressource (Monaco JSON)
- Champ Opération (input)
- `PolicySourcePicker` :
  - Radio : "Politique courante" | "Version archivée" | "Politique inline"
  - Si "Version archivée" → Select avec les versions
  - Si "Politique inline" → Monaco JSON en dessous
- Bouton "▶ Simuler"

**DecisionTrace :**
```
┌─────────────────────────────────────────────────────┐
│  ✅ AUTORISÉ    policySource: current               │
│  Règles vérifiées : 3   Règles correspondantes : 1  │
│                                                     │
│  R1  allow  lire  ✅ Correspondance               │
│       Conditions : 3/3 vérifiées                   │
│                                                     │
│  R2  deny   lire  ❌ Non correspondante            │
│       $r.confidentiel ≠ "true"                     │
└─────────────────────────────────────────────────────┘
```

#### Étape 3.2 — Audit

```typescript
// src/api/audit.ts
export function useAudit(params: AuditSearchParams) {
  return useQuery({
    queryKey: ['audit', params],
    queryFn: () => {
      const qs = new URLSearchParams()
      if (params.subjectId)     qs.set('subjectId', params.subjectId)
      if (params.resourceClass) qs.set('resourceClass', params.resourceClass)
      if (params.decision)      qs.set('decision', params.decision)
      if (params.from)          qs.set('from', params.from)
      if (params.to)            qs.set('to', params.to)
      qs.set('page', String(params.page ?? 1))
      qs.set('pageSize', String(params.pageSize ?? 20))
      return api.get<{ items: AuditEntry[]; total: number }>(`/admin/audit/search?${qs}`)
    },
    placeholderData: (prev) => prev,  // Garde les données pendant le rechargement
  })
}

export function useVerifyChain() {
  return useQuery({
    queryKey: ['audit', 'verify'],
    queryFn: () => api.get<AuditChainResult>('/admin/audit/verify'),
    enabled: false,  // Manuel uniquement
  })
}
```

**AuditTable (TanStack Table) :**
- Colonnes : timestamp | request-id (tronqué, tooltip complet) | sujet | classe | opération | décision (badge)
- Tri côté client sur toutes les colonnes
- Clic sur ligne → `AuditEntryDrawer` (Sheet latéral)
- Bouton "Export CSV" : génère et télécharge un fichier CSV des résultats filtrés

**AuditEntryDrawer :**
- Tous les champs de l'entrée avec labels lisibles
- Section "Intégrité" : payload_hash et HMAC formatés en monospace
- Section "Règles correspondantes" : liste de badges

**ChainVerifier :**
- Bouton "Vérifier l'intégrité de la chaîne HMAC"
- État loading avec spinner
- Résultat :
  - ✅ badge vert "Chaîne valide — N entrées vérifiées"
  - ❌ badge rouge "Chaîne compromise à l'entrée #N : message d'erreur"

**ExportCSV :**
```typescript
function exportCsv(entries: AuditEntry[]) {
  const headers = ['id','timestamp','request_id','subject_id',
                   'resource_class','resource_id','operation','decision']
  const rows = entries.map(e => headers.map(h => e[h as keyof AuditEntry] ?? ''))
  const csv = [headers, ...rows].map(r => r.join(',')).join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `audit-${new Date().toISOString().slice(0,10)}.csv`
  a.click()
}
```

---

### Phase 4 — Polish production-ready (2-3 jours)

#### Étape 4.1 — LoginPage

Deux modes :
- **API Key** : champ texte password + bouton "Se connecter"
- **JWT** : champ texte multiline (coller le token) + bouton

Validation : test de l'API Key/JWT en appelant `/status` → si 200, stocker + rediriger.
Si 401/403, afficher erreur inline.

```typescript
async function handleLogin(token: string, type: TokenType) {
  setToken(token, type)  // Stocker d'abord
  try {
    await api.get('/status')  // Tester
    navigate('/')
  } catch (e) {
    clearToken()  // Annuler si échec
    setError('Token invalide ou accès refusé')
  }
}
```

#### Étape 4.2 — Dark mode

Bouton dans le Header : toggle `dark` class sur `<html>`.
Persister dans `localStorage` (juste la préférence UI, pas le token).
Tailwind `darkMode: ['class']` est déjà configuré.

#### Étape 4.3 — Skeleton screens

Chaque page affiche des `<Skeleton>` shadcn pendant le chargement initial.
Évite le "flash" de contenu vide.

```typescript
// Exemple dans PolicyList
if (isLoading) return (
  <div className="space-y-2">
    {Array.from({length: 5}).map((_, i) => (
      <Skeleton key={i} className="h-12 w-full rounded" />
    ))}
  </div>
)
```

#### Étape 4.4 — Gestion d'erreurs globale

`ApiError` est catchée dans chaque hook. Les erreurs réseau (fetch failed) sont
catchées dans le QueryClient :

```typescript
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      onError: (error: Error) => toast.error(error.message),
    },
    mutations: {
      onError: (error: Error) => toast.error(error.message),
    }
  }
})
```

#### Étape 4.5 — Tests

Tests unitaires avec Vitest + Testing Library pour les composants critiques :
- `RuleCard.test.tsx` — rendu correct selon l'effet
- `DecisionTrace.test.tsx` — affichage allow/deny
- `ChainVerifier.test.tsx` — états loading/valid/invalid
- `api-client.test.ts` — gestion 401, 429, 500

```bash
cd admin-ui && npm test
```

#### Étape 4.6 — Build final et commit

```bash
cd admin-ui && npm run build
# Vérifie que resources/public/admin/index.html existe
ls ../resources/public/admin/

# Test intégration
cd .. && lein ring server-headless &
curl -H "X-API-Key: test" http://localhost:8080/admin/ui
# Doit retourner le HTML de la SPA
```

---

## Flux de développement recommandé

```bash
# Terminal 1 — Autho backend
cd /home/rsclison/autho
JWT_SECRET=test-secret API_KEY=test-key lein ring server-headless

# Terminal 2 — Frontend avec HMR
cd /home/rsclison/autho/admin-ui
npm run dev
# UI disponible sur http://localhost:3000/admin/ui
# Proxy vers Autho :8080 automatiquement
```

En dev, utiliser l'API Key `test-key` dans le formulaire de login.

---

## Checklist de complétion par phase

### Phase 1
- [ ] `npm create vite` + installation dépendances
- [ ] Fichiers de config (vite, ts, tailwind, postcss)
- [ ] `src/lib/auth.ts` et `src/lib/api-client.ts`
- [ ] Types TypeScript complets
- [ ] `App.tsx` avec router + QueryClientProvider
- [ ] `AppShell` + `Sidebar` + `Header`
- [ ] `AuthGuard` + `LoginPage`
- [ ] Hooks `useStatus`, `useHealth`, `useCacheStats`
- [ ] `DashboardPage` avec métriques live
- [ ] `InfrastructurePage` avec cache + circuit breakers
- [ ] Build Vite → `resources/public/admin/`
- [ ] Modification `handler.clj` (serve-spa-page)
- [ ] Test intégration jar

### Phase 2
- [ ] Hooks `usePolicies`, `usePolicy`, `useSubmitPolicy`, `useDeletePolicy`, `useImportYaml`
- [ ] Hooks versions : `useVersions`, `useVersion`, `useDiffVersions`, `useRollback`
- [ ] `PolicyList` avec TanStack Table
- [ ] `PolicyDetail` avec onglets
- [ ] `RuleCard` + `RuleForm` (Sheet)
- [ ] `PolicyEditor` (Monaco JSON)
- [ ] `YamlEditor` + `YamlImport` (drag & drop)
- [ ] `VersionsList` + `DiffView` (Monaco diff)
- [ ] Confirmations (Dialog) pour suppression et rollback

### Phase 3
- [ ] Hooks `useSimulate`, `useExplain`
- [ ] `SimulatorForm` avec JSON editors
- [ ] `PolicySourcePicker`
- [ ] `DecisionTrace`
- [ ] Hooks `useAudit`, `useVerifyChain`
- [ ] `AuditFilters`
- [ ] `AuditTable` (TanStack Table)
- [ ] `AuditEntryDrawer`
- [ ] `ChainVerifier`
- [ ] `ExportButton` (CSV)

### Phase 4
- [ ] Dark mode toggle persisté
- [ ] Skeleton screens sur toutes les pages
- [ ] Gestion erreurs globale (toast)
- [ ] `LoginPage` avec validation live
- [ ] Tests Vitest (4 fichiers minimum)
- [ ] `lein-shell` hook dans `project.clj`
- [ ] Suppression de `src/autho/api/admin_ui.clj`
- [ ] Commit et push

---

## Points d'attention critiques

1. **Chunk Monaco** : Monaco Editor pèse ~4 MB minifié. Le `manualChunks` dans
   `vite.config.ts` est obligatoire pour éviter un bundle initial trop lourd.
   Utiliser `@monaco-editor/react` avec `loading={<Skeleton />}` pour le lazy loading.

2. **basename React Router** : `basename="/admin/ui"` dans `BrowserRouter` est
   indispensable car la SPA est montée sous un sous-chemin.

3. **Route `/admin/ui/*` dans Clojure** : Compojure interprète `/*` différemment
   selon la position. Tester avec `/admin/ui/policies/Facture` (URL avec sous-chemin)
   pour s'assurer que Clojure renvoie bien `index.html` et non un 404.

4. **CORS en dev** : Le proxy Vite évite tous les problèmes CORS. En production
   (SPA dans le jar), pas de CORS car origine identique.

5. **Cache TanStack Query** : `staleTime: 30_000` par défaut. Les mutations
   invalident leurs queries associées via `queryClient.invalidateQueries`.
   Le polling (`refetchInterval`) est uniquement sur Dashboard et Infrastructure.

6. **Sécurité token** : `sessionStorage` est effacé à la fermeture de l'onglet.
   Ne jamais stocker le token dans `localStorage` (persistant entre sessions,
   risque XSS). Le header `Cache-Control: no-cache` sur `index.html` évite
   la mise en cache du shell HTML par le navigateur.

---

## Résumé pour reprendre ce travail

Pour démarrer immédiatement la Phase 1 dans une nouvelle session :

1. Lire ce fichier (`docs/ADMIN_UI_PLAN.md`) en intégralité
2. Lire `src/autho/handler.clj` pour comprendre les routes existantes
3. Lire `src/autho/api/admin_ui.clj` pour voir l'UI actuelle à remplacer
4. Exécuter la Phase 1 dans l'ordre des étapes 1.1 → 1.6
5. Tester avec `npm run dev` (port 3000) + `lein ring server-headless` (port 8080)
6. Committer à la fin de chaque phase
