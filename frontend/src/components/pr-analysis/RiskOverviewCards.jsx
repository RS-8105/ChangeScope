import RiskBadge from '../ui/RiskBadge.jsx'

export default function RiskOverviewCards({ report, _warnings = [] }) {
  if (!report) return null

  const verification = report.verificationPlan || {}
  const confidence = verification.confidence || {}
  const highPriorityCount = verification.highPriorityCount || 0
  const mediumPriorityCount = verification.mediumPriorityCount || 0

  // Derive risk level from actual backend report data (high priority verification recommendations & blast radius size)
  let calculatedRisk = 'LOW'
  if (highPriorityCount > 0 || (report.totalAffectedTypes || 0) > 10 || (report.totalChangedMethods || 0) > 10) {
    calculatedRisk = 'HIGH'
  } else if (mediumPriorityCount > 0 || (report.totalAffectedTypes || 0) > 3) {
    calculatedRisk = 'MEDIUM'
  }

  const confidenceScore = confidence.score != null ? `${confidence.score}%` : '—'
  const confidenceRating = confidence.rating || (confidence.level ? `${confidence.level} Confidence` : 'Estimated')

  const totalFilesChanged = report.totalChangedTypes != null ? report.totalChangedTypes : (report.changedTypes?.length || 0)
  const totalClassesAffected = report.totalAffectedTypes != null ? report.totalAffectedTypes : (report.affectedTypes?.length || 0)
  const totalMethodsAffected = report.totalAffectedMethods != null ? report.totalAffectedMethods : (report.affectedMethods?.length || 0)
  const directMethodsChanged = report.totalChangedMethods != null ? report.totalChangedMethods : (report.changedMethods?.length || 0)

  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
      {/* 1. Risk Level */}
      <div className="bg-[#242424] border border-[#303030] rounded-lg p-3.5 flex flex-col justify-between">
        <span className="text-[11px] font-medium uppercase tracking-wider text-[#858585]">
          Risk Level
        </span>
        <div className="mt-2">
          <RiskBadge level={calculatedRisk} />
        </div>
        <div className="text-[10px] text-[#858585] mt-1.5 truncate">
          {highPriorityCount > 0 ? `${highPriorityCount} high-risk vectors` : 'Standard verification'}
        </div>
      </div>

      {/* 2. Confidence Score */}
      <div className="bg-[#242424] border border-[#303030] rounded-lg p-3.5 flex flex-col justify-between">
        <span className="text-[11px] font-medium uppercase tracking-wider text-[#858585]">
          Confidence Score
        </span>
        <div className="text-xl font-bold font-mono text-[#D4D4D4] mt-1.5">
          {confidenceScore}
        </div>
        <div className="text-[10px] text-[#45DFA4] mt-1 truncate">
          {confidenceRating}
        </div>
      </div>

      {/* 3. Affected Files */}
      <div className="bg-[#242424] border border-[#303030] rounded-lg p-3.5 flex flex-col justify-between">
        <span className="text-[11px] font-medium uppercase tracking-wider text-[#858585]">
          Changed Types
        </span>
        <div className="text-xl font-bold font-mono text-[#D4D4D4] mt-1.5">
          {totalFilesChanged}
        </div>
        <div className="text-[10px] text-[#858585] mt-1 truncate">
          Directly modified in PR
        </div>
      </div>

      {/* 4. Affected Classes */}
      <div className="bg-[#242424] border border-[#303030] rounded-lg p-3.5 flex flex-col justify-between">
        <span className="text-[11px] font-medium uppercase tracking-wider text-[#858585]">
          Blast Radius Classes
        </span>
        <div className="text-xl font-bold font-mono text-[#D4D4D4] mt-1.5">
          {totalClassesAffected}
        </div>
        <div className="text-[10px] text-[#858585] mt-1 truncate">
          Downstream dependents
        </div>
      </div>

      {/* 5. Affected Methods */}
      <div className="bg-[#242424] border border-[#303030] rounded-lg p-3.5 flex flex-col justify-between">
        <span className="text-[11px] font-medium uppercase tracking-wider text-[#858585]">
          Affected Methods
        </span>
        <div className="text-xl font-bold font-mono text-[#D4D4D4] mt-1.5">
          {totalMethodsAffected}
        </div>
        <div className="text-[10px] text-[#858585] mt-1 truncate">
          {directMethodsChanged} signature mutations
        </div>
      </div>
    </div>
  )
}
