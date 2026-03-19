export type Condition = [string, string, string]

export interface Rule {
  name: string
  resourceClass?: string
  operation: string
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

export interface PolicyVersion {
  version: number
  author: string | null
  comment: string | null
  createdAt: string
}

export interface DiffResult {
  added: string[]
  removed: string[]
  changed: string[]
}
