/**
 * D3.js + dagre 血缘图谱渲染引擎
 *
 * Phase 2：替换 ECharts，提供层次化 DAG 布局和交互式编辑能力。
 * 与 Vue 层组件（左侧面板/弹窗/抽屉）通过事件回调通信，
 * Vue 组件在 Phase 0→Phase 2 过渡中零改动迁移。
 *
 * 参考：Marquez UI (D3.js) + OpenMetadata React Flow 交互设计
 */
import * as d3 from 'd3'
import dagre from 'dagre'
import type { MetadataEntityItem, MetadataRelationshipItem } from '../api/admin/catalog'

// ========== 类型定义 ==========

export interface GraphNode {
  id: string
  label: string
  entity: MetadataEntityItem
  x?: number
  y?: number
}

export interface GraphEdge {
  id: string
  sourceId: string
  targetId: string
  label: string
  relationType: string
  lineageType: string
  style: {
    color: string
    width: number
    dashed: boolean
  }
  relationship: MetadataRelationshipItem
}

export interface GraphCallbacks {
  onNodeClick?: (node: GraphNode) => void
  onNodeContextMenu?: (node: GraphNode, event: MouseEvent) => void
  onEdgeContextMenu?: (edge: GraphEdge, event: MouseEvent) => void
  onBlankContextMenu?: (event: MouseEvent) => void
  onEdgeDragCreate?: (sourceId: string, targetId: string) => void
}

// ========== 线色表 ==========
const LINEAGE_COLORS: Record<string, string> = {
  QUERY: '#4d8fdc',
  ETL: '#52c41a',
  MANUAL: '#faad14',
}
const DERIVED_COLOR = '#91d5ff'

// ========== dagre 图布局 ==========
function layoutGraph(nodes: GraphNode[], edges: GraphEdge[]) {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({
    rankdir: 'LR',        // 从左到右
    nodesep: 60,
    ranksep: 140,
    marginx: 40,
    marginy: 40,
  })

  for (const n of nodes) {
    const isTable = n.entity.entityType === 'TABLE'
    g.setNode(n.id, { width: isTable ? 160 : 100, height: isTable ? 56 : 32 })
  }
  for (const e of edges) {
    g.setEdge(e.sourceId, e.targetId)
  }

  dagre.layout(g)

  for (const n of nodes) {
    const dagreNode = g.node(n.id)
    n.x = dagreNode.x
    n.y = dagreNode.y
  }
}

// ========== 主入口 ==========

