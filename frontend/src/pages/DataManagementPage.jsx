import { useState, useEffect, useCallback } from 'react'
import { Link } from 'react-router-dom'
import PageHeader from '../components/layout/PageHeader.jsx'
import RetentionPolicyCard from '../components/data-management/RetentionPolicyCard.jsx'
import ConfirmActionModal from '../components/data-management/ConfirmActionModal.jsx'
import repositoryService from '../services/repositoryService.js'
import {
  Database,
  History,
  Layers,
  Trash2,
  AlertTriangle,
  CheckCircle2,
  GitBranch,
  RefreshCw,
  FolderGit2,
  ExternalLink,
  ChevronDown,
  X,
} from 'lucide-react'

export default function DataManagementPage() {
  const [repositories, setRepositories] = useState([])
  const [selectedRepoId, setSelectedRepoId] = useState('')
  const [isLoading, setIsLoading] = useState(true)
  const [pageError, setPageError] = useState(null)
  const [successBanner, setSuccessBanner] = useState(null)

  // Confirmation modal state
  const [modalConfig, setModalConfig] = useState({
    isOpen: false,
    type: null, // 'CLEAR_ANALYSES' | 'DELETE_INDEX' | 'DELETE_REPO'
    repo: null,
    isLoading: false,
    error: null,
  })

  // 1. Fetch connected repositories
  const loadRepositories = useCallback(async (preferredRepoId = null) => {
    setIsLoading(true)
    setPageError(null)
    try {
      const data = await repositoryService.getAllRepositories()
      setRepositories(data)

      if (data.length > 0) {
        setSelectedRepoId((prev) => {
          if (preferredRepoId && data.some((r) => String(r.id) === String(preferredRepoId))) {
            return String(preferredRepoId)
          }
          if (prev && data.some((r) => String(r.id) === String(prev))) {
            return prev
          }
          return String(data[0].id)
        })
      } else {
        setSelectedRepoId('')
      }
    } catch (err) {
      setPageError(
        err.formattedMessage ||
          'Failed to load repositories from ChangeScope backend. Verify connection to http://localhost:8081.'
      )
    } finally {
      setIsLoading(false)
    }
  }, [])

  useEffect(() => {
    loadRepositories()
  }, [loadRepositories])

  const selectedRepo = repositories.find((r) => String(r.id) === String(selectedRepoId)) || null

  // 2. Open confirmation modal helpers
  const promptClearAnalyses = (repo) => {
    if (!repo) return
    setModalConfig({
      isOpen: true,
      type: 'CLEAR_ANALYSES',
      repo,
      isLoading: false,
      error: null,
    })
  }

  const promptDeleteIndex = (repo) => {
    if (!repo) return
    setModalConfig({
      isOpen: true,
      type: 'DELETE_INDEX',
      repo,
      isLoading: false,
      error: null,
    })
  }

  const promptDeleteRepository = (repo) => {
    if (!repo) return
    setModalConfig({
      isOpen: true,
      type: 'DELETE_REPO',
      repo,
      isLoading: false,
      error: null,
    })
  }

  const closeModal = () => {
    if (modalConfig.isLoading) return
    setModalConfig({
      isOpen: false,
      type: null,
      repo: null,
      isLoading: false,
      error: null,
    })
  }

  // 3. Execute confirmed action
  const handleModalConfirm = async () => {
    const { type, repo } = modalConfig
    if (!repo || !type) return

    setModalConfig((prev) => ({ ...prev, isLoading: true, error: null }))

    try {
      if (type === 'CLEAR_ANALYSES') {
        const result = await repositoryService.deleteRepositoryAnalyses(repo.id)
        const count = result?.deletedCount ?? result?.count ?? 0
        closeModal()
        setSuccessBanner({
          title: 'Analysis History Cleared',
          message: `Successfully deleted ${count} analysis record(s) for ${repo.fullName || repo.name}. Stored dependency index and repository record remain intact.`,
        })
      } else if (type === 'DELETE_INDEX') {
        await repositoryService.deleteRepositoryIndex(repo.id)
        closeModal()
        setSuccessBanner({
          title: 'Dependency Index Deleted',
          message: `Stored AST dependency graphs, indexed types, and dependencies for ${repo.fullName || repo.name} have been purged. Last indexed commit reset to null.`,
        })
        // Refresh repositories to show updated lastIndexedCommit
        await loadRepositories(repo.id)
      } else if (type === 'DELETE_REPO') {
        await repositoryService.deleteRepository(repo.id)
        closeModal()
        setSuccessBanner({
          title: 'Repository Deleted',
          message: `Repository ${repo.fullName || repo.name} and all associated analyses and dependency indexes have been permanently removed.`,
        })
        // Refresh repositories list, switching selected repo safely
        const remaining = repositories.filter((r) => r.id !== repo.id)
        const nextId = remaining.length > 0 ? remaining[0].id : null
        await loadRepositories(nextId)
      }
    } catch (err) {
      setModalConfig((prev) => ({
        ...prev,
        isLoading: false,
        error:
          err.formattedMessage ||
          'Operation failed. Ensure the repository exists and backend is accessible.',
      }))
    }
  }

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <PageHeader
        title="Data Management"
        subtitle="Review automated data retention policies and manage repository-scoped manual data deletions."
        tags={
          <span className="px-2.5 py-0.5 rounded text-[11px] font-mono bg-[#A78BFA]/10 border border-[#A78BFA]/30 text-[#A78BFA] flex items-center gap-1.5">
            <Database className="w-3.5 h-3.5" />
            Retention & Lifecycle
          </span>
        }
        actions={
          <button
            type="button"
            onClick={() => loadRepositories(selectedRepoId)}
            disabled={isLoading}
            className="flex items-center gap-2 px-3 py-1.5 text-xs font-medium bg-[#242424] hover:bg-[#2A2A2A] border border-[#303030] rounded-md text-[#D4D4D4] transition-colors disabled:opacity-50"
          >
            <RefreshCw className={`w-3.5 h-3.5 text-[#858585] ${isLoading ? 'animate-spin' : ''}`} />
            <span>Refresh</span>
          </button>
        }
      />

      {/* Dismissible Feedback Banner */}
      {successBanner && (
        <div className="bg-[#1C2C24] border border-[#45DFA4]/40 rounded-lg p-4 flex items-start justify-between gap-3 animate-in fade-in duration-200">
          <div className="flex items-start gap-3">
            <CheckCircle2 className="w-5 h-5 text-[#45DFA4] shrink-0 mt-0.5" />
            <div>
              <h3 className="text-xs font-semibold text-[#EDEDED]">{successBanner.title}</h3>
              <p className="text-xs text-[#A3D9C9] mt-0.5 leading-relaxed">{successBanner.message}</p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => setSuccessBanner(null)}
            className="text-[#858585] hover:text-[#D4D4D4] p-1 rounded hover:bg-[#2A2A2A] transition-colors"
            aria-label="Dismiss banner"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {/* Global Page Error */}
      {pageError && (
        <div className="bg-[#3D1414] border border-[#FFB4AB]/40 rounded-lg p-4 flex items-start justify-between gap-3">
          <div className="flex items-start gap-3">
            <AlertTriangle className="w-5 h-5 text-[#FFB4AB] shrink-0 mt-0.5" />
            <div>
              <h3 className="text-xs font-semibold text-[#FFB4AB]">Data Management Unavailable</h3>
              <p className="text-xs text-[#E5B5B0] mt-0.5 leading-relaxed">{pageError}</p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => loadRepositories(selectedRepoId)}
            className="px-2.5 py-1 text-xs font-medium text-[#FFB4AB] bg-[#FFB4AB]/10 hover:bg-[#FFB4AB]/20 rounded border border-[#FFB4AB]/30 transition-colors"
          >
            Retry
          </button>
        </div>
      )}

      {/* Section 1: Automated Retention Information */}
      <RetentionPolicyCard />

      {/* Section 2: Repository-Scoped Data Cleanup */}
      <div className="bg-[#242424] border border-[#303030] rounded-lg overflow-hidden shadow-sm">
        <div className="px-5 py-4 border-b border-[#303030] bg-[#1C1C1C] flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-[#D4D4D4] flex items-center gap-2">
              <FolderGit2 className="w-4 h-4 text-[#A78BFA]" />
              Repository-Scoped Data Operations
            </h2>
            <p className="text-[11px] text-[#858585] mt-0.5">
              Select a repository to selectively clear its pull request analysis history or purge its stored AST dependency graph.
            </p>
          </div>

          {/* Repository Selector Dropdown */}
          {repositories.length > 0 && (
            <div className="relative min-w-[220px]">
              <select
                id="scoped-repository-select"
                aria-label="Select connected repository"
                value={selectedRepoId}
                onChange={(e) => setSelectedRepoId(e.target.value)}
                className="w-full appearance-none bg-[#171717] border border-[#303030] focus:border-[#A78BFA] text-[#D4D4D4] text-xs rounded-md pl-3 pr-8 py-1.5 focus:outline-none transition-colors font-mono cursor-pointer"
              >
                {repositories.map((repo) => (
                  <option key={repo.id} value={String(repo.id)}>
                    {repo.fullName || `${repo.owner}/${repo.name}`}
                  </option>
                ))}
              </select>
              <ChevronDown className="w-4 h-4 text-[#858585] absolute right-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
            </div>
          )}
        </div>

        {repositories.length === 0 && !isLoading ? (
          <div className="p-8 text-center">
            <FolderGit2 className="w-8 h-8 text-[#5A5A5A] mx-auto mb-2" />
            <div className="text-xs font-medium text-[#D4D4D4]">No connected repositories</div>
            <p className="text-[11px] text-[#858585] mt-1 max-w-sm mx-auto">
              Connect a GitHub repository on the Dashboard to inspect AST dependency graphs and manage data retention.
            </p>
            <Link
              to="/"
              className="inline-flex items-center gap-1.5 px-3 py-1.5 mt-3 text-xs font-medium text-[#A78BFA] bg-[#A78BFA]/10 hover:bg-[#A78BFA]/20 border border-[#A78BFA]/30 rounded transition-colors"
            >
              Go to Dashboard
              <ExternalLink className="w-3.5 h-3.5" />
            </Link>
          </div>
        ) : (
          <div className="p-5 space-y-4">
            {/* Repository Info Strip */}
            {selectedRepo && (
              <div className="bg-[#1C1C1C] border border-[#2D2D2D] rounded-md px-4 py-3 flex flex-wrap items-center justify-between gap-3 text-xs">
                <div className="flex items-center gap-3">
                  <span className="font-semibold text-[#EDEDED]">{selectedRepo.fullName || selectedRepo.name}</span>
                  <span className="text-[#525252]">•</span>
                  <span className="text-[#858585] flex items-center gap-1 font-mono text-[11px]">
                    <GitBranch className="w-3.5 h-3.5 text-[#A78BFA]" />
                    {selectedRepo.defaultBranch || 'main'}
                  </span>
                </div>
                <div className="flex items-center gap-4 text-[11px] font-mono">
                  <div>
                    <span className="text-[#5A5A5A]">Last Indexed Commit: </span>
                    {selectedRepo.lastIndexedCommit ? (
                      <span className="text-[#45DFA4] bg-[#45DFA4]/10 px-1.5 py-0.5 rounded border border-[#45DFA4]/20">
                        {selectedRepo.lastIndexedCommit.substring(0, 8)}
                      </span>
                    ) : (
                      <span className="text-[#858585] italic">Not indexed</span>
                    )}
                  </div>
                </div>
              </div>
            )}

            {/* Scoped Actions Grid */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {/* Action 1: Clear Analysis History */}
              <div className="bg-[#1C1C1C] border border-[#2D2D2D] rounded-md p-4 flex flex-col justify-between hover:border-[#383838] transition-colors">
                <div>
                  <div className="flex items-center gap-2 mb-2">
                    <div className="w-6 h-6 rounded bg-[#F9BD22]/15 text-[#F9BD22] flex items-center justify-center">
                      <History className="w-3.5 h-3.5" />
                    </div>
                    <h3 className="text-xs font-semibold text-[#EDEDED]">Clear Analysis History</h3>
                  </div>
                  <p className="text-[11px] text-[#858585] leading-relaxed">
                    Purges all completed, failed, and running pull request analysis snapshots for{' '}
                    <span className="font-medium text-[#D4D4D4]">{selectedRepo?.fullName || 'this repository'}</span>.
                    The repository record and stored AST dependency index remain completely intact.
                  </p>
                </div>

                <div className="mt-4 pt-3 border-t border-[#262626] flex items-center justify-between">
                  <span className="text-[10px] text-[#5A5A5A] font-mono">DELETE /analyses</span>
                  <button
                    type="button"
                    onClick={() => promptClearAnalyses(selectedRepo)}
                    disabled={!selectedRepo || isLoading}
                    className="px-3 py-1.5 text-xs font-medium text-[#FFB4AB] hover:text-[#171717] bg-[#FFB4AB]/10 hover:bg-[#FFB4AB] border border-[#FFB4AB]/30 rounded transition-colors disabled:opacity-50"
                  >
                    Clear Analysis History
                  </button>
                </div>
              </div>

              {/* Action 2: Delete Dependency Index */}
              <div className="bg-[#1C1C1C] border border-[#2D2D2D] rounded-md p-4 flex flex-col justify-between hover:border-[#383838] transition-colors">
                <div>
                  <div className="flex items-center gap-2 mb-2">
                    <div className="w-6 h-6 rounded bg-[#A78BFA]/15 text-[#A78BFA] flex items-center justify-center">
                      <Layers className="w-3.5 h-3.5" />
                    </div>
                    <h3 className="text-xs font-semibold text-[#EDEDED]">Delete Dependency Index</h3>
                  </div>
                  <p className="text-[11px] text-[#858585] leading-relaxed">
                    Purges stored AST graph snapshots, indexed classes, interfaces, and dependency edges for{' '}
                    <span className="font-medium text-[#D4D4D4]">{selectedRepo?.fullName || 'this repository'}</span>,
                    and resets the last-indexed commit. Analysis history remains completely intact.
                  </p>
                </div>

                <div className="mt-4 pt-3 border-t border-[#262626] flex items-center justify-between">
                  <span className="text-[10px] text-[#5A5A5A] font-mono">DELETE /index</span>
                  <button
                    type="button"
                    onClick={() => promptDeleteIndex(selectedRepo)}
                    disabled={!selectedRepo || isLoading}
                    className="px-3 py-1.5 text-xs font-medium text-[#FFB4AB] hover:text-[#171717] bg-[#FFB4AB]/10 hover:bg-[#FFB4AB] border border-[#FFB4AB]/30 rounded transition-colors disabled:opacity-50"
                  >
                    Delete Dependency Index
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Section 3: Repository Deletion (Full Cascade) */}
      <div className="bg-[#242424] border border-[#303030] rounded-lg overflow-hidden shadow-sm">
        <div className="px-5 py-4 border-b border-[#303030] bg-[#1C1C1C]">
          <h2 className="text-sm font-semibold text-[#D4D4D4] flex items-center gap-2">
            <Trash2 className="w-4 h-4 text-[#FFB4AB]" />
            Connected Repository Deletion
          </h2>
          <p className="text-[11px] text-[#858585] mt-0.5">
            Permanently disconnect and delete a repository along with all associated pull request analyses, reports, and dependency indexes.
          </p>
        </div>

        <div className="p-5">
          {repositories.length === 0 && !isLoading ? (
            <div className="text-xs text-[#858585] text-center py-4">No connected repositories to delete.</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left border-collapse text-xs">
                <thead>
                  <tr className="border-b border-[#303030] text-[11px] font-semibold text-[#858585] uppercase tracking-wider">
                    <th className="py-2.5 px-3">Repository</th>
                    <th className="py-2.5 px-3">Default Branch</th>
                    <th className="py-2.5 px-3">Index State</th>
                    <th className="py-2.5 px-3 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#2A2A2A]">
                  {repositories.map((repo) => (
                    <tr key={repo.id} className="hover:bg-[#282828] transition-colors">
                      <td className="py-3 px-3">
                        <div className="font-semibold text-[#EDEDED]">{repo.fullName || `${repo.owner}/${repo.name}`}</div>
                        <div className="text-[10px] text-[#858585] font-mono mt-0.5">ID: {repo.id}</div>
                      </td>
                      <td className="py-3 px-3 font-mono text-[11px] text-[#A78BFA]">
                        <span className="flex items-center gap-1">
                          <GitBranch className="w-3 h-3 text-[#858585]" />
                          {repo.defaultBranch || 'main'}
                        </span>
                      </td>
                      <td className="py-3 px-3 font-mono text-[11px]">
                        {repo.lastIndexedCommit ? (
                          <span className="text-[#45DFA4] bg-[#45DFA4]/10 px-2 py-0.5 rounded border border-[#45DFA4]/20">
                            {repo.lastIndexedCommit.substring(0, 8)}
                          </span>
                        ) : (
                          <span className="text-[#858585] italic">Not indexed</span>
                        )}
                      </td>
                      <td className="py-3 px-3 text-right">
                        <button
                          type="button"
                          onClick={() => promptDeleteRepository(repo)}
                          disabled={isLoading}
                          className="inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium text-[#FFB4AB] hover:text-[#171717] bg-[#FFB4AB]/10 hover:bg-[#FFB4AB] border border-[#FFB4AB]/30 rounded transition-colors disabled:opacity-50"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                          <span>Delete</span>
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Confirmation Modal Safeguard */}
      <ConfirmActionModal
        isOpen={modalConfig.isOpen}
        onClose={closeModal}
        onConfirm={handleModalConfirm}
        isLoading={modalConfig.isLoading}
        error={modalConfig.error}
        isDestructive={true}
        title={
          modalConfig.type === 'CLEAR_ANALYSES'
            ? 'Clear Analysis History'
            : modalConfig.type === 'DELETE_INDEX'
            ? 'Delete Stored Dependency Index'
            : 'Permanently Delete Repository'
        }
        targetName={modalConfig.repo?.fullName || modalConfig.repo?.name}
        description={
          modalConfig.type === 'CLEAR_ANALYSES'
            ? 'Are you sure you want to delete all historical pull request analyses for this repository? Active analyses will also be terminated.'
            : modalConfig.type === 'DELETE_INDEX'
            ? 'Are you sure you want to delete the AST dependency graph index for this repository?'
            : 'Are you sure you want to permanently delete this repository and all related records? This action is irreversible.'
        }
        impactPoints={
          modalConfig.type === 'CLEAR_ANALYSES'
            ? [
                'All PR impact reports and verification metrics for this repo will be permanently removed.',
                'The repository record and stored dependency index will remain intact.',
                'New analyses can be triggered at any time.',
              ]
            : modalConfig.type === 'DELETE_INDEX'
            ? [
                'All stored AST classes, interfaces, and dependency edges will be purged from the database.',
                'The repository last-indexed commit SHA will be reset to null.',
                'Pull request analysis history and repository settings will remain intact.',
              ]
            : [
                'The repository record and all configuration mappings will be deleted.',
                'All stored pull request analyses and historical reports will be deleted.',
                'All stored AST dependency indexes, types, and edges will be purged.',
                'This repository will be completely removed from ChangeScope.',
              ]
        }
        confirmButtonText={
          modalConfig.type === 'CLEAR_ANALYSES'
            ? 'Yes, Clear Analysis History'
            : modalConfig.type === 'DELETE_INDEX'
            ? 'Yes, Delete Dependency Index'
            : 'Permanently Delete Repository'
        }
        confirmTextToType={
          modalConfig.type === 'DELETE_REPO'
            ? modalConfig.repo?.name || modalConfig.repo?.fullName
            : null
        }
      />
    </div>
  )
}
