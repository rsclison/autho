import { getAuthHeader, clearToken } from './auth'
import toast from 'react-hot-toast'

export class ApiError extends Error {
  public readonly status: number
  public readonly code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  contentType = 'application/json',
): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...getAuthHeader(),
  }
  if (body !== undefined) {
    headers['Content-Type'] = contentType
  }

  const response = await fetch(path, {
    method,
    headers,
    body:
      body !== undefined
        ? typeof body === 'string'
          ? body
          : JSON.stringify(body)
        : undefined,
  })

  if (response.status === 401 || response.status === 403) {
    clearToken()
    window.location.href = '/admin/ui/login'
    throw new ApiError(response.status, 'UNAUTHORIZED', 'Session expirée')
  }

  if (response.status === 429) {
    throw new ApiError(429, 'RATE_LIMIT', 'Trop de requêtes — réessayez dans quelques secondes')
  }

  if (!response.ok) {
    let code = 'UNKNOWN_ERROR'
    let message = `Erreur HTTP ${response.status}`
    try {
      const err = (await response.json()) as { error?: { code?: string; message?: string } }
      code = err?.error?.code ?? code
      message = err?.error?.message ?? message
    } catch (_) {
      // ignore parse error
    }
    throw new ApiError(response.status, code, message)
  }

  const text = await response.text()
  if (!text) return undefined as T
  const json = JSON.parse(text)
  // Désencapsule l'enveloppe standard v1 : { status: "success", data: ... }
  if (json && typeof json === 'object' && json.status === 'success' && 'data' in json) {
    return json.data as T
  }
  return json as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  delete: <T>(path: string) => request<T>('DELETE', path),
  postYaml: <T>(path: string, yaml: string) =>
    request<T>('POST', path, yaml, 'text/yaml'),
}

/** Gestionnaire d'erreur global pour TanStack Query */
function shouldSuppressErrorToast(context: unknown): boolean {
  if (!context || typeof context !== 'object') return false
  const meta = (context as { meta?: { suppressErrorToast?: boolean } }).meta
  return meta?.suppressErrorToast === true
}

export function handleQueryError(error: unknown, context?: unknown): void {
  if (shouldSuppressErrorToast(context)) {
    return
  }

  if (error instanceof ApiError) {
    toast.error(error.message)
  } else if (error instanceof Error) {
    toast.error(error.message)
  }
}
