<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
  type ComponentPublicInstance,
} from 'vue'
import {
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
  Copy,
  Info,
  Network,
  RefreshCw,
  ShieldCheck,
  SlidersHorizontal,
} from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getEntityOverview,
  unconfirmEntityTag,
  type CatalogEntityOverview,
  type OverviewColumn,
  type OverviewLineageItem,
  type OverviewRelationItem,
  type OverviewSection,
  type OverviewSectionStatus,
  type OverviewTagItem,
  type OverviewTermItem,
} from '../../../api/admin/catalog'
import { useIamS1Store } from '../../../stores/iamS1'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import {
  entityTypeLabel,
  glossaryTermStatusLabel,
  governanceStatusLabel,
  lineageTypeLabel,
  tableTypeLabel,
} from '../../../utils/enumLabels'

const route = useRoute()
const router = useRouter()
const iamS1 = useIamS1Store()
const loading = ref(true)
const error = ref('')
const overview = ref<CatalogEntityOverview | null>(null)
const columnPage = ref(1)
const columnPageSize = 12
const commentExpanded = ref(false)
const technicalOpen = ref<string[]>([])
const expandedTerms = ref<Set<number>>(new Set())
const expandedReasons = ref<Set<number>>(new Set())
/** 只有实际渲染发生三行截断的术语才显示展开入口。 */
const overflowingTerms = ref<Set<number>>(new Set())
const termDefinitionElements = new Map<number, HTMLElement>()
let termResizeObserver: ResizeObserver | null = null
let termMeasureScheduled = false
const entityId = computed(() => Number(route.params.entityId))
let detailRequestId = 0
let disposed = false

/** 桌面固定模式依赖父容器真实剩余高度，不在样式里写死表格高度。 */
const pageRoot = ref<HTMLElement | null>(null)
const fixedLayout = ref(false)
const FIXED_LAYOUT_MIN_BODY_HEIGHT = 360
let layoutMedia: MediaQueryList | undefined
let layoutObserver: ResizeObserver | null = null
let layoutMeasureScheduled = false
const observedLayoutTargets = new Set<HTMLElement>()

const isTable = computed(() => overview.value?.entityType === 'TABLE')
const isColumn = computed(() => overview.value?.entityType === 'COLUMN')
const governanceStatus = computed(() => (isTable.value
  ? overview.value?.table?.governanceStatus
  : overview.value?.column?.governanceStatus))
/** 目录标签的取消沿用既有治理写入门禁；查看本身只需要对象目录权限。 */
const canManageTags = computed(() => iamS1.canOnDatasource(
  'governance:field:manage',
  overview.value?.datasource?.id,
))
/** 查看字段解释只需要字段查看能力，不能要求标签维护能力。 */
const canViewFieldGovernance = computed(() => iamS1.canOnDatasource(
  'governance:field:view',
  overview.value?.datasource?.id,
))
/** 进入治理状态维护只需要查看元数据能力，页面内编辑另由 governance:rule:manage 控制。 */
const canViewGovernanceStatus = computed(() => iamS1.canOnDatasource(
  'metadata:view',
  overview.value?.datasource?.id,
))
const canViewLineage = computed(() => iamS1.canOnDatasource(
  'lineage:view',
  overview.value?.datasource?.id,
))
const lineageWorkspaceUnavailableReason = computed(() => {
  const current = overview.value
  if (!current) return '正在读取对象信息…'
  if (!iamS1.loaded) {
    return iamS1.errorMessage
      ? '血缘权限读取失败，暂时无法确认入口。'
      : '正在读取血缘权限…'
  }
  if (!current.datasource) return '来源数据源无法核验，暂时无法打开血缘工作台。'
  if (!canViewLineage.value) {
    return '没有“查看数据血缘”能力（lineage:view），或当前数据源不在负责范围内。'
  }
  return ''
})
const canOpenLineageWorkspace = computed(() => !lineageWorkspaceUnavailableReason.value)
const primaryName = computed(() => overview.value
  ? (overview.value.entity.displayName?.trim() || overview.value.entity.name)
  : '对象详情')
const locationText = computed(() => {
  if (!overview.value) return ''
  const parts = [
    overview.value.datasource?.name,
    overview.value.datasource?.databaseName,
    overview.value.table?.tableName || overview.value.column?.tableName,
    isColumn.value ? (overview.value.column?.name || overview.value.entity.name) : undefined,
  ].filter(Boolean)
  return parts.join(' / ')
})
const backTarget = computed(() => ({ path: '/admin/assets', query: detailContextQuery() }))
const technicalMetadata = computed(() => parseMetadata(overview.value?.entity.entityMetadata))
const technicalEntries = computed(() => Object.entries(technicalMetadata.value)
  .filter(([key]) => !['tag_candidates'].includes(key))
  .sort(([left], [right]) => left.localeCompare(right)))
const sourceSnapshot = computed(() => overview.value?.sourceSnapshot)
const publishedSnapshot = computed(() => overview.value?.publishedSnapshot)
const columnItems = computed(() => overview.value?.columns?.items || [])
const pagedColumns = computed(() => {
  const start = (columnPage.value - 1) * columnPageSize
  return columnItems.value.slice(start, start + columnPageSize)
})

/**
 * 标题附近区分对象类别与真实表类型：
 * 只有已确认的表类型才翻译成中文，缺失或未知时退回“表对象”；
 * 原始 table_type 始终保存在技术信息中，不把未知值冒充普通表。
 */
const objectTypeLabel = computed(() => {
  if (isTable.value) return tableTypeLabel(overview.value?.table?.tableType) || '表对象'
  if (isColumn.value) return '字段'
  return entityTypeLabel(overview.value?.entityType)
})

interface FactItem {
  label: string
  value: string
  mono?: boolean
  /** 与页面主标题重复的中文名称降低权重，但仍保留可读取内容。 */
  muted?: boolean
}

/** 基本信息按真实字段自适应排布，不再补固定列造成的空单元格。 */
const facts = computed<FactItem[]>(() => {
  const current = overview.value
  if (!current) return []
  const items: FactItem[] = [
    { label: '数据源', value: display(current.datasource?.name) },
    { label: '数据库', value: display(current.datasource?.databaseName) },
    isColumn.value
      ? { label: '所属表', value: display(current.column?.tableName), mono: true }
      : { label: '表名', value: display(current.table?.tableName), mono: true },
  ]
  const chineseName = display(isColumn.value
    ? current.column?.displayName
    : current.table?.displayName)
  const chineseNameMuted = chineseName !== '未记录' && chineseName === primaryName.value
  if (isColumn.value) {
    items.push(
      { label: '字段技术名', value: display(current.column?.name), mono: true },
      { label: '中文名称', value: chineseName, muted: chineseNameMuted },
      { label: '数据类型', value: display(current.column?.dataType), mono: true },
      { label: '可空性', value: nullableLabel(current.column?.nullable) },
      { label: '主键', value: boolLabel(current.column?.primaryKey) },
    )
  } else {
    // 表类型已在标题附近表达，不再单独占据基本信息的一整行。
    items.push({ label: '中文名称', value: chineseName, muted: chineseNameMuted })
  }
  return items
})

const rawComment = computed(() => {
  const current = overview.value
  if (!current) return ''
  const value = isTable.value ? current.table?.comment : current.column?.comment
  return value?.trim() || ''
})
const commentText = computed(() => rawComment.value
  || '暂无原始注释。原始注释来自数据库采集，不等同于已审核的业务定义。')
const commentIsLong = computed(() => rawComment.value.length > 140
  || rawComment.value.includes('\n'))

/** 来源与发布目标一致时只留一句紧凑说明，差异仍分别显示。 */
const sourceSummary = computed(() => {
  const current = overview.value
  if (!current || !current.sourceVerified || current.sourceMessage) return ''
  if (!current.sourceSnapshot || !current.publishedSnapshot || !current.sourceMatchesPublished) return ''
  return `与发布目标一致 · 快照 v${current.sourceSnapshot.version}`
})

const snapshotWarning = computed(() => {
  const current = overview.value
  if (!current) return ''
  const source = current.sourceSnapshot
  const published = current.publishedSnapshot
  const parts: string[] = []
  if (current.sourceMessage) {
    parts.push(current.sourceMessage)
  } else if (!current.sourceVerified) {
    parts.push('无法核验对象详情的数据来源快照，结构与治理信息不可用。')
  }
  if (published) {
    if (published.entitySyncStatus === 'FAILED') {
      parts.push(published.entitySyncError
        ? `当前发布目标 v${published.version} 的目录同步失败：${published.entitySyncError}。`
        : `当前发布目标 v${published.version} 的目录同步失败。`)
    } else if (published.entitySyncStatus === 'SYNCING') {
      parts.push(`当前发布目标 v${published.version} 正在同步目录，相关信息可能尚未完整。`)
    } else if (!published.entitySyncStatus) {
      parts.push(`发布目标 v${published.version} 没有记录目录同步状态，结果未经确认。`)
    }
  } else if (!current.sourceMessage) {
    parts.push('当前数据源没有已发布快照。')
  }
  if (source && published && !current.sourceMatchesPublished
      && current.sourceVerified && !current.sourceMessage) {
    parts.push(`详情按来源快照 v${source.version} 读取，当前发布目标为 v${published.version}。`)
  }
  return parts.join(' ')
})

