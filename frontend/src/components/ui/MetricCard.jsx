export default function MetricCard({
  label,
  value,
  subtext,
  badge,
  icon: Icon,
  isUnavailable = false,
}) {
  return (
    <div className="bg-[#242424] border border-[#303030] rounded-lg p-4 flex flex-col justify-between transition-colors hover:border-[#3E3E3E]">
      <div className="flex items-center justify-between">
        <span className="text-[11px] font-medium uppercase tracking-wider text-[#858585]">
          {label}
        </span>
        {Icon && <Icon className="w-4 h-4 text-[#858585]" />}
      </div>

      <div className="mt-3 mb-1.5 flex items-baseline gap-2">
        <span
          className={`text-2xl font-bold font-mono ${
            isUnavailable ? 'text-[#5A5A5A]' : 'text-[#D4D4D4]'
          }`}
        >
          {value}
        </span>
        {badge && <span>{badge}</span>}
      </div>

      {subtext && (
        <div className="text-[11px] text-[#858585] truncate">
          {subtext}
        </div>
      )}
    </div>
  )
}
