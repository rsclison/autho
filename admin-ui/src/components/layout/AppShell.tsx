import { useState } from 'react'
import { Outlet } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { Header } from './Header'

export function AppShell() {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  return (
    <div className="flex h-screen overflow-hidden bg-background">
      {sidebarOpen ? <button aria-label="Fermer le menu" className="fixed inset-0 z-30 bg-slate-950/45 lg:hidden" onClick={() => setSidebarOpen(false)} /> : null}
      <Sidebar mobileOpen={sidebarOpen} onNavigate={() => setSidebarOpen(false)} />
      <div className="flex flex-col flex-1 overflow-hidden">
        <Header onOpenMenu={() => setSidebarOpen(true)} />
        <main className="flex-1 overflow-y-auto px-4 py-5 sm:px-6 lg:px-8 lg:py-7">
          <div className="app-page">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}
