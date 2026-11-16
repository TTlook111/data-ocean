<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { GitCompareArrows } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAuthStore } from '../../../stores/auth'
import { compareSnapshots, getPublishedSnapshot, type VersionDiff } from '../../../api/admin/versioning'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import { buildContextQuery } from '../../../utils/adminNavigation'

const route = useRoute(), iam=useIamS1Store(), auth=useAuthStore()
// 差异 URL 协议：snapshotId 是旧快照，compareId 是新快照。
const oldSnapshotId = computed(() => Number(route.params.snapshotId))
const newSnapshotId = computed(() => Number(route.params.compareId))
const diff = ref<VersionDiff | null>(null)
const currentOfficialSnapshotId = ref<number | undefined>()
const currentOfficialError = ref('')
const loading = ref(true)
const error = ref('')
let requestId = 0

const baseIsCurrentOfficial = computed(() => currentOfficialSnapshotId.value !== undefined && currentOfficialSnapshotId.value === diff.value?.oldSnapshotId)
const comparedIsCurrentOfficial = computed(() => currentOfficialSnapshotId.value !== undefined && currentOfficialSnapshotId.value === diff.value?.newSnapshotId)
const returnToRelease = computed(() => ({
  path: '/admin/releases',
  query: buildContextQuery('datasource', { datasourceId: diff.value?.datasourceId }),
}))

async function load() {
  const currentRequest = ++requestId
  loading.value = true
  error.value = ''
  diff.value = null
  currentOfficialSnapshotId.value = undefined
  currentOfficialError.value = ''
  try {
    const expectedSource=route.query.datasourceId == null ? undefined : typeof route.query.datasourceId==='string' ? Number(route.query.datasourceId) : NaN
    if (![oldSnapshotId.value,newSnapshotId.value].every(id=>Number.isSafeInteger(id) && id>0) || oldSnapshotId.value===newSnapshotId.value) throw new Error('需要两个不同的有效快照标识')
    if (expectedSource!=null && (!Number.isSafeInteger(expectedSource) || expectedSource<=0)) throw new Error('指定数据源标识无效，未改用其他范围')
    if (!iam.loaded || iam.errorMessage) throw new Error(iam.errorMessage || '正在读取权限摘要')
    // 比较是版本主读取，服务端同时裁决两个快照。页面只采用方向、来源和明细均完整的回执。
    const sources = expectedSource === undefined ? iam.datasourcesWithFunction('metadata:release:view') : [expectedSource]
    if (!iam.systemAdmin && !sources.some(id => iam.canOnDatasource('metadata:release:view',id))) throw new Error('当前账号无权查看此来源的版本差异')
    const response = await compareSnapshots(oldSnapshotId.value, newSnapshotId.value)
    if (disposedOrStale(currentRequest)) return
    const result = response.data
    if (!result || result.oldSnapshotId !== oldSnapshotId.value || result.newSnapshotId !== newSnapshotId.value || !Number.isSafeInteger(result.oldSnapshotVersion) || !Number.isSafeInteger(result.newSnapshotVersion)) throw new Error('返回的比较方向与指定版本不一致')
    if (!Number.isSafeInteger(result.datasourceId) || (expectedSource !== undefined && result.datasourceId !== expectedSource) || !iam.canOnDatasource('metadata:release:view',result.datasourceId)) throw new Error('差异回执不属于当前授权来源')
    if (![result.addedTables,result.removedTables,result.addedColumns,result.removedColumns,result.modifiedColumns].every(Array.isArray)) throw new Error('版本差异明细不完整，请重新读取')
    diff.value = result
    loading.value = false
    let officialId: number | undefined
    let officialLookupError = ''
    try {
      const officialResult = await getPublishedSnapshot(result.datasourceId)
      if (disposedOrStale(currentRequest)) return
      if (officialResult.data && officialResult.data.datasourceId !== result.datasourceId) throw new Error('正式版回执来源不符')
      officialId = officialResult.data?.snapshotId
    } catch {
      officialLookupError = '当前正式版身份读取失败，无法标记生效版本。'
    }
    if (disposedOrStale(currentRequest)) return
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

watch(() => [route.params.snapshotId, route.params.compareId, route.query.datasourceId,auth.token,iam.snapshot,iam.loaded,iam.errorMessage], load, {immediate:true,flush:'sync'})
onBeforeUnmount(() => { requestId++ })
</script>

<template>
  <div class="admin-page snapshot-diff-page">
    <TaskPageHeader title="快照差异" description="差异结果用于判断治理影响和发布风险，不会自动改变任何快照状态。">
      <template #title-prefix><RouterLink :to="returnToRelease" class="snapshot-diff-page__back">返回版本列表</RouterLink></template>
      <template #title-suffix><span v-if="diff" class="snapshot-diff-page__direction">基准 v{{ diff.oldSnapshotVersion }} → 对比 v{{ diff.newSnapshotVersion }}</span></template>
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
      <section v-if="diff.addedTables?.length || diff.removedTables?.length" class="diff-card"><h2>表变化</h2><p v-for="name in diff.addedTables" :key="'add'+name">新增表 · {{ name }}</p><p v-for="name in diff.removedTables" :key="'remove'+name">删除表 · {{ name }}</p></section>
      <section class="diff-card">
        <h2>字段变化</h2>
        <el-table v-if="(diff.modifiedColumns?.length || diff.addedColumns?.length || diff.removedColumns?.length)" :data="[...(diff.addedColumns || []).map((item) => ({ ...item, change: '新增' })), ...(diff.removedColumns || []).map((item) => ({ ...item, change: '删除' })), ...(diff.modifiedColumns || []).map((item) => ({ ...item, change: '变更' }))]" size="small">
          <el-table-column prop="change" label="变化" width="90" />
          <el-table-column prop="tableName" label="表" />
          <el-table-column prop="columnName" label="字段" />
          <el-table-column prop="oldType" label="旧类型" />
          <el-table-column prop="newType" label="新类型" />
          <el-table-column prop="oldComment" label="旧注释" min-width="180" />
          <el-table-column prop="newComment" label="新注释" min-width="180" />
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
/* 方向和计数属于同一次比较，使用连续的统计面板便于扫读。 */
.snapshot-diff-page__back { color:var(--do-primary-strong); font-size:12px; }
.snapshot-diff-page__direction { color:var(--do-muted); font-size:13px; }
.diff-grid,.diff-card { border:1px solid var(--do-line); border-radius:var(--do-radius-lg); background:var(--do-surface); }
.diff-grid { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); overflow:hidden; }
.diff-grid > div,.diff-card { padding:16px; }
.diff-grid > div + div { border-inline-start:1px solid var(--do-line); }
.diff-grid span { display: block; color: var(--do-muted); font-size: 12px; }
.diff-grid strong { display: block; margin-top: 6px; color: var(--do-ink); font-size: 24px; }
.diff-card h2 { margin: 0 0 14px; color: var(--do-ink); font-size: 16px; }
@media(max-width:760px) { .diff-grid {grid-template-columns:repeat(2,minmax(0,1fr));}.diff-card {min-width:0;overflow:auto;} }
</style>
