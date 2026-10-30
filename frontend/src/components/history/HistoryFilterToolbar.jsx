import { Search, FolderGit2, RefreshCw } from 'lucide-react'

export default function HistoryFilterToolbar({
  repositories = [],
  selectedRepoId = '',
  onSelectRepository,
  searchTerm = '',
  onSearchChange,
  statusFilter = 'ALL',
  onStatusFilterChange,
  onRefresh,
  isLoading = false,
}) {
  return (
    <div className="bg-[#242424] border border-[#303030] rounded-lg p-3.5 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
      {/* Left: Repository Selector and Status Filter */}
      <div className="flex flex-wrap items-center gap-2.5 flex-1">
        {/* Repository Selector */}
        <div className="relative min-w-[200px] flex-1 sm:flex-initial">
          <div className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[#858585] pointer-events-none">
            <FolderGit2 className="w-3.5 h-3.5" />
          </div>
          <select
            value={selectedRepoId}
            onChange={(e) => onSelectRepository(e.target.value)}
            className="w-full pl-8 pr-3 py-1.5 bg-[#171717] border border-[#303030] focus:border-[#525252] rounded text-xs text-[#D4D4D4] font-mono outline-hidden transition-colors cursor-pointer"
          >
            {repositories.length === 0 ? (
              <option value="">No connected repositories</option>
            ) : (
              repositories.map((repo) => (
                <option key={repo.id} value={repo.id}>
                  {repo.owner}/{repo.name} (ID: {repo.id})
                </option>
              ))
            )}
          </select>
        </div>

        {/* Status Filter Dropdown */}
        <div className="relative min-w-[130px]">
          <select
            value={statusFilter}
            onChange={(e) => onStatusFilterChange(e.target.value)}
            className="w-full px-2.5 py-1.5 bg-[#171717] border border-[#303030] focus:border-[#525252] rounded text-xs text-[#D4D4D4] font-mono outline-hidden transition-colors cursor-pointer"
          >
            <option value="ALL">All Statuses</option>
            <option value="COMPLETED">Completed</option>
            <option value="RUNNING">Running / In Progress</option>
            <option value="FAILED">Failed</option>
          </select>
        </div>
      </div>

      {/* Right: Search Input & Refresh Trigger */}
      <div className="flex items-center gap-2">
        <div className="relative flex-1 sm:w-56">
          <Search className="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-[#5A5A5A]" />
          <input
            type="text"
            placeholder="Filter PR # or SHA..."
            value={searchTerm}
            onChange={(e) => onSearchChange(e.target.value)}
            className="w-full pl-8 pr-2.5 py-1.5 bg-[#171717] border border-[#303030] focus:border-[#525252] rounded text-xs text-[#D4D4D4] placeholder:text-[#525252] font-mono outline-hidden transition-colors"
          />
        </div>

        {onRefresh && (
          <button
            type="button"
            onClick={onRefresh}
            disabled={isLoading}
            className="p-1.5 bg-[#171717] hover:bg-[#2A2A2A] text-[#858585] hover:text-[#D4D4D4] border border-[#303030] rounded transition-colors disabled:opacity-50"
            title="Refresh history"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} />
          </button>
        )}
      </div>
    </div>
  )
}
