<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Component } from 'vue'
import { CircleHelp, Columns3, Copy, Database, ExternalLink, RefreshCw, Search, Table2 } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../../stores/auth'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAdminContextStore } from '../../../stores/adminContext'
import { getPublishedCatalog, type MetadataEntityItem, type PublishedCatalog } from '../../../api/admin/catalog'
import { entityTypeLabel } from '../../../utils/enumLabels'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import ScopeBar from '../../../components/admin/ScopeBar.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import TableExplorer from '../metadata/TableExplorer.vue'
import { useRoute, useRouter } from 'vue-router'

const context = useAdminContextStore()
const auth = useAuthStore()
const iam = useIamS1Store()
const scopeProblem = computed(() => {
  const raw = route.query.datasourceId
  if (raw == null) return ''
  const id = typeof raw === 'string' ? Number(raw) : NaN
  if (!Number.isSafeInteger(id) || id <= 0) return '指定的数据源标识无效，未改用其他数据源。'
  if (!iam.loaded) return iam.errorMessage || ''
  if (!iam.canOnDatasource('metadata:view', id)) return '没有指定数据源的元数据查看权限，未改用其他数据源。'
  if (id !== context.datasourceId) return '指定数据源不存在或范围尚未就绪，未改用其他数据源。'
  return ''
})
const canRead = computed(() => !scopeProblem.value && iam.loaded && !iam.errorMessage && iam.canOnDatasource('metadata:view', context.datasourceId))
const canEnterDatasource = computed(() => iam.loaded && !iam.errorMessage && (iam.hasGlobal('datasource:view') || iam.datasourcesWithFunction('datasource:view').length > 0))
const canRelease = computed(() => iam.loaded && !iam.errorMessage && iam.canOnDatasource('metadata:release:view', context.datasourceId))
const route = useRoute()
const router = useRouter()
const activeView = computed(() => route.query.view === 'tables' ? 'tables' : 'catalog')
const entityType = ref<'TABLE' | 'COLUMN'>(route.query.type === 'COLUMN' ? 'COLUMN' : 'TABLE')
const keyword = ref(String(route.query.q || ''))
const catalog = ref<PublishedCatalog | null>(null)
const loading = ref(false)
const error = ref('')
const requestId = ref(0)
const assetsPageRef = ref<HTMLElement>()
const fixedCatalogHeight = ref(false)
const fixedTablesHeight = ref(false)
const expandedPaths = ref<Set<number>>(new Set())
let catalogMedia: MediaQueryList | undefined
let tableMedia: MediaQueryList | undefined
let layoutResizeObserver: ResizeObserver | undefined
let observedLayoutElements = new Set<Element>()
let layoutFrame: number | undefined
let queryTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false

const helpText = '查找已发布的表和字段，查看所在位置、技术信息、业务含义与关联影响。这里只展示已发布快照同步后的正式目录。表对象代表一张数据库表，字段对象代表表中的一列；业务术语、标签和字段治理状态作为对象关联信息展示，并进入各自的维护工作区处理。'

const typeOptions = [
  { value: 'TABLE' as const, label: entityTypeLabel('TABLE'), icon: Table2 },
  { value: 'COLUMN' as const, label: entityTypeLabel('COLUMN'), icon: Columns3 },
]
const typeIcons: Record<string, Component> = {
  TABLE: Table2,
  COLUMN: Columns3,
}
const catalogTypes = new Set(['TABLE', 'COLUMN'])

const sourceEntities = computed(() =>
  (catalog.value?.entities || []).filter((item) => catalogTypes.has(item.entityType)),
)
const typeEntities = computed(() => sourceEntities.value.filter((item) => item.entityType === entityType.value))
const filteredEntities = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return typeEntities.value
  return typeEntities.value.filter((item) => [
    item.name,
    item.displayName,
    item.fqn,
    item.description,
    owningTable(item),
  ].filter(Boolean).some((value) => String(value).toLowerCase().includes(q)))
})
const tableCount = computed(() => catalog.value?.tableCount
  ?? sourceEntities.value.filter((item) => item.entityType === 'TABLE').length)
const columnCount = computed(() => catalog.value?.columnCount
  ?? sourceEntities.value.filter((item) => item.entityType === 'COLUMN').length)
const matchUnit = computed(() => entityType.value === 'COLUMN' ? '个字段' : '张表')
const nameColumnLabel = computed(() => entityType.value === 'COLUMN' ? '字段' : '表')
const filteredTableColumnCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const item of sourceEntities.value) {
    if (item.entityType !== 'COLUMN') continue
    const table = owningTable(item)
    counts.set(table, (counts.get(table) || 0) + 1)
  }
  return counts
})

