import { MarkerType } from '@xyflow/react'

/**
 * Transforms backend ImpactReport data into @xyflow/react nodes and edges.
 * Uses only authentic AST and blast-radius data without fabricating synthetic edges.
 *
 * @param {Object} impactReport - Backend ImpactReport DTO
 * @param {Array} changedTypeDetections - Backend ChangedTypeDetection DTOs
 * @param {Object} options - Filtering options { relationshipFilter, showUnaffected, searchTerm }
 * @returns {Object} { nodes: Array, edges: Array, stats: Object }
 */
export function transformReportToGraph(
  impactReport,
  changedTypeDetections = [],
  options = {}
) {
  const {
    relationshipFilter = 'ALL',
    showUnaffected = true,
    searchTerm = '',
  } = options

  if (!impactReport) {
    return {
      nodes: [],
      edges: [],
      stats: { totalNodes: 0, totalEdges: 0, changedNodes: 0, affectedNodes: 0 },
    }
  }

  const changedTypes = impactReport.changedTypes || []
  const affectedTypes = impactReport.affectedTypes || []
  const dependencies = impactReport.dependencies || []
  const affectedMethods = impactReport.affectedMethods || []

  // Create lookup map for file paths and evidence from ChangedTypeDetection
  const detectionsMap = new Map()
  changedTypeDetections.forEach((d) => {
    if (d.fullyQualifiedName) {
      detectionsMap.set(d.fullyQualifiedName, d)
    }
  })

  // 1. Identify all unique type nodes
  const nodesMap = new Map()

  // 1A. Direct Changed Types (Epicenter - Distance 0)
  changedTypes.forEach((fqn) => {
    if (!fqn) return
    const detection = detectionsMap.get(fqn)
    nodesMap.set(fqn, {
      id: `node-${fqn}`,
      fqn,
      simpleName: fqn.split('.').pop() || fqn,
      package: fqn.includes('.') ? fqn.substring(0, fqn.lastIndexOf('.')) : '',
      category: 'CHANGED',
      distance: 0,
      filePath: detection?.filePath || null,
      evidence: detection?.evidence || 'DIRECT_CHANGE',
      detail: detection?.detail || 'Direct source modification in PR',
    })
  })

  // 1B. Blast-Radius Affected Types (Distance 1+)
  affectedTypes.forEach((item) => {
    const fqn = item.typeName
    if (!fqn) return

    // If not already classified as directly changed
    if (!nodesMap.has(fqn)) {
      const dist = item.distance || 1
      nodesMap.set(fqn, {
        id: `node-${fqn}`,
        fqn,
        simpleName: fqn.split('.').pop() || fqn,
        package: fqn.includes('.') ? fqn.substring(0, fqn.lastIndexOf('.')) : '',
        category: dist === 1 ? 'DIRECT' : 'TRANSITIVE',
        distance: dist,
        filePath: null,
        evidence: 'BLAST_RADIUS_TRAVERSAL',
        detail: `Discovered in blast radius at distance ${dist}`,
      })
    }
  })

  // 1C. Other Referenced Types (present in type dependencies)
  dependencies.forEach((dep) => {
    if (dep.sourceType && !nodesMap.has(dep.sourceType)) {
      nodesMap.set(dep.sourceType, {
        id: `node-${dep.sourceType}`,
        fqn: dep.sourceType,
        simpleName: dep.sourceType.split('.').pop() || dep.sourceType,
        package: dep.sourceType.includes('.')
          ? dep.sourceType.substring(0, dep.sourceType.lastIndexOf('.'))
          : '',
        category: 'UNAFFECTED',
        distance: null,
        filePath: dep.sourceFilePath || null,
        evidence: 'DEPENDENCY_REFERENCE',
        detail: 'Referenced component in dependency graph',
      })
    }

    if (dep.targetType && !nodesMap.has(dep.targetType)) {
      nodesMap.set(dep.targetType, {
        id: `node-${dep.targetType}`,
        fqn: dep.targetType,
        simpleName: dep.targetType.split('.').pop() || dep.targetType,
        package: dep.targetType.includes('.')
          ? dep.targetType.substring(0, dep.targetType.lastIndexOf('.'))
          : '',
        category: 'UNAFFECTED',
        distance: null,
        filePath: null,
        evidence: 'TARGET_REFERENCE',
        detail: 'Target dependency of an indexed component',
      })
    }
  })

  // 2. Build directional edges from TypeDependency list
  const rawEdges = []
  const edgeKeySet = new Set()

  dependencies.forEach((dep, index) => {
    if (!dep.sourceType || !dep.targetType) return

    // Filter by relationship type if selected
    const kind = dep.kind || 'REFERENCE'
    if (relationshipFilter !== 'ALL' && kind !== relationshipFilter) {
      return
    }

    const edgeKey = `${dep.sourceType}->${dep.targetType}:${kind}`
    if (edgeKeySet.has(edgeKey)) return // deduplicate duplicate edges
    edgeKeySet.add(edgeKey)

    const isSelfRef = dep.sourceType === dep.targetType

    rawEdges.push({
      id: `edge-${index}-${dep.sourceType}-${dep.targetType}`,
      source: `node-${dep.sourceType}`,
      target: `node-${dep.targetType}`,
      label: kind,
      type: isSelfRef ? 'default' : 'smoothstep',
      markerEnd: {
        type: MarkerType.ArrowClosed,
        width: 14,
        height: 14,
        color: '#858585',
      },
      style: {
        stroke: '#525252',
        strokeWidth: 1.5,
      },
      data: {
        kind,
        sourceType: dep.sourceType,
        targetType: dep.targetType,
      },
    })
  })

  // 3. Filter nodes based on options (showUnaffected and searchTerm)
  let visibleNodeList = Array.from(nodesMap.values())

  if (!showUnaffected) {
    visibleNodeList = visibleNodeList.filter((n) => n.category !== 'UNAFFECTED')
  }

  const visibleNodeIdSet = new Set(visibleNodeList.map((n) => n.id))

  // Filter edges so they only connect visible nodes
  const visibleEdges = rawEdges.filter(
    (e) => visibleNodeIdSet.has(e.source) && visibleNodeIdSet.has(e.target)
  )

  // 4. Calculate Hierarchical Layout (Tiered by distance / category)
  // Tier 0: Changed (y: 50)
  // Tier 1: Direct (y: 230)
  // Tier 2: Transitive (y: 410)
  // Tier 3: Unaffected (y: 590)
  const tiers = {
    CHANGED: [],
    DIRECT: [],
    TRANSITIVE: [],
    UNAFFECTED: [],
  }

  visibleNodeList.forEach((n) => {
    const list = tiers[n.category] || tiers.UNAFFECTED
    list.push(n)
  })

  const HORIZONTAL_SPACING = 260
  const TIER_HEIGHTS = {
    CHANGED: 50,
    DIRECT: 230,
    TRANSITIVE: 410,
    UNAFFECTED: 590,
  }

  const nodes = []

  Object.entries(tiers).forEach(([tierName, nodeList]) => {
    const y = TIER_HEIGHTS[tierName] || 50
    const totalInTier = nodeList.length

    nodeList.forEach((nodeItem, idx) => {
      // Optically center nodes in each tier
      const x = (idx - (totalInTier - 1) / 2) * HORIZONTAL_SPACING + 400

      const isMatchSearch =
        searchTerm.trim() &&
        (nodeItem.fqn.toLowerCase().includes(searchTerm.toLowerCase()) ||
          nodeItem.simpleName.toLowerCase().includes(searchTerm.toLowerCase()))

      // Attach affected method count for this node if any
      const relatedMethods = affectedMethods.filter(
        (m) => m.declaringType === nodeItem.fqn
      )

      nodes.push({
        id: nodeItem.id,
        type: 'customTypeNode',
        position: { x, y },
        data: {
          ...nodeItem,
          isMatchSearch,
          relatedMethodsCount: relatedMethods.length,
          incomingCount: visibleEdges.filter((e) => e.target === nodeItem.id).length,
          outgoingCount: visibleEdges.filter((e) => e.source === nodeItem.id).length,
        },
      })
    })
  })

  // 5. Compute exact graph statistics
  const stats = {
    totalNodes: nodes.length,
    totalEdges: visibleEdges.length,
    changedNodes: nodes.filter((n) => n.data.category === 'CHANGED').length,
    affectedNodes: nodes.filter(
      (n) => n.data.category === 'DIRECT' || n.data.category === 'TRANSITIVE'
    ).length,
  }

  return {
    nodes,
    edges: visibleEdges,
    stats,
  }
}
