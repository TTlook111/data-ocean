<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch, nextTick, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Plus,
  Download,
  Upload,
  RefreshCw,
  Network,
  ChevronDown,
  Table2,
  Columns3,
} from 'lucide-vue-next'
import { useD3LineageGraph, type GraphNode, type GraphEdge } from '../../../composables/useD3LineageGraph'
import {
  getEntitiesByDatasource,
  searchCatalog,
  type MetadataEntityItem,
  type MetadataRelationshipItem,
} from '../../../api/admin/catalog'
import {
  getEnrichedLineage,
  getColumnLineage,
  deleteLineage,
  batchCreateLineage,
  type ColumnLineageVO,
  type ColumnLineageNode,
} from '../../../api/admin/lineageApi'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAuthStore } from '../../../stores/auth'
import { positiveId, entitySource, queryEvidenceTarget } from '../../../utils/lineageContext'
import { pendingLineageWrites, trackLineageWrite } from '../../../composables/lineageWriteTracker'
import { getHttpErrorMessage } from '../../../utils/httpError'
import { buildContextQuery } from '../../../utils/adminNavigation'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import AddLineageDialog from './AddLineageDialog.vue'
import QueryLineageEvidence from './QueryLineageEvidence.vue'

const route = useRoute(),
  router = useRouter(),
  adminContext = useAdminContextStore(),
  iamS1 = useIamS1Store(),
  auth = useAuthStore()
const datasourceId = computed(() =>
  route.query.datasourceId !== undefined
    ? (positiveId(route.query.datasourceId) ?? null)
    : (adminContext.datasourceId ?? null),
)
const routeEntityId = computed(() => positiveId(route.query.entityId))
const invalidContext = computed(
  () =>
    (route.query.datasourceId !== undefined && !positiveId(route.query.datasourceId)) ||
    (route.query.entityId !== undefined && !routeEntityId.value),
)
const canViewLineage = computed(
  () =>
    iamS1.loaded &&
    !iamS1.errorMessage &&
    !!datasourceId.value &&
    iamS1.canOnDatasource('lineage:view', datasourceId.value),
)
const canReadDirectory = computed(
  () => !!datasourceId.value && iamS1.canOnDatasource('metadata:view', datasourceId.value),
)
const canManageLineage = computed(
  () => !!datasourceId.value && iamS1.canOnDatasource('lineage:manage', datasourceId.value),
)
const canViewReleases = computed(
  () => !!datasourceId.value && iamS1.canOnDatasource('metadata:release:view', datasourceId.value),
)
const identity = computed(() => auth.token)
const scopeKey = computed(() =>
  JSON.stringify([
    auth.token,
    iamS1.loaded,
    iamS1.snapshot,
    iamS1.errorMessage,
    datasourceId.value,
    invalidContext.value,
  ]),
)
const MANAGE_HINT = '需要关系两端实际数据源上的 lineage:manage 能力；表搜索和列选择还需对应 metadata:view。'
const entities = ref<MetadataEntityItem[]>([]),
  directory = ref<MetadataEntityItem[]>([]),
  relationships = ref<MetadataRelationshipItem[]>([])
const selectedEntity = ref<MetadataEntityItem | null>(null),
  focusEntityId = ref('')
const loading = ref(false),
  graphError = ref(''),
  directoryError = ref(''),
  routeEntityHint = ref('')
const searchQuery = ref(''),
  searchLoading = ref(false),
  searchResults = ref<MetadataEntityItem[]>([])
const lineageTypeFilter = ref<string[]>(['QUERY', 'ETL', 'MANUAL']),
  depth = ref(3),
  direction = ref<'both' | 'upstream' | 'downstream'>('both')
const activeTab = ref<'graph' | 'queries'>('graph'),
  evidenceTarget = ref<ReturnType<typeof queryEvidenceTarget>>()
const chartRef = ref<HTMLElement | null>(null),
  fileInputRef = ref<HTMLInputElement | null>(null)
const drawerVisible = ref(false),
  drawerEntity = ref<MetadataEntityItem | null>(null),
  drawerLoading = ref(false),
  drawerError = ref(''),
  columnLineageData = ref<ColumnLineageVO | null>(null)
const addDialogVisible = ref(false),
  prefilledSourceId = ref<number | null>(null),
  prefilledTargetId = ref<number | null>(null)
const deleteTarget = ref<MetadataRelationshipItem | null>(null),
  cascadeDerived = ref(false),
  deleting = ref(false),
  deleteError = ref('')
const importing = ref(false),
  importError = ref(''),
  exportBusy = ref(false)
const uncertain = computed(() => pendingLineageWrites.value.filter((x) => x.identity === identity.value))
let scopeEpoch = 0
let graphRequestId = 0,
  columnLineageRequestId = 0,
  searchRequestId = 0,
  exportRequestId = 0,
  impactDisposed = false
const currentDatasourceEntityIds = new Set<number>()
const contextMenu = ref<{
  visible: boolean
  x: number
  y: number
  type: 'node' | 'edge' | 'blank'
  entity?: MetadataEntityItem
  edge?: MetadataRelationshipItem
}>({ visible: false, x: 0, y: 0, type: 'blank' })
const {
  init: d3Init,
  render: d3Render,
  destroy: d3Destroy,
  resize: d3Resize,
  exportPngAsync: d3ExportPng,
} = useD3LineageGraph(() => chartRef.value, {
  onNodeClick: handleNodeClick,
  onNodeContextMenu: (node, event) => {
    contextMenu.value = {
      visible: true,
      x: event.clientX,
      y: event.clientY,
      type: 'node',
      entity: node.entity,
    }
  },
  onEdgeContextMenu: (edge, event) => {
    contextMenu.value = {
      visible: true,
      x: event.clientX,
      y: event.clientY,
      type: 'edge',
      edge: edge.relationship,
    }
  },
  onBlankContextMenu: (event) => {
    contextMenu.value = { visible: true, x: event.clientX, y: event.clientY, type: 'blank' }
  },
  onEdgeDragCreate: (source, target) => {
    const a = entities.value.find((x) => String(x.id) === source),
      b = entities.value.find((x) => String(x.id) === target)
    if (
      !a ||
      !b ||
      a.entityType !== 'TABLE' ||
      b.entityType !== 'TABLE' ||
      !canManageEntity(a) ||
      !canManageEntity(b)
    )
      return
    openCreate(a.id, b.id)
  },
})
function sourceOf(entity: MetadataEntityItem | null) {
  return (
    entitySource(entity) ??
    (entity && currentDatasourceEntityIds.has(entity.id) ? (datasourceId.value ?? undefined) : undefined)
  )
}
function canManageEntity(entity: MetadataEntityItem | null) {
  const source = sourceOf(entity)
  return !!source && canViewLineage.value && iamS1.canOnDatasource('lineage:manage', source)
}
function canDelete(edge: MetadataRelationshipItem) {
  return (
    edge.relationType === 'LINEAGE' &&
    [edge.sourceId, edge.targetId].every((id) =>
      canManageEntity(entities.value.find((x) => x.id === id) || null),
    )
  )
}
function parseLineageMeta(meta?: string): Record<string, any> {
  try {
    return JSON.parse(meta || '{}')
  } catch {
    return {}
  }
}
function relationLabel(edge: MetadataRelationshipItem) {
  if (edge.relationType === 'FOREIGN_KEY') return '结构关联 · FOREIGN_KEY'
  if (edge.relationType === 'DERIVED_FROM') return '字段派生 · DERIVED_FROM'
  if (edge.relationType === 'LINEAGE')
    return `已记录关系 · ${parseLineageMeta(edge.relationMetadata).lineage_type || '来源未标注'}`
  return `结构层级 · ${edge.relationType}`
}
/**
 * 图谱、对象条和画布标题使用的短标签。
 * 展示名可能被同步成很长的原始注释（验收数据里就有这种回填），不再直接铺满标题；
 * 超过 20 个字符时回退到技术名，完整原文仍可从 title 和选中详情里查看。
 */
