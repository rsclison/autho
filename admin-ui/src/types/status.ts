export type CircuitBreakerState = 'open' | 'closed' | 'half-open'

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

export interface ServerStatus {
  status: string
  version: string
  uptime_seconds: number
  rules_status: string
  cache_stats: CacheStats
  circuit_breakers: Record<string, CircuitBreakerState>
}
