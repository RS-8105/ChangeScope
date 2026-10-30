import { useState, useEffect, useCallback } from 'react'
import PageHeader from '../components/layout/PageHeader.jsx'
import MetricCard from '../components/ui/MetricCard.jsx'
import RepositoryTable from '../components/repository/RepositoryTable.jsx'
import ConnectRepoModal from '../components/repository/ConnectRepoModal.jsx'
import RecentAnalysesStream from '../components/dashboard/RecentAnalysesStream.jsx'
import repositoryService from '../services/repositoryService.js'
import {
  FolderGit2,
  GitPullRequest,
  ShieldAlert,
  CheckCircle2,
  Plus,
  RefreshCw,
  AlertTriangle,
} from 'lucide-react'

export default function DashboardPage() {
  const [repositories, setRepositories] = useState([])
  const [recentAnalyses, setRecentAnalyses] = useState([])
  const [isLoadingRepos, setIsLoadingRepos] = useState(true)
  const [isLoadingAnalyses, setIsLoadingAnalyses] = useState(false)
  const [reposError, setReposError] = useState(null)
  const [isConnectModalOpen, setIsConnectModalOpen] = useState(false)
  const [successToast, setSuccessToast] = useState(null)

  // Fetch recent analyses for connected repositories
  const loadRecentAnalyses = useCallback(async (repoList) => {
    setIsLoadingAnalyses(true)
    try {
      const analysesPromises = repoList.slice(0, 3).map(async (repo) => {
        try {
          const res = await repositoryService.getRepositoryAnalyses(repo.id, 0, 5)
          return res.content || []
        } catch {
          return []
        }
      })

      const results = await Promise.all(analysesPromises)
      const combined = results.flat().sort((a, b) => {
        const dateA = new Date(b.completedAt || b.startedAt || 0)
        const dateB = new Date(a.completedAt || a.startedAt || 0)
        return dateA - dateB
      })

      setRecentAnalyses(combined.slice(0, 5))
    } catch {
      setRecentAnalyses([])
    } finally {
      setIsLoadingAnalyses(false)
    }
  }, [])

  // Load repositories from real backend API
  const loadRepositories = useCallback(async () => {
    setIsLoadingRepos(true)
    setReposError(null)
    try {
      const data = await repositoryService.getAllRepositories()
      const list = Array.isArray(data) ? data : []
      setRepositories(list)

      // If repositories exist, load recent analyses across the first few repos
      if (list.length > 0) {
        loadRecentAnalyses(list)
      } else {
        setRecentAnalyses([])
      }
    } catch (err) {
      setReposError(
        err.formattedMessage ||
          'Failed to connect to ChangeScope backend (http://localhost:8081). Please ensure the backend is running.'
      )
      setRepositories([])
    } finally {
      setIsLoadingRepos(false)
    }
  }, [loadRecentAnalyses])

  useEffect(() => {
    loadRepositories()
  }, [loadRepositories])

  // Handle repository deletion
  const handleDeleteRepository = async (id) => {
    await repositoryService.deleteRepository(id)
    setSuccessToast('Repository deleted successfully.')
    setTimeout(() => setSuccessToast(null), 3000)
    await loadRepositories()
  }

  // Handle newly registered repository
  const handleRepositoryCreated = (newRepo) => {
    setSuccessToast(`Repository ${newRepo.owner}/${newRepo.name} connected successfully!`)
    setTimeout(() => setSuccessToast(null), 3000)
    loadRepositories()
  }

  // Calculate actual metrics from live backend data
  const connectedReposCount = repositories.length
  const indexedReposCount = repositories.filter((r) => Boolean(r.lastIndexedCommit)).length
  const indexingRatePercentage =
    connectedReposCount > 0
      ? `${Math.round((indexedReposCount / connectedReposCount) * 100)}%`
      : '—'

  const analyzedPrsCount = recentAnalyses.length > 0 ? String(recentAnalyses.length) : '0'

  return (
    <div className="space-y-6">
      {/* View Header */}
      <PageHeader
        title="Overview"
        subtitle="Monitor repositories and understand the impact of code changes."
        actions={
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={loadRepositories}
              disabled={isLoadingRepos}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-[#242424] hover:bg-[#2A2A2A] text-[#D4D4D4] border border-[#303030] text-xs font-medium rounded transition-colors disabled:opacity-50"
              title="Refresh repository list"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${isLoadingRepos ? 'animate-spin' : ''}`} />
              <span className="hidden sm:inline">Refresh</span>
            </button>
            <button
              type="button"
              onClick={() => setIsConnectModalOpen(true)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-[#A78BFA] hover:bg-[#906EE8] text-[#121212] font-semibold text-xs rounded transition-colors"
            >
              <Plus className="w-4 h-4" />
              <span>Connect Repository</span>
            </button>
          </div>
        }
      />

      {/* Success Notification Banner */}
      {successToast && (
        <div className="p-3 bg-[#45DFA4]/10 border border-[#45DFA4]/30 rounded text-xs text-[#45DFA4] flex items-center justify-between animate-in fade-in">
          <div className="flex items-center gap-2">
            <CheckCircle2 className="w-4 h-4 shrink-0" />
            <span>{successToast}</span>
          </div>
          <button
            type="button"
            onClick={() => setSuccessToast(null)}
            className="text-[#858585] hover:text-[#D4D4D4] text-xs"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Backend API Connection Error Banner */}
      {reposError && (
        <div className="p-4 bg-[#2A1818] border border-[#522525] rounded-lg text-xs text-[#FFB4AB] flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div className="flex items-start gap-2.5">
            <AlertTriangle className="w-4 h-4 text-[#FFB4AB] shrink-0 mt-0.5" />
            <div>
              <div className="font-semibold text-[#FFB4AB]">Backend Connection Notice</div>
              <div className="text-[#D4D4D4]/80 mt-0.5">{reposError}</div>
            </div>
          </div>
          <button
            type="button"
            onClick={loadRepositories}
            className="px-3 py-1.5 bg-[#1C1C1C] hover:bg-[#2A2A2A] text-[#D4D4D4] border border-[#383838] rounded text-xs font-medium self-start sm:self-auto shrink-0 transition-colors"
          >
            Retry Connection
          </button>
        </div>
      )}

      {/* 4-Metric Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Metric 1: Connected Repositories */}
        <MetricCard
          label="Connected Repositories"
          value={isLoadingRepos ? '—' : String(connectedReposCount)}
          subtext={
            connectedReposCount === 1
              ? '1 repository in PostgreSQL'
              : `${connectedReposCount} repositories in PostgreSQL`
          }
          icon={FolderGit2}
          isUnavailable={isLoadingRepos}
        />

        {/* Metric 2: Repositories Indexed */}
        <MetricCard
          label="Repositories Indexed"
          value={isLoadingRepos ? '—' : indexingRatePercentage}
          subtext={
            connectedReposCount > 0
              ? `${indexedReposCount} of ${connectedReposCount} indexed`
              : 'Requires connected repositories'
          }
          icon={CheckCircle2}
          isUnavailable={isLoadingRepos || connectedReposCount === 0}
        />

        {/* Metric 3: Pull Requests Analyzed */}
        <MetricCard
          label="Pull Requests Analyzed"
          value={isLoadingRepos || isLoadingAnalyses ? '—' : analyzedPrsCount}
          subtext={
            recentAnalyses.length > 0
              ? 'Derived from analysis history'
              : 'Awaiting PR webhook triggers'
          }
          icon={GitPullRequest}
          isUnavailable={recentAnalyses.length === 0}
        />

        {/* Metric 4: High-Risk Changes (Honest unavailable state) */}
        <MetricCard
          label="High-Risk Changes"
          value="—"
          subtext="Available upon PR report inspection"
          icon={ShieldAlert}
          isUnavailable={true}
        />
      </div>

      {/* Repository Management Section */}
      <RepositoryTable
        repositories={repositories}
        isLoading={isLoadingRepos}
        onDeleteRepository={handleDeleteRepository}
        onOpenConnectModal={() => setIsConnectModalOpen(true)}
      />

      {/* Recent Analyses Stream Section */}
      <RecentAnalysesStream
        analyses={recentAnalyses}
        isLoading={isLoadingAnalyses}
      />

      {/* Connect Repository Modal */}
      <ConnectRepoModal
        isOpen={isConnectModalOpen}
        onClose={() => setIsConnectModalOpen(false)}
        onRepositoryCreated={handleRepositoryCreated}
      />
    </div>
  )
}
