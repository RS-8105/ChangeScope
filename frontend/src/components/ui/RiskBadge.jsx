export default function RiskBadge({ level }) {
  const normalized = (level || 'LOW').toUpperCase()

  const config = {
    HIGH: {
      text: 'text-[#FFB4AB]',
      bg: 'bg-[#FFB4AB]/10',
      border: 'border-[#FFB4AB]/30',
    },
    MEDIUM: {
      text: 'text-[#F9BD22]',
      bg: 'bg-[#F9BD22]/10',
      border: 'border-[#F9BD22]/30',
    },
    LOW: {
      text: 'text-[#45DFA4]',
      bg: 'bg-[#45DFA4]/10',
      border: 'border-[#45DFA4]/25',
    },
  }[normalized] || {
    text: 'text-[#858585]',
    bg: 'bg-[#2A2A2A]',
    border: 'border-[#383838]',
  }

  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded text-[11px] font-semibold font-mono border ${config.bg} ${config.border} ${config.text}`}
    >
      {normalized} RISK
    </span>
  )
}
