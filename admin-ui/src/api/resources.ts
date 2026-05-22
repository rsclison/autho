import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { PipResource, ResourceClassSummary } from '@/types/resource'

export function useResourceClasses() {
  return useQuery({
    queryKey: ['resources', 'classes'],
    queryFn: () => api.get<ResourceClassSummary[]>('/v1/resources'),
  })
}

export function useResourcesByClass(resourceClass: string | null) {
  return useQuery({
    queryKey: ['resources', resourceClass],
    queryFn: () =>
      api.get<PipResource[]>(
        `/v1/resources/${encodeURIComponent(resourceClass ?? '')}?page=1&per-page=100&sort=id`,
      ),
    enabled: !!resourceClass,
  })
}
