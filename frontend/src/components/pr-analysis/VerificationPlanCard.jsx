import { useState } from 'react'
import {
  CheckSquare,
  Terminal,
  Copy,
  Check,
  ShieldAlert,
} from 'lucide-react'

export default function VerificationPlanCard({
  candidateTests = [],
  recommendations = [],
  warnings = [],
  parseErrors = [],
}) {
  const [copiedCmd, setCopiedCmd] = useState(false)

  // Extract distinct test class names for the CLI test command
  const testNames = candidateTests
    .map((t) => t.testClassName || t.testFilePath?.split('/').pop()?.replace('.java', ''))
    .filter(Boolean)
  const uniqueTestNames = Array.from(new Set(testNames))

  const mavenCommand = uniqueTestNames.length > 0
    ? `mvn test -Dtest=${uniqueTestNames.join(',')}`
    : 'mvn test'

  const handleCopy = () => {
    navigator.clipboard.writeText(mavenCommand)
    setCopiedCmd(true)
    setTimeout(() => setCopiedCmd(false), 2000)
  }

  return (
    <div className="bg-[#242424] border border-[#303030] rounded-lg p-5 space-y-5">
      {/* Header */}
      <div className="flex items-center justify-between pb-3 border-b border-[#303030]">
        <div className="flex items-center gap-2">
          <CheckSquare className="w-4 h-4 text-[#45DFA4]" />
          <h2 className="text-xs font-semibold uppercase tracking-wider text-[#D4D4D4]">
            Recommended Verification Plan
          </h2>
        </div>
        <span className="text-[11px] font-mono text-[#858585]">
          {uniqueTestNames.length} candidate test suites identified
        </span>
      </div>

      {/* 2-Column Bento: Left = Tests + Copy CLI, Right = Recommendations & Warnings */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        {/* Left Column: Candidate Tests & CLI Command */}
        <div className="space-y-4">
          <div>
            <span className="text-[11px] font-semibold uppercase tracking-wider text-[#858585] block mb-2">
              Prioritized Unit & Integration Test Suites
            </span>

            {uniqueTestNames.length === 0 ? (
              <div className="p-4 bg-[#1C1C1C] border border-[#303030] rounded text-xs text-[#858585]">
                No direct candidate tests matched by naming convention or AST method invocation.
              </div>
            ) : (
              <div className="space-y-2 max-h-56 overflow-y-auto pr-1">
                {candidateTests.map((test, idx) => (
                  <div
                    key={`${test.testClassName}-${idx}`}
                    className="p-2.5 bg-[#1C1C1C] border border-[#303030] rounded flex flex-col gap-1 text-xs font-mono"
                  >
                    <div className="flex items-center justify-between">
                      <span className="font-semibold text-[#D4D4D4]">
                        {test.testClassName || 'Test'}
                      </span>
                      <span className="text-[10px] text-[#45DFA4] bg-[#45DFA4]/10 px-1.5 py-0.5 rounded border border-[#45DFA4]/20">
                        {test.evidenceKind || 'MATCHED'}
                      </span>
                    </div>
                    {test.reason && (
                      <div className="text-[11px] text-[#858585] font-sans">
                        {test.reason}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Copyable Maven Test Command */}
          <div>
            <span className="text-[11px] font-semibold uppercase tracking-wider text-[#858585] block mb-1.5">
              Copyable Test Execution Command
            </span>
            <div className="flex items-center justify-between px-3 py-2 bg-[#171717] border border-[#303030] rounded font-mono text-xs text-[#A78BFA]">
              <div className="flex items-center gap-2 truncate">
                <Terminal className="w-3.5 h-3.5 text-[#5A5A5A] shrink-0" />
                <span className="truncate">{mavenCommand}</span>
              </div>
              <button
                type="button"
                onClick={handleCopy}
                className="inline-flex items-center gap-1 text-xs text-[#D4D4D4] hover:text-[#A78BFA] ml-2 shrink-0 p-1 rounded hover:bg-[#242424] transition-colors"
                title="Copy command to clipboard"
              >
                {copiedCmd ? (
                  <>
                    <Check className="w-3.5 h-3.5 text-[#45DFA4]" />
                    <span className="text-[10px] text-[#45DFA4]">Copied!</span>
                  </>
                ) : (
                  <>
                    <Copy className="w-3.5 h-3.5 text-[#858585]" />
                    <span className="text-[10px]">Copy</span>
                  </>
                )}
              </button>
            </div>
          </div>
        </div>

        {/* Right Column: Suggested Verification Steps & Warnings */}
        <div className="space-y-4">
          <div>
            <span className="text-[11px] font-semibold uppercase tracking-wider text-[#858585] block mb-2">
              Actionable Validation Steps
            </span>

            {recommendations.length === 0 ? (
              <div className="p-4 bg-[#1C1C1C] border border-[#303030] rounded text-xs text-[#858585]">
                No specific risk recommendations generated. Standard regression suites advised.
              </div>
            ) : (
              <div className="space-y-2 max-h-56 overflow-y-auto pr-1">
                {recommendations.map((rec, idx) => (
                  <div
                    key={idx}
                    className="p-2.5 bg-[#1C1C1C] border border-[#303030] rounded flex flex-col gap-1 text-xs"
                  >
                    <div className="flex items-center justify-between">
                      <span className="font-semibold text-[#D4D4D4]">
                        {rec.target}
                      </span>
                      <span
                        className={`text-[10px] font-mono px-1.5 py-0.5 rounded border ${
                          rec.priority === 'HIGH'
                            ? 'text-[#FFB4AB] bg-[#FFB4AB]/10 border-[#FFB4AB]/30'
                            : rec.priority === 'MEDIUM'
                            ? 'text-[#F9BD22] bg-[#F9BD22]/10 border-[#F9BD22]/30'
                            : 'text-[#858585] bg-[#2A2A2A] border-[#383838]'
                        }`}
                      >
                        {rec.priority || 'NORMAL'}
                      </span>
                    </div>
                    <div className="text-[11px] text-[#858585]">
                      {rec.action || rec.reason}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Warnings & Parse Errors Banner */}
          {(warnings.length > 0 || parseErrors.length > 0) && (
            <div className="p-3 bg-[#2A1818] border border-[#522525] rounded text-xs space-y-1.5">
              <div className="flex items-center gap-1.5 font-semibold text-[#FFB4AB]">
                <ShieldAlert className="w-4 h-4 shrink-0" />
                <span>Analysis Warnings & Gaps ({warnings.length + parseErrors.length})</span>
              </div>
              <ul className="list-disc pl-5 space-y-0.5 text-[11px] text-[#D4D4D4]/90">
                {warnings.map((warn, i) => (
                  <li key={`warn-${i}`}>{warn}</li>
                ))}
                {parseErrors.map((err, i) => (
                  <li key={`err-${i}`}>
                    {err.filePath ? `${err.filePath}: ` : ''}{err.errorMessage}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
