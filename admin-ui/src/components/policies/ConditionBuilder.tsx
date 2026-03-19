import { useId } from 'react'
import { Trash2, Plus, Loader2 } from 'lucide-react'
import { usePipSchema } from '@/api/pips'
import type { Condition, ConditionMember } from '@/types/policy'

// ─── Constants ────────────────────────────────────────────────────────────────

export const OPERATORS = [
  { value: '=',      label: '= égal' },
  { value: 'diff',   label: '≠ différent' },
  { value: '<',      label: '< inférieur' },
  { value: '<=',     label: '≤ inf. ou égal' },
  { value: '>',      label: '> supérieur' },
  { value: '>=',     label: '≥ sup. ou égal' },
  { value: 'in',     label: 'in  dans la liste' },
  { value: 'notin',  label: 'notin  hors liste' },
  { value: 'date>',  label: 'date> après la date' },
]

// Fallback si l'API n'est pas disponible
const FALLBACK_ENTITY_TYPES = [
  'Person', 'Facture', 'Diplome', 'Application',
  'Contrat', 'EngagementJuridique', 'Mouvement',
]

const FALLBACK_ATTRS = [
  'clearance-level', 'code', 'codegestion', 'contains-pii', 'department',
  'montant', 'name', 'owner-department', 'required-clearance-level',
  'responsible-department', 'role', 'service', 'seuil',
]

const ROLES = [
  { value: '$s', label: 'sujet ($s)' },
  { value: '$r', label: 'ressource ($r)' },
]

// ─── Helpers ──────────────────────────────────────────────────────────────────

type MemberType = 'path' | 'literal'

function detectMemberType(m: ConditionMember): MemberType {
  return Array.isArray(m) ? 'path' : 'literal'
}

function memberToPath(m: ConditionMember): [string, string, string] {
  if (Array.isArray(m)) return m
  return ['Person', '$s', '']
}

function memberToLiteral(m: ConditionMember): string {
  if (Array.isArray(m)) return ''
  return String(m)
}

// ─── MemberEditor ─────────────────────────────────────────────────────────────

interface MemberEditorProps {
  member: ConditionMember
  onChange: (m: ConditionMember) => void
  side: 'left' | 'right'
  entityTypes: string[]
  attrsByEntity: Record<string, string[]>
  attrsLoading: boolean
}

function MemberEditor({ member, onChange, side, entityTypes, attrsByEntity, attrsLoading }: MemberEditorProps) {
  const type = detectMemberType(member)
  const [entity, role, attr] = memberToPath(member)
  const literalVal = memberToLiteral(member)
  const listId = useId()

  const colorClass = side === 'left'
    ? 'bg-blue-50 border-blue-200 dark:bg-blue-900/20 dark:border-blue-800'
    : 'bg-green-50 border-green-200 dark:bg-green-900/20 dark:border-green-800'

  // Known attributes for the currently selected entity, merged with fallback
  const knownAttrs = [
    ...new Set([
      ...(attrsByEntity[entity] ?? []),
      ...FALLBACK_ATTRS,
    ]),
  ].sort()

  return (
    <div className={`flex flex-col gap-1.5 flex-1 rounded-md border p-2 ${colorClass}`}>
      {/* Type toggle */}
      <div className="flex gap-1">
        {(['path', 'literal'] as MemberType[]).map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => {
              if (t === 'path') onChange([entityTypes[0] ?? 'Person', '$s', ''])
              else onChange('')
            }}
            className={`text-[10px] px-2 py-0.5 rounded transition-colors ${
              type === t
                ? 'bg-autho-dark text-white'
                : 'bg-muted text-muted-foreground hover:bg-muted/80'
            }`}
          >
            {t === 'path' ? 'Chemin' : 'Valeur'}
          </button>
        ))}
      </div>

      {type === 'path' ? (
        <div className="flex gap-1 flex-wrap">
          {/* Entity type */}
          <select
            value={entity}
            onChange={(e) => onChange([e.target.value, role, attr])}
            className="text-xs px-1.5 py-0.5 rounded border border-input bg-background min-w-0 flex-1"
          >
            {entityTypes.map((et) => (
              <option key={et} value={et}>{et}</option>
            ))}
          </select>

          {/* Role */}
          <select
            value={role}
            onChange={(e) => onChange([entity, e.target.value, attr])}
            className="text-xs px-1.5 py-0.5 rounded border border-input bg-background"
          >
            {ROLES.map((r) => (
              <option key={r.value} value={r.value}>{r.label}</option>
            ))}
          </select>

          {/* Attribute with dynamic datalist */}
          <div className="relative flex-1 min-w-[80px] flex items-center">
            <input
              list={listId}
              value={attr}
              onChange={(e) => onChange([entity, role, e.target.value])}
              placeholder="attribut"
              className="text-xs px-1.5 py-0.5 rounded border border-input bg-background w-full font-mono pr-5"
            />
            {attrsLoading && (
              <Loader2 size={10} className="absolute right-1 text-muted-foreground animate-spin" />
            )}
            <datalist id={listId}>
              {knownAttrs.map((a) => <option key={a} value={a} />)}
            </datalist>
          </div>
        </div>
      ) : (
        <input
          type="text"
          value={literalVal}
          onChange={(e) => {
            const v = e.target.value
            const num = Number(v)
            onChange(!isNaN(num) && v !== '' ? num : v)
          }}
          placeholder="valeur littérale"
          className="text-xs px-1.5 py-0.5 rounded border border-input bg-background w-full font-mono"
        />
      )}
    </div>
  )
}

