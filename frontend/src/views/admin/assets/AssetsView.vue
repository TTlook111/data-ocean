<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Database, ExternalLink, RefreshCw, Search, Table2 } from 'lucide-vue-next'
import { useAdminContextStore } from '../../../stores/adminContext'
import { getDatasourceReadiness, type DatasourceReadiness } from '../../../api/admin/datasource'
import { getEntitiesByDatasource, type MetadataEntityItem } from '../../../api/admin/catalog'
import { entityTypeLabel } from '../../../utils/enumLabels'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import TableExplorer from '../metadata/TableExplorer.vue'
import { useRoute, useRouter } from 'vue-router'

const context = useAdminContextStore()
const route = useRoute()
const router = useRouter()
const activeView = computed(() => route.query.view === 'tables' ? 'tables' : 'catalog')
const entities = ref<MetadataEntityItem[]>([])
const readiness = ref<DatasourceReadiness | null>(null)
const keyword = ref('')
const entityType = ref('')
const loading = ref(false)
const error = ref('')
const requestId = ref(0)

// 实体类型中文映射统一取自 utils/enumLabels.ts（§10.1 不重复实现状态映射）。
// 原先本文件自带一份近似副本，GLOSSARY_TERM 的译法与共享映射还不一致。

const filteredEntities = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return entities.value.filter((item) => {
    const matchesType = !entityType.value || item.entityType === entityType.value
    const matchesKeyword = !q || [item.name, item.displayName, item.fqn, item.description]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(q))
    return matchesType && matchesKeyword
  })
})

function entityLabel(item: MetadataEntityItem) {
  return entityTypeLabel(item.entityType)
}

async function load() {
  const id = context.datasourceId
  if (!id) {
    entities.value = []
    readiness.value = null
    return
  }
  const currentRequest = ++requestId.value
  loading.value = true
  error.value = ''
  try {
    const [readinessResult, entitiesResult] = await Promise.all([
      getDatasourceReadiness(id),
      getEntitiesByDatasource(id),
    ])
    if (currentRequest !== requestId.value) return
    readiness.value = readinessResult.data
    entities.value = readinessResult.data.publishedSnapshotId ? (entitiesResult.data || []) : []
  } catch (cause) {
    if (currentRequest !== requestId.value) return
    entities.value = []
    error.value = cause instanceof Error ? cause.message : '资产目录加载失败'
  } finally {
    if (currentRequest === requestId.value) loading.value = false
  }
}

onMounted(async () => {
  try {
    await context.initialize()
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '数据源范围加载失败'
    loading.value = false
  }
})

watch(() => context.datasourceId, load)
</script>

<template>
  <div class="admin-page assets-page">
    <TaskPageHeader
      eyebrow="数据资产"
      title="资产目录"
      description="只浏览当前数据源已发布快照同步后的正式资产。关键词和类型筛选在前端执行，不使用无法保证数据源隔离的全局搜索接口。"
    >
      <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新资产</el-button></template>
    </TaskPageHeader>

    <section class="assets-page__scope">
      <Database :size="18" />
      <div>
        <strong>{{ context.currentDatasource?.name || '未选择数据源' }}</strong>
        <span v-if="readiness?.publishedSnapshotId">正式资产范围 · 快照 v{{ readiness.snapshotVersion }}</span>
        <span v-else>尚未确认有已发布快照</span>
      </div>
      <BusinessStatusBadge v-if="readiness" :status="readiness.publishedSnapshotId ? 'PUBLISHED' : readiness.stage" :label="readiness.publishedSnapshotId ? '正式资产' : '待发布'" />
    </section>

    <section class="assets-page__filters">
      <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索表名、字段名、FQN 或说明" />
      <el-select v-model="entityType" clearable placeholder="全部资产类型">
        <el-option label="表" value="TABLE" />
        <el-option label="字段" value="COLUMN" />
        <el-option label="术语" value="GLOSSARY_TERM" />
        <el-option label="标签" value="TAG" />
      </el-select>
    </section>

    <el-tabs :model-value="activeView" @update:model-value="(view: string | number) => router.push({ query: { ...route.query, view: view === 'tables' ? 'tables' : undefined } })">
      <el-tab-pane label="正式资产目录" name="catalog" />
      <el-tab-pane label="快照表浏览" name="tables" />
    </el-tabs>

    <TableExplorer v-if="activeView === 'tables'" />

    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <LoadingState v-else-if="loading" variant="skeleton" :rows="6" />
    <template v-else-if="!context.datasourceId">
      <EmptyState message="请先在顶部范围栏选择数据源，再查看正式资产目录。" action-text="去数据源接入" @action="$router.push('/admin/data-sources')" />
    </template>
    <template v-else-if="!readiness?.publishedSnapshotId">
      <EmptyState message="当前数据源没有已发布快照，正式资产目录暂不可用。" action-text="去版本发布" @action="$router.push({ path: '/admin/releases', query: { datasourceId: String(context.datasourceId) } })" />
    </template>
    <section v-else-if="filteredEntities.length" class="assets-page__table">
      <div class="assets-page__result-heading">
        <span>当前范围 {{ filteredEntities.length }} 个资产</span>
        <span>数据源内前端过滤</span>
      </div>
      <el-table :data="filteredEntities" stripe>
        <el-table-column label="资产名称" min-width="220">
          <template #default="{ row }">
            <div class="asset-name">
              <Table2 v-if="row.entityType === 'TABLE'" :size="16" />
              <Database v-else :size="16" />
              <div><strong>{{ row.displayName || row.name }}</strong><span>{{ row.fqn }}</span></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110"><template #default="{ row }"><BusinessStatusBadge :status="row.entityType" :label="entityLabel(row)" /></template></el-table-column>
        <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
        <el-table-column prop="version" label="资产版本" width="100" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }"><el-button link type="primary" :icon="ExternalLink" @click="$router.push('/admin/assets/entities/' + row.id)">查看详情</el-button></template>
        </el-table-column>
      </el-table>
    </section>
    <EmptyState v-else message="当前正式资产范围内没有匹配结果。" />
  </div>
</template>

<style scoped>
.assets-page {
  display: grid;
  gap: 16px;
}

.assets-page__scope {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid rgba(77, 143, 220, .22);
  border-radius: var(--do-radius-lg);
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
}

.assets-page__scope div {
  display: grid;
  flex: 1;
  gap: 3px;
}

.assets-page__scope strong { color: var(--do-ink); font-size: 14px; }
.assets-page__scope span { color: var(--do-muted); font-size: 12px; }

.assets-page__filters {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) 180px;
  gap: 10px;
}

.assets-page__table {
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
}

.assets-page__result-heading {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
  color: var(--do-muted);
  font-size: 12px;
}

.asset-name {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.asset-name > svg { flex: 0 0 auto; margin-top: 2px; color: var(--do-primary-strong); }
.asset-name div { display: grid; gap: 3px; }
.asset-name strong { color: var(--do-ink); }
.asset-name span { color: var(--do-muted); font-family: monospace; font-size: 11px; }

</style>
