export default function StatusBadge({ status, label, commitSha }) {
  const normalized = (status || '').toUpperCase()

  const config = {
    INDEXED: {
      dot: 'bg-[#45DFA4]',
      text: 'text-[#45DFA4]',
      bg: 'bg-[#45DFA4]/10',
      border: 'border-[#45DFA4]/25',
      defaultLabel: 'Indexed',
    },
    COMPLETED: {
      dot: 'bg-[#45DFA4]',
      text: 'text-[#45DFA4]',
      bg: 'bg-[#45DFA4]/10',
      border: 'border-[#45DFA4]/25',
      defaultLabel: 'Completed',
    },
    INDEXING: {
      dot: 'bg-[#F9BD22] animate-pulse',
      text: 'text-[#F9BD22]',
      bg: 'bg-[#F9BD22]/10',
      border: 'border-[#F9BD22]/25',
      defaultLabel: 'Indexing',
    },
    RUNNING: {
      dot: 'bg-[#F9BD22] animate-pulse',
      text: 'text-[#F9BD22]',
      bg: 'bg-[#F9BD22]/10',
      border: 'border-[#F9BD22]/25',
      defaultLabel: 'Running',
    },
    UNINDEXED: {
      dot: 'bg-[#737373]',
      text: 'text-[#858585]',
      bg: 'bg-[#2A2A2A]',
      border: 'border-[#383838]',
      defaultLabel: 'Unindexed',
    },
    FAILED: {
      dot: 'bg-[#FFB4AB]',
      text: 'text-[#FFB4AB]',
      bg: 'bg-[#FFB4AB]/10',
      border: 'border-[#FFB4AB]/30',
      defaultLabel: 'Failed',
    },
  }[normalized] || {
    dot: 'bg-[#737373]',
    text: 'text-[#858585]',
    bg: 'bg-[#2A2A2A]',
    border: 'border-[#383838]',
    defaultLabel: status || 'Unknown',
  }

  return (
    <span
      className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-mono font-medium border ${config.bg} ${config.border} ${config.text}`}
    >
      <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${config.dot}`} />
      <span>{label || config.defaultLabel}</span>
      {commitSha && (
        <span className="text-[#858585] text-[10px] pl-0.5">
          ({commitSha.substring(0, 7)})
        </span>
      )}
    </span>
  )
}