const catalogWarning = computed(() => {
  const status = catalog.value?.syncStatus
  if (status === 'FAILED') {
    return catalog.value?.syncError
      ? `目录同步失败：${catalog.value.syncError}。列表可能仍显示上一次成功同步的内容，不代表本次快照已完整可用。`
      : '目录同步失败，列表可能是不完整或旧内容，不代表本次快照已完整可用。'
  }
  if (status === 'SYNCING') return '目录正在同步本次快照，列表可能尚未完整。'
  if (!status) return '历史发布版本没有记录目录同步状态，当前目录内容可能不完整，请以元数据版本记录为准。'
  return ''
})
const catalogWarningType = computed<'error' | 'warning'>(
  () => catalog.value?.syncStatus === 'FAILED' ? 'error' : 'warning',
)

const scopeNote = computed(() => {
  if (scopeProblem.value) return '指定数据源范围不可用'
  if (!context.datasourceId) return '请选择数据源'
  if (!iam.loaded && !iam.errorMessage) return '正在核验元数据查看权限'
  if (!canRead.value) return iam.errorMessage ? '元数据权限读取失败' : '没有当前源的元数据查看权限'
  if (activeView.value === 'tables') return '按所选快照查看表和字段结构'
  if (error.value) return '范围状态读取失败'
  if (loading.value && !catalog.value) return '正在读取已发布范围'
  if (!catalog.value?.publishedSnapshotId) return '当前数据源暂无已发布快照'
  return catalog.value.snapshotVersion ? `已发布快照 v${catalog.value.snapshotVersion}` : '已发布快照'
})

