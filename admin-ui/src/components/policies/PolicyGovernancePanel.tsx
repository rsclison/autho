import { useMemo, useState } from 'react'
import { ShieldAlert, Clock3, Rocket, Eye, CheckCircle2, History, Filter, Sparkles } from 'lucide-react'
import toast from 'react-hot-toast'
import {
  useAnalyzePolicyImpact,
  usePolicyImpactHistory,
  usePolicyTimeline,
  useReviewPolicyImpact,
  useRolloutPolicyImpact,
  useVersions,
} from '@/api/policies'
import type { PolicyImpactHistoryEntry, PolicyTimelineEvent } from '@/types/policy'

interface Props {
  resourceClass: string
}

type TimelinePreset = 'all' | 'review' | 'deploy'

const DEFAULT_REQUESTS = JSON.stringify([
  {
    subject: { id: 'alice', role: 'manager' },
    resource: { class: 'Document', id: 'doc-1' },
    operation: 'read',
    context: {},
  },
], null, 2)

function toneForStatus(entry: PolicyImpactHistoryEntry) {
  if (entry.rolloutStatus === 'deployed') return 'border-green-500/30 bg-green-500/5'
  if (entry.reviewStatus === 'approved') return 'border-blue-500/30 bg-blue-500/5'
  if (entry.reviewStatus === 'rejected') return 'border-red-500/30 bg-red-500/5'
  return 'border-border bg-card'
}

function formatDate(value?: string | null) {
  if (!value) return 'n/a'
  return new Date(value).toLocaleString('fr-FR')
}

function getEventType(event: PolicyTimelineEvent) {
  return event.eventType ?? event.type ?? 'unknown'
}

function getEventActor(event: PolicyTimelineEvent) {
  return event.actor ?? event.author ?? event.reviewedBy ?? event.deployedBy ?? null
}

function eventLabel(event: PolicyTimelineEvent) {
  switch (getEventType(event)) {
    case 'impact_analysis_created':
      return 'Preview creee'
    case 'impact_reviewed':
      return 'Review mise a jour'
    case 'impact_deployed':
      return 'Preview deployee'
    case 'policy_version_created':
      return 'Version creee'
    default:
      return getEventType(event)
  }
}

function SectionCard({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <section className="rounded-2xl border border-border/80 bg-card shadow-sm">
      <div className="border-b border-border/70 px-5 py-4">
        <p className="text-[11px] font-semibold uppercase tracking-[0.22em] text-muted-foreground">{title}</p>
        {subtitle ? <p className="mt-1.5 max-w-2xl text-sm leading-6 text-muted-foreground">{subtitle}</p> : null}
      </div>
      <div className="p-5">{children}</div>
    </section>
  )
}

function MetricCard({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-xl border border-border/70 bg-card px-4 py-4">
      <div className="text-xs uppercase tracking-wide text-muted-foreground">{label}</div>
      <strong className="mt-2 block text-2xl font-semibold text-foreground">{value}</strong>
    </div>
  )
}

