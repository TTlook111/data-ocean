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
 * - Phase 2 将图谱从 ECharts 替换为 D3.js 时，Vue 层组件直接复用
 */
import { ref, onMounted, onBeforeUnmount, watch, nextTick, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useD3LineageGraph, type GraphNode, type GraphEdge } from '../../../composables/useD3LineageGraph'
import {
  Search, Plus, Upload, Download, X,
  Network, Filter, Sliders
} from 'lucide-vue-next'
import { listMyDatasources, type UserDatasourceItem } from '../../../api/datasource'
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
import AddLineageDialog from './AddLineageDialog.vue'

// ========== 数据源 ==========
const datasourceId = ref<number | null>(null)
const datasources = ref<UserDatasourceItem[]>([])

// ========== 图谱数据 ==========
const entities = ref<MetadataEntityItem[]>([])
const relationships = ref<MetadataRelationshipItem[]>([])
const loading = ref(false)

// ========== 筛选条件 ==========
const searchQuery = ref('')
const lineageTypeFilter = ref<string[]>(['QUERY', 'ETL', 'MANUAL'])
const depth = ref(3)

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
    onEdgeDragCreate: (sourceId, targetId) => {
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
const LINEAGE_COLORS: Record<string, string> = {
  QUERY: '#4d8fdc',
  ETL: '#52c41a',
  MANUAL: '#faad14',
}

// ========== 初始化生命周期 ==========

/** 加载数据源列表并恢复上次选择 */
async function loadDatasources() {
  try {
    const res = await listMyDatasources()
    datasources.value = res.data ?? []
    // 从 localStorage 恢复上次选择
    const saved = localStorage.getItem('data-lineage-datasource')
    if (saved && datasources.value.some((d) => d.id === Number(saved))) {
      datasourceId.value = Number(saved)
    } else if (datasources.value.length > 0) {
      datasourceId.value = datasources.value[0].id
    }
  } catch {
    ElMessage.error('数据源列表加载失败')
  }
}

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
  const LINEAGE_COLORS: Record<string, string> = {
    QUERY: '#4d8fdc', ETL: '#52c41a', MANUAL: '#faad14',
  }

  for (const rel of relationships.value) {
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
        color: isDerived ? '#91d5ff' : (LINEAGE_COLORS[lineageType] || '#999'),
        width: isDerived ? 1 : (rel.relationType === 'FOREIGN_KEY' ? 2.5 : 2),
        dashed: isDerived,
      },
      relationship: rel,
    })
  }

  // 若无血缘边，回退到 CONTAINS/HAS_PART
  const hasLineageEdges = d3Edges.some(e => e.relationType !== 'CONTAINS' && e.relationType !== 'HAS_PART')
  if (!hasLineageEdges) {
    for (const rel of relationships.value) {
      if (!nodeMap.has(String(rel.sourceId)) || !nodeMap.has(String(rel.targetId))) continue
      if (rel.relationType !== 'CONTAINS' && rel.relationType !== 'HAS_PART') continue
      d3Edges.push({
        id: String(rel.id),
        sourceId: String(rel.sourceId),
        targetId: String(rel.targetId),
        label: '',
        relationType: rel.relationType,
        lineageType: '',
        style: { color: '#cccccc', width: 1, dashed: false },
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
    const res = await getColumnLineage(columnId, 3, 'both')
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

/** 编辑边 */
function handleEditEdge() {
  closeContextMenu()
  ElMessage.info('编辑功能将在 Phase 2 中实现')
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
watch(datasourceId, (val) => {
  if (val) {
    localStorage.setItem('data-lineage-datasource', String(val))
    loadGraph()
  }
})

watch([lineageTypeFilter, depth], () => {
  if (datasourceId.value) loadGraph()
}, { deep: true })

onMounted(() => {
  loadDatasources()
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
      <!-- 数据源选择 -->
      <div class="panel-section">
        <label class="panel-label"><Filter :size="14" /> 数据源</label>
        <el-select
          v-model="datasourceId"
          placeholder="选择数据源"
          filterable
          style="width: 100%"
        >
          <el-option
            v-for="item in datasources"
            :key="item.id"
            :label="`${item.name}${item.databaseName ? ` / ${item.databaseName}` : ''}`"
            :value="item.id"
          />
        </el-select>
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
            <el-button :icon="Search" @click="handleSearch" />
          </template>
        </el-input>
      </div>

      <!-- 血缘类型过滤 -->
      <div class="panel-section">
        <label class="panel-label"><Sliders :size="14" /> 血缘类型</label>
        <el-checkbox-group v-model="lineageTypeFilter">
          <div class="checkbox-list">
            <el-checkbox value="QUERY" label="查询血缘 (QUERY)" />
            <el-checkbox value="ETL" label="ETL 流转 (ETL)" />
            <el-checkbox value="MANUAL" label="手动标注 (MANUAL)" />
          </div>
        </el-checkbox-group>
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
            {{ selectedEntity.entityType }}
          </el-tag>
          <span class="detail-name">{{ selectedEntity.displayName || selectedEntity.name }}</span>
        </div>
        <div class="detail-meta">
          <div><strong>FQN:</strong> {{ selectedEntity.fqn }}</div>
          <div v-if="selectedEntity.description"><strong>描述:</strong> {{ selectedEntity.description }}</div>
        </div>
      </div>

      <!-- 操作按钮 -->
      <div class="panel-section panel-actions">
        <el-button type="primary" :icon="Plus" @click="addDialogVisible = true">
          添加血缘
        </el-button>
        <el-button :icon="Upload" @click="handleBatchImport">
          批量导入
        </el-button>
        <input
          ref="fileInputRef"
          type="file"
          accept=".csv,.json"
          style="display: none"
          @change="onFileSelected"
        />
        <el-button :icon="Download" @click="handleExportPng">
          导出 PNG
        </el-button>
      </div>

      <!-- 图例提示 -->
      <div class="panel-section legend-compact">
        <div class="legend-item"><span class="legend-dot" style="background: #4d8fdc" /> QUERY</div>
        <div class="legend-item"><span class="legend-dot" style="background: #52c41a" /> ETL</div>
        <div class="legend-item"><span class="legend-dot" style="background: #faad14" /> MANUAL</div>
        <div class="legend-item"><span class="legend-dot" style="background: #91d5ff; border: 1px dashed #91d5ff" /> 列派生</div>
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
            <hr />
            <button @click="handleAddDownstream">➕ 添加下游血缘</button>
          </template>
          <!-- 边菜单 -->
          <template v-else-if="contextMenu.type === 'edge'">
            <button @click="handleViewDetail">📋 查看详情</button>
            <button @click="handleEditEdge">✏️ 编辑</button>
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
          <el-tag type="info" size="small">{{ drawerEntity.entityType }}</el-tag>
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
                <el-tag type="primary" size="small" effect="plain">{{ node.entity.entityType }}</el-tag>
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
                <el-tag type="success" size="small" effect="plain">{{ node.entity.entityType }}</el-tag>
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
  </main>
</template>

<style scoped>
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
  background: #fff;
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
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.12);
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
  color: #e74c3c;
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
