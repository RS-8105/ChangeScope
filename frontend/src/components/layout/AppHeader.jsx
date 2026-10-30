import { useLocation, Link } from 'react-router-dom'
import { Search } from 'lucide-react'

export default function AppHeader() {
  const location = useLocation()
  const pathnames = location.pathname.split('/').filter(Boolean)

  const buildBreadcrumbs = () => {
    if (pathnames.length === 0) {
      return [{ label: 'Overview', to: '/' }]
    }

    const crumbs = [{ label: 'Overview', to: '/' }]

    if (pathnames[0] === 'history') {
      crumbs.push({ label: 'Analysis History', to: '/history' })
    } else if (pathnames[0] === 'repositories') {
      const repoId = pathnames[1] || '1'
      crumbs.push({ label: `Repository #${repoId}`, to: '/' })

      if (pathnames[2] === 'pulls' && pathnames[3]) {
        const prNumber = pathnames[3]
        crumbs.push({
          label: `PR #${prNumber}`,
          to: `/repositories/${repoId}/pulls/${prNumber}`,
        })

        if (pathnames[4] === 'graph') {
          crumbs.push({
            label: 'Dependency Graph',
            to: `/repositories/${repoId}/pulls/${prNumber}/graph`,
          })
        }
      }
    } else {
      crumbs.push({ label: pathnames.join(' / '), to: location.pathname })
    }

    return crumbs
  }

  const breadcrumbs = buildBreadcrumbs()

  return (
    <header className="h-[52px] bg-[#171717] border-b border-[#303030] px-6 flex items-center justify-between shrink-0">
      {/* Breadcrumbs */}
      <nav aria-label="Breadcrumb" className="flex items-center gap-1.5 text-xs">
        {breadcrumbs.map((crumb, idx) => {
          const isLast = idx === breadcrumbs.length - 1
          return (
            <div key={crumb.to + idx} className="flex items-center gap-1.5">
              {idx > 0 && <span className="text-[#525252]">/</span>}
              {isLast ? (
                <span className="text-[#D4D4D4] font-medium font-mono">{crumb.label}</span>
              ) : (
                <Link
                  to={crumb.to}
                  className="text-[#858585] hover:text-[#D4D4D4] transition-colors"
                >
                  {crumb.label}
                </Link>
              )}
            </div>
          )
        })}
      </nav>

      {/* Right Telemetry & Quick Action */}
      <div className="flex items-center gap-3">
        {/* Engine Telemetry Pill */}
        <div className="hidden sm:flex items-center gap-2 px-2.5 py-1 bg-[#1C1C1C] border border-[#303030] rounded text-[11px] font-mono text-[#858585]">
          <span className="w-1.5 h-1.5 rounded-full bg-[#45DFA4] animate-pulse" />
          <span>AST_ENGINE</span>
          <span className="text-[#525252]">/</span>
          <span className="text-[#D4D4D4]">jdk-21-lts:indexed</span>
        </div>

        {/* Global Search trigger shortcut */}
        <button
          type="button"
          className="flex items-center gap-2 px-2.5 py-1 bg-[#1C1C1C] border border-[#303030] hover:border-[#525252] rounded text-xs text-[#858585] transition-colors"
        >
          <Search className="w-3.5 h-3.5" />
          <span className="hidden md:inline">Quick jump</span>
          <kbd className="px-1 py-0.2 bg-[#2A2A2A] border border-[#383838] rounded text-[10px] text-[#A78BFA] font-mono">
            ⌘K
          </kbd>
        </button>
      </div>
    </header>
  )
}
