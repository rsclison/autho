import { cn } from '@/lib/utils'
import type { CircuitBreakerState } from '@/types/status'

interface Props {
  breakers: Record<string, CircuitBreakerState>
}

const stateConfig: Record<CircuitBreakerState, { label: string; dot: string; badge: string }> = {
  closed:    { label: 'Fermé',        dot: 'bg-green-500',  badge: 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300' },
  open:      { label: 'Ouvert',       dot: 'bg-red-500',    badge: 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300' },
  'half-open': { label: 'Semi-ouvert', dot: 'bg-yellow-500', badge: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/40 dark:text-yellow-300' },
}

export function CircuitBreakerList({ breakers }: Props) {
  const entries = Object.entries(breakers)

  if (entries.length === 0) {
    return (
      <p className="text-sm text-muted-foreground py-4 text-center">
        Aucun circuit breaker enregistré
      </p>
    )
  }

  return (
    <div className="space-y-2">
      {entries.sort(([a], [b]) => a.localeCompare(b)).map(([key, state]) => {
        const cfg = stateConfig[state] ?? stateConfig.closed
        return (
          <div
            key={key}
            className="flex items-center justify-between py-2 px-1 rounded-md"
          >
            <div className="flex items-center gap-2.5 min-w-0">
              <span className={cn('w-2 h-2 rounded-full shrink-0', cfg.dot)} />
              <span className="text-sm font-mono text-foreground truncate">{key}</span>
            </div>
            <span className={cn('text-xs font-medium px-2 py-0.5 rounded-full', cfg.badge)}>
              {cfg.label}
            </span>
          </div>
        )
      })}
    </div>
  )
}
