import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'

export interface QuarantinedRelationEvent {
  id: string
  event_value: string
  reason?: string
}

interface QuarantineResponse {
  entries: QuarantinedRelationEvent[]
  count: number
}

export interface RelationProjectionStatus {
  running: boolean
  healthy: boolean
  quarantineCount: number
  projectionAgeMs?: number
  maxLagRecords: number
  lagByPartition: Record<string, number>
  thresholds: { maxIdleMs: number; maxLagRecords: number }
}

export function useRelationProjectionStatus() {
  return useQuery<RelationProjectionStatus>({
    queryKey: ['relations', 'status'],
    queryFn: () => api.get<RelationProjectionStatus>('/v1/relations/status'),
    refetchInterval: 15_000,
  })
}

export function useRelationQuarantine() {
  return useQuery<QuarantineResponse>({
    queryKey: ['relations', 'quarantine'],
    queryFn: () => api.get<QuarantineResponse>('/v1/relations/quarantine'),
    refetchInterval: 30_000,
  })
}

export function useReplayRelationQuarantine() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.post<{ status: string }>(`/v1/relations/quarantine/${encodeURIComponent(id)}/replay`, {}),
    onSuccess: () => client.invalidateQueries({ queryKey: ['relations', 'quarantine'] }),
  })
}

export interface ReconciliationReport {
  source: string
  expectedCount: number
  projectedCount: number
  missing: unknown[]
  obsolete: unknown[]
  conflicts: unknown[]
  errors: unknown[]
}

export function useReconcileRelations() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ source, tuples }: { source: string; tuples: unknown[] }) =>
      api.post<ReconciliationReport>('/v1/relations/reconcile', { source, tuples }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['relations', 'reconciliation-reports'] }),
  })
}

export interface ReconciliationReportSummary {
  id: string
  source: string
  expected_count: number
  projected_count: number
  missing_count: number
  obsolete_count: number
  created_at?: string
}

export function useReconciliationReports() {
  return useQuery<{ reports: ReconciliationReportSummary[]; count: number }>({
    queryKey: ['relations', 'reconciliation-reports'],
    queryFn: () => api.get('/v1/relations/reconcile/reports'),
  })
}

export interface ReconciliationSource { name: string; type: string; url?: string; timeoutMs?: number }

export function useReconciliationSources() {
  return useQuery<{ sources: ReconciliationSource[] }>({
    queryKey: ['relations', 'reconciliation-sources'],
    queryFn: () => api.get('/v1/relations/reconcile/sources'),
  })
}

export function useImportReconciliationSource() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (source: string) => api.post<ReconciliationReport>(`/v1/relations/reconcile/${encodeURIComponent(source)}/import`, {}),
    onSuccess: () => client.invalidateQueries({ queryKey: ['relations', 'reconciliation-reports'] }),
  })
}

export interface RelationProjectionAuditEvent {
  id: string
  action: string
  source?: string
  event_id?: string
  created_at?: string
}

export function useRelationProjectionAudit() {
  return useQuery<{ events: RelationProjectionAuditEvent[]; count: number }>({
    queryKey: ['relations', 'audit'],
    queryFn: () => api.get('/v1/relations/audit'),
    refetchInterval: 30_000,
  })
}
