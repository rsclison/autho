import { useState } from 'react'
import { Trash2, RefreshCw, RotateCcw, Users, AlertTriangle } from 'lucide-react'
import { useCacheStats, useClearCache, useInvalidateEntry } from '@/api/cache'
import { useStatus } from '@/api/status'
import { useAdminActions } from '@/api/decisions'
import { CircuitBreakerList } from '@/components/dashboard/CircuitBreakerList'
import { formatNumber, formatPercent } from '@/lib/utils'
import toast from 'react-hot-toast'

function CacheBar({ label, hits, misses }: { label: string; hits: number; misses: number }) {
  const total = hits + misses
  const ratio = total > 0 ? hits / total : 0
  return (
    <div className="space-y-1">
      <div className="flex justify-between text-xs text-muted-foreground">
        <span>{label}</span>
        <span>{formatPercent(ratio)} ({formatNumber(hits)} hits / {formatNumber(misses)} misses)</span>
      </div>
      <div className="h-2 bg-muted rounded-full overflow-hidden">
        <div
          className="h-full bg-green-500 rounded-full transition-all"
          style={{ width: `${ratio * 100}%` }}
        />
      </div>
    </div>
  )
}

export default function InfrastructurePage() {
  const { data: stats, isLoading: statsLoading } = useCacheStats()
  const { data: status } = useStatus()
  const clearCache = useClearCache()
  const invalidate = useInvalidateEntry()
  const { reinit, reloadRules, reloadPersons } = useAdminActions()

  const [invalidateType, setInvalidateType] = useState('subject')
  const [invalidateKey, setInvalidateKey] = useState('')

  const handleInvalidate = () => {
    if (!invalidateKey.trim()) {
      toast.error('Saisir une clé à invalider')
      return
    }
    invalidate.mutate({ type: invalidateType, key: invalidateKey.trim() })
    setInvalidateKey('')
  }

  return (
    <div className="space-y-6">
      {/* Cache */}
      <div className="bg-card border border-border rounded-xl p-5">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-sm font-semibold text-foreground">Cache</h2>
          <button
            onClick={() => clearCache.mutate()}
            disabled={clearCache.isPending}
            className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-md border border-destructive text-destructive hover:bg-destructive/10 transition-colors disabled:opacity-50"
          >
            <Trash2 size={12} />
            Vider tout
          </button>
        </div>

        {statsLoading ? (
          <div className="space-y-3">
            {[1,2,3,4].map(i => (
              <div key={i} className="h-6 bg-muted rounded animate-pulse" />
            ))}
          </div>
        ) : stats ? (
          <div className="space-y-3">
            <CacheBar label="Décisions" hits={stats['decision-hits']} misses={stats['decision-misses']} />
            <CacheBar label="Sujets"    hits={stats['subject-hits']}   misses={stats['subject-misses']} />
            <CacheBar label="Ressources" hits={stats['resource-hits']} misses={stats['resource-misses']} />
            <CacheBar label="Politiques" hits={stats['policy-hits']}   misses={stats['policy-misses']} />
          </div>
        ) : (
          <p className="text-sm text-muted-foreground text-center py-4">
            Impossible de charger les statistiques
          </p>
        )}

        {/* Invalidation ciblée */}
        <div className="mt-4 pt-4 border-t border-border">
          <p className="text-xs font-medium text-muted-foreground mb-2">Invalider une entrée</p>
          <div className="flex gap-2">
            <select
              value={invalidateType}
              onChange={(e) => setInvalidateType(e.target.value)}
              className="px-2 py-1.5 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-1 focus:ring-ring"
            >
              <option value="subject">Sujet</option>
              <option value="resource">Ressource</option>
              <option value="policy">Politique</option>
              <option value="decision">Décision</option>
            </select>
            <input
              type="text"
              value={invalidateKey}
              onChange={(e) => setInvalidateKey(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleInvalidate()}
              placeholder="Clé (ex: alice)"
              className="flex-1 px-3 py-1.5 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-1 focus:ring-ring"
            />
            <button
              onClick={handleInvalidate}
              disabled={invalidate.isPending}
              className="px-3 py-1.5 rounded-md bg-autho-dark text-white text-sm hover:bg-autho-dark/90 transition-colors disabled:opacity-50"
            >
              Invalider
            </button>
          </div>
        </div>
      </div>

      {/* Circuit Breakers */}
      <div className="bg-card border border-border rounded-xl p-5">
        <h2 className="text-sm font-semibold text-foreground mb-4">Circuit Breakers</h2>
        {status?.circuit_breakers ? (
          <CircuitBreakerList breakers={status.circuit_breakers} />
        ) : (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <AlertTriangle size={14} />
            <span>Aucun circuit breaker enregistré</span>
          </div>
        )}
      </div>

      {/* Actions système */}
      <div className="bg-card border border-border rounded-xl p-5">
        <h2 className="text-sm font-semibold text-foreground mb-4">Actions système</h2>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <ActionButton
            icon={RotateCcw}
            label="Réinitialiser le PDP"
            description="Recharge règles, personnes et délégations"
            loading={reinit.isPending}
            onClick={() => reinit.mutate(undefined, {
              onSuccess: () => toast.success('PDP réinitialisé'),
            })}
          />
          <ActionButton
            icon={RefreshCw}
            label="Recharger les règles"
            description="Relit jrules.edn depuis le disque"
            loading={reloadRules.isPending}
            onClick={() => reloadRules.mutate(undefined, {
              onSuccess: () => toast.success('Règles rechargées'),
            })}
          />
          <ActionButton
            icon={Users}
            label="Recharger les personnes"
            description="Relit le cache des personnes (LDAP/fichier)"
            loading={reloadPersons.isPending}
            onClick={() => reloadPersons.mutate(undefined, {
              onSuccess: (data) => toast.success(`${data.personCount} personnes rechargées`),
            })}
          />
        </div>
      </div>
    </div>
  )
}

function ActionButton({
  icon: Icon, label, description, loading, onClick,
}: {
  icon: typeof Trash2
  label: string
  description: string
  loading: boolean
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      disabled={loading}
      className="flex items-start gap-3 p-4 rounded-lg border border-border hover:bg-muted/60 transition-colors text-left disabled:opacity-50 disabled:cursor-not-allowed"
    >
      <div className="p-1.5 rounded-md bg-muted mt-0.5">
        <Icon size={14} className="text-foreground" />
      </div>
      <div>
        <p className="text-sm font-medium text-foreground">{loading ? 'En cours…' : label}</p>
        <p className="text-xs text-muted-foreground mt-0.5">{description}</p>
      </div>
    </button>
  )
}
