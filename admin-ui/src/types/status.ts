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

// Matches actual /status response from handler.clj
export interface ServerStatus {
  service: string
  version: string
  status: string
  uptime: {
    milliseconds: number
    seconds: number
    minutes: number
    hours: number
    formatted: string
  }
  rulesRepository: string
  rateLimit: { enabled: boolean; requestsPerMinute: number }
  kafka: { enabled: boolean }
  circuitBreakers: Record<string, CircuitBreakerState>
  timestamp: string
}
