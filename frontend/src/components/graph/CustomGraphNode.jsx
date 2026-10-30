import { memo } from 'react'
import { Handle, Position } from '@xyflow/react'
import { FileCode2, Layers, Network, CheckCircle2 } from 'lucide-react'

function CustomGraphNode({ data, selected }) {
  const {
    simpleName,
    package: pkgName,
    category = 'UNAFFECTED',
    distance,
    isMatchSearch = false,
    relatedMethodsCount = 0,
    incomingCount = 0,
    outgoingCount = 0,
  } = data || {}

  const config = {
    CHANGED: {
      border: 'border-[#A78BFA]',
      bg: 'bg-[#A78BFA]/10',
      badgeText: 'text-[#A78BFA]',
      badgeBg: 'bg-[#A78BFA]/15',
      badgeBorder: 'border-[#A78BFA]/30',
      tag: 'MODIFIED',
      icon: Layers,
    },
    DIRECT: {
      border: 'border-[#F9BD22]',
      bg: 'bg-[#F9BD22]/5',
      badgeText: 'text-[#F9BD22]',
      badgeBg: 'bg-[#F9BD22]/15',
      badgeBorder: 'border-[#F9BD22]/30',
      tag: 'DIRECT',
      icon: Network,
    },
    TRANSITIVE: {
      border: 'border-[#525252]',
      bg: 'bg-[#242424]',
      badgeText: 'text-[#858585]',
      badgeBg: 'bg-[#2A2A2A]',
      badgeBorder: 'border-[#383838]',
      tag: `HOP ${distance || 2}`,
      icon: FileCode2,
    },
    UNAFFECTED: {
      border: 'border-dashed border-[#383838]',
      bg: 'bg-[#1C1C1C]/60',
      badgeText: 'text-[#5A5A5A]',
      badgeBg: 'bg-[#242424]',
      badgeBorder: 'border-[#303030]',
      tag: 'DEP',
      icon: CheckCircle2,
    },
  }[category] || {
    border: 'border-[#303030]',
    bg: 'bg-[#242424]',
    badgeText: 'text-[#858585]',
    badgeBg: 'bg-[#2A2A2A]',
    badgeBorder: 'border-[#383838]',
    tag: category,
    icon: FileCode2,
  }

  const Icon = config.icon

  return (
    <div
      className={`w-[220px] rounded-md p-3 transition-all select-none font-mono ${config.bg} border ${
        selected
          ? 'ring-2 ring-[#A78BFA] border-[#A78BFA] shadow-lg shadow-black/50'
          : isMatchSearch
          ? 'ring-2 ring-[#F9BD22] border-[#F9BD22]'
          : config.border
      }`}
    >
      {/* Top Handle (Incoming connections) */}
      <Handle
        type="target"
        position={Position.Top}
        className="w-2.5 h-2.5 bg-[#525252] border-2 border-[#171717] rounded-full"
      />

      {/* Header: Class Icon + Simple Name + Status Pill */}
      <div className="flex items-center justify-between gap-1.5 pb-1.5 border-b border-[#303030]">
        <div className="flex items-center gap-1.5 truncate">
          <Icon className="w-3.5 h-3.5 text-[#D4D4D4] shrink-0" />
          <span className="text-xs font-semibold text-[#D4D4D4] truncate" title={simpleName}>
            {simpleName}
          </span>
        </div>
        <span
          className={`text-[9px] px-1 py-0.2 rounded font-semibold border shrink-0 ${config.badgeText} ${config.badgeBg} ${config.badgeBorder}`}
        >
          {config.tag}
        </span>
      </div>

      {/* Package Subtext */}
      <div className="text-[10px] text-[#858585] truncate pt-1.5" title={pkgName}>
        {pkgName || 'default package'}
      </div>

      {/* Metrics Line */}
      <div className="mt-2 pt-1.5 border-t border-[#2A2A2A] text-[9px] text-[#5A5A5A] flex items-center justify-between">
        <span>
          {relatedMethodsCount > 0 ? (
            <span className="text-[#A78BFA] font-medium">{relatedMethodsCount} methods diff</span>
          ) : (
            <span>Class contract</span>
          )}
        </span>
        <span className="text-[#858585]">
          in:{incomingCount} out:{outgoingCount}
        </span>
      </div>

      {/* Bottom Handle (Outgoing connections) */}
      <Handle
        type="source"
        position={Position.Bottom}
        className="w-2.5 h-2.5 bg-[#525252] border-2 border-[#171717] rounded-full"
      />
    </div>
  )
}

export default memo(CustomGraphNode)