function metadataOf(item: MetadataEntityItem): Record<string, unknown> {
  if (!item.entityMetadata) return {}
  try {
    const parsed = JSON.parse(item.entityMetadata)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

function owningTable(item: MetadataEntityItem) {
  const metadata = metadataOf(item)
  const fromMetadata = typeof metadata.table_name === 'string' ? metadata.table_name : ''
  if (fromMetadata) return fromMetadata
  const parts = (item.fqn || '').split('.')
  return item.entityType === 'COLUMN' && parts.length >= 2 ? parts[parts.length - 2] : ''
}

function primaryName(item: MetadataEntityItem) {
  return item.displayName?.trim() || item.name
}

function hasDistinctTechnicalName(item: MetadataEntityItem) {
  const displayName = item.displayName?.trim()
  return Boolean(displayName && displayName !== item.name)
}

function hasDescription(item: MetadataEntityItem) {
  return Boolean(item.description?.trim())
}

function entityIcon(type: string) {
  return typeIcons[type] ?? Database
}

function isPathExpanded(id: number) {
  return expandedPaths.value.has(id)
}

function togglePath(id: number) {
  const next = new Set(expandedPaths.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedPaths.value = next
}

/** 仅用于收起状态：中间省略，保留路径末尾的定位信息；搜索仍使用完整 FQN。 */
function shortenFqn(value: string) {
  if (value.length <= 56) return value
  return `${value.slice(0, 20)}…${value.slice(-34)}`
}

async function copyFqn(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success('已复制完整路径')
  } catch {
    ElMessage.error('复制失败，请手动选择路径')
  }
}

function updateQuery(next: Record<string, string | undefined>) {
  const query = { ...route.query }
  Object.entries(next).forEach(([key, value]) => {
    if (value == null || value === '') delete query[key]
    else query[key] = value
  })
  router.replace({ query })
}

function switchType(type: 'TABLE' | 'COLUMN') {
  if (entityType.value === type) return
  entityType.value = type
}

function switchView(view: string | number) {
  router.push({ query: { ...route.query, view: view === 'tables' ? 'tables' : undefined } })
}

function openDetail(item: MetadataEntityItem) {
  captureScroll()
  router.push({
    path: `/admin/assets/entities/${item.id}`,
    query: {
      ...route.query,
      q: keyword.value.trim() || undefined,
      type: entityType.value,
    },
  })
}

/** 滚动位置按“数据源 + 视图 + 类型 + 搜索词”分别保存，返回目录时恢复合理位置。 */
function scrollKey(datasourceId: number | undefined = context.datasourceId) {
  return `dataocean-assets-scroll:${datasourceId || 0}:${activeView.value}:${entityType.value}:${keyword.value.trim()}`
}

/** 桌面固定表格模式：真实表格正文滚动容器。 */
function tableScrollContainer(): HTMLElement | null {
  return (document.querySelector('.assets-panel__body .el-table__body-wrapper .el-scrollbar__wrap')
    || document.querySelector('.assets-panel__body .el-scrollbar__wrap')) as HTMLElement | null
}

/** 自然滚动模式：后台主内容区滚动容器，不是 window。 */
function contentScrollContainer(): HTMLElement | null {
  return document.querySelector('.admin-shell__content') as HTMLElement | null
}

function captureScroll(datasourceId: number | undefined = context.datasourceId) {
  try {
    // 加载中表格尚未渲染时不要用 0 覆盖已有的有效位置。
    if (loading.value && !catalog.value) return
    const table = tableScrollContainer()
    const content = contentScrollContainer()
    // 两种布局都记录，返回时即使窗口高度变化也能恢复到对应容器。
    sessionStorage.setItem(scrollKey(datasourceId), JSON.stringify({
      tableTop: table ? table.scrollTop : 0,
      contentTop: content ? content.scrollTop : 0,
    }))
  } catch {
    // 浏览器禁用 sessionStorage 时不影响目录使用。
  }
}

function restoreScroll(expectedKey: string) {
  try {
    // 异步返回前核对组件、路由和查询条件，避免旧页面的回调滚动新页面。
    if (disposed || scrollKey() !== expectedKey || loading.value) return
    const raw = sessionStorage.getItem(expectedKey)
    if (!raw) return
    const saved = JSON.parse(raw) as { tableTop?: number; contentTop?: number }
    if (fixedCatalogHeight.value && activeView.value === 'catalog') {
      const table = tableScrollContainer()
      // 0 也是有效位置，不能用真假判断跳过。
      if (table && typeof saved.tableTop === 'number') table.scrollTop = saved.tableTop
    } else {
      const content = contentScrollContainer()
      if (content && typeof saved.contentTop === 'number') content.scrollTop = saved.contentTop
    }
  } catch {
    // 忽略损坏的滚动记录。
  }
}

async function load() {
  const id = context.datasourceId
  if (!id || !canRead.value) {
    requestId.value++
    catalog.value = null
    error.value = scopeProblem.value
    loading.value = false
    return
  }
  const currentRequest = ++requestId.value
  const token = auth.token
  const current = () => !disposed && currentRequest === requestId.value && token === auth.token && id === context.datasourceId && canRead.value
  const keyAtLoad = scrollKey()
  loading.value = true
  error.value = ''
  catalog.value = null
  try {
    const catalogResult = await getPublishedCatalog(id)
    if (!current()) return
    if (!catalogResult.data || catalogResult.data.datasourceId !== id) throw new Error('目录来源与当前数据源不一致，请刷新重试。')
    catalog.value = catalogResult.data
  } catch (cause) {
    if (!current()) return
    catalog.value = null
    error.value = cause instanceof Error ? cause.message : '数据对象目录加载失败'
  } finally {
    if (current()) {
      loading.value = false
      // loading 结束且对应 DOM 渲染后再恢复；一次 nextTick + 一帧渲染同步，不用固定延时。
      await nextTick()
      requestAnimationFrame(() => {
        if (!error.value && !disposed) restoreScroll(keyAtLoad)
      })
    }
  }
}

function syncAssetsMedia(event?: MediaQueryListEvent) {
  fixedCatalogHeight.value = event ? event.matches : (catalogMedia?.matches ?? false)
  scheduleTableLayoutCheck()
}

function observeAssetsLayout() {
  if (!layoutResizeObserver || !assetsPageRef.value) return
  const page = assetsPageRef.value
  const targets = new Set<Element>([
    page.closest('.admin-shell__content'),
    page.querySelector('.task-page-header'),
    page.querySelector('.assets-page__tabs'),
    page.querySelector('.assets-panel__scope'),
    page.querySelector('.snapshot-toolbar'),
  ].filter((element): element is Element => element != null))
  for (const element of observedLayoutElements) {
    if (!targets.has(element)) {
      layoutResizeObserver.unobserve(element)
      observedLayoutElements.delete(element)
    }
  }
  for (const element of targets) {
    if (observedLayoutElements.has(element)) continue
    layoutResizeObserver.observe(element)
    observedLayoutElements.add(element)
  }
}

function scheduleTableLayoutCheck() {
  if (layoutFrame != null) cancelAnimationFrame(layoutFrame)
  void nextTick(() => {
    if (disposed) return
    layoutFrame = requestAnimationFrame(() => {
      layoutFrame = undefined
      if (disposed) return
      const page = assetsPageRef.value
      const content = page?.closest('.admin-shell__content') as HTMLElement | null
      const header = page?.querySelector('.task-page-header') as HTMLElement | null
      const tabs = page?.querySelector('.assets-page__tabs') as HTMLElement | null
      const scope = page?.querySelector('.assets-panel__scope') as HTMLElement | null
      const toolbar = page?.querySelector('.snapshot-toolbar') as HTMLElement | null
      if (!page || activeView.value !== 'tables' || !tableMedia?.matches || !content || !header || !tabs || !toolbar) {
        fixedTablesHeight.value = false
        observeAssetsLayout()
        return
      }

      const contentStyle = getComputedStyle(content)
      const availableHeight = content.clientHeight
        - (Number.parseFloat(contentStyle.paddingTop) || 0)
        - (Number.parseFloat(contentStyle.paddingBottom) || 0)
      const pageGap = Number.parseFloat(getComputedStyle(page).rowGap) || 0
      const remaining = availableHeight
        - header.getBoundingClientRect().height
        - tabs.getBoundingClientRect().height
        - (scope?.getBoundingClientRect().height || 0)
        - toolbar.getBoundingClientRect().height
        - pageGap * 2

      // 保留标题/属性区与至少约 240px 的字段正文；阈值只决定滚动模式，不设置表格固定高度。
      fixedTablesHeight.value = remaining >= 480
      observeAssetsLayout()
    })
  })
}

watch([keyword, entityType], () => {
  if (queryTimer) clearTimeout(queryTimer)
  queryTimer = setTimeout(() => {
    updateQuery({ q: keyword.value.trim() || undefined, type: entityType.value })
  }, 250)
})

watch(() => route.query.q, (value) => {
  const next = String(value || '')
  if (next !== keyword.value) keyword.value = next
})

watch(() => route.query.type, (value) => {
  const next = value === 'COLUMN' ? 'COLUMN' : 'TABLE'
  if (next !== entityType.value) entityType.value = next
})

onMounted(async () => {
  catalogMedia = window.matchMedia('(min-width: 960px) and (min-height: 700px)')
  tableMedia = window.matchMedia('(min-width: 960px)')
  if (typeof ResizeObserver !== 'undefined') {
    layoutResizeObserver = new ResizeObserver(scheduleTableLayoutCheck)
  }
  window.addEventListener('resize', scheduleTableLayoutCheck)
  tableMedia.addEventListener('change', scheduleTableLayoutCheck)
  syncAssetsMedia()
  catalogMedia.addEventListener('change', syncAssetsMedia)
  observeAssetsLayout()
  scheduleTableLayoutCheck()
  const token = auth.token
  try {
    await context.initialize()
    if (disposed || token !== auth.token) return
    await load()
  } catch (cause) {
    if (disposed || token !== auth.token) return
    error.value = cause instanceof Error ? cause.message : '数据源范围加载失败'
    loading.value = false
  }
})

onBeforeUnmount(() => {
  captureScroll()
  disposed = true
  requestId.value++
  if (queryTimer) clearTimeout(queryTimer)
  if (layoutFrame != null) cancelAnimationFrame(layoutFrame)
  layoutResizeObserver?.disconnect()
  layoutResizeObserver = undefined
  observedLayoutElements.clear()
  window.removeEventListener('resize', scheduleTableLayoutCheck)
  tableMedia?.removeEventListener('change', scheduleTableLayoutCheck)
  catalogMedia?.removeEventListener('change', syncAssetsMedia)
})

watch(activeView, async () => {
  await nextTick()
  observeAssetsLayout()
  scheduleTableLayoutCheck()
})

// 数据源切换会触发重新加载；先用同步 watcher 记录旧数据源目录的真实滚动位置。
watch(() => context.datasourceId, (_newId, oldId) => captureScroll(oldId), { flush: 'sync' })
watch([() => context.datasourceId, () => route.query.datasourceId], load, { flush: 'sync' })
watch(() => auth.token, () => { requestId.value++; catalog.value = null; loading.value = false; error.value = ''; expandedPaths.value = new Set(); load() }, { flush: 'sync' })
watch([() => iam.snapshot, () => iam.loaded, () => iam.errorMessage], load, { flush: 'sync' })
</script>

<template>
  <div
    ref="assetsPageRef"
    class="admin-page assets-page"
    :class="{
      'assets-page--fixed-catalog': activeView === 'catalog' && fixedCatalogHeight,
      'assets-page--fixed-tables': activeView === 'tables' && fixedTablesHeight,
    }"
  >
    <TaskPageHeader
      title="数据对象目录"
      eyebrow="数据资产 / 对象与结构"
      description="从已发布对象理解数据，或回到指定快照核对结构。" description-mode="inline"
      :help="helpText"
    >
      <template #actions>
        <el-button v-if="activeView === 'catalog'" :icon="RefreshCw" :loading="loading" @click="load">刷新目录</el-button>
      </template>
    </TaskPageHeader>

    <el-tabs :model-value="activeView" class="assets-page__tabs" @update:model-value="switchView">
      <el-tab-pane label="已发布对象" name="catalog" />
      <el-tab-pane label="快照表结构" name="tables" />
    </el-tabs>

    <section class="assets-panel do-glass-panel">
      <div class="assets-panel__scope">
        <div class="assets-panel__scope-picker">
          <ScopeBar mode="datasource" refresh-label="刷新数据源列表" compact />
        </div>
        <div class="assets-panel__scope-note"><span class="catalog-origin">{{ activeView === 'catalog' ? '正式对象目录' : '快照结构' }}</span><strong>{{ scopeNote }}</strong><span v-if="activeView === 'catalog' && catalog?.syncStatus === 'SYNCED' && !loading && !error">目录同步完成</span></div>
      </div>

      <template v-if="activeView === 'catalog'">
        <el-alert
          v-if="!loading && !error && catalog && context.datasourceId && catalog.publishedSnapshotId && catalogWarning"
          class="assets-panel__sync-warning"
          :type="catalogWarningType"
          :closable="false"
          :title="catalogWarning"
        />

        <div class="assets-panel__filters">
          <el-input
            aria-label="搜索已发布对象" v-model="keyword"
            class="assets-panel__search"
            clearable
            :prefix-icon="Search"
            :placeholder="entityType === 'COLUMN' ? '搜索字段、所属表或说明' : '搜索表名、中文名或说明'"
          />
          <el-radio-group
            aria-label="对象类型" :model-value="entityType"
            class="assets-panel__type"
            @update:model-value="(value: string | number | boolean | undefined) => switchType(value as 'TABLE' | 'COLUMN')"
          >
            <el-radio-button v-for="option in typeOptions" :key="option.value" :value="option.value">
              <component :is="option.icon" :size="14" aria-hidden="true" />
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
          <span v-if="!loading && !error && context.datasourceId && catalog?.publishedSnapshotId" class="assets-panel__count">
            <span>当前范围 {{ tableCount }} 张表 · {{ columnCount }} 个字段</span>
            <span>匹配 {{ filteredEntities.length }} {{ matchUnit }}</span>
          </span>
        </div>

        <div class="assets-panel__body">
          <LoadingState v-if="!iam.loaded && !iam.errorMessage" variant="skeleton" :rows="5" />
          <ErrorState v-else-if="scopeProblem || (context.datasourceId && !canRead)" :message="scopeProblem || '没有当前数据源的元数据查看权限。'" @retry="iam.load(true)" />
          <ErrorState v-else-if="error" :message="error" @retry="load" />
          <LoadingState v-else-if="loading" variant="skeleton" :rows="6" />
          <template v-else-if="!context.datasourceId">
            <EmptyState message="请选择数据源后，再查看已发布对象。" :action-text="canEnterDatasource ? '去数据源接入' : undefined" @action="$router.push('/admin/data-sources')" />
          </template>
          <template v-else-if="!catalog?.publishedSnapshotId">
            <EmptyState message="当前数据源没有已发布快照，暂无可浏览的已发布对象。" :action-text="canRelease ? '去元数据版本' : undefined" @action="$router.push({ path: '/admin/releases', query: { datasourceId: String(context.datasourceId) } })" />
          </template>
          <template v-else-if="catalog?.syncStatus === 'FAILED' && !filteredEntities.length">
            <ErrorState :message="catalogWarning" @retry="load" />
          </template>
          <div v-else-if="filteredEntities.length" class="assets-panel__table">
            <el-table :data="filteredEntities" :height="fixedCatalogHeight ? '100%' : undefined">
              <el-table-column :label="nameColumnLabel" min-width="320">
                <template #default="{ row }">
                  <div class="asset-name">
                    <component :is="entityIcon(row.entityType)" :size="16" class="asset-name__icon" aria-hidden="true" />
                    <div class="asset-name__copy">
                      <div class="asset-name__head">
                        <button type="button" class="asset-open" :title="primaryName(row)" @click="openDetail(row)">{{ primaryName(row) }}</button>
                        <span v-if="hasDistinctTechnicalName(row)" class="asset-tech-name" translate="no" :title="row.name">{{ row.name }}</span>
                      </div>
                      <div class="asset-path" :class="{ 'asset-path--expanded': isPathExpanded(row.id) }">
                        <span class="asset-path__label">路径：</span>
                        <button type="button" class="asset-path__toggle" :aria-expanded="isPathExpanded(row.id)" @click.stop="togglePath(row.id)">
                          {{ isPathExpanded(row.id) ? row.fqn : shortenFqn(row.fqn) }}
                        </button>
                        <button type="button" class="asset-path__copy" :aria-label="`复制完整路径 ${row.fqn}`" @click.stop="copyFqn(row.fqn)"><Copy :size="12" aria-hidden="true" /></button>
                      </div>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column v-if="entityType === 'COLUMN'" label="所属表" min-width="170">
                <template #default="{ row }"><span class="asset-table-name" translate="no">{{ owningTable(row) || '未能解析' }}</span></template>
              </el-table-column>
              <el-table-column v-if="entityType === 'TABLE'" label="字段数" width="88">
                <template #default="{ row }">{{ filteredTableColumnCounts.get(row.name) || 0 }}</template>
              </el-table-column>
              <el-table-column label="原始注释" min-width="230">
                <template #default="{ row }">
                  <span class="asset-description" :title="hasDescription(row) ? row.description : '暂无原始注释'">
                    {{ hasDescription(row) ? row.description : '暂无原始注释' }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="对象版本" width="100">
                <template #header>
                  <el-tooltip content="这是目录对象自身的版本编号，与元数据快照版本不同。" placement="top" :trigger="['hover', 'focus']">
                    <span class="asset-version-head" tabindex="0">对象版本<CircleHelp :size="13" aria-hidden="true" /></span>
                  </el-tooltip>
                </template>
                <template #default="{ row }"><span class="asset-version">v{{ row.version }}</span></template>
              </el-table-column>
              <el-table-column label="操作" width="116" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" :icon="ExternalLink" @click="openDetail(row)">查看详情</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <EmptyState
            v-else
            :message="keyword.trim() ? '当前已发布范围内没有匹配的对象。' : `当前已发布范围内没有${entityType === 'COLUMN' ? '字段' : '表'}对象。`"
          />
        </div>
      </template>

      <EmptyState v-else-if="!context.datasourceId" message="请选择数据源后查看快照表结构。" :action-text="canEnterDatasource ? '去数据源接入' : undefined" @action="$router.push('/admin/data-sources')" />
      <ErrorState v-else-if="!canRead" :message="scopeProblem || '没有当前数据源的元数据查看权限。'" @retry="iam.load(true)" />
      <TableExplorer v-else :fixed-height="fixedTablesHeight" />
    </section>
  </div>
</template>

<style scoped>
.asset-open { border:0; background:transparent; font:inherit; font-weight:600; text-align:left; color:var(--do-ink); padding:0; cursor:pointer; }
.asset-open:hover { color:var(--do-primary-strong); text-decoration:underline; text-underline-offset:4px; }
.assets-panel__scope-note { display:flex; align-items:center; flex-wrap:wrap; gap:8px 16px; }
.assets-panel__scope-note strong { font-weight:500; color:var(--do-ink); }
.catalog-origin { border-left:2px solid var(--do-primary); padding-left:8px; }
.assets-page { min-height: 0; display: flex; flex-direction: column; gap: 14px; }
.assets-page--fixed-catalog { height: 100%; }
.assets-page--fixed-tables { height: 100%; flex: 1 1 auto; min-height: 0; }
.assets-page--fixed-catalog :deep(.task-page-header),
.assets-page--fixed-catalog .assets-page__tabs,
.assets-page--fixed-tables :deep(.task-page-header),
.assets-page--fixed-tables .assets-page__tabs { flex: 0 0 auto; }
.assets-page :deep(.task-page-header) { align-items: center; margin-bottom: 0; }
.assets-page :deep(.task-page-header__copy) { flex: 1 1 auto; min-width: 0; }
.assets-page :deep(.task-page-header h1) { font-size: clamp(24px, 2.2vw, 28px); line-height: 1.2; letter-spacing: -.025em; }
.assets-page__tabs :deep(.el-tabs__header) { margin-bottom: 10px; }
.assets-page__tabs :deep(.el-tab-pane) { padding-top: 0; }

.assets-panel {
  min-width: 0;
  padding: 0;
  overflow: hidden;
  border-color: var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-glass-data-bg);
  box-shadow: none;
  clip-path: inset(0 round var(--do-radius-lg));
}

.assets-panel__scope { flex:0 0 auto; display: flex; align-items: center; flex-wrap: wrap; gap: 10px 14px; padding: 12px 20px; border-bottom: 1px solid var(--do-line); }
.assets-panel__scope-picker { min-width: 0; flex: 1 1 auto; display: flex; flex-wrap: wrap; gap: 8px; }
.assets-panel__scope-picker :deep(.scope-bar) { display: block; width: 100%; margin: 0; padding: 0; border: 0; border-radius: 0; background: transparent; }
.assets-panel__scope-picker :deep(.scope-bar__intro) { display: none; }
.assets-panel__scope-picker :deep(.scope-bar--compact),
.assets-panel__scope-picker :deep(.scope-bar--compact .scope-bar__controls) { display: contents; }
.assets-panel__scope-picker :deep(.scope-bar__controls) { align-items: center; flex-wrap: wrap; gap: 8px; }
.assets-panel__scope-picker :deep(.scope-bar__datasource-control) { flex: 0 1 488px; min-width: 240px; max-width: 488px; }
.assets-panel__scope-picker :deep(.scope-bar__datasource-control .el-select) { flex: 1 1 400px; width: 400px; min-width: 180px; max-width: 440px; }
.assets-panel__scope-picker :deep(.scope-bar__controls .el-select__wrapper) { min-height: 36px; }
.assets-page :deep(.scope-bar--compact .scope-bar__refresh-icon) { min-height: 36px; padding: 0; border-color: transparent; color: var(--do-muted); background: transparent; box-shadow: none; }
.assets-page :deep(.scope-bar--compact .scope-bar__refresh-icon:hover) { color: var(--do-primary-strong); background: var(--do-primary-soft); }
.assets-panel__scope-picker :deep(.scope-bar__warning) { margin-top: 6px; }
.assets-panel__scope-note { margin-left: auto; color: var(--do-muted); font-size: 12px; line-height: 1.5; text-align: right; }

.assets-panel__sync-warning { border-radius: 0; border-width: 0 0 1px; border-color: var(--do-line); }
.assets-panel__filters { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 12px 16px; border-bottom: 1px solid var(--do-line); }
.assets-panel__search { flex: 1 1 320px; min-width: 220px; max-width: 400px; }
.assets-panel__type :deep(.el-radio-button__inner) { display: inline-flex; align-items: center; gap: 5px; height: 36px; padding: 0 14px; font-size: 13px; }
.assets-panel__filters :deep(.el-input__wrapper) { min-height: 36px; }
.assets-panel__filters :deep(.el-input__inner) { font-size: 13px; }
.assets-panel__count { margin-left: auto; display: flex; gap: 12px; color: var(--do-muted); font-size: 12px; white-space: nowrap; }

.assets-panel__body { min-height: 0; }
.assets-page--fixed-catalog .assets-panel { flex: 1 1 auto; min-height: 320px; display: flex; flex-direction: column; }
.assets-page--fixed-catalog .assets-panel__scope,
.assets-page--fixed-catalog .assets-panel__filters,
.assets-page--fixed-catalog .assets-panel__sync-warning { flex: 0 0 auto; }
.assets-page--fixed-catalog .assets-panel__body { flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; }
.assets-page--fixed-catalog .assets-panel__table { flex: 1 1 auto; min-height: 0; }

.assets-page--fixed-tables .assets-panel { flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; }
.assets-page--fixed-tables :deep(.table-explorer-page) { flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; }

.assets-panel__table { min-width: 0; }
.assets-panel :deep(.el-table) {
  --el-table-bg-color: var(--do-surface);
  --el-table-tr-bg-color: var(--do-surface);
  --el-table-header-bg-color: var(--do-soft);
  --el-table-border-color: var(--do-line);
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--do-primary) 5%, var(--do-surface));
  color: var(--do-ink);
}
.assets-panel :deep(.el-table::before),
.assets-panel :deep(.el-table__inner-wrapper::before) { display: none; }
.assets-panel :deep(.el-table th.el-table__cell) { height: 42px; padding: 0; background: var(--do-soft); color: var(--do-muted); font-size: 12px; font-weight: 600; }
.assets-panel :deep(.el-table th.el-table__cell .cell) { padding: 0 12px; line-height: 42px; }
.assets-panel :deep(.el-table td.el-table__cell) { padding: 13px 0; vertical-align: middle; border-bottom-color: var(--do-line); }
.assets-panel :deep(.el-table td.el-table__cell .cell) { padding: 0 12px; line-height: 1.45; }
.assets-panel :deep(.el-table th.el-table__cell:first-child .cell),
.assets-panel :deep(.el-table td.el-table__cell:first-child .cell) { padding-left: 16px; }
.assets-panel :deep(.el-table th.el-table__cell:last-child .cell),
.assets-panel :deep(.el-table td.el-table__cell:last-child .cell) { padding-right: 16px; }
.assets-panel :deep(.el-table__body tr:last-child td.el-table__cell) { border-bottom: 0; }

