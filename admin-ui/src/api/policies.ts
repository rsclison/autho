import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import toast from 'react-hot-toast'
import type {
  Policy,
  PolicyVersion,
  DiffResult,
  PolicyImpactAnalysis,
  PolicyImpactHistoryEntry,
  PolicyImpactRequest,
  PolicyImpactReviewPayload,
  PolicyImpactRolloutResult,
  PolicyTimeline,
} from '@/types/policy'

function buildTimelineQuery(resourceClass: string, filters?: {
  eventType?: string[]
  from?: string
  to?: string
}) {
  const params = new URLSearchParams()
  if (filters?.eventType?.length) {
    params.set('event-type', filters.eventType.join(','))
  }
  if (filters?.from) {
    params.set('from', filters.from)
  }
  if (filters?.to) {
    params.set('to', filters.to)
  }
  const query = params.toString()
  return `/v1/policies/${resourceClass}/timeline${query ? `?${query}` : ''}`
}

export function usePolicies() {
  return useQuery({
    queryKey: ['policies'],
    queryFn: () => api.get<Record<string, { global: Policy }>>('/policies'),
  })
}

export function usePolicy(resourceClass: string | null) {
  return useQuery({
    queryKey: ['policy', resourceClass],
    queryFn: () => api.get<{ global: Policy }>(`/policy/${resourceClass}`),
    enabled: !!resourceClass,
  })
}

export function useSubmitPolicy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ resourceClass, policy }: { resourceClass: string; policy: unknown }) =>
      api.put<unknown>(`/policy/${resourceClass}`, policy),
    onSuccess: (_, { resourceClass }) => {
      void qc.invalidateQueries({ queryKey: ['policies'] })
      void qc.invalidateQueries({ queryKey: ['policy', resourceClass] })
      void qc.invalidateQueries({ queryKey: ['versions', resourceClass] })
      void qc.invalidateQueries({ queryKey: ['impact-history', resourceClass] })
      void qc.invalidateQueries({ queryKey: ['timeline', resourceClass] })
      toast.success('Politique sauvegardee')
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Erreur lors de la sauvegarde'
      toast.error(msg)
    },
  })
}

export function useDeletePolicy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (resourceClass: string) =>
      api.delete<unknown>(`/policy/${resourceClass}`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['policies'] })
      toast.success('Politique supprimee')
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Erreur lors de la suppression'
      toast.error(msg)
    },
  })
}

export function useImportYaml() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (yaml: string) =>
      api.postYaml<{ ok: boolean; resourceClass: string; rulesLoaded: number }>(
        '/v1/policies/import',
        yaml,
      ),
    onSuccess: (data) => {
      void qc.invalidateQueries({ queryKey: ['policies'] })
      toast.success(`${data.rulesLoaded} regles importees pour ${data.resourceClass}`)
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : "Erreur lors de l'import YAML"
      toast.error(msg)
    },
  })
}

export function useVersions(resourceClass: string | null) {
  return useQuery({
    queryKey: ['versions', resourceClass],
    queryFn: () =>
      api.get<PolicyVersion[]>(`/v1/policies/${resourceClass}/versions`),
    enabled: !!resourceClass,
  })
}

export function useVersion(resourceClass: string | null, version: number | null) {
  return useQuery({
    queryKey: ['version', resourceClass, version],
    queryFn: async () => {
      const data = await api.get<PolicyVersion>(`/v1/policies/${resourceClass}/versions/${version}`)
      return data.policy ?? data
    },
    enabled: !!resourceClass && version !== null,
  })
}

export function useVersionRecord(resourceClass: string | null, version: number | null) {
  return useQuery({
    queryKey: ['version-record', resourceClass, version],
    queryFn: () =>
      api.get<PolicyVersion>(`/v1/policies/${resourceClass}/versions/${version}`),
    enabled: !!resourceClass && version !== null,
  })
}

export function useDiffVersions(
  resourceClass: string | null,
  fromV: number | null,
  toV: number | null,
) {
  return useQuery({
    queryKey: ['diff', resourceClass, fromV, toV],
    queryFn: () =>
      api.get<DiffResult>(
        `/v1/policies/${resourceClass}/diff?from=${fromV}&to=${toV}`,
      ),
    enabled: !!resourceClass && fromV !== null && toV !== null,
  })
}