const fieldGovernanceUnavailableReason = computed(() => {
  const current = overview.value
  if (!current) return '正在读取对象信息…'
  if (!iamS1.loaded) {
    return iamS1.errorMessage
      ? '字段解释权限读取失败，暂时无法确认入口。'
      : '正在读取字段解释权限…'
  }
  if (!current.datasource || !current.sourceSnapshot) {
    return '来源快照无法核验，暂时无法定位字段解释。'
  }
  if (!(current.table?.tableName || current.column?.tableName)) {
    return '来源快照缺少表名，暂时无法定位字段解释。'
  }
  if (isColumn.value && !current.column?.columnMetaId) {
    return '当前字段缺少来源快照中的字段标识，暂时无法精确定位字段解释。'
  }
  if (!canViewFieldGovernance.value) {
    return '没有“查看字段解释”能力（governance:field:view），或当前数据源不在负责范围内。'
  }
  return ''
})
const canOpenFieldGovernance = computed(() => !fieldGovernanceUnavailableReason.value)

/** 治理状态维护按表定位；字段对象也进入其所属表，页面内再选择字段。 */
const statusMaintenanceUnavailableReason = computed(() => {
  const current = overview.value
  if (!current) return '正在读取对象信息…'
  if (!iamS1.loaded) {
    return iamS1.errorMessage
      ? '治理状态维护权限读取失败，暂时无法确认入口。'
      : '正在读取治理状态维护权限…'
  }
  if (!current.datasource || !current.sourceSnapshot) {
    return '来源快照无法核验，暂时无法定位治理状态维护。'
  }
  if (!(current.table?.tableName || current.column?.tableName)) {
    return '来源快照缺少表名，暂时无法定位治理状态维护。'
  }
  if (!canViewGovernanceStatus.value) {
    return '没有“查看元数据”能力（metadata:view），或当前数据源不在负责范围内。'
  }
  return ''
})
const canOpenStatusMaintenance = computed(() => !statusMaintenanceUnavailableReason.value)

interface DetailTab {
  key: string
  label: string
  count?: number
  stateLabel?: string
}

function sectionStateLabel(status: OverviewSectionStatus) {
  if (status === 'FORBIDDEN') return '无权'
  if (status === 'ERROR') return '失败'
  if (status === 'UNAVAILABLE') return '不可用'
  return ''
}

function combinedStateLabel(
  first: OverviewSection<unknown>,
  second: OverviewSection<unknown>,
) {
  if (first.status === 'OK' && second.status === 'OK') return ''
  if (first.status !== 'OK' && second.status !== 'OK' && first.status !== second.status) {
    return '部分不可用'
  }
  return sectionStateLabel(first.status !== 'OK' ? first.status : second.status)
}

const detailTabKeys = computed(() => (isTable.value
  ? ['fields', 'terms', 'tags', 'relations', 'lineage', 'downstream']
  : ['terms', 'tags', 'relations', 'lineage', 'downstream']))
const defaultTab = computed(() => (isTable.value ? 'fields' : 'terms'))

function queryTabValue() {
  const value = route.query.tab
  if (Array.isArray(value)) return String(value[0] || '')
  return value == null ? '' : String(value)
}

const requestedTab = ref(queryTabValue())
const activeTab = computed(() => (detailTabKeys.value.includes(requestedTab.value)
  ? requestedTab.value
  : defaultTab.value))

/** 真实数量来自聚合结果；不可用时不显示 0，也不把无权当成空。 */
const detailTabs = computed<DetailTab[]>(() => {
  const current = overview.value
  if (!current) return []
  const tabs: DetailTab[] = []
  if (isTable.value) {
    tabs.push({
      key: 'fields',
      label: '字段',
      count: current.columns.status === 'OK' ? current.counts.columns : undefined,
      stateLabel: sectionStateLabel(current.columns.status),
    })
  }
  tabs.push({
    key: 'terms',
    label: '业务术语',
    count: current.terms.status === 'OK' ? current.counts.terms : undefined,
    stateLabel: sectionStateLabel(current.terms.status),
  })
  // 目录标签、候选标签与字段治理标签来源不同，Tab 上不再合并成一个总数。
  tabs.push({
    key: 'tags',
    label: '标签与治理',
    stateLabel: combinedStateLabel(current.tags, current.fieldTags),
  })
  tabs.push({
    key: 'relations',
    label: '所属与引用',
    count: current.structureRelations.status === 'OK' ? current.counts.structureRelations : undefined,
    stateLabel: sectionStateLabel(current.structureRelations.status),
  })
  tabs.push({
    key: 'lineage',
    label: '血缘',
    count: current.lineage.status === 'OK' ? current.counts.lineage : undefined,
    stateLabel: sectionStateLabel(current.lineage.status),
  })
  tabs.push({
    key: 'downstream',
    label: '下游影响',
    count: current.downstream.status === 'OK' ? current.counts.downstream : undefined,
    stateLabel: sectionStateLabel(current.downstream.status),
  })
  return tabs
})

const confirmedTags = computed(() => (overview.value?.tags.items || [])
  .filter((tag) => tag.kind === 'CONFIRMED'))
const candidateTags = computed(() => (overview.value?.tags.items || [])
  .filter((tag) => tag.kind === 'CANDIDATE'))
const abnormalTags = computed(() => (overview.value?.tags.items || [])
  .filter((tag) => tag.kind === 'UNBOUND' || tag.kind === 'BROKEN'))

const termReasonSummary = computed(() => {
  const counts = new Map<string, number>()
  for (const term of overview.value?.terms.items || []) {
    if (term.currentlyValid) continue
    const code = term.verificationCode || 'UNKNOWN'
    counts.set(code, (counts.get(code) || 0) + 1)
  }
  return [...counts.entries()].map(([code, count]) => ({
    code,
    // 当前无效却仍带 VALID 码时不写成“核验通过”，保留待确认语义。
    label: code === 'VALID'
      ? '当前有效性待确认'
      : (verificationCodeLabel(code) || '核验状态未知'),
    count,
  }))
})

function parseMetadata(raw?: string): Record<string, unknown> {
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

function display(value?: string | null, fallback = '未记录') {
  return value?.trim() ? value : fallback
}

function boolLabel(value?: boolean | null) {
  if (value === true) return '是'
  if (value === false) return '否'
  return '未记录'
}

function nullableLabel(value?: boolean | null) {
  if (value === true) return '可空'
  if (value === false) return '不可空'
  return '未记录'
}

/** 字段列表中的约束信息同时保留真实值与未记录状态。 */
function constraintText(row: OverviewColumn) {
  const parts: string[] = []
  if (row.primaryKey === true) parts.push('主键')
  else if (row.primaryKey == null) parts.push('主键未记录')
  if (row.nullable === true) parts.push('可空')
  else if (row.nullable === false) parts.push('非空')
  else parts.push('可空性未记录')
  return parts.join(' · ')
}

/** 中文名与技术名分开阅读；没有独立中文名时技术名作为唯一名称。 */
function hasDistinctDisplayName(row: OverviewColumn) {
  const displayName = row.displayName?.trim()
  return Boolean(displayName && displayName !== row.name)
}

function sectionAlertType(status: OverviewSectionStatus): 'info' | 'warning' | 'error' {
  if (status === 'FORBIDDEN') return 'info'
  if (status === 'ERROR') return 'error'
  return 'warning'
}

function bindingStatusLabel(status?: string) {
  if (!status || status === 'BOUND') return '已关联'
  if (status === 'UNBOUND') return '关联失效'
  return '状态未知'
}

function bindingStatusRaw(status?: string) {
  return status || 'BOUND'
}

function bindingStatusWarning(status?: string) {
  return status === 'UNBOUND'
}

function tagKindLabel(kind: string) {
  if (kind === 'CONFIRMED') return '目录标签'
  if (kind === 'CANDIDATE') return '采集候选'
  if (kind === 'UNBOUND') return '关联失效'
  if (kind === 'BROKEN') return '记录异常'
  return '标签'
}

function tagKey(tag: OverviewTagItem, index: number) {
  return `${tag.kind}:${tag.id || tag.name}:${index}`
}

async function copyText(value: string, label: string) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success(`已复制${label}`)
  } catch {
    ElMessage.error('复制失败，请手动选择内容')
  }
}

/** 只继承目录上下文；详情 Tab 与详情自身状态不带入相邻页面。 */
function detailContextQuery(): Record<string, string> {
  const query: Record<string, string> = {}
  for (const key of ['datasourceId', 'snapshotId', 'view', 'q', 'type']) {
    const value = route.query[key]
    if (typeof value === 'string' && value) query[key] = value
    else if (Array.isArray(value) && value[0]) query[key] = value[0]
  }
  return query
}

