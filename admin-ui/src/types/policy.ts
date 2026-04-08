// Membre d'une condition : valeur litterale ou chemin [EntiteType, "$s"|"$r", attribut]
export type ConditionMember = string | number | [string, string, string]

// Condition : [operateur, gauche, droite]
export type Condition = [string, ConditionMember, ConditionMember]

export interface Rule {
  name: string
  resourceClass?: string | null
  operation: string | null
  priority: number
  effect: 'allow' | 'deny'
  conditions: Condition[]
  domain?: string
  startDate?: string
  endDate?: string
}

export interface GlobalPolicy {
  strategy: string
  rules: Rule[]
}

export interface Policy {
  resourceClass: string
  global?: GlobalPolicy
  strategy?: string
  rules?: Rule[]
}

export interface PolicyImpactSummary {
  totalRequests: number
  changedDecisions: number
  grants: number
  revokes: number
  byOperation?: Record<string, {
    totalRequests: number
    changedDecisions: number
    grants: number
    revokes: number
  }>
}

export interface PolicyImpactBlastRadiusEntry {
  totalRequests: number
  changedDecisions: number
  grants: number
  revokes: number
}

export interface PolicyImpactRiskEntry {
  key: string
  changedDecisions: number
  grants: number
  revokes: number
}

export interface PolicyImpactChange {
  requestId: number
  request: Record<string, unknown>
  before: {
    allowed: boolean
    decisionType: string
    matchedRuleNames: string[]
  }
  after: {
    allowed: boolean
    decisionType: string
    matchedRuleNames: string[]
  }
  changed: boolean
  changeCategory: 'deny_to_allow' | 'allow_to_deny' | 'unchanged' | string
  winningRuleNames: string[]
  losingRuleNames: string[]
  changeType: 'grant' | 'revoke' | 'unchanged' | string
}

export interface PolicyImpactAnalysis {
  analysisId?: number
  resourceClass: string
  baseline: {
    version: number | null
    strategy: string | null
  }
  candidate: {
    version: number | null
    strategy: string | null
    source: 'provided' | 'version' | 'current' | string
  }
  summary: PolicyImpactSummary
  blastRadius: {
    subjects: Record<string, PolicyImpactBlastRadiusEntry>
    resources: Record<string, PolicyImpactBlastRadiusEntry>
    operations: Record<string, PolicyImpactBlastRadiusEntry>
  }
  riskSignals: {
    highRisk: boolean
    revokeCount: number
    changedSubjectCount: number
    changedResourceCount: number
    changedOperationCount: number
    topImpactedSubjects: PolicyImpactRiskEntry[]
    topImpactedResources: PolicyImpactRiskEntry[]
  }
  changes: PolicyImpactChange[]
}

export interface PolicyDeployedVersionLink {
  id?: number
  resourceClass?: string
  version: number
  author: string | null
  comment: string | null
  createdAt: string
  sourceAnalysisId?: number | null
  deploymentKind?: string | null
  sourceCandidateVersion?: number | null
}

export interface PolicyImpactHistoryEntry {
  id: number
  resourceClass: string
  baselineVersion: number | null
  candidateVersion: number | null
  candidateSource: string | null
  author: string | null
  requestCount: number
  changedDecisions: number
  revokeCount: number
  highRisk: boolean
  reviewStatus: 'draft' | 'reviewed' | 'approved' | 'rejected' | string
  reviewedBy?: string | null
  reviewNote?: string | null
  reviewedAt?: string | null
  rolloutStatus: 'not_deployed' | 'deployed' | string
  deployedVersion?: number | null
  deployedBy?: string | null
  deployedAt?: string | null
  createdAt: string
  analysis?: PolicyImpactAnalysis
  candidatePolicy?: Policy
  deployedVersions?: PolicyDeployedVersionLink[]
}

export interface PolicyVersion {
  id?: number
  resourceClass?: string
  version: number
  author: string | null
  comment: string | null
  createdAt: string
  sourceAnalysisId?: number | null
  deploymentKind?: string | null
  sourceCandidateVersion?: number | null
  sourceAnalysis?: PolicyImpactHistoryEntry | null
  policy?: Policy
}

export interface DiffResult {
  resourceClass: string
  from: number
  to: number
  strategy: {
    from: string | null
    to: string | null
    changed: boolean
  }
  summary: {
    strategyChanged: boolean
    addedRules: number
    removedRules: number
    changedRules: number
    unchangedRules: number
    totalRuleChanges: number
  }
  rules: {
    added: Rule[]
    removed: Rule[]
    changed: Array<{
      name: string
      changedFields: string[]
      before: Rule
      after: Rule
    }>
    unchanged: string[]
  }
}

export interface PolicyImpactReviewPayload {
  status: 'draft' | 'reviewed' | 'approved' | 'rejected'
  reviewedBy?: string
  reviewNote?: string
}

export interface PolicyImpactRolloutResult {
  resourceClass: string
  analysisId: number
  newVersion: number
  rolloutStatus: 'deployed' | string
  versionLink?: PolicyDeployedVersionLink | null
  history?: PolicyImpactHistoryEntry | null
  sourceCandidateVersion?: number | null
  sourceCandidatePolicy?: boolean
}

export interface PolicyImpactRequest {
  baselineVersion?: number
  candidateVersion?: number
  candidatePolicy?: Policy
  requests: Array<Record<string, unknown>>
}

export interface PolicyTimelineEvent {
  eventType?: 'impact_analysis_created' | 'impact_reviewed' | 'impact_deployed' | 'policy_version_created' | string
  type?: 'impact_analysis_created' | 'impact_reviewed' | 'impact_deployed' | 'policy_version_created' | string
  occurredAt: string
  resourceClass: string
  analysisId?: number | null
  version?: number | null
  actor?: string | null
  author?: string | null
  reviewedBy?: string | null
  deployedBy?: string | null
  reviewStatus?: string | null
  rolloutStatus?: string | null
  deploymentKind?: string | null
  sourceCandidateVersion?: number | null
  summary?: PolicyImpactSummary | null
}

export interface PolicyTimeline {
  resourceClass: string
  events: PolicyTimelineEvent[]
}

