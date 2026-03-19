export interface AuthRequest {
  subject: Record<string, unknown>
  resource: Record<string, unknown>
  operation: string
  context?: Record<string, unknown>
  simulatedPolicy?: Record<string, unknown>
  policyVersion?: number
}

export interface AuthResult {
  results: string[]
}

export interface ExplainRule {
  name: string
  effect: 'allow' | 'deny'
  operation: string
  matched: boolean
  subjectCond?: unknown[]
  resourceCond?: unknown[]
}

export interface ExplainResult {
  decision: boolean
  strategy: string
  totalRules: number
  matchedRules: number
  rules: ExplainRule[]
}

export interface SimulateResult extends ExplainResult {
  simulated: boolean
  policySource: 'provided' | 'version' | 'current'
  policyVersion: number | null
}
