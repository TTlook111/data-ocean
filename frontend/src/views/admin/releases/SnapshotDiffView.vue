<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { GitCompareArrows } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { compareSnapshots } from '../../../api/admin/versioning'
import { getSnapshotDetail, type SnapshotDetail } from '../../../api/admin/metadata'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import ObjectContextSummary from '../../../components/admin/ObjectContextSummary.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
// 差异 URL 协议：snapshotId 是旧快照，compareId 是新快照。
const oldSnapshotId = computed(() => Number(route.params.snapshotId))
const newSnapshotId = computed(() => Number(route.params.compareId))
const oldSnapshot = ref<SnapshotDetail | null>(null)
const newSnapshot = ref<SnapshotDetail | null>(null)
const diff = ref<any>(null)
const loading = ref(true)
const error = ref('')
let requestId = 0

async function load() {
  const currentRequest = ++requestId
  loading.value = true
  error.value = ''
  oldSnapshot.value = null
  newSnapshot.value = null
  diff.value = null
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
    oldSnapshot.value = oldResult.data
    newSnapshot.value = newResult.data
    diff.value = diffResult.data
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
    <ObjectContextSummary v-if="oldSnapshot" :title="'v' + oldSnapshot.snapshot.snapshotVersion + ' → v' + (newSnapshot?.snapshot.snapshotVersion || newSnapshotId)" description="旧快照对比新快照" back-to="/admin/releases" source-label="数据资产 / 版本发布" />
    <TaskPageHeader title="快照差异" description="差异结果用于判断治理影响和发布风险，不会自动改变任何快照状态。">
      <template #status><GitCompareArrows :size="20" /></template>
    </TaskPageHeader>
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
.diff-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
.diff-grid > div, .diff-card { padding: 16px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); }
.diff-grid span { display: block; color: var(--do-muted); font-size: 12px; }
.diff-grid strong { display: block; margin-top: 6px; color: var(--do-ink); font-size: 24px; }
.diff-card h2 { margin: 0 0 14px; color: var(--do-ink); font-size: 16px; }
</style>
