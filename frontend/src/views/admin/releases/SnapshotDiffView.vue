<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { GitCompareArrows } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { compareSnapshots, getPublishedSnapshot } from '../../../api/admin/versioning'
import { getSnapshotDetail, type SnapshotDetail } from '../../../api/admin/metadata'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import ObjectContextSummary from '../../../components/admin/ObjectContextSummary.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import { buildContextQuery } from '../../../utils/adminNavigation'

const route = useRoute()
// 差异 URL 协议：snapshotId 是旧快照，compareId 是新快照。
const oldSnapshotId = computed(() => Number(route.params.snapshotId))
const newSnapshotId = computed(() => Number(route.params.compareId))
const oldSnapshot = ref<SnapshotDetail | null>(null)
const newSnapshot = ref<SnapshotDetail | null>(null)
const diff = ref<any>(null)
const currentOfficialSnapshotId = ref<number | undefined>()
const currentOfficialError = ref('')
const loading = ref(true)
const error = ref('')
let requestId = 0

const baseIsCurrentOfficial = computed(() => currentOfficialSnapshotId.value !== undefined && currentOfficialSnapshotId.value === oldSnapshot.value?.snapshot.id)
const comparedIsCurrentOfficial = computed(() => currentOfficialSnapshotId.value !== undefined && currentOfficialSnapshotId.value === newSnapshot.value?.snapshot.id)
const returnToRelease = computed(() => ({
  path: '/admin/releases',
  query: buildContextQuery('datasource', { datasourceId: oldSnapshot.value?.snapshot.datasourceId }),
}))

async function load() {
  const currentRequest = ++requestId
  loading.value = true
  error.value = ''
  oldSnapshot.value = null
  newSnapshot.value = null
  diff.value = null
  currentOfficialSnapshotId.value = undefined
  currentOfficialError.value = ''
  try {
    const [oldResult, newResult] = await Promise.all([
      getSnapshotDetail(oldSnapshotId.value),
      getSnapshotDetail(newSnapshotId.value),
    ])
    if (disposedOrStale(currentRequest)) return
    if (oldResult.data.snapshot.datasourceId !== newResult.data.snapshot.datasourceId) {
      throw new Error('两个快照必须属于同一个数据源')
    }
    const diffResult = await compareSnapshots(oldSnapshotId.value, newSnapshotId.value)
    if (disposedOrStale(currentRequest)) return
    let officialId: number | undefined
    let officialLookupError = ''
    try {
      const officialResult = await getPublishedSnapshot(oldResult.data.snapshot.datasourceId)
      if (disposedOrStale(currentRequest)) return
      officialId = officialResult.data?.snapshotId
    } catch {
      officialLookupError = '当前正式版身份读取失败，无法标记生效版本。'
    }
    if (disposedOrStale(currentRequest)) return
    oldSnapshot.value = oldResult.data
    newSnapshot.value = newResult.data
    diff.value = diffResult.data
    currentOfficialSnapshotId.value = officialId
    currentOfficialError.value = officialLookupError
  } catch (cause) {
    if (disposedOrStale(currentRequest)) return
    error.value = cause instanceof Error ? cause.message : '版本差异加载失败'
  } finally {
    if (!disposedOrStale(currentRequest)) loading.value = false
  }
}

function disposedOrStale(currentRequest: number) {
  return currentRequest !== requestId
}

onMounted(load)
watch(() => [route.params.snapshotId, route.params.compareId], load)
onBeforeUnmount(() => { requestId++ })
</script>

<template>
  <div class="admin-page snapshot-diff-page">
    <ObjectContextSummary v-if="oldSnapshot" :title="'基准版本 v' + oldSnapshot.snapshot.snapshotVersion + ' → 对比版本 v' + (newSnapshot?.snapshot.snapshotVersion || newSnapshotId)" description="差异方向从基准版本指向对比版本" :back-to="returnToRelease" source-label="数据资产 / 元数据版本" />
    <TaskPageHeader title="快照差异" description="差异结果用于判断治理影响和发布风险，不会自动改变任何快照状态。">
      <template #status>
        <span v-if="baseIsCurrentOfficial" class="snapshot-diff-page__official-tag">基准为当前正式版</span>
        <span v-else-if="comparedIsCurrentOfficial" class="snapshot-diff-page__official-tag">对比版本为当前正式版</span>
        <GitCompareArrows v-else :size="20" aria-hidden="true" />
      </template>
    </TaskPageHeader>
    <p v-if="currentOfficialError" class="snapshot-diff-page__identity-error" role="status">{{ currentOfficialError }}</p>
    <LoadingState v-if="loading" variant="skeleton" :rows="6" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="diff">
      <section class="diff-grid">
        <div><span>新增表</span><strong>{{ diff.addedTables?.length || 0 }}</strong></div>
        <div><span>删除表</span><strong>{{ diff.removedTables?.length || 0 }}</strong></div>
        <div><span>新增字段</span><strong>{{ diff.addedColumns?.length || 0 }}</strong></div>
        <div><span>删除字段</span><strong>{{ diff.removedColumns?.length || 0 }}</strong></div>
        <div><span>变更字段</span><strong>{{ diff.modifiedColumns?.length || 0 }}</strong></div>
      </section>
      <section class="diff-card">
        <h2>字段变化</h2>
        <el-table v-if="(diff.modifiedColumns?.length || diff.addedColumns?.length || diff.removedColumns?.length)" :data="[...(diff.addedColumns || []).map((item: any) => ({ ...item, change: '新增' })), ...(diff.removedColumns || []).map((item: any) => ({ ...item, change: '删除' })), ...(diff.modifiedColumns || []).map((item: any) => ({ ...item, change: '变更' }))]" size="small">
          <el-table-column prop="change" label="变化" width="90" />
          <el-table-column prop="tableName" label="表" />
          <el-table-column prop="columnName" label="字段" />
          <el-table-column prop="oldType" label="旧类型" />
          <el-table-column prop="newType" label="新类型" />
        </el-table>
        <EmptyState v-else message="两个版本没有字段差异" />
      </section>
    </template>
  </div>
</template>

<style scoped>
.snapshot-diff-page { display: grid; gap: 16px; }
.snapshot-diff-page__official-tag { display: inline-flex; align-items: center; min-height: 24px; padding: 3px 9px; border-radius: var(--do-radius-full); color: var(--do-primary-strong); background: var(--do-primary-soft); font-size: 12px; font-weight: 600; white-space: nowrap; }
.snapshot-diff-page__identity-error { margin: -8px 0 0; color: var(--do-warning); font-size: 12px; }
.diff-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
.diff-grid > div, .diff-card { padding: 16px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); }
.diff-grid span { display: block; color: var(--do-muted); font-size: 12px; }
.diff-grid strong { display: block; margin-top: 6px; color: var(--do-ink); font-size: 24px; }
.diff-card h2 { margin: 0 0 14px; color: var(--do-ink); font-size: 16px; }
</style>