.asset-name { min-width: 0; min-height: 46px; display: flex; align-items: flex-start; gap: 8px; }
.asset-name__icon { flex: 0 0 auto; margin-top: 2px; color: var(--do-primary-strong); }
.asset-name__copy { min-width: 0; display: grid; gap: 3px; }
.asset-name__head { min-width: 0; display: flex; align-items: baseline; gap: 6px; }
.asset-name__head strong { flex: 1 1 auto; min-width: 0; overflow: hidden; color: var(--do-ink); font-size: 13.5px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.asset-tech-name { flex: 0 1 auto; min-width: 0; overflow: hidden; color: var(--do-muted); font-size: 11.5px; font-weight: 400; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.asset-path { min-width: 0; display: flex; align-items: center; gap: 4px; color: var(--do-muted); font-size: 12px; line-height: 1.45; }
.asset-path__label { flex: 0 0 auto; }
.asset-path__toggle { min-width: 0; overflow: hidden; padding: 0; border: 0; color: var(--do-muted); background: transparent; font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace; font-size: 12px; line-height: 1.45; text-align: left; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.asset-path--expanded .asset-path__toggle { overflow: visible; white-space: normal; word-break: break-all; }
.asset-path__toggle:focus-visible { outline: 2px solid color-mix(in srgb, var(--do-primary) 32%, transparent); outline-offset: 1px; }
.asset-path__copy { flex: 0 0 auto; width: 22px; height: 22px; display: grid; place-items: center; padding: 0; border: 0; border-radius: 5px; color: var(--do-muted); background: transparent; cursor: pointer; }
.asset-path__copy:hover { color: var(--do-primary-strong); background: var(--do-primary-soft); }
.asset-path__copy:focus-visible { outline: 2px solid color-mix(in srgb, var(--do-primary) 32%, transparent); outline-offset: 1px; }

.entity-type { display: inline-flex; align-items: center; gap: 5px; padding: 3px 8px; border-radius: var(--do-radius-full); color: var(--do-primary-strong); background: var(--do-primary-soft); font-size: 12px; font-weight: 600; white-space: nowrap; }
.asset-table-name { color: var(--do-ink); font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace; font-size: 12.5px; }
.asset-description { display: block; max-width: 100%; overflow: hidden; color: var(--do-muted); font-size: 12.5px; text-overflow: ellipsis; white-space: nowrap; }
.asset-version-head { display: inline-flex; align-items: center; gap: 4px; border-radius: 4px; outline: none; }
.asset-version-head:focus-visible { outline: 2px solid color-mix(in srgb, var(--do-primary) 32%, transparent); outline-offset: 2px; }
.asset-version { color: var(--do-ink); font-size: 12.5px; white-space: nowrap; }

@media (max-width: 900px) {
  .assets-panel__scope-picker { flex: 1 1 100%; }
  .assets-panel__scope-picker :deep(.scope-bar__datasource-control) { flex: 1 1 100%; min-width: 0; max-width: none; }
  .assets-panel__scope-picker :deep(.scope-bar__datasource-control .el-select) { flex: 1 1 180px; width: auto; min-width: 0; max-width: none; }
  .assets-panel__scope-note { margin-left: 0; text-align: left; }
  .assets-panel__filters { padding: 10px 12px; }
  .assets-panel__search { flex: 1 1 100%; max-width: none; }
  .assets-panel__count { margin-left: 0; flex-wrap: wrap; }
}
</style>
