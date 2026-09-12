<script setup lang="ts">
/**
 * 数据血缘统一工作台
 *
 * 合并原 LineageViewer.vue（搜索+影响分析）和 LineageGraph.vue（可视化图谱）
 * 为单一页面。三栏布局：左侧筛选面板 → 主区域图谱 → 右侧列血缘抽屉。
 * 与文档 data-lineage-research.md §4.1.3 完全一致。
 *
 * 设计原则：
 * - Vue 组件（面板/弹窗/抽屉）通过事件总线与图谱渲染层通信
 * - 图谱渲染层与 Vue 层解耦，便于后续替换渲染实现
 *
 * **数据源范围来自全局上下文，页面不再自建选择器。** 原先页面自己有一个数据源下拉
 * 并持久化到 `localStorage`，而路由的 `contextMode` 是 `datasource`，`AdminShell` 会
 * 另外渲染一个 `ScopeBar` —— 同一页面两个数据源选择器且互不同步，违反 §6.2 规则 8，
 * 也让 URL 不可分享（不读 `route.query.datasourceId`）。
 */
import { ref, onMounted, onBeforeUnmount, watch, nextTick, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useD3LineageGraph, type GraphNode, type GraphEdge } from '../../../composables/useD3LineageGraph'
import {
  Search, Plus, Upload, Download,
  Network, Filter, Sliders, MoreHorizontal, Crosshair
} from 'lucide-vue-next'
import {
  getEntitiesByDatasource,
  getEntityLineage,
  searchCatalog,
  type MetadataEntityItem,
  type MetadataRelationshipItem,
} from '../../../api/admin/catalog'
import {
  getEnrichedLineage,
  getColumnLineage,
  deleteLineage,
  type LineageGraphVO,
  type ColumnLineageVO,
} from '../../../api/admin/lineageApi'
import { analyzeImpact, type ImpactAnalysisVO } from '../../../api/admin/audit'
import { useAdminContextStore } from '../../../stores/adminContext'
import { entityTypeLabel, lineageTypeLabel } from '../../../utils/enumLabels'
import AddLineageDialog from './AddLineageDialog.vue'

const route = useRoute()
const adminContext = useAdminContextStore()

// ========== 数据源（来自全局上下文，可被 URL 覆盖） ==========
const datasourceId = computed<number | null>(() => {
  const fromUrl = Number(route.query.datasourceId) || undefined
  return fromUrl ?? adminContext.datasourceId ?? null
})

// ========== 图谱数据 ==========
const entities = ref<MetadataEntityItem[]>([])
const relationships = ref<MetadataRelationshipItem[]>([])
const loading = ref(false)

// ========== 筛选条件 ==========
const searchQuery = ref('')
const lineageTypeFilter = ref<string[]>(['QUERY', 'ETL', 'MANUAL'])
const depth = ref(3)

/**
 * 血缘方向（§7.17「上下游方向和深度」）。
 *
 * 有双重作用：
 * - 表格图谱层：对已取回的边做**有向可达性过滤**（后端血缘接口不接受方向参数）
 * - 列血缘抽屉：直接作为 `getColumnLineage` 的 `direction` 参数（该接口支持方向）
 */
const direction = ref<'both' | 'upstream' | 'downstream'>('both')

/** 图谱的有向可达性过滤；`both` 时原样返回。焦点实体缺失时不改变结果 */
function edgesByDirection(rels: MetadataRelationshipItem[]): MetadataRelationshipItem[] {
  const focusId = focusEntityId.value
  if (direction.value === 'both' || !focusId) return rels
  const upstream = direction.value === 'upstream'
  const adjacency = new Map<string, MetadataRelationshipItem[]>()
  for (const rel of rels) {
    const from = upstream ? String(rel.targetId) : String(rel.sourceId)
    const list = adjacency.get(from)
    if (list) list.push(rel)
    else adjacency.set(from, [rel])
  }
  const keep = new Set<MetadataRelationshipItem>()
  const queue = [focusId]
  const visited = new Set<string>([focusId])
  while (queue.length) {
    const current = queue.shift() as string
    for (const rel of adjacency.get(current) || []) {
      keep.add(rel)
      const next = upstream ? String(rel.sourceId) : String(rel.targetId)
      if (!visited.has(next)) {
        visited.add(next)
        queue.push(next)
      }
    }
  }
  return rels.filter((rel) => keep.has(rel))
}

/** 图谱的焦点实体：加载根表，或用户当前选中的实体 */
const focusEntityId = ref<string>('')

/**
 * 血缘图谱的分类色板。
 *
 * **必须是字面色值**：这些值直接交给 D3 渲染，CSS 变量在 canvas/svg 属性里不会自动解析。
 * 图例也引用同一常量而不是各自写字面量，避免图例与图形配色漂移。
 * 分类色板属图表自身的编码体系，不并入 `--do-*` 设计令牌。
 */
