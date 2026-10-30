import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useIamS1Store } from '../stores/iamS1'
import { useAdminContextStore } from '../stores/adminContext'

/** Public, verified scope only. This does not grant metadata or recover a different snapshot. */
export function useGovernanceScope() {
  const context = useAdminContextStore(), iam = useIamS1Store(), auth = useAuthStore(), route = useRoute()
  const generation = ref(0)
  let disposed = false
  const scopeLoading = computed(() => (!iam.loaded && !iam.errorMessage) || context.loading || context.routeSyncing)
  const datasourceIssue = computed(() => {
    if (iam.errorMessage) return `权限摘要读取失败：${iam.errorMessage}`
    const raw = route.query.datasourceId
    if (raw != null) {
      const id = typeof raw === 'string' ? Number(raw) : NaN
      if (!Number.isSafeInteger(id) || id <= 0) return '指定数据源标识无效，未改用其他范围。'
      if (context.datasourceId && id !== context.datasourceId) return '指定数据源范围尚未核验，未改用偏好范围。'
    }
    return context.datasourceId ? '' : '请在公共范围栏选择数据源。'
  })
  const sourceReady = computed(() => iam.loaded && !scopeLoading.value && !datasourceIssue.value && !!context.datasourceId)
  const scopeIssue = computed(() => {
    if (datasourceIssue.value) return datasourceIssue.value
    const raw = route.query.snapshotId
    if (raw != null) {
      const id = typeof raw === 'string' ? Number(raw) : NaN
      if (!Number.isSafeInteger(id) || id <= 0) return '指定快照标识无效，未改用其他范围。'
      if (context.snapshotId && id !== context.snapshotId) return '指定快照范围尚未核验，未改用偏好范围。'
    }
    if (context.routeSyncError) return context.routeSyncError
    if (!context.snapshotId) {
      if (iam.loaded && !iam.canOnDatasource('metadata:view', context.datasourceId)) return '当前账号没有 metadata:view，公共范围无法读取快照；字段治理查看权不自动授予元数据查看权。请由管理员核对角色范围，此页不会补权或改用其他快照。'
      return '请在公共范围栏明确选择快照。'
    }
    return ''
  })
  const ready = computed(() => iam.loaded && !scopeLoading.value && !scopeIssue.value && !!context.datasourceId && !!context.snapshotId)
  function canSource(code: string) { return sourceReady.value && iam.canOnDatasource(code, context.datasourceId) }
  function can(code: string) { return ready.value && iam.canOnDatasource(code, context.datasourceId) }
  function capture() { return generation.value }
  function isCurrent(ticket: number) { return !disposed && ticket === generation.value && ready.value }
  watch(() => [auth.token, context.datasourceId, context.snapshotId, context.loading, context.routeSyncing, context.routeSyncError,
    route.query.datasourceId, route.query.snapshotId, iam.snapshot, iam.loaded, iam.errorMessage], () => { generation.value++ }, { flush: 'sync' })
  onBeforeUnmount(() => { disposed = true })
  return { context, iam, auth, route, scopeLoading, scopeIssue, datasourceIssue, sourceReady, canSource, ready, can, capture, isCurrent, generation }
}
