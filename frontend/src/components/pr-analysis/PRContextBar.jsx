import { Link } from 'react-router-dom'
import { Network, RefreshCw, ArrowLeft } from 'lucide-react'

export default function PRContextBar({
  repositoryId,
  pullNumber,
  metadata,
  isAnalyzing,
  onReanalyze,
}) {
  const pr = metadata || {}
  const repoName = pr.repo ? `${pr.owner}/${pr.repo}` : `Repository #${repositoryId}`
  const isOpen = pr.state ? String(pr.state).toUpperCase() === 'OPEN' : true

  return (
    <div className="bg-[#242424] border border-[#303030] rounded-lg p-5 space-y-4">
      {/* Top Row: Navigation Breadcrumb / Repo link and Actions */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 pb-3 border-b border-[#303030]">
        <div className="flex items-center gap-2 text-xs">
          <Link
            to="/"
            className="inline-flex items-center gap-1.5 text-[#858585] hover:text-[#D4D4D4] transition-colors"
          >
            <ArrowLeft className="w-3.5 h-3.5" />
            <span>Overview</span>
          </Link>
          <span className="text-[#525252]">/</span>
          <span className="font-mono text-[#A78BFA] font-medium">{repoName}</span>
          <span className="text-[#525252]">/</span>
          <span className="font-mono text-[#D4D4D4]">PR #{pullNumber}</span>
        </div>

        {/* Action Triggers */}
        <div className="flex items-center gap-2.5">
          <Link
            to={`/repositories/${repositoryId}/pulls/${pullNumber}/graph`}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-[#1C1C1C] hover:bg-[#2A2A2A] text-[#D4D4D4] border border-[#303030] hover:border-[#525252] text-xs font-medium rounded transition-colors"
          >
            <Network className="w-3.5 h-3.5 text-[#A78BFA]" />
            <span>View Dependency Graph</span>
          </Link>
          <button
            type="button"
            onClick={onReanalyze}
            disabled={isAnalyzing}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-[#A78BFA] hover:bg-[#906EE8] text-[#121212] font-semibold text-xs rounded transition-colors disabled:opacity-50"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isAnalyzing ? 'animate-spin' : ''}`} />
            <span>{isAnalyzing ? 'Analyzing...' : 'Re-analyze'}</span>
          </button>
        </div>
      </div>

      {/* Main PR Title & Meta */}
      <div>
        <div className="flex flex-wrap items-center gap-2 mb-1.5">
          <span
            className={`inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono font-medium border ${
              isOpen
                ? 'bg-[#45DFA4]/10 border-[#45DFA4]/25 text-[#45DFA4]'
                : 'bg-[#737373]/10 border-[#737373]/25 text-[#858585]'
            }`}
          >
            ● {isOpen ? 'Open for Review' : 'Closed / Merged'}
          </span>
          <h1 className="text-base sm:text-lg font-semibold text-[#D4D4D4] tracking-tight font-sans">
            {pr.title ? `PR #${pullNumber} — ${pr.title}` : `Pull Request #${pullNumber}`}
          </h1>
        </div>

        {/* Revision Revisions Pill */}
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-[#858585] font-mono mt-2">
          {pr.baseCommitSha && (
            <div className="flex items-center gap-1.5">
              <span className="text-[#525252]">Base:</span>
              <span className="text-[#D4D4D4] bg-[#1C1C1C] px-1.5 py-0.5 rounded border border-[#303030]">
                {pr.baseCommitSha.substring(0, 7)}
              </span>
            </div>
          )}

          {pr.headCommitSha && (
            <div className="flex items-center gap-1.5">
              <span className="text-[#525252]">Head:</span>
              <span className="text-[#D4D4D4] bg-[#1C1C1C] px-1.5 py-0.5 rounded border border-[#303030]">
                {pr.headCommitSha.substring(0, 7)}
              </span>
            </div>
          )}

          <div className="flex items-center gap-1 text-[11px] text-[#525252]">
            <span>AST Semantic Analysis Engine</span>
          </div>
        </div>
      </div>
    </div>
  )
}
