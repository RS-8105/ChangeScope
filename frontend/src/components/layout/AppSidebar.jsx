import { NavLink } from 'react-router-dom'
import {
  LayoutDashboard,
  GitPullRequest,
  Network,
  History,
  Layers,
  Terminal,
  Database,
} from 'lucide-react'

const navItems = [
  {
    to: '/',
    label: 'Dashboard',
    icon: LayoutDashboard,
    end: true,
  },
  {
    to: '/history',
    label: 'Analysis History',
    icon: History,
  },
  {
    to: '/repositories/1/pulls/142',
    label: 'PR Analysis (Demo)',
    icon: GitPullRequest,
  },
  {
    to: '/repositories/1/pulls/142/graph',
    label: 'Dependency Graph (Demo)',
    icon: Network,
  },
]

export default function AppSidebar() {
  return (
    <aside className="w-[240px] h-screen bg-[#242424] border-r border-[#303030] flex flex-col shrink-0 select-none">
      {/* Brand & Workspace Identity */}
      <div className="h-[52px] px-4 border-b border-[#303030] flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 rounded bg-[#A78BFA]/15 border border-[#A78BFA]/30 flex items-center justify-center text-[#A78BFA]">
            <Layers className="w-4 h-4" />
          </div>
          <div>
            <div className="text-sm font-semibold text-[#EDEDED] tracking-tight leading-none">
              ChangeScope
            </div>
            <div className="text-[10px] font-mono text-[#858585] mt-1 flex items-center gap-1">
              <span>AST Engine</span>
              <span className="text-[#525252]">•</span>
              <span className="text-[#45DFA4]">v1.0</span>
            </div>
          </div>
        </div>
      </div>

      {/* Navigation Links */}
      <div className="flex-1 py-4 px-2 space-y-1 overflow-y-auto">
        <div className="px-2 pb-2 text-[10px] font-semibold uppercase tracking-wider text-[#5A5A5A]">
          Platform Navigation
        </div>
        {navItems.map((item) => {
          const Icon = item.icon
          return (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                `flex items-center gap-2.5 px-3 py-2 text-xs font-medium rounded-md transition-colors ${
                  isActive
                    ? 'text-[#A78BFA] bg-[#A78BFA]/10 border-l-2 border-[#A78BFA]'
                    : 'text-[#858585] hover:text-[#D4D4D4] hover:bg-[#2A2A2A]'
                }`
              }
            >
              <Icon className="w-4 h-4 shrink-0" />
              <span className="truncate">{item.label}</span>
            </NavLink>
          )
        })}

        <div className="pt-4 px-2 pb-2 text-[10px] font-semibold uppercase tracking-wider text-[#5A5A5A]">
          Settings
        </div>
        <NavLink
          to="/settings/data-management"
          className={({ isActive }) =>
            `flex items-center gap-2.5 px-3 py-2 text-xs font-medium rounded-md transition-colors ${
              isActive
                ? 'text-[#A78BFA] bg-[#A78BFA]/10 border-l-2 border-[#A78BFA]'
                : 'text-[#858585] hover:text-[#D4D4D4] hover:bg-[#2A2A2A]'
            }`
          }
        >
          <Database className="w-4 h-4 shrink-0" />
          <span className="truncate">Data Management</span>
        </NavLink>
      </div>

      {/* Engine Telemetry & Environment Footer */}
      <div className="p-3 border-t border-[#303030] bg-[#1E1E1E]">
        <div className="flex items-center justify-between text-[11px] font-mono text-[#858585]">
          <span className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-[#45DFA4] animate-pulse" />
            Backend Active
          </span>
          <span className="text-[#525252]">8081</span>
        </div>
        <div className="mt-2 pt-2 border-t border-[#2A2A2A] flex items-center gap-2 text-[11px] text-[#858585]">
          <Terminal className="w-3.5 h-3.5 text-[#A78BFA]" />
          <span className="truncate">Spring Boot 3.3 • Java 21</span>
        </div>
      </div>
    </aside>
  )
}
