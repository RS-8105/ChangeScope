import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  FolderGit2,
  GitBranch,
  Trash2,
  Search,
  GitPullRequest,
  Check,
  Copy,
} from 'lucide-react'
import StatusBadge from '../ui/StatusBadge.jsx'

export default function RepositoryTable({
  repositories = [],
  isLoading = false,
  onDeleteRepository,
  onOpenConnectModal,
}) {
  const [searchTerm, setSearchTerm] = useState('')
  const [deletingId, setDeletingId] = useState(null)
  const [confirmDeleteId, setConfirmDeleteId] = useState(null)
  const [copiedSha, setCopiedSha] = useState(null)

  const filteredRepos = repositories.filter((repo) => {
    if (!searchTerm.trim()) return true
    const term = searchTerm.toLowerCase()
    return (
      (repo.name && repo.name.toLowerCase().includes(term)) ||
      (repo.owner && repo.owner.toLowerCase().includes(term)) ||
      (repo.fullName && repo.fullName.toLowerCase().includes(term))
    )
  })

  const handleDelete = async (id) => {
    if (onDeleteRepository) {
      setDeletingId(id)
      try {
        await onDeleteRepository(id)
      } finally {
        setDeletingId(null)
        setConfirmDeleteId(null)
      }
    }
  }

  const handleCopySha = (sha) => {
    if (!sha) return
    navigator.clipboard.writeText(sha)
    setCopiedSha(sha)
    setTimeout(() => setCopiedSha(null), 1500)
  }

  return (
    <div className="bg-[#242424] border border-[#303030] rounded-lg overflow-hidden">
      {/* Table Header Toolbar */}
      <div className="px-4 py-3 bg-[#1C1C1C] border-b border-[#303030] flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div className="flex items-center gap-2">
          <FolderGit2 className="w-4 h-4 text-[#A78BFA]" />
          <h2 className="text-xs font-semibold uppercase tracking-wider text-[#D4D4D4]">
            Connected Repositories ({repositories.length})
          </h2>
        </div>

        {/* Search Bar */}
        <div className="relative w-full sm:w-64">
          <Search className="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-[#5A5A5A]" />
          <input
            type="text"
            placeholder="Filter repositories..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="w-full pl-8 pr-2.5 py-1 bg-[#171717] border border-[#303030] focus:border-[#525252] rounded text-xs text-[#D4D4D4] placeholder:text-[#525252] font-mono outline-hidden transition-colors"
          />
        </div>
      </div>

      {/* Loading Skeleton */}
      {isLoading ? (
        <div className="p-8 space-y-3">
          {[1, 2, 3].map((n) => (
            <div
              key={n}
              className="h-12 bg-[#1C1C1C]/60 rounded border border-[#2A2A2A] animate-pulse flex items-center px-4 justify-between"
            >
              <div className="w-48 h-3.5 bg-[#303030] rounded" />
              <div className="w-20 h-3.5 bg-[#303030] rounded hidden sm:block" />
              <div className="w-24 h-3.5 bg-[#303030] rounded" />
            </div>
          ))}
        </div>
      ) : filteredRepos.length === 0 ? (
        /* Empty State */
        <div className="py-14 px-4 flex flex-col items-center justify-center text-center">
          <div className="w-10 h-10 rounded-full bg-[#1C1C1C] border border-[#303030] flex items-center justify-center text-[#5A5A5A] mb-3">
            <FolderGit2 className="w-5 h-5" />
          </div>
          <div className="text-sm font-medium text-[#D4D4D4]">
            {searchTerm ? 'No matching repositories found' : 'No repositories connected yet'}
          </div>
          <p className="text-xs text-[#858585] max-w-sm mt-1 mb-4">
            {searchTerm
              ? `No repository records match "${searchTerm}". Clear the search query to show all connected repositories.`
              : 'Connect your first GitHub repository to enable Java AST parsing, dependency graph snapshots, and PR blast-radius analysis.'}
          </p>
          {searchTerm ? (
            <button
              type="button"
              onClick={() => setSearchTerm('')}
              className="px-3 py-1.5 bg-[#1C1C1C] hover:bg-[#2A2A2A] text-[#D4D4D4] border border-[#303030] text-xs font-medium rounded transition-colors"
            >
              Clear Filter
            </button>
          ) : (
            onOpenConnectModal && (
              <button
                type="button"
                onClick={onOpenConnectModal}
                className="px-3 py-1.5 bg-[#A78BFA] hover:bg-[#906EE8] text-[#121212] text-xs font-semibold rounded transition-colors"
              >
                Connect First Repository
              </button>
            )
          )}
        </div>
      ) : (
        /* Data Table */
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse text-xs">
            <thead>
              <tr className="h-9 bg-[#1C1C1C]/75 border-b border-[#303030] text-[11px] uppercase tracking-wider text-[#858585] font-medium">
                <th className="px-4 py-2">Repository</th>
                <th className="px-4 py-2 hidden md:table-cell">Default Branch</th>
                <th className="px-4 py-2">Indexing Status</th>
                <th className="px-4 py-2 hidden lg:table-cell">Last Indexed Commit</th>
                <th className="px-4 py-2 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#282828]">
              {filteredRepos.map((repo) => {
                const isIndexed = Boolean(repo.lastIndexedCommit)
                const isConfirming = confirmDeleteId === repo.id
                const isDeleting = deletingId === repo.id

                return (
                  <tr
                    key={repo.id}
                    className="h-12 hover:bg-[#2A2A2A]/50 transition-colors group"
                  >
                    {/* Repository Name & Owner */}
                    <td className="px-4 py-2 font-mono">
                      <div className="flex items-center gap-2">
                        <span className="text-[#858585]">{repo.owner}/</span>
                        <span className="font-semibold text-[#D4D4D4] text-xs">
                          {repo.name}
                        </span>
                      </div>
                      <div className="text-[10px] text-[#525252] font-mono mt-0.5">
                        ID: {repo.id} {repo.installationId && `• App Inst: ${repo.installationId}`}
                      </div>
                    </td>

                    {/* Default Branch */}
                    <td className="px-4 py-2 hidden md:table-cell font-mono">
                      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 bg-[#1C1C1C] border border-[#303030] rounded text-[11px] text-[#858585]">
                        <GitBranch className="w-3 h-3 text-[#A78BFA]" />
                        {repo.defaultBranch || 'main'}
                      </span>
                    </td>

                    {/* Indexing Status */}
                    <td className="px-4 py-2">
                      <StatusBadge
                        status={isIndexed ? 'INDEXED' : 'UNINDEXED'}
                        commitSha={repo.lastIndexedCommit}
                      />
                    </td>

                    {/* Last Indexed Commit */}
                    <td className="px-4 py-2 hidden lg:table-cell font-mono">
                      {repo.lastIndexedCommit ? (
                        <button
                          type="button"
                          onClick={() => handleCopySha(repo.lastIndexedCommit)}
                          className="inline-flex items-center gap-1.5 px-2 py-0.5 bg-[#1C1C1C] hover:bg-[#2A2A2A] border border-[#303030] rounded text-[11px] text-[#D4D4D4] transition-colors"
                          title="Click to copy full commit SHA"
                        >
                          <span>{repo.lastIndexedCommit.substring(0, 7)}</span>
                          {copiedSha === repo.lastIndexedCommit ? (
                            <Check className="w-3 h-3 text-[#45DFA4]" />
                          ) : (
                            <Copy className="w-3 h-3 text-[#5A5A5A]" />
                          )}
                        </button>
                      ) : (
                        <span className="text-[#5A5A5A] text-[11px]">
                          Pending push event
                        </span>
                      )}
                    </td>

                    {/* Actions */}
                    <td className="px-4 py-2 text-right">
                      {isConfirming ? (
                        <div className="inline-flex items-center gap-2">
                          <span className="text-[10px] text-[#FFB4AB] hidden sm:inline">
                            Delete repo #{repo.id}?
                          </span>
                          <button
                            type="button"
                            onClick={() => handleDelete(repo.id)}
                            disabled={isDeleting}
                            className="px-2 py-1 bg-[#2A1818] hover:bg-[#381E1E] text-[#FFB4AB] border border-[#522525] rounded text-[11px] font-semibold transition-colors disabled:opacity-50"
                          >
                            {isDeleting ? 'Deleting...' : 'Confirm'}
                          </button>
                          <button
                            type="button"
                            onClick={() => setConfirmDeleteId(null)}
                            disabled={isDeleting}
                            className="px-2 py-1 bg-[#1C1C1C] text-[#858585] hover:text-[#D4D4D4] rounded text-[11px] transition-colors"
                          >
                            Cancel
                          </button>
                        </div>
                      ) : (
                        <div className="inline-flex items-center gap-1">
                          <Link
                            to={`/repositories/${repo.id}/pulls/1`}
                            className="p-1.5 text-[#858585] hover:text-[#A78BFA] hover:bg-[#2A2A2A] rounded transition-colors"
                            title="View PR Impact Analysis"
                          >
                            <GitPullRequest className="w-3.5 h-3.5" />
                          </Link>
                          <button
                            type="button"
                            onClick={() => setConfirmDeleteId(repo.id)}
                            className="p-1.5 text-[#858585] hover:text-[#FFB4AB] hover:bg-[#2A2A2A] rounded transition-colors"
                            title="Delete repository"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