const LINEAGE_COLORS: Record<string, string> = {
  QUERY: '#4d8fdc',
  ETL: '#52c41a',
  MANUAL: '#faad14',
}
/** 列派生边（虚线） */
const DERIVED_EDGE_COLOR = '#91d5ff'
/** 无血缘类型时的兜底边色 */
const FALLBACK_EDGE_COLOR = '#999'

// ========== 选中状态 ==========
const selectedEntity = ref<MetadataEntityItem | null>(null)
const drawerVisible = ref(false)
const drawerEntity = ref<MetadataEntityItem | null>(null)
const drawerLoading = ref(false)
const columnLineageData = ref<ColumnLineageVO | null>(null)

// ========== 弹窗控制 ==========
const addDialogVisible = ref(false)
const prefilledSourceId = ref<number | null>(null)

// ========== 图谱渲染 (D3.js + dagre) ==========
const chartRef = ref<HTMLElement | null>(null)
const {
  init: d3Init,
  render: d3Render,
  destroy: d3Destroy,
  resize: d3Resize,
  exportPngAsync: d3ExportPng,
} = useD3LineageGraph(
  () => chartRef.value,
  {
    onNodeClick: (node) => {
      handleNodeClick(node)
    },
    onNodeContextMenu: (node, event) => {
      contextMenu.value = {
        visible: true,
        x: event.clientX,
        y: event.clientY,
        type: 'node',
        data: { _entity: node.entity, id: node.id, name: node.label },
      }
    },
    onEdgeContextMenu: (_edge, event) => {
      // 查找对应的 relationship 数据
      const rel = relationships.value.find(
        r => String(r.sourceId) === _edge.sourceId && String(r.targetId) === _edge.targetId,
      )
      contextMenu.value = {
        visible: true,
        x: event.clientX,
        y: event.clientY,
        type: 'edge',
        data: rel ? { id: rel.id, sourceId: rel.sourceId, targetId: rel.targetId, relationType: rel.relationType } : null,
      }
    },
    onBlankContextMenu: (event) => {
      contextMenu.value = {
        visible: true,
        x: event.clientX,
        y: event.clientY,
        type: 'blank',
        data: null,
      }
    },
    onEdgeDragCreate: (sourceId) => {
      prefilledSourceId.value = Number(sourceId)
      addDialogVisible.value = true
      // 目标实体 ID 可通过预填方式处理
      ElMessage.info(`已预填源节点，请在弹窗中选择目标表`)
    },
  },
)

// 右键菜单
const contextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  type: '' as 'node' | 'edge' | 'blank',
  data: null as any,
})

// ========== 初始化生命周期 ==========

