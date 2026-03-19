import { useState } from 'react'
import { FileX, Plus, GripVertical } from 'lucide-react'
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
  arrayMove,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { RuleCard } from './RuleCard'
import { RuleForm } from './RuleForm'
import type { Rule } from '@/types/policy'

// ─── SortableRuleCard ─────────────────────────────────────────────────────────

interface SortableProps {
  id: string
  rule: Rule
  index: number
  onEdit?: (updated: Rule) => void
  onDelete?: () => void
  disabled?: boolean
}

function SortableRuleCard({ id, rule, index, onEdit, onDelete, disabled }: SortableProps) {
  const {
    attributes,
    listeners,
    setNodeRef,
    setActivatorNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id, disabled })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.4 : 1,
    zIndex: isDragging ? 50 : undefined,
  }

  return (
    <div ref={setNodeRef} style={style} className="flex items-start gap-1.5">
      {/* Drag handle */}
      {!disabled && (
        <button
          ref={setActivatorNodeRef}
          {...attributes}
          {...listeners}
          className="mt-3 p-0.5 text-muted-foreground/40 hover:text-muted-foreground cursor-grab active:cursor-grabbing transition-colors shrink-0 touch-none"
          tabIndex={-1}
          aria-label="Réordonner"
        >
          <GripVertical size={14} />
        </button>
      )}

      <div className="flex-1 min-w-0">
        <RuleCard
          rule={rule}
          index={index}
          onEdit={onEdit}
          onDelete={onDelete}
        />
      </div>
    </div>
  )
}

// ─── RuleList ─────────────────────────────────────────────────────────────────

interface Props {
  rules: Rule[]
  operation?: string | null
  onEdit?: (index: number, updated: Rule) => void
  onDelete?: (index: number) => void
  onAdd?: (rule: Rule) => void
  onReorder?: (rules: Rule[]) => void
}

export function RuleList({ rules, operation, onEdit, onDelete, onAdd, onReorder }: Props) {
  const [adding, setAdding] = useState(false)

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const filtered = operation
    ? rules.filter((r) => !r.operation || r.operation === operation)
    : rules

  // DnD only works on the unfiltered full list to avoid index confusion
  const dndEnabled = !operation && !!onReorder

  function originalIndex(rule: Rule): number {
    return rules.indexOf(rule)
  }

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event
    if (!over || active.id === over.id) return

    const oldIdx = rules.findIndex((r) => ruleId(r) === active.id)
    const newIdx = rules.findIndex((r) => ruleId(r) === over.id)
    if (oldIdx === -1 || newIdx === -1) return

    onReorder!(arrayMove(rules, oldIdx, newIdx))
  }

  const ids = rules.map(ruleId)

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragEnd={handleDragEnd}
    >
      <SortableContext items={ids} strategy={verticalListSortingStrategy}>
        <div className="space-y-2">
          {filtered.length === 0 && !adding && (
            <div className="flex flex-col items-center justify-center py-12 text-muted-foreground gap-2">
              <FileX size={20} />
              <p className="text-sm">Aucune règle définie</p>
            </div>
          )}

          {filtered.map((rule, i) => {
            const origIdx = originalIndex(rule)
            return (
              <SortableRuleCard
                key={ruleId(rule)}
                id={ruleId(rule)}
                rule={rule}
                index={i}
                disabled={!dndEnabled}
                onEdit={onEdit ? (updated) => onEdit(origIdx, updated) : undefined}
                onDelete={onDelete ? () => onDelete(origIdx) : undefined}
              />
            )
          })}

          {/* New rule form */}
          {adding && onAdd && (
            <div className="rounded-lg border border-dashed border-border p-3">
              <RuleForm
                onSave={(rule) => { onAdd(rule); setAdding(false) }}
                onCancel={() => setAdding(false)}
              />
            </div>
          )}

          {/* Add button */}
          {onAdd && !adding && (
            <button
              onClick={() => setAdding(true)}
              className="w-full flex items-center justify-center gap-2 text-xs px-3 py-2 rounded-lg border border-dashed border-border text-muted-foreground hover:text-foreground hover:border-foreground transition-colors"
            >
              <Plus size={13} />
              Ajouter une règle
            </button>
          )}
        </div>
      </SortableContext>
    </DndContext>
  )
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function ruleId(rule: Rule): string {
  // Stable ID: prefer name, fall back to JSON fingerprint
  return rule.name || JSON.stringify([rule.effect, rule.operation, rule.priority])
}
