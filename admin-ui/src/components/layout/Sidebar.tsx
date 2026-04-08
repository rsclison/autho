import { useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import {
  LayoutDashboard, Shield, FlaskConical, ScrollText,
  Server, Settings, Zap, ChevronDown,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { useStatus } from '@/api/status'
import { usePolicies } from '@/api/policies'

const navItems = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/simulator', label: 'Simulateur', icon: FlaskConical },
  { to: '/audit', label: 'Audit', icon: ScrollText },
  { to: '/infrastructure', label: 'Infrastructure', icon: Server },
  { to: '/settings', label: 'Paramètres', icon: Settings },
]

export function Sidebar() {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const { data: status } = useStatus()
  const { data: policies } = usePolicies()
  const healthy = status?.status === 'ok'
  const [policiesOpen, setPoliciesOpen] = useState(true)

  const policyClasses = policies ? Object.keys(policies).sort() : []
  const inPolicies = pathname === '/policies' || pathname.startsWith('/policies/')

  return (
    <aside className="flex flex-col w-56 bg-autho-dark text-white shrink-0">
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

      <nav className="flex-1 py-4 px-2 space-y-1 overflow-y-auto">
        <NavLink
          to="/"
          end
          className={({ isActive }) =>
            cn(
              'flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium transition-colors',
              isActive
                ? 'bg-autho-blue/20 text-autho-blue'
                : 'text-white/70 hover:bg-white/10 hover:text-white',
            )
          }
        >
          <LayoutDashboard size={16} />
          Dashboard
        </NavLink>

        <div className="space-y-1">
          <div
            className={cn(
              'flex items-center rounded-md transition-colors',
              inPolicies ? 'bg-autho-blue/20 text-autho-blue' : 'text-white/70 hover:bg-white/10 hover:text-white',
            )}
          >
            <button
              onClick={() => navigate('/policies')}
              className="flex flex-1 items-center gap-3 px-3 py-2 text-left text-sm font-medium"
            >
              <Shield size={16} />
              Politiques
            </button>
            <button
              onClick={() => setPoliciesOpen((open) => !open)}
              className="mr-2 rounded p-1 hover:bg-white/10 transition-colors"
              aria-label={policiesOpen ? 'Replier la liste des politiques' : 'Déplier la liste des politiques'}
            >
              <ChevronDown size={14} className={cn('transition-transform', policiesOpen ? 'rotate-0' : '-rotate-90')} />
            </button>
          </div>

          {policiesOpen && policyClasses.length > 0 ? (
            <div className="ml-4 space-y-1 border-l border-white/10 pl-3">
              {policyClasses.map((resourceClass) => {
                const active = pathname === `/policies/${resourceClass}` || pathname.startsWith(`/policies/${resourceClass}/`)
                return (
                  <button
                    key={resourceClass}
                    onClick={() => navigate(`/policies/${resourceClass}`)}
                    className={cn(
                      'w-full rounded-md px-3 py-1.5 text-left text-xs font-medium transition-colors',
                      active
                        ? 'bg-white text-autho-dark'
                        : 'text-white/60 hover:bg-white/10 hover:text-white',
                    )}
                  >
                    {resourceClass}
                  </button>
                )
              })}
            </div>
          ) : null}
        </div>

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

      <div className="px-4 py-3 border-t border-white/10 text-xs text-white/40">
        {status?.version ?? '…'}
      </div>
    </aside>
  )
}
