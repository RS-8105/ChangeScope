import {
  Menu,
  Bell,
  Search,
  Plus,
  ChevronDown,
  GitFork,
  ExternalLink,
} from 'lucide-react'

export default function Header({
  pageTitle,
  repositories,
  selectedRepo,
  setSelectedRepo,
  onOpenAnalyzeModal,
  onOpenMobileMenu,
}) {
  return (
    <header className="h-16 bg-white border-b border-slate-200 sticky top-0 z-30 px-4 md:px-8 flex items-center justify-between">
      {/* Left: Mobile toggle + Page title */}
      <div className="flex items-center space-x-3">
        <button
          onClick={onOpenMobileMenu}
          className="md:hidden p-2 rounded-lg text-slate-500 hover:text-slate-700 hover:bg-slate-100"
          aria-label="Open menu"
        >
          <Menu className="w-5 h-5" />
        </button>

        <div>
          <h1 className="text-lg md:text-xl font-bold text-slate-900 tracking-tight">
            {pageTitle}
          </h1>
        </div>
      </div>

      {/* Right controls: Repo selector, Notifications, Primary Action, Profile */}
      <div className="flex items-center space-x-3">
        {/* Repo selector */}
        <div className="relative hidden sm:block">
          <div className="flex items-center space-x-2 px-3 py-1.5 rounded-lg border border-slate-200 bg-slate-50/70 hover:bg-slate-100 transition-colors cursor-pointer text-xs font-medium text-slate-700">
            <GitFork className="w-3.5 h-3.5 text-slate-400" />
            <select
              value={selectedRepo}
              onChange={(e) => setSelectedRepo(e.target.value)}
              className="bg-transparent border-none outline-none text-xs font-medium text-slate-700 cursor-pointer pr-4 appearance-none"
            >
              {repositories.map((repo) => (
                <option key={repo.id} value={repo.id}>
                  {repo.fullName || repo.name}
                </option>
              ))}
            </select>
            <ChevronDown className="w-3.5 h-3.5 text-slate-400 pointer-events-none absolute right-2.5" />
          </div>
        </div>

        {/* Primary Action Button */}
        <button
          onClick={onOpenAnalyzeModal}
          className="inline-flex items-center space-x-1.5 px-3.5 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold shadow-xs transition-all active:scale-98"
        >
          <Plus className="w-4 h-4" />
          <span>Analyze PR</span>
        </button>

        {/* Notifications */}
        <button
          className="relative p-2 rounded-lg text-slate-500 hover:text-slate-800 hover:bg-slate-100 transition-colors"
          title="Notifications"
          aria-label="View notifications"
        >
          <Bell className="w-4 h-4" />
          <span className="absolute top-1.5 right-1.5 w-2 h-2 rounded-full bg-indigo-600 ring-2 ring-white" />
        </button>

        <div className="h-5 w-px bg-slate-200 hidden sm:block" />

        {/* User avatar */}
        <div className="flex items-center space-x-2">
          <div className="relative">
            <img
              src="https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100&auto=format&fit=crop&q=80"
              alt="User avatar"
              className="w-8 h-8 rounded-full object-cover ring-1 ring-slate-200"
            />
            <span className="absolute bottom-0 right-0 w-2.5 h-2.5 rounded-full bg-emerald-500 ring-1.5 ring-white" />
          </div>
          <div className="hidden lg:block text-left">
            <p className="text-xs font-semibold text-slate-800 leading-none">Rushi P.</p>
            <p className="text-[10px] text-slate-500 leading-none mt-1">Lead Architect</p>
          </div>
        </div>
      </div>
    </header>
  )
}