/** 加载图谱数据 */
async function loadGraph() {
  if (!datasourceId.value) return
  loading.value = true
  try {
    // 先加载该数据源所有实体
    const entRes = await getEntitiesByDatasource(datasourceId.value)
    entities.value = entRes.data ?? []

    // 加载血缘关系（优先使用增强 API，回退到逐个查询）
    const tableEntities = entities.value.filter((e) => e.entityType === 'TABLE')
    // 图谱以第一个表为加载根，也是方向过滤的默认焦点
    focusEntityId.value = tableEntities.length ? String(tableEntities[0].id) : ''
    if (tableEntities.length === 0) {
      relationships.value = []
      await nextTick()
      renderGraph()
      return
    }

    // 使用第一个表的增强血缘 API 获取图谱
    try {
      const enrichedRes = await getEnrichedLineage(
        tableEntities[0].id,
        depth.value,
        lineageTypeFilter.value,
      )
      if (enrichedRes.data && 'edges' in enrichedRes.data) {
        const graphVo = enrichedRes.data as LineageGraphVO
        relationships.value = graphVo.edges
        // 合并节点（增强 API 返回的节点和本地实体列表）
        const existingIds = new Set(entities.value.map((e) => e.id))
        for (const node of graphVo.nodes) {
          if (!existingIds.has(node.id)) {
            entities.value.push(node)
          }
        }
        await nextTick()
        renderGraph()
        return
      }
    } catch {
      // 增强 API 失败，回退到逐个查询
    }

    // 回退：逐个查询每个实体的血缘
    const lineagePromises = tableEntities.map((e) => getEntityLineage(e.id))
    const lineageResults = await Promise.allSettled(lineagePromises)
    const allRels: MetadataRelationshipItem[] = []
    for (const r of lineageResults) {
      if (r.status === 'fulfilled' && r.value.data) {
        allRels.push(...r.value.data)
      }
    }
    // 去重
    const seen = new Set<string>()
    relationships.value = allRels.filter((r) => {
      const key = `${r.sourceId}-${r.targetId}-${r.relationType}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })

    await nextTick()
    renderGraph()
  } catch {
    ElMessage.error('血缘图谱加载失败')
  } finally {
    loading.value = false
  }
}

/** 构建 D3 图谱数据并渲染 */
function renderGraph() {
  if (!chartRef.value) return

  const tableAndColumnEntities = entities.value.filter(
    (e) => e.entityType === 'TABLE' || e.entityType === 'COLUMN',
  )

  // 构建节点
  const nodeMap = new Map<string, GraphNode>()
  for (const e of tableAndColumnEntities) {
    nodeMap.set(String(e.id), {
      id: String(e.id),
      label: e.displayName || e.name,
      entity: e,
    })
  }

  // 构建边
  const d3Edges: GraphEdge[] = []

  const directionalRelationships = edgesByDirection(relationships.value)

  for (const rel of directionalRelationships) {
    if (!nodeMap.has(String(rel.sourceId)) || !nodeMap.has(String(rel.targetId))) continue
    if (rel.relationType !== 'LINEAGE' && rel.relationType !== 'FOREIGN_KEY' && rel.relationType !== 'DERIVED_FROM') continue

    let lineageType = 'MANUAL'
    if (rel.relationMetadata) {
      try {
        const meta = JSON.parse(rel.relationMetadata)
        lineageType = meta.lineage_type || 'MANUAL'
      } catch { /* ignore */ }
    }
    const isDerived = rel.relationType === 'DERIVED_FROM'

    d3Edges.push({
      id: String(rel.id),
      sourceId: String(rel.sourceId),
      targetId: String(rel.targetId),
      label: lineageType,
      relationType: rel.relationType,
      lineageType,
      style: {
        color: isDerived ? DERIVED_EDGE_COLOR : (LINEAGE_COLORS[lineageType] || FALLBACK_EDGE_COLOR),
        width: isDerived ? 1 : (rel.relationType === 'FOREIGN_KEY' ? 2.5 : 2),
        dashed: isDerived,
      },
      relationship: rel,
    })
  }

  // 若无血缘边，回退到 CONTAINS/HAS_PART
  const hasLineageEdges = d3Edges.some(e => e.relationType !== 'CONTAINS' && e.relationType !== 'HAS_PART')
  if (!hasLineageEdges) {
    for (const rel of directionalRelationships) {
      if (!nodeMap.has(String(rel.sourceId)) || !nodeMap.has(String(rel.targetId))) continue
      if (rel.relationType !== 'CONTAINS' && rel.relationType !== 'HAS_PART') continue
      d3Edges.push({
        id: String(rel.id),
        sourceId: String(rel.sourceId),
        targetId: String(rel.targetId),
        label: '',
        relationType: rel.relationType,
        lineageType: '',
        style: { color: FALLBACK_EDGE_COLOR, width: 1, dashed: false },
        relationship: rel,
      })
    }
  }

  d3Init()
  d3Render(Array.from(nodeMap.values()), d3Edges)
}

// ========== 图表交互 ==========

/** 点击节点（D3 回调） */
function handleNodeClick(node: GraphNode) {
  contextMenu.value.visible = false
  selectedEntity.value = node.entity
  // 方向过滤以当前选中实体为焦点；重新渲染使方向筛选立即生效
  focusEntityId.value = String(node.entity.id)
  if (direction.value !== 'both') renderGraph()
  if (node.entity.entityType === 'COLUMN') {
    drawerEntity.value = node.entity
    drawerVisible.value = true
    loadColumnLineage(node.entity.id)
  }
}

/** 加载列级血缘 DERIVED_FROM 链 */
async function loadColumnLineage(columnId: number) {
  drawerLoading.value = true
  columnLineageData.value = null
  try {
    // 方向取自筛选面板；该接口支持 upstream/downstream/both
    const res = await getColumnLineage(columnId, 3, direction.value)
    columnLineageData.value = res.data ?? null
  } catch {
    columnLineageData.value = null
  } finally {
    drawerLoading.value = false
  }
}

/** 解析 relation_metadata JSON 字符串 */
function parseLineageMeta(metaStr: string): Record<string, any> {
  try {
    return JSON.parse(metaStr)
  } catch {
    return {}
  }
}

/** 关闭右键菜单 */
function closeContextMenu() {
  contextMenu.value.visible = false
}

// ========== 右键菜单操作 ==========

/** 添加下游血缘（节点菜单） */
function handleAddDownstream() {
  closeContextMenu()
  const nodeData = contextMenu.value.data
  if (nodeData?._entity) {
    prefilledSourceId.value = nodeData._entity.id
    addDialogVisible.value = true
  }
}

/** 展开上游（节点菜单） */
function handleExpandUpstream() {
  closeContextMenu()
  const nodeData = contextMenu.value.data
  if (nodeData?._entity) {
    loadEntityLineage(nodeData._entity.id)
  }
}

/** 展开下游（节点菜单） */
async function handleExpandDownstream() {
  closeContextMenu()
  const nodeData = contextMenu.value.data
  if (nodeData?._entity) {
    try {
      const res = await getEnrichedLineage(nodeData._entity.id, depth.value, lineageTypeFilter.value)
      if (res.data && 'edges' in res.data) {
        const graphVo = res.data as LineageGraphVO
        // 合并新节点和边
        const existingIds = new Set(entities.value.map((e) => e.id))
        for (const node of graphVo.nodes) {
          if (!existingIds.has(node.id)) entities.value.push(node)
        }
        const existingRelKeys = new Set(relationships.value.map((r) => `${r.sourceId}-${r.targetId}-${r.relationType}`))
        for (const edge of graphVo.edges) {
          const key = `${edge.sourceId}-${edge.targetId}-${edge.relationType}`
          if (!existingRelKeys.has(key)) {
            relationships.value.push(edge)
          }
        }
        await nextTick()
        renderGraph()
      }
    } catch {
      ElMessage.error('展开失败')
    }
  }
}

/** 查看节点详情 */
function handleViewDetail() {
  closeContextMenu()
  const nodeData = contextMenu.value.data
  if (nodeData?._entity) {
    selectedEntity.value = nodeData._entity
  }
}

/** 删除血缘边 */
async function handleDeleteEdge() {
  const edgeData = contextMenu.value.data
  if (!edgeData?.id) return
  closeContextMenu()

  try {
    // 先检查关联的列映射数量
    const checkRes = await deleteLineage(edgeData.id, false)
    const derivedCount = checkRes.data?.derivedCount ?? 0

    if (derivedCount > 0) {
      await ElMessageBox.confirm(
        `该关系下有 ${derivedCount} 条列映射，是否一并删除？`,
        '确认删除',
        { confirmButtonText: '一并删除', cancelButtonText: '仅删除表级关系', distinguishCancelAndClose: true },
      ).then(async () => {
        await deleteLineage(edgeData.id, true)
        ElMessage.success('血缘关系及列映射已删除')
      }).catch(async (action: string) => {
        if (action === 'cancel') {
          await deleteLineage(edgeData.id, false)
          ElMessage.success('已删除表级血缘关系，列映射保留为孤边')
        }
      })
    } else {
      await ElMessageBox.confirm('确定删除此血缘关系？', '确认删除', { type: 'warning' })
      await deleteLineage(edgeData.id, false)
      ElMessage.success('血缘关系已删除')
    }

    // 刷新图谱
    await loadGraph()
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e?.response?.data?.message || '删除失败')
    }
  }
}

/** 为指定实体加载血缘 */
async function loadEntityLineage(entityId: number) {
  try {
    const res = await getEnrichedLineage(entityId, depth.value, lineageTypeFilter.value)
    if (res.data && 'edges' in res.data) {
      const graphVo = res.data as LineageGraphVO
      entities.value = graphVo.nodes
      relationships.value = graphVo.edges
      await nextTick()
      renderGraph()
    }
  } catch {
    ElMessage.error('加载血缘失败')
  }
}

// ========== 影响分析（§7.17） ==========
const impactVisible = ref(false)
const impactLoading = ref(false)
const impactError = ref('')
const impact = ref<ImpactAnalysisVO | null>(null)
const impactTarget = ref<MetadataEntityItem | null>(null)

/**
 * 对选中实体做下游影响分析。
 *
 * `analyzeImpact` 早已封装在 `api/admin/audit.ts`，但此前只被已废弃的
 * `LineageViewer.vue` 引用，活页面里没有入口（§7.17 要求「影响分析」）。
 */
async function handleAnalyzeImpact(entity: MetadataEntityItem | null = selectedEntity.value) {
  closeContextMenu()
  if (!entity || !datasourceId.value) {
    ElMessage.warning('请先选择数据源并在图谱中选中一个实体')
    return
  }
  impactTarget.value = entity
  impactVisible.value = true
  impactLoading.value = true
  impactError.value = ''
  impact.value = null
  try {
    impact.value = (
      await analyzeImpact(datasourceId.value, entity.entityType === 'TABLE' ? entity.name : '', entity.name)
    ).data
  } catch (cause) {
    const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
    impactError.value = message || (cause instanceof Error ? cause.message : '影响分析计算失败')
  } finally {
    impactLoading.value = false
  }
}

/** 导出 PNG (D3 异步渲染) */
async function handleExportPng() {
  closeContextMenu()
  try {
    const url = await d3ExportPng()
    if (url) {
      const link = document.createElement('a')
      link.href = url
      link.download = `lineage-dag-${Date.now()}.png`
      link.click()
      ElMessage.success('图谱已导出')
    }
  } catch {
    ElMessage.warning('导出失败，请重试')
  }
}

/** 刷新图谱 */
function handleRefresh() {
  closeContextMenu()
  loadGraph()
}

// ========== 搜索定位 ==========
/** 搜索表名并高亮 */
async function handleSearch() {
  const query = searchQuery.value.trim()
  if (!query) return

  try {
    const res = await searchCatalog({ q: query, type: 'TABLE', datasourceId: datasourceId.value ?? undefined, page: 1, size: 5 })
    const found = (res.data ?? []).find((e) => e.entityType === 'TABLE')
    if (found) {
      // 高亮该节点
      selectedEntity.value = found
      ElMessage.success(`已定位: ${found.displayName || found.name}`)
    } else {
      ElMessage.info('未找到匹配表')
    }
  } catch {
    ElMessage.error('搜索失败')
  }
}

// ========== 批量导入 ==========
const fileInputRef = ref<HTMLInputElement | null>(null)

function handleBatchImport() {
  fileInputRef.value?.click()
}

async function onFileSelected(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  try {
    const { batchCreateLineage } = await import('../../../api/admin/lineageApi')
    await batchCreateLineage(file)
    ElMessage.success('批量导入完成')
    loadGraph()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '批量导入失败')
  } finally {
    target.value = ''
  }
}

// ========== 监听 ==========
// 数据源来自全局上下文或 URL，切换时重新加载图谱（不再写入 localStorage）
watch(datasourceId, (val) => {
  if (val) loadGraph()
})

watch([lineageTypeFilter, depth, direction], () => {
  if (datasourceId.value) loadGraph()
}, { deep: true })

onMounted(async () => {
  await adminContext.initialize()
  loadGraph()
  window.addEventListener('resize', () => d3Resize())
  document.addEventListener('click', closeContextMenu)
})

onBeforeUnmount(() => {
  d3Destroy()
  document.removeEventListener('click', closeContextMenu)
})
</script>

<template>
  <main class="data-lineage-page post-login-page">
    <!-- ===== 左侧面板 (320px) ===== -->
    <aside class="left-panel">
      <!-- 数据源范围由顶部 ScopeBar 提供，页面不再自建选择器（§6.2 规则 8） -->
      <div class="panel-section">
        <label class="panel-label"><Filter :size="14" aria-hidden="true" /> 数据源范围</label>
        <p class="scope-hint">
          <strong>{{ adminContext.currentDatasource?.name || (datasourceId ? `数据源 #${datasourceId}` : '未选择数据源') }}</strong>
          <span>使用顶部的数据源范围条切换</span>
        </p>
      </div>

      <!-- 搜索 -->
      <div class="panel-section">
        <label class="panel-label"><Search :size="14" /> 搜索表/列</label>
        <el-input
          v-model="searchQuery"
          placeholder="输入表名或列名..."
          @keyup.enter="handleSearch"
        >
          <template #append>
            <el-button :icon="Search" aria-label="搜索血缘" @click="handleSearch" />
          </template>
        </el-input>
      </div>

      <!-- 血缘类型过滤 -->
      <div class="panel-section">
        <label class="panel-label"><Sliders :size="14" aria-hidden="true" /> 血缘类型</label>
        <el-checkbox-group v-model="lineageTypeFilter">
          <div class="checkbox-list">
            <el-checkbox value="QUERY" :label="`${lineageTypeLabel('QUERY')}（QUERY）`" />
            <el-checkbox value="ETL" :label="`${lineageTypeLabel('ETL')}（ETL）`" />
            <el-checkbox value="MANUAL" :label="`${lineageTypeLabel('MANUAL')}（MANUAL）`" />
          </div>
        </el-checkbox-group>
      </div>

      <!-- 方向（§7.17「上下游方向和深度」） -->
      <div class="panel-section">
        <label class="panel-label">方向</label>
        <el-radio-group v-model="direction" size="small">
          <el-radio-button value="upstream">上游</el-radio-button>
          <el-radio-button value="downstream">下游</el-radio-button>
          <el-radio-button value="both">双向</el-radio-button>
        </el-radio-group>
        <p class="direction-hint">
          图表方向以当前选中实体为焦点，只保留该方向的连通关系；列血缘抽屉同样按此方向查询。
        </p>
      </div>

      <!-- 深度控制 -->
      <div class="panel-section">
        <label class="panel-label">深度: {{ depth }}</label>
        <el-slider v-model="depth" :min="1" :max="10" :step="1" show-stops />
      </div>

      <!-- 选中实体详情 -->
      <div v-if="selectedEntity" class="panel-section entity-detail">
        <div class="detail-header">
          <el-tag :type="selectedEntity.entityType === 'TABLE' ? 'primary' : 'info'" size="small">
            {{ entityTypeLabel(selectedEntity.entityType) }}
          </el-tag>
          <span class="detail-name">{{ selectedEntity.displayName || selectedEntity.name }}</span>
        </div>
        <div class="detail-meta">
          <div><strong>FQN:</strong> {{ selectedEntity.fqn }}</div>
          <div v-if="selectedEntity.description"><strong>描述:</strong> {{ selectedEntity.description }}</div>
        </div>
      </div>

      <!-- 操作按钮：一个主操作 + 更多菜单（§11.2 一个页面只允许一个最突出的主要操作） -->
      <div class="panel-section panel-actions">
        <el-button type="primary" :icon="Plus" @click="addDialogVisible = true">
          添加血缘
        </el-button>
        <el-dropdown trigger="click">
          <el-button :icon="MoreHorizontal" aria-label="更多图谱操作">更多</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item :icon="Crosshair" :disabled="!selectedEntity" @click="handleAnalyzeImpact()">
                影响分析
              </el-dropdown-item>
              <el-dropdown-item :icon="Upload" @click="handleBatchImport">批量导入</el-dropdown-item>
              <el-dropdown-item :icon="Download" @click="handleExportPng">导出 PNG</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <input
          ref="fileInputRef"
          type="file"
          accept=".csv,.json"
          style="display: none"
          @change="onFileSelected"
        />
      </div>

      <!-- 图例提示 -->
      <div class="panel-section legend-compact">
        <div class="legend-item"><span class="legend-dot" :style="{ background: LINEAGE_COLORS.QUERY }" /> {{ lineageTypeLabel('QUERY') }}</div>
        <div class="legend-item"><span class="legend-dot" :style="{ background: LINEAGE_COLORS.ETL }" /> {{ lineageTypeLabel('ETL') }}</div>
        <div class="legend-item"><span class="legend-dot" :style="{ background: LINEAGE_COLORS.MANUAL }" /> {{ lineageTypeLabel('MANUAL') }}</div>
        <div class="legend-item"><span class="legend-dot" :style="{ background: DERIVED_EDGE_COLOR, border: '1px dashed ' + DERIVED_EDGE_COLOR }" /> 列派生</div>
      </div>
    </aside>

    <!-- ===== 主区域：图谱 ===== -->
    <section class="main-area" v-loading="loading">
      <div v-if="!datasourceId" class="empty-hint">
        <Network :size="48" style="color: var(--do-muted); margin-bottom: 12px;" />
        <p>请先选择数据源查看血缘图谱</p>
      </div>
      <div v-else-if="!loading && entities.length === 0" class="empty-hint">
        <p>该数据源暂无实体数据，请先发布快照</p>
      </div>
      <div v-else ref="chartRef" class="chart-area" />

      <!-- 右键菜单 -->
      <Teleport to="body">
        <div
          v-if="contextMenu.visible"
          class="context-menu"
          :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
          @click.stop
        >
          <!-- 节点菜单 -->
          <template v-if="contextMenu.type === 'node'">
            <button @click="handleViewDetail">📋 查看详情</button>
            <button @click="handleExpandUpstream">🔍 展开上游</button>
            <button @click="handleExpandDownstream">🔍 展开下游</button>
            <button @click="handleAnalyzeImpact(contextMenu.data?._entity)">🎯 影响分析</button>
            <hr />
            <button @click="handleAddDownstream">➕ 添加下游血缘</button>
          </template>
          <!-- 边菜单。后端只有创建与删除血缘的接口，没有更新接口，
               因此这里不提供「编辑」——不做一个点了必然失败的菜单项。 -->
          <template v-else-if="contextMenu.type === 'edge'">
            <button @click="handleViewDetail">📋 查看详情</button>
            <hr />
            <button class="danger" @click="handleDeleteEdge">🗑️ 删除此血缘</button>
          </template>
          <!-- 空白区域菜单 -->
          <template v-else>
            <button @click="addDialogVisible = true">➕ 添加血缘关系</button>
            <button @click="handleRefresh">🔄 刷新图谱</button>
            <button @click="handleExportPng">📷 导出 PNG</button>
          </template>
        </div>
      </Teleport>
    </section>

    <!-- ===== 右侧抽屉：列血缘详情 ===== -->
    <el-drawer
      v-model="drawerVisible"
      title="列血缘详情"
      direction="rtl"
      size="400px"
    >
      <template v-if="drawerEntity">
        <div class="drawer-section">
          <el-tag type="info" size="small">{{ entityTypeLabel(drawerEntity.entityType) }}（{{ drawerEntity.entityType }}）</el-tag>
          <h4 style="margin: 8px 0 4px;">{{ drawerEntity.displayName || drawerEntity.name }}</h4>
          <p class="drawer-fqn">{{ drawerEntity.fqn }}</p>
          <p v-if="drawerEntity.description" class="drawer-desc">{{ drawerEntity.description }}</p>
        </div>

        <el-divider />

        <!-- 上游（来源） -->
        <div class="drawer-section" v-loading="drawerLoading">
          <h4>上游（来源） — DERIVED_FROM</h4>
          <div v-if="columnLineageData?.upstream && columnLineageData.upstream.length > 0">
            <div v-for="(node, i) in columnLineageData.upstream" :key="i" class="lineage-chain-item">
              <div class="chain-entity">
                <el-tag type="primary" size="small" effect="plain">{{ entityTypeLabel(node.entity.entityType) }}（{{ node.entity.entityType }}）</el-tag>
                <strong>{{ node.entity.name }}</strong>
                <span class="chain-fqn">{{ node.entity.fqn }}</span>
              </div>
              <div v-if="node.relationship?.relationMetadata" class="chain-meta">
                <span v-if="parseLineageMeta(node.relationship.relationMetadata).expression" class="chain-expr">
                  {{ parseLineageMeta(node.relationship.relationMetadata).expression }}
                </span>
                <el-tag size="small" type="warning" effect="plain">
                  {{ parseLineageMeta(node.relationship.relationMetadata).expression_type || 'DIRECT' }}
                </el-tag>
              </div>
              <!-- 递归子节点 -->
              <div v-if="node.children && node.children.length" style="margin-left: 16px; border-left: 2px solid var(--do-line); padding-left: 12px;">
                <div v-for="(child, ci) in node.children" :key="ci" class="lineage-chain-item" style="font-size: 12px;">
                  <strong>{{ child.entity.name }}</strong>
                  <span class="chain-fqn">{{ child.entity.fqn }}</span>
                </div>
              </div>
            </div>
          </div>
          <div v-else-if="!drawerLoading" class="drawer-empty">无上游派生关系</div>
        </div>

        <el-divider />

        <!-- 下游（影响） -->
        <div class="drawer-section">
          <h4>下游（影响） — DERIVED_FROM</h4>
          <div v-if="columnLineageData?.downstream && columnLineageData.downstream.length > 0">
            <div v-for="(node, i) in columnLineageData.downstream" :key="i" class="lineage-chain-item">
              <div class="chain-entity">
                <el-tag type="success" size="small" effect="plain">{{ entityTypeLabel(node.entity.entityType) }}（{{ node.entity.entityType }}）</el-tag>
                <strong>{{ node.entity.name }}</strong>
                <span class="chain-fqn">{{ node.entity.fqn }}</span>
              </div>
              <div v-if="node.relationship?.relationMetadata" class="chain-meta">
                <span v-if="parseLineageMeta(node.relationship.relationMetadata).expression" class="chain-expr">
                  {{ parseLineageMeta(node.relationship.relationMetadata).expression }}
                </span>
                <el-tag size="small" type="warning" effect="plain">
                  {{ parseLineageMeta(node.relationship.relationMetadata).expression_type || 'DIRECT' }}
                </el-tag>
              </div>
              <div v-if="node.children && node.children.length" style="margin-left: 16px; border-left: 2px solid var(--do-line); padding-left: 12px;">
                <div v-for="(child, ci) in node.children" :key="ci" class="lineage-chain-item" style="font-size: 12px;">
                  <strong>{{ child.entity.name }}</strong>
                  <span class="chain-fqn">{{ child.entity.fqn }}</span>
                </div>
              </div>
            </div>
          </div>
          <div v-else-if="!drawerLoading" class="drawer-empty">无下游派生关系</div>
        </div>
      </template>
      <el-empty v-else description="请点击图谱中的列节点" />
    </el-drawer>

    <!-- ===== 录入弹窗 ===== -->
    <AddLineageDialog
      v-model:visible="addDialogVisible"
      :prefilled-source-id="prefilledSourceId"
      @created="loadGraph"
    />

    <!-- 影响分析（§7.17） -->
    <el-dialog v-model="impactVisible" title="影响分析" width="520px">
      <LoadingState v-if="impactLoading" variant="skeleton" :rows="4" />
      <ErrorState v-else-if="impactError" :message="impactError" @retry="handleAnalyzeImpact(impactTarget)" />
      <template v-else>
        <dl class="impact-facts">
          <div><dt>分析对象</dt><dd>{{ impactTarget?.displayName || impactTarget?.name || '—' }}</dd></div>
          <div><dt>实体类型</dt><dd>{{ impactTarget ? entityTypeLabel(impactTarget.entityType) : '—' }}</dd></div>
          <div><dt>依赖查询数</dt><dd>{{ impact?.dependentQueryCount ?? 0 }}</dd></div>
        </dl>
        <section class="impact-section">
          <h3>近期依赖该对象的查询任务</h3>
          <p v-if="!impact?.recentQueryTaskIds?.length" class="muted-text">
            没有查到近期依赖该对象的查询任务。这不代表一定无人使用——后端只统计已记录审计日志的查询。
          </p>
          <div v-else class="impact-tasks">
            <el-tag v-for="taskId in impact.recentQueryTaskIds" :key="taskId" size="small">#{{ taskId }}</el-tag>
          </div>
        </section>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.scope-hint {
  display: grid;
  gap: 3px;
  margin: 0;
  font-size: 12px;
}
.scope-hint strong { color: var(--do-ink); }
.scope-hint span { color: var(--do-muted); }
.direction-hint {
  margin: 8px 0 0;
  color: var(--do-muted);
  font-size: 11px;
  line-height: 1.6;
}
.impact-facts {
  display: grid;
  grid-template-columns: 88px 1fr;
  gap: 10px;
  margin: 0 0 16px;
}
.impact-facts dt { color: var(--do-muted); font-size: 12px; }
.impact-facts dd { margin: 0; color: var(--do-ink); font-size: 13px; }
.impact-section { display: grid; gap: 6px; }
.impact-section h3 { margin: 0; color: var(--do-ink); font-size: 14px; }
.impact-tasks { display: flex; flex-wrap: wrap; gap: 6px; }
.muted-text { color: var(--do-muted); font-size: 12px; line-height: 1.7; }

.data-lineage-page {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 0;
  height: calc(100vh - 160px);
  overflow: hidden;
}

/* ===== 左侧面板 ===== */
.left-panel {
  display: flex;
  flex-direction: column;
  gap: 0;
  border-right: 1px solid var(--do-line);
  background: var(--do-surface);
  overflow-y: auto;
  padding: 16px 14px;
}

.panel-section {
  margin-bottom: 16px;
}

.panel-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--do-muted);
}

.checkbox-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.entity-detail {
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-bg);
}

.detail-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.detail-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--do-ink);
}

