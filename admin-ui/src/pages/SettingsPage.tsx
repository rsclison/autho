import { useState } from 'react'
import { Moon, Sun, LogOut, Key, Info } from 'lucide-react'
import { getDarkMode, setDarkMode, clearToken, getToken } from '@/lib/auth'
import { useStatus } from '@/api/status'
import toast from 'react-hot-toast'

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-card border border-border rounded-xl p-5">
      <h2 className="text-sm font-semibold text-foreground mb-4">{title}</h2>
      {children}
    </div>
  )
}

export default function SettingsPage() {
  const [dark, setDark] = useState(getDarkMode())
  const { data: status } = useStatus()
  const token = getToken()

  const toggleDark = () => {
    const next = !dark
    setDark(next)
    setDarkMode(next)
    document.documentElement.classList.toggle('dark', next)
    toast.success(next ? 'Mode sombre activé' : 'Mode clair activé')
  }

  const handleLogout = () => {
    clearToken()
    window.location.href = '/admin/ui/login'
  }

  return (
    <div className="space-y-5 max-w-xl">
      {/* Appearance */}
      <Section title="Apparence">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm text-foreground">Mode sombre</p>
            <p className="text-xs text-muted-foreground mt-0.5">Préférence sauvegardée dans le navigateur</p>
          </div>
          <button
            onClick={toggleDark}
            className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${dark ? 'bg-autho-dark' : 'bg-muted'}`}
          >
            <span className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform ${dark ? 'translate-x-6' : 'translate-x-1'}`} />
          </button>
        </div>
        <div className="flex items-center gap-2 mt-3 text-xs text-muted-foreground">
          {dark ? <Moon size={13} /> : <Sun size={13} />}
          <span>Thème actuel : {dark ? 'sombre' : 'clair'}</span>
        </div>
      </Section>

      {/* Session */}
      <Section title="Session">
        <div className="space-y-3">
          <div className="flex items-start gap-3 p-3 rounded-lg bg-muted/40">
            <Key size={14} className="mt-0.5 text-muted-foreground shrink-0" />
            <div className="min-w-0">
              <p className="text-xs font-medium text-foreground">Token actif</p>
              <p className="text-xs text-muted-foreground font-mono truncate mt-0.5">
                {token ? `${token.slice(0, 20)}…` : 'Aucun token'}
              </p>
              <p className="text-xs text-muted-foreground mt-1">
                Stocké en sessionStorage — effacé à la fermeture de l'onglet
              </p>
            </div>
          </div>
          <button
            onClick={handleLogout}
            className="flex items-center gap-2 text-xs px-3 py-2 rounded-md border border-destructive text-destructive hover:bg-destructive/10 transition-colors"
          >
            <LogOut size={13} /> Se déconnecter
          </button>
        </div>
      </Section>

      {/* Server info */}
      {status && (
        <Section title="Informations serveur">
          <div className="space-y-2 text-xs">
            {([
              ['Version', status.version],
              ['Statut', status.status],
              ['Uptime', status.uptime?.formatted],
              ['Dépôt de règles', status.rulesRepository],
              ['Kafka', status.kafka?.enabled ? 'activé' : 'désactivé'],
              ['Rate limiting', status.rateLimit?.enabled ? `activé (${status.rateLimit.requestsPerMinute} req/min)` : 'désactivé'],
            ] as [string, string | undefined][]).map(([k, v]) => (
              <div key={k} className="flex items-center justify-between py-1 border-b border-border last:border-0">
                <span className="text-muted-foreground">{k}</span>
                <span className="font-mono text-foreground">{v ?? '—'}</span>
              </div>
            ))}
          </div>
        </Section>
      )}

      {/* About */}
      <Section title="À propos">
        <div className="flex items-start gap-3">
          <Info size={14} className="mt-0.5 text-muted-foreground shrink-0" />
          <div className="text-xs text-muted-foreground space-y-1">
            <p><span className="font-semibold text-foreground">Autho</span> — Serveur d'autorisation ABAC/XACML</p>
            <p>Interface d'administration — React 18 + Vite 5 + TanStack Query v5</p>
            <p className="text-autho-accent font-mono">autho.handler/serve-spa-page</p>
          </div>
        </div>
      </Section>
    </div>
  )
}
