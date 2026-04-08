export interface AuthRequest {
  subject: Record<string, unknown>
  resource: Record<string, unknown>
  operation: string
  context?: Record<string, unknown>
  simulatedPolicy?: Record<string, unknown>
  policyVersion?: number
}

export interface AuthResult {
  allowed?: boolean
  decision?: boolean
  decisionType?: 'allow' | 'deny' | string
  strategy?: string
  results: string[]
  matchedRules?: number
  resourceClass?: string
  resourceId?: string
  operation?: string
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
  allowed?: boolean
  decision: boolean
  decisionType?: 'allow' | 'deny' | string
  strategy: string
  resourceClass?: string
  resourceId?: string
  operation?: string
  matchedRuleNames?: string[]
  rules: ExplainRule[]
}

export interface SimulateResult extends ExplainResult {
  simulated: boolean
  policySource: 'provided' | 'version' | 'current'
  policyVersion: number | null
}

