import { useState } from 'react'
import {
  useReactTable, getCoreRowModel, getSortedRowModel, flexRender,
  createColumnHelper, type SortingState,
} from '@tanstack/react-table'
import { Search, Download, ShieldCheck, ShieldAlert, ChevronLeft, ChevronRight, ChevronsUpDown } from 'lucide-react'
import { useAudit, useVerifyChain } from '@/api/audit'
import { exportToCsv, formatDate } from '@/lib/utils'
import type { AuditEntry, AuditSearchParams } from '@/types/audit'
import toast from 'react-hot-toast'

// ─── Search form ──────────────────────────────────────────────────────────────

function SearchForm({ onSearch }: { onSearch: (p: AuditSearchParams) => void }) {
  const [subjectId, setSubjectId] = useState('')
  const [resourceClass, setResourceClass] = useState('')
  const [decision, setDecision] = useState<'' | 'allow' | 'deny'>('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    onSearch({
      subjectId: subjectId || undefined,
      resourceClass: resourceClass || undefined,
      decision: decision || undefined,
      from: from || undefined,
      to: to || undefined,
      page: 1,
    })
  }

  return (
    <form onSubmit={submit} className="bg-card border border-border rounded-xl p-4">
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
        <input value={subjectId} onChange={(e) => setSubjectId(e.target.value)} placeholder="Sujet"
          className="px-3 py-1.5 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-1 focus:ring-ring" />
        <input value={resourceClass} onChange={(e) => setResourceClass(e.target.value)} placeholder="Classe ressource"
          className="px-3 py-1.5 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-1 focus:ring-ring" />
        <select value={decision} onChange={(e) => setDecision(e.target.value as '' | 'allow' | 'deny')}
          className="px-3 py-1.5 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-1 focus:ring-ring">
          <option value="">Toutes décisions</option>
          <option value="allow">Autorisé</option>
          <option value="deny">Refusé</option>
        </select>
        <input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)}
          className="px-3 py-1.5 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-1 focus:ring-ring" />
        <input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)}
          className="px-3 py-1.5 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-1 focus:ring-ring" />
      </div>
      <div className="flex items-center justify-end mt-3">
        <button type="submit"
          className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-md bg-autho-dark text-white hover:bg-autho-dark/90 transition-colors">
          <Search size={12} /> Rechercher
        </button>
      </div>
    </form>
  )
}

// ─── Chain integrity verifier ─────────────────────────────────────────────────

function ChainVerifier() {
  const verify = useVerifyChain()
  return (
    <button onClick={() => verify.mutate(undefined, {
      onSuccess: (data) => {
        if (data.valid) toast.success('Chaîne d\'audit intègre ✓')
        else toast.error(`Chaîne corrompue à l'entrée #${data['broken-at'] ?? '?'}: ${data.reason ?? ''}`)
      },
    })}
      disabled={verify.isPending}
      className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-md border border-input hover:bg-muted transition-colors disabled:opacity-50">
      {verify.isPending ? <span className="text-xs">⟳</span>
        : verify.data
          ? (verify.data.valid
              ? <ShieldCheck size={13} className="text-green-500" />
              : <ShieldAlert size={13} className="text-destructive" />)
          : <ShieldCheck size={13} />}
      Vérifier l'intégrité
    </button>
  )
}

// ─── Table ────────────────────────────────────────────────────────────────────

const col = createColumnHelper<AuditEntry>()

