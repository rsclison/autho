import { ArrowLeft, ShieldAlert } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import { usePolicy, useVersions } from '@/api/policies'
import { PolicyGovernancePanel } from '@/components/policies/PolicyGovernancePanel'
import { normalizePolicyForSave } from '@/lib/policyDocument'

export default function PolicyGovernancePage() {
  const navigate = useNavigate()
  const { resourceClass = '' } = useParams()
  const { data } = usePolicy(resourceClass)
  const { data: versions } = useVersions(resourceClass)
  const normalized = normalizePolicyForSave(data, resourceClass)
  const currentVersion = versions?.[0]?.version ?? null

  return (
    <div className="flex h-full flex-col bg-background">
      <header className="border-b border-border bg-card px-8 py-5">
        <div className="flex items-start justify-between gap-6">
          <div className="space-y-3">
            <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.18em] text-muted-foreground">
              <ShieldAlert size={14} /> Gouvernance
            </div>
            <div className="flex items-center gap-3">
              <h2 className="text-2xl font-semibold text-foreground">{resourceClass}</h2>
              <span className="rounded-full border border-border bg-muted/50 px-3 py-1 text-xs font-medium text-muted-foreground">
                Strategie : <span className="font-mono text-foreground">{String(normalized.strategy ?? 'n/a')}</span>
              </span>
              {currentVersion ? (
                <span className="rounded-full border border-border bg-background px-3 py-1 text-xs font-medium text-muted-foreground">
                  Version courante : <span className="text-foreground">v{currentVersion}</span>
                </span>
              ) : null}
            </div>
            <p className="max-w-4xl text-sm leading-6 text-muted-foreground">
              Compare les versions, genere une preview d'impact, puis reveille, approuve ou deploie une preview validee sans comprimer l'editeur de regles.
            </p>
          </div>
          <button
            onClick={() => navigate(`/policies/${resourceClass}`)}
            className="inline-flex items-center gap-2 rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground hover:bg-muted transition-colors"
          >
            <ArrowLeft size={14} /> Retour a la policy
          </button>
        </div>
      </header>

      <div className="flex-1 min-h-0 overflow-hidden">
        <PolicyGovernancePanel resourceClass={resourceClass} />
      </div>
    </div>
  )
}