function openColumn(column: OverviewColumn) {
  if (!column.entityId) {
    ElMessage.warning('当前字段没有可跳转的目录对象（可能尚未完成目录同步）。')
    return
  }
  // 字段详情默认打开业务术语，不把表详情的字段 Tab 带过去。
  router.push({ path: `/admin/assets/entities/${column.entityId}`, query: detailContextQuery() })
}

function openTerm(termId: number, glossaryId?: number) {
  if (!glossaryId) return
  router.push({
    path: '/admin/semantics/glossaries',
    query: { glossaryId: String(glossaryId), termId: String(termId) },
  })
}

function selectTab(name: string | number) {
  const key = String(name)
  if (!detailTabKeys.value.includes(key)) return
  requestedTab.value = key
  if (queryTabValue() === key) return
  router.replace({ query: { ...route.query, tab: key } })
}

function isTermExpanded(termId: number) {
  return expandedTerms.value.has(termId)
}

function sameTermSet(left: Set<number>, right: Set<number>) {
  if (left.size !== right.size) return false
  for (const value of left) {
    if (!right.has(value)) return false
  }
  return true
}

/** 依据实际布局测量折叠状态是否超过三行，不用字符数猜测。 */
function measureTermOverflow() {
  const current = overview.value
  if (!current) return
  const next = new Set<number>()
  for (const term of current.terms.items) {
    if (isTermExpanded(term.termId)) {
      // 展开后保留明确的“收起”入口，不因当前全文高度而误删。
      next.add(term.termId)
      continue
    }
    const element = termDefinitionElements.get(term.termId)
    if (element && element.scrollHeight > element.clientHeight + 1) {
      next.add(term.termId)
    }
  }
  // 仅在结果变化时更新，避免 ResizeObserver 与类名切换互相触发。
  if (!sameTermSet(next, overflowingTerms.value)) {
    overflowingTerms.value = next
  }
}

function scheduleTermMeasure() {
  if (disposed || termMeasureScheduled) return
  termMeasureScheduled = true
  // 固定发起时的对象与请求序号，旧测量不能作用到新对象。
  const requestToken = detailRequestId
  const objectId = overview.value?.entity.id
  nextTick(() => {
    termMeasureScheduled = false
    if (disposed || requestToken !== detailRequestId || overview.value?.entity.id !== objectId) return
    measureTermOverflow()
  })
}

/** 读取 px 数值；计算样式缺失时按 0 处理，不写死版式数字。 */
function pxValue(value: string | null | undefined) {
  const parsed = Number.parseFloat(value || '')
  return Number.isFinite(parsed) ? parsed : 0
}

/** 详情页仍需受后台主内容区的滚动约束，测量以真实父容器为准。 */
function contentElement(): HTMLElement | null {
  return pageRoot.value?.closest('.admin-shell__content') as HTMLElement | null
}

/**
 * 固定模式下只有活动正文参与伸缩，其余直接子元素（标题、提示、基本信息、技术信息）
 * 保持自然高度；用这些稳定区域与父容器高度计算正文实际可用空间。
 */
function stableAreaHeight(page: HTMLElement) {
  const children = Array.from(page.children) as HTMLElement[]
  const style = getComputedStyle(page)
  const gap = pxValue(style.rowGap || style.gap)
  let total = 0
  for (const child of children) {
    if (child.classList.contains('entity-detail__panel')) continue
    total += child.getBoundingClientRect().height
  }
  return total + gap * Math.max(0, children.length - 1)
}

function measureAndApplyLayout() {
  const page = pageRoot.value
  const content = contentElement()
  const panel = page?.querySelector('.entity-detail__panel')
  // 加载、空态和错误态没有可伸缩正文，保持后台自然滚动。
  const canUseFixed = Boolean(
    page
    && content
    && panel
    && overview.value
    && !loading.value
    && !error.value
    && (layoutMedia?.matches ?? true),
  )
  if (!page || !content || !canUseFixed) {
    fixedLayout.value = false
    return
  }
  const contentStyle = getComputedStyle(content)
  const contentHeight = content.clientHeight
    - pxValue(contentStyle.paddingTop)
    - pxValue(contentStyle.paddingBottom)
  const available = contentHeight - stableAreaHeight(page)
  fixedLayout.value = available >= FIXED_LAYOUT_MIN_BODY_HEIGHT
}

/** 只增删变化的观察目标，避免重复 observe 触发新的 ResizeObserver 初始回调。 */
function refreshLayoutTargets() {
  const observer = layoutObserver
  if (!observer) return
  const targets = new Set<HTMLElement>()
  const content = contentElement()
  if (content) targets.add(content)
  const page = pageRoot.value
  if (page) {
    for (const child of Array.from(page.children)) {
      if (!child.classList.contains('entity-detail__panel')) targets.add(child as HTMLElement)
    }
  }
  observedLayoutTargets.forEach((element) => {
    if (!targets.has(element)) {
      observer.unobserve(element)
      observedLayoutTargets.delete(element)
    }
  })
  targets.forEach((element) => {
    if (!observedLayoutTargets.has(element)) {
      observer.observe(element)
      observedLayoutTargets.add(element)
    }
  })
}

function scheduleLayoutMeasure() {
  if (disposed || layoutMeasureScheduled) return
  layoutMeasureScheduled = true
  // 与数据加载共用请求序号：旧对象的延后测量不能改写新对象的布局状态。
  const requestToken = detailRequestId
  nextTick(() => {
    layoutMeasureScheduled = false
    if (disposed || requestToken !== detailRequestId) return
    measureAndApplyLayout()
    refreshLayoutTargets()
  })
}

function setTermDefinitionRef(
  termId: number,
  element: Element | ComponentPublicInstance | null,
) {
  const previous = termDefinitionElements.get(termId)
  if (previous) termResizeObserver?.unobserve(previous)
  if (element instanceof HTMLElement) {
    termDefinitionElements.set(termId, element)
    termResizeObserver?.observe(element)
    // 术语 Tab 首次渲染、切换对象或重新挂载时都重新测量。
    scheduleTermMeasure()
  } else {
    termDefinitionElements.delete(termId)
  }
}

function termNeedsExpansion(term: OverviewTermItem) {
  return overflowingTerms.value.has(term.termId)
}

function toggleTerm(termId: number) {
  const next = new Set(expandedTerms.value)
  if (next.has(termId)) next.delete(termId)
  else next.add(termId)
  expandedTerms.value = next
  // 收起后重新按实际截断高度决定是否保留展开入口。
  scheduleTermMeasure()
}

/** 每条只显示简短核验原因，完整后端说明按需展开，避免重复长提示。 */
function termReasonLabel(term: OverviewTermItem) {
  if (term.currentlyValid) return ''
  if (term.verificationCode && term.verificationCode !== 'VALID') {
    return verificationCodeLabel(term.verificationCode) || '核验状态未知'
  }
  return '当前有效性待确认'
}

function termReasonDetail(term: OverviewTermItem) {
  return term.currentlyValid ? '' : (term.validityMessage?.trim() || '')
}

function isReasonExpanded(termId: number) {
  return expandedReasons.value.has(termId)
}

function toggleReason(termId: number) {
  const next = new Set(expandedReasons.value)
  if (next.has(termId)) next.delete(termId)
  else next.add(termId)
  expandedReasons.value = next
}

async function removeCatalogTag(tag: OverviewTagItem) {
  if (!tag.id || !canManageTags.value) return
  // 固定发起时的对象与标签，确认期间切走对象后不再对新对象发请求。
  const targetEntityId = entityId.value
  const targetTagName = tag.name
  const objectToken = detailRequestId
  try {
    await ElMessageBox.confirm(
      `确认取消「${tag.displayName || tag.name}」与当前对象的标签关联？这不会删除共享标签本身。`,
      '取消标签关联',
      { type: 'warning', confirmButtonText: '取消关联', cancelButtonText: '保留' },
    )
    if (disposed || entityId.value !== targetEntityId || detailRequestId !== objectToken) {
      ElMessage.warning('对象已切换，已取消原标签操作。')
      return
    }
    await unconfirmEntityTag(targetEntityId, targetTagName)
    if (disposed || entityId.value !== targetEntityId || detailRequestId !== objectToken) return
    ElMessage.success('标签关联已取消')
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') {
      ElMessage.error(cause instanceof Error ? cause.message : '取消标签关联失败')
    }
  }
}

function openFieldGovernance() {
  const current = overview.value
  if (!current || fieldGovernanceUnavailableReason.value) {
    ElMessage.warning(fieldGovernanceUnavailableReason.value || '暂时无法定位字段解释。')
    return
  }
  const tableName = current.table?.tableName || current.column?.tableName
  if (!tableName) {
    ElMessage.warning('当前对象缺少来源快照中的表名，无法定位字段解释。')
    return
  }
  // 白名单上下文：字段解释按数据源、来源快照、表和 columnMetaId 定位。
  const query: Record<string, string> = {
    datasourceId: String(current.datasource!.id),
    snapshotId: String(current.sourceSnapshot!.id),
    tableName,
  }
  if (isColumn.value && current.column?.columnMetaId) {
    query.columnMetaId = String(current.column.columnMetaId)
  }
  router.push({ path: '/admin/semantics/field-explanations', query })
}

