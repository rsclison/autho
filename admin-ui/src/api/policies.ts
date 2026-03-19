import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import toast from 'react-hot-toast'
import type { Policy, PolicyVersion, DiffResult } from '@/types/policy'

export function usePolicies() {
  return useQuery({
    queryKey: ['policies'],
    queryFn: () => api.get<Record<string, { global: Policy }>>('/policies'),
  })
}

export function usePolicy(resourceClass: string | null) {
  return useQuery({
    queryKey: ['policy', resourceClass],
    queryFn: () => api.get<{ global: Policy }>(`/policies/${resourceClass}`),
    enabled: !!resourceClass,
  })
}

export function useSubmitPolicy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ resourceClass, policy }: { resourceClass: string; policy: unknown }) =>
      api.put<unknown>(`/policies/${resourceClass}`, policy),
    onSuccess: (_, { resourceClass }) => {
      void qc.invalidateQueries({ queryKey: ['policies'] })
      void qc.invalidateQueries({ queryKey: ['policy', resourceClass] })
      void qc.invalidateQueries({ queryKey: ['versions', resourceClass] })
      toast.success('Politique sauvegardée')
    },
  })
}

export function useDeletePolicy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (resourceClass: string) =>
      api.delete<unknown>(`/policies/${resourceClass}`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['policies'] })
      toast.success('Politique supprimée')
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
      toast.success(`${data.rulesLoaded} règles importées pour ${data.resourceClass}`)
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
    queryFn: () =>
      api.get<Policy>(`/v1/policies/${resourceClass}/versions/${version}`),
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
      toast.success(`Rollback effectué — nouvelle version : v${data.newVersion}`)
    },
  })
}
