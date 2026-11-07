<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from 'vue'
import { useAuthStore } from '../../../stores/auth'
import { ArrowLeft } from 'lucide-vue-next'
import { useRouter, useRoute } from 'vue-router'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import SnapshotDetailPanel from '../../../components/admin/SnapshotDetailPanel.vue'
import ReleaseGatePanel from '../../../components/admin/ReleaseGatePanel.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import { useIamS1Store } from '../../../stores/iamS1'
import { useSnapshotDetail } from '../../../composables/useSnapshotDetail'
import { useReleaseGate } from '../../../composables/useReleaseGate'
import { buildContextQuery } from '../../../utils/adminNavigation'
import { snapshotStatusLabel } from '../../../utils/enumLabels'

const route = useRoute()
const router = useRouter()
const iamS1 = useIamS1Store(), auth = useAuthStore()
const snapshotId = computed(() => Number(route.params.snapshotId))
const routeDatasourceId = computed(() => route.query.datasourceId == null ? undefined : typeof route.query.datasourceId === 'string' ? Number(route.query.datasourceId) : NaN)
const canReadDetail = computed(() => iamS1.loaded && !iamS1.errorMessage && (routeDatasourceId.value != null ? iamS1.canOnDatasource('metadata:view', routeDatasourceId.value) : iamS1.systemAdmin || iamS1.datasourcesWithFunction('metadata:view').length > 0))
const { detail, loading, error, load, clear } = useSnapshotDetail()
const {
  gate,
  loading: gateLoading,
  error: gateError,
  load: loadGate,
  clear: clearGate,
} = useReleaseGate()
// 版本门禁按版本查看权读取，显式来源与返回身份必须相符；表字段结构独立判权。
const source = computed(() => detail.value?.snapshot.datasourceId ?? routeDatasourceId.value)
const verifiedSource = computed(() => detail.value?.snapshot.datasourceId ?? gate.value?.datasourceId)
const canViewGovernance = computed(() => !!verifiedSource.value && iamS1.canOnDatasource('governance:view', verifiedSource.value))
const canViewReleaseGate = computed(() => iamS1.loaded && !iamS1.errorMessage && (source.value === undefined ? iamS1.systemAdmin || iamS1.datasourcesWithFunction('metadata:release:view').length > 0 : Number.isSafeInteger(source.value) && source.value > 0 && iamS1.canOnDatasource('metadata:release:view', source.value)))
const canViewGateIssues = computed(() => !!gate.value?.datasourceId && iamS1.canOnDatasource('governance:issue:view', gate.value.datasourceId))
async function retryGate() {
  if (!canViewReleaseGate.value) return
  await loadGate(snapshotId.value, source.value, id => iamS1.canOnDatasource('metadata:release:view', id))
}

const returnToRelease = computed(() => {
  const sourceId = detail.value?.snapshot.datasourceId ?? routeDatasourceId.value
  if(sourceId && iamS1.canOnDatasource('metadata:release:view',sourceId)) return {path:'/admin/releases',query:buildContextQuery('datasource',{datasourceId:sourceId})}
  if(sourceId && iamS1.canOnDatasource('metadata:view',sourceId)) return {path:'/admin/assets',query:{datasourceId:String(sourceId),...(detail.value ? {snapshotId:String(detail.value.snapshot.id)} : {}),view:'tables'}}
  return {path:'/admin'}
})

function loadRouteSnapshot() {
  clear(); clearGate()
  if (routeDatasourceId.value != null && (!Number.isSafeInteger(routeDatasourceId.value) || routeDatasourceId.value <= 0)) { error.value='指定数据源标识无效，未改用其他范围。'; return }
  if (!canReadDetail.value) { error.value=iamS1.errorMessage || '此账号未开放表字段结构查看。'; return }
  void load({ snapshotId: snapshotId.value, datasourceId: routeDatasourceId.value })
}

