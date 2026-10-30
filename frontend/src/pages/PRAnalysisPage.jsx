import { useState, useEffect, useCallback, useRef } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import PRContextBar from '../components/pr-analysis/PRContextBar.jsx'
import RiskOverviewCards from '../components/pr-analysis/RiskOverviewCards.jsx'
import ImpactSummaryPanels from '../components/pr-analysis/ImpactSummaryPanels.jsx'
import AffectedComponentsTable from '../components/pr-analysis/AffectedComponentsTable.jsx'
import VerificationPlanCard from '../components/pr-analysis/VerificationPlanCard.jsx'
import CoverageFooter from '../components/pr-analysis/CoverageFooter.jsx'
import impactAnalysisService from '../services/impactAnalysisService.js'
import {
  GitPullRequest,
  AlertTriangle,
  Loader2,
} from 'lucide-react'

const ANALYSIS_STEPS = [
  '1. Retrieving PR commit revisions & changed files via GitHub API...',
  '2. Locating base commit graph snapshot in PostgreSQL...',
  '3. Parsing Java AST declarations, method calls & signature diffs...',
  '4. Traversing BFS blast-radius and ranking candidate test suites...',
]

export default function PRAnalysisPage() {
  const { repoId = '1', prNumber = '1' } = useParams()
  const [searchParams] = useSearchParams()
  const explicitAnalysisId = searchParams.get('analysisId')

  const [analysisResult, setAnalysisResult] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const [isAnalyzing, setIsAnalyzing] = useState(false)
  const [error, setError] = useState(null)
  const [activeStepIndex, setActiveStepIndex] = useState(0)

  // Track active AbortController to cancel inflight requests
  const activeControllerRef = useRef(null)

  // Trigger real backend analysis
  const executeAnalysis = useCallback(async () => {
    // Cancel previous inflight request if running
    if (activeControllerRef.current) {
      activeControllerRef.current.abort()
    }
    const controller = new AbortController()
    activeControllerRef.current = controller

    setIsAnalyzing(true)
    setError(null)
    setActiveStepIndex(0)

    // Simulate progress through analysis steps while awaiting response
    const stepInterval = setInterval(() => {
      setActiveStepIndex((prev) => (prev < ANALYSIS_STEPS.length - 1 ? prev + 1 : prev))
    }, 2500)

    try {
      const data = await impactAnalysisService.analyzePullRequest(
        repoId,
        prNumber,
        controller.signal
      )
      // Only commit if this controller wasn't aborted
      if (!controller.signal.aborted) {
        setAnalysisResult(data)
      }
    } catch (err) {
      // Ignore cancellations cleanly - do not show aborted requests as errors
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
          `Failed to run impact analysis for PR #${prNumber}. Verify backend is accessible and repository #${repoId} exists.`
      )
    } finally {
      clearInterval(stepInterval)
      if (!controller.signal.aborted) {
        setIsAnalyzing(false)
        setIsLoading(false)
      }
    }
  }, [repoId, prNumber])

  // Initial load: check if explicit analysisId or existing snapshot is saved
  const loadInitialState = useCallback(async () => {
    if (activeControllerRef.current) {
      activeControllerRef.current.abort()
    }
    const controller = new AbortController()
    activeControllerRef.current = controller

    setIsLoading(true)
    setError(null)

    try {
      // If a specific analysis ID was passed in query params, load it directly
      if (explicitAnalysisId) {
        const snapshot = await impactAnalysisService.getAnalysisSnapshot(
          repoId,
          explicitAnalysisId,
          controller.signal
        )
        if (!controller.signal.aborted) {
          setAnalysisResult(snapshot)
          setIsLoading(false)
        }
        return
      }

      // Otherwise query repository analysis history for this pullNumber
      const historyPage = await impactAnalysisService.getRepositoryAnalyses(
        repoId,
        0,
        10,
        controller.signal
      )
      if (controller.signal.aborted) return

      const matches = (historyPage.content || []).filter(
        (a) => String(a.pullNumber) === String(prNumber) && a.status === 'COMPLETED'
      )

      if (matches.length > 0) {
        const snapshot = await impactAnalysisService.getAnalysisSnapshot(
          repoId,
          matches[0].id,
          controller.signal
        )
        if (!controller.signal.aborted) {
          setAnalysisResult(snapshot)
          setIsLoading(false)
        }
      } else {
        await executeAnalysis()
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
      await executeAnalysis()
    }
  }, [repoId, prNumber, explicitAnalysisId, executeAnalysis])

  useEffect(() => {
    loadInitialState()
    return () => {
      // Abort inflight request when unmounting or when dependencies change
      if (activeControllerRef.current) {
        activeControllerRef.current.abort()
      }
    }
  }, [loadInitialState])

  const report = analysisResult?.impactReport
  const metadata = analysisResult?.pullRequest
  const extractionWarnings = analysisResult?.extractionWarnings || []

  return (
    <div className="space-y-6">
      {/* A. PR Context Header */}
      <PRContextBar
        repositoryId={repoId}
        pullNumber={prNumber}
        metadata={metadata}
        isAnalyzing={isAnalyzing}
        onReanalyze={executeAnalysis}
      />

      {/* Loading Progress State (During Synchronous Analysis Execution) */}
      {isAnalyzing && (
        <div className="p-6 bg-[#242424] border border-[#A78BFA]/30 rounded-lg space-y-4 animate-in fade-in">
          <div className="flex items-center gap-3">
            <Loader2 className="w-5 h-5 text-[#A78BFA] animate-spin" />
            <div>
              <div className="text-sm font-semibold text-[#D4D4D4]">
                Running Java AST Impact Analysis
              </div>
              <div className="text-xs text-[#858585] mt-0.5">
                Executing bytecode dependency overlay against PR #{prNumber} (timeout: 60s)
              </div>
            </div>
          </div>

          {/* Stepper Progress */}
          <div className="space-y-1.5 pt-2">
            {ANALYSIS_STEPS.map((step, idx) => {
              const isCurrent = idx === activeStepIndex
              const isDone = idx < activeStepIndex
              return (
                <div
                  key={step}
                  className={`text-xs font-mono flex items-center gap-2 ${
                    isCurrent
                      ? 'text-[#A78BFA] font-medium'
                      : isDone
                      ? 'text-[#45DFA4]'
                      : 'text-[#525252]'
                  }`}
                >
                  <span>{isDone ? '✓' : isCurrent ? '▶' : '○'}</span>
                  <span>{step}</span>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {/* Error Alert Banner */}
      {error && !isAnalyzing && (
        <div className="p-5 bg-[#2A1818] border border-[#522525] rounded-lg text-xs text-[#FFB4AB] flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div className="flex items-start gap-3">
            <AlertTriangle className="w-5 h-5 text-[#FFB4AB] shrink-0 mt-0.5" />
            <div>
              <div className="font-semibold text-[#FFB4AB] text-sm">Analysis Execution Failure</div>
              <div className="text-[#D4D4D4]/80 mt-1">{error}</div>
            </div>
          </div>
          <button
            type="button"
            onClick={executeAnalysis}
            className="px-3.5 py-1.5 bg-[#1C1C1C] hover:bg-[#2A2A2A] text-[#D4D4D4] border border-[#383838] rounded text-xs font-semibold self-start sm:self-auto shrink-0 transition-colors"
          >
            Retry Analysis
          </button>
        </div>
      )}

      {/* Main Analysis Report Content */}
      {!isAnalyzing && report ? (
        <>
          {/* B. Risk Overview Bar */}
          <RiskOverviewCards
            report={report}
            warnings={extractionWarnings}
          />

          {/* C. Impact Summary Bento Grid */}
          <ImpactSummaryPanels report={report} />

          {/* D. Affected Components Table */}
          <AffectedComponentsTable
            changedTypes={report.changedTypes || []}
            affectedTypes={report.affectedTypes || []}
            dependencies={report.dependencies || []}
          />

          {/* E. Verification Plan */}
          <VerificationPlanCard
            candidateTests={report.candidateTests || []}
            recommendations={report.verificationPlan?.recommendations || []}
            warnings={extractionWarnings}
            parseErrors={report.parseErrors || []}
          />

          {/* F. Coverage Information Telemetry */}
          <CoverageFooter coverage={report.coverage} />
        </>
      ) : !isAnalyzing && !error && !isLoading ? (
        /* Empty Fallback */
        <div className="py-16 px-4 bg-[#242424] border border-[#303030] rounded-lg text-center flex flex-col items-center justify-center">
          <GitPullRequest className="w-10 h-10 text-[#5A5A5A] mb-3" />
          <div className="text-sm font-medium text-[#D4D4D4]">
            No Impact Report Found for PR #{prNumber}
          </div>
          <p className="text-xs text-[#858585] max-w-sm mt-1 mb-4">
            Click below to trigger a live AST semantic diff and blast-radius traversal against the pull request.
          </p>
          <button
            type="button"
            onClick={executeAnalysis}
            className="px-3.5 py-2 bg-[#A78BFA] hover:bg-[#906EE8] text-[#121212] text-xs font-semibold rounded transition-colors"
          >
            Run Impact Analysis
          </button>
        </div>
      ) : null}
    </div>
  )
}
