import { useState, useEffect, useCallback, useRef } from 'react'
import PageHeader from '../components/layout/PageHeader.jsx'
import HistoryMetrics from '../components/history/HistoryMetrics.jsx'
import HistoryFilterToolbar from '../components/history/HistoryFilterToolbar.jsx'
import HistoryTable from '../components/history/HistoryTable.jsx'
import TriggerAnalysisModal from '../components/history/TriggerAnalysisModal.jsx'
import repositoryService from '../services/repositoryService.js'
import impactAnalysisService from '../services/impactAnalysisService.js'
import { Play, AlertTriangle } from 'lucide-react'

export default function AnalysisHistoryPage() {
  const [repositories, setRepositories] = useState([])
  const [selectedRepoId, setSelectedRepoId] = useState('')
  const [analyses, setAnalyses] = useState([])
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [currentPage, setCurrentPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)

  const [searchTerm, setSearchTerm] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')

  const [isLoadingRepos, setIsLoadingRepos] = useState(true)
  const [isLoadingAnalyses, setIsLoadingAnalyses] = useState(false)
  const [error, setError] = useState(null)
  const [isTriggerModalOpen, setIsTriggerModalOpen] = useState(false)

  const activeControllerRef = useRef(null)

  // 1. Load connected repositories
  const loadRepositories = useCallback(async () => {
    setIsLoadingRepos(true)
    setError(null)
    try {
      const repos = await repositoryService.getAllRepositories()
      setRepositories(repos)
      if (repos.length > 0 && !selectedRepoId) {
        setSelectedRepoId(String(repos[0].id))
      }
    } catch (err) {
      setError(
        err.formattedMessage ||
          'Failed to load repositories. Verify that the ChangeScope backend is running at http://localhost:8081.'
      )
    } finally {
      setIsLoadingRepos(false)
    }
  }, [selectedRepoId])

  useEffect(() => {
    loadRepositories()
  }, [loadRepositories])

  // 2. Load historical analyses for selected repository
  const loadAnalyses = useCallback(async () => {
    if (!selectedRepoId) {
      setAnalyses([])
      setTotalElements(0)
      setTotalPages(1)
      return
    }

    if (activeControllerRef.current) {
      activeControllerRef.current.abort()
    }
    const controller = new AbortController()
    activeControllerRef.current = controller

    setIsLoadingAnalyses(true)
    setError(null)

    try {
      const pageData = await impactAnalysisService.getRepositoryAnalyses(
        selectedRepoId,
        currentPage,
        pageSize,
        controller.signal
      )
      if (!controller.signal.aborted) {
        setAnalyses(pageData.content || [])
        setTotalElements(pageData.totalElements || 0)
        setTotalPages(pageData.totalPages || 1)
      }
    } catch (err) {
      if (
        err.isCanceled ||
        err.name === 'CanceledError' ||
        err.code === 'ERR_CANCELED' ||
        controller.signal.aborted
      ) {
        return
      }
      setError(
        err.formattedMessage ||
          `Failed to load analysis history for repository #${selectedRepoId}.`
      )
      setAnalyses([])
      setTotalElements(0)
    } finally {
      if (!controller.signal.aborted) {
        setIsLoadingAnalyses(false)
      }
    }
  }, [selectedRepoId, currentPage, pageSize])

  useEffect(() => {
    loadAnalyses()
    return () => {
      if (activeControllerRef.current) {
        activeControllerRef.current.abort()
      }
    }
  }, [loadAnalyses])

  // Handle repository selection change
  const handleSelectRepository = (newRepoId) => {
    setSelectedRepoId(newRepoId)
    setCurrentPage(0)
  }

  // Client-side filtering on current page for search and status
  const filteredAnalyses = analyses.filter((item) => {
    const matchesSearch =
      !searchTerm.trim() ||
      String(item.pullNumber).includes(searchTerm.trim()) ||
      (item.headCommitSha && item.headCommitSha.toLowerCase().includes(searchTerm.toLowerCase()))

    const matchesStatus =
      statusFilter === 'ALL' || String(item.status).toUpperCase() === statusFilter

    return matchesSearch && matchesStatus
  })

  // Calculate actual counts from currently loaded records
  const completedCount = analyses.filter((a) => a.status === 'COMPLETED').length
  const failedCount = analyses.filter((a) => a.status === 'FAILED').length

  const currentRepo = repositories.find((r) => String(r.id) === String(selectedRepoId))
  const repoName = currentRepo ? `${currentRepo.owner}/${currentRepo.name}` : ''

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <PageHeader
        title="Analysis History"
        subtitle="Review previous pull request impact analyses across your repositories."
        actions={
          <button
            type="button"
            onClick={() => setIsTriggerModalOpen(true)}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-[#A78BFA] hover:bg-[#906EE8] text-[#121212] font-semibold text-xs rounded transition-colors"
          >
            <Play className="w-3.5 h-3.5 fill-current" />
            <span>Analyze Pull Request</span>
          </button>
        }
      />

      {/* Error Alert */}
      {error && (
        <div className="p-4 bg-[#2A1818] border border-[#522525] rounded-lg text-xs text-[#FFB4AB] flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div className="flex items-start gap-2.5">
            <AlertTriangle className="w-4 h-4 text-[#FFB4AB] shrink-0 mt-0.5" />
            <div>
              <div className="font-semibold text-[#FFB4AB]">History Service Notice</div>
              <div className="text-[#D4D4D4]/80 mt-0.5">{error}</div>
            </div>
          </div>
          <button
            type="button"
            onClick={loadAnalyses}
            className="px-3 py-1.5 bg-[#1C1C1C] hover:bg-[#2A2A2A] text-[#D4D4D4] border border-[#383838] rounded text-xs font-medium self-start sm:self-auto shrink-0 transition-colors"
          >
            Retry Query
          </button>
        </div>
      )}

      {/* Summary Metrics */}
      <HistoryMetrics
        totalAnalyses={totalElements}
        completedCount={completedCount}
        failedCount={failedCount}
        isLoading={isLoadingAnalyses || isLoadingRepos}
        hasRepository={Boolean(selectedRepoId)}
      />

      {/* Search & Filter Toolbar */}
      <HistoryFilterToolbar
        repositories={repositories}
        selectedRepoId={selectedRepoId}
        onSelectRepository={handleSelectRepository}
        searchTerm={searchTerm}
        onSearchChange={setSearchTerm}
        statusFilter={statusFilter}
        onStatusFilterChange={setStatusFilter}
        onRefresh={loadAnalyses}
        isLoading={isLoadingAnalyses}
      />

      {/* History Data Table */}
      <HistoryTable
        analyses={filteredAnalyses}
        repositoryName={repoName}
        repositoryId={selectedRepoId}
        totalElements={totalElements}
        totalPages={totalPages}
        currentPage={currentPage}
        pageSize={pageSize}
        onPageChange={setCurrentPage}
        onPageSizeChange={(newSize) => {
          setPageSize(newSize)
          setCurrentPage(0)
        }}
        isLoading={isLoadingAnalyses}
        searchTerm={searchTerm}
      />

      {/* Trigger Analysis Modal */}
      <TriggerAnalysisModal
        isOpen={isTriggerModalOpen}
        onClose={() => setIsTriggerModalOpen(false)}
        repositories={repositories}
        initialRepoId={selectedRepoId}
      />
    </div>
  )
}
