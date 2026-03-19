import { useLocation } from 'react-router-dom'
import { Moon, Sun, LogOut } from 'lucide-react'
import { useState } from 'react'
import { getDarkMode, setDarkMode, clearToken } from '@/lib/auth'
import { useNavigate } from 'react-router-dom'

const labels: Record<string, string> = {
  '/':               'Dashboard',
  '/policies':       'Politiques',
  '/simulator':      'Simulateur',
  '/audit':          'Journal d\'audit',
  '/infrastructure': 'Infrastructure',
  '/settings':       'Paramètres',
}

export function Header() {
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
    <header className="flex items-center justify-between px-6 h-14 border-b border-border bg-card shrink-0">
      <h1 className="text-base font-semibold text-foreground">{title}</h1>
      <div className="flex items-center gap-2">
        <button
          onClick={toggleDark}
          className="p-2 rounded-md hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
          title={dark ? 'Passer en mode clair' : 'Passer en mode sombre'}
        >
          {dark ? <Sun size={16} /> : <Moon size={16} />}
        </button>
        <button
          onClick={logout}
          className="p-2 rounded-md hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
          title="Déconnexion"
        >
          <LogOut size={16} />
        </button>
      </div>
    </header>
  )
}
