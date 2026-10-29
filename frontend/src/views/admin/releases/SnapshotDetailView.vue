<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from 'vue'
import { useAuthStore } from '../../../stores/auth'
import { ArrowLeft } from 'lucide-vue-next'
import { useRouter, useRoute, type RouteLocationRaw } from 'vue-router'
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
const canViewGovernance = computed(() => {
  const sourceId = detail.value?.snapshot.datasourceId
  return sourceId !== undefined && iamS1.canOnDatasource('governance:view', sourceId)
})
const canViewReleaseGate = computed(() => {
  const sourceId = detail.value?.snapshot.datasourceId
  return iamS1.loaded && sourceId !== undefined
    && iamS1.canOnDatasource('metadata:release:view', sourceId)
})
const gateIssueTarget = computed<RouteLocationRaw | undefined>(() => {
  const snapshot = detail.value?.snapshot
  if (!snapshot) return undefined
  return {
    path: '/admin/governance/issues',
    query: { datasourceId: String(snapshot.datasourceId), snapshotId: String(snapshot.id) },
  }
})

function retryGate() {
  const snapshot = detail.value?.snapshot
  if (snapshot && canViewReleaseGate.value) void loadGate(snapshot.id)
}

const returnToRelease = computed<RouteLocationRaw>(() => {
  const sourceId = detail.value?.snapshot.datasourceId ?? routeDatasourceId.value
  return {
    path: '/admin/releases',
    query: buildContextQuery('datasource', { datasourceId: sourceId }),
  }
})

function loadRouteSnapshot() {
  clear(); clearGate()
  if (routeDatasourceId.value != null && (!Number.isSafeInteger(routeDatasourceId.value) || routeDatasourceId.value <= 0)) { error.value='指定数据源标识无效，未改用其他范围。'; return }
  if (!canReadDetail.value) { error.value=iamS1.errorMessage || '需要当前数据源的 metadata:view 才能读取快照结构。'; return }
  void load({ snapshotId: snapshotId.value, datasourceId: routeDatasourceId.value })
}

function openGovernance() {
  const snapshot = detail.value?.snapshot
  if (!snapshot || !canViewGovernance.value) return
  router.push({
    path: '/admin/governance',
    query: {
      datasourceId: String(snapshot.datasourceId),
      snapshotId: String(snapshot.id),
      returnTo: '/admin/releases',
    },
  })
}

watch(() => [snapshotId.value, routeDatasourceId.value, auth.token, iamS1.snapshot, iamS1.loaded, iamS1.errorMessage], loadRouteSnapshot, { immediate: true, flush: 'sync' })
watch(
  () => [detail.value?.snapshot.id, detail.value?.snapshot.datasourceId, canViewReleaseGate.value] as const,
  ([loadedSnapshotId]) => {
    if (!loadedSnapshotId || !canViewReleaseGate.value) {
      clearGate()
      return
    }
    void loadGate(loadedSnapshotId)
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  clear()
  clearGate()
})
</script>

<template>
  <div class="admin-page snapshot-detail-page">
    <p class="snapshot-detail__eyebrow">数据资产 / 精确版本</p>
    <TaskPageHeader title="快照详情" description="检查采集结果和治理状态；审核通过与正式发布是两个独立动作。">
      <template #title-prefix>
        <RouterLink class="snapshot-detail__back" :to="returnToRelease" aria-label="返回元数据版本">
          <ArrowLeft :size="15" aria-hidden="true" />
          <span>返回元数据版本</span>
        </RouterLink>
      </template>
      <template #title-suffix>
        <span v-if="detail" class="snapshot-detail__version">v{{ detail.snapshot.snapshotVersion }}</span>
      </template>
      <template #status><BusinessStatusBadge v-if="detail" :status="detail.snapshot.status" :label="snapshotStatusLabel(detail.snapshot.status)" /></template>
      <template #actions><el-button v-if="canViewGovernance" @click="openGovernance">查看治理情况</el-button></template>
    </TaskPageHeader>

    <ReleaseGatePanel
      v-if="canViewReleaseGate"
      :gate="gate"
      :loading="gateLoading"
      :error="gateError"
      :issue-target="gateIssueTarget"
      @retry="retryGate"
    />

    <SnapshotDetailPanel :detail="detail" :loading="loading" :error="error" @retry="loadRouteSnapshot" />
  </div>
</template>

<style scoped>
.snapshot-detail__eyebrow {margin:0;color:var(--do-primary-strong);font-size:12px;font-weight:650;}
.snapshot-detail-page { display: grid; gap: 14px; min-width: 0; }
.snapshot-detail-page :deep(.task-page-header) { align-items: center; margin-bottom: 0; }
.snapshot-detail-page :deep(.task-page-header h1) { font-size: clamp(24px, 2.1vw, 28px); line-height: 1.2; letter-spacing: -.02em; }
.snapshot-detail__back { display: inline-flex; align-items: center; gap: 4px; padding: 4px 6px 4px 0; border-radius: var(--do-radius-sm); color: var(--do-muted); font-size: 12px; font-weight: 600; white-space: nowrap; }
.snapshot-detail__back:hover { color: var(--do-primary-strong); }
.snapshot-detail__back:focus-visible { outline: 2px solid color-mix(in srgb, var(--do-primary) 42%, transparent); outline-offset: 2px; }
.snapshot-detail__version { display: inline-flex; align-items: center; min-height: 24px; padding: 2px 8px; border-radius: var(--do-radius-full); color: var(--do-primary-strong); background: var(--do-primary-soft); font-size: 12px; font-weight: 700; white-space: nowrap; }
</style>