function openStatusMaintenance() {
  const current = overview.value
  if (!current || statusMaintenanceUnavailableReason.value) {
    ElMessage.warning(statusMaintenanceUnavailableReason.value || '暂时无法定位治理状态维护。')
    return
  }
  const tableName = current.table?.tableName || current.column?.tableName
  if (!tableName) {
    ElMessage.warning('当前对象缺少来源快照中的表名，无法定位治理状态维护。')
    return
  }
  // 白名单上下文：状态维护按表操作，字段对象先定位到所属表。
  const query: Record<string, string> = {
    datasourceId: String(current.datasource!.id),
    snapshotId: String(current.sourceSnapshot!.id),
    tableName,
  }
  router.push({ path: '/admin/governance/status', query })
}

function openLineageWorkspace() {
  const current = overview.value
  if (!current || lineageWorkspaceUnavailableReason.value) {
    ElMessage.warning(lineageWorkspaceUnavailableReason.value || '暂时无法打开血缘工作台。')
    return
  }
  // 白名单上下文：完整血缘按数据源 + 实体定位，不带快照或页内筛选。
  router.push({
    path: '/admin/assets/lineage',
    query: {
      datasourceId: String(current.datasource!.id),
      entityId: String(current.entity.id),
    },
  })
}

function goBack() {
  router.push(backTarget.value)
}

function structureRelationLabel(relation: OverviewRelationItem) {
  if (relation.relationType === 'CONTAINS') {
    return relation.targetId === entityId.value ? '所属数据源' : '包含表'
  }
  if (relation.relationType === 'HAS_PART') {
    return relation.targetId === entityId.value ? '所属表' : '包含字段'
  }
  if (relation.relationType === 'FOREIGN_KEY') return '外键引用'
  return relation.relationType
}

function relationPath(relation: OverviewRelationItem) {
  return `${display(relation.sourceName, `对象 #${relation.sourceId}`)} → ${display(relation.targetName, `对象 #${relation.targetId}`)}`
}

function lineageDirectionLabel(item: OverviewLineageItem) {
  return item.direction === 'UPSTREAM' ? '上游' : '下游'
}

function lineageDisplayType(item: OverviewLineageItem) {
  if (item.lineageType) return lineageTypeLabel(item.lineageType)
  if (item.relationType === 'DERIVED_FROM') return '字段派生'
  if (item.relationType === 'LINEAGE') return '表级血缘'
  return item.relationType
}

function lineagePeer(item: OverviewLineageItem) {
  return item.direction === 'UPSTREAM'
    ? display(item.sourceName, `对象 #${item.sourceId}`)
    : display(item.targetName, `对象 #${item.targetId}`)
}

function sectionUnavailable(section?: OverviewSection<unknown>) {
  return section && section.status !== 'OK'
}

function linkModeLabel(mode: string) {
  if (mode === 'DIRECT_COLUMN') return '直接关联当前字段'
  if (mode === 'DIRECT_TABLE') return '直接关联当前表'
  if (mode === 'BROKEN') return '术语关联记录不完整'
  return '通过字段汇总'
}

const verificationCodeLabels: Record<string, string> = {
  VALID: '核验通过',
  EVIDENCE_MISSING: '历史审核证据不足',
  CONTENT_CHANGED: '内容或来源已变化',
  SOURCE_INVALID: '关联来源失效',
  SYSTEM_ERROR: '核验读取失败',
  NOT_APPROVED: '尚未形成有效审核',
  TERM_MISSING: '术语记录缺失',
}

function verificationCodeLabel(code?: string) {
  if (!code) return ''
  return verificationCodeLabels[code] || '核验状态未知'
}

function termValidityLabel(term: { currentlyValid: boolean; status: string }) {
  if (term.currentlyValid) return '当前有效'
  if (term.status === 'APPROVED') return '需重新确认'
  return '未生效'
}

function termValidityTone(term: { currentlyValid: boolean; status: string }): 'success' | 'warning' | 'info' {
  if (term.currentlyValid) return 'success'
  if (term.status === 'APPROVED') return 'warning'
  return 'info'
}

async function load() {
  const requestedEntityId = entityId.value
  const requestId = ++detailRequestId
  loading.value = true
  error.value = ''
  overview.value = null
  columnPage.value = 1
  commentExpanded.value = false
  expandedTerms.value = new Set()
  expandedReasons.value = new Set()
  overflowingTerms.value = new Set()
  termDefinitionElements.forEach((element) => termResizeObserver?.unobserve(element))
  termDefinitionElements.clear()
  try {
    const result = await getEntityOverview(requestedEntityId)
    // 成功、失败和 loading 都由同一请求序号约束；旧对象响应不能覆盖新对象。
    if (disposed || requestId !== detailRequestId || entityId.value !== requestedEntityId) return
    overview.value = result.data
    scheduleTermMeasure()
  } catch (cause) {
    if (disposed || requestId !== detailRequestId || entityId.value !== requestedEntityId) return
    error.value = cause instanceof Error ? cause.message : '对象详情加载失败'
  } finally {
    if (!disposed && requestId === detailRequestId && entityId.value === requestedEntityId) {
      loading.value = false
    }
  }
}

watch(entityId, () => {
  requestedTab.value = queryTabValue()
  load()
})
watch(() => overview.value?.entity.id, () => { columnPage.value = 1 })
watch(() => route.query.tab, () => { requestedTab.value = queryTabValue() })
watch(activeTab, (tab) => {
  // 术语 Tab 采用懒渲染，进入时元素才出现，需要重新测量。
  if (tab === 'terms') scheduleTermMeasure()
  scheduleLayoutMeasure()
})
// 标题换行、提示增减、基本信息注释展开和技术信息开合都会改变稳定区域高度。
watch(
  [
    loading,
    error,
    overview,
    snapshotWarning,
    fieldGovernanceUnavailableReason,
    statusMaintenanceUnavailableReason,
    lineageWorkspaceUnavailableReason,
    commentExpanded,
    technicalOpen,
  ],
  () => scheduleLayoutMeasure(),
)

function handleViewportChange() {
  scheduleTermMeasure()
  scheduleLayoutMeasure()
}

onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') {
    termResizeObserver = new ResizeObserver(() => scheduleTermMeasure())
    termDefinitionElements.forEach((element) => termResizeObserver?.observe(element))
    layoutObserver = new ResizeObserver(() => scheduleLayoutMeasure())
  }
  if (typeof window !== 'undefined') {
    if (typeof window.matchMedia === 'function') {
      layoutMedia = window.matchMedia('(min-width: 1024px)')
      layoutMedia.addEventListener('change', scheduleLayoutMeasure)
    }
    window.addEventListener('resize', handleViewportChange)
  }
  iamS1.load().catch(() => {
    // 能力摘要失败时只影响写入按钮可见性，不影响详情阅读；后端仍是最终安全边界。
  })
  load()
  scheduleLayoutMeasure()
})
onBeforeUnmount(() => {
  disposed = true
  detailRequestId++
  termResizeObserver?.disconnect()
  termResizeObserver = null
  layoutObserver?.disconnect()
  layoutObserver = null
  observedLayoutTargets.clear()
  if (typeof window !== 'undefined') {
    layoutMedia?.removeEventListener('change', scheduleLayoutMeasure)
    layoutMedia = undefined
    window.removeEventListener('resize', handleViewportChange)
  }
  termDefinitionElements.clear()
})
</script>

