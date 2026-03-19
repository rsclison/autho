export interface AuditEntry {
  id: number
  ts: string
  request_id: string
  subject_id: string
  resource_class: string
  resource_id: string | null
  operation: string
  decision: 'allow' | 'deny'
  matched_rules: string[]
  payload_hash: string
  previous_hash: string
  hmac: string
}

export interface AuditSearchParams {
  subjectId?: string
  resourceClass?: string
  decision?: 'allow' | 'deny' | ''
  from?: string
  to?: string
  page?: number
  pageSize?: number
}

export interface AuditResult {
  items: AuditEntry[]
  total: number
  page: number
  pageSize: number
}

export interface AuditChainResult {
  valid: boolean
  'broken-at'?: number
  reason?: string
}
