import { useState } from 'react'
import {
  FolderGit2,
  GitPullRequest,
  CheckCircle2,
  AlertTriangle,
  AlertOctagon,
  Clock,
  ArrowUpRight,
  Search,
  Filter,
  FileCode2,
  TestTube2,
  Activity,
  GitBranch,
  ShieldAlert,
  ChevronRight,
  Sparkles,
} from 'lucide-react'

export default function Overview({
  stats,
  pullRequests,
  activities,
  selectedRepo,
  lastImpactReport,
  onOpenAnalyzeModal,
}) {
  const [searchFilter, setSearchFilter] = useState('')
  const [riskFilter, setRiskFilter] = useState('ALL')
  const [selectedPR, setSelectedPR] = useState(null)

  // Filter pull requests by selected repo, search text, and risk level
  const filteredPRs = pullRequests.filter((pr) => {
    if (selectedRepo !== 'all' && !pr.repo.includes(selectedRepo)) {
      return false
    }
    if (riskFilter !== 'ALL' && pr.risk !== riskFilter) {
      return false
    }
    if (
      searchFilter &&
      !pr.title.toLowerCase().includes(searchFilter.toLowerCase()) &&
      !pr.repo.toLowerCase().includes(searchFilter.toLowerCase()) &&
      !pr.number.toString().includes(searchFilter)
    ) {
      return false
    }
    return true
  })

  const getRiskBadge = (risk) => {
    switch (risk) {
      case 'HIGH':
        return (
          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold bg-rose-50 text-rose-700 border border-rose-200">
            <AlertOctagon className="w-3 h-3 mr-1 text-rose-600" />
            High Risk
          </span>
        )
      case 'MEDIUM':
        return (
          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold bg-amber-50 text-amber-700 border border-amber-200">
            <AlertTriangle className="w-3 h-3 mr-1 text-amber-600" />
            Medium
          </span>
        )
      case 'LOW':
      default:
        return (
          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold bg-emerald-50 text-emerald-700 border border-emerald-200">
            <CheckCircle2 className="w-3 h-3 mr-1 text-emerald-600" />
            Low Risk
          </span>
        )
    }
  }

  const getStatusBadge = (status) => {
    switch (status) {
      case 'ANALYZING':
        return (
          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium bg-indigo-50 text-indigo-700 border border-indigo-200">
            <span className="w-1.5 h-1.5 rounded-full bg-indigo-600 mr-1.5 animate-ping" />
            Analyzing
          </span>
        )
      case 'COMPLETED':
      default:
        return (
          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium bg-slate-100 text-slate-700">
            Completed
          </span>
        )
    }
  }

  return (
    <div className="space-y-6">
      {/* 1. Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {stats.map((stat) => (
          <div
            key={stat.id}
            className="p-5 rounded-xl bg-white border border-slate-200 shadow-2xs hover:border-slate-300 transition-all"
          >
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                {stat.label}
              </span>
              <span
                className={`text-[11px] font-semibold px-2 py-0.5 rounded-full ${
                  stat.changeType === 'positive'
                    ? 'bg-emerald-50 text-emerald-700'
                    : stat.changeType === 'warning'
                    ? 'bg-rose-50 text-rose-700'
                    : 'bg-indigo-50 text-indigo-700'
                }`}
              >
                {stat.change}
              </span>
            </div>
            <div className="mt-3 flex items-baseline space-x-2">
              <span className="text-2xl font-bold text-slate-900 tracking-tight">
                {stat.value}
              </span>
            </div>
            <p className="mt-1 text-xs text-slate-400 font-medium">{stat.subtext}</p>
          </div>
        ))}
      </div>

      {/* 2. Real Impact Analysis Report Card (when available) */}
      {lastImpactReport && (
        <div className="p-5 rounded-xl bg-white border border-indigo-200 shadow-2xs space-y-3">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 border-b border-slate-100 pb-3">
            <div className="flex items-center space-x-2.5">
              <div className="w-8 h-8 rounded-lg bg-indigo-50 text-indigo-600 flex items-center justify-center shrink-0">
                <Sparkles className="w-4 h-4" />
              </div>
              <div>
                <div className="flex items-center space-x-2">
                  <h3 className="text-sm font-bold text-slate-900">Latest Source-Level Impact Run</h3>
                  <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200">
                    Real Backend Pipeline (POST /api/v1/analysis/impact)
                  </span>
                </div>
                <p className="text-[11px] text-slate-500">
                  {lastImpactReport.totalChangedTypes} changed root {lastImpactReport.totalChangedTypes === 1 ? 'type' : 'types'} &bull; {lastImpactReport.totalAffectedTypes} downstream affected {lastImpactReport.totalAffectedTypes === 1 ? 'type' : 'types'} &bull; {lastImpactReport.dependencies?.length || 0} discovered dependencies
                </p>
              </div>
            </div>

            <button
              onClick={onOpenAnalyzeModal}
              className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-indigo-50 hover:bg-indigo-100 text-indigo-700 text-xs font-semibold transition-colors shrink-0"
            >
              <span>View Full Report / Rerun</span>
              <ArrowUpRight className="w-3.5 h-3.5" />
            </button>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
            <div>
              <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider block mb-1">
                Changed Roots:
              </span>
              <div className="flex flex-wrap gap-1.5">
                {lastImpactReport.changedTypes?.map((t, idx) => (
                  <span key={idx} className="font-mono text-[11px] px-2 py-0.5 rounded bg-slate-100 text-slate-700 border border-slate-200">
                    {t}
                  </span>
                ))}
              </div>
            </div>

            <div>
              <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider block mb-1">
                Blast Radius (Downstream Callers):
              </span>
              <div className="flex flex-wrap gap-1.5">
                {lastImpactReport.affectedTypes && lastImpactReport.affectedTypes.length > 0 ? (
                  lastImpactReport.affectedTypes.map((aff, idx) => (
                    <span key={idx} className="font-mono text-[11px] px-2 py-0.5 rounded bg-indigo-50 text-indigo-700 border border-indigo-200 flex items-center gap-1">
                      <span>{aff.typeName}</span>
                      <span className="text-[10px] text-indigo-500">({aff.distance}h)</span>
                    </span>
                  ))
                ) : (
                  <span className="text-slate-400 italic text-[11px]">No downstream callers affected</span>
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 2. Main Analysis Table & Activity Feed Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left 2 Cols: Recent Pull Request Analyses */}
        <div className="lg:col-span-2 space-y-4">
          <div className="bg-white rounded-xl border border-slate-200 shadow-2xs overflow-hidden">
            {/* Table Header & Filters */}
            <div className="p-4 sm:p-5 border-b border-slate-200 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div>
                <div className="flex items-center space-x-2">
                  <h2 className="text-sm font-bold text-slate-900">
                    Recent Pull Request Analyses
                  </h2>
                  <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-slate-100 text-slate-500 border border-slate-200">
                    Simulated Data
                  </span>
                </div>
                <p className="text-xs text-slate-500 mt-0.5">
                  Static AST diffs and affected test boundaries in Java repositories (Mock analysis)
                </p>
              </div>

              {/* Filter controls */}
              <div className="flex items-center space-x-2">
                {/* Search */}
                <div className="relative">
                  <Search className="w-3.5 h-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2" />
                  <input
                    type="text"
                    placeholder="Search PR or repo..."
                    value={searchFilter}
                    onChange={(e) => setSearchFilter(e.target.value)}
                    className="pl-8 pr-3 py-1.5 text-xs bg-slate-50 border border-slate-200 rounded-lg text-slate-800 placeholder-slate-400 focus:outline-hidden focus:ring-1 focus:ring-indigo-500"
                  />
                </div>

                {/* Risk Filter Buttons */}
                <div className="hidden sm:flex rounded-lg border border-slate-200 bg-slate-50 p-0.5 text-xs">
                  {['ALL', 'HIGH', 'MEDIUM', 'LOW'].map((risk) => (
                    <button
                      key={risk}
                      onClick={() => setRiskFilter(risk)}
                      className={`px-2 py-1 rounded-md font-medium text-[11px] transition-colors ${
                        riskFilter === risk
                          ? 'bg-white text-slate-900 shadow-2xs font-semibold'
                          : 'text-slate-500 hover:text-slate-800'
                      }`}
                    >
                      {risk}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            {/* Table */}
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead>
                  <tr className="bg-slate-50/75 border-b border-slate-200 text-[11px] font-semibold text-slate-500 uppercase tracking-wider">
                    <th className="py-3 px-4">Pull Request</th>
                    <th className="py-3 px-4">Repository</th>
                    <th className="py-3 px-4">Risk Level</th>
                    <th className="py-3 px-4">Impact Scope</th>
                    <th className="py-3 px-4">Status</th>
                    <th className="py-3 px-4 text-right">Action</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-200">
                  {filteredPRs.length > 0 ? (
                    filteredPRs.map((pr) => (
                      <tr
                        key={pr.id}
                        onClick={() => setSelectedPR(selectedPR?.id === pr.id ? null : pr)}
                        className={`hover:bg-slate-50/80 transition-colors cursor-pointer ${
                          selectedPR?.id === pr.id ? 'bg-indigo-50/30' : ''
                        }`}
                      >
                        <td className="py-3.5 px-4">
                          <div className="flex items-start space-x-2.5">
                            <span className="font-mono text-[11px] font-bold text-indigo-600 bg-indigo-50 px-1.5 py-0.5 rounded border border-indigo-100">
                              #{pr.number}
                            </span>
                            <div className="max-w-[220px] sm:max-w-xs">
                              <p className="font-semibold text-slate-900 leading-snug truncate" title={pr.title}>
                                {pr.title}
                              </p>
                              <div className="flex items-center space-x-2 text-[11px] text-slate-400 mt-1">
                                <span className="flex items-center gap-1 font-mono text-[10px]">
                                  <GitBranch className="w-3 h-3 text-slate-400" />
                                  {pr.branch}
                                </span>
                                <span>&bull;</span>
                                <span>{pr.author.name}</span>
                              </div>
                            </div>
                          </div>
                        </td>

                        <td className="py-3.5 px-4 font-mono text-[11px] text-slate-600 whitespace-nowrap">
                          {pr.repo.split('/')[1]}
                        </td>

                        <td className="py-3.5 px-4 whitespace-nowrap">
                          {getRiskBadge(pr.risk)}
                        </td>

                        <td className="py-3.5 px-4 whitespace-nowrap">
                          <div className="flex items-center space-x-3">
                            <span className="inline-flex items-center text-slate-600 font-medium" title="Classes changed / affected">
                              <FileCode2 className="w-3.5 h-3.5 text-slate-400 mr-1" />
                              {pr.impact.affectedClasses} classes
                            </span>
                            <span className="inline-flex items-center text-indigo-600 font-medium" title="Targeted tests impacted">
                              <TestTube2 className="w-3.5 h-3.5 text-indigo-500 mr-1" />
                              {pr.impact.impactedTests} tests
                            </span>
                          </div>
                        </td>

                        <td className="py-3.5 px-4 whitespace-nowrap">
                          {getStatusBadge(pr.status)}
                        </td>

                        <td className="py-3.5 px-4 text-right whitespace-nowrap">
                          <button
                            onClick={(e) => {
                              e.stopPropagation()
                              setSelectedPR(selectedPR?.id === pr.id ? null : pr)
                            }}
                            className="p-1 rounded-md text-slate-400 hover:text-indigo-600 hover:bg-indigo-50"
                            title="Inspect impact summary"
                          >
                            <ChevronRight className={`w-4 h-4 transition-transform ${selectedPR?.id === pr.id ? 'rotate-90' : ''}`} />
                          </button>
                        </td>
                      </tr>
                    ))
                  ) : (
                    <tr>
                      <td colSpan={6} className="py-10 text-center text-slate-400">
                        <FolderGit2 className="w-8 h-8 text-slate-300 mx-auto mb-2" />
                        <p className="font-medium text-slate-600">No pull requests found</p>
                        <p className="text-xs text-slate-400 mt-1">Try adjusting your search or risk filters</p>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            {/* Quick Expanded Details Drawer/Row if row is selected */}
            {selectedPR && (
              <div className="p-4 bg-slate-50/90 border-t border-indigo-100 flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs">
                <div className="space-y-1">
                  <div className="flex items-center space-x-2">
                    <span className="font-semibold text-slate-900">
                      Impact Breakdown for #{selectedPR.number}:
                    </span>
                    <span className="font-mono text-slate-500">commit {selectedPR.commitHash}</span>
                  </div>
                  <p className="text-[11px] text-slate-600">
                    {selectedPR.impact.classesChanged} source classes modified &rarr; {selectedPR.impact.affectedClasses} transitive callers impacted &rarr; {selectedPR.impact.impactedTests} Spring Boot tests to execute.
                  </p>
                </div>
                <div className="flex items-center space-x-2">
                  <span
                    className="inline-flex items-center space-x-1 px-3 py-1.5 rounded-lg bg-slate-100 text-slate-500 text-xs font-medium cursor-not-allowed"
                    title="Interactive AST graph is planned for Milestone 2"
                  >
                    <span>Graph View (Milestone 2)</span>
                    <ArrowUpRight className="w-3.5 h-3.5 text-slate-400" />
                  </span>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Right 1 Col: Recent Activity & Impact Stream */}
        <div className="space-y-4">
          <div className="bg-white rounded-xl border border-slate-200 shadow-2xs p-5">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-sm font-bold text-slate-900 flex items-center gap-2">
                <Activity className="w-4 h-4 text-indigo-600" />
                Impact Activity Stream
              </h2>
              <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-slate-100 text-slate-500 border border-slate-200">
                Simulated Feed
              </span>
            </div>

            <div className="space-y-4">
              {activities.map((activity) => (
                <div key={activity.id} className="relative pl-6 pb-2 border-l border-slate-200 last:border-transparent">
                  {/* Timeline bullet */}
                  <span
                    className={`absolute -left-1.5 top-0.5 w-3 h-3 rounded-full border-2 border-white ${
                      activity.severity === 'warning'
                        ? 'bg-rose-500'
                        : activity.severity === 'success'
                        ? 'bg-emerald-500'
                        : 'bg-indigo-500'
                    }`}
                  />
                  <div>
                    <div className="flex items-center justify-between">
                      <span className="text-xs font-semibold text-slate-800 leading-snug">
                        {activity.title}
                      </span>
                      <span className="text-[10px] text-slate-400 whitespace-nowrap ml-2">
                        {activity.timestamp}
                      </span>
                    </div>
                    <p className="text-[11px] text-slate-500 mt-1 leading-relaxed">
                      {activity.description}
                    </p>
                    <div className="mt-1.5 flex items-center space-x-2">
                      <span className="font-mono text-[10px] text-slate-400">
                        {activity.repo.split('/')[1]}
                      </span>
                    </div>
                  </div>
                </div>
              ))}
            </div>

            <div className="mt-4 pt-3 border-t border-slate-100">
              <button
                onClick={onOpenAnalyzeModal}
                className="w-full py-2 px-3 rounded-lg border border-dashed border-slate-300 hover:border-indigo-400 hover:bg-indigo-50/30 text-xs font-medium text-slate-600 hover:text-indigo-600 flex items-center justify-center gap-1.5 transition-colors"
              >
                <Sparkles className="w-3.5 h-3.5 text-indigo-500" />
                Analyze New Code Change
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
