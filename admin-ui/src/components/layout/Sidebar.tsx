import { NavLink } from 'react-router-dom'
import {
  LayoutDashboard, Shield, FlaskConical, ScrollText,
  Server, Settings, Zap,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { useStatus } from '@/api/status'

const navItems = [
  { to: '/',              label: 'Dashboard',     icon: LayoutDashboard, end: true },
  { to: '/policies',      label: 'Politiques',    icon: Shield },
  { to: '/simulator',     label: 'Simulateur',    icon: FlaskConical },
  { to: '/audit',         label: 'Audit',         icon: ScrollText },
  { to: '/infrastructure',label: 'Infrastructure',icon: Server },
  { to: '/settings',      label: 'Paramètres',    icon: Settings },
]

export function Sidebar() {
  const { data: status } = useStatus()
  const healthy = status?.status === 'ok'

  return (
    <aside className="flex flex-col w-56 bg-autho-dark text-white shrink-0">
      {/* Brand */}
      <div className="flex items-center gap-2 px-4 py-5 border-b border-white/10">
        <Zap size={20} className="text-autho-blue" />
        <span className="text-lg font-bold tracking-tight">autho</span>
        <span
          className={cn(
            'ml-auto w-2 h-2 rounded-full',
            healthy ? 'bg-green-400' : 'bg-red-400',
          )}
          title={healthy ? 'Serveur opérationnel' : 'Serveur non disponible'}
        />
      </div>

      {/* Navigation */}
      <nav className="flex-1 py-4 px-2 space-y-1">
        {navItems.map(({ to, label, icon: Icon, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium transition-colors',
                isActive
                  ? 'bg-autho-blue/20 text-autho-blue'
                  : 'text-white/70 hover:bg-white/10 hover:text-white',
              )
            }
          >
            <Icon size={16} />
            {label}
          </NavLink>
        ))}
      </nav>

      {/* Version */}
      <div className="px-4 py-3 border-t border-white/10 text-xs text-white/40">
        {status?.version ?? '…'}
      </div>
    </aside>
  )
}