.detail-meta {
  font-size: 12px;
  color: var(--do-muted);
  line-height: 1.6;
}

.panel-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.legend-compact {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: var(--do-muted);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}

/* ===== 主区域 ===== */
.main-area {
  position: relative;
  background: var(--do-surface);
  overflow: hidden;
}

.chart-area {
  width: 100%;
  height: 100%;
}

.empty-hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--do-muted);
  font-size: 14px;
}

/* ===== 右键菜单 ===== */
.context-menu {
  position: fixed;
  z-index: 9999;
  min-width: 180px;
  padding: 4px 0;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow-hover);
}

.context-menu button {
  display: block;
  width: 100%;
  padding: 8px 16px;
  border: none;
  background: transparent;
  text-align: left;
  font-size: 13px;
  color: var(--do-ink);
  cursor: pointer;
}

.context-menu button:hover {
  background: var(--do-bg);
}

.context-menu button.danger {
  color: var(--do-danger);
}

.context-menu hr {
  margin: 4px 0;
  border: none;
  border-top: 1px solid var(--do-line);
}

/* ===== 右侧抽屉 ===== */
.drawer-section {
  margin-bottom: 16px;
}

.drawer-section h4 {
  margin: 0 0 6px;
  font-size: 14px;
  color: var(--do-ink);
}

.drawer-fqn {
  font-size: 12px;
  color: var(--do-muted);
  font-family: monospace;
  word-break: break-all;
}

.drawer-empty {
  color: var(--do-muted);
  font-size: 13px;
}

.drawer-hint {
  color: var(--do-muted);
  font-size: 12px;
  font-style: italic;
}

.drawer-desc {
  font-size: 12px;
  color: var(--do-muted);
  margin-top: 6px;
  line-height: 1.5;
}

/* 列血缘链 */
.lineage-chain-item {
  padding: 8px 0;
  border-bottom: 1px solid var(--do-line);
}

.lineage-chain-item:last-child {
  border-bottom: none;
}

.chain-entity {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.chain-entity strong {
  font-size: 13px;
  color: var(--do-ink);
}

.chain-fqn {
  font-size: 11px;
  color: var(--do-muted);
  font-family: monospace;
  word-break: break-all;
}

.chain-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  margin-left: 28px;
}

.chain-expr {
  font-family: monospace;
  font-size: 12px;
  color: var(--do-primary-strong);
  padding: 1px 6px;
  background: var(--do-primary-soft);
  border-radius: 4px;
}
</style>