export function useD3LineageGraph(
  containerRef: () => HTMLElement | null,
  callbacks: GraphCallbacks = {},
) {
  let svg: d3.Selection<SVGSVGElement, unknown, null, undefined> | null = null
  let mainGroup: d3.Selection<SVGGElement, unknown, null, undefined> | null = null
  let zoomBehavior: d3.ZoomBehavior<SVGSVGElement, unknown> | null = null

  // 拖拽连线状态
  let dragLine: d3.Selection<SVGLineElement, unknown, null, undefined> | null = null
  let dragSourceNodeId: string | null = null

  /** 初始化 SVG 画布 */
  function init() {
    const container = containerRef()
    if (!container) return

    // 清除旧内容
    d3.select(container).selectAll('*').remove()

    const { width, height } = container.getBoundingClientRect()
    if (width === 0 || height === 0) return

    svg = d3.select(container)
      .append('svg')
      .attr('width', width)
      .attr('height', height)
      .style('cursor', 'grab')

    // 缩放/平移行为
    zoomBehavior = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.15, 3])
      .on('zoom', (event) => {
        mainGroup?.attr('transform', event.transform.toString())
      })

    svg.call(zoomBehavior)

    // 关闭双击缩放
    svg.on('dblclick.zoom', null)

    // 主图层
    mainGroup = svg.append('g')

    // 拖拽连线预览线（隐藏）
    dragLine = mainGroup.append('line')
      .attr('stroke', '#faad14')
      .attr('stroke-width', 2)
      .attr('stroke-dasharray', '6,4')
      .attr('visibility', 'hidden')
      .attr('marker-end', 'url(#arrowhead)')

    // 箭头标记定义
    const defs = svg.append('defs')
    defs.append('marker')
      .attr('id', 'arrowhead')
      .attr('viewBox', '0 0 10 10')
      .attr('refX', 10)
      .attr('refY', 5)
      .attr('markerWidth', 8)
      .attr('markerHeight', 8)
      .attr('orient', 'auto')
      .append('path')
      .attr('d', 'M 0 0 L 10 5 L 0 10 z')
      .attr('fill', '#999')
  }

  /** 渲染图谱 */
  function render(nodes: GraphNode[], edges: GraphEdge[]) {
    if (!svg || !mainGroup) return

    // 布局计算
    layoutGraph(nodes, edges)

    // --- 绘制边 ---
    const edgeSelection = mainGroup.selectAll<SVGGElement, GraphEdge>('g.edge')
      .data(edges, (d: any) => d.id)
      .join('g')
      .attr('class', 'edge')

    // 路径（曲线）
    edgeSelection.selectAll('path').remove()
    edgeSelection.append('path')
      .attr('d', (e) => {
        const src = nodes.find(n => n.id === e.sourceId)
        const tgt = nodes.find(n => n.id === e.targetId)
        if (!src || !tgt) return ''
        const sx = src.x ?? 0
        const sy = src.y ?? 0
        const tx = tgt.x ?? 0
        const ty = tgt.y ?? 0
        const dx = tx - sx
        const mx = sx + dx * 0.5
        return `M ${sx} ${sy} C ${mx} ${sy}, ${mx} ${ty}, ${tx} ${ty}`
      })
      .attr('fill', 'none')
      .attr('stroke', (e) => e.style.color)
      .attr('stroke-width', (e) => e.style.width)
      .attr('stroke-dasharray', (e) => e.style.dashed ? '6,4' : 'none')
      .attr('opacity', 0.7)
      .attr('marker-end', 'url(#arrowhead)')

    // 边标签（lineageType）
    edgeSelection.selectAll('text').remove()
    edgeSelection.append('text')
      .attr('text-anchor', 'middle')
      .attr('dy', -6)
      .attr('font-size', 10)
      .attr('fill', '#888')
      .append('textPath')
      .attr('href', (_e, i) => `#edge-label-path-${i}`)
      .text((e) => e.lineageType)

    // 边 hover title（tooltip）
    edgeSelection.append('title')
      .text((e) => {
        const meta = parseRelMeta(e.relationship.relationMetadata)
        return `${e.relationType} · ${e.lineageType}\n${meta.description || ''}\n${meta.created_by ? '操作人: ' + meta.created_by : ''}`
      })

    // 边右键
    edgeSelection.on('contextmenu', (event, e) => {
      event.preventDefault()
      callbacks.onEdgeContextMenu?.(e, event)
    })

    // --- 绘制节点 ---
    const nodeSelection = mainGroup.selectAll<SVGGElement, GraphNode>('g.node')
      .data(nodes, (d: any) => d.id)
      .join('g')
      .attr('class', 'node')
      .attr('transform', (n) => `translate(${(n.x ?? 0) - 80}, ${(n.y ?? 0) - 28})`)
      .style('cursor', 'pointer')

    // 节点矩形
    nodeSelection.selectAll('rect').remove()
    const isTable = (n: GraphNode) => n.entity.entityType === 'TABLE'
    nodeSelection.append('rect')
      .attr('width', (n) => isTable(n) ? 160 : 100)
      .attr('height', (n) => isTable(n) ? 56 : 32)
      .attr('rx', 8)
      .attr('fill', (n) => isTable(n) ? '#4d8fdc' : '#e6f7ff')
      .attr('stroke', (n) => isTable(n) ? '#2f73bd' : '#91d5ff')
      .attr('stroke-width', 1.5)
      .attr('filter', 'drop-shadow(0 2px 4px rgba(0,0,0,0.1))')

    // 节点文字
    nodeSelection.selectAll('text').remove()
    nodeSelection.append('text')
      .attr('x', 12)
      .attr('y', (n) => isTable(n) ? 30 : 21)
      .attr('fill', (n) => isTable(n) ? '#fff' : '#333')
      .attr('font-size', (n) => isTable(n) ? 13 : 11)
      .attr('font-weight', (n) => isTable(n) ? 600 : 400)
      .text((n) => n.label)

    // 节点类型标签
    nodeSelection.append('text')
      .attr('x', 12)
      .attr('y', (n) => isTable(n) ? 46 : 20)
      .attr('fill', (n) => isTable(n) ? 'rgba(255,255,255,0.7)' : '#999')
      .attr('font-size', 10)
      .text((n) => n.entity.entityType)

    // 节点 hover title
    nodeSelection.append('title')
      .text((n) => `${n.entity.entityType}: ${n.entity.displayName || n.entity.name}\n${n.entity.fqn}\n${n.entity.description || ''}`)

    // 节点点击
    nodeSelection.on('click', (_event, n) => {
      callbacks.onNodeClick?.(n)
    })

    // 节点右键
    nodeSelection.on('contextmenu', (event, n) => {
      event.preventDefault()
      callbacks.onNodeContextMenu?.(n, event)
    })

    // 节点拖拽（移动位置）
    const nodeDrag = d3.drag<SVGGElement, GraphNode>()
      .on('start', function () {
        d3.select(this).raise()
        svg?.style('cursor', 'grabbing')
      })
      .on('drag', (event, n) => {
        n.x = (n.x ?? 0) + event.dx
        n.y = (n.y ?? 0) + event.dy
        d3.select(this).attr('transform', `translate(${(n.x ?? 0) - 80}, ${(n.y ?? 0) - 28})`)
        // 更新关联边
        updateEdgePositions(nodes, edges)
      })
      .on('end', () => {
        svg?.style('cursor', 'grab')
      })

    nodeSelection.call(nodeDrag as any)

    // 拖拽连线：从节点拖出创建边
    const connectDrag = d3.drag<SVGGElement, GraphNode>()
      .on('start', (event, n) => {
        dragSourceNodeId = n.id
        dragLine?.attr('visibility', 'visible')
          .attr('x1', n.x ?? 0)
          .attr('y1', n.y ?? 0)
          .attr('x2', event.x)
          .attr('y2', event.y)
      })
      .on('drag', (event) => {
        dragLine?.attr('x2', event.x).attr('y2', event.y)
      })
      .on('end', (event) => {
        dragLine?.attr('visibility', 'hidden')
        // 检测释放位置是否在另一个节点上
        const container = containerRef()
        if (!container || !dragSourceNodeId) return
        const rect = container.getBoundingClientRect()
        const mx = event.sourceEvent.clientX - rect.left
        const my = event.sourceEvent.clientY - rect.top
        const target = nodes.find(n => {
          const nx = n.x ?? 0
          const ny = n.y ?? 0
          return mx >= nx - 80 && mx <= nx + 80 && my >= ny - 28 && my <= ny + 28
        })
        if (target && target.id !== dragSourceNodeId) {
          callbacks.onEdgeDragCreate?.(dragSourceNodeId, target.id)
        }
        dragSourceNodeId = null
      })

    nodeSelection.call(connectDrag as any)
  }

  /** 更新边路径位置 */
  function updateEdgePositions(nodes: GraphNode[], edges: GraphEdge[]) {
    mainGroup?.selectAll<SVGGElement, GraphEdge>('g.edge path')
      .attr('d', (e) => {
        const src = nodes.find(n => n.id === e.sourceId)
        const tgt = nodes.find(n => n.id === e.targetId)
        if (!src || !tgt) return ''
        const sx = src.x ?? 0
        const sy = src.y ?? 0
        const tx = tgt.x ?? 0
        const ty = tgt.y ?? 0
        const dx = tx - sx
        const mx = sx + dx * 0.5
        return `M ${sx} ${sy} C ${mx} ${sy}, ${mx} ${ty}, ${tx} ${ty}`
      })
  }

  /** 导出 PNG */
  function exportPng(): string | null {
    if (!svg) return null
    const svgNode = svg.node()
    if (!svgNode) return null
    const serializer = new XMLSerializer()
    const svgStr = serializer.serializeToString(svgNode)
    const canvas = document.createElement('canvas')
    const ctx = canvas.getContext('2d')
    if (!ctx) return null
    const img = new Image()
    const blob = new Blob([svgStr], { type: 'image/svg+xml;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    // 同步不可能，返回数据 URL 不可直接使用，改为同步方式
    return null // 异步导出见下方 exportPngAsync
  }

  /** 异步导出 PNG */
  async function exportPngAsync(): Promise<string> {
    return new Promise((resolve) => {
      if (!svg) return resolve('')
      const svgNode = svg.node()
      if (!svgNode) return resolve('')
      const clone = svgNode.cloneNode(true) as SVGSVGElement
      const data = new XMLSerializer().serializeToString(clone)
      const blob = new Blob([data], { type: 'image/svg+xml;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const img = new Image()
      img.onload = () => {
        const canvas = document.createElement('canvas')
        canvas.width = clone.clientWidth * 2
        canvas.height = clone.clientHeight * 2
        const ctx = canvas.getContext('2d')!
        ctx.scale(2, 2)
        ctx.fillStyle = '#ffffff'
        ctx.fillRect(0, 0, canvas.width, canvas.height)
        ctx.drawImage(img, 0, 0)
        URL.revokeObjectURL(url)
        resolve(canvas.toDataURL('image/png'))
      }
      img.src = url
    })
  }

  /** 销毁 */
  function destroy() {
    const container = containerRef()
    if (container) d3.select(container).selectAll('*').remove()
    svg = null
    mainGroup = null
  }

  /** 自适应 */
  function resize() {
    const container = containerRef()
    if (!container || !svg) return
    const { width, height } = container.getBoundingClientRect()
    if (width === 0 || height === 0) return
    svg.attr('width', width).attr('height', height)
  }

  return { init, render, destroy, resize, exportPngAsync }
}

/** 解析 relation_metadata JSON */
function parseRelMeta(metaStr?: string): Record<string, any> {
  if (!metaStr) return {}
  try { return JSON.parse(metaStr) } catch { return {} }
}
