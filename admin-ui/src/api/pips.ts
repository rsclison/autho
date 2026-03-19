import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api-client'

// Map: entityClass → string[]
export type PipSchema = Record<string, string[]>

export function usePipSchema() {
  return useQuery<PipSchema>({
    queryKey: ['pips', 'schema'],
    queryFn: () => api.get<PipSchema>('/admin/pips/schema'),
    staleTime: 5 * 60_000,   // 5 min — schema rarely changes at runtime
    retry: false,             // graceful degradation: fall back to static list
  })
}
