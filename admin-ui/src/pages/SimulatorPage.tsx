import { useState, useMemo } from 'react'
import { Play, Zap, HelpCircle, CheckCircle2, XCircle, ChevronDown, ChevronRight } from 'lucide-react'
import { useIsAuthorized, useExplain } from '@/api/decisions'
import { usePolicies } from '@/api/policies'
import type { AuthRequest, ExplainResult } from '@/types/decision'
import type { Rule } from '@/types/policy'

// ─── Request form ─────────────────────────────────────────────────────────────

const DEFAULT_REQUEST: AuthRequest = {
  subject: { id: '', role: '' },
  resource: { class: '', id: '' },
  operation: '',
  context: {},
}

function RequestForm({
  value,
  onChange,
}: {
  value: AuthRequest
  onChange: (v: AuthRequest) => void
}) {
  const { data: policies } = usePolicies()

  const resourceClasses = useMemo(
    () => (policies ? Object.keys(policies).sort() : []),
    [policies],
  )

  // Extract known operations for the currently selected resource class
  const operations = useMemo(() => {
    const rc = (value.resource.class as string) ?? ''
    if (!policies || !rc || !policies[rc]) return []
    const policy = policies[rc] as Record<string, unknown>
    const global = policy['global'] as Record<string, unknown> | undefined
    const rules = (global?.['rules'] ?? []) as Rule[]
    const ops = [...new Set(rules.map((r) => r.operation).filter(Boolean) as string[])]
    return ops.sort()
  }, [policies, value.resource.class])

  const inputCls = 'w-full px-3 py-1.5 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-1 focus:ring-ring'

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
      <div className="space-y-2">
        <label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">Sujet</label>
        <input value={(value.subject.id as string) ?? ''} onChange={(e) => onChange({ ...value, subject: { ...value.subject, id: e.target.value } })}
          placeholder="Identifiant (ex: alice)"
          className={inputCls} />
        <input value={(value.subject.role as string) ?? ''} onChange={(e) => onChange({ ...value, subject: { ...value.subject, role: e.target.value || undefined } })}
          placeholder="Rôle (optionnel)"
          className={inputCls} />
      </div>
      <div className="space-y-2">
        <label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">Ressource</label>
        <input
          list="sim-resource-classes"
          value={(value.resource.class as string) ?? ''}
          onChange={(e) => onChange({ ...value, resource: { ...value.resource, class: e.target.value }, operation: '' })}
          placeholder="Classe (ex: Facture)"
          className={inputCls}
        />
        <datalist id="sim-resource-classes">
          {resourceClasses.map((rc) => <option key={rc} value={rc} />)}
        </datalist>
        <input value={(value.resource.id as string) ?? ''} onChange={(e) => onChange({ ...value, resource: { ...value.resource, id: e.target.value || undefined } })}
          placeholder="ID ressource (optionnel)"
          className={inputCls} />
      </div>
      <div className="sm:col-span-2">
        <label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">Opération</label>
        <input
          list="sim-operations"
          value={value.operation}
          onChange={(e) => onChange({ ...value, operation: e.target.value })}
          placeholder="ex: lire, executer"
          className={`mt-1 ${inputCls}`}
        />
        <datalist id="sim-operations">
          {operations.map((op) => <option key={op} value={op} />)}
        </datalist>
      </div>
    </div>
  )
}

// ─── Decision badge ───────────────────────────────────────────────────────────

function DecisionBadge({ decision }: { decision: 'allow' | 'deny' | string }) {
  const allowed = decision === 'allow' || decision === 'Permit'
  return (
    <div className={`flex items-center gap-2 px-4 py-3 rounded-lg ${allowed ? 'bg-green-50 border border-green-200 dark:bg-green-900/20 dark:border-green-800' : 'bg-red-50 border border-red-200 dark:bg-red-900/20 dark:border-red-800'}`}>
      {allowed
        ? <CheckCircle2 size={20} className="text-green-600 dark:text-green-400" />
        : <XCircle size={20} className="text-red-600 dark:text-red-400" />}
      <div>
        <p className={`text-sm font-bold ${allowed ? 'text-green-700 dark:text-green-400' : 'text-red-700 dark:text-red-400'}`}>
          {allowed ? 'Accès autorisé' : 'Accès refusé'}
        </p>
        <p className="text-xs text-muted-foreground">{decision}</p>
      </div>
    </div>
  )
}

// ─── Explain result tree ──────────────────────────────────────────────────────