<template>
  <div
    ref="pageRoot"
    class="admin-page entity-detail-page"
    :class="{ 'entity-detail-page--fixed': fixedLayout }"
    data-test="entity-detail-page"
  >
    <TaskPageHeader
      :title="primaryName"
      :help="isTable ? '表详情展示所在位置、字段结构、业务术语、标签与治理、所属关系及影响范围。' : '字段详情展示所属位置、原始注释、业务术语、标签与治理、使用限制及影响范围。'"
    >
      <template #title-prefix>
        <el-button link type="primary" class="entity-detail__back" :icon="ArrowLeft" @click="goBack">返回目录</el-button>
      </template>
      <template #title-suffix>
        <span class="entity-detail__type-tag" data-test="type-tag">{{ objectTypeLabel }}</span>
      </template>
      <template #actions>
        <el-button
          type="primary"
          :icon="ShieldCheck"
          :disabled="!canOpenFieldGovernance"
          :title="fieldGovernanceUnavailableReason || '在字段解释中定位当前对象'"
          @click="openFieldGovernance"
        >查看字段解释</el-button>
        <el-button
          :icon="SlidersHorizontal"
          :disabled="!canOpenStatusMaintenance"
          :title="statusMaintenanceUnavailableReason || '在治理状态维护中定位当前表'"
          @click="openStatusMaintenance"
        >维护治理状态</el-button>
        <el-button v-if="overview" :icon="Copy" @click="copyText(overview.entity.fqn, '完整路径')">复制路径</el-button>
        <el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
      </template>
      <template #meta>
        <div v-if="overview" class="entity-detail__meta">
          <div class="entity-detail__meta-group entity-detail__meta-group--path">
            <span class="entity-detail__meta-label">所属路径</span>
            <span class="entity-detail__meta-path" tabindex="0" :title="locationText">{{ locationText || '未记录' }}</span>
          </div>
          <div class="entity-detail__meta-group">
            <span class="entity-detail__meta-label">治理状态</span>
            <BusinessStatusBadge
              v-if="governanceStatus"
              :status="governanceStatus"
              :label="governanceStatusLabel(governanceStatus)"
            />
            <span v-else class="entity-detail__meta-empty">未记录</span>
          </div>
          <div class="entity-detail__meta-group entity-detail__meta-group--source">
            <span class="entity-detail__meta-label">来源</span>
            <span v-if="sourceSummary" class="entity-detail__source-summary">{{ sourceSummary }}</span>
            <template v-else>
              <span
                class="entity-detail__source-pill"
                :class="{ 'entity-detail__source-pill--warning': !sourceSnapshot }"
              >
                {{ sourceSnapshot ? `快照 v${sourceSnapshot.version}` : '快照不可核验' }}
              </span>
              <span class="entity-detail__source-pill">
                {{ publishedSnapshot ? `当前发布 v${publishedSnapshot.version}` : '暂无已发布目标' }}
              </span>
            </template>
          </div>
        </div>
      </template>
    </TaskPageHeader>

    <p v-if="overview && fieldGovernanceUnavailableReason" class="entity-detail__entry-hint">
      字段解释入口不可用：{{ fieldGovernanceUnavailableReason }}
    </p>

    <LoadingState v-if="loading" variant="skeleton" :rows="6" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="overview">
      <el-alert
        v-if="snapshotWarning"
        class="entity-detail__alert"
        :type="!overview.sourceVerified || overview.columns.status === 'ERROR' || publishedSnapshot?.entitySyncStatus === 'FAILED' ? 'error' : 'warning'"
        :closable="false"
        :title="snapshotWarning"
      />

      <el-alert
        v-if="isColumn && overview.columns.status !== 'OK'"
        class="entity-detail__alert"
        :type="overview.columns.status === 'ERROR' ? 'error' : 'warning'"
        :closable="false"
        :title="overview.columns.message || '字段来源不可用，技术属性与治理标签无法确认。'"
      />

      <section class="entity-detail__summary">
        <dl class="entity-detail__facts">
          <div v-for="fact in facts" :key="fact.label" class="entity-detail__fact">
            <dt>{{ fact.label }}</dt>
            <dd
              :class="{
                'entity-detail__mono': fact.mono,
                'entity-detail__fact-value--muted': fact.muted,
              }"
              :tabindex="fact.mono || fact.value.length > 30 ? 0 : undefined"
              :title="fact.value"
            >{{ fact.value }}</dd>
          </div>
        </dl>
        <div class="entity-detail__comment">
          <div class="entity-detail__comment-head">
            <span class="entity-detail__label">原始注释</span>
            <span class="entity-detail__provenance">来源：数据库采集</span>
          </div>
          <p :class="{ 'entity-detail__comment-text--clamped': commentIsLong && !commentExpanded }">
            {{ commentText }}
          </p>
          <el-button
            v-if="commentIsLong"
            link
            type="primary"
            class="entity-detail__comment-toggle"
            :aria-expanded="commentExpanded"
            @click="commentExpanded = !commentExpanded"
          >{{ commentExpanded ? '收起注释' : '展开完整注释' }}</el-button>
        </div>
      </section>

      <section class="entity-detail__panel">
        <el-tabs :model-value="activeTab" class="entity-detail__tabs" @update:model-value="selectTab">
          <el-tab-pane v-for="tab in detailTabs" :key="tab.key" :name="tab.key" lazy>
            <template #label>
              <span class="entity-detail__tab-label" :data-tab="tab.key">
                {{ tab.label }}
                <span
                  v-if="tab.count !== undefined"
                  class="entity-detail__tab-count"
                  :class="{ 'entity-detail__tab-count--zero': tab.count === 0 }"
                >{{ tab.count }}</span>
                <span v-else-if="tab.stateLabel" class="entity-detail__tab-state">{{ tab.stateLabel }}</span>
              </span>
            </template>

            <div v-if="tab.key === 'fields'" class="entity-detail__pane entity-detail__pane--table">
              <el-alert
                v-if="sectionUnavailable(overview.columns)"
                :type="sectionAlertType(overview.columns.status)"
                :closable="false"
                :title="overview.columns.message || '字段列表来源不可用'"
              />
              <template v-else-if="columnItems.length">
                <div class="entity-detail__table-region">
                  <el-table :data="pagedColumns" size="small" :height="fixedLayout ? '100%' : undefined">
                  <el-table-column label="字段" min-width="240">
                    <template #default="{ row }">
                      <div class="entity-detail__field-name">
                        <strong
                          :class="{ 'entity-detail__mono': !hasDistinctDisplayName(row) }"
                          :title="display(row.displayName, row.name)"
                        >{{ display(row.displayName, row.name) }}</strong>
                        <span
                          v-if="hasDistinctDisplayName(row)"
                          class="entity-detail__mono entity-detail__muted"
                          :title="row.name"
                        >技术名：{{ row.name }}</span>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="类型" width="120">
                    <template #default="{ row }"><span class="entity-detail__mono">{{ display(row.dataType, '未记录') }}</span></template>
                  </el-table-column>
                  <el-table-column label="约束" width="180">
                    <template #default="{ row }">{{ constraintText(row) }}</template>
                  </el-table-column>
                  <el-table-column label="原始注释" min-width="260">
                    <template #default="{ row }">
                      <span class="entity-detail__muted entity-detail__cell-clamp" :title="display(row.comment, '暂无原始注释')">
                        {{ display(row.comment, '暂无原始注释') }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column label="治理状态" width="126">
                    <template #default="{ row }">
                      <BusinessStatusBadge :status="row.governanceStatus" :label="governanceStatusLabel(row.governanceStatus)" />
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="112" align="right" header-align="right" fixed="right">
                    <template #default="{ row }">
                      <el-button link type="primary" :icon="ArrowRight" :disabled="!row.entityId" @click="openColumn(row)">查看字段</el-button>
                    </template>
                  </el-table-column>
                  </el-table>
                </div>
                <el-pagination
                  v-if="columnItems.length > columnPageSize"
                  v-model:current-page="columnPage"
                  class="entity-detail__pagination"
                  :page-size="columnPageSize"
                  :total="columnItems.length"
                  layout="prev, pager, next"
                  small
                />
              </template>
              <EmptyState
                v-else
                class="entity-detail__empty"
                message="来源快照中没有读到该表的字段结构。"
              />
            </div>

            <div v-else-if="tab.key === 'terms'" class="entity-detail__pane entity-detail__pane--scroll">
              <el-alert
                v-if="sectionUnavailable(overview.terms)"
                :type="sectionAlertType(overview.terms.status)"
                :closable="false"
                :title="overview.terms.message || '业务术语读取失败'"
              />
              <template v-else-if="overview.terms.items.length">
                <div v-if="termReasonSummary.length" class="entity-detail__reason-summary">
                  <span class="entity-detail__reason-summary-title">核验提示</span>
                  <span v-for="item in termReasonSummary" :key="item.code" class="entity-detail__reason-chip">
                    {{ item.label }} {{ item.count }} 条
                  </span>
                </div>
                <ul class="entity-detail__term-list">
                  <li v-for="term in overview.terms.items" :key="term.termId" class="entity-detail__term">
                    <div class="entity-detail__term-head">
                      <div class="entity-detail__term-title">
                        <strong>{{ term.displayName || term.name }}</strong>
                        <el-tag :type="termValidityTone(term)" effect="plain" size="small">
                          {{ termValidityLabel(term) }}
                        </el-tag>
                      </div>
                      <el-button
                        v-if="term.status !== 'MISSING' && term.glossaryId"
                        link
                        type="primary"
                        :icon="ArrowRight"
                        @click="openTerm(term.termId, term.glossaryId)"
                      >在术语工作区查看</el-button>
                      <span v-else-if="term.status === 'MISSING'" class="entity-detail__term-missing">
                        术语主记录缺失
                      </span>
                    </div>
                    <p
                      :ref="(element) => setTermDefinitionRef(term.termId, element)"
                      class="entity-detail__term-definition"
                      :class="{ 'entity-detail__term-definition--clamped': !isTermExpanded(term.termId) }"
                      data-test="term-definition"
                    >{{ display(term.description, '尚未填写业务定义。') }}</p>
                    <el-button
                      v-if="termNeedsExpansion(term)"
                      link
                      type="primary"
                      class="entity-detail__term-toggle"
                      data-test="term-toggle"
                      :aria-expanded="isTermExpanded(term.termId)"
                      @click="toggleTerm(term.termId)"
                    >{{ isTermExpanded(term.termId) ? '收起完整定义' : '展开完整定义' }}</el-button>
                    <div class="entity-detail__term-meta">
                      <span>审核状态：{{ glossaryTermStatusLabel(term.status) }}</span>
                      <span class="entity-detail__term-link">
                        {{ linkModeLabel(term.linkMode) }}<template v-if="term.linkMode === 'VIA_COLUMNS' && term.viaColumns.length">：{{ term.viaColumns.join('、') }}</template>
                      </span>
                    </div>
                    <div v-if="!term.currentlyValid" class="entity-detail__term-reason-block">
                      <p class="entity-detail__term-reason entity-detail__term-reason--warning">
                        <AlertTriangle :size="13" aria-hidden="true" />
                        <span>{{ termReasonLabel(term) }}</span>
                        <el-button
                          v-if="termReasonDetail(term)"
                          link
                          type="primary"
                          class="entity-detail__term-reason-toggle"
                          data-test="term-reason-toggle"
                          :aria-expanded="isReasonExpanded(term.termId)"
                          @click="toggleReason(term.termId)"
                        >{{ isReasonExpanded(term.termId) ? '收起说明' : '详细说明' }}</el-button>
                      </p>
                      <p
                        v-if="isReasonExpanded(term.termId) && termReasonDetail(term)"
                        class="entity-detail__term-reason-detail"
                      >{{ termReasonDetail(term) }}</p>
                    </div>
                  </li>
                </ul>
              </template>
              <EmptyState
                v-else
                class="entity-detail__empty"
                message="该对象当前没有已记录的业务术语关联。"
              />
            </div>

            <div v-else-if="tab.key === 'tags'" class="entity-detail__pane entity-detail__pane--scroll">
              <section class="entity-detail__subsection">
                <div class="entity-detail__subhead">
                  <h3>目录标签</h3>
                  <span v-if="overview.tags.status === 'OK'">{{ confirmedTags.length }} 个</span>
                  <span v-else>{{ sectionStateLabel(overview.tags.status) }}</span>
                </div>
                <el-alert
                  v-if="sectionUnavailable(overview.tags)"
                  :type="sectionAlertType(overview.tags.status)"
                  :closable="false"
                  :title="overview.tags.message || '标签读取失败'"
                />
                <template v-else>
                  <div v-if="confirmedTags.length" class="entity-detail__tag-list">
                    <div
                      v-for="(tag, index) in confirmedTags"
                      :key="tagKey(tag, index)"
                      class="entity-detail__tag-item"
                    >
                      <el-tag effect="plain">{{ tag.displayName || tag.name }}</el-tag>
                      <span class="entity-detail__tag-source">{{ tag.source }}</span>
                      <el-button
                        v-if="tag.id && canManageTags"
                        link
                        type="danger"
                        @click="removeCatalogTag(tag)"
                      >取消关联</el-button>
                    </div>
                  </div>
                  <p v-else class="entity-detail__inline-empty">暂无已确认的目录标签。</p>
                </template>
              </section>

              <section v-if="overview.tags.status === 'OK'" class="entity-detail__subsection">
                <div class="entity-detail__subhead">
                  <h3>候选标签</h3>
                  <span>{{ candidateTags.length }} 个</span>
                </div>
                <div v-if="candidateTags.length" class="entity-detail__tag-list">
                  <div
                    v-for="(tag, index) in candidateTags"
                    :key="tagKey(tag, index)"
                    class="entity-detail__tag-item"
                  >
                    <el-tag type="warning" effect="plain">{{ tag.displayName || tag.name }}</el-tag>
                    <span class="entity-detail__tag-source">{{ tag.source }}</span>
                  </div>
                </div>
                <p v-else class="entity-detail__inline-empty">暂无采集候选标签。</p>
              </section>

              <section v-if="overview.tags.status === 'OK' && abnormalTags.length" class="entity-detail__subsection">
                <div class="entity-detail__subhead">
                  <h3>关联异常</h3>
                  <span>{{ abnormalTags.length }} 个</span>
                </div>
                <div class="entity-detail__tag-list">
                  <div
                    v-for="(tag, index) in abnormalTags"
                    :key="tagKey(tag, index)"
                    class="entity-detail__tag-item"
                  >
                    <el-tag :type="tag.kind === 'BROKEN' ? 'danger' : 'info'" effect="plain">
                      {{ tag.displayName || tag.name }}
                    </el-tag>
                    <span class="entity-detail__tag-source">{{ tagKindLabel(tag.kind) }} · {{ tag.source }}</span>
                  </div>
                </div>
              </section>

              <section class="entity-detail__subsection">
                <div class="entity-detail__subhead" data-test="field-tags-head">
                  <h3><ShieldCheck :size="15" aria-hidden="true" />字段治理标签</h3>
                  <span v-if="overview.fieldTags.status !== 'OK'">{{ sectionStateLabel(overview.fieldTags.status) }}</span>
                  <span v-else-if="isColumn">{{ overview.fieldTags.items.length }} 个</span>
                  <span v-else>未汇总 · 按字段查看</span>
                </div>
                <el-alert
                  v-if="sectionUnavailable(overview.fieldTags)"
                  :type="sectionAlertType(overview.fieldTags.status)"
                  :closable="false"
                  :title="overview.fieldTags.message || '字段治理标签读取失败'"
                />
                <p v-else-if="isTable" class="entity-detail__hint">
                  字段治理标签按字段维护，目前没有表级汇总；请从“字段”Tab 打开具体字段查看，不能据此推断整张表所有字段都没有标签。
                </p>
                <div v-else-if="overview.fieldTags.items.length" class="entity-detail__tag-list">
                  <div v-for="tag in overview.fieldTags.items" :key="tag.id" class="entity-detail__tag-item">
                    <el-tag effect="plain">{{ tag.tagName }}</el-tag>
                    <span class="entity-detail__tag-source">来源：{{ tag.source }} · 当前快照字段治理</span>
                  </div>
                </div>
                <p v-else class="entity-detail__hint">
                  当前字段在当前发布快照中尚未维护字段治理标签。字段治理标签按快照维护，新快照发布后需要在该快照重新确认。
                </p>
              </section>

              <section v-if="isColumn" class="entity-detail__subsection">
                <div class="entity-detail__subhead">
                  <h3>使用限制</h3>
                </div>
                <ul v-if="overview.column?.usageLimits?.length" class="entity-detail__limits">
                  <li v-for="limit in overview.column.usageLimits" :key="limit">{{ limit }}</li>
                </ul>
                <p v-else class="entity-detail__hint">未记录额外使用限制。</p>
              </section>

              <p class="entity-detail__hint">标签用于分类与提示，不等同于权限、治理状态或已生效的脱敏策略。</p>
            </div>

            <div v-else-if="tab.key === 'relations'" class="entity-detail__pane entity-detail__pane--table">
              <el-alert
                v-if="sectionUnavailable(overview.structureRelations)"
                :type="sectionAlertType(overview.structureRelations.status)"
                :closable="false"
                :title="overview.structureRelations.message || '所属关系读取失败'"
              />
              <div v-else-if="overview.structureRelations.items.length" class="entity-detail__table-region">
                <el-table
                  :data="overview.structureRelations.items"
                  size="small"
                  :height="fixedLayout ? '100%' : undefined"
                >
                <el-table-column label="关系" width="110">
                  <template #default="{ row }">{{ structureRelationLabel(row) }}</template>
                </el-table-column>
                <el-table-column label="端点" min-width="240">
                  <template #default="{ row }">{{ relationPath(row) }}</template>
                </el-table-column>
                <el-table-column label="绑定状态" width="110">
                  <template #default="{ row }">
                    <span
                      :class="{ 'entity-detail__binding-warning': bindingStatusWarning(row.bindingStatus) }"
                      :title="`原始值：${bindingStatusRaw(row.bindingStatus)}`"
                    >{{ bindingStatusLabel(row.bindingStatus) }}</span>
                  </template>
                </el-table-column>
                </el-table>
              </div>
              <template v-else>
                <EmptyState class="entity-detail__empty" message="暂无已记录的所属或引用关系。" />
                <p class="entity-detail__hint">关系记录来自当前结构与人工关联，没有记录不代表对象没有关联。</p>
              </template>
            </div>

            <div v-else-if="tab.key === 'lineage'" class="entity-detail__pane entity-detail__pane--table">
              <div class="entity-detail__pane-toolbar">
                <p class="entity-detail__hint">这里展示目录中已记录的关系；完整图谱、搜索与影响分析在数据血缘工作台。</p>
                <el-button
                  :icon="Network"
                  :disabled="!canOpenLineageWorkspace"
                  :title="lineageWorkspaceUnavailableReason || '在数据血缘工作台中查看当前对象'"
                  @click="openLineageWorkspace"
                >在血缘工作台中查看</el-button>
              </div>
              <el-alert
                v-if="sectionUnavailable(overview.lineage)"
                :type="sectionAlertType(overview.lineage.status)"
                :closable="false"
                :title="overview.lineage.message || '血缘读取失败'"
              />
              <div v-else-if="overview.lineage.items.length" class="entity-detail__table-region">
                <el-table
                  :data="overview.lineage.items"
                  size="small"
                  :height="fixedLayout ? '100%' : undefined"
                >
                <el-table-column label="方向" width="80">
                  <template #default="{ row }">{{ lineageDirectionLabel(row) }}</template>
                </el-table-column>
                <el-table-column label="类型" width="110">
                  <template #default="{ row }">{{ lineageDisplayType(row) }}</template>
                </el-table-column>
                <el-table-column label="关联对象" min-width="220">
                  <template #default="{ row }">{{ lineagePeer(row) }}</template>
                </el-table-column>
                <el-table-column label="绑定状态" width="110">
                  <template #default="{ row }">
                    <span
                      :class="{ 'entity-detail__binding-warning': bindingStatusWarning(row.bindingStatus) }"
                      :title="`原始值：${bindingStatusRaw(row.bindingStatus)}`"
                    >{{ bindingStatusLabel(row.bindingStatus) }}</span>
                  </template>
                </el-table-column>
                </el-table>
              </div>
              <template v-else>
                <EmptyState class="entity-detail__empty" message="暂无已记录血缘。" />
                <p class="entity-detail__hint">没有记录不代表已经证明该对象没有业务影响。</p>
              </template>
            </div>

            <div v-else-if="tab.key === 'downstream'" class="entity-detail__pane entity-detail__pane--table">
              <el-alert
                v-if="sectionUnavailable(overview.downstream)"
                :type="sectionAlertType(overview.downstream.status)"
                :closable="false"
                :title="overview.downstream.message || '下游影响读取失败'"
              />
              <div v-else-if="overview.downstream.items.length" class="entity-detail__table-region">
                <el-table
                  :data="overview.downstream.items"
                  size="small"
                  :height="fixedLayout ? '100%' : undefined"
                >
                <el-table-column label="类型" width="120">
                  <template #default="{ row }">{{ lineageDisplayType(row) }}</template>
                </el-table-column>
                <el-table-column label="上游对象" min-width="220">
                  <template #default="{ row }">{{ display(row.sourceName, `对象 #${row.sourceId}`) }}</template>
                </el-table-column>
                <el-table-column label="下游对象" min-width="220">
                  <template #default="{ row }">{{ display(row.targetName, `对象 #${row.targetId}`) }}</template>
                </el-table-column>
                <el-table-column label="绑定状态" width="110">
                  <template #default="{ row }">
                    <span
                      :class="{ 'entity-detail__binding-warning': bindingStatusWarning(row.bindingStatus) }"
                      :title="`原始值：${bindingStatusRaw(row.bindingStatus)}`"
                    >{{ bindingStatusLabel(row.bindingStatus) }}</span>
                  </template>
                </el-table-column>
                </el-table>
              </div>
              <template v-else>
                <EmptyState class="entity-detail__empty" message="暂无已记录的下游影响。" />
                <p class="entity-detail__hint">未记录下游边不代表已经证明该对象没有影响。</p>
              </template>
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>

      <section class="entity-detail__technical">
        <el-collapse v-model="technicalOpen">
          <el-collapse-item name="technical">
            <template #title><Info :size="15" />技术标识与原始元数据</template>
            <dl class="entity-detail__technical-list">
              <div><dt>对象 ID</dt><dd>{{ overview.entity.id }}</dd></div>
              <div><dt>对象类型（原始值）</dt><dd class="entity-detail__mono">{{ overview.entity.entityType }}</dd></div>
              <div><dt>对象 UUID</dt><dd class="entity-detail__mono">{{ display(overview.entity.entityUuid, '未记录') }}</dd></div>
              <div>
                <dt>完整路径</dt>
                <dd class="entity-detail__mono entity-detail__technical-path">
                  <span>{{ overview.entity.fqn }}</span>
                  <el-button link type="primary" :icon="Copy" @click="copyText(overview.entity.fqn, '完整路径')">复制</el-button>
                </dd>
              </div>
              <div><dt>目录对象版本</dt><dd>v{{ overview.entity.version }}</dd></div>
              <div v-if="isTable && overview.table?.tableType">
                <dt>数据表类型（原始值）</dt>
                <dd class="entity-detail__mono">{{ overview.table.tableType }}</dd>
              </div>
              <div>
                <dt>绑定状态取值</dt>
                <dd class="entity-detail__mono">BOUND=已关联；UNBOUND=关联失效</dd>
              </div>
              <div>
                <dt>详情来源快照 ID</dt>
                <dd>{{ sourceSnapshot ? sourceSnapshot.id : '无法核验' }}</dd>
              </div>
              <div>
                <dt>当前发布目标快照 ID</dt>
                <dd>{{ publishedSnapshot ? publishedSnapshot.id : '暂无' }}</dd>
              </div>
              <div v-for="[key, value] in technicalEntries" :key="key">
                <dt>{{ key }}</dt>
                <dd class="entity-detail__mono">{{ value == null ? '未记录' : typeof value === 'object' ? JSON.stringify(value) : String(value) }}</dd>
              </div>
            </dl>
          </el-collapse-item>
        </el-collapse>
      </section>
    </template>
  </div>
</template>

<style scoped>
.entity-detail-page { min-width: 0; display: grid; gap: 12px; }
/* 本页复用紧凑头部：共享组件默认的外边距和字号保持不变。 */
.entity-detail-page :deep(.task-page-header) { margin-bottom: 0; }
.entity-detail-page :deep(.task-page-header__title) { padding-right: 24px; }
.entity-detail-page :deep(.task-page-header h1) { font-size: clamp(22px, 2.2vw, 28px); }
.entity-detail-page :deep(.task-page-header__actions) { align-self: center; }

.entity-detail__back { flex: 0 0 auto; }
.entity-detail__type-tag {
  display: inline-flex;
  align-items: center;
  padding: 3px 9px;
  border-radius: var(--do-radius-full);
  background: var(--do-primary-soft);
  color: var(--do-primary-strong);
  font-size: 12px;
  font-weight: 700;
  line-height: 1.4;
  white-space: nowrap;
}
.entity-detail__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 20px;
  min-width: 0;
}
.entity-detail__meta-group { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; min-width: 0; }
.entity-detail__meta-group--path { flex: 1 1 340px; }
.entity-detail__meta-label { flex: 0 0 auto; color: var(--do-muted); font-size: 12px; }
.entity-detail__meta-path {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--do-ink);
  font-size: 12.5px;
  line-height: 1.5;
}
.entity-detail__meta-path:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--do-primary) 32%, transparent);
  outline-offset: 2px;
  border-radius: 4px;
}
.entity-detail__meta-empty { color: var(--do-muted); font-size: 12px; }

