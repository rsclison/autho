import { useState } from 'react'
import { X, Save } from 'lucide-react'
import { ConditionBuilder } from './ConditionBuilder'
import type { Rule, Condition } from '@/types/policy'

// ─── Constants ────────────────────────────────────────────────────────────────

const KNOWN_OPERATIONS = ['lire', 'executer']

const DEFAULT_START = '1961-01-01'
const DEFAULT_END   = '3000-12-31'

// ─── Helpers ──────────────────────────────────────────────────────────────────

function emptyRule(): Rule {
  return {
    name: '',
    operation: null,
    priority: 0,
    effect: 'allow',
    conditions: [],
    startDate: DEFAULT_START,
    endDate: DEFAULT_END,
  }
}

function isDefaultDateRange(start?: string, end?: string) {
  return start === DEFAULT_START && end === DEFAULT_END
}

// ─── RuleForm ─────────────────────────────────────────────────────────────────

interface Props {
  initial?: Rule
  onSave: (rule: Rule) => void
  onCancel: () => void
}

export function RuleForm({ initial, onSave, onCancel }: Props) {
  const [rule, setRule] = useState<Rule>(initial ?? emptyRule())
  const [useDateRange, setUseDateRange] = useState(
    !isDefaultDateRange(initial?.startDate, initial?.endDate) &&
    !!(initial?.startDate && initial?.endDate)
  )

  const set = <K extends keyof Rule>(key: K, value: Rule[K]) =>
    setRule((r) => ({ ...r, [key]: value }))

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const saved: Rule = {
      ...rule,
      startDate: useDateRange ? rule.startDate : DEFAULT_START,
      endDate:   useDateRange ? rule.endDate   : DEFAULT_END,
    }
    onSave(saved)
  }

  const isEdit = !!initial

  return (
    <form
      onSubmit={handleSubmit}
      className="flex flex-col gap-4 text-sm"
    >
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="font-semibold text-foreground text-sm">
          {isEdit ? 'Modifier la règle' : 'Nouvelle règle'}
        </h3>
        <button type="button" onClick={onCancel} className="p-1 hover:text-destructive transition-colors">
          <X size={14} />
        </button>
      </div>

      {/* Name */}
      <Field label="Nom">
        <input
          type="text"
          value={rule.name ?? ''}
          onChange={(e) => set('name', e.target.value)}
          placeholder="ex: allow-read-own"
          className="input-sm"
          required
        />
      </Field>

      {/* Effect + Operation row */}
      <div className="grid grid-cols-2 gap-3">
        <Field label="Effet">
          <div className="flex gap-2">
            {(['allow', 'deny'] as const).map((ef) => (
              <button
                key={ef}
                type="button"
                onClick={() => set('effect', ef)}
                className={`flex-1 text-xs py-1 rounded border transition-colors font-semibold ${
                  rule.effect === ef
                    ? ef === 'allow'
                      ? 'bg-green-600 border-green-600 text-white'
                      : 'bg-red-600 border-red-600 text-white'
                    : 'border-input bg-background text-muted-foreground hover:bg-muted'
                }`}
              >
                {ef}
              </button>
            ))}
          </div>
        </Field>

        <Field label="Priorité">
          <input
            type="number"
            value={rule.priority ?? 0}
            onChange={(e) => set('priority', Number(e.target.value))}
            className="input-sm"
          />
        </Field>
      </div>

      {/* Operation */}
      <Field label="Opération (vide = toutes)">
        <input
          list="known-ops"
          value={rule.operation ?? ''}
          onChange={(e) => set('operation', e.target.value || null)}
          placeholder="ex: lire"
          className="input-sm font-mono"
        />
        <datalist id="known-ops">
          {KNOWN_OPERATIONS.map((op) => <option key={op} value={op} />)}
        </datalist>
      </Field>

      {/* Date range */}
      <div className="space-y-2">
        <label className="flex items-center gap-2 text-xs text-muted-foreground cursor-pointer">
          <input
            type="checkbox"
            checked={useDateRange}
            onChange={(e) => setUseDateRange(e.target.checked)}
            className="rounded"
          />
          Limiter à une plage de dates
        </label>

        {useDateRange && (
          <div className="grid grid-cols-2 gap-3 pl-5">
            <Field label="Début">
              <input
                type="date"
                value={rule.startDate ?? DEFAULT_START}
                onChange={(e) => set('startDate', e.target.value)}
                className="input-sm"
              />
            </Field>
            <Field label="Fin">
              <input
                type="date"
                value={rule.endDate ?? DEFAULT_END}
                onChange={(e) => set('endDate', e.target.value)}
                className="input-sm"
              />
            </Field>
          </div>
        )}
      </div>

      {/* Conditions */}
      <div className="space-y-2">
        <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
          Conditions
        </label>
        <ConditionBuilder
          conditions={rule.conditions ?? []}
          onChange={(c: Condition[]) => set('conditions', c)}
        />
      </div>

      {/* Actions */}
      <div className="flex justify-end gap-2 pt-2 border-t border-border">
        <button
          type="button"
          onClick={onCancel}
          className="text-xs px-3 py-1.5 rounded border border-input bg-background hover:bg-muted transition-colors"
        >
          Annuler
        </button>
        <button
          type="submit"
          className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded bg-autho-dark text-white hover:opacity-90 transition-opacity"
        >
          <Save size={12} />
          {isEdit ? 'Enregistrer' : 'Créer la règle'}
        </button>
      </div>
    </form>
  )
}

// ─── Field helper ─────────────────────────────────────────────────────────────

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <label className="text-xs text-muted-foreground">{label}</label>
      {children}
    </div>
  )
}