// ─── ConditionRow ─────────────────────────────────────────────────────────────

interface ConditionRowProps {
  cond: Condition
  index: number
  onChange: (i: number, cond: Condition) => void
  onRemove: (i: number) => void
  entityTypes: string[]
  attrsByEntity: Record<string, string[]>
  attrsLoading: boolean
}

function ConditionRow({ cond, index, onChange, onRemove, entityTypes, attrsByEntity, attrsLoading }: ConditionRowProps) {
  const [op, left, right] = cond

  return (
    <div className="flex items-start gap-2">
      <span className="text-[10px] text-muted-foreground mt-3 w-4 text-right shrink-0">
        {index + 1}
      </span>

      {/* Left member */}
      <MemberEditor
        member={left}
        side="left"
        onChange={(m) => onChange(index, [op, m, right])}
        entityTypes={entityTypes}
        attrsByEntity={attrsByEntity}
        attrsLoading={attrsLoading}
      />

      {/* Operator */}
      <div className="shrink-0 mt-2">
        <select
          value={op}
          onChange={(e) => onChange(index, [e.target.value, left, right])}
          className="text-xs px-1.5 py-1 rounded border border-input bg-background font-semibold"
        >
          {OPERATORS.map((o) => (
            <option key={o.value} value={o.value}>{o.value}</option>
          ))}
        </select>
      </div>

      {/* Right member */}
      <MemberEditor
        member={right}
        side="right"
        onChange={(m) => onChange(index, [op, left, m])}
        entityTypes={entityTypes}
        attrsByEntity={attrsByEntity}
        attrsLoading={attrsLoading}
      />

      {/* Remove */}
      <button
        type="button"
        onClick={() => onRemove(index)}
        className="mt-2 p-1 text-muted-foreground hover:text-destructive transition-colors shrink-0"
        title="Supprimer la condition"
      >
        <Trash2 size={13} />
      </button>
    </div>
  )
}

// ─── ConditionBuilder ─────────────────────────────────────────────────────────

interface Props {
  conditions: Condition[]
  onChange: (conditions: Condition[]) => void
}

export function ConditionBuilder({ conditions, onChange }: Props) {
  const { data: schema, isLoading } = usePipSchema()

  // Entity types: from schema keys, else fallback
  const entityTypes = schema
    ? Object.keys(schema).sort()
    : FALLBACK_ENTITY_TYPES

  // Attributes per entity: schema values, else empty (MemberEditor merges fallback)
  const attrsByEntity: Record<string, string[]> = schema ?? {}

  function handleChange(i: number, cond: Condition) {
    const next = [...conditions]
    next[i] = cond
    onChange(next)
  }

  function handleRemove(i: number) {
    onChange(conditions.filter((_, idx) => idx !== i))
  }

  function handleAdd() {
    const defaultEntity = entityTypes[0] ?? 'Person'
    onChange([...conditions, ['=', [defaultEntity, '$s', ''], '']])
  }

  return (
    <div className="space-y-2">
      {conditions.length === 0 && (
        <p className="text-xs text-muted-foreground italic">
          Aucune condition — la règle s'applique toujours
        </p>
      )}

      {conditions.map((cond, i) => (
        <ConditionRow
          key={i}
          cond={cond}
          index={i}
          onChange={handleChange}
          onRemove={handleRemove}
          entityTypes={entityTypes}
          attrsByEntity={attrsByEntity}
          attrsLoading={isLoading}
        />
      ))}

      <button
        type="button"
        onClick={handleAdd}
        className="flex items-center gap-1.5 text-xs px-2.5 py-1 rounded border border-dashed border-border text-muted-foreground hover:text-foreground hover:border-foreground transition-colors"
      >
        <Plus size={11} /> Ajouter une condition
      </button>
    </div>
  )
}
