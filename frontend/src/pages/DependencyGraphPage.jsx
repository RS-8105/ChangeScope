import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { useParams, useSearchParams, Link } from 'react-router-dom'
import PageHeader from '../components/layout/PageHeader.jsx'
import GraphToolbar from '../components/graph/GraphToolbar.jsx'
import GraphCanvas from '../components/graph/GraphCanvas.jsx'
import NodeInspectorPanel from '../components/graph/NodeInspectorPanel.jsx'
import impactAnalysisService from '../services/impactAnalysisService.js'
import { transformReportToGraph } from '../utils/graphTransform.js'
import { ArrowLeft, Network, AlertTriangle, Loader2 } from 'lucide-react'

export default function DependencyGraphPage() {
  const { repoId = '1', prNumber = '1' } = useParams()
  const [searchParams] = useSearchParams()
  const explicitAnalysisId = searchParams.get('analysisId')

  const [analysisResult, setAnalysisResult] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState(null)

  // Graph interactive state
  const [searchTerm, setSearchTerm] = useState('')
  const [relationshipFilter, setRelationshipFilter] = useState('ALL')
  const [showUnaffected, setShowUnaffected] = useState(true)
  const [selectedNode, setSelectedNode] = useState(null)

  const controlsRef = useRef(null)

  // Load the exact same analysis report as PRAnalysisPage
  const loadGraphData = useCallback(async () => {
    setIsLoading(true)
    setError(null)

    try {
      if (explicitAnalysisId) {
        // 1. If explicit analysisId is provided from history, load that exact snapshot
        const snapshot = await impactAnalysisService.getAnalysisSnapshot(
          repoId,
          explicitAnalysisId
        )
        setAnalysisResult(snapshot)
        setIsLoading(false)
        return
      }

      // 2. Otherwise search history for matching pullNumber
      const historyPage = await impactAnalysisService.getRepositoryAnalyses(repoId, 0, 10)
      const matches = (historyPage.content || []).filter(
        (a) => String(a.pullNumber) === String(prNumber) && a.status === 'COMPLETED'
      )

      if (matches.length > 0) {
        const snapshot = await impactAnalysisService.getAnalysisSnapshot(repoId, matches[0].id)
        setAnalysisResult(snapshot)
        setIsLoading(false)
      } else {
        // 3. Fallback: trigger fresh analysis if no saved snapshot exists
        const live = await impactAnalysisService.analyzePullRequest(repoId, prNumber)
        setAnalysisResult(live)
        setIsLoading(false)
      }
    } catch (err) {
      setError(
        err.formattedMessage ||
          `Failed to load dependency graph for PR #${prNumber}. Verify backend connectivity.`
      )
      setIsLoading(false)
    }
  }, [repoId, prNumber, explicitAnalysisId])

  useEffect(() => {
    loadGraphData()
  }, [loadGraphData])

  // Transform backend ImpactReport into React Flow graph model
  const { nodes, edges, stats } = useMemo(() => {
    const report = analysisResult?.impactReport
    const detections = analysisResult?.changedTypeDetections || []
    return transformReportToGraph(report, detections, {
      relationshipFilter,
      showUnaffected,
      searchTerm,
    })
  }, [analysisResult, relationshipFilter, showUnaffected, searchTerm])

  const report = analysisResult?.impactReport
  const pr = analysisResult?.pullRequest || {}
  const repoName = pr.repo ? `${pr.owner}/${pr.repo}` : `Repository #${repoId}`

  const backLink = `/repositories/${repoId}/pulls/${prNumber}${
    explicitAnalysisId ? `?analysisId=${explicitAnalysisId}` : ''
  }`

  return (
    <div className="space-y-3 flex flex-col h-[calc(100vh-100px)]">
      {/* View Header */}
      <PageHeader
        title="Dependency Graph & Blast Radius"
        subtitle={`Interactive visual topology for ${repoName} • PR #${prNumber}`}
        actions={
          <div className="flex items-center gap-2">
            <Link
              to={backLink}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-[#242424] hover:bg-[#2A2A2A] text-[#D4D4D4] border border-[#303030] hover:border-[#525252] text-xs font-medium rounded transition-colors"
            >
              <ArrowLeft className="w-3.5 h-3.5" />
              <span>Back to PR Report</span>
            </Link>
          </div>
        }
      />

      {/* Loading State */}
      {isLoading ? (
        <div className="flex-1 bg-[#242424] border border-[#303030] rounded-lg flex flex-col items-center justify-center p-8 text-center">
          <Loader2 className="w-7 h-7 text-[#A78BFA] animate-spin mb-3" />
          <div className="text-sm font-semibold text-[#D4D4D4]">
            Rendering Dependency Graph
          </div>
          <p className="text-xs text-[#858585] mt-1">
            Loading AST type hierarchy and method call edges for PR #{prNumber}...
          </p>
        </div>
      ) : error ? (
        /* Error State */
        <div className="p-5 bg-[#2A1818] border border-[#522525] rounded-lg text-xs text-[#FFB4AB] flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div className="flex items-start gap-3">
            <AlertTriangle className="w-5 h-5 text-[#FFB4AB] shrink-0 mt-0.5" />
            <div>
              <div className="font-semibold text-[#FFB4AB] text-sm">Graph Render Error</div>
              <div className="text-[#D4D4D4]/80 mt-1">{error}</div>
            </div>
          </div>
          <button
            type="button"
            onClick={loadGraphData}
            className="px-3.5 py-1.5 bg-[#1C1C1C] hover:bg-[#2A2A2A] text-[#D4D4D4] border border-[#383838] rounded text-xs font-semibold self-start sm:self-auto shrink-0 transition-colors"
          >
            Retry Loading
          </button>
        </div>
      ) : (
        /* Main Workspace: Toolbar + Split Canvas & Inspector */
        <div className="flex-1 flex flex-col min-h-0 space-y-3">
          {/* Controls Toolbar */}
          <GraphToolbar
            searchTerm={searchTerm}
            onSearchChange={setSearchTerm}
            relationshipFilter={relationshipFilter}
            onRelationshipFilterChange={setRelationshipFilter}
            showUnaffected={showUnaffected}
            onToggleUnaffected={() => setShowUnaffected((prev) => !prev)}
            onZoomIn={() => controlsRef.current?.zoomIn()}
            onZoomOut={() => controlsRef.current?.zoomOut()}
            onFitView={() => controlsRef.current?.fitView()}
            onResetView={() => controlsRef.current?.resetView()}
            stats={stats}
          />

          {/* Canvas & Inspector Split View */}
          <div className="flex-1 flex flex-col lg:flex-row gap-3 min-h-0">
            {/* Graph Canvas Container */}
            <div className="flex-1 bg-[#171717] border border-[#303030] rounded-lg overflow-hidden relative min-h-[400px]">
              {nodes.length === 0 ? (
                <div className="w-full h-full flex flex-col items-center justify-center p-8 text-center">
                  <Network className="w-8 h-8 text-[#5A5A5A] mb-2" />
                  <div className="text-sm font-medium text-[#D4D4D4]">
                    Empty Dependency Graph
                  </div>
                  <p className="text-xs text-[#858585] max-w-sm mt-1">
                    No Java class nodes match the active filter criteria, or the pull request contains no type dependencies.
                  </p>
                </div>
              ) : (
                <GraphCanvas
                  initialNodes={nodes}
                  initialEdges={edges}
                  onNodeSelect={setSelectedNode}
                  selectedNodeId={selectedNode?.id}
                  forwardedControlsRef={controlsRef}
                />
              )}
            </div>

            {/* Docked Node Inspector Panel (~30% width) */}
            <NodeInspectorPanel
              selectedNode={selectedNode}
              onClose={() => setSelectedNode(null)}
              allEdges={edges}
              affectedMethods={report?.affectedMethods || []}
            />
          </div>
        </div>
      )}
    </div>
  )
}
