import {
  Search,
  Filter,
  Eye,
  EyeOff,
  ZoomIn,
  ZoomOut,
  Maximize2,
  RotateCcw,
  Info,
} from 'lucide-react'

export default function GraphToolbar({
  searchTerm = '',
  onSearchChange,
  relationshipFilter = 'ALL',
  onRelationshipFilterChange,
  showUnaffected = true,
  onToggleUnaffected,
  onZoomIn,
  onZoomOut,
  onFitView,
  onResetView,
  stats = { totalNodes: 0, totalEdges: 0, changedNodes: 0, affectedNodes: 0 },
}) {
  return (
    <div className="bg-[#242424] border border-[#303030] rounded-lg p-2.5 flex flex-wrap items-center justify-between gap-3 text-xs">
      {/* Left: Telemetry Statistics & Dependency Direction Clarification */}
      <div className="flex flex-wrap items-center gap-2.5 text-xs font-mono">
        <div className="flex items-center gap-1.5 px-2 py-0.5 bg-[#1C1C1C] border border-[#303030] rounded text-[#D4D4D4]">
          <span className="text-[#858585]">Nodes:</span>
          <span className="font-bold">{stats.totalNodes}</span>
        </div>

        <div className="flex items-center gap-1.5 px-2 py-0.5 bg-[#1C1C1C] border border-[#303030] rounded text-[#D4D4D4]">
          <span className="text-[#858585]">Edges:</span>
          <span className="font-bold text-[#A78BFA]">{stats.totalEdges}</span>
        </div>

        <div className="flex items-center gap-1.5 px-2 py-0.5 bg-[#A78BFA]/10 border border-[#A78BFA]/20 rounded text-[#A78BFA]">
          <span>Changed:</span>
          <span className="font-bold">{stats.changedNodes}</span>
        </div>

        <div className="flex items-center gap-1.5 px-2 py-0.5 bg-[#F9BD22]/10 border border-[#F9BD22]/20 rounded text-[#F9BD22]">
          <span>Affected:</span>
          <span className="font-bold">{stats.affectedNodes}</span>
        </div>

        {/* Graph Direction Explanation */}
        <div
          className="flex items-center gap-1.5 px-2 py-0.5 bg-[#171717] border border-[#303030] rounded text-[11px] text-[#858585] cursor-help transition-colors hover:border-[#525252]"
          title="Arrow direction represents static dependency references. A → B means A depends on or references B."
        >
          <Info className="w-3 h-3 text-[#A78BFA] shrink-0" />
          <span className="hidden xl:inline">
            Arrow direction represents static dependency references. A → B means A depends on or references B.
          </span>
          <span className="xl:hidden">
            A → B (A depends on B)
          </span>
        </div>
      </div>

      {/* Center & Right: Search, Filters & Controls */}
      <div className="flex flex-wrap items-center gap-2">
        {/* Class Search Bar */}
        <div className="relative w-44 sm:w-52">
          <Search className="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-[#5A5A5A]" />
          <input
            type="text"
            placeholder="Highlight class..."
            value={searchTerm}
            onChange={(e) => onSearchChange(e.target.value)}
            className="w-full pl-8 pr-2 py-1 bg-[#171717] border border-[#303030] focus:border-[#525252] rounded text-xs text-[#D4D4D4] placeholder:text-[#525252] font-mono outline-hidden transition-colors"
          />
        </div>

        {/* Relationship Filter */}
        <div className="flex items-center gap-1">
          <Filter className="w-3.5 h-3.5 text-[#5A5A5A] hidden sm:inline" />
          <select
            value={relationshipFilter}
            onChange={(e) => onRelationshipFilterChange(e.target.value)}
            className="px-2 py-1 bg-[#171717] border border-[#303030] rounded text-xs text-[#D4D4D4] font-mono outline-hidden"
          >
            <option value="ALL">All Relationships</option>
            <option value="EXTENDS">EXTENDS</option>
            <option value="IMPLEMENTS">IMPLEMENTS</option>
            <option value="FIELD">FIELD</option>
            <option value="CONSTRUCTOR_PARAMETER">CONSTRUCTOR_PARAM</option>
            <option value="METHOD_PARAMETER">METHOD_PARAM</option>
            <option value="METHOD_RETURN">METHOD_RETURN</option>
          </select>
        </div>

        {/* Unaffected Nodes Toggle */}
        <button
          type="button"
          onClick={onToggleUnaffected}
          className={`inline-flex items-center gap-1.5 px-2.5 py-1 border rounded text-xs transition-colors ${
            showUnaffected
              ? 'bg-[#1C1C1C] text-[#D4D4D4] border-[#303030] hover:bg-[#2A2A2A]'
              : 'bg-[#A78BFA]/10 text-[#A78BFA] border-[#A78BFA]/30'
          }`}
          title={showUnaffected ? 'Hide unaffected dependencies' : 'Show unaffected dependencies'}
        >
          {showUnaffected ? <Eye className="w-3.5 h-3.5" /> : <EyeOff className="w-3.5 h-3.5" />}
          <span className="hidden md:inline">Unaffected</span>
        </button>

        {/* Zoom Controls */}
        <div className="flex items-center gap-0.5 border border-[#303030] rounded bg-[#171717] p-0.5">
          <button
            type="button"
            onClick={onZoomIn}
            className="p-1 text-[#858585] hover:text-[#D4D4D4] hover:bg-[#242424] rounded transition-colors"
            title="Zoom in"
          >
            <ZoomIn className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={onZoomOut}
            className="p-1 text-[#858585] hover:text-[#D4D4D4] hover:bg-[#242424] rounded transition-colors"
            title="Zoom out"
          >
            <ZoomOut className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={onFitView}
            className="p-1 text-[#858585] hover:text-[#D4D4D4] hover:bg-[#242424] rounded transition-colors"
            title="Fit graph to view"
          >
            <Maximize2 className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={onResetView}
            className="p-1 text-[#858585] hover:text-[#D4D4D4] hover:bg-[#242424] rounded transition-colors"
            title="Reset view"
          >
            <RotateCcw className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
    </div>
  )
}
