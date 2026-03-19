import { Activity, Database, Clock, Shield, AlertTriangle } from 'lucide-react'
import { MetricCard } from '@/components/dashboard/MetricCard'
import { DecisionsChart } from '@/components/dashboard/DecisionsChart'
import { CircuitBreakerList } from '@/components/dashboard/CircuitBreakerList'
import { useStatus } from '@/api/status'
import { usePolicies } from '@/api/policies'
import { useAudit } from '@/api/audit'
import { formatNumber, formatPercent, formatDate, truncate } from '@/lib/utils'

export default function DashboardPage() {
  const { data: status, isLoading: statusLoading } = useStatus()
  const { data: policies } = usePolicies()
  const { data: recentAudit } = useAudit({ page: 1, pageSize: 10 })

  const policyCount = policies ? Object.keys(policies).length : 0
  const cache = status?.cache_stats
  const decisionHitRate = cache
    ? (cache['decision-hits'] + cache['decision-misses']) > 0
      ? cache['decision-hits'] / (cache['decision-hits'] + cache['decision-misses'])
      : 0
    : 0
  const allowTotal = cache?.['decision-hits'] ?? 0
  const denyTotal  = cache?.['decision-misses'] ?? 0
  const uptime = status
    ? `${Math.floor(status.uptime_seconds / 3600)}h ${Math.floor((status.uptime_seconds % 3600) / 60)}m`
    : '…'

  return (
    <div className="space-y-6">
      {/* Métriques principales */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <MetricCard
          title="Décisions cachées"
          value={statusLoading ? '…' : formatNumber(cache?.['decision-hits'] ?? 0)}
          sub="Hits cache décision"
          icon={Activity}
          color="green"
        />
        <MetricCard
          title="Hit Rate cache"
          value={statusLoading ? '…' : formatPercent(decisionHitRate)}
          sub={`${formatNumber(cache?.['decision-size'] ?? 0)} entrées`}
          icon={Database}
          color={decisionHitRate > 0.7 ? 'green' : decisionHitRate > 0.4 ? 'yellow' : 'red'}
        />
        <MetricCard
          title="Uptime"
          value={statusLoading ? '…' : uptime}
          sub={status?.rules_status === 'loaded' ? 'Règles chargées' : 'Règles non chargées'}
          icon={Clock}
          color={status?.rules_status === 'loaded' ? 'green' : 'red'}
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
          {status?.circuit_breakers ? (
            <CircuitBreakerList breakers={status.circuit_breakers} />
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
        {recentAudit?.items && recentAudit.items.length > 0 ? (
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
