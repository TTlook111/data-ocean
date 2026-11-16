import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useGovernanceScope } from './useGovernanceScope'
import { getQualityContext, type QualityContext } from '../api/admin/governance'
import { positiveId } from '../utils/lineageContext'

/** 无元数据目录权时，显式深链由主业务接口证明归属；不把稀疏上下文注入公共元数据缓存。 */
export function useQualityBusinessScope() {
  const base = useGovernanceScope()
  const verified = ref<QualityContext | null>(null),
    pending = ref(false),
    error = ref('')
  let request = 0,
    disposed = false
  const source = computed(() => positiveId(base.route.query.datasourceId))
  const snapshot = computed(() => positiveId(base.route.query.snapshotId))
  const eligible = computed(() =>
    Boolean(
      source.value &&
        snapshot.value &&
        base.iam.loaded &&
        !base.iam.errorMessage &&
        !base.iam.canOnDatasource('metadata:view', source.value) &&
        ['governance:view', 'governance:issue:view', 'governance:check'].some((code) =>
          base.iam.canOnDatasource(code, source.value),
        ),
    ),
  )
  watch(
    () => [
      base.auth.token,
      base.iam.snapshot,
      base.iam.loaded,
      base.iam.errorMessage,
      source.value,
      snapshot.value,
      eligible.value,
    ],
    async () => {
      const ticket = ++request
      verified.value = null
      error.value = ''
      pending.value = false
      if (!eligible.value) return
      pending.value = true
      try {
        const result = await getQualityContext(snapshot.value!)
        if (disposed || request !== ticket) return
        if (result.data?.id !== snapshot.value || result.data.datasourceId !== source.value)
          throw new Error('质量工作区范围与指定来源不一致')
        verified.value = result.data
      } catch {
        if (!disposed && request === ticket) error.value = '质量工作区范围核验失败，未改用其他快照。'
      } finally {
        if (!disposed && request === ticket) pending.value = false
      }
    },
    { immediate: true, flush: 'sync' },
  )
  const primaryVerified = computed(() => eligible.value && !!verified.value)
  const ready = computed(() => (eligible.value ? primaryVerified.value : base.ready.value))
  const scopeLoading = computed(() => (eligible.value ? pending.value : base.scopeLoading.value))
  const scopeIssue = computed(() => (eligible.value ? error.value : base.scopeIssue.value))
  const datasourceId = computed(() =>
    primaryVerified.value ? verified.value!.datasourceId : base.context.datasourceId,
  )
  const snapshotId = computed(() => (primaryVerified.value ? verified.value!.id : base.context.snapshotId))
  const currentSnapshot = computed(() =>
    primaryVerified.value ? verified.value! : base.context.currentSnapshot,
  )
  const generation = ref(0)
  watch(
    () => [base.generation.value, ready.value, datasourceId.value, snapshotId.value, scopeLoading.value],
    () => {
      generation.value++
    },
    { flush: 'sync' },
  )
  onBeforeUnmount(() => {
    disposed = true
    request++
  })
  return {
    ...base,
    ready,
    scopeLoading,
    scopeIssue,
    datasourceId,
    snapshotId,
    currentSnapshot,
    primaryVerified,
    generation,
    capture: () => generation.value,
    isCurrent: (ticket: number) => !disposed && ready.value && ticket === generation.value,
  }
}