const columns = [
  col.accessor('ts', {
    header: 'Horodatage',
    cell: (i) => <span className="text-xs font-mono whitespace-nowrap">{formatDate(i.getValue())}</span>,
    size: 160,
  }),
  col.accessor('subject_id', { header: 'Sujet', cell: (i) => <span className="text-xs truncate block max-w-[120px]">{i.getValue()}</span>, size: 130 }),
  col.accessor('resource_class', { header: 'Classe', cell: (i) => <span className="text-xs">{i.getValue()}</span>, size: 110 }),
  col.accessor('resource_id', { header: 'Ressource', cell: (i) => <span className="text-xs text-muted-foreground">{i.getValue() ?? '—'}</span>, size: 110 }),
  col.accessor('operation', { header: 'Opération', cell: (i) => <span className="text-xs font-mono">{i.getValue()}</span>, size: 100 }),
  col.accessor('decision', {
    header: 'Décision',
    cell: (i) => (
      <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${i.getValue() === 'allow' ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400' : 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400'}`}>
        {i.getValue() === 'allow' ? 'Autorisé' : 'Refusé'}
      </span>
    ),
    size: 90,
  }),
  col.accessor('matched_rules', {
    header: 'Règles',
    cell: (i) => <span className="text-xs text-muted-foreground">{i.getValue()?.join(', ') || '—'}</span>,
    size: 150,
  }),
]

function AuditTable({ items }: { items: AuditEntry[] }) {
  const [sorting, setSorting] = useState<SortingState>([])
  const table = useReactTable({
    data: items, columns,
    state: { sorting }, onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(), getSortedRowModel: getSortedRowModel(),
  })

  return (
    <div className="overflow-x-auto rounded-xl border border-border bg-card">
      <table className="w-full text-left">
        <thead className="border-b border-border bg-muted/30">
          {table.getHeaderGroups().map((hg) => (
            <tr key={hg.id}>
              {hg.headers.map((h) => (
                <th key={h.id} style={{ width: h.getSize() }}
                  className="px-3 py-2 text-xs font-semibold text-muted-foreground uppercase tracking-wide cursor-pointer select-none"
                  onClick={h.column.getToggleSortingHandler()}>
                  <div className="flex items-center gap-1">
                    {flexRender(h.column.columnDef.header, h.getContext())}
                    {h.column.getCanSort() && <ChevronsUpDown size={10} className="opacity-40" />}
                  </div>
                </th>
              ))}
            </tr>
          ))}
        </thead>
        <tbody className="divide-y divide-border">
          {table.getRowModel().rows.length === 0 ? (
            <tr><td colSpan={columns.length} className="px-3 py-8 text-center text-sm text-muted-foreground">Aucune entrée d'audit</td></tr>
          ) : (
            table.getRowModel().rows.map((row) => (
              <tr key={row.id} className="hover:bg-muted/30 transition-colors">
                {row.getVisibleCells().map((cell) => (
                  <td key={cell.id} className="px-3 py-2">
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function AuditPage() {
  const [params, setParams] = useState<AuditSearchParams>({ page: 1, pageSize: 20 })
  const { data, isLoading, isFetching } = useAudit(params)

  const handleExport = () => {
    if (!data?.items.length) { toast.error('Aucune donnée à exporter'); return }
    const headers = ['ts', 'subject_id', 'resource_class', 'resource_id', 'operation', 'decision', 'matched_rules']
    const rows = data.items.map((e) => [
      e.ts, e.subject_id, e.resource_class, e.resource_id ?? '', e.operation, e.decision, e.matched_rules?.join(';') ?? '',
    ])
    exportToCsv('audit', headers, rows)
    toast.success('Export CSV téléchargé')
  }

  return (
    <div className="space-y-4">
      <SearchForm onSearch={(p) => setParams({ ...p, pageSize: params.pageSize })} />

      <div className="flex items-center justify-between">
        <ChainVerifier />
        <button onClick={handleExport} disabled={!data?.items.length}
          className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-md border border-input hover:bg-muted transition-colors disabled:opacity-50">
          <Download size={12} /> Exporter CSV
        </button>
      </div>

      {isLoading ? (
        <div className="space-y-2">
          {Array.from({ length: 5 }).map((_, i) => <div key={i} className="h-10 bg-muted rounded animate-pulse" />)}
        </div>
      ) : (
        <div className={isFetching ? 'opacity-70 transition-opacity' : ''}>
          <AuditTable items={data?.items ?? []} />
        </div>
      )}

      {data && (
        <div className="flex items-center justify-between text-xs text-muted-foreground">
          <span>{data.total} entrée{data.total !== 1 ? 's' : ''}</span>
          <div className="flex items-center gap-2">
            <button onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 1) - 1 }))} disabled={(params.page ?? 1) <= 1}
              className="p-1 rounded hover:bg-muted disabled:opacity-40 transition-colors">
              <ChevronLeft size={14} />
            </button>
            <span>Page {data.page} / {Math.max(1, Math.ceil(data.total / data.pageSize))}</span>
            <button onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 1) + 1 }))}
              disabled={(params.page ?? 1) >= Math.ceil(data.total / data.pageSize)}
              className="p-1 rounded hover:bg-muted disabled:opacity-40 transition-colors">
              <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
