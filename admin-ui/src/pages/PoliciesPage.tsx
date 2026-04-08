import { useState, useCallback, useRef } from 'react'
import Editor, { DiffEditor } from '@monaco-editor/react'
import {
  Plus, Trash2, Upload, History, GitCompare, RotateCcw, Save, X, ChevronDown, ShieldAlert,
} from 'lucide-react'
import { Navigate, Route, Routes, useNavigate, useParams } from 'react-router-dom'
import { PolicyEditorTabs } from '@/components/policies/PolicyEditorTabs'
import {
  usePolicies, usePolicy, useSubmitPolicy, useDeletePolicy,
  useImportYaml, useVersions, useVersion, useDiffVersions, useRollback,
} from '@/api/policies'
import { getDarkMode } from '@/lib/auth'
import { normalizePolicyForSave } from '@/lib/policyDocument'
import PolicyGovernancePage from '@/pages/PolicyGovernancePage'
import toast from 'react-hot-toast'
import type { PolicyVersion } from '@/types/policy'

function useEditorTheme() {
  return getDarkMode() ? 'vs-dark' : 'light'
}

function CreatePolicyDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (rc: string) => void }) {
  const [name, setName] = useState('')
  const submit = useSubmitPolicy()

  const handleCreate = () => {
    const rc = name.trim()
    if (!rc) { toast.error('Nom requis'); return }
    submit.mutate(
      { resourceClass: rc, policy: { resourceClass: rc, strategy: 'deny-unless-permit', rules: [] } },
      { onSuccess: () => { onCreated(rc); onClose() } },
    )
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-card border border-border rounded-xl p-5 w-80 shadow-xl">
        <h3 className="text-sm font-semibold mb-3">Nouvelle politique</h3>
        <input
          autoFocus type="text" value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
          placeholder="Classe de ressource (ex: Document)"
          className="w-full px-3 py-2 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-1 focus:ring-ring mb-3"
        />
        <div className="flex justify-end gap-2">
          <button onClick={onClose} className="px-3 py-1.5 text-sm rounded-md border border-input hover:bg-muted transition-colors">Annuler</button>
          <button onClick={handleCreate} disabled={submit.isPending} className="px-3 py-1.5 text-sm rounded-md bg-autho-dark text-white hover:bg-autho-dark/90 transition-colors disabled:opacity-50">Creer</button>
        </div>
      </div>
    </div>
  )
}

function YamlImportModal({ onClose }: { onClose: () => void }) {
  const [yaml, setYaml] = useState('')
  const [dragging, setDragging] = useState(false)
  const importYaml = useImportYaml()
  const theme = useEditorTheme()

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    setDragging(false)
    const file = e.dataTransfer.files[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = (ev) => setYaml((ev.target?.result as string) ?? '')
    reader.readAsText(file)
  }, [])

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <div className="bg-card border border-border rounded-xl shadow-xl w-full max-w-2xl flex flex-col gap-3 p-5">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold">Importer YAML</h3>
          <button onClick={onClose} className="p-1 rounded hover:bg-muted"><X size={14} /></button>
        </div>
        <div
          onDrop={handleDrop}
          onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
          onDragLeave={() => setDragging(false)}
          className={`border-2 border-dashed rounded-lg transition-colors ${dragging ? 'border-autho-dark bg-autho-dark/5' : 'border-border'}`}
        >
          {yaml ? (
            <Editor height="320px" language="yaml" value={yaml} onChange={(v) => setYaml(v ?? '')} theme={theme}
              options={{ minimap: { enabled: false }, fontSize: 12, lineNumbers: 'on' }} />
          ) : (
            <div className="flex flex-col items-center justify-center py-12 text-muted-foreground text-sm gap-2">
              <Upload size={24} />
              <span>Glissez un fichier .yaml ou collez du YAML ici</span>
              <button onClick={() => setYaml('# Collez votre YAML ici\n')} className="text-xs underline">Editer manuellement</button>
            </div>
          )}
        </div>
        <div className="flex justify-end gap-2">
          <button onClick={onClose} className="px-3 py-1.5 text-sm rounded-md border border-input hover:bg-muted transition-colors">Annuler</button>
          <button onClick={() => { if (!yaml.trim()) { toast.error('Contenu YAML vide'); return }; importYaml.mutate(yaml, { onSuccess: onClose }) }}
            disabled={importYaml.isPending || !yaml.trim()}
            className="px-3 py-1.5 text-sm rounded-md bg-autho-dark text-white hover:bg-autho-dark/90 transition-colors disabled:opacity-50">
            Importer
          </button>
        </div>
      </div>
    </div>
  )
}

