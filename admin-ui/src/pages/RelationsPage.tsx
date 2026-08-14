import { useState } from 'react'
import { AlertTriangle, RefreshCw, RotateCcw, GitBranch } from 'lucide-react'
import toast from 'react-hot-toast'
import { useImportReconciliationSource, useReconcileRelations, useReconciliationReports, useReconciliationSources, useRelationProjectionAudit, useRelationProjectionStatus, useRelationQuarantine, useReplayRelationQuarantine } from '@/api/relations'

export default function RelationsPage() {
  const quarantine = useRelationQuarantine()
  const projectionStatus = useRelationProjectionStatus()
  const replay = useReplayRelationQuarantine()
  const reconcile = useReconcileRelations()
  const reconciliationReports = useReconciliationReports()
  const reconciliationSources = useReconciliationSources()
  const importSource = useImportReconciliationSource()
  const projectionAudit = useRelationProjectionAudit()
  const [snapshotSource, setSnapshotSource] = useState('')
  const [snapshotJson, setSnapshotJson] = useState('[]')

  const replayEntry = (id: string) => {
    replay.mutate(id, {
      onSuccess: (result) => {
        if (result.status === 'quarantined') toast.error('Le message reste invalide et demeure en quarantaine')
        else toast.success('Événement relationnel rejoué')
      },
    })
  }

  const entries = quarantine.data?.entries ?? []
  const runReconciliation = () => {
    try {
      const tuples: unknown = JSON.parse(snapshotJson)
      if (!snapshotSource.trim() || !Array.isArray(tuples)) {
        toast.error('Indiquer une source et un tableau JSON de tuples')
        return
      }
      reconcile.mutate({ source: snapshotSource.trim(), tuples })
    } catch {
      toast.error('Le snapshot doit être un JSON valide')
    }
  }
  return (
    <div className="space-y-6">
      <div className="bg-card border border-border rounded-xl p-5">
        <div className="flex items-start gap-3">
          <div className="rounded-lg bg-autho-blue/10 p-2 text-autho-blue"><GitBranch size={20} /></div>
          <div>
            <h2 className="text-sm font-semibold text-foreground">Projection relationnelle</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              Autho consomme une projection d’autorisation. Les systèmes métier restent propriétaires des relations.
            </p>
          </div>
        </div>
      </div>

      <div className="bg-card border border-border rounded-xl p-5">
        <h2 className="text-sm font-semibold text-foreground">Journal des opérations relationnelles</h2>
        {projectionAudit.isLoading ? <div className="mt-3 h-12 animate-pulse rounded bg-muted" /> : (projectionAudit.data?.events.length ?? 0) === 0 ? <p className="mt-3 text-sm text-muted-foreground">Aucune opération enregistrée.</p> : <div className="mt-3 space-y-2">{projectionAudit.data?.events.slice(0, 10).map((event) => <div key={event.id} className="flex items-center justify-between rounded-lg border border-border px-3 py-2 text-xs"><span className="font-medium">{event.action}</span><span className="text-muted-foreground">{event.source ?? 'source inconnue'}</span><span className="font-mono text-muted-foreground">{event.event_id ?? event.id}</span></div>)}</div>}
      </div>

      <div className="bg-card border border-border rounded-xl p-5">
        <h2 className="text-sm font-semibold text-foreground">Historique des réconciliations</h2>
        {reconciliationReports.isLoading ? <div className="mt-3 h-12 animate-pulse rounded bg-muted" /> : (reconciliationReports.data?.reports.length ?? 0) === 0 ? <p className="mt-3 text-sm text-muted-foreground">Aucune réconciliation enregistrée.</p> : <div className="mt-3 overflow-x-auto"><table className="w-full text-left text-xs"><thead className="text-muted-foreground"><tr><th className="pb-2">Source</th><th className="pb-2">Attendus</th><th className="pb-2">Projetés</th><th className="pb-2">Manquants</th><th className="pb-2">Obsolètes</th></tr></thead><tbody>{reconciliationReports.data?.reports.map((report) => <tr key={report.id} className="border-t border-border"><td className="py-2 font-medium">{report.source}</td><td>{report.expected_count}</td><td>{report.projected_count}</td><td className={report.missing_count ? 'text-amber-600' : ''}>{report.missing_count}</td><td className={report.obsolete_count ? 'text-amber-600' : ''}>{report.obsolete_count}</td></tr>)}</tbody></table></div>}
      </div>

      {projectionStatus.data ? (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <StatusCard label="Consommateur Kafka" value={projectionStatus.data.running ? 'Actif' : 'Arrêté'} healthy={projectionStatus.data.running && projectionStatus.data.healthy} />
          <StatusCard label="Retard maximal" value={`${projectionStatus.data.maxLagRecords} événement(s)`} healthy={projectionStatus.data.maxLagRecords <= projectionStatus.data.thresholds.maxLagRecords} />
          <StatusCard label="Âge de projection" value={projectionStatus.data.projectionAgeMs == null ? 'Aucun événement' : `${Math.round(projectionStatus.data.projectionAgeMs / 1000)} s`} healthy={projectionStatus.data.healthy} />
        </div>
      ) : null}

      <div className="bg-card border border-border rounded-xl p-5">
        <h2 className="text-sm font-semibold text-foreground">Réconciliation avec une source métier</h2>
        <p className="mt-1 text-xs text-muted-foreground">Compare un snapshot sans modifier la projection. Les corrections doivent être publiées par la source.</p>
        <div className="mt-4 grid gap-3">
          <input value={snapshotSource} onChange={(event) => setSnapshotSource(event.target.value)} placeholder="Source (ex. iam, documents)" className="rounded-md border border-input bg-background px-3 py-2 text-sm" />
          <textarea value={snapshotJson} onChange={(event) => setSnapshotJson(event.target.value)} rows={6} spellCheck={false} className="rounded-md border border-input bg-background p-3 font-mono text-xs" aria-label="Snapshot relationnel JSON" />
          <button onClick={runReconciliation} disabled={reconcile.isPending} className="w-fit rounded-md bg-autho-dark px-3 py-2 text-xs text-white disabled:opacity-50">Comparer le snapshot</button>
        </div>
        {reconcile.data ? <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-5"><ReconciliationCard label="Attendus" value={reconcile.data.expectedCount} /><ReconciliationCard label="Projetés" value={reconcile.data.projectedCount} /><ReconciliationCard label="Manquants" value={reconcile.data.missing.length} alert={reconcile.data.missing.length > 0} /><ReconciliationCard label="Obsolètes" value={reconcile.data.obsolete.length} alert={reconcile.data.obsolete.length > 0} /><ReconciliationCard label="Conflits" value={reconcile.data.conflicts.length} alert={reconcile.data.conflicts.length > 0} /></div> : null}
        {(reconciliationSources.data?.sources.length ?? 0) > 0 ? <div className="mt-4 border-t border-border pt-3"><p className="text-xs text-muted-foreground">Imports automatisés configurés</p><div className="mt-2 flex flex-wrap gap-2">{reconciliationSources.data?.sources.map((source) => <button key={source.name} onClick={() => importSource.mutate(source.name, { onSuccess: () => toast.success(`Snapshot ${source.name} importé`) })} disabled={importSource.isPending} className="rounded-md border border-border px-3 py-1.5 text-xs hover:bg-muted disabled:opacity-50">Importer {source.name}</button>)}</div></div> : null}
      </div>

      <div className="bg-card border border-border rounded-xl p-5">
        <div className="mb-4 flex items-center justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-foreground">Quarantaine d’événements</h2>
            <p className="mt-1 text-xs text-muted-foreground">
              Les messages Kafka invalides sont conservés pour analyse et rejeu après correction de leur source.
            </p>
          </div>
          <button
            onClick={() => void quarantine.refetch()}
            disabled={quarantine.isFetching}
            className="flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-xs hover:bg-muted disabled:opacity-50"
          >
            <RefreshCw size={13} className={quarantine.isFetching ? 'animate-spin' : ''} /> Actualiser
          </button>
        </div>

        {quarantine.isLoading ? (
          <div className="space-y-2">{[1, 2].map((key) => <div key={key} className="h-14 animate-pulse rounded bg-muted" />)}</div>
        ) : entries.length === 0 ? (
          <div className="flex items-center gap-2 rounded-lg bg-green-500/10 px-3 py-4 text-sm text-green-700 dark:text-green-400">
            <AlertTriangle size={16} /> Aucune entrée en quarantaine.
          </div>
        ) : (
          <div className="space-y-3">
            <p className="text-xs font-medium text-muted-foreground">{quarantine.data?.count ?? entries.length} message(s) à traiter</p>
            {entries.map((entry) => (
              <div key={entry.id} className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-3">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-mono text-xs text-foreground">{entry.id}</p>
                    <p className="mt-1 text-xs text-amber-700 dark:text-amber-400">{entry.reason ?? 'Message invalide'}</p>
                    <pre className="mt-2 max-h-24 overflow-auto whitespace-pre-wrap rounded bg-muted p-2 text-xs text-muted-foreground">{entry.event_value}</pre>
                  </div>
                  <button
                    onClick={() => replayEntry(entry.id)}
                    disabled={replay.isPending}
                    className="flex shrink-0 items-center gap-1.5 rounded-md bg-autho-dark px-3 py-1.5 text-xs text-white hover:bg-autho-dark/90 disabled:opacity-50"
                  >
                    <RotateCcw size={13} /> Rejouer
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

function StatusCard({ label, value, healthy }: { label: string; value: string; healthy: boolean }) {
  return <div className="rounded-xl border border-border bg-card p-4"><p className="text-xs text-muted-foreground">{label}</p><p className={`mt-1 text-lg font-semibold ${healthy ? 'text-green-600' : 'text-amber-600'}`}>{value}</p></div>
}

function ReconciliationCard({ label, value, alert = false }: { label: string; value: number; alert?: boolean }) {
  return <div className="rounded-lg bg-muted p-3"><p className="text-xs text-muted-foreground">{label}</p><p className={`mt-1 text-lg font-semibold ${alert ? 'text-amber-600' : ''}`}>{value}</p></div>
}
