import { useState } from 'react'
import { FileCode2, Search } from 'lucide-react'

export default function AffectedComponentsTable({
  changedTypes = [],
  affectedTypes = [],
  dependencies = [],
}) {
  const [searchTerm, setSearchTerm] = useState('')
  const [filterLevel, setFilterLevel] = useState('ALL')

  // Build unified component rows
  const rows = []

  // 1. Direct Changed Types (Distance 0)
  changedTypes.forEach((type) => {
    rows.push({
      fqn: type,
      simpleName: type.split('.').pop(),
      package: type.includes('.') ? type.substring(0, type.lastIndexOf('.')) : '',
      relationship: 'Direct Modified (Epicenter)',
      distance: 0,
      level: 'HIGH',
      reason: 'Direct Java source mutation in PR changeset',
    })
  })

  // 2. Blast-Radius Affected Types (Distance 1+)
  affectedTypes.forEach((item) => {
    // Avoid duplicate if already marked as changed
    if (changedTypes.includes(item.typeName)) return

    const dist = item.distance || 1
    const level = dist === 1 ? 'MEDIUM' : 'LOW'
    const relationship = dist === 1 ? 'Direct Caller (Distance 1)' : `Transitive Consumer (Distance ${dist})`

    // Look for matching dependency kind if available
    const dep = dependencies.find((d) => d.sourceType === item.typeName)
    const reason = dep
      ? `Depends on ${dep.targetType.split('.').pop()} via ${dep.kind || 'REFERENCE'}`
      : `Discovered in breadth-first traversal at hop ${dist}`

    rows.push({
      fqn: item.typeName,
      simpleName: item.typeName.split('.').pop(),
      package: item.typeName.includes('.') ? item.typeName.substring(0, item.typeName.lastIndexOf('.')) : '',
      relationship,
      distance: dist,
      level,
      reason,
    })
  })

  // Filter rows
  const filteredRows = rows.filter((row) => {
    const matchesSearch =
      !searchTerm.trim() ||
      row.fqn.toLowerCase().includes(searchTerm.toLowerCase()) ||
      row.reason.toLowerCase().includes(searchTerm.toLowerCase())

    const matchesLevel =
      filterLevel === 'ALL' ||
      (filterLevel === 'HIGH' && row.distance === 0) ||
      (filterLevel === 'DIRECT' && row.distance === 1) ||
      (filterLevel === 'TRANSITIVE' && row.distance > 1)

    return matchesSearch && matchesLevel
  })

  return (
    <div className="bg-[#242424] border border-[#303030] rounded-lg overflow-hidden">
      {/* Table Toolbar */}
      <div className="px-4 py-3 bg-[#1C1C1C] border-b border-[#303030] flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div className="flex items-center gap-2">
          <FileCode2 className="w-4 h-4 text-[#A78BFA]" />
          <h2 className="text-xs font-semibold uppercase tracking-wider text-[#D4D4D4]">
            Affected Components ({rows.length})
          </h2>
        </div>

        {/* Search & Filter Controls */}
        <div className="flex items-center gap-2 w-full sm:w-auto">
          {/* Search Input */}
          <div className="relative flex-1 sm:w-64">
            <Search className="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-[#5A5A5A]" />
            <input
              type="text"
              placeholder="Search component, package, or reason..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pl-8 pr-2.5 py-1 bg-[#171717] border border-[#303030] focus:border-[#525252] rounded text-xs text-[#D4D4D4] placeholder:text-[#525252] font-mono outline-hidden transition-colors"
            />
          </div>

          {/* Filter Dropdown */}
          <select
            value={filterLevel}
            onChange={(e) => setFilterLevel(e.target.value)}
            className="px-2.5 py-1 bg-[#171717] border border-[#303030] text-xs text-[#D4D4D4] rounded font-mono outline-hidden"
          >
            <option value="ALL">All Types</option>
            <option value="HIGH">Modified (Epicenter)</option>
            <option value="DIRECT">Direct Callers (Hop 1)</option>
            <option value="TRANSITIVE">Transitive (Hop 2+)</option>
          </select>
        </div>
      </div>

      {/* Table Body */}
      {filteredRows.length === 0 ? (
        <div className="py-12 text-center text-xs text-[#858585]">
          No components match the search query "{searchTerm}".
        </div>
      ) : (
        <div className="overflow-x-auto max-h-[440px] overflow-y-auto">
          <table className="w-full text-left border-collapse text-xs">
            <thead className="sticky top-0 z-10">
              <tr className="h-9 bg-[#1C1C1C] border-b border-[#303030] text-[11px] uppercase tracking-wider text-[#858585] font-medium">
                <th className="px-4 py-2">Component / Class</th>
                <th className="px-4 py-2">Relationship</th>
                <th className="px-4 py-2">Blast Distance</th>
                <th className="px-4 py-2 hidden md:table-cell">Compiler Evidence / Reason</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#282828]">
              {filteredRows.map((row) => (
                <tr
                  key={row.fqn}
                  className="h-12 hover:bg-[#2A2A2A]/50 transition-colors font-mono"
                >
                  {/* Class Name & Package */}
                  <td className="px-4 py-2">
                    <div className="font-semibold text-[#D4D4D4] text-xs">
                      {row.simpleName}
                    </div>
                    {row.package && (
                      <div className="text-[10px] text-[#858585] truncate max-w-xs" title={row.package}>
                        {row.package}
                      </div>
                    )}
                  </td>

                  {/* Relationship */}
                  <td className="px-4 py-2">
                    <span
                      className={`inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono border ${
                        row.distance === 0
                          ? 'bg-[#A78BFA]/10 text-[#A78BFA] border-[#A78BFA]/25'
                          : row.distance === 1
                          ? 'bg-[#F9BD22]/10 text-[#F9BD22] border-[#F9BD22]/25'
                          : 'bg-[#2A2A2A] text-[#858585] border-[#383838]'
                      }`}
                    >
                      {row.relationship}
                    </span>
                  </td>

                  {/* Blast Distance */}
                  <td className="px-4 py-2 text-[#D4D4D4]">
                    {row.distance === 0 ? (
                      <span className="text-[#A78BFA] font-bold">0 (Direct)</span>
                    ) : (
                      <span>{row.distance} {row.distance === 1 ? 'hop' : 'hops'}</span>
                    )}
                  </td>

                  {/* Compiler Evidence */}
                  <td className="px-4 py-2 hidden md:table-cell text-[#858585] text-[11px] truncate max-w-sm" title={row.reason}>
                    {row.reason}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
