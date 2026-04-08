import { useQuery, useMutation } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { AuditResult, AuditSearchParams, AuditChainResult } from '@/types/audit'

interface UseAuditOptions {
  suppressErrorToast?: boolean
  retry?: boolean | number
}

export function useAudit(params: AuditSearchParams, options?: UseAuditOptions) {
  return useQuery({
    queryKey: ['audit', params],
    queryFn: () => {
      const qs = new URLSearchParams()
      if (params.subjectId)     qs.set('subjectId', params.subjectId)
      if (params.resourceClass) qs.set('resourceClass', params.resourceClass)
      if (params.decision)      qs.set('decision', params.decision)
      if (params.from)          qs.set('from', params.from)
      if (params.to)            qs.set('to', params.to)
      qs.set('page', String(params.page ?? 1))
      qs.set('pageSize', String(params.pageSize ?? 20))
      return api.get<AuditResult>(`/admin/audit/search?${qs.toString()}`)
    },
    placeholderData: (prev) => prev,
    retry: options?.retry,
    meta: options?.suppressErrorToast ? { suppressErrorToast: true } : undefined,
  })
}

export function useVerifyChain() {
  return useMutation({
    mutationFn: () => api.get<AuditChainResult>('/admin/audit/verify'),
  })
}
