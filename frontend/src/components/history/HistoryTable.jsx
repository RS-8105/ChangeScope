import { Link } from 'react-router-dom'
import { History, ArrowRight, ChevronLeft, ChevronRight } from 'lucide-react'
import StatusBadge from '../ui/StatusBadge.jsx'

export default function HistoryTable({
  analyses = [],
  repositoryName = '',
  repositoryId = '',
  totalElements = 0,
  totalPages = 1,
  currentPage = 0,
  pageSize = 20,
  onPageChange,
  onPageSizeChange,
  isLoading = false,
  searchTerm = '',
}) {
  const startIndex = totalElements === 0 ? 0 : currentPage * pageSize + 1
  const endIndex = Math.min((currentPage + 1) * pageSize, totalElements)

  return (
    <div className="bg-[#242424] border border-[#303030] rounded-lg overflow-hidden">
      {/* Table Content */}
      {isLoading ? (
        <div className="p-8 space-y-3">
          {[1, 2, 3, 4].map((n) => (
            <div
              key={n}
              className="h-12 bg-[#1C1C1C]/60 rounded border border-[#2A2A2A] animate-pulse flex items-center px-4 justify-between"
            >
              <div className="w-40 h-3.5 bg-[#303030] rounded" />
              <div className="w-24 h-3.5 bg-[#303030] rounded" />
              <div className="w-28 h-3.5 bg-[#303030] rounded" />
            </div>
          ))}
        </div>
      ) : analyses.length === 0 ? (
        /* Empty State */
        <div className="py-16 px-4 flex flex-col items-center justify-center text-center">
          <div className="w-10 h-10 rounded-full bg-[#1C1C1C] border border-[#303030] flex items-center justify-center text-[#5A5A5A] mb-3">
            <History className="w-5 h-5" />
          </div>
          <div className="text-sm font-medium text-[#D4D4D4]">
            {searchTerm
              ? `No analyses match "${searchTerm}"`
              : 'No analysis history recorded for this repository'}
          </div>
          <p className="text-xs text-[#858585] max-w-sm mt-1 mb-4">
            {searchTerm
              ? 'Try modifying your filter query to locate historical impact records.'
              : 'When pull requests are analyzed or GitHub webhooks are received, completed impact reports will be archived here.'}
          </p>
        </div>
      ) : (
        /* Data Table */
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse text-xs">
            <thead>
              <tr className="h-9 bg-[#1C1C1C] border-b border-[#303030] text-[11px] uppercase tracking-wider text-[#858585] font-medium">
                <th className="px-4 py-2">Pull Request</th>
                <th className="px-4 py-2">Repository</th>
                <th className="px-4 py-2">Status</th>
                <th className="px-4 py-2 hidden sm:table-cell">Target Commit</th>
                <th className="px-4 py-2 hidden md:table-cell">Analyzed At</th>
                <th className="px-4 py-2 text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#282828]">
              {analyses.map((item) => {
                const analyzedDate = item.completedAt || item.startedAt
                const formattedDate = analyzedDate
                  ? new Date(analyzedDate).toLocaleString()
                  : '—'

                return (
                  <tr
                    key={item.id}
                    className="h-12 hover:bg-[#2A2A2A]/50 transition-colors font-mono"
                  >
                    {/* Pull Request */}
                    <td className="px-4 py-2">
                      <div className="font-semibold text-[#D4D4D4] text-xs">
                        PR #{item.pullNumber}
                      </div>
                      <div className="text-[10px] text-[#525252] mt-0.5">
                        Analysis ID: {item.id}
                      </div>
                    </td>

                    {/* Repository */}
                    <td className="px-4 py-2 text-[#858585] text-xs">
                      {repositoryName || `Repo #${item.repositoryId}`}
                    </td>

                    {/* Status */}
                    <td className="px-4 py-2">
                      <StatusBadge status={item.status} />
                    </td>

                    {/* Target Commit Head */}
                    <td className="px-4 py-2 hidden sm:table-cell text-[#858585] text-[11px]">
                      {item.headCommitSha ? (
                        <span className="bg-[#1C1C1C] px-1.5 py-0.5 rounded border border-[#303030] text-[#D4D4D4]">
                          {item.headCommitSha.substring(0, 7)}
                        </span>
                      ) : (
                        '—'
                      )}
                    </td>

                    {/* Analyzed At */}
                    <td className="px-4 py-2 hidden md:table-cell text-[#858585] text-[11px] font-sans">
                      {formattedDate}
                    </td>

                    {/* Action */}
                    <td className="px-4 py-2 text-right">
                      <Link
                        to={`/repositories/${item.repositoryId || repositoryId}/pulls/${item.pullNumber}?analysisId=${item.id}`}
                        className="inline-flex items-center gap-1 text-[11px] text-[#A78BFA] hover:text-[#906EE8] font-medium font-sans px-2 py-1 rounded hover:bg-[#A78BFA]/10 transition-colors"
                      >
                        <span>View Report</span>
                        <ArrowRight className="w-3 h-3" />
                      </Link>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Pagination Footer */}
      {totalElements > 0 && (
        <div className="px-4 py-3 bg-[#1C1C1C] border-t border-[#303030] flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 text-xs">
          <div className="text-[11px] text-[#858585]">
            Showing <span className="font-mono text-[#D4D4D4]">{startIndex}</span> to{' '}
            <span className="font-mono text-[#D4D4D4]">{endIndex}</span> of{' '}
            <span className="font-mono text-[#D4D4D4]">{totalElements}</span> analyses
          </div>

          <div className="flex items-center gap-3">
            {/* Page Size Selector */}
            <div className="flex items-center gap-1.5 text-[11px] text-[#858585]">
              <span>Rows:</span>
              <select
                value={pageSize}
                onChange={(e) => onPageSizeChange && onPageSizeChange(Number(e.target.value))}
                className="bg-[#171717] border border-[#303030] rounded px-1.5 py-0.5 text-xs text-[#D4D4D4] font-mono outline-hidden"
              >
                <option value={10}>10</option>
                <option value={20}>20</option>
                <option value={50}>50</option>
              </select>
            </div>

            {/* Pagination Controls */}
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => onPageChange && onPageChange(currentPage - 1)}
                disabled={currentPage === 0 || isLoading}
                className="p-1 rounded bg-[#171717] hover:bg-[#2A2A2A] border border-[#303030] text-[#858585] hover:text-[#D4D4D4] disabled:opacity-40 transition-colors"
                title="Previous page"
              >
                <ChevronLeft className="w-3.5 h-3.5" />
              </button>

              <span className="text-[11px] font-mono text-[#858585] px-1">
                {currentPage + 1} / {Math.max(totalPages, 1)}
              </span>

              <button
                type="button"
                onClick={() => onPageChange && onPageChange(currentPage + 1)}
                disabled={currentPage >= totalPages - 1 || isLoading}
                className="p-1 rounded bg-[#171717] hover:bg-[#2A2A2A] border border-[#303030] text-[#858585] hover:text-[#D4D4D4] disabled:opacity-40 transition-colors"
                title="Next page"
              >
                <ChevronRight className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