export function useAnalyzePolicyImpact(resourceClass: string | null) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (payload: PolicyImpactRequest) =>
      api.post<PolicyImpactAnalysis>(`/v1/policies/${resourceClass}/impact`, payload),
    onSuccess: (_, variables) => {
      if (!resourceClass) return
      void qc.invalidateQueries({ queryKey: ['impact-history', resourceClass] })
      void qc.invalidateQueries({ queryKey: ['timeline', resourceClass] })
      if (variables.candidateVersion !== undefined) {
        void qc.invalidateQueries({ queryKey: ['versions', resourceClass] })
      }
      toast.success('Preview d\'impact generee')
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Erreur lors de l\'analyse d\'impact'
      toast.error(msg)
    },
  })
}

export function usePolicyImpactHistory(resourceClass: string | null) {
  return useQuery({
    queryKey: ['impact-history', resourceClass],
    queryFn: () =>
      api.get<PolicyImpactHistoryEntry[]>(`/v1/policies/${resourceClass}/impact/history`),
    enabled: !!resourceClass,
  })
}

export function usePolicyImpactHistoryEntry(resourceClass: string | null, analysisId: number | null) {
  return useQuery({
    queryKey: ['impact-history-entry', resourceClass, analysisId],
    queryFn: () =>
      api.get<PolicyImpactHistoryEntry>(`/v1/policies/${resourceClass}/impact/history/${analysisId}`),
    enabled: !!resourceClass && analysisId !== null,
  })
}

export function useReviewPolicyImpact(resourceClass: string | null) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ analysisId, payload }: { analysisId: number; payload: PolicyImpactReviewPayload }) =>
      api.post<PolicyImpactHistoryEntry>(`/v1/policies/${resourceClass}/impact/history/${analysisId}/review`, payload),
    onSuccess: (_, { analysisId }) => {
      if (!resourceClass) return
      void qc.invalidateQueries({ queryKey: ['impact-history', resourceClass] })
      void qc.invalidateQueries({ queryKey: ['impact-history-entry', resourceClass, analysisId] })
      void qc.invalidateQueries({ queryKey: ['timeline', resourceClass] })
      toast.success('Statut de review mis a jour')
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Erreur lors de la mise a jour de review'
      toast.error(msg)
    },
  })
}

export function useRolloutPolicyImpact(resourceClass: string | null) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ analysisId, payload }: { analysisId: number; payload?: Record<string, unknown> }) =>
      api.post<PolicyImpactRolloutResult>(`/v1/policies/${resourceClass}/impact/history/${analysisId}/rollout`, payload ?? {}),
    onSuccess: (_, { analysisId }) => {
      if (!resourceClass) return
      void qc.invalidateQueries({ queryKey: ['policy', resourceClass] })
      void qc.invalidateQueries({ queryKey: ['policies'] })
      void qc.invalidateQueries({ queryKey: ['versions', resourceClass] })
      void qc.invalidateQueries({ queryKey: ['impact-history', resourceClass] })
      void qc.invalidateQueries({ queryKey: ['impact-history-entry', resourceClass, analysisId] })
      void qc.invalidateQueries({ queryKey: ['timeline', resourceClass] })
      toast.success('Rollout effectue depuis la preview approuvee')
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Erreur lors du rollout'
      toast.error(msg)
    },
  })
}

export function usePolicyTimeline(resourceClass: string | null, filters?: {
  eventType?: string[]
  from?: string
  to?: string
}) {
  return useQuery({
    queryKey: ['timeline', resourceClass, filters?.eventType?.join(','), filters?.from, filters?.to],
    queryFn: () => api.get<PolicyTimeline>(buildTimelineQuery(resourceClass!, filters)),
    enabled: !!resourceClass,
  })
}

export function useRollback() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ resourceClass, version }: { resourceClass: string; version: number }) =>
      api.post<{ resourceClass: string; rolledBackTo: number; newVersion: number }>(
        `/v1/policies/${resourceClass}/rollback/${version}`,
        {},
      ),
    onSuccess: (data) => {
      void qc.invalidateQueries({ queryKey: ['policies'] })
      void qc.invalidateQueries({ queryKey: ['policy', data.resourceClass] })
      void qc.invalidateQueries({ queryKey: ['versions', data.resourceClass] })
      void qc.invalidateQueries({ queryKey: ['timeline', data.resourceClass] })
      toast.success(`Rollback effectue - nouvelle version : v${data.newVersion}`)
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Erreur lors du rollback'
      toast.error(msg)
    },
  })
}