function VersionHistory({ resourceClass, onDiff, onClose }: {
  resourceClass: string
  onDiff: (from: number, to: number) => void
  onClose: () => void
}) {
  const { data: versions, isLoading } = useVersions(resourceClass)
  const rollback = useRollback()
  const [sel, setSel] = useState<Set<number>>(new Set())

  const toggle = (v: number) => setSel((prev) => {
    const next = new Set(prev)
    if (next.has(v)) { next.delete(v); return next }
    if (next.size < 2) { next.add(v); return next }
    return new Set([v])
  })

  const selArr = Array.from(sel).sort((a, b) => a - b)
  const [fromV, toV] = selArr

  return (
    <div className="flex flex-col h-full border-l border-border bg-card">
      <div className="flex items-center justify-between px-3 py-2 border-b border-border">
        <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">Historique</span>
        <button onClick={onClose} className="p-1 rounded hover:bg-muted"><X size={14} /></button>
      </div>
      {sel.size === 2 && (
        <div className="px-3 py-2 bg-muted/40 border-b border-border flex items-center justify-between">
          <span className="text-xs text-muted-foreground">Comparer v{fromV} vers v{toV}</span>
          <button onClick={() => onDiff(fromV, toV)} className="flex items-center gap-1 text-xs px-2 py-1 rounded bg-autho-dark text-white hover:bg-autho-dark/90">
            <GitCompare size={11} /> Diff
          </button>
        </div>
      )}
      <div className="flex-1 overflow-y-auto divide-y divide-border">
        {isLoading ? Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="h-14 mx-2 my-1 bg-muted rounded animate-pulse" />
        )) : !versions?.length ? (
          <p className="text-xs text-muted-foreground text-center py-6">Aucune version enregistree</p>
        ) : (
          versions.map((v: PolicyVersion) => (
            <div key={v.version} onClick={() => toggle(v.version)}
              className={`group px-3 py-2 cursor-pointer transition-colors ${sel.has(v.version) ? 'bg-autho-dark/10' : 'hover:bg-muted'}`}>
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-foreground">v{v.version}</span>
                <button onClick={(e) => { e.stopPropagation(); rollback.mutate({ resourceClass, version: v.version }) }}
                  title="Rollback" className="p-1 rounded hover:bg-destructive/10 hover:text-destructive opacity-0 group-hover:opacity-100">
                  <RotateCcw size={11} />
                </button>
              </div>
              {v.author && <p className="text-xs text-muted-foreground truncate">{v.author}</p>}
              {v.comment && <p className="text-xs text-muted-foreground truncate italic">{v.comment}</p>}
              <p className="text-xs text-muted-foreground">{new Date(v.createdAt).toLocaleString('fr-FR')}</p>
            </div>
          ))
        )}
      </div>
    </div>
  )
}

function DiffView({ resourceClass, fromV, toV, onClose }: { resourceClass: string; fromV: number; toV: number; onClose: () => void }) {
  const { data: original } = useVersion(resourceClass, fromV)
  const { data: modified } = useVersion(resourceClass, toV)
  void useDiffVersions(resourceClass, fromV, toV)
  const theme = useEditorTheme()

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <div className="bg-card border border-border rounded-xl shadow-xl w-full max-w-5xl flex flex-col overflow-hidden" style={{ height: '80vh' }}>
        <div className="flex items-center justify-between px-4 py-2 border-b border-border bg-muted/40">
          <span className="text-sm font-semibold">{resourceClass} - v{fromV} vers v{toV}</span>
          <button onClick={onClose} className="p-1 rounded hover:bg-muted"><X size={14} /></button>
        </div>
        <div className="flex-1 min-h-0">
          <DiffEditor height="100%" language="json"
            original={original ? JSON.stringify(original, null, 2) : ''}
            modified={modified ? JSON.stringify(modified, null, 2) : ''}
            theme={theme}
            options={{ readOnly: true, minimap: { enabled: false }, fontSize: 12, renderSideBySide: true }} />
        </div>
      </div>
    </div>
  )
}

