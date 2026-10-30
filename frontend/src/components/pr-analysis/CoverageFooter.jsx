import { Cpu, AlertTriangle, CheckCircle2, Database } from 'lucide-react'

export default function CoverageFooter({ coverage }) {
  if (!coverage) {
    return (
      <div className="p-3 bg-[#1C1C1C] border border-[#303030] rounded-lg text-xs font-mono text-[#858585] flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Cpu className="w-3.5 h-3.5 text-[#5A5A5A]" />
          <span>Bytecode Engine: Java 21 LTS (Spring Boot 3.3)</span>
        </div>
        <span className="text-[#5A5A5A]">Coverage metadata not provided</span>
      </div>
    )
  }

  const isFull = coverage.scope === 'FULL_REPOSITORY_INDEX'
  const isComplete = coverage.isComplete !== false
  const reasons = coverage.incompleteReasons || []

  return (
    <div className="bg-[#1C1C1C] border border-[#303030] rounded-lg p-3.5 space-y-2.5">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 text-xs font-mono">
        {/* Left: Engine & Scope */}
        <div className="flex items-center gap-2 flex-wrap">
          <Cpu className="w-3.5 h-3.5 text-[#A78BFA] shrink-0" />
          <span className="text-[#D4D4D4] font-medium">Java 21 AST Engine</span>
          <span className="text-[#525252]">•</span>
          <span className="text-[#858585]">Scope:</span>
          <span
            className={`px-1.5 py-0.5 rounded text-[10px] font-semibold ${
              isFull
                ? 'bg-[#45DFA4]/10 text-[#45DFA4] border border-[#45DFA4]/20'
                : 'bg-[#F9BD22]/10 text-[#F9BD22] border border-[#F9BD22]/20'
            }`}
          >
            {coverage.scope || 'PR_FILES_ONLY'}
          </span>
          {coverage.baseCommitSha && (
            <>
              <span className="text-[#525252]">•</span>
              <span className="text-[#858585]">Base Snapshot:</span>
              <span className="text-[#D4D4D4]">{coverage.baseCommitSha.substring(0, 7)}</span>
            </>
          )}
        </div>

        {/* Right: Files Count & Completeness */}
        <div className="flex items-center gap-3 text-[11px] text-[#858585]">
          {coverage.productionFilesDiscovered > 0 && (
            <div className="flex items-center gap-1">
              <Database className="w-3 h-3 text-[#5A5A5A]" />
              <span>
                {coverage.productionFilesIncluded} / {coverage.productionFilesDiscovered} files indexed
              </span>
            </div>
          )}

          <div className="flex items-center gap-1 font-semibold">
            {isComplete ? (
              <span className="text-[#45DFA4] flex items-center gap-1">
                <CheckCircle2 className="w-3 h-3" />
                COMPLETE
              </span>
            ) : (
              <span className="text-[#F9BD22] flex items-center gap-1">
                <AlertTriangle className="w-3 h-3" />
                PARTIAL COVERAGE
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Incomplete Reasons Warning Callout */}
      {reasons.length > 0 && (
        <div className="pt-2 border-t border-[#2A2A2A] text-[11px] text-[#F9BD22] font-mono flex items-start gap-1.5">
          <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
          <div>
            {reasons.map((reason, idx) => (
              <div key={idx}>{reason}</div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
