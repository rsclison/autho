// Membre d'une condition : valeur littérale ou chemin [EntitéType, "$s"|"$r", attribut]
export type ConditionMember = string | number | [string, string, string]

// Condition : [opérateur, gauche, droite]
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
