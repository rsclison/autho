import { useMutation } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { AuthRequest, AuthResult, ExplainResult, SimulateResult } from '@/types/decision'

export function useIsAuthorized() {
  return useMutation({
    mutationFn: (payload: AuthRequest) =>
      api.post<AuthResult>('/isAuthorized', payload),
  })
}

export function useExplain() {
  return useMutation({
    mutationFn: (payload: AuthRequest) =>
      api.post<ExplainResult>('/explain', payload),
  })
}

export function useSimulate() {
  return useMutation({
    mutationFn: (payload: AuthRequest) =>
      api.post<SimulateResult>('/v1/authz/simulate', payload),
  })
}

export function useBatchDecisions() {
  return useMutation({
    mutationFn: (requests: AuthRequest[]) =>
      api.post<{ results: Array<{ 'request-id': number; decision?: AuthResult; error?: string }>; count: number }>(
        '/v1/authz/batch',
        { requests },
      ),
  })
}

export function useAdminActions() {
  return {
    reinit: useMutation({
      mutationFn: () => api.post<{ status: string; message: string }>('/admin/reinit', {}),
    }),
    reloadRules: useMutation({
      mutationFn: () => api.post<{ status: string; message: string }>('/admin/reload_rules', {}),
    }),
    reloadPersons: useMutation({
      mutationFn: () => api.post<{ status: string; message: string; personCount: number }>('/admin/reload_persons', {}),
    }),
  }
}
