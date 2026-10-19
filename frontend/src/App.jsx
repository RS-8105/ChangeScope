import { useState, useEffect, useCallback } from 'react'
import Sidebar from './components/Sidebar.jsx'
import Header from './components/Header.jsx'
import Overview from './components/Overview.jsx'
import RepositoriesView from './components/RepositoriesView.jsx'
import AnalyzeModal from './components/AnalyzeModal.jsx'
import {
  fetchRepositories,
  createRepository,
  deleteRepository,
} from './lib/api.js'
import {
  mockSummaryStats,
  mockPullRequests,
  mockActivities,
} from './data/mockData.js'
import { GitPullRequest, History, Settings, ArrowLeft } from 'lucide-react'

export default function App() {
  const [activeNav, setActiveNav] = useState('overview')
  const [selectedRepo, setSelectedRepo] = useState('all')
  const [isModalOpen, setIsModalOpen] = useState(false)
  const [isMobileSidebarOpen, setIsMobileSidebarOpen] = useState(false)
  const [pullRequests, setPullRequests] = useState(mockPullRequests)
  const [lastImpactReport, setLastImpactReport] = useState(null)

  // Real backend repository state
  const [repositories, setRepositories] = useState([])
  const [isLoadingRepos, setIsLoadingRepos] = useState(false)
  const [reposError, setReposError] = useState(null)

  const loadRepositories = useCallback(async () => {
    setIsLoadingRepos(true)
    setReposError(null)
    try {
      const data = await fetchRepositories()
      const list = Array.isArray(data) ? data : []
      setRepositories(list)
      // Reset selectedRepo if it was deleted or is no longer present
      setSelectedRepo((current) => {
        if (current === 'all') return 'all'
        const exists = list.some(
          (r) =>
            r.name === current ||
            r.fullName === current ||
            String(r.id) === current
        )
        return exists ? current : 'all'
      })
    } catch (err) {
      setReposError(err.message || 'Failed to connect to repository API')
      setRepositories([])
    } finally {
      setIsLoadingRepos(false)
    }
  }, [])

  useEffect(() => {
    loadRepositories()
  }, [loadRepositories])

  const handleCreateRepository = async (repoData) => {
    await createRepository(repoData)
    await loadRepositories()
  }

  const handleDeleteRepository = async (id) => {
    const target = repositories.find((r) => r.id === id)
    await deleteRepository(id)
    await loadRepositories()
    if (
      target &&
      (selectedRepo === String(target.id) ||
        selectedRepo === target.name ||
        selectedRepo === target.fullName)
    ) {
      setSelectedRepo('all')
    }
  }

  const handleReportGenerated = (report) => {
    setLastImpactReport(report)
  }

  // Repository selector options: All option + real database records
  const repoSelectorOptions = [
    { id: 'all', name: 'All Repositories', fullName: 'All Repositories' },
    ...repositories.map((r) => ({
      id: r.name || String(r.id),
      name: r.fullName || `${r.owner}/${r.name}`,
      fullName: r.fullName || `${r.owner}/${r.name}`,
      raw: r,
    })),
  ]

  // Dynamic summary stats binding real repository count
  const dynamicStats = mockSummaryStats.map((stat) => {
    if (stat.id === 'connected-repos') {
      return {
        ...stat,
        value: String(repositories.length),
        subtext:
          repositories.length === 1
            ? '1 repository in PostgreSQL'
            : `${repositories.length} repositories in PostgreSQL`,
      }
    }
    return stat
  })

  const getPageTitle = () => {
    switch (activeNav) {
      case 'repositories':
        return 'Connected Repositories'
      case 'pull-requests':
        return 'Pull Requests'
      case 'analysis-history':
        return 'Analysis History'
      case 'settings':
        return 'Workspace Settings'
      case 'overview':
      default:
        return 'Impact Overview'
    }
  }

  return (
    <div className="min-h-screen bg-slate-50/70 text-slate-800 flex">
      {/* 1. Left Sidebar */}
      <Sidebar
        activeNav={activeNav}
        setActiveNav={setActiveNav}
        isOpen={isMobileSidebarOpen}
        onClose={() => setIsMobileSidebarOpen(false)}
        repoCount={repositories.length}
      />

      {/* 2. Main Layout Area (offset for sidebar on desktop) */}
      <div className="flex-1 flex flex-col md:pl-64 min-w-0">
        <Header
          pageTitle={getPageTitle()}
          repositories={repoSelectorOptions}
          selectedRepo={selectedRepo}
          setSelectedRepo={setSelectedRepo}
          onOpenAnalyzeModal={() => setIsModalOpen(true)}
          onOpenMobileMenu={() => setIsMobileSidebarOpen(true)}
        />

        <main className="flex-1 p-4 md:p-8 max-w-7xl w-full mx-auto">
          {/* View: Overview */}
          {activeNav === 'overview' && (
            <Overview
              stats={dynamicStats}
              pullRequests={pullRequests}
              activities={mockActivities}
              selectedRepo={selectedRepo}
              lastImpactReport={lastImpactReport}
              onOpenAnalyzeModal={() => setIsModalOpen(true)}
            />
          )}

          {/* View: Repositories (Real PostgreSQL Backend) */}
          {activeNav === 'repositories' && (
            <RepositoriesView
              repositories={repositories}
              isLoading={isLoadingRepos}
              error={reposError}
              onRefresh={loadRepositories}
              onCreate={handleCreateRepository}
              onDelete={handleDeleteRepository}
            />
          )}

          {/* Placeholders for upcoming milestones */}
          {activeNav !== 'overview' && activeNav !== 'repositories' && (
            <div className="bg-white rounded-xl border border-slate-200 p-12 text-center max-w-lg mx-auto my-12 shadow-2xs">
              <div className="w-12 h-12 rounded-xl bg-indigo-50 text-indigo-600 flex items-center justify-center mx-auto mb-4">
                {activeNav === 'pull-requests' && <GitPullRequest className="w-6 h-6" />}
                {activeNav === 'analysis-history' && <History className="w-6 h-6" />}
                {activeNav === 'settings' && <Settings className="w-6 h-6" />}
              </div>
              <h2 className="text-base font-bold text-slate-900">{getPageTitle()}</h2>
              <p className="text-xs text-slate-500 mt-2 leading-relaxed">
                This section is queued for upcoming milestones. You can explore the core Java AST impact analysis and recent PR metrics on the Overview dashboard.
              </p>
              <button
                onClick={() => setActiveNav('overview')}
                className="mt-6 inline-flex items-center space-x-1.5 px-4 py-2 rounded-lg bg-indigo-600 text-white text-xs font-semibold hover:bg-indigo-700 transition-colors"
              >
                <ArrowLeft className="w-3.5 h-3.5" />
                <span>Return to Overview</span>
              </button>
            </div>
          )}
        </main>
      </div>

      {/* 3. Primary Action Modal */}
      <AnalyzeModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        lastReport={lastImpactReport}
        onReportGenerated={handleReportGenerated}
      />
    </div>
  )
}
