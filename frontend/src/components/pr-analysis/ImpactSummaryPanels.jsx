import { Layers, Network, AlertTriangle, CheckCircle2 } from 'lucide-react'

export default function ImpactSummaryPanels({ report }) {
  if (!report) return null

  const changedTypes = report.changedTypes || []
  const affectedTypes = report.affectedTypes || []
  const directCallers = affectedTypes.filter((t) => t.distance === 1)
  const transitiveConsumers = affectedTypes.filter((t) => t.distance > 1)
  const recommendations = report.verificationPlan?.recommendations || []
  const highPriorityItems = recommendations.filter((r) => r.priority === 'HIGH')
  const negativeFactors = report.verificationPlan?.confidence?.negativeFactors || []

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
      {/* Panel 1: Epicenter Components */}
      <div className="bg-[#242424] border border-[#303030] rounded-lg p-4 flex flex-col justify-between">
        <div>
          <div className="flex items-center gap-2 pb-2.5 border-b border-[#303030]">
            <Layers className="w-4 h-4 text-[#A78BFA]" />
            <h3 className="text-xs font-semibold uppercase tracking-wider text-[#D4D4D4]">
              Epicenter Components ({changedTypes.length})
            </h3>
          </div>
          <p className="text-[11px] text-[#858585] mt-2 mb-3">
            Source classes directly modified in this pull request changeset.
          </p>

          <div className="space-y-1.5 max-h-48 overflow-y-auto pr-1">
            {changedTypes.length === 0 ? (
              <div className="text-xs text-[#5A5A5A] font-mono py-2">
                No direct Java class changes detected.
              </div>
            ) : (
              changedTypes.map((type) => {
                const simpleName = type.split('.').pop()
                return (
                  <div
                    key={type}
                    className="p-2 bg-[#1C1C1C] border border-[#303030] rounded text-xs font-mono flex items-center justify-between"
                  >
                    <span className="font-semibold text-[#D4D4D4] truncate" title={type}>
                      {simpleName}
                    </span>
                    <span className="text-[10px] text-[#A78BFA] bg-[#A78BFA]/10 px-1.5 py-0.5 rounded border border-[#A78BFA]/20">
                      MODIFIED
                    </span>
                  </div>
                )
              })
            )}
          </div>
        </div>

        <div className="pt-3 border-t border-[#303030] text-[10px] text-[#858585] flex items-center justify-between font-mono">
          <span>Target Types</span>
          <span className="text-[#D4D4D4]">{changedTypes.length} types</span>
        </div>
      </div>

      {/* Panel 2: Egress & Call Hierarchy */}
      <div className="bg-[#242424] border border-[#303030] rounded-lg p-4 flex flex-col justify-between">
        <div>
          <div className="flex items-center gap-2 pb-2.5 border-b border-[#303030]">
            <Network className="w-4 h-4 text-[#F9BD22]" />
            <h3 className="text-xs font-semibold uppercase tracking-wider text-[#D4D4D4]">
              Egress & Call Hierarchy
            </h3>
          </div>
          <p className="text-[11px] text-[#858585] mt-2 mb-3">
            Downstream propagation across class and method dependency paths.
          </p>

          <div className="space-y-2">
            <div className="p-2.5 bg-[#1C1C1C] border border-[#303030] rounded flex items-center justify-between">
              <span className="text-xs text-[#858585]">Direct Dependents (Distance 1)</span>
              <span className="text-xs font-mono font-bold text-[#F9BD22]">
                {directCallers.length} classes
              </span>
            </div>

            <div className="p-2.5 bg-[#1C1C1C] border border-[#303030] rounded flex items-center justify-between">
              <span className="text-xs text-[#858585]">Transitive Consumers (Distance 2+)</span>
              <span className="text-xs font-mono font-bold text-[#D4D4D4]">
                {transitiveConsumers.length} classes
              </span>
            </div>

            <div className="p-2.5 bg-[#1C1C1C] border border-[#303030] rounded flex items-center justify-between">
              <span className="text-xs text-[#858585]">Method Invocations Mapped</span>
              <span className="text-xs font-mono font-bold text-[#A78BFA]">
                {report.methodDependencies?.length || 0} calls
              </span>
            </div>
          </div>
        </div>

        <div className="pt-3 border-t border-[#303030] text-[10px] text-[#858585] flex items-center justify-between font-mono">
          <span>Max Traversal Depth</span>
          <span className="text-[#D4D4D4]">
            {affectedTypes.reduce((max, t) => Math.max(max, t.distance || 0), 0)} hops
          </span>
        </div>
      </div>

      {/* Panel 3: Potential Regression Areas */}
      <div className="bg-[#242424] border border-[#303030] rounded-lg p-4 flex flex-col justify-between">
        <div>
          <div className="flex items-center gap-2 pb-2.5 border-b border-[#303030]">
            <AlertTriangle className="w-4 h-4 text-[#FFB4AB]" />
            <h3 className="text-xs font-semibold uppercase tracking-wider text-[#D4D4D4]">
              High-Risk Regression Areas
            </h3>
          </div>
          <p className="text-[11px] text-[#858585] mt-2 mb-3">
            Compiler-detected contract mutations and unverified execution paths.
          </p>

          <div className="space-y-1.5 max-h-48 overflow-y-auto pr-1">
            {highPriorityItems.length === 0 && negativeFactors.length === 0 ? (
              <div className="p-3 bg-[#45DFA4]/5 border border-[#45DFA4]/20 rounded text-xs text-[#45DFA4] flex items-center gap-2">
                <CheckCircle2 className="w-4 h-4 shrink-0" />
                <span>No high-priority breaking changes flagged.</span>
              </div>
            ) : (
              <>
                {highPriorityItems.map((item, idx) => (
                  <div
                    key={idx}
                    className="p-2 bg-[#1C1C1C] border border-[#FFB4AB]/25 rounded text-xs"
                  >
                    <div className="font-semibold text-[#FFB4AB] truncate">
                      {item.target}
                    </div>
                    <div className="text-[11px] text-[#858585] mt-0.5 leading-snug">
                      {item.action || item.reason}
                    </div>
                  </div>
                ))}
                {negativeFactors.map((factor, idx) => (
                  <div
                    key={`factor-${idx}`}
                    className="p-2 bg-[#1C1C1C] border border-[#303030] rounded text-xs text-[#858585]"
                  >
                    {factor}
                  </div>
                ))}
              </>
            )}
          </div>
        </div>

        <div className="pt-3 border-t border-[#303030] text-[10px] text-[#858585] flex items-center justify-between font-mono">
          <span>Flagged Items</span>
          <span className="text-[#FFB4AB] font-bold">
            {highPriorityItems.length} critical
          </span>
        </div>
      </div>
    </div>
  )
}