.entity-detail__source-summary { color: var(--do-muted); font-size: 12px; line-height: 1.4; }
.entity-detail__source-pill {
  display: inline-flex;
  align-items: center;
  padding: 4px 9px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-full);
  background: var(--do-soft);
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.2;
  white-space: nowrap;
}
.entity-detail__source-pill--warning {
  border-color: color-mix(in srgb, var(--do-warning) 30%, var(--do-line));
  background: var(--do-warning-soft);
  color: var(--do-warning);
  font-weight: 600;
}
.entity-detail__entry-hint { margin: 0; color: var(--do-warning); font-size: 12px; line-height: 1.5; }
.entity-detail__alert { border-radius: var(--do-radius-md); }

/* 基本信息：按容器宽度在 1 / 2 / 4 列之间切换，4 项与 8 项都不会出现孤立末项。 */
.entity-detail__summary {
  container: entity-facts / inline-size;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}
.entity-detail__facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin: 0;
  padding: 6px 8px;
}
.entity-detail__fact {
  min-width: 0;
  padding: 10px 10px;
}
.entity-detail__fact dt { color: var(--do-muted); font-size: 12px; }
.entity-detail__fact dd {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
  color: var(--do-ink);
  font-size: 13.5px;
  line-height: 1.5;
  white-space: normal;
}
.entity-detail__fact-value--muted { color: var(--do-muted); }
.entity-detail__fact dd:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--do-primary) 32%, transparent);
  outline-offset: 2px;
  border-radius: 4px;
}
@container entity-facts (max-width: 560px) {
  .entity-detail__facts { grid-template-columns: 1fr; }
}
@container entity-facts (min-width: 1000px) {
  .entity-detail__facts { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}

.entity-detail__comment { padding: 10px 16px 12px; border-top: 1px solid var(--do-line); }
.entity-detail__comment-head { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.entity-detail__label { color: var(--do-ink); font-size: 12.5px; font-weight: 700; }
.entity-detail__provenance {
  padding: 1px 7px;
  border-radius: var(--do-radius-full);
  background: var(--do-soft);
  color: var(--do-muted);
  font-size: 11px;
}
.entity-detail__comment p {
  max-width: 88ch;
  margin: 4px 0 0;
  overflow-wrap: anywhere;
  color: var(--do-ink);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.entity-detail__comment-text--clamped {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}
.entity-detail__comment-toggle { height: auto; margin-top: 2px; padding: 0; font-size: 12px; }

/* 单一内容区：Tab 在主体面板内切换，不再堆叠多张等高卡片。 */
.entity-detail__panel {
  min-width: 0;
  padding: 2px 16px 14px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}
.entity-detail__tabs,
.entity-detail__tabs :deep(.el-tabs__content) { min-width: 0; }
.entity-detail__tabs :deep(.el-tabs__header) { margin: 0 0 12px; }
.entity-detail__tabs :deep(.el-tabs__nav-wrap) { min-width: 0; }
.entity-detail__tabs :deep(.el-tabs__item) { height: 42px; padding: 0 14px; font-size: 13px; }
.entity-detail__tabs :deep(.el-tabs__item:first-child) { padding-left: 0; }
.entity-detail__tab-label { display: inline-flex; align-items: center; gap: 6px; white-space: nowrap; }
.entity-detail__tab-count {
  min-width: 18px;
  padding: 0 6px;
  border-radius: var(--do-radius-full);
  background: var(--do-soft);
  color: var(--do-muted);
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}
.entity-detail__tab-count--zero { background: transparent; opacity: .65; }
.entity-detail__tab-state { color: var(--do-muted); font-size: 11px; }
.entity-detail__pane { min-width: 0; display: grid; gap: 12px; }
.entity-detail__table-region { min-width: 0; min-height: 0; }

/*
 * 桌面固定模式：页面高度取自后台主内容区，标题、提示、基本信息和 Tab 不移动，
 * 只有活动正文滚动。表格正文由 el-table 自己滚动，分页留在表格外保持可见；
 * 非表格 Tab 只让当前正文容器滚动，避免与外层同时出现纵向滚动条。
 */
.entity-detail-page--fixed {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}
.entity-detail-page--fixed > * { flex: 0 0 auto; }
.entity-detail-page--fixed > .entity-detail__panel {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.entity-detail-page--fixed .entity-detail__tabs {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.entity-detail-page--fixed .entity-detail__tabs :deep(.el-tabs__header) { flex: 0 0 auto; }
.entity-detail-page--fixed .entity-detail__tabs :deep(.el-tabs__content) {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.entity-detail-page--fixed .entity-detail__tabs :deep(.el-tab-pane) {
  flex: 1 1 auto;
  min-height: 0;
  height: 100%;
}
.entity-detail-page--fixed .entity-detail__pane { height: 100%; min-height: 0; }
.entity-detail-page--fixed .entity-detail__pane--table {
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
}
.entity-detail-page--fixed .entity-detail__pane--table > :not(.entity-detail__table-region) {
  flex: 0 0 auto;
}
.entity-detail-page--fixed .entity-detail__table-region { flex: 1 1 auto; min-height: 0; }
.entity-detail-page--fixed .entity-detail__pane--table :deep(.el-table) { height: 100%; }
.entity-detail-page--fixed .entity-detail__pane--scroll {
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 4px;
}

.entity-detail__pane :deep(.el-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: var(--do-soft);
  --el-table-border-color: var(--do-line);
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--do-primary) 5%, var(--do-surface));
  color: var(--do-ink);
  font-size: 13px;
}
.entity-detail__pane :deep(.el-table::before),
.entity-detail__pane :deep(.el-table__inner-wrapper::before) { display: none; }
.entity-detail__pane :deep(.el-table th.el-table__cell) {
  height: 40px;
  padding: 0;
  background: var(--do-soft);
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 600;
}
.entity-detail__pane :deep(.el-table td.el-table__cell) { padding: 10px 0; vertical-align: middle; }
.entity-detail__pane :deep(.el-table .cell) { padding: 0 12px; line-height: 1.5; }
.entity-detail__pane :deep(.el-table th.el-table__cell .cell) { line-height: 40px; }
.entity-detail__field-name { display: grid; gap: 2px; min-width: 0; }
.entity-detail__field-name strong { color: var(--do-ink); font-size: 13.5px; overflow-wrap: anywhere; }
.entity-detail__field-name span { font-size: 12px; overflow-wrap: anywhere; }
.entity-detail__cell-clamp {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow-wrap: anywhere;
}
.entity-detail__pagination { justify-content: flex-end; margin-top: 2px; }

.entity-detail__subsection { min-width: 0; display: grid; gap: 10px; }
.entity-detail__subsection + .entity-detail__subsection {
  padding-top: 12px;
  border-top: 1px solid var(--do-line);
}
.entity-detail__subhead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px 10px;
}
.entity-detail__subhead h3 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: var(--do-ink);
  font-size: 13.5px;
}
.entity-detail__subhead span { color: var(--do-muted); font-size: 12px; white-space: nowrap; }

.entity-detail__tag-list { display: grid; gap: 8px; }
.entity-detail__tag-item { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; min-width: 0; }
.entity-detail__tag-source { min-width: 0; color: var(--do-muted); font-size: 12px; overflow-wrap: anywhere; }
.entity-detail__inline-empty { margin: 0; color: var(--do-muted); font-size: 12.5px; line-height: 1.55; }
.entity-detail__limits { margin: 0; padding-left: 18px; color: var(--do-ink); font-size: 13px; line-height: 1.6; }
.entity-detail__hint { margin: 0; color: var(--do-muted); font-size: 12px; line-height: 1.55; }
.entity-detail__pane-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-bottom: 10px;
}

.entity-detail__reason-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 9px 12px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  background: var(--do-soft);
}
.entity-detail__reason-summary-title { color: var(--do-muted); font-size: 12px; font-weight: 700; }
.entity-detail__reason-chip { color: var(--do-warning); font-size: 12px; }

