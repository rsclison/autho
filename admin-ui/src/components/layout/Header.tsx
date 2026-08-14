import { useLocation } from 'react-router-dom'
import { Moon, Sun, LogOut, Menu } from 'lucide-react'
import { useState } from 'react'
import { getDarkMode, setDarkMode, clearToken } from '@/lib/auth'
import { useNavigate } from 'react-router-dom'

const labels: Record<string, string> = {
  '/':               'Dashboard',
  '/policies':       'Politiques',
  '/simulator':      'Simulateur',
  '/audit':          'Journal d\'audit',
  '/pip-data':       'Données PIP',
  '/relations':      'Relations',
  '/infrastructure': 'Infrastructure',
  '/settings':       'Paramètres',
}

export function Header({ onOpenMenu }: { onOpenMenu: () => void }) {
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const [dark, setDark] = useState(getDarkMode())

  const toggleDark = () => {
    const next = !dark
    setDark(next)
    setDarkMode(next)
  }

  const logout = () => {
    clearToken()
    navigate('/login')
  }

  const title = Object.entries(labels)
    .sort((a, b) => b[0].length - a[0].length)
    .find(([path]) => pathname === path || pathname.startsWith(path + '/') )?.[1]
    ?? 'Autho Admin'

  return (
    <header className="flex min-h-16 items-center justify-between border-b border-border bg-card px-4 sm:px-6 shrink-0">
      <div className="flex min-w-0 items-center gap-2">
        <button onClick={onOpenMenu} className="inline-flex rounded-md p-2 text-muted-foreground hover:bg-muted lg:hidden" aria-label="Ouvrir le menu"><Menu size={20} /></button>
        <div className="min-w-0">
          <p className="text-xs font-semibold uppercase tracking-[0.12em] text-muted-foreground">Administration</p>
          <h1 className="truncate text-lg font-bold tracking-tight text-foreground">{title}</h1>
        </div>
      </div>
      <div className="flex items-center gap-2">
        <button
          onClick={toggleDark}
          className="rounded-md p-2 text-muted-foreground hover:bg-muted transition-colors hover:text-foreground"
          title={dark ? 'Passer en mode clair' : 'Passer en mode sombre'}
        >
          {dark ? <Sun size={16} /> : <Moon size={16} />}
        </button>
        <button
          onClick={logout}
          className="rounded-md p-2 text-muted-foreground hover:bg-muted transition-colors hover:text-foreground"
          title="Déconnexion"
        >
          <LogOut size={16} />
        </button>
      </div>
    </header>
  )
}
