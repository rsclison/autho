import { Activity, Database, Clock, Shield, AlertTriangle } from 'lucide-react'
import { MetricCard } from '@/components/dashboard/MetricCard'
import { DecisionsChart } from '@/components/dashboard/DecisionsChart'
import { CircuitBreakerList } from '@/components/dashboard/CircuitBreakerList'
import { useStatus } from '@/api/status'
import { useCacheStats } from '@/api/cache'
import { usePolicies } from '@/api/policies'
import { useAudit } from '@/api/audit'
import { formatNumber, formatPercent, formatDate, truncate } from '@/lib/utils'

export default function DashboardPage() {
  const { data: status, isLoading: statusLoading } = useStatus()
  const { data: cache } = useCacheStats()
  const { data: policies } = usePolicies()
  const {
    data: recentAudit,
    error: auditError,
    isError: auditUnavailable,
  } = useAudit(
    { page: 1, pageSize: 10 },
    { suppressErrorToast: true, retry: false },
  )

  const policyCount = policies ? Object.keys(policies).length : 0
  const decisionHitRate = cache && (cache['decision-hits'] + cache['decision-misses']) > 0
    ? cache['decision-hits'] / (cache['decision-hits'] + cache['decision-misses'])
    : 0
  const allowTotal = cache?.['decision-hits'] ?? 0
  const denyTotal  = cache?.['decision-misses'] ?? 0

  return (
    <div className="space-y-6">
      {/* Métriques principales */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <MetricCard
          title="Décisions cachées"
          value={!cache ? '…' : formatNumber(cache['decision-hits'])}
          sub="Hits cache décision"
          icon={Activity}
          color="green"
        />
        <MetricCard
          title="Hit Rate cache"
          value={!cache ? '…' : formatPercent(decisionHitRate)}
          sub={`${formatNumber(cache?.['decision-size'] ?? 0)} entrées`}
          icon={Database}
          color={decisionHitRate > 0.7 ? 'green' : decisionHitRate > 0.4 ? 'yellow' : 'red'}
        />
        <MetricCard
          title="Uptime"
          value={statusLoading ? '…' : (status?.uptime?.formatted ?? '—')}
          sub={status?.rulesRepository === 'loaded' ? 'Règles chargées' : 'Règles non chargées'}
          icon={Clock}
          color={status?.rulesRepository === 'loaded' ? 'green' : 'red'}
        />
        <MetricCard
          title="Politiques actives"
          value={policyCount}
          sub="Classes de ressources"
          icon={Shield}
          color="default"
        />
      </div>

      {/* Graphique + Circuit breakers */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 bg-card border border-border rounded-xl p-5">
          <h2 className="text-sm font-semibold text-foreground mb-4">
            Décisions d'autorisation (simulation 1h)
          </h2>
          <DecisionsChart allowTotal={allowTotal} denyTotal={denyTotal} />
        </div>

        <div className="bg-card border border-border rounded-xl p-5">
          <h2 className="text-sm font-semibold text-foreground mb-4">Circuit Breakers</h2>
          {status?.circuitBreakers ? (
            <CircuitBreakerList breakers={status.circuitBreakers} />
          ) : (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <AlertTriangle size={14} />
              <span>Données non disponibles</span>
            </div>
          )}
        </div>
      </div>

      {/* Dernières décisions */}
      <div className="bg-card border border-border rounded-xl p-5">
        <h2 className="text-sm font-semibold text-foreground mb-4">
          Dernières décisions d'autorisation
        </h2>
        {auditUnavailable ? (
          <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-4 text-sm text-amber-900 dark:border-amber-900/40 dark:bg-amber-950/30 dark:text-amber-200">
            <div className="flex items-start gap-2">
              <AlertTriangle size={16} className="mt-0.5 shrink-0" />
              <div className="space-y-1">
                <p className="font-medium">Journal d'audit indisponible</p>
                <p className="text-xs leading-5 text-amber-800/90 dark:text-amber-200/80">
                  {auditError instanceof Error
                    ? auditError.message
                    : "La lecture du stockage d'audit a échoué. Le Dashboard reste utilisable, mais les dernières décisions ne peuvent pas être affichées."}
                </p>
              </div>
            </div>
          </div>
        ) : recentAudit?.items && recentAudit.items.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border">
                  <th className="text-left py-2 px-3 text-xs font-medium text-muted-foreground">Horodatage</th>
                  <th className="text-left py-2 px-3 text-xs font-medium text-muted-foreground">Sujet</th>
                  <th className="text-left py-2 px-3 text-xs font-medium text-muted-foreground">Ressource</th>
                  <th className="text-left py-2 px-3 text-xs font-medium text-muted-foreground">Opération</th>
                  <th className="text-left py-2 px-3 text-xs font-medium text-muted-foreground">Décision</th>
                </tr>
              </thead>
              <tbody>
                {recentAudit.items.map((entry) => (
                  <tr key={entry.id} className="border-b border-border/50 hover:bg-muted/40 transition-colors">
                    <td className="py-2 px-3 text-xs text-muted-foreground font-mono">
                      {formatDate(entry.ts)}
                    </td>
                    <td className="py-2 px-3 font-medium">{entry.subject_id}</td>
                    <td className="py-2 px-3 text-muted-foreground">
                      {entry.resource_class}
                      {entry.resource_id ? (
                        <span className="text-xs ml-1">#{truncate(entry.resource_id, 12)}</span>
                      ) : null}
                    </td>
                    <td className="py-2 px-3 font-mono text-xs">{entry.operation}</td>
                    <td className="py-2 px-3">
                      <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold ${
                        entry.decision === 'allow'
                          ? 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300'
                          : 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300'
                      }`}>
                        {entry.decision.toUpperCase()}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground text-center py-8">
            Aucune décision enregistrée dans le journal d'audit
          </p>
        )}
      </div>
    </div>
  )
}
