import { useState } from 'react'
import { ChevronDown, ChevronRight, CheckCircle2, XCircle, Calendar, Hash, Pencil, Trash2 } from 'lucide-react'
import { RuleForm } from './RuleForm'
import type { Rule, Condition, ConditionMember } from '@/types/policy'

// ─── Helpers ─────────────────────────────────────────────────────────────────

const OPERATOR_LABELS: Record<string, string> = {
  '=':      'est égal à',
  'diff':   '≠',
  '<':      '<',
  '<=':     '≤',
  '>':      '>',
  '>=':     '≥',
  'in':     'dans',
  'notin':  'pas dans',
  'date>':  'date après',
}

function formatMember(m: ConditionMember): { label: string; isPath: boolean } {
  if (Array.isArray(m)) {
    const role = m[1] === '$s' ? 'sujet' : 'ressource'
    return { label: `${m[0]}.${role}.${m[2]}`, isPath: true }
  }
  return { label: String(m), isPath: false }
}

function ConditionRow({ cond }: { cond: Condition }) {
  const [op, left, right] = cond
  const l = formatMember(left)
  const r = formatMember(right)
  const opLabel = OPERATOR_LABELS[op] ?? op

  return (
    <div className="flex items-center gap-1.5 text-xs flex-wrap">
      {/* Gauche */}
      <span className={`px-1.5 py-0.5 rounded font-mono ${
        l.isPath
          ? 'bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'
          : 'bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'
      }`}>
        {l.label}
      </span>

      {/* Opérateur */}
      <span className="px-1.5 py-0.5 rounded bg-muted text-muted-foreground font-semibold">
        {opLabel}
      </span>

      {/* Droite */}
      <span className={`px-1.5 py-0.5 rounded font-mono ${
        r.isPath
          ? 'bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'
          : 'bg-green-50 text-green-700 dark:bg-green-900/30 dark:text-green-300'
      }`}>
        {r.label}
      </span>
    </div>
  )
}

// ─── RuleCard ─────────────────────────────────────────────────────────────────

interface Props {
  rule: Rule
  index: number
  onEdit?: (updated: Rule) => void
  onDelete?: () => void
}

export function RuleCard({ rule, index, onEdit, onDelete }: Props) {
  const [open, setOpen] = useState(true)
  const [editing, setEditing] = useState(false)
  const isAllow = rule.effect === 'allow'

  const hasDateRange =
    rule.startDate && rule.endDate &&
    !(rule.startDate === '1961-01-01' && rule.endDate === '3000-12-31')

  if (editing && onEdit) {
    return (
      <div className={`rounded-lg border p-3 ${
        isAllow ? 'border-green-200 dark:border-green-900' : 'border-red-200 dark:border-red-900'
      }`}>
        <RuleForm
          initial={rule}
          onSave={(updated) => { onEdit(updated); setEditing(false) }}
          onCancel={() => setEditing(false)}
        />
      </div>
    )
  }

  return (
    <div className={`rounded-lg border transition-colors ${
      isAllow
        ? 'border-green-200 dark:border-green-900'
        : 'border-red-200 dark:border-red-900'
    }`}>
      {/* Header */}
      <div className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg">
        <button
          onClick={() => setOpen((o) => !o)}
          className="flex items-center gap-3 flex-1 text-left hover:bg-muted/40 transition-colors rounded min-w-0"
        >
          <span className="text-muted-foreground shrink-0">
            {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
          </span>

          {/* Numéro */}
          <span className="text-xs text-muted-foreground w-5 text-right shrink-0">
            {index + 1}
          </span>

          {/* Nom */}
          <span className="text-sm font-semibold text-foreground flex-1 truncate">
            {rule.name || <span className="italic text-muted-foreground">Sans nom</span>}
          </span>

          {/* Opération */}
          {rule.operation && (
            <span className="text-xs px-2 py-0.5 rounded bg-muted text-muted-foreground font-mono shrink-0">
              {rule.operation}
            </span>
          )}

          {/* Priorité */}
          {rule.priority !== undefined && rule.priority !== 0 && (
            <span className="flex items-center gap-1 text-xs text-muted-foreground shrink-0">
              <Hash size={10} /> {rule.priority}
            </span>
          )}

          {/* Effet */}
          <span className={`flex items-center gap-1 text-xs font-semibold shrink-0 ${
            isAllow ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
          }`}>
            {isAllow ? <CheckCircle2 size={13} /> : <XCircle size={13} />}
            {isAllow ? 'allow' : 'deny'}
          </span>
        </button>

        {/* Edit / Delete actions */}
        {(onEdit || onDelete) && (
          <div className="flex items-center gap-1 shrink-0">
            {onEdit && (
              <button
                onClick={() => setEditing(true)}
                className="p-1 text-muted-foreground hover:text-foreground transition-colors rounded"
                title="Modifier"
              >
                <Pencil size={12} />
              </button>
            )}
            {onDelete && (
              <button
                onClick={onDelete}
                className="p-1 text-muted-foreground hover:text-destructive transition-colors rounded"
                title="Supprimer"
              >
                <Trash2 size={12} />
              </button>
            )}
          </div>
        )}
      </div>

      {/* Body */}
      {open && (
        <div className="px-3 pb-3 space-y-2 border-t border-border/50 pt-2">
          {/* Dates */}
          {hasDateRange && (
            <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <Calendar size={11} />
              <span>{rule.startDate} → {rule.endDate}</span>
            </div>
          )}

          {/* Conditions */}
          {rule.conditions && rule.conditions.length > 0 ? (
            <div className="space-y-1.5">
              <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Conditions ({rule.conditions.length})
              </p>
              <div className="space-y-1 pl-2 border-l-2 border-border">
                {rule.conditions.map((cond, i) => (
                  <ConditionRow key={i} cond={cond} />
                ))}
              </div>
            </div>
          ) : (
            <p className="text-xs text-muted-foreground italic">Aucune condition (s'applique toujours)</p>
          )}
        </div>
      )}
    </div>
  )
}
