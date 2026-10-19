import { useState, useEffect, useCallback } from 'react'
import {
  LayoutDashboard,
  FolderGit2,
  GitPullRequest,
  History,
  Settings,
  Layers,
  Sparkles,
  Server,
  RefreshCw,
  X,
} from 'lucide-react'
import { fetchBackendStatus } from '../lib/api.js'

export default function Sidebar({
  activeNav,
  setActiveNav,
  isOpen,
  onClose,
  repoCount,
}) {
  const navItems = [
    { id: 'overview', label: 'Overview', icon: LayoutDashboard },
    {
      id: 'repositories',
      label: 'Repositories',
      icon: FolderGit2,
      badge: repoCount !== undefined ? String(repoCount) : null,
    },
    { id: 'pull-requests', label: 'Pull Requests', icon: GitPullRequest, badge: '12' },
    { id: 'analysis-history', label: 'Analysis History', icon: History },
    { id: 'settings', label: 'Settings', icon: Settings },
  ]
  const [backendState, setBackendState] = useState({
    status: 'checking', // 'checking' | 'online' | 'offline'
    data: null,
    error: null,
  })

  const checkStatus = useCallback(async () => {
    setBackendState((prev) => ({ ...prev, status: 'checking', error: null }))
    try {
      const data = await fetchBackendStatus()
      if (data && data.status === 'UP') {
        setBackendState({ status: 'online', data, error: null })
      } else {
        setBackendState({
          status: 'offline',
          data,
          error: data?.status ? `Status: ${data.status}` : 'Degraded',
        })
      }
    } catch (err) {
      setBackendState({
        status: 'offline',
        data: null,
        error: err.message || 'Offline',
      })
    }
  }, [])

  useEffect(() => {
    checkStatus()
    const timer = setInterval(checkStatus, 30000)
    return () => clearInterval(timer)
  }, [checkStatus])

  return (
    <>
      {/* Mobile backdrop */}
      {isOpen && (
        <div
          className="fixed inset-0 z-40 bg-slate-900/40 backdrop-blur-sm md:hidden"
          onClick={onClose}
          aria-hidden="true"
        />
      )}

      <aside
        className={`fixed top-0 bottom-0 left-0 z-50 w-64 bg-white border-r border-slate-200 flex flex-col transition-transform duration-200 ease-in-out md:translate-x-0 ${
          isOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        {/* Brand / Logo */}
        <div className="h-16 px-6 border-b border-slate-200 flex items-center justify-between">
          <div className="flex items-center space-x-2.5">
            <div className="w-9 h-9 rounded-lg bg-indigo-600 flex items-center justify-center text-white shadow-xs">
              <Layers className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center space-x-1.5">
                <span className="font-bold text-slate-900 tracking-tight text-base">ChangeScope</span>
                <span className="text-[10px] uppercase font-semibold px-1.5 py-0.2 rounded bg-indigo-50 text-indigo-600 border border-indigo-100">
                  DEV
                </span>
              </div>
              <p className="text-[11px] text-slate-500 font-medium">Java Impact Engine</p>
            </div>
          </div>

          <button
            onClick={onClose}
            className="md:hidden p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100"
            aria-label="Close sidebar"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Navigation list */}
        <div className="flex-1 px-3 py-4 space-y-1 overflow-y-auto">
          <div className="px-3 pb-2 text-[11px] font-semibold uppercase tracking-wider text-slate-400">
            Platform
          </div>
          {navItems.map((item) => {
            const Icon = item.icon
            const isActive = activeNav === item.id
            return (
              <button
                key={item.id}
                onClick={() => {
                  setActiveNav(item.id)
                  onClose()
                }}
                className={`w-full flex items-center justify-between px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-indigo-50 text-indigo-700 font-semibold'
                    : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100/80'
                }`}
              >
                <div className="flex items-center space-x-3">
                  <Icon
                    className={`w-4 h-4 ${
                      isActive ? 'text-indigo-600' : 'text-slate-400 group-hover:text-slate-600'
                    }`}
                  />
                  <span>{item.label}</span>
                </div>
                {item.badge && (
                  <span
                    className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                      isActive
                        ? 'bg-indigo-100 text-indigo-700'
                        : 'bg-slate-100 text-slate-500'
                    }`}
                  >
                    {item.badge}
                  </span>
                )}
              </button>
            )
          })}
        </div>

        {/* Bottom service status card connected to real backend */}
        <div className="p-3 border-t border-slate-200">
          <div className="p-3 rounded-lg bg-slate-50 border border-slate-200/80">
            <div className="flex items-center justify-between mb-1.5">
              <span className="text-[11px] font-semibold text-slate-600 flex items-center gap-1.5">
                <Server className="w-3.5 h-3.5 text-slate-400" />
                Backend Engine
              </span>

              {/* Status Badge */}
              {backendState.status === 'checking' && (
                <span className="inline-flex items-center px-1.5 py-0.5 rounded-full text-[10px] font-medium bg-amber-50 text-amber-700 border border-amber-200">
                  <span className="w-1.5 h-1.5 rounded-full bg-amber-500 mr-1 animate-pulse" />
                  Checking
                </span>
              )}

              {backendState.status === 'online' && (
                <span className="inline-flex items-center px-1.5 py-0.5 rounded-full text-[10px] font-medium bg-emerald-50 text-emerald-700 border border-emerald-200">
                  <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 mr-1 animate-pulse" />
                  Online
                </span>
              )}

              {backendState.status === 'offline' && (
                <button
                  onClick={checkStatus}
                  className="inline-flex items-center px-1.5 py-0.5 rounded-full text-[10px] font-medium bg-rose-50 text-rose-700 border border-rose-200 hover:bg-rose-100 transition-colors"
                  title="Click to retry"
                >
                  <RefreshCw className="w-2.5 h-2.5 mr-1" />
                  Unavailable
                </button>
              )}
            </div>

            {/* Subtext */}
            {backendState.status === 'checking' && (
              <p className="text-[11px] text-slate-500 leading-snug">
                Polling :8081/api/v1/status...
              </p>
            )}

            {backendState.status === 'online' && (
              <p className="text-[11px] text-slate-500 leading-snug truncate" title={`${backendState.data?.application} v${backendState.data?.version}`}>
                {backendState.data?.application || 'ChangeScope'} v{backendState.data?.version || '0.0.1'} &bull; Java 21
              </p>
            )}

            {backendState.status === 'offline' && (
              <div className="flex items-center justify-between mt-1">
                <p className="text-[11px] text-rose-600 leading-snug truncate" title={backendState.error}>
                  Port 8081 unreachable
                </p>
                <button
                  onClick={checkStatus}
                  className="text-[10px] text-indigo-600 hover:text-indigo-800 font-semibold underline ml-1 shrink-0"
                >
                  Retry
                </button>
              </div>
            )}
          </div>

          <div className="mt-3 px-2 flex items-center justify-between text-xs text-slate-400">
            <span className="flex items-center gap-1">
              <Sparkles className="w-3 h-3 text-indigo-500" /> AST v1.0.4
            </span>
            <span>REST API</span>
          </div>
        </div>
      </aside>
    </>
  )
}