export function PolicyGovernancePanel({ resourceClass }: Props) {
  const { data: versions } = useVersions(resourceClass)
  const history = usePolicyImpactHistory(resourceClass)
  const analyzeImpact = useAnalyzePolicyImpact(resourceClass)
  const reviewImpact = useReviewPolicyImpact(resourceClass)
  const rolloutImpact = useRolloutPolicyImpact(resourceClass)

  const [baselineVersion, setBaselineVersion] = useState<string>('')
  const [candidateVersion, setCandidateVersion] = useState<string>('')
  const [requestsJson, setRequestsJson] = useState(DEFAULT_REQUESTS)
  const [timelinePreset, setTimelinePreset] = useState<TimelinePreset>('all')

  const timelineFilter = useMemo(() => {
    if (timelinePreset === 'review') return { eventType: ['impact_reviewed'] }
    if (timelinePreset === 'deploy') return { eventType: ['impact_deployed', 'policy_version_created'] }
    return undefined
  }, [timelinePreset])

  const timeline = usePolicyTimeline(resourceClass, timelineFilter)

  const latestAnalysis = analyzeImpact.data
  const versionOptions = versions ?? []

  const handleAnalyze = () => {
    try {
      const parsed = JSON.parse(requestsJson)
      if (!Array.isArray(parsed) || parsed.length === 0) {
        toast.error('Le jeu de requetes doit etre un tableau JSON non vide')
        return
      }

      analyzeImpact.mutate({
        baselineVersion: baselineVersion ? Number(baselineVersion) : undefined,
        candidateVersion: candidateVersion ? Number(candidateVersion) : undefined,
        requests: parsed,
      })
    } catch {
      toast.error('JSON invalide pour les requetes d impact')
    }
  }

  const handleReview = (analysisId: number, status: 'reviewed' | 'approved' | 'rejected') => {
    reviewImpact.mutate({
      analysisId,
      payload: { status },
    })
  }

  const handleRollout = (analysisId: number) => {
    rolloutImpact.mutate({ analysisId, payload: {} })
  }

  return (
    <div className="h-full overflow-auto bg-muted/15 px-6 py-6">
      <div className="grid min-h-full grid-cols-[minmax(21rem,23rem)_minmax(30rem,1.15fr)_minmax(21rem,25rem)] gap-6 2xl:grid-cols-[minmax(22rem,24rem)_minmax(34rem,1.2fr)_minmax(22rem,26rem)]">
        <div className="flex flex-col gap-5">
          <SectionCard title="Preparation" subtitle="Choisis la comparaison puis construis un jeu de requetes representatif.">
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <label className="text-xs text-muted-foreground space-y-1.5">
                  <span>Baseline</span>
                  <select value={baselineVersion} onChange={(e) => setBaselineVersion(e.target.value)} className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm">
                    <option value="">Courante</option>
                    {versionOptions.map((version) => (
                      <option key={`baseline-${version.version}`} value={String(version.version)}>
                        v{version.version}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="text-xs text-muted-foreground space-y-1.5">
                  <span>Candidate</span>
                  <select value={candidateVersion} onChange={(e) => setCandidateVersion(e.target.value)} className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm">
                    <option value="">Courante</option>
                    {versionOptions.map((version) => (
                      <option key={`candidate-${version.version}`} value={String(version.version)}>
                        v{version.version}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              <div className="rounded-xl border border-dashed border-border bg-background/70 p-4 text-sm leading-6 text-muted-foreground">
                Utilise des requetes proches de la realite metier. Les variations d'operation, de sujet et de ressource sont souvent ce qui revele le vrai blast radius.
              </div>

              <label className="block text-xs text-muted-foreground space-y-1.5">
                <span>Requetes de reference</span>
                <textarea
                  value={requestsJson}
                  onChange={(e) => setRequestsJson(e.target.value)}
                  className="min-h-[26rem] w-full rounded-xl border border-input bg-background px-4 py-4 font-mono text-[12px] leading-6 shadow-inner"
                />
              </label>

              <button
                onClick={handleAnalyze}
                disabled={analyzeImpact.isPending}
                className="inline-flex w-full items-center justify-center gap-2 rounded-md bg-autho-dark px-4 py-3 text-sm font-medium text-white hover:bg-autho-dark/90 disabled:opacity-50"
              >
                <Eye size={15} /> {analyzeImpact.isPending ? 'Analyse en cours...' : 'Generer une preview d impact'}
              </button>
            </div>
          </SectionCard>
        </div>

        <div className="flex flex-col gap-5 min-w-0">
          <SectionCard title="Derniere preview" subtitle="Le dernier resultat d'impact apparait ici, avec les principaux signaux de risque.">
            {latestAnalysis ? (
              <div className={`rounded-xl border p-4 ${latestAnalysis.riskSignals.highRisk ? 'border-amber-500/40 bg-amber-500/5' : 'border-border bg-background/80'}`}>
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <div className="flex items-center gap-2 text-foreground">
                      <Sparkles size={16} />
                      <span className="text-base font-semibold">Preview #{latestAnalysis.analysisId ?? 'courante'}</span>
                    </div>
                    <p className="mt-1 text-sm text-muted-foreground">
                      Compare {baselineVersion ? `v${baselineVersion}` : 'la policy courante'} avec {candidateVersion ? `v${candidateVersion}` : 'la candidate courante'}.
                    </p>
                  </div>
                  {latestAnalysis.riskSignals.highRisk ? (
                    <span className="rounded-full border border-amber-500/40 bg-amber-500/10 px-3 py-1 text-xs font-semibold text-amber-700">
                      Risque eleve
                    </span>
                  ) : null}
                </div>
                <div className="mt-5 grid grid-cols-2 gap-4 xl:grid-cols-4">
                  <MetricCard label="Changements" value={latestAnalysis.summary.changedDecisions} />
                  <MetricCard label="Revocations" value={latestAnalysis.summary.revokes} />
                  <MetricCard label="Sujets touches" value={latestAnalysis.riskSignals.changedSubjectCount} />
                  <MetricCard label="Ressources touchees" value={latestAnalysis.riskSignals.changedResourceCount} />
                </div>
              </div>
            ) : (
              <div className="rounded-xl border border-dashed border-border bg-background/60 p-6 text-sm text-muted-foreground">
                Aucune preview generee pour cette session. Choisis une baseline, une candidate et lance une analyse pour remplir ce panneau.
              </div>
            )}
          </SectionCard>

          <SectionCard title="Historique" subtitle="Previews persistees, statuts de review et actions de rollout.">
            <div className="mb-4 flex items-center justify-between text-xs text-muted-foreground">
              <span>{history.data?.length ?? 0} entree(s)</span>
            </div>

            {history.isLoading ? (
              <div className="space-y-3">
                {Array.from({ length: 3 }).map((_, index) => (
                  <div key={index} className="h-24 rounded-lg bg-muted animate-pulse" />
                ))}
              </div>
            ) : !history.data?.length ? (
              <div className="rounded-lg border border-dashed border-border p-5 text-sm text-muted-foreground">
                Aucune preview d impact enregistree pour cette politique.
              </div>
            ) : (
              <div className="space-y-4">
                {history.data.map((entry) => (
                  <div key={entry.id} className={`rounded-2xl border p-5 space-y-4 shadow-sm ${toneForStatus(entry)}`}>
                    <div className="flex items-start justify-between gap-4">
                      <div>
                        <div className="flex items-center gap-2 text-foreground">
                          <ShieldAlert size={15} />
                          <span className="text-sm font-semibold">Preview #{entry.id}</span>
                        </div>
                        <p className="mt-1 text-xs text-muted-foreground">
                          Creee le {formatDate(entry.createdAt)} par {entry.author ?? 'system'}
                        </p>
                      </div>
                      <div className="text-right text-xs text-muted-foreground space-y-1">
                        <div>Review: <strong className="text-foreground">{entry.reviewStatus}</strong></div>
                        <div>Rollout: <strong className="text-foreground">{entry.rolloutStatus}</strong></div>
                      </div>
                    </div>

                    <div className="grid grid-cols-2 gap-3 text-xs text-muted-foreground xl:grid-cols-4">
                      <span>Baseline: <strong className="text-foreground">{entry.baselineVersion ?? 'courante'}</strong></span>
                      <span>Candidate: <strong className="text-foreground">{entry.candidateVersion ?? entry.candidateSource ?? 'courante'}</strong></span>
                      <span>Changements: <strong className="text-foreground">{entry.changedDecisions}</strong></span>
                      <span>Revocations: <strong className="text-foreground">{entry.revokeCount}</strong></span>
                    </div>

                    <div className="flex flex-wrap gap-2">
                      <button onClick={() => handleReview(entry.id, 'reviewed')} disabled={reviewImpact.isPending} className="rounded-md border border-input px-3 py-1.5 text-xs hover:bg-muted transition-colors">
                        Marquer revue
                      </button>
                      <button onClick={() => handleReview(entry.id, 'approved')} disabled={reviewImpact.isPending} className="rounded-md border border-blue-500/40 px-3 py-1.5 text-xs text-blue-700 hover:bg-blue-500/10 transition-colors">
                        Approuver
                      </button>
                      <button onClick={() => handleReview(entry.id, 'rejected')} disabled={reviewImpact.isPending} className="rounded-md border border-red-500/40 px-3 py-1.5 text-xs text-red-700 hover:bg-red-500/10 transition-colors">
                        Rejeter
                      </button>
                      <button
                        onClick={() => handleRollout(entry.id)}
                        disabled={rolloutImpact.isPending || entry.reviewStatus !== 'approved' || entry.rolloutStatus === 'deployed'}
                        className="ml-auto inline-flex items-center gap-1 rounded-md bg-autho-dark px-3 py-1.5 text-xs text-white hover:bg-autho-dark/90 disabled:opacity-50"
                      >
                        <Rocket size={12} /> Deployer
                      </button>
                    </div>

                    {entry.deployedVersions?.length ? (
                      <div className="rounded-md bg-background/80 p-3 text-xs text-muted-foreground">
                        Versions liees: {entry.deployedVersions.map((version) => `v${version.version}`).join(', ')}
                      </div>
                    ) : null}
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {(reviewImpact.isError || rolloutImpact.isError) && (
            <div className="rounded-lg border border-red-500/30 bg-red-500/5 p-3 text-sm text-red-700">
              {(reviewImpact.error as Error | null)?.message ?? (rolloutImpact.error as Error | null)?.message ?? 'Une erreur est survenue'}
            </div>
          )}
        </div>

        <aside className="flex flex-col gap-5">
          <SectionCard title="Timeline" subtitle="Chronologie recente des reviews, deploiements et creations de version.">
            <div className="flex items-center gap-2 pb-4">
              <Filter size={13} className="text-muted-foreground" />
              <button onClick={() => setTimelinePreset('all')} className={`rounded-md px-3 py-1.5 text-xs ${timelinePreset === 'all' ? 'bg-autho-dark text-white' : 'border border-input text-muted-foreground hover:bg-muted'}`}>
                Tout
              </button>
              <button onClick={() => setTimelinePreset('review')} className={`rounded-md px-3 py-1.5 text-xs ${timelinePreset === 'review' ? 'bg-autho-dark text-white' : 'border border-input text-muted-foreground hover:bg-muted'}`}>
                Reviews
              </button>
              <button onClick={() => setTimelinePreset('deploy')} className={`rounded-md px-3 py-1.5 text-xs ${timelinePreset === 'deploy' ? 'bg-autho-dark text-white' : 'border border-input text-muted-foreground hover:bg-muted'}`}>
                Deploys
              </button>
            </div>

            {timeline.isLoading ? (
              <div className="space-y-3">
                {Array.from({ length: 4 }).map((_, index) => (
                  <div key={index} className="h-20 rounded-lg bg-muted animate-pulse" />
                ))}
              </div>
            ) : !timeline.data?.events?.length ? (
              <div className="rounded-lg border border-dashed border-border p-4 text-sm text-muted-foreground">
                Aucun evenement pour cette politique avec le filtre actuel.
              </div>
            ) : (
              <div className="space-y-4 border-l border-border/70 pl-4">
                {timeline.data.events.map((event) => {
                  const eventType = getEventType(event)
                  const actor = getEventActor(event)
                  return (
                    <div key={`${eventType}-${event.occurredAt}-${event.analysisId ?? 'na'}-${event.version ?? 'na'}`} className="relative rounded-2xl border border-border/80 bg-background p-4 shadow-sm before:absolute before:-left-[1.4rem] before:top-5 before:h-2.5 before:w-2.5 before:rounded-full before:bg-autho-dark">
                      <div className="flex items-start justify-between gap-3">
                        <div>
                          <div className="flex items-center gap-2 text-foreground">
                            {eventType.includes('deploy') ? <Rocket size={14} /> : eventType.includes('review') ? <CheckCircle2 size={14} /> : eventType.includes('version') ? <History size={14} /> : <Clock3 size={14} />}
                            <span className="text-sm font-semibold">{eventLabel(event)}</span>
                          </div>
                          <p className="mt-1 text-xs text-muted-foreground">{formatDate(event.occurredAt)}</p>
                        </div>
                        <div className="text-right text-[11px] text-muted-foreground">
                          {event.analysisId ? <div>Preview #{event.analysisId}</div> : null}
                          {event.version ? <div>Version v{event.version}</div> : null}
                        </div>
                      </div>

                      <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-muted-foreground">
                        {actor ? <span>Acteur: <strong className="text-foreground">{actor}</strong></span> : null}
                        {event.reviewStatus ? <span>Review: <strong className="text-foreground">{event.reviewStatus}</strong></span> : null}
                        {event.rolloutStatus ? <span>Rollout: <strong className="text-foreground">{event.rolloutStatus}</strong></span> : null}
                        {event.deploymentKind ? <span>Type: <strong className="text-foreground">{event.deploymentKind}</strong></span> : null}
                      </div>

                      {event.summary ? (
                        <div className="mt-3 grid grid-cols-2 gap-2 text-[11px] text-muted-foreground">
                          <span>Changements: <strong className="text-foreground">{event.summary.changedDecisions}</strong></span>
                          <span>Revocations: <strong className="text-foreground">{event.summary.revokes}</strong></span>
                          <span>Grants: <strong className="text-foreground">{event.summary.grants}</strong></span>
                          <span>Requetes: <strong className="text-foreground">{event.summary.totalRequests}</strong></span>
                        </div>
                      ) : null}
                    </div>
                  )
                })}
              </div>
            )}

            {timeline.isError && (
              <div className="mt-3 rounded-lg border border-red-500/30 bg-red-500/5 p-3 text-sm text-red-700">
                {(timeline.error as Error | null)?.message ?? 'Impossible de charger la timeline'}
              </div>
            )}
          </SectionCard>
        </aside>
      </div>
    </div>
  )
}
