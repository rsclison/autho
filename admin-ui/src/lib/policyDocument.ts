type UnknownRecord = Record<string, unknown>

export const DEFAULT_POLICY_STRATEGY = 'almost_one_allow_no_deny'

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function normalizePolicyForSave(document: unknown, resourceClass: string): UnknownRecord {
  if (!isRecord(document)) {
    return {
      resourceClass,
      strategy: DEFAULT_POLICY_STRATEGY,
      rules: [],
    }
  }

  const global = isRecord(document.global) ? document.global : null
  if (global) {
    return {
      ...global,
      resourceClass: typeof document.resourceClass === 'string' ? document.resourceClass : resourceClass,
    }
  }

  return {
    ...document,
    resourceClass: typeof document.resourceClass === 'string' ? document.resourceClass : resourceClass,
  }
}