function StrategySelector({ resourceClass }: { resourceClass: string }) {
  const { data } = usePolicy(resourceClass)
  const submit = useSubmitPolicy()
  const [open, setOpen] = useState(false)
  const strategies = ['deny-unless-permit', 'permit-unless-deny', 'first-applicable', 'only-one-applicable']
  const current = (normalizePolicyForSave(data, resourceClass).strategy ?? 'deny-unless-permit') as string

  return (
    <div className="relative">
      <button onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1 text-xs px-2 py-1 rounded border border-input bg-background hover:bg-muted transition-colors">
        <span className="font-mono">{current}</span>
        <ChevronDown size={11} />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className="absolute top-full left-0 mt-1 w-52 bg-popover border border-border rounded-md shadow-lg z-20">
            {strategies.map((s) => (
              <button key={s} onClick={() => { submit.mutate({ resourceClass, policy: { ...normalizePolicyForSave(data, resourceClass), strategy: s } }); setOpen(false) }}
                className={`w-full text-left px-3 py-2 text-xs hover:bg-muted transition-colors ${s === current ? 'text-autho-dark font-semibold' : 'text-foreground'}`}>
                {s}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

function PolicyEditor({ resourceClass }: { resourceClass: string }) {
  const navigate = useNavigate()
  const { data } = usePolicy(resourceClass)
  const submit = useSubmitPolicy()
  const deletePolicy = useDeletePolicy()
  const [editorValue, setEditorValue] = useState<string | null>(null)
  const [showHistory, setShowHistory] = useState(false)
  const [diffParams, setDiffParams] = useState<{ from: number; to: number } | null>(null)
  const savedRef = useRef('')

  const policyJson = data ? JSON.stringify(data, null, 2) : ''
  if (!editorValue && policyJson !== savedRef.current) savedRef.current = policyJson

  const current = editorValue ?? policyJson
  const isDirty = !!editorValue && editorValue !== policyJson

  const handleSave = () => {
    try {
      const parsed: unknown = JSON.parse(current)
      submit.mutate(
        { resourceClass, policy: normalizePolicyForSave(parsed, resourceClass) },
        { onSuccess: () => setEditorValue(null) },
      )
    } catch {
      toast.error('JSON invalide - verifiez la syntaxe')
    }
  }

  return (
    <div className="flex h-full">
      <div className="flex-1 flex flex-col min-w-0">
        <div className="flex items-center justify-between px-3 py-2 border-b border-border bg-muted/30">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold text-foreground">{resourceClass}</span>
            {isDirty && <span className="text-xs text-amber-500 font-medium">• non sauvegarde</span>}
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => navigate(`/policies/${resourceClass}/governance`)}
              className="flex items-center gap-1 text-xs px-2.5 py-1.5 rounded border border-input text-muted-foreground hover:bg-muted transition-colors"
            >
              <ShieldAlert size={12} /> Gouvernance
            </button>
            <button onClick={() => setShowHistory((s) => !s)}
              className={`flex items-center gap-1 text-xs px-2.5 py-1.5 rounded border transition-colors ${showHistory ? 'border-autho-dark text-autho-dark bg-autho-dark/10' : 'border-input text-muted-foreground hover:bg-muted'}`}>
              <History size={12} /> Historique
            </button>
            <button onClick={() => { if (!confirm(`Supprimer "${resourceClass}" ?`)) return; deletePolicy.mutate(resourceClass) }}
              className="flex items-center gap-1 text-xs px-2.5 py-1.5 rounded border border-destructive text-destructive hover:bg-destructive/10 transition-colors">
              <Trash2 size={12} /> Supprimer
            </button>
            <button onClick={handleSave} disabled={submit.isPending || !isDirty}
              className="flex items-center gap-1 text-xs px-2.5 py-1.5 rounded bg-autho-dark text-white hover:bg-autho-dark/90 transition-colors disabled:opacity-50">
              <Save size={12} /> {submit.isPending ? 'Sauvegarde...' : 'Sauvegarder'}
            </button>
          </div>
        </div>

        <div className="flex-1 min-h-0">
          <PolicyEditorTabs
            resourceClass={resourceClass}
            jsonValue={current}
            onJsonChange={setEditorValue}
          />
        </div>
      </div>

      {showHistory && (
        <div className="w-72 flex-shrink-0">
          <VersionHistory resourceClass={resourceClass} onDiff={(f, t) => setDiffParams({ from: f, to: t })} onClose={() => setShowHistory(false)} />
        </div>
      )}
      {diffParams && (
        <DiffView resourceClass={resourceClass} fromV={diffParams.from} toV={diffParams.to} onClose={() => setDiffParams(null)} />
      )}
    </div>
  )
}

function PoliciesIndex({ onCreate, onImport }: { onCreate: () => void; onImport: () => void }) {
  const { data } = usePolicies()
  const classes = data ? Object.keys(data) : []

  if (classes.length > 0) {
    return <Navigate to={`/policies/${classes[0]}`} replace />
  }

  return (
    <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
      <p className="text-sm font-medium text-foreground">Selectionnez une politique</p>
      <div className="flex gap-2">
        <button onClick={onCreate}
          className="flex items-center gap-1.5 text-xs px-3 py-2 rounded-md bg-autho-dark text-white hover:bg-autho-dark/90 transition-colors">
          <Plus size={13} /> Nouvelle politique
        </button>
        <button onClick={onImport}
          className="flex items-center gap-1.5 text-xs px-3 py-2 rounded-md border border-input hover:bg-muted transition-colors">
          <Upload size={13} /> Importer YAML
        </button>
      </div>
    </div>
  )
}

function PoliciesContent() {
  const navigate = useNavigate()
  const { resourceClass } = useParams()
  const [showCreate, setShowCreate] = useState(false)
  const [showImport, setShowImport] = useState(false)

  return (
    <>
      <div className="h-[calc(100vh-3.5rem)] flex border border-border rounded-xl overflow-hidden bg-card">
        <div className="flex-1 flex flex-col min-w-0">
          <div className="flex items-center justify-between gap-3 px-4 py-2 border-b border-border bg-muted/20">
            <div className="flex items-center gap-2 min-w-0">
              {resourceClass ? (
                <>
                  <span className="text-xs text-muted-foreground">Strategie :</span>
                  <StrategySelector resourceClass={resourceClass} />
                </>
              ) : (
                <span className="text-sm font-medium text-muted-foreground">Gestion des politiques</span>
              )}
            </div>
            <div className="flex items-center gap-2">
              <button onClick={() => setShowImport(true)}
                className="inline-flex items-center gap-1.5 rounded-md border border-input bg-background px-3 py-1.5 text-xs text-muted-foreground hover:bg-muted transition-colors">
                <Upload size={12} /> Importer YAML
              </button>
              <button onClick={() => setShowCreate(true)}
                className="inline-flex items-center gap-1.5 rounded-md bg-autho-dark px-3 py-1.5 text-xs text-white hover:bg-autho-dark/90 transition-colors">
                <Plus size={12} /> Nouvelle politique
              </button>
            </div>
          </div>

          <div className="flex-1 min-h-0">
            {resourceClass ? (
              <Routes>
                <Route index element={<PolicyEditor resourceClass={resourceClass} />} />
                <Route path="governance" element={<PolicyGovernancePage />} />
              </Routes>
            ) : (
              <PoliciesIndex onCreate={() => setShowCreate(true)} onImport={() => setShowImport(true)} />
            )}
          </div>
        </div>
      </div>
      {showCreate && <CreatePolicyDialog onClose={() => setShowCreate(false)} onCreated={(rc) => navigate(`/policies/${rc}`)} />}
      {showImport && <YamlImportModal onClose={() => setShowImport(false)} />}
    </>
  )
}

export default function PoliciesPage() {
  return (
    <Routes>
      <Route index element={<PoliciesContent />} />
      <Route path=":resourceClass/*" element={<PoliciesContent />} />
    </Routes>
  )
}
