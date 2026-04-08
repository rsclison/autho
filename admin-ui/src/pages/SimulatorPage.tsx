import { useMemo, useState } from 'react'
import { Zap, HelpCircle, CheckCircle2, XCircle, ChevronDown, ChevronRight, Sparkles } from 'lucide-react'
import { useExplain, useSimulate } from '@/api/decisions'
import { usePolicies } from '@/api/policies'
import type { AuthRequest, ExplainResult, SimulateResult } from '@/types/decision'
import type { Rule } from '@/types/policy'

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

  const operations = useMemo(() => {
    const rc = (value.resource.class as string) ?? ''
    if (!policies || !rc || !policies[rc]) return []
    const policy = policies[rc] as Record<string, unknown>
    const global = policy.global as Record<string, unknown> | undefined
    const rules = (global?.rules ?? []) as Rule[]
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
          placeholder="Role (optionnel)"
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
        <label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">Operation</label>
        <input
          list="sim-operations"
          value={value.operation}
          onChange={(e) => onChange({ ...value, operation: e.target.value })}
          placeholder="ex: read, execute"
          className={`mt-1 ${inputCls}`}
        />
        <datalist id="sim-operations">
          {operations.map((op) => <option key={op} value={op} />)}
        </datalist>
      </div>
    </div>
  )
}

function DecisionBadge({ decisionType, allowed }: { decisionType?: string; allowed?: boolean }) {
  const isAllowed = allowed ?? (decisionType === 'allow' || decisionType === 'Permit')
  return (
    <div className={`flex items-center gap-2 px-4 py-3 rounded-lg ${isAllowed ? 'bg-green-50 border border-green-200 dark:bg-green-900/20 dark:border-green-800' : 'bg-red-50 border border-red-200 dark:bg-red-900/20 dark:border-red-800'}`}>
      {isAllowed
        ? <CheckCircle2 size={20} className="text-green-600 dark:text-green-400" />
        : <XCircle size={20} className="text-red-600 dark:text-red-400" />}
      <div>
        <p className={`text-sm font-bold ${isAllowed ? 'text-green-700 dark:text-green-400' : 'text-red-700 dark:text-red-400'}`}>
          {isAllowed ? 'Acces autorise' : 'Acces refuse'}
        </p>
        <p className="text-xs text-muted-foreground">{decisionType ?? (isAllowed ? 'allow' : 'deny')}</p>
      </div>
    </div>
  )
}

function ExplainTree({ result }: { result: ExplainResult | SimulateResult }) {
  const [open, setOpen] = useState(true)
  const matchedRuleNames = result.matchedRuleNames ?? []

  return (
    <div className="rounded-xl border border-border bg-card p-4 space-y-3">
      <button onClick={() => setOpen((o) => !o)} className="flex items-center gap-2 text-sm font-semibold text-foreground w-full text-left">
        {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        Explication de la decision
      </button>
      {open && (
        <div className="space-y-3 text-xs">
          <div className="grid grid-cols-2 gap-2 text-muted-foreground">
            <span>Strategie: <strong className="text-foreground">{result.strategy}</strong></span>
            <span>Operation: <strong className="text-foreground">{result.operation ?? 'n/a'}</strong></span>
            <span>Classe: <strong className="text-foreground">{result.resourceClass ?? 'n/a'}</strong></span>
            <span>Ressource: <strong className="text-foreground">{result.resourceId ?? 'n/a'}</strong></span>
          </div>

          {matchedRuleNames.length > 0 && (
            <div>
              <span className="font-semibold text-muted-foreground">Regles matchantes :</span>
              <ul className="mt-1 space-y-0.5 pl-3">
                {matchedRuleNames.map((ruleName) => <li key={ruleName} className="font-mono text-foreground">- {ruleName}</li>)}
              </ul>
            </div>
          )}

          {result.rules.length > 0 && (
            <div>
              <span className="font-semibold text-muted-foreground">Regles evaluees :</span>
              <ul className="mt-1 space-y-1 pl-3">
                {result.rules.map((rule, index) => (
                  <li key={`${rule.name}-${index}`} className={`font-mono ${rule.matched ? 'text-green-600 dark:text-green-400' : 'text-muted-foreground'}`}>
                    {rule.matched ? 'OK' : '--'} {rule.name}
                    <span className="ml-1 opacity-60">({rule.effect})</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {'simulated' in result && result.simulated && (
            <div className="rounded-md border border-blue-500/30 bg-blue-500/5 px-3 py-2 text-muted-foreground">
              Simulation via <strong className="text-foreground">{result.policySource}</strong>
              {result.policyVersion !== null ? <> sur la version <strong className="text-foreground">v{result.policyVersion}</strong></> : null}
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

export default function SimulatorPage() {
  const [request, setRequest] = useState<AuthRequest>(DEFAULT_REQUEST)
  const explain = useExplain()
  const simulate = useSimulate()

  const isReady = !!request.subject.id && !!request.resource.class && !!request.operation
  const currentResult = simulate.data ?? explain.data

  return (
    <div className="space-y-4 max-w-3xl">
      <div className="bg-card border border-border rounded-xl p-5">
        <h2 className="text-sm font-semibold text-foreground mb-4">Requete d'autorisation</h2>
        <RequestForm value={request} onChange={setRequest} />
        <div className="flex items-center gap-3 mt-4 pt-4 border-t border-border">
          <button
            onClick={() => simulate.mutate(request)}
            disabled={simulate.isPending || !isReady}
            className="flex items-center gap-1.5 text-xs px-4 py-2 rounded-md bg-autho-dark text-white hover:bg-autho-dark/90 transition-colors disabled:opacity-50"
          >
            <Sparkles size={13} /> {simulate.isPending ? 'Simulation...' : 'Simuler'}
          </button>
          <button
            onClick={() => explain.mutate(request)}
            disabled={explain.isPending || !isReady}
            className="flex items-center gap-1.5 text-xs px-4 py-2 rounded-md border border-input hover:bg-muted transition-colors disabled:opacity-50"
          >
            <HelpCircle size={13} /> {explain.isPending ? 'Analyse...' : 'Expliquer'}
          </button>
          <button
            onClick={() => {
              explain.reset()
              simulate.reset()
            }}
            className="flex items-center gap-1.5 text-xs px-3 py-2 rounded-md border border-input hover:bg-muted transition-colors text-muted-foreground ml-auto"
          >
            <Zap size={12} /> Reinitialiser
          </button>
        </div>
      </div>

      {currentResult && (
        <DecisionBadge decisionType={currentResult.decisionType} allowed={currentResult.allowed ?? currentResult.decision} />
      )}

      {currentResult && <ExplainTree result={currentResult} />}

      {(simulate.isError || explain.isError) && (
        <div className="rounded-lg border border-destructive/50 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {(simulate.error as Error | null)?.message ?? (explain.error as Error | null)?.message}
        </div>
      )}
    </div>
  )
}


