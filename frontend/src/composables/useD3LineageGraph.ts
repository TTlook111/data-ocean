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

// ========== dagre 图布局 ==========
function layoutGraph(nodes: GraphNode[], edges: GraphEdge[]) {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({
    rankdir: 'LR', // 从左到右
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

export function useD3LineageGraph(containerRef: () => HTMLElement | null, callbacks: GraphCallbacks = {}) {
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

    svg = d3
      .select(container)
      .append('svg')
      .attr('width', width)
      .attr('height', height)
      .style('cursor', 'grab')

    // 缩放/平移行为
    zoomBehavior = d3
      .zoom<SVGSVGElement, unknown>()
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
    dragLine = mainGroup
      .append('line')
      .attr('stroke', '#faad14')
      .attr('stroke-width', 2)
      .attr('stroke-dasharray', '6,4')
      .attr('visibility', 'hidden')
      .attr('marker-end', 'url(#arrowhead)')

    // 箭头标记定义
    const defs = svg.append('defs')
    defs
      .append('marker')
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
    const edgeSelection = mainGroup
      .selectAll<SVGGElement, GraphEdge>('g.edge')
      .data(edges, (d: any) => d.id)
      .join('g')
      .attr('class', 'edge')

    // 路径（曲线）
    edgeSelection.selectAll('path').remove()
    edgeSelection
      .append('path')
      .attr('id', (_e, i) => `edge-label-path-${i}`)
      .attr('d', (e) => {
        const src = nodes.find((n) => n.id === e.sourceId)
        const tgt = nodes.find((n) => n.id === e.targetId)
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
      .attr('stroke-dasharray', (e) => (e.style.dashed ? '6,4' : 'none'))
      .attr('opacity', 0.7)
      .attr('marker-end', 'url(#arrowhead)')

    // 边标签（lineageType）
    edgeSelection.selectAll('text').remove()
    edgeSelection
      .append('text')
      .attr('text-anchor', 'middle')
      .attr('dy', -6)
      .attr('font-size', 10)
      .attr('fill', '#888')
      .append('textPath')
      .attr('href', (_e, i) => `#edge-label-path-${i}`)
      .attr('startOffset', '50%')
      .text((e) => e.label)

    // 边 hover title（tooltip）
    edgeSelection.selectAll('title').remove()
    edgeSelection.append('title').text((e) => {
      const meta = parseRelMeta(e.relationship.relationMetadata)
      return `${e.relationType} · ${e.lineageType}\n${meta.description || ''}\n${meta.created_by ? '操作人: ' + meta.created_by : ''}`
    })

    // 边右键
    edgeSelection.on('contextmenu', (event, e) => {
      event.preventDefault()
      callbacks.onEdgeContextMenu?.(e, event)
    })

    // --- 绘制节点 ---
    const nodeSelection = mainGroup
      .selectAll<SVGGElement, GraphNode>('g.node')
      .data(nodes, (d: any) => d.id)
      .join('g')
      .attr('class', 'node')
      .attr('transform', (n) => `translate(${(n.x ?? 0) - 80}, ${(n.y ?? 0) - 28})`)
      .style('cursor', 'pointer')
      .attr('tabindex', 0)
      .attr('role', 'button')
      .attr('aria-label', (n) => `${n.label}，实体 ${n.id}`)
      .on('keydown', (event, n) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          callbacks.onNodeClick?.(n)
        }
      })

    // 节点矩形
    nodeSelection.selectAll('rect').remove()
    const isTable = (n: GraphNode) => n.entity.entityType === 'TABLE'
    nodeSelection
      .append('rect')
      .attr('width', (n) => (isTable(n) ? 160 : 100))
      .attr('height', (n) => (isTable(n) ? 56 : 32))
      .attr('rx', 8)
      .attr('fill', (n) => (isTable(n) ? '#fffefa' : '#f2f0e9'))
      .attr('stroke', (n) => (isTable(n) ? '#b7bcb2' : '#d1ccc1'))
      .attr('stroke-width', 1.5)
      .attr('filter', 'drop-shadow(0 2px 4px rgba(0,0,0,0.1))')

    // 节点文字
    nodeSelection.selectAll('text').remove()
    nodeSelection
      .append('text')
      .attr('x', 12)
      .attr('y', (n) => (isTable(n) ? 30 : 21))
      .attr('fill', '#303932')
      .attr('font-size', (n) => (isTable(n) ? 13 : 11))
      .attr('font-weight', (n) => (isTable(n) ? 600 : 400))
      .text((n) => n.label)

    // 节点类型标签
    nodeSelection
      .append('text')
      .attr('x', 12)
      .attr('y', (n) => (isTable(n) ? 46 : 20))
      .attr('fill', '#777e74')
      .attr('font-size', 10)
      .text((n) => (isTable(n) ? 'TABLE' : ''))

    // 节点 hover title
    nodeSelection.selectAll('title').remove()
    nodeSelection
      .append('title')
      .text(
        (n) =>
          `${n.entity.entityType}: ${n.entity.displayName || n.entity.name}\n${n.entity.fqn}\n${n.entity.description || ''}`,
      )

    // 节点点击
    nodeSelection.on('click', (_event, n) => {
      callbacks.onNodeClick?.(n)
    })

    // 节点右键
    nodeSelection.on('contextmenu', (event, n) => {
      event.preventDefault()
      callbacks.onNodeContextMenu?.(n, event)
    })

    // One drag behavior: ordinary drag moves; Shift-drag explicitly requests a relationship.
    // D3 pointer against the transformed group applies the inverse SVG CTM after zoom/pan.
    let connecting = false
    const nodeDrag = d3
      .drag<SVGGElement, GraphNode>()
      .container(() => mainGroup!.node()!)
      .on('start', function (event, n) {
        connecting = event.sourceEvent.shiftKey === true
        d3.select(this).raise()
        if (connecting) {
          dragSourceNodeId = n.id
          dragLine
            ?.attr('visibility', 'visible')
            .attr('x1', n.x ?? 0)
            .attr('y1', n.y ?? 0)
            .attr('x2', event.x)
            .attr('y2', event.y)
        } else svg?.style('cursor', 'grabbing')
      })
      .on('drag', function (event, n) {
        if (connecting) {
          dragLine?.attr('x2', event.x).attr('y2', event.y)
          return
        }
        n.x = (n.x ?? 0) + event.dx
        n.y = (n.y ?? 0) + event.dy
        d3.select(this).attr('transform', `translate(${(n.x ?? 0) - 80}, ${(n.y ?? 0) - 28})`)
        updateEdgePositions(nodes)
      })
      .on('end', (event) => {
        svg?.style('cursor', 'grab')
        dragLine?.attr('visibility', 'hidden')
        if (connecting && dragSourceNodeId && mainGroup) {
          const [x, y] = d3.pointer(event.sourceEvent, mainGroup.node())
          const target = nodes.find((n) => {
            const width = n.entity.entityType === 'TABLE' ? 160 : 100,
              height = n.entity.entityType === 'TABLE' ? 56 : 32
            return (
              x >= (n.x ?? 0) - 80 &&
              x <= (n.x ?? 0) - 80 + width &&
              y >= (n.y ?? 0) - 28 &&
              y <= (n.y ?? 0) - 28 + height
            )
          })
          if (target && target.id !== dragSourceNodeId)
            callbacks.onEdgeDragCreate?.(dragSourceNodeId, target.id)
        }
        dragSourceNodeId = null
        connecting = false
      })
    nodeSelection.call(nodeDrag)
  }

  /** 更新边路径位置 */
  function updateEdgePositions(nodes: GraphNode[]) {
    mainGroup?.selectAll<SVGGElement, GraphEdge>('g.edge path').attr('d', (e) => {
      const src = nodes.find((n) => n.id === e.sourceId)
      const tgt = nodes.find((n) => n.id === e.targetId)
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

  /** 异步导出 PNG */
  async function exportPngAsync(): Promise<string> {
    const node = svg?.node()
    if (!node) throw new Error('图谱尚未就绪')
    const { width, height } = node.getBoundingClientRect()
    if (!width || !height || width * height > 16000000) throw new Error('导出尺寸无效')
    const clone = node.cloneNode(true) as SVGSVGElement
    clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
    clone.setAttribute('width', String(width))
    clone.setAttribute('height', String(height))
    const url = URL.createObjectURL(
      new Blob([new XMLSerializer().serializeToString(clone)], { type: 'image/svg+xml;charset=utf-8' }),
    )
    return new Promise((resolve, reject) => {
      const img = new Image()
      const cleanup = () => {
        clearTimeout(timer)
        URL.revokeObjectURL(url)
        img.onload = null
        img.onerror = null
      }
      const timer = setTimeout(() => {
        cleanup()
        reject(new Error('图谱导出超时'))
      }, 10000)
      img.onerror = () => {
        cleanup()
        reject(new Error('图谱图像无法读取'))
      }
      img.onload = () => {
        try {
          const canvas = document.createElement('canvas')
          canvas.width = Math.ceil(width * 2)
          canvas.height = Math.ceil(height * 2)
          const ctx = canvas.getContext('2d')
          if (!ctx) throw new Error('画布不可用')
          ctx.scale(2, 2)
          ctx.fillStyle = '#fffefa'
          ctx.fillRect(0, 0, width, height)
          ctx.drawImage(img, 0, 0, width, height)
          resolve(canvas.toDataURL('image/png'))
        } catch (cause) {
          reject(cause)
        } finally {
          cleanup()
        }
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
  try {
    return JSON.parse(metaStr)
  } catch {
    return {}
  }
}