function ExplainTree({ result }: { result: ExplainResult }) {
  const [open, setOpen] = useState(true)

  const raw = result as unknown as Record<string, unknown>
  const rules = raw['matched-rules'] as string[] | undefined
  const reason = raw['reason'] as string | undefined
  const applicables = raw['applicable-rules'] as unknown[] | undefined

  return (
    <div className="rounded-xl border border-border bg-card p-4 space-y-3">
      <button onClick={() => setOpen((o) => !o)} className="flex items-center gap-2 text-sm font-semibold text-foreground w-full text-left">
        {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        Explication de la décision
      </button>
      {open && (
        <div className="space-y-2 text-xs">
          {reason && (
            <div><span className="font-semibold text-muted-foreground">Raison :</span> <span>{reason}</span></div>
          )}
          {rules && rules.length > 0 && (
            <div>
              <span className="font-semibold text-muted-foreground">Règles applicables :</span>
              <ul className="mt-1 space-y-0.5 pl-3">
                {rules.map((r, i) => <li key={i} className="font-mono text-foreground">• {r}</li>)}
              </ul>
            </div>
          )}
          {applicables && applicables.length > 0 && (
            <div>
              <span className="font-semibold text-muted-foreground">Règles évaluées :</span>
              <ul className="mt-1 space-y-1 pl-3">
                {applicables.map((r, i) => {
                  const rule = r as Record<string, unknown>
                  const matched = rule['matched'] as boolean
                  return (
                    <li key={i} className={`font-mono ${matched ? 'text-green-600 dark:text-green-400' : 'text-muted-foreground'}`}>
                      {matched ? '✓' : '✗'} {(rule['name'] as string | undefined) ?? `rule-${i}`}
                      {rule['effect'] ? <span className="ml-1 opacity-60">({rule['effect'] as string})</span> : null}
                    </li>
                  )
                })}
              </ul>
            </div>
          )}
          <details className="mt-2">
            <summary className="cursor-pointer text-muted-foreground hover:text-foreground">JSON brut</summary>
            <pre className="mt-1 p-2 bg-muted rounded text-xs overflow-auto max-h-40">{JSON.stringify(result, null, 2)}</pre>
          </details>
        </div>
      )}
    </div>
  )
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function SimulatorPage() {
  const [request, setRequest] = useState<AuthRequest>(DEFAULT_REQUEST)
  const isAuth = useIsAuthorized()
  const explain = useExplain()

  const isReady = !!request.subject.id && !!request.resource.class && !!request.operation

  return (
    <div className="space-y-4 max-w-3xl">
      <div className="bg-card border border-border rounded-xl p-5">
        <h2 className="text-sm font-semibold text-foreground mb-4">Requête d'autorisation</h2>
        <RequestForm value={request} onChange={setRequest} />
        <div className="flex items-center gap-3 mt-4 pt-4 border-t border-border">
          <button
            onClick={() => isAuth.mutate(request)}
            disabled={isAuth.isPending || !isReady}
            className="flex items-center gap-1.5 text-xs px-4 py-2 rounded-md bg-autho-dark text-white hover:bg-autho-dark/90 transition-colors disabled:opacity-50"
          >
            <Play size={13} /> {isAuth.isPending ? 'En cours…' : 'Tester'}
          </button>
          <button
            onClick={() => explain.mutate(request)}
            disabled={explain.isPending || !isReady}
            className="flex items-center gap-1.5 text-xs px-4 py-2 rounded-md border border-input hover:bg-muted transition-colors disabled:opacity-50"
          >
            <HelpCircle size={13} /> {explain.isPending ? 'Analyse…' : 'Expliquer'}
          </button>
          <button
            onClick={() => { isAuth.reset(); explain.reset() }}
            className="flex items-center gap-1.5 text-xs px-3 py-2 rounded-md border border-input hover:bg-muted transition-colors text-muted-foreground ml-auto"
          >
            <Zap size={12} /> Réinitialiser
          </button>
        </div>
      </div>

      {/* Decision result */}
      {isAuth.data && (
        <DecisionBadge decision={((isAuth.data as unknown as Record<string, unknown>)['decision'] as string | undefined) ?? (isAuth.data.results?.[0] ?? 'unknown')} />
      )}

      {/* Explain result */}
      {explain.data && <ExplainTree result={explain.data} />}

      {/* Errors */}
      {(isAuth.isError || explain.isError) && (
        <div className="rounded-lg border border-destructive/50 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {(isAuth.error as Error | null)?.message ?? (explain.error as Error | null)?.message}
        </div>
      )}
    </div>
  )
}
