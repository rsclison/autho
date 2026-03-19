import { FileX } from 'lucide-react'
import { RuleCard } from './RuleCard'
import type { Rule } from '@/types/policy'

interface Props {
  rules: Rule[]
  operation?: string | null
}

export function RuleList({ rules, operation }: Props) {
  const filtered = operation
    ? rules.filter((r) => !r.operation || r.operation === operation)
    : rules

  if (filtered.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-muted-foreground gap-2">
        <FileX size={20} />
        <p className="text-sm">Aucune règle définie</p>
      </div>
    )
  }

  return (
    <div className="space-y-2">
      {filtered.map((rule, i) => (
        <RuleCard key={rule.name ?? i} rule={rule} index={i} />
      ))}
    </div>
  )
}