function entityShortLabel(entity: MetadataEntityItem | null | undefined) {
  if (!entity) return ''
  const display = (entity.displayName || '').trim()
  return display && display.length <= 20 ? display : entity.name
}
/** 与技术名不同的展示名，作为选中详情里的次级说明。 */
function entityAlias(entity: MetadataEntityItem | null | undefined) {
  if (!entity) return ''
  const display = (entity.displayName || '').trim()
  return display && display !== entity.name ? display : ''
}
function syncFocusToUrl(id: number) {
  if (currentDatasourceEntityIds.has(id) && routeEntityId.value !== id)
    void router.replace({ query: { ...route.query, entityId: String(id) } })
}
function clearScope() {
  scopeEpoch++
  graphRequestId++
  columnLineageRequestId++
  searchRequestId++
  exportRequestId++
  d3Destroy()
  entities.value = []
  directory.value = []
  relationships.value = []
  currentDatasourceEntityIds.clear()
  selectedEntity.value = null
  focusEntityId.value = ''
  loading.value = false
  graphError.value = ''
  directoryError.value = ''
  routeEntityHint.value = ''
  searchQuery.value = ''
  searchResults.value = []
  searchLoading.value = false
  drawerVisible.value = false
  drawerEntity.value = null
  columnLineageData.value = null
  drawerError.value = ''
  drawerLoading.value = false
  contextMenu.value.visible = false
  addDialogVisible.value = false
  deleteTarget.value = null
  deleting.value = false
  deleteError.value = ''
  evidenceTarget.value = undefined
  importing.value = false
  importError.value = ''
  exportBusy.value = false
}
/** Exact scope and latest generation own graph reads, including filter and browser-history changes. */
async function loadGraph() {
  if (impactDisposed || !canViewLineage.value || invalidContext.value) return
  const key = scopeKey.value,
    ticket = ++graphRequestId,
    rootId = routeEntityId.value || positiveId(focusEntityId.value)
  const current = () => !impactDisposed && key === scopeKey.value && ticket === graphRequestId
  loading.value = true
  graphError.value = ''
  directoryError.value = ''
  routeEntityHint.value = ''
  entities.value = []
  directory.value = []
  currentDatasourceEntityIds.clear()
  relationships.value = []
  selectedEntity.value = null
  try {
    if (!lineageTypeFilter.value.length) {
      routeEntityHint.value = '未选择关系来源类型，请至少选择一项后读取图谱。'
      return
    }
    let root = rootId
    if (canReadDirectory.value) {
      try {
        const result = await getEntitiesByDatasource(datasourceId.value!)
        if (!current()) return
        directory.value = result.data || []
        currentDatasourceEntityIds.clear()
        directory.value.forEach((x) => currentDatasourceEntityIds.add(x.id))
        if (
          rootId &&
          !directory.value.some((x) => x.id === rootId && ['TABLE', 'COLUMN'].includes(x.entityType))
        )
          throw new Error('指定对象不在当前数据源目录中；请核对链接，未替换为其他对象。')
        root ??= directory.value.find((x) => x.entityType === 'TABLE')?.id
      } catch (cause) {
        if (!current()) return
        directoryError.value = getHttpErrorMessage(cause, '对象目录辅助读取失败')
        // A precise deep link can still be read with lineage:view; metadata:view is an independent helper.
        if (!rootId) throw cause
        if (!directory.value.some((x) => x.id === rootId) && !(cause as any)?.response?.status) throw cause
      }
    } else {
      directory.value = []
      directoryError.value =
        '没有 metadata:view，无法浏览或搜索对象目录。仍可用精确实体 ID 查看授权血缘，或按精确表名读取查询来源。'
    }
    if (!current()) return
    if (!root) {
      routeEntityHint.value = '对象目录来自已发布快照的目录同步；请发布快照并等待同步，或提供精确实体 ID。'
      return
    }
    await readGraphRoot(root, current)
  } catch (cause) {
    if (current()) graphError.value = getHttpErrorMessage(cause, '血缘图谱读取失败')
  } finally {
    if (current()) {
      loading.value = false
      await nextTick()
      if (current()) renderGraph()
    }
  }
}
async function readGraphRoot(root: number, current: () => boolean) {
  const result = await getEnrichedLineage(root, depth.value, [...lineageTypeFilter.value])
  if (!current()) return
  if (!result.data || Array.isArray(result.data))
    throw new Error('接口仅返回兼容的一跳关系，无法确认节点与所选深度；请重试增强图谱。')
  const graph = result.data,
    rootEntity = graph.nodes.find((x) => x.id === root) || directory.value.find((x) => x.id === root)
  if (
    !rootEntity ||
    (entitySource(rootEntity) !== undefined
      ? entitySource(rootEntity) !== datasourceId.value
      : !currentDatasourceEntityIds.has(root))
  )
    throw new Error('无法确认指定实体属于当前数据源，未替换为其他对象。')
  const nodes = graph.nodes.length ? graph.nodes : [rootEntity]
  entities.value = [...new Map(nodes.map((x) => [x.id, x])).values()]
  relationships.value = [...new Map(graph.edges.map((x) => [x.id, x])).values()]
  for (const node of nodes)
    if (entitySource(node) === datasourceId.value) currentDatasourceEntityIds.add(node.id)
  focusEntityId.value = String(root)
  selectedEntity.value = rootEntity
  if (rootEntity.entityType === 'COLUMN') {
    drawerEntity.value = rootEntity
    drawerVisible.value = true
    void loadColumnLineage(root)
  }
}
async function loadEntityLineage(id: number) {
  if (impactDisposed || !canViewLineage.value || !lineageTypeFilter.value.length) return
  const entity = entities.value.find((x) => x.id === id),
    source = sourceOf(entity || null)
  if (source && source !== datasourceId.value) {
    ElMessage.info('跨源节点请先切换至其实际数据源，再读取其关系。')
    return
  }
  focusEntityId.value = String(id)
  syncFocusToUrl(id)
  await loadGraph()
}
function edgesByDirection(rels: MetadataRelationshipItem[]) {
  if (direction.value === 'both' || !focusEntityId.value) return rels
  const upstream = direction.value === 'upstream',
    seen = new Set([focusEntityId.value]),
    keep = new Set<number>(),
    queue = [focusEntityId.value]
  while (queue.length) {
    const id = queue.shift()!
    for (const edge of rels) {
      if (String(upstream ? edge.targetId : edge.sourceId) !== id) continue
      keep.add(edge.id)
      const next = String(upstream ? edge.sourceId : edge.targetId)
      if (!seen.has(next)) {
        seen.add(next)
        queue.push(next)
      }
    }
  }
  return rels.filter((x) => keep.has(x.id))
}
const visibleRelations = computed(() => edgesByDirection(relationships.value))
/** 没有可展示关系时不铺一整片空白画布，改为说明性空态。 */
const graphEmpty = computed(
  () =>
    !loading.value &&
    !graphError.value &&
    !routeEntityHint.value &&
    entities.value.length > 0 &&
    visibleRelations.value.length === 0,
)
const graphEmptyHint = computed(() =>
  relationships.value.length && !visibleRelations.value.length
    ? '当前方向过滤后没有可展示的关系；切回「双向」可查看全部已加载关系。'
    : '这个对象还没有已记录的上下游关系。可以先核对对象目录中的结构与注释，或记录第一条关系。',
)
function renderGraph() {
  if (!chartRef.value || activeTab.value !== 'graph') return
  const edges = visibleRelations.value,
    ids = new Set(edges.flatMap((x) => [x.sourceId, x.targetId]))
  if (focusEntityId.value) ids.add(Number(focusEntityId.value))
  const nodes: GraphNode[] = entities.value
    .filter((x) => ['TABLE', 'COLUMN'].includes(x.entityType) && ids.has(x.id))
    .map((x) => ({ id: String(x.id), label: entityShortLabel(x), entity: x }))
  const palette: Record<string, string> = { QUERY: '#628179', ETL: '#aa603c', MANUAL: '#8c7957' }
  const d3Edges: GraphEdge[] = edges.map((x) => {
    const type = parseLineageMeta(x.relationMetadata).lineage_type || 'UNKNOWN'
    return {
      id: String(x.id),
      sourceId: String(x.sourceId),
      targetId: String(x.targetId),
      label: relationLabel(x),
      relationType: x.relationType,
      lineageType: type,
      relationship: x,
      style: {
        color:
          x.relationType === 'FOREIGN_KEY'
            ? '#939b96'
            : x.relationType === 'DERIVED_FROM'
              ? '#80728f'
              : palette[type] || '#939b96',
        width: 1.6,
        dashed: x.relationType === 'DERIVED_FROM',
      },
    }
  })
  d3Init()
  d3Render(nodes, d3Edges)
}
function handleNodeClick(node: GraphNode) {
  selectedEntity.value = node.entity
  focusEntityId.value = String(node.entity.id)
  contextMenu.value.visible = false
  syncFocusToUrl(node.entity.id)
  if (direction.value !== 'both') renderGraph()
  if (node.entity.entityType === 'COLUMN') {
    drawerEntity.value = node.entity
    drawerVisible.value = true
    void loadColumnLineage(node.entity.id)
  }
}
async function loadColumnLineage(id: number) {
  const entity = drawerEntity.value,
    source = sourceOf(entity),
    key = scopeKey.value,
    ticket = ++columnLineageRequestId
  drawerError.value = ''
  columnLineageData.value = null
  if (!source || !iamS1.canOnDatasource('lineage:view', source)) {
    drawerError.value = '没有此字段实际数据源的 lineage:view 能力，或字段来源未确认。'
    return
  }
  const current = () =>
    !impactDisposed &&
    key === scopeKey.value &&
    ticket === columnLineageRequestId &&
    drawerVisible.value &&
    drawerEntity.value?.id === id
  drawerLoading.value = true
  try {
    const result = await getColumnLineage(id, depth.value, direction.value)
    if (current()) columnLineageData.value = result.data
  } catch (cause) {
    if (current()) drawerError.value = getHttpErrorMessage(cause, '字段派生读取失败')
  } finally {
    if (current()) drawerLoading.value = false
  }
}
function flatten(
  nodes: ColumnLineageNode[] | undefined,
  level = 0,
): Array<{ node: ColumnLineageNode; level: number }> {
  return (nodes || []).flatMap((node) => [{ node, level }, ...flatten(node.children, level + 1)])
}
function handleAnalyzeImpact(entity: MetadataEntityItem | null = selectedEntity.value) {
  contextMenu.value.visible = false
  const target = entity
    ? queryEvidenceTarget({
        ...entity,
        entityMetadata: JSON.stringify({
          ...parseLineageMeta(entity.entityMetadata),
          datasource_id: sourceOf(entity),
        }),
      })
    : undefined
  if (!target) {
    ElMessage.warning('对象缺少精确数据源或所属表，无法分析；请在查询来源页输入精确名称。')
    return
  }
  if (!iamS1.canOnDatasource('lineage:view', target.source)) {
    ElMessage.warning('没有该对象实际数据源的 lineage:view 能力')
    return
  }
  evidenceTarget.value = target
  activeTab.value = 'queries'
}
function openCreate(source: number | null = null, target: number | null = null) {
  if (
    source !== null
      ? !canManageEntity(entities.value.find((x) => x.id === source) || null)
      : !canManageLineage.value
  )
    return
  prefilledSourceId.value = source
  prefilledTargetId.value = target
  addDialogVisible.value = true
  contextMenu.value.visible = false
}
function requestDelete(edge: MetadataRelationshipItem) {
  contextMenu.value.visible = false
  if (!canDelete(edge)) return
  deleteTarget.value = { ...edge }
  cascadeDerived.value = false
  deleteError.value = ''
}
async function confirmDelete() {
  const edge = deleteTarget.value
  if (!edge || deleting.value || !canDelete(edge)) return
  const key = scopeKey.value,
    epoch = scopeEpoch
  deleting.value = true
  deleteError.value = ''
  try {
    const result = await trackLineageWrite(identity.value, `delete:${edge.id}`, () =>
      deleteLineage(edge.id, cascadeDerived.value),
    )
    if (key !== scopeKey.value || epoch !== scopeEpoch || impactDisposed) return
    ElMessage.success(result.data?.message || '关系已删除')
    deleteTarget.value = null
    await loadGraph()
  } catch (cause) {
    if (key === scopeKey.value && epoch === scopeEpoch && !impactDisposed)
      deleteError.value = getHttpErrorMessage(cause, '删除未确认成功，请重新读取关系核对；不要重复提交')
  } finally {
    if (key === scopeKey.value && epoch === scopeEpoch) deleting.value = false
  }
}
async function handleSearch() {
  if (!canReadDirectory.value || !searchQuery.value.trim()) return
  const key = scopeKey.value,
    ticket = ++searchRequestId
  searchLoading.value = true
  searchResults.value = []
  try {
    const r = await searchCatalog({
      q: searchQuery.value.trim(),
      datasourceId: datasourceId.value!,
      page: 1,
      size: 30,
    })
    if (key === scopeKey.value && ticket === searchRequestId)
      searchResults.value = (r.data || []).filter((x) => ['TABLE', 'COLUMN'].includes(x.entityType))
  } catch (e) {
    if (key === scopeKey.value && ticket === searchRequestId)
      ElMessage.error(getHttpErrorMessage(e, '对象搜索失败'))
  } finally {
    if (key === scopeKey.value && ticket === searchRequestId) searchLoading.value = false
  }
}
function selectSearch(entity: MetadataEntityItem) {
  currentDatasourceEntityIds.add(entity.id)
  focusEntityId.value = String(entity.id)
  syncFocusToUrl(entity.id)
  void loadGraph()
}
async function onFileSelected(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0]
  input.value = ''
  if (!file || !canManageLineage.value || importing.value) return
  const key = scopeKey.value,
    epoch = scopeEpoch
  importing.value = true
  importError.value = ''
  try {
    const bytes = await file.arrayBuffer()
    const digest = await crypto.subtle.digest('SHA-256', bytes)
    const hash = Array.from(new Uint8Array(digest), (x) => x.toString(16).padStart(2, '0')).join('')
    if (key !== scopeKey.value || epoch !== scopeEpoch || impactDisposed || !canManageLineage.value) return
    const r = await trackLineageWrite(identity.value, `import:${hash}`, () => batchCreateLineage(file))
    if (key === scopeKey.value && epoch === scopeEpoch && !impactDisposed) {
      ElMessage.success(`批量请求已完成，返回 ${r.data?.length ?? 0} 条关系，请核对图谱`)
      await loadGraph()
    }
  } catch (e) {
    if (key === scopeKey.value && epoch === scopeEpoch && !impactDisposed)
      importError.value = getHttpErrorMessage(e, '批量导入结果未知，请先核对记录，不能重复提交')
  } finally {
    if (key === scopeKey.value && epoch === scopeEpoch) importing.value = false
  }
}
async function handleExportPng() {
  if (exportBusy.value || !canViewLineage.value) return
  const ticket = ++exportRequestId,
    key = scopeKey.value
  exportBusy.value = true
  try {
    const url = await d3ExportPng()
    if (url && !impactDisposed && ticket === exportRequestId && key === scopeKey.value) {
      const a = document.createElement('a')
      a.href = url
      a.download = `lineage-source-${datasourceId.value}.png`
      a.click()
    }
  } catch {
    if (key === scopeKey.value) ElMessage.error('图谱导出失败，请重试')
  } finally {
    if (ticket === exportRequestId) exportBusy.value = false
  }
}
function openQueryEvidence() {
  evidenceTarget.value = undefined
  activeTab.value = 'queries'
}
function expandFromMenu() {
  const entity = contextMenu.value.entity
  closeContextMenu()
  if (entity) void loadEntityLineage(entity.id)
}
function refreshFromMenu() {
  closeContextMenu()
  void loadGraph()
}
function closeContextMenu() {
  contextMenu.value.visible = false
}
watch(
  scopeKey,
  () => {
    clearScope()
    void loadGraph()
  },
  { flush: 'sync' },
)
watch(
  () => route.query.entityId,
  () => {
    drawerVisible.value = false
    columnLineageRequestId++
    void loadGraph()
  },
)
watch(
  [lineageTypeFilter, depth],
  () => {
    exportRequestId++
    exportBusy.value = false
    void loadGraph()
    if (drawerVisible.value && drawerEntity.value) void loadColumnLineage(drawerEntity.value.id)
  },
  { deep: true },
)
watch(direction, () => {
  renderGraph()
  if (drawerVisible.value && drawerEntity.value) void loadColumnLineage(drawerEntity.value.id)
})
watch(drawerVisible, (v) => {
  if (!v) {
    columnLineageRequestId++
    drawerLoading.value = false
  }
})
watch(activeTab, async () => {
  await nextTick()
  renderGraph()
})
onMounted(async () => {
  await adminContext.initialize()
  if (!impactDisposed) await loadGraph()
  window.addEventListener('resize', d3Resize)
  document.addEventListener('click', closeContextMenu)
})
onBeforeUnmount(() => {
  impactDisposed = true
  clearScope()
  window.removeEventListener('resize', d3Resize)
  document.removeEventListener('click', closeContextMenu)
})
</script>
<template>
  <main class="data-lineage-page post-login-page">
    <TaskPageHeader
      title="关系与查询来源"
      eyebrow="数据资产 / 可追溯性"
      description="先看已记录的对象关系，再核对实际查询使用。结构关联、加工关系和结果来源分别解释。"
      description-mode="inline"
      help="图谱以实体 ID 为根，不支持快照或时间筛选。QUERY 标记仅说明已存储关系的来源类型；实际受保护执行证据在查询来源中查看。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :disabled="loading || !canViewLineage" @click="loadGraph">
          重新读取
        </el-button>
        <el-button
          type="primary"
          :icon="Plus"
          :disabled="!canManageLineage"
          :title="MANAGE_HINT"
          @click="openCreate()"
        >
          记录关系
        </el-button>
      </template>
    </TaskPageHeader>
    <p v-if="invalidContext" class="boundary-error" role="alert">
      链接包含无效数据源或实体 ID，未使用已保存的其他范围。请核对链接。
    </p>
    <p v-else-if="!datasourceId" class="boundary-note">请先在顶部选择数据源。</p>
    <p v-else-if="!canViewLineage" class="boundary-note">
      没有当前数据源的 lineage:view 能力，或能力摘要尚未就绪。
    </p>
    <p v-if="uncertain.length" class="boundary-error" role="alert">
      有
      {{ uncertain.length }}
      个关系操作执行中或结果未知。先重新读取并核对；此会话内阻止相同操作重复提交。页面刷新后仍需人工核对。
      <span v-for="item in uncertain" :key="item.key">
        目标：{{ item.key.startsWith('import:') ? '批量文件内容摘要 ' + item.key.slice(7, 19) : item.key }}。
      </span>
    </p>
    <nav class="workspace-tabs" aria-label="血缘工作面">
      <button :aria-current="activeTab === 'graph' ? 'page' : undefined" @click="activeTab = 'graph'">
        关系图谱
      </button>
      <button :aria-current="activeTab === 'queries' ? 'page' : undefined" @click="openQueryEvidence">
        查询使用与输出来源
      </button>
    </nav>
    <QueryLineageEvidence
      v-if="activeTab === 'queries' && !invalidContext"
      :datasource-id="evidenceTarget?.source || datasourceId"
      :table="evidenceTarget?.table"
      :column="evidenceTarget?.column"
    />
    <section v-else-if="canViewLineage && !invalidContext" class="graph-workspace">
      <aside class="graph-controls">
        <div class="control-heading">
          <Network :size="18" />
          <strong>图谱范围</strong>
        </div>
        <form @submit.prevent="handleSearch">
          <label for="lineage-search">搜索对象目录</label>
          <el-input
            id="lineage-search"
            v-model="searchQuery"
            :disabled="!canReadDirectory"
            placeholder="表名或字段名"
          />
          <el-button
            native-type="submit"
            :loading="searchLoading"
            :disabled="!canReadDirectory || !searchQuery.trim()"
          >
            查找对象
          </el-button>
        </form>
        <div v-if="searchResults.length" class="object-results">
          <button v-for="entity in searchResults" :key="entity.id" @click="selectSearch(entity)">
            {{ entity.displayName || entity.name }}
            <small>#{{ entity.id }} · {{ entity.entityType }}</small>
          </button>
        </div>
        <p v-if="directoryError" class="helper-note" role="status">{{ directoryError }}</p>
        <label>已记录关系的来源类型</label>
        <el-checkbox-group v-model="lineageTypeFilter">
          <el-checkbox value="QUERY" label="查询记录标记 QUERY" />
          <el-checkbox value="ETL" label="加工记录 ETL" />
          <el-checkbox value="MANUAL" label="人工记录 MANUAL" />
        </el-checkbox-group>
        <label>相对焦点的方向</label>
        <el-radio-group v-model="direction" size="small">
          <el-radio-button value="upstream">上游</el-radio-button>
          <el-radio-button value="downstream">下游</el-radio-button>
          <el-radio-button value="both">双向</el-radio-button>
        </el-radio-group>
        <label>请求深度 · {{ depth }}</label>
        <el-slider v-model="depth" :min="1" :max="10" :step="1" />
        <p class="helper-note">方向在已加载图谱内过滤；字段派生抽屉按方向重新读取。未记录不代表没有影响。</p>
        <div class="secondary-actions">
          <el-button
            :icon="Upload"
            :loading="importing"
            :disabled="!canManageLineage"
            @click="fileInputRef?.click()"
          >
            导入 CSV / JSON
          </el-button>
          <el-button
            :icon="Download"
            :loading="exportBusy"
            :disabled="loading || !entities.length"
            @click="handleExportPng"
          >
            导出当前图谱
          </el-button>
          <input ref="fileInputRef" type="file" accept=".csv,.json" hidden @change="onFileSelected" />
        </div>
        <p class="helper-note">
          批量文件中的每个源与目标仍由服务端按实际负责源校验；导入不会创建新的业务权限。
        </p>
        <ErrorState v-if="importError" :message="importError" />
      </aside>
      <div class="graph-main">
        <header class="canvas-heading">
          <div class="canvas-heading__main">
            <span class="eyebrow">当前焦点 · {{ selectedEntity?.entityType || '—' }}</span>
            <strong
              :title="selectedEntity ? selectedEntity.displayName || selectedEntity.name : ''"
            >
              {{ selectedEntity ? entityShortLabel(selectedEntity) : '选择起点' }}
            </strong>
            <span class="canvas-heading__meta">
              <span class="meta-chip">实体 #{{ focusEntityId || '—' }}</span>
              <span class="meta-chip">{{ entities.length }} 个节点</span>
              <span class="meta-chip">{{ visibleRelations.length }} 条已加载关系</span>
            </span>
          </div>
          <span class="canvas-tip">拖动移动 · 滚轮缩放 · Shift 拖动记录关系</span>
        </header>
        <ErrorState v-if="graphError" :message="graphError" @retry="loadGraph" />
        <div v-else-if="loading" class="graph-status" role="status">正在读取当前实体的关系图谱…</div>
        <div v-else-if="routeEntityHint" class="graph-status" role="status">
          <Network :size="28" />
          <p>{{ routeEntityHint }}</p>
          <RouterLink
            v-if="canViewReleases"
            :to="{
              path: '/admin/releases',
              query: buildContextQuery('datasource', { datasourceId: datasourceId || undefined }),
            }"
          >
            查看元数据版本
          </RouterLink>
        </div>
        <template v-else>
          <div
            v-if="!graphEmpty"
            ref="chartRef"
            class="chart-area"
            aria-label="关系图谱；下方对象列表提供键盘入口"
          />
          <div v-else class="lineage-empty">
            <div class="lineage-empty__card" role="status">
              <span class="lineage-empty__icon"><Network :size="20" /></span>
              <strong>还没有已记录的关系</strong>
              <p>{{ graphEmptyHint }}</p>
              <div class="lineage-empty__actions">
                <el-button
                  v-if="canManageLineage"
                  type="primary"
                  :icon="Plus"
                  @click="openCreate()"
                >
                  记录关系
                </el-button>
                <el-button :icon="RefreshCw" :loading="loading" @click="loadGraph">
                  重新读取
                </el-button>
              </div>
            </div>
            <p class="lineage-empty__note">图谱只展示已记录的关系；未记录不代表没有影响。</p>
          </div>
        </template>
        <p class="graph-legend">
          <span><i class="legend-dot legend-dot--struct" />灰色：结构关联</span>
          <span><i class="legend-dot legend-dot--etl" />铜色：ETL 加工记录</span>
          <span><i class="legend-dot legend-dot--derived" />虚线：字段派生</span>
          <span><i class="legend-dot legend-dot--query" />绿色：QUERY 来源标记</span>
        </p>
        <div v-if="entities.length" class="object-strip" aria-label="选择图谱对象">
          <button
            v-for="entity in entities"
            :key="entity.id"
            :title="entity.displayName || entity.name"
            :aria-pressed="selectedEntity?.id === entity.id"
            @click="handleNodeClick({ id: String(entity.id), label: entityShortLabel(entity), entity })"
          >
            {{ entityShortLabel(entity) }}
            <small>#{{ entity.id }}</small>
          </button>
        </div>
        <section v-if="selectedEntity" class="selection-card">
          <div class="selection-main">
            <div class="selection-heading">
              <span class="selection-icon">
                <Table2 v-if="selectedEntity.entityType === 'TABLE'" :size="18" />
                <Columns3 v-else :size="18" />
              </span>
              <div class="selection-title">
                <span class="eyebrow">当前选中 · {{ selectedEntity.entityType }}</span>
                <h2>{{ selectedEntity.name }}</h2>
                <p
                  v-if="entityAlias(selectedEntity)"
                  class="selection-alias"
                  :title="selectedEntity.displayName"
                >
                  展示名：{{ selectedEntity.displayName }}
                </p>
              </div>
            </div>
            <code class="selection-fqn">{{ selectedEntity.fqn }}</code>
            <div class="selection-facts">
              <span><span class="fact-label">实体</span><strong>#{{ selectedEntity.id }}</strong></span>
              <span>
                <span class="fact-label">数据源</span>
                <strong>#{{ sourceOf(selectedEntity) ?? '未确认' }}</strong>
              </span>
              <span>
                <span class="fact-label">对象版本</span>
                <strong>v{{ selectedEntity.version }}</strong>
              </span>
            </div>
            <details v-if="selectedEntity.description" class="raw-comment">
              <summary>
                <span class="raw-comment__label">原始注释</span>
                <span class="raw-comment__preview">{{ selectedEntity.description }}</span>
                <span class="raw-comment__hint">展开原始注释</span>
                <ChevronDown :size="14" class="raw-comment__chevron" />
              </summary>
              <div class="raw-comment__full">
                <p class="raw-comment__tag">原始注释 · 未清洗，仅作血缘记录参考</p>
                <p class="raw-comment__text">{{ selectedEntity.description }}</p>
              </div>
            </details>
            <p v-else class="selection-empty-note">服务端未提供对象说明。</p>
          </div>
          <div class="selection-actions">
            <el-button @click="handleAnalyzeImpact()">查看查询使用</el-button>
            <el-button @click="loadEntityLineage(selectedEntity.id)">以此为起点展开</el-button>
            <el-button v-if="canManageEntity(selectedEntity)" @click="openCreate(selectedEntity.id)">
              添加下游
            </el-button>
          </div>
        </section>
        <details v-if="visibleRelations.length" class="relation-records">
          <summary>
            <span>检查已记录关系（{{ visibleRelations.length }}）</span>
            <ChevronDown :size="14" class="relation-records__chevron" />
          </summary>
          <p>结构关联不等于实际 JOIN，人工或 ETL 记录不证明本次查询使用。只允许删除 LINEAGE 表级记录。</p>
          <div v-for="edge in visibleRelations" :key="edge.id" class="relation-row">
            <code>#{{ edge.id }} · {{ edge.sourceId }} → {{ edge.targetId }}</code>
            <span>{{ relationLabel(edge) }}</span>
            <el-button v-if="canDelete(edge)" type="danger" text @click="requestDelete(edge)">
              删除关系 #{{ edge.id }}
            </el-button>
          </div>
        </details>
      </div>
    </section>
    <Teleport to="body">
      <div
        v-if="contextMenu.visible"
        class="context-menu"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
        @click.stop
      >
        <template v-if="contextMenu.entity">
          <button
            @click="
              handleNodeClick({
                id: String(contextMenu.entity.id),
                label: contextMenu.entity.name,
                entity: contextMenu.entity,
              })
            "
          >
            选择此对象
          </button>
          <button @click="handleAnalyzeImpact(contextMenu.entity)">查看查询使用</button>
          <button @click="expandFromMenu">以此为起点展开</button>
          <button v-if="canManageEntity(contextMenu.entity)" @click="openCreate(contextMenu.entity.id)">
            添加下游关系
          </button>
        </template>
        <button
          v-else-if="contextMenu.edge && canDelete(contextMenu.edge)"
          @click="requestDelete(contextMenu.edge)"
        >
          删除关系 #{{ contextMenu.edge.id }}
        </button>
        <template v-else>
          <button @click="refreshFromMenu">重新读取</button>
          <button v-if="canManageLineage" @click="openCreate()">记录关系</button>
        </template>
      </div>
    </Teleport>
    <el-drawer v-model="drawerVisible" title="字段派生详情" size="min(520px, 100vw)">
      <template v-if="drawerEntity">
        <h2>{{ drawerEntity.name }}</h2>
        <p v-if="entityAlias(drawerEntity)" class="helper-note">
          展示名：{{ drawerEntity.displayName }}
        </p>
        <code>{{ drawerEntity.fqn }}</code>
        <p class="helper-note">
          精确实体 #{{ drawerEntity.id }} · 数据源 #{{ sourceOf(drawerEntity) || '未确认' }} · 深度
          {{ depth }} · {{ direction }}
        </p>
        <ErrorState v-if="drawerError" :message="drawerError" @retry="loadColumnLineage(drawerEntity!.id)" />
        <p v-else-if="drawerLoading" role="status">正在读取字段派生…</p>
        <template v-else v-for="side in ['upstream', 'downstream'] as const" :key="side">
          <h3>{{ side === 'upstream' ? '上游来源' : '下游派生' }} · DERIVED_FROM</h3>
          <p v-if="!columnLineageData?.[side]?.length" class="helper-note">
            {{
              direction !== 'both' && direction !== side
                ? '当前方向未请求此侧关系'
                : '没有已记录的此侧派生关系'
            }}
          </p>
          <div
            v-for="({ node, level }, index) in flatten(columnLineageData?.[side])"
            :key="index"
            class="chain-item"
            :style="{ marginLeft: Math.min(level, 8) * 12 + 'px' }"
          >
            <strong :title="node.entity.displayName || node.entity.name">{{
              entityShortLabel(node.entity)
            }}</strong>
            <code>{{ node.entity.fqn }}</code>
            <span>
              {{ parseLineageMeta(node.relationship?.relationMetadata).expression_type || '类型未提供' }}
            </span>
            <pre v-if="parseLineageMeta(node.relationship?.relationMetadata).expression">{{
              parseLineageMeta(node.relationship?.relationMetadata).expression
            }}</pre>
          </div>
        </template>
      </template>
    </el-drawer>
    <AddLineageDialog
      v-model:visible="addDialogVisible"
      :prefilled-source-id="prefilledSourceId"
      :prefilled-target-id="prefilledTargetId"
      @created="loadGraph"
    />
    <el-dialog
      :model-value="!!deleteTarget"
      title="删除已记录的表级关系"
      width="520px"
      :close-on-click-modal="false"
      :show-close="!deleting"
      @update:model-value="
        (value: boolean) => {
          if (!value && !deleting) deleteTarget = null
        }
      "
    >
      <p>
        将删除关系 #{{ deleteTarget?.id }}：{{ deleteTarget?.sourceId }} →
        {{ deleteTarget?.targetId }}。确认前不会发送删除请求。
      </p>
      <el-checkbox v-model="cascadeDerived" :disabled="deleting">同时删除该关系关联的列映射</el-checkbox>
      <p class="helper-note">
        未勾选时，列映射将保留为独立记录。接口没有只读映射数量预检查；此次只发送一次删除请求。
      </p>
      <ErrorState v-if="deleteError" :message="deleteError" />
      <template #footer>
        <el-button :disabled="deleting" @click="deleteTarget = null">取消</el-button>
        <el-button
          type="danger"
          :loading="deleting"
          :disabled="!deleteTarget || !canDelete(deleteTarget)"
          @click="confirmDelete"
        >
          确认删除
        </el-button>
      </template>
    </el-dialog>
  </main>
