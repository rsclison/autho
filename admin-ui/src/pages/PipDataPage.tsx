import { useMemo, useState } from 'react'
import { Database, RefreshCw } from 'lucide-react'
import { useResourceClasses, useResourcesByClass } from '@/api/resources'
import type { PipResource } from '@/types/resource'

function formatCount(value?: number) {
  if (value === undefined || value === null) return '...'
  return new Intl.NumberFormat('fr-FR').format(value)
}

export default function PipDataPage() {
  const classes = useResourceClasses()
  const [selectedClass, setSelectedClass] = useState<string | null>(null)
  const effectiveClass = selectedClass ?? classes.data?.[0]?.class ?? null
  const resources = useResourcesByClass(effectiveClass)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const selectedResource = useMemo<PipResource | null>(() => {
    const items = resources.data ?? []
    return items.find((item) => item.id === selectedId) ?? items[0] ?? null
  }, [resources.data, selectedId])

  const refresh = () => {
    void classes.refetch()
    void resources.refetch()
  }

  const classItems = classes.data ?? []
  const resourceItems = resources.data ?? []

  return (
    <div className="grid min-h-[42rem] grid-cols-1 gap-5 lg:grid-cols-[16rem_minmax(22rem,1fr)_minmax(20rem,28rem)]">
      <section className="app-surface min-h-0 overflow-hidden">
        <div className="flex items-center justify-between border-b border-border px-5 py-4">
          <div className="flex items-center gap-2">
            <Database size={15} />
            <h2 className="section-title">Classes RocksDB</h2>
          </div>
          <button
            onClick={refresh}
            className="rounded-md p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
            title="Rafraichir"
          >
            <RefreshCw size={13} />
          </button>
        </div>
        <div className="max-h-full overflow-y-auto p-2">
          {classes.isLoading ? (
            <div className="space-y-2">
              {Array.from({ length: 3 }).map((_, i) => (
                <div key={i} className="h-10 rounded-md bg-muted animate-pulse" />
              ))}
            </div>
          ) : classItems.length === 0 ? (
            <p className="px-3 py-6 text-center text-sm text-muted-foreground">
              Aucune classe RocksDB disponible
            </p>
          ) : (
            classItems.map((item) => {
              const active = item.class === effectiveClass
              return (
                <button
                  key={item.class}
                  onClick={() => {
                    setSelectedClass(item.class)
                    setSelectedId(null)
                  }}
                  className={`mb-1 w-full rounded-md px-3 py-2 text-left transition-colors ${
                    active ? 'bg-autho-dark text-white' : 'hover:bg-muted'
                  }`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-sm font-medium">{item.class}</span>
                    <span className={`text-xs ${active ? 'text-white/70' : 'text-muted-foreground'}`}>
                      {formatCount(item.count)}
                    </span>
                  </div>
                </button>
              )
            })
          )}
        </div>
      </section>

      <section className="app-surface min-h-0 overflow-hidden">
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold text-foreground">{effectiveClass ?? 'Ressources'}</h2>
            <p className="text-xs text-muted-foreground">Objets actuellement presents dans RocksDB</p>
          </div>
          <button
            onClick={() => void resources.refetch()}
            disabled={!effectiveClass || resources.isFetching}
            className="flex items-center gap-1.5 rounded-md border border-input px-2.5 py-1.5 text-xs hover:bg-muted disabled:opacity-50"
          >
            <RefreshCw size={12} /> Rafraichir
          </button>
        </div>
        <div className="min-h-0 overflow-auto">
          {resources.isLoading ? (
            <div className="space-y-2 p-3">
              {Array.from({ length: 5 }).map((_, i) => (
                <div key={i} className="h-11 rounded-md bg-muted animate-pulse" />
              ))}
            </div>
          ) : !effectiveClass ? (
            <p className="px-4 py-8 text-center text-sm text-muted-foreground">
              Selectionnez une classe de ressource
            </p>
          ) : resourceItems.length === 0 ? (
            <div className="px-4 py-10 text-center">
              <p className="text-sm font-medium text-foreground">Aucun objet</p>
              <p className="mt-1 text-xs text-muted-foreground">
                Lancez `./demo_inject_kafka.sh` pour alimenter Kafka puis RocksDB.
              </p>
            </div>
          ) : (
            <table className="w-full text-left">
              <thead className="sticky top-0 border-b border-border bg-muted/40">
                <tr>
                  <th className="px-3 py-2 text-xs font-semibold uppercase text-muted-foreground">ID</th>
                  <th className="px-3 py-2 text-xs font-semibold uppercase text-muted-foreground">Attributs</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {resourceItems.map((resource) => (
                  <tr
                    key={resource.id}
                    onClick={() => setSelectedId(resource.id)}
                    className={`cursor-pointer transition-colors ${
                      selectedResource?.id === resource.id ? 'bg-autho-dark/10' : 'hover:bg-muted/50'
                    }`}
                  >
                    <td className="px-3 py-2 font-mono text-xs text-foreground">{resource.id}</td>
                    <td className="px-3 py-2 text-xs text-muted-foreground">
                      {Object.keys(resource.attributes ?? {}).slice(0, 4).join(', ') || '-'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>

      <section className="app-surface min-h-0 overflow-hidden">
        <div className="border-b border-border px-4 py-3">
          <h2 className="text-sm font-semibold text-foreground">Detail objet</h2>
          <p className="text-xs text-muted-foreground">
            JSON stocke pour l'objet selectionne
          </p>
        </div>
        <div className="h-[calc(100%-4rem)] overflow-auto p-3">
          {selectedResource ? (
            <pre className="min-h-full rounded-lg bg-muted p-3 text-xs leading-5 text-foreground">
              {JSON.stringify(selectedResource, null, 2)}
            </pre>
          ) : (
            <p className="px-4 py-8 text-center text-sm text-muted-foreground">
              Aucun objet selectionne
            </p>
          )}
        </div>
      </section>
    </div>
  )
}
