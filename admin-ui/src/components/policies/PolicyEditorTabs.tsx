import { useState, useMemo, useEffect } from 'react'
import Editor from '@monaco-editor/react'
import { LayoutGrid, Code2, ChevronDown, Filter } from 'lucide-react'
import { RuleList } from './RuleList'
import { usePolicy } from '@/api/policies'
import { getDarkMode } from '@/lib/auth'
import type { Rule } from '@/types/policy'

type Tab = 'visual' | 'json'

// ─── Helpers ──────────────────────────────────────────────────────────────────

function extractRules(data: unknown): Rule[] {
  if (!data) return []
  const d = data as Record<string, unknown>
  const global = d['global'] as Record<string, unknown> | undefined
  return ((global?.['rules'] ?? d['rules'] ?? []) as Rule[])
}

function serializeBack(data: unknown, rules: Rule[]): string {
  if (!data) return JSON.stringify({ rules }, null, 2)
  const d = data as Record<string, unknown>
  const hasGlobal = 'global' in d
  if (hasGlobal) {
    const global = (d['global'] as Record<string, unknown>) ?? {}
    return JSON.stringify({ ...d, global: { ...global, rules } }, null, 2)
  }
  return JSON.stringify({ ...d, rules }, null, 2)
}

// ─── Filtre opération ─────────────────────────────────────────────────────────

function OperationFilter({
  operations,
  selected,
  onChange,
}: {
  operations: string[]
  selected: string | null
  onChange: (op: string | null) => void
}) {
  const [open, setOpen] = useState(false)

  if (operations.length === 0) return null

  return (
    <div className="relative">
      <button
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded border border-input bg-background hover:bg-muted transition-colors"
      >
        <Filter size={11} />
        <span>{selected ?? 'Toutes opérations'}</span>
        <ChevronDown size={10} />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className="absolute top-full left-0 mt-1 w-44 bg-popover border border-border rounded-md shadow-lg z-20">
            <button
              onClick={() => { onChange(null); setOpen(false) }}
              className={`w-full text-left px-3 py-2 text-xs hover:bg-muted transition-colors ${!selected ? 'text-autho-dark font-semibold' : 'text-foreground'}`}
            >
              Toutes opérations
            </button>
            {operations.map((op) => (
              <button
                key={op}
                onClick={() => { onChange(op); setOpen(false) }}
                className={`w-full text-left px-3 py-2 text-xs font-mono hover:bg-muted transition-colors ${selected === op ? 'text-autho-dark font-semibold' : 'text-foreground'}`}
              >
                {op}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

// ─── Stats ────────────────────────────────────────────────────────────────────

function RuleStats({ rules }: { rules: Rule[] }) {
  const allows = rules.filter((r) => r.effect === 'allow').length
  const denies = rules.filter((r) => r.effect === 'deny').length

  return (
    <div className="flex items-center gap-3 text-xs text-muted-foreground">
      <span>{rules.length} règle{rules.length !== 1 ? 's' : ''}</span>
      {allows > 0 && (
        <span className="text-green-600 dark:text-green-400">{allows} allow</span>
      )}
      {denies > 0 && (
        <span className="text-red-600 dark:text-red-400">{denies} deny</span>
      )}
    </div>
  )
}

// ─── PolicyEditorTabs ─────────────────────────────────────────────────────────

interface Props {
  resourceClass: string
  jsonValue: string
  onJsonChange: (v: string) => void
}

export function PolicyEditorTabs({ resourceClass, jsonValue, onJsonChange }: Props) {
  const [tab, setTab] = useState<Tab>('visual')
  const [opFilter, setOpFilter] = useState<string | null>(null)
  const { data } = usePolicy(resourceClass)
  const theme = getDarkMode() ? 'vs-dark' : 'light'

  // Parse raw server data once to know shape for re-serialization
  const serverData = useMemo(() => data ?? null, [data])

  // Parse rules from the live JSON value (reflects edits from JSON tab)
  const rules: Rule[] = useMemo(() => {
    try {
      const parsed = JSON.parse(jsonValue)
      return extractRules(parsed)
    } catch {
      return extractRules(serverData)
    }
  }, [jsonValue, serverData])

  // Keep json in sync when server data first loads (before any edit)
  useEffect(() => {
    if (serverData && !jsonValue) {
      onJsonChange(JSON.stringify(serverData, null, 2))
    }
  }, [serverData]) // eslint-disable-line react-hooks/exhaustive-deps

  // Opérations distinctes pour le filtre
  const operations = useMemo(() => {
    const ops = [...new Set(rules.map((r) => r.operation).filter(Boolean) as string[])]
    return ops.sort()
  }, [rules])

  // ── Mutation helpers ────────────────────────────────────────────────────────

  function updateRules(next: Rule[]) {
    try {
      const parsed = JSON.parse(jsonValue)
      onJsonChange(serializeBack(parsed, next))
    } catch {
      onJsonChange(serializeBack(serverData, next))
    }
  }

  function handleEdit(index: number, updated: Rule) {
    const next = [...rules]
    next[index] = updated
    updateRules(next)
  }

  function handleDelete(index: number) {
    updateRules(rules.filter((_, i) => i !== index))
  }

  function handleAdd(rule: Rule) {
    updateRules([...rules, rule])
  }

  return (
    <div className="flex flex-col h-full">
      {/* Barre d'onglets */}
      <div className="flex items-center justify-between px-3 py-1.5 border-b border-border bg-muted/20">
        <div className="flex items-center gap-1">
          <button
            onClick={() => setTab('visual')}
            className={`flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded transition-colors ${
              tab === 'visual'
                ? 'bg-autho-dark text-white'
                : 'text-muted-foreground hover:bg-muted'
            }`}
          >
            <LayoutGrid size={12} /> Visuel
          </button>
          <button
            onClick={() => setTab('json')}
            className={`flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded transition-colors ${
              tab === 'json'
                ? 'bg-autho-dark text-white'
                : 'text-muted-foreground hover:bg-muted'
            }`}
          >
            <Code2 size={12} /> JSON
          </button>
        </div>

        {tab === 'visual' && (
          <div className="flex items-center gap-3">
            <RuleStats rules={rules} />
            <OperationFilter
              operations={operations}
              selected={opFilter}
              onChange={setOpFilter}
            />
          </div>
        )}
      </div>

      {/* Contenu */}
      <div className="flex-1 min-h-0 overflow-auto">
        {tab === 'visual' ? (
          <div className="p-4">
            <RuleList
              rules={rules}
              operation={opFilter}
              onEdit={handleEdit}
              onDelete={handleDelete}
              onAdd={handleAdd}
              onReorder={updateRules}
            />
          </div>
        ) : (
          <Editor
            height="100%"
            language="json"
            value={jsonValue}
            onChange={(v) => onJsonChange(v ?? '')}
            theme={theme}
            options={{
              minimap: { enabled: false },
              fontSize: 13,
              lineNumbers: 'on',
              wordWrap: 'on',
              formatOnPaste: true,
              formatOnType: true,
              scrollBeyondLastLine: false,
            }}
          />
        )}
      </div>
    </div>
  )
}
