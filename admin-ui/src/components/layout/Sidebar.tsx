import { useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import {
  LayoutDashboard, Shield, FlaskConical, ScrollText,
  Server, Settings, Zap, ChevronDown, Database, GitBranch,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { useStatus } from '@/api/status'
import { usePolicies } from '@/api/policies'

const navItems = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/simulator', label: 'Simulateur', icon: FlaskConical },
  { to: '/audit', label: 'Audit', icon: ScrollText },
  { to: '/pip-data', label: 'Données PIP', icon: Database },
  { to: '/relations', label: 'Relations', icon: GitBranch },
  { to: '/infrastructure', label: 'Infrastructure', icon: Server },
  { to: '/settings', label: 'Paramètres', icon: Settings },
]

export function Sidebar({ mobileOpen, onNavigate }: { mobileOpen: boolean; onNavigate: () => void }) {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const { data: status } = useStatus()
  const { data: policies } = usePolicies()
  const healthy = status?.status === 'ok'
  const [policiesOpen, setPoliciesOpen] = useState(true)

  const policyClasses = policies ? Object.keys(policies).sort() : []
  const inPolicies = pathname === '/policies' || pathname.startsWith('/policies/')

  return (
    <aside className={cn('fixed inset-y-0 left-0 z-40 flex w-72 shrink-0 flex-col bg-autho-dark text-white shadow-xl shadow-slate-950/20 transition-transform duration-200 lg:static lg:w-64 lg:translate-x-0', mobileOpen ? 'translate-x-0' : '-translate-x-full')}>
      <div className="flex items-center gap-3 border-b border-white/10 px-5 py-5">
        <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-autho-blue/15"><Zap size={19} className="text-autho-blue" /></span>
        <span className="text-xl font-bold tracking-tight">autho</span>
        <span
          className={cn(
            'ml-auto w-2 h-2 rounded-full',
            healthy ? 'bg-green-400' : 'bg-red-400',
          )}
          title={healthy ? 'Serveur opérationnel' : 'Serveur non disponible'}
        />
      </div>

      <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-5">
        <NavLink
          to="/"
          end
          onClick={onNavigate}
          className={({ isActive }) =>
            cn(
              'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
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
              'flex items-center rounded-lg transition-colors',
              inPolicies ? 'bg-autho-blue/20 text-autho-blue' : 'text-white/70 hover:bg-white/10 hover:text-white',
            )}
          >
            <button
              onClick={() => { navigate('/policies'); onNavigate() }}
              className="flex flex-1 items-center gap-3 px-3 py-2.5 text-left text-sm font-medium"
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
                    onClick={() => { navigate(`/policies/${resourceClass}`); onNavigate() }}
                    className={cn(
                      'w-full rounded-md px-3 py-2 text-left text-sm font-medium transition-colors',
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
            onClick={onNavigate}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
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

      <div className="border-t border-white/10 px-5 py-4 text-xs text-white/50">
        {status?.version ?? '…'}
      </div>
    </aside>
  )
}