function openGovernance() {
  const sourceId = verifiedSource.value
  if (!sourceId || !canViewGovernance.value) return
  router.push({
    path: '/admin/governance/checks',
    query: {
      datasourceId: String(sourceId),
      snapshotId: String(snapshotId.value),
      returnTo: '/admin/releases',
    },
  })
}

watch(() => [snapshotId.value, routeDatasourceId.value, auth.token, iamS1.snapshot, iamS1.loaded, iamS1.errorMessage], loadRouteSnapshot, { immediate: true, flush: 'sync' })
watch(() => [snapshotId.value, source.value, canViewReleaseGate.value, auth.token, iamS1.snapshot], () => {
  if (canViewReleaseGate.value) retryGate()
  else clearGate()
}, { immediate: true, flush:'post' })
onBeforeUnmount(() => {
  clear()
  clearGate()
})
</script>

<template>
  <div class="admin-page snapshot-detail-page">
    <TaskPageHeader title="快照详情" description="检查采集结果和治理状态；审核通过与正式发布是两个独立动作。">
      <template #title-prefix>
        <RouterLink class="snapshot-detail__back" :to="returnToRelease" :aria-label="returnToRelease.path === '/admin/releases' ? '返回元数据版本' : '返回有权数据工作区'">
          <ArrowLeft :size="15" aria-hidden="true" />
          <span>{{ returnToRelease.path === '/admin/releases' ? '返回元数据版本' : '返回有权数据工作区' }}</span>
        </RouterLink>
      </template>
      <template #title-suffix>
        <span v-if="detail" class="snapshot-detail__version">v{{ detail.snapshot.snapshotVersion }}</span><span v-else-if="gate" class="snapshot-detail__version">数据源 #{{ gate.datasourceId }} · 快照 #{{ gate.snapshotId }}</span>
      </template>
      <template #status><BusinessStatusBadge v-if="detail || gate" :status="detail?.snapshot.status || gate?.snapshotStatus" :label="snapshotStatusLabel(detail?.snapshot.status || gate?.snapshotStatus)" /></template>
      <template #actions><el-button v-if="canViewGovernance" @click="openGovernance">查看质量检查</el-button></template>
    </TaskPageHeader>

    <ReleaseGatePanel
      v-if="canViewReleaseGate"
      :gate="gate"
      :loading="gateLoading"
      :error="gateError"
      :can-view-issues="canViewGateIssues"
      :can-view-checks="canViewGovernance"
      @retry="retryGate"
    />

    <p v-if="!canReadDetail && canViewReleaseGate" role="status">此账号未开放表字段结构查看。</p>
    <SnapshotDetailPanel v-else :detail="detail" :loading="loading" :error="error" @retry="loadRouteSnapshot" />
  </div>
</template>

<style scoped>
.snapshot-detail-page { display: grid; gap: 14px; min-width: 0; }
.snapshot-detail-page :deep(.task-page-header) { align-items: center; margin-bottom: 0; }
.snapshot-detail-page :deep(.task-page-header h1) { font-size: clamp(24px, 2.1vw, 28px); line-height: 1.2; letter-spacing: -.02em; }
.snapshot-detail__back { display: inline-flex; align-items: center; gap: 4px; padding: 4px 6px 4px 0; border-radius: var(--do-radius-sm); color: var(--do-muted); font-size: 12px; font-weight: 600; white-space: nowrap; }
.snapshot-detail__back:hover { color: var(--do-primary-strong); }
.snapshot-detail__back:focus-visible { outline: 2px solid color-mix(in srgb, var(--do-primary) 42%, transparent); outline-offset: 2px; }
.snapshot-detail__version { display: inline-flex; align-items: center; min-height: 24px; padding: 2px 8px; border-radius: var(--do-radius-full); color: var(--do-primary-strong); background: var(--do-primary-soft); font-size: 12px; font-weight: 700; white-space: nowrap; }
</style>
