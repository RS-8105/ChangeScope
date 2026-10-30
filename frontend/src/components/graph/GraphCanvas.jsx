import { useEffect, useMemo, useCallback } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  useNodesState,
  useEdgesState,
  useReactFlow,
  ReactFlowProvider,
} from '@xyflow/react'
import CustomGraphNode from './CustomGraphNode.jsx'

function FlowCanvasInternal({
  initialNodes = [],
  initialEdges = [],
  onNodeSelect,
  selectedNodeId,
  forwardedControlsRef,
}) {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)
  const { zoomIn, zoomOut, fitView, setViewport } = useReactFlow()

  const nodeTypes = useMemo(() => ({ customTypeNode: CustomGraphNode }), [])

  // Synchronize internal nodes/edges when transformed data changes
  useEffect(() => {
    setNodes(
      initialNodes.map((n) => ({
        ...n,
        selected: n.id === selectedNodeId,
      }))
    )
  }, [initialNodes, selectedNodeId, setNodes])

  useEffect(() => {
    setEdges(initialEdges)
  }, [initialEdges, setEdges])

  // Expose viewport controls to parent toolbar
  useEffect(() => {
    if (forwardedControlsRef) {
      forwardedControlsRef.current = {
        zoomIn: () => zoomIn({ duration: 300 }),
        zoomOut: () => zoomOut({ duration: 300 }),
        fitView: () => fitView({ padding: 0.2, duration: 400 }),
        resetView: () => {
          setViewport({ x: 0, y: 0, zoom: 1 }, { duration: 400 })
          setTimeout(() => fitView({ padding: 0.2, duration: 400 }), 100)
        },
      }
    }
  }, [forwardedControlsRef, zoomIn, zoomOut, fitView, setViewport])

  const handleNodeClick = useCallback(
    (_, node) => {
      if (onNodeSelect) {
        onNodeSelect(node)
      }
    },
    [onNodeSelect]
  )

  const handlePaneClick = useCallback(() => {
    if (onNodeSelect) {
      onNodeSelect(null)
    }
  }, [onNodeSelect])

  return (
    <div className="w-full h-full relative select-none">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodeClick={handleNodeClick}
        onPaneClick={handlePaneClick}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        colorMode="dark"
        minZoom={0.2}
        maxZoom={2.5}
        defaultEdgeOptions={{
          type: 'smoothstep',
        }}
        proOptions={{ hideAttribution: true }}
      >
        <Background
          color="#303030"
          gap={20}
          size={1.5}
          style={{ backgroundColor: '#171717' }}
        />
        <Controls
          showInteractive={false}
          className="bg-[#242424]! border-[#303030]! fill-[#D4D4D4]! rounded-md! shadow-lg! overflow-hidden!"
        />
      </ReactFlow>
    </div>
  )
}

export default function GraphCanvas(props) {
  return (
    <ReactFlowProvider>
      <FlowCanvasInternal {...props} />
    </ReactFlowProvider>
  )
}
