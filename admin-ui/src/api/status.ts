import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { ServerStatus } from '@/types/status'

export function useStatus() {
  return useQuery({
    queryKey: ['status'],
    queryFn: () => api.get<ServerStatus>('/status'),
    refetchInterval: 10_000,
  })
}

export function useHealth() {
  return useQuery({
    queryKey: ['health'],
    queryFn: () => api.get<{ status: string }>('/health'),
    refetchInterval: 30_000,
  })
}
