import {
  X,
  FileCode2,
  Layers,
  ArrowRight,
  ArrowLeft,
} from 'lucide-react'

export default function NodeInspectorPanel({
  selectedNode,
  onClose,
  allEdges = [],
  affectedMethods = [],
}) {
  if (!selectedNode) {
    return (
      <aside className="w-full lg:w-[360px] bg-[#242424] border border-[#303030] rounded-lg p-5 flex flex-col justify-between shrink-0 select-none">
        <div>
          <div className="text-[11px] font-semibold uppercase tracking-wider text-[#858585] pb-2.5 border-b border-[#303030]">
            Node Inspector
          </div>
          <div className="py-16 text-center text-xs text-[#858585] flex flex-col items-center justify-center">
            <FileCode2 className="w-8 h-8 text-[#5A5A5A] mb-2" />
            <span className="text-[#D4D4D4] font-medium">No component selected</span>
            <p className="text-[11px] text-[#5A5A5A] max-w-xs mt-1 leading-relaxed">
              Click any Java class node in the dependency graph canvas to inspect its call hierarchy, method mutations, and blast-radius details.
            </p>
          </div>
        </div>

        <div className="pt-3 border-t border-[#303030] text-[10px] font-mono text-[#5A5A5A] text-center">
          Interactive AST Telemetry Panel
        </div>
      </aside>
    )
  }

  const {
    fqn,
    simpleName,
    category = 'UNAFFECTED',
    distance,
    filePath,
    detail,
  } = selectedNode.data || {}

  // Compute incoming and outgoing dependencies for this node from allEdges
  const nodeId = selectedNode.id
  const incomingEdges = allEdges.filter((e) => e.target === nodeId)
  const outgoingEdges = allEdges.filter((e) => e.source === nodeId)

  // Find methods declared by this type that were affected
  const methodsForType = affectedMethods.filter((m) => m.declaringType === fqn)

  return (
    <aside className="w-full lg:w-[360px] bg-[#242424] border border-[#303030] rounded-lg p-4 flex flex-col justify-between shrink-0 overflow-y-auto max-h-[calc(100vh-210px)]">
      <div className="space-y-4">
        {/* Header with Title and Close button */}
        <div className="flex items-center justify-between pb-2.5 border-b border-[#303030]">
          <div className="flex items-center gap-2">
            <Layers className="w-4 h-4 text-[#A78BFA]" />
            <span className="text-xs font-semibold uppercase tracking-wider text-[#D4D4D4]">
              Component Inspector
            </span>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-[#858585] hover:text-[#D4D4D4] p-1 rounded hover:bg-[#2A2A2A] transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Component Identity Card */}
        <div className="p-3 bg-[#1C1C1C] border border-[#303030] rounded-lg font-mono text-xs space-y-1.5">
          <div className="flex items-center justify-between">
            <span className="text-sm font-bold text-[#D4D4D4] truncate" title={simpleName}>
              {simpleName}
            </span>
            <span
              className={`text-[10px] px-1.5 py-0.5 rounded font-semibold border ${
                category === 'CHANGED'
                  ? 'bg-[#A78BFA]/10 text-[#A78BFA] border-[#A78BFA]/30'
                  : category === 'DIRECT'
                  ? 'bg-[#F9BD22]/10 text-[#F9BD22] border-[#F9BD22]/30'
                  : 'bg-[#2A2A2A] text-[#858585] border-[#383838]'
              }`}
            >
              {category} {distance != null ? `(d=${distance})` : ''}
            </span>
          </div>

          <div className="text-[10px] text-[#858585] break-all">
            {fqn}
          </div>

          {filePath && (
            <div className="text-[10px] text-[#5A5A5A] pt-1 border-t border-[#2A2A2A] truncate" title={filePath}>
              {filePath}
            </div>
          )}
        </div>

        {/* Compiler Evidence & Blast Detail */}
        {detail && (
          <div>
            <span className="text-[10px] font-semibold uppercase tracking-wider text-[#858585] block mb-1">
              Impact Classification
            </span>
            <div className="p-2.5 bg-[#1C1C1C] border border-[#303030] rounded text-xs text-[#D4D4D4] leading-snug">
              {detail}
            </div>
          </div>
        )}

        {/* Affected Methods (if any) */}
        {methodsForType.length > 0 && (
          <div>
            <span className="text-[10px] font-semibold uppercase tracking-wider text-[#858585] block mb-1">
              Affected Methods ({methodsForType.length})
            </span>
            <div className="space-y-1 max-h-36 overflow-y-auto">
              {methodsForType.map((m, idx) => (
                <div
                  key={idx}
                  className="p-1.5 bg-[#1C1C1C] border border-[#303030] rounded text-[11px] font-mono text-[#D4D4D4] flex items-center justify-between"
                >
                  <span className="truncate" title={m.methodIdentifier}>
                    {m.methodIdentifier}
                  </span>
                  <span className="text-[9px] text-[#A78BFA] bg-[#A78BFA]/10 px-1 py-0.2 rounded border border-[#A78BFA]/20 shrink-0">
                    d={m.distance}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Incoming Dependencies */}
        <div>
          <span className="text-[10px] font-semibold uppercase tracking-wider text-[#858585] block mb-1">
            Incoming Consumers ({incomingEdges.length})
          </span>
          {incomingEdges.length === 0 ? (
            <div className="p-2 bg-[#1C1C1C] border border-[#303030] rounded text-[11px] text-[#5A5A5A] font-mono">
              No incoming callers mapped in this PR changeset.
            </div>
          ) : (
            <div className="space-y-1 max-h-32 overflow-y-auto">
              {incomingEdges.map((e) => {
                const sourceName = e.data?.sourceType?.split('.').pop() || e.source.replace('node-', '')
                return (
                  <div
                    key={e.id}
                    className="p-1.5 bg-[#1C1C1C] border border-[#303030] rounded text-[11px] font-mono text-[#D4D4D4] flex items-center justify-between"
                  >
                    <div className="flex items-center gap-1.5 truncate">
                      <ArrowLeft className="w-3 h-3 text-[#F9BD22] shrink-0" />
                      <span className="truncate" title={e.data?.sourceType || sourceName}>
                        {sourceName}
                      </span>
                    </div>
                    <span className="text-[9px] text-[#858585] bg-[#2A2A2A] px-1 py-0.2 rounded shrink-0">
                      {e.data?.kind || 'CALLS'}
                    </span>
                  </div>
                )
              })}
            </div>
          )}
        </div>

        {/* Outgoing Dependencies */}
        <div>
          <span className="text-[10px] font-semibold uppercase tracking-wider text-[#858585] block mb-1">
            Outgoing Dependencies ({outgoingEdges.length})
          </span>
          {outgoingEdges.length === 0 ? (
            <div className="p-2 bg-[#1C1C1C] border border-[#303030] rounded text-[11px] text-[#5A5A5A] font-mono">
              No outgoing dependency references recorded.
            </div>
          ) : (
            <div className="space-y-1 max-h-32 overflow-y-auto">
              {outgoingEdges.map((e) => {
                const targetName = e.data?.targetType?.split('.').pop() || e.target.replace('node-', '')
                return (
                  <div
                    key={e.id}
                    className="p-1.5 bg-[#1C1C1C] border border-[#303030] rounded text-[11px] font-mono text-[#D4D4D4] flex items-center justify-between"
                  >
                    <div className="flex items-center gap-1.5 truncate">
                      <ArrowRight className="w-3 h-3 text-[#A78BFA] shrink-0" />
                      <span className="truncate" title={e.data?.targetType || targetName}>
                        {targetName}
                      </span>
                    </div>
                    <span className="text-[9px] text-[#858585] bg-[#2A2A2A] px-1 py-0.2 rounded shrink-0">
                      {e.data?.kind || 'USES'}
                    </span>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </div>

      {/* Footer Meta */}
      <div className="mt-4 pt-3 border-t border-[#303030] text-[10px] font-mono text-[#5A5A5A] flex items-center justify-between">
        <span>AST Bytecode Inspector</span>
        <span>ID: {selectedNode.id}</span>
      </div>
    </aside>
  )
}