</template>
<style scoped>
.data-lineage-page {
  min-width: 0;
}
.boundary-note,
.boundary-error {
  padding: 14px 16px;
  border: 1px solid var(--do-line);
  background: var(--do-surface);
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.7;
}
.boundary-error {
  color: var(--do-danger);
}
.workspace-tabs {
  display: flex;
  gap: 28px;
  border-bottom: 1px solid var(--do-line);
  margin: 0 0 16px;
}
.workspace-tabs button {
  display: inline-flex;
  align-items: center;
  height: 44px;
  padding: 0 2px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  color: var(--do-muted);
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition:
    color 150ms ease,
    border-color 150ms ease;
}
.workspace-tabs button:hover {
  color: var(--do-ink);
}
.workspace-tabs button[aria-current] {
  color: var(--do-primary-strong);
  border-bottom-color: var(--do-primary);
}
.workspace-tabs button:focus-visible {
  outline: 2px solid var(--do-primary);
  outline-offset: 2px;
  border-radius: 4px;
}
.graph-workspace {
  display: grid;
  grid-template-columns: 252px minmax(0, 1fr);
  border: 1px solid var(--do-line);
  border-radius: 14px;
  background: var(--do-surface);
  box-shadow: 0 12px 32px rgb(36 39 36 / 5%);
  overflow: hidden;
}
.graph-controls {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  border-right: 1px solid var(--do-line);
  background: color-mix(in srgb, var(--do-soft) 46%, var(--do-surface));
}
.control-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 2px;
  color: var(--do-ink);
  font-size: 14px;
  font-weight: 650;
}
.control-heading svg {
  color: var(--do-primary);
}
.graph-controls label {
  display: block;
  margin-top: 2px;
  color: var(--do-muted);
  font-size: 11.5px;
  font-weight: 650;
  letter-spacing: 0.02em;
}
.graph-controls form {
  display: grid;
  gap: 8px;
}
.graph-controls :deep(.el-input__wrapper) {
  min-height: 34px;
  border-radius: 8px;
}
.graph-controls :deep(.el-button) {
  min-height: 34px;
  border-radius: 8px;
}
.graph-controls :deep(.el-checkbox-group) {
  display: grid;
  gap: 2px;
  margin: 2px 0;
}
.graph-controls :deep(.el-checkbox) {
  height: 26px;
  margin: 0;
  font-size: 12px;
}
.graph-controls :deep(.el-checkbox__label) {
  color: var(--do-ink);
  font-size: 12px;
}
.graph-controls :deep(.el-radio-group) {
  margin: 2px 0 4px;
}
.graph-controls :deep(.el-radio-button__inner) {
  padding: 7px 14px;
  font-size: 12px;
}
.graph-controls :deep(.el-slider) {
  margin: 2px 6px 4px;
}
.helper-note {
  margin: 0;
  color: var(--do-muted);
  font-size: 11.5px;
  line-height: 1.75;
  overflow-wrap: anywhere;
}
.secondary-actions {
  display: grid;
  gap: 8px;
  margin-top: 2px;
}
.secondary-actions .el-button {
  margin: 0;
}
.graph-main {
  min-width: 0;
  background: var(--do-surface);
}
.canvas-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px 14px;
  border-bottom: 1px solid var(--do-line);
}
.canvas-heading__main {
  display: grid;
  flex: 1;
  min-width: 0;
  gap: 4px;
}
.canvas-heading strong {
  display: block;
  overflow: hidden;
  color: var(--do-ink);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 17px;
  font-weight: 700;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.canvas-heading__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.meta-chip {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border: 1px solid var(--do-line);
  border-radius: 999px;
  background: var(--do-surface);
  color: var(--do-muted);
  font-size: 11px;
  white-space: nowrap;
}
.canvas-tip {
  flex-shrink: 0;
  color: var(--do-muted);
  font-size: 11px;
  line-height: 1.7;
  text-align: right;
  white-space: nowrap;
}
.chart-area {
  width: 100%;
  height: clamp(360px, calc(100vh - 430px), 520px);
  background-image: radial-gradient(var(--do-line) 0.7px, transparent 0.7px);
  background-size: 18px 18px;
}
.graph-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 320px;
  padding: 32px;
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.8;
  text-align: center;
}
.lineage-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: clamp(360px, calc(100vh - 430px), 520px);
  padding: 40px 20px;
  background-image: radial-gradient(var(--do-line) 0.7px, transparent 0.7px);
  background-size: 18px 18px;
}
.lineage-empty__card {
  display: grid;
  gap: 10px;
  justify-items: center;
  max-width: 480px;
  padding: 26px 30px;
  border: 1px solid var(--do-line);
  border-radius: 14px;
  background: var(--do-surface);
  box-shadow: 0 10px 26px rgb(36 39 36 / 6%);
  text-align: center;
}
.lineage-empty__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  background: var(--do-primary-soft);
  color: var(--do-primary);
}
.lineage-empty__card strong {
  color: var(--do-ink);
  font-size: 15px;
}
.lineage-empty__card p {
  margin: 0;
  color: var(--do-muted);
  font-size: 12.5px;
  line-height: 1.8;
}
.lineage-empty__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 2px;
}
.lineage-empty__actions .el-button {
  margin: 0;
}
.lineage-empty__note {
  margin: 0;
  color: var(--do-muted);
  font-size: 11.5px;
}
.graph-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding: 12px 20px 0;
  color: var(--do-muted);
  font-size: 11px;
}
.graph-legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.legend-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-radius: 999px;
}
.legend-dot--struct {
  background: #939b96;
}
.legend-dot--etl {
  background: #aa603c;
}
.legend-dot--derived {
  width: 10px;
  height: 0;
  border-top: 2px dashed #80728f;
  border-radius: 0;
}
.legend-dot--query {
  background: #628179;
}
.object-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 20px 16px;
}
.object-strip button,
.object-results button {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  padding: 7px 10px;
  color: var(--do-ink);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition:
    border-color 150ms ease,
    background 150ms ease;
}
.object-strip button {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.object-strip button small {
  margin-left: 6px;
  color: var(--do-muted);
  font-size: 10.5px;
}
.object-strip button:hover,
.object-results button:hover {
  border-color: var(--do-primary);
}
.object-strip button[aria-pressed='true'] {
  border-color: color-mix(in srgb, var(--do-primary) 45%, var(--do-line));
  background: var(--do-primary-soft);
  color: var(--do-primary-strong);
}
.object-strip button:focus-visible,
.object-results button:focus-visible {
  outline: 2px solid var(--do-primary);
  outline-offset: 1px;
}
small {
  color: var(--do-muted);
}
.object-results {
  display: grid;
  gap: 5px;
  max-height: 240px;
  overflow: auto;
}
.selection-card {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  margin: 16px 20px 18px;
  padding: 18px 20px;
  border: 1px solid var(--do-line);
  border-radius: 12px;
  background: var(--do-surface);
}
.selection-main {
  display: grid;
  flex: 1;
  min-width: 0;
  gap: 10px;
}
.selection-heading {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}
.selection-icon {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--do-primary-soft);
  color: var(--do-primary);
}
.selection-title {
  min-width: 0;
}
.eyebrow {
  display: block;
  color: var(--do-primary-strong);
  font-size: 10.5px;
  font-weight: 800;
  letter-spacing: 0.04em;
}
.selection-title h2 {
  margin: 4px 0 0;
  color: var(--do-ink);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 17px;
  font-weight: 700;
  overflow-wrap: anywhere;
}
.selection-alias {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.selection-fqn {
  display: block;
  color: var(--do-muted);
  font-size: 11.5px;
  overflow-wrap: anywhere;
}
.selection-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
}
.selection-facts span {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  color: var(--do-ink);
  font-size: 12px;
}
.fact-label {
  color: var(--do-muted);
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.02em;
}
.selection-empty-note {
  margin: 0;
  color: var(--do-muted);
  font-size: 12px;
}
.selection-actions {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  gap: 8px;
}
.selection-actions .el-button {
  margin: 0;
}
.raw-comment {
  padding: 0 12px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  background: color-mix(in srgb, var(--do-soft) 40%, var(--do-surface));
}
.raw-comment summary {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  padding: 6px 0;
  cursor: pointer;
  list-style: none;
}
.raw-comment summary::-webkit-details-marker {
  display: none;
}
.raw-comment summary::marker {
  content: '';
}
.raw-comment__label {
  flex-shrink: 0;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--do-primary-soft);
  color: var(--do-primary-strong);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.02em;
}
.raw-comment__preview {
  display: -webkit-box;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.raw-comment__hint {
  flex-shrink: 0;
  color: var(--do-primary-strong);
  font-size: 11.5px;
  font-weight: 650;
}
.raw-comment__chevron {
  flex-shrink: 0;
  color: var(--do-primary-strong);
  transition: transform 150ms ease;
}
.raw-comment[open] .raw-comment__hint {
  display: none;
}
.raw-comment[open] .raw-comment__chevron {
  transform: rotate(180deg);
}
.raw-comment__full {
  padding: 10px 0 12px;
  border-top: 1px dashed var(--do-line);
}
.raw-comment__tag {
  margin: 0 0 6px;
  color: var(--do-muted);
  font-size: 11px;
  font-weight: 650;
}
.raw-comment__text {
  margin: 0;
  color: var(--do-ink);
  font-size: 12.5px;
  line-height: 1.8;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.relation-records {
  margin: 0 20px 18px;
  padding: 12px 14px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  font-size: 12px;
}
.relation-records summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: var(--do-ink);
  cursor: pointer;
  font-weight: 650;
  list-style: none;
}
.relation-records summary::-webkit-details-marker {
  display: none;
}
.relation-records summary::marker {
  content: '';
}
.relation-records__chevron {
  flex-shrink: 0;
  color: var(--do-muted);
  transition: transform 150ms ease;
}
.relation-records[open] .relation-records__chevron {
  transform: rotate(180deg);
}
.relation-records p {
  margin: 8px 0 0;
  color: var(--do-muted);
  line-height: 1.8;
}
.relation-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--do-line);
}
.relation-row code {
  font-size: 11.5px;
}
.context-menu {
  position: fixed;
  z-index: 3000;
  padding: 6px;
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  box-shadow: 0 8px 28px #242b241b;
  border-radius: 8px;
  display: grid;
}
.context-menu button {
  border: 0;
  background: none;
  text-align: left;
  padding: 10px 12px;
  cursor: pointer;
  color: var(--do-ink);
  font: inherit;
  font-size: 13px;
}
.context-menu button:hover {
  background: var(--do-bg);
}
.chain-item {
  display: grid;
  gap: 8px;
  border-left: 2px solid var(--do-line);
  padding: 12px;
  margin-top: 8px;
  font-size: 12px;
}
.chain-item code {
  overflow-wrap: anywhere;
}
.chain-item pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
@media (max-width: 1100px) {
  .graph-workspace {
    grid-template-columns: 220px minmax(0, 1fr);
  }
  .canvas-tip {
    display: none;
  }
}
@media (max-width: 960px) {
  .graph-workspace {
    grid-template-columns: 1fr;
  }
  .graph-controls {
    border-right: 0;
    border-bottom: 1px solid var(--do-line);
  }
  .chart-area,
  .lineage-empty {
    height: auto;
    min-height: 340px;
  }
  .selection-card {
    flex-direction: column;
  }
  .selection-actions {
    flex-direction: row;
    flex-wrap: wrap;
  }
}
@media (max-width: 640px) {
  .workspace-tabs {
    gap: 16px;
  }
  .workspace-tabs button {
    font-size: 13px;
  }
  .canvas-heading {
    flex-direction: column;
    padding: 14px 16px;
  }
  .graph-legend,
  .object-strip {
    padding-right: 16px;
    padding-left: 16px;
  }
  .selection-card {
    margin: 12px 16px 14px;
    padding: 14px 16px;
  }
}
</style>
