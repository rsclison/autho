import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import toast from 'react-hot-toast'
import type { CacheStats } from '@/types/status'

export function useCacheStats() {
  return useQuery({
    queryKey: ['cache', 'stats'],
    queryFn: () => api.get<CacheStats>('/v1/cache/stats'),
    refetchInterval: 15_000,
  })
}

export function useClearCache() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.delete<{ status: string }>('/v1/cache'),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['cache'] })
      toast.success('Cache vidé avec succès')
    },
  })
}

export function useInvalidateEntry() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ type, key }: { type: string; key: string }) =>
      api.delete<{ status: string }>(`/v1/cache/${type}/${key}`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['cache'] })
      toast.success('Entrée invalidée')
    },
  })
}