.entity-detail__term-list { display: grid; gap: 10px; margin: 0; padding: 0; list-style: none; }
.entity-detail__term {
  min-width: 0;
  padding: 13px 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  background: var(--do-soft);
}
.entity-detail__term-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px 14px;
  flex-wrap: wrap;
}
.entity-detail__term-title { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; min-width: 0; }
.entity-detail__term-title strong { color: var(--do-ink); font-size: 14px; overflow-wrap: anywhere; }
.entity-detail__term-definition {
  max-width: 78ch;
  margin: 6px 0 0;
  overflow-wrap: anywhere;
  color: var(--do-ink);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.entity-detail__term-definition--clamped {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}
.entity-detail__term-toggle { height: auto; margin-top: 2px; padding: 0; font-size: 12px; }
.entity-detail__term-reason-toggle { height: auto; padding: 0; font-size: 12px; }
.entity-detail__term-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 14px;
  max-width: 78ch;
  margin-top: 6px;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.5;
}
.entity-detail__term-link { overflow-wrap: anywhere; }
.entity-detail__term-reason-block { margin-top: 6px; }
.entity-detail__term-reason {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.5;
}
.entity-detail__term-reason--warning { color: var(--do-warning); }
.entity-detail__term-reason-detail {
  max-width: 78ch;
  margin: 6px 0 0;
  padding: 8px 10px;
  border-radius: var(--do-radius-sm);
  background: var(--do-surface);
  color: var(--do-ink);
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.entity-detail__term-missing { color: var(--do-muted); font-size: 12px; }
.entity-detail__binding-warning { color: var(--do-warning); font-weight: 600; }

.entity-detail__muted { color: var(--do-muted); }
.entity-detail__mono { font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace; }

.entity-detail__pane :deep(.entity-detail__empty.empty-state) { padding: 16px 12px; gap: 6px; }
.entity-detail__pane :deep(.entity-detail__empty .empty-icon) { display: none; }
.entity-detail__pane :deep(.entity-detail__empty .empty-message) { font-size: 13px; }

.entity-detail__technical {
  padding: 4px 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-soft);
}
.entity-detail__technical :deep(.el-collapse) { border: 0; }
.entity-detail__technical :deep(.el-collapse-item__header),
.entity-detail__technical :deep(.el-collapse-item__wrap) { border-bottom: 0; background: transparent; }
.entity-detail__technical :deep(.el-collapse-item__header) { gap: 7px; color: var(--do-ink); font-size: 13px; }
.entity-detail__technical-list { display: grid; gap: 8px; margin: 0; padding: 0 0 12px; }
.entity-detail__technical-list > div {
  display: grid;
  grid-template-columns: 150px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}
.entity-detail__technical-list dt { color: var(--do-muted); font-size: 12px; }
.entity-detail__technical-list dd { min-width: 0; margin: 0; overflow-wrap: anywhere; color: var(--do-ink); font-size: 12.5px; }
.entity-detail__technical-path { display: flex; align-items: flex-start; gap: 8px; flex-wrap: wrap; }

@media (max-width: 760px) {
  .entity-detail__panel { padding: 2px 12px 12px; }
  .entity-detail__technical-list > div { grid-template-columns: 1fr; gap: 2px; }
  .entity-detail__meta-group--path { flex-basis: 100%; }
}
</style>
