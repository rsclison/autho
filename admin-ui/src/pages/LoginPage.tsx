import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { Zap, KeyRound, ShieldCheck } from 'lucide-react'
import { setToken, type TokenType } from '@/lib/auth'
import { api, ApiError } from '@/lib/api-client'

type Mode = 'api-key' | 'jwt'

export default function LoginPage() {
  const navigate = useNavigate()
  const [mode, setMode] = useState<Mode>('api-key')
  const [value, setValue] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    if (!value.trim()) {
      setError('Veuillez saisir un token')
      return
    }
    setLoading(true)
    setError('')

    const tokenType: TokenType = mode === 'api-key' ? 'api-key' : 'jwt'
    setToken(value.trim(), tokenType)

    try {
      await api.get('/status')
      navigate('/')
    } catch (err) {
      if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
        setError('Token invalide ou accès refusé')
      } else {
        setError('Impossible de joindre le serveur Autho')
      }
      // Ne pas clearToken() ici — la redirection /login est gérée par api-client
      // Mais on doit quand même vider si l'auth échoue
      import('@/lib/auth').then(({ clearToken }) => clearToken())
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-background p-4">
      <div className="w-full max-w-md">
        {/* Logo */}
        <div className="flex flex-col items-center mb-8">
          <div className="flex items-center justify-center w-14 h-14 rounded-2xl bg-autho-dark mb-4">
            <Zap size={28} className="text-autho-blue" />
          </div>
          <h1 className="text-2xl font-bold text-foreground">Autho Admin</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Connectez-vous pour accéder à l'administration
          </p>
        </div>

        {/* Card */}
        <div className="bg-card rounded-xl border border-border p-8 shadow-sm">
          {/* Mode selector */}
          <div className="flex rounded-lg border border-border p-1 mb-6">
            <button
              type="button"
              onClick={() => { setMode('api-key'); setError('') }}
              className={`flex-1 flex items-center justify-center gap-2 py-2 px-3 rounded-md text-sm font-medium transition-colors ${
                mode === 'api-key'
                  ? 'bg-autho-dark text-white'
                  : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              <KeyRound size={14} />
              API Key
            </button>
            <button
              type="button"
              onClick={() => { setMode('jwt'); setError('') }}
              className={`flex-1 flex items-center justify-center gap-2 py-2 px-3 rounded-md text-sm font-medium transition-colors ${
                mode === 'jwt'
                  ? 'bg-autho-dark text-white'
                  : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              <ShieldCheck size={14} />
              JWT Token
            </button>
          </div>

          <form onSubmit={(e) => { void handleSubmit(e) }}>
            <div className="mb-4">
              <label className="block text-sm font-medium text-foreground mb-2">
                {mode === 'api-key' ? 'Clé API' : 'JWT Bearer Token'}
              </label>
              {mode === 'api-key' ? (
                <input
                  type="password"
                  value={value}
                  onChange={(e) => setValue(e.target.value)}
                  placeholder="Saisir la clé API…"
                  autoFocus
                  className="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                />
              ) : (
                <textarea
                  value={value}
                  onChange={(e) => setValue(e.target.value)}
                  placeholder="Coller le JWT ici…"
                  autoFocus
                  rows={4}
                  className="w-full px-3 py-2 rounded-md border border-input bg-background text-foreground text-sm font-mono focus:outline-none focus:ring-2 focus:ring-ring resize-none"
                />
              )}
            </div>

            {error && (
              <p className="text-sm text-destructive mb-4 flex items-center gap-1">
                <span>⚠</span> {error}
              </p>
            )}

            <button
              type="submit"
              disabled={loading}
              className="w-full py-2.5 px-4 rounded-md bg-autho-dark text-white text-sm font-medium hover:bg-autho-dark/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {loading ? 'Vérification…' : 'Se connecter'}
            </button>
          </form>
        </div>

        <p className="text-center text-xs text-muted-foreground mt-6">
          Le token est stocké en session et effacé à la fermeture de l'onglet
        </p>
      </div>
    </div>
  )
}
