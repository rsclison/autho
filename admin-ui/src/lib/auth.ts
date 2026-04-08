const TOKEN_KEY = 'autho-admin-token'
const TOKEN_TYPE_KEY = 'autho-admin-token-type'
const DARK_MODE_KEY = 'autho-dark-mode'

export type TokenType = 'api-key' | 'jwt'

export function setToken(token: string, type: TokenType): void {
  sessionStorage.setItem(TOKEN_KEY, token)
  sessionStorage.setItem(TOKEN_TYPE_KEY, type)
}

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function getTokenType(): TokenType | null {
  return sessionStorage.getItem(TOKEN_TYPE_KEY) as TokenType | null
}

export function clearToken(): void {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(TOKEN_TYPE_KEY)
}

export function isAuthenticated(): boolean {
  return !!getToken()
}

export function getAuthHeader(): Record<string, string> {
  const token = getToken()
  const type = getTokenType()
  if (!token) return {}
  if (type === 'api-key') return { 'Authorization': `X-API-Key ${token}` }
  return { 'Authorization': `Token ${token}` }
}

export function getDarkMode(): boolean {
  return localStorage.getItem(DARK_MODE_KEY) === 'true'
}

export function setDarkMode(enabled: boolean): void {
  localStorage.setItem(DARK_MODE_KEY, String(enabled))
  if (enabled) {
    document.documentElement.classList.add('dark')
  } else {
    document.documentElement.classList.remove('dark')
  }
}

export function initDarkMode(): void {
  if (getDarkMode()) {
    document.documentElement.classList.add('dark')
  }
}

