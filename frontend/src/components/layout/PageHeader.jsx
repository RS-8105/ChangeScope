export default function PageHeader({ title, subtitle, actions, tags }) {
  return (
    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 pb-6 border-b border-[#303030]">
      <div>
        <div className="flex items-center gap-3">
          <h1 className="text-xl sm:text-2xl font-semibold text-[#D4D4D4] tracking-tight font-sans">
            {title}
          </h1>
          {tags && <div className="flex items-center gap-2">{tags}</div>}
        </div>
        {subtitle && (
          <p className="text-xs sm:text-sm text-[#858585] mt-1">
            {subtitle}
          </p>
        )}
      </div>

      {actions && (
        <div className="flex items-center gap-2.5 shrink-0">
          {actions}
        </div>
      )}
    </div>
  )
}
