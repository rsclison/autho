export interface ResourceClassSummary {
  class: string
  description?: string
  count?: number
}

export interface PipResource {
  class: string
  id: string
  attributes: Record<string, unknown>
}
