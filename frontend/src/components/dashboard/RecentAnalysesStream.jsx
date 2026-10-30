import { Link } from 'react-router-dom'
import { GitPullRequest, ArrowRight, Clock, AlertCircle } from 'lucide-react'
import StatusBadge from '../ui/StatusBadge.jsx'

export default function RecentAnalysesStream({
  analyses = [],
  isLoading = false,
  error = null,
}) {
  return (
    <div className="bg-[#242424] border border-[#303030] rounded-lg overflow-hidden">
      {/* Section Header */}
      <div className="px-4 py-3 bg-[#1C1C1C] border-b border-[#303030] flex items-center justify-between">
        <div className="flex items-center gap-2">
          <GitPullRequest className="w-4 h-4 text-[#A78BFA]" />
          <h2 className="text-xs font-semibold uppercase tracking-wider text-[#D4D4D4]">
            Recent Pull Request Analyses ({analyses.length})
          </h2>
        </div>
        <Link
          to="/history"
          className="inline-flex items-center gap-1 text-xs text-[#858585] hover:text-[#A78BFA] transition-colors"
        >
          <span>View all history</span>
          <ArrowRight className="w-3.5 h-3.5" />
        </Link>
      </div>

      {/* Loading Skeleton */}
      {isLoading ? (
        <div className="p-6 space-y-3">
          {[1, 2].map((n) => (
            <div
              key={n}
              className="h-10 bg-[#1C1C1C]/60 rounded border border-[#2A2A2A] animate-pulse"
            />
          ))}
        </div>
      ) : error ? (
        /* Error Fallback */
        <div className="p-6 text-center text-xs text-[#858585] flex flex-col items-center justify-center">
          <AlertCircle className="w-5 h-5 text-[#F9BD22] mb-1.5" />
          <span>Could not retrieve recent analysis stream: {error}</span>
        </div>
      ) : analyses.length === 0 ? (
        /* Meaningful Empty State */
        <div className="py-12 px-4 flex flex-col items-center justify-center text-center">
          <div className="w-9 h-9 rounded-full bg-[#1C1C1C] border border-[#303030] flex items-center justify-center text-[#5A5A5A] mb-2.5">
            <Clock className="w-4 h-4" />
          </div>
          <div className="text-sm font-medium text-[#D4D4D4]">
            No Pull Request analyses recorded yet
          </div>
          <p className="text-xs text-[#858585] max-w-md mt-1">
            When GitHub push webhooks or manual PR impact analyses run against your connected repositories, recent reports and blast-radius telemetry will automatically appear here.
          </p>
        </div>
      ) : (
        /* Analyses Stream Table */
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse text-xs">
            <thead>
              <tr className="h-8 bg-[#1C1C1C]/75 border-b border-[#303030] text-[11px] uppercase tracking-wider text-[#858585] font-medium">
                <th className="px-4 py-1.5">Pull Request</th>
                <th className="px-4 py-1.5">Target Commit</th>
                <th className="px-4 py-1.5">Status</th>
                <th className="px-4 py-1.5 hidden sm:table-cell">Analyzed At</th>
                <th className="px-4 py-1.5 text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#282828]">
              {analyses.map((item) => (
                <tr key={item.id} className="h-11 hover:bg-[#2A2A2A]/50 transition-colors">
                  <td className="px-4 py-2 font-mono">
                    <span className="font-semibold text-[#D4D4D4]">
                      PR #{item.pullNumber}
                    </span>
                    <span className="text-[#525252] text-[11px] pl-2">
                      Repo #{item.repositoryId}
                    </span>
                  </td>
                  <td className="px-4 py-2 font-mono text-[#858585] text-[11px]">
                    {item.headCommitSha ? item.headCommitSha.substring(0, 7) : '—'}
                  </td>
                  <td className="px-4 py-2">
                    <StatusBadge status={item.status} />
                  </td>
                  <td className="px-4 py-2 hidden sm:table-cell text-[#858585] text-[11px]">
                    {item.completedAt
                      ? new Date(item.completedAt).toLocaleString()
                      : item.startedAt
                      ? new Date(item.startedAt).toLocaleString()
                      : 'Just now'}
                  </td>
                  <td className="px-4 py-2 text-right">
                    <Link
                      to={`/repositories/${item.repositoryId}/pulls/${item.pullNumber}`}
                      className="inline-flex items-center gap-1 text-[11px] text-[#A78BFA] hover:text-[#906EE8] font-medium"
                    >
                      <span>View Report</span>
                      <ArrowRight className="w-3 h-3" />
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
