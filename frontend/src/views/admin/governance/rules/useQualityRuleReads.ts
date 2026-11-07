import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  listQualityRules,
  listQualityRuleTemplates,
  getQualityRuleSummary,
  type QualityRule,
  type QualityRuleTemplate,
  type QualityRuleSummary,
} from '../../../../api/admin/governance'
import type { useQualityRuleScope } from './useQualityRuleScope'

type RuleScopeFilter = 'ALL' | QualityRule['scopeType']

/** 列表筛选、检查项目录和快照规划各有独立代次，不把配置读取当成执行结果。 */
export function useQualityRuleReads(scope: ReturnType<typeof useQualityRuleScope>) {
  const { context, scopeMode, readKey, planKey, canViewRules, canViewCheckItems, planScopeValid } = scope
  let disposed = false
  const applicability = ref<QualityRuleSummary | null>(null)
  const applicabilityLoading = ref(false)
  const applicabilityError = ref('')
  let applicabilityRequestId = 0
  const rules = ref<QualityRule[]>([])
  const rulesPage = ref(1)
  const rulesPageSize = 10
  const rulesTotal = ref(0)
  const templates = ref<QualityRuleTemplate[]>([])
  const rulesLoading = ref(false)
  const templatesLoading = ref(false)
  const templatesError = ref('')
  const rulesError = ref('')
  const selectedRuleScope = ref<RuleScopeFilter>('ALL')
  const templateFilter = ref('')
  const dimensionFilter = ref('')
  const ruleNameFilter = ref('')
  const enabledFilter = ref<'ALL' | 'ENABLED' | 'DISABLED'>('ALL')
  const showDeleted = ref(false)
  const advancedFiltersOpen = ref(false)
  let rulesRequestId = 0
  let templateRequestId = 0
  const filteredRules = computed(() => rules.value)
  const advancedFilterCount = computed(
    () =>
      [
        scopeMode.value === 'DATASOURCE' && selectedRuleScope.value !== 'ALL',
        dimensionFilter.value !== '',
        showDeleted.value,
      ].filter(Boolean).length,
  )
  const hasRuleFilters = computed(() =>
    Boolean(
      ruleNameFilter.value.trim() ||
        templateFilter.value ||
        dimensionFilter.value ||
        (scopeMode.value === 'DATASOURCE' && selectedRuleScope.value !== 'ALL') ||
        enabledFilter.value !== 'ALL' ||
        showDeleted.value,
    ),
  )

  function clearAllRuleFilters() {
    clearAdvancedFilters()
    templateFilter.value = ''
    ruleNameFilter.value = ''
    enabledFilter.value = 'ALL'
  }
  function clearAdvancedFilters() {
    selectedRuleScope.value = scopeMode.value === 'GLOBAL' ? 'GLOBAL' : 'ALL'
    dimensionFilter.value = ''
    showDeleted.value = false
  }

  function queryParams() {
    return {
      datasourceId: scopeMode.value === 'DATASOURCE' ? context.datasourceId : undefined,
      scopeType:
        scopeMode.value === 'GLOBAL'
          ? 'GLOBAL'
          : selectedRuleScope.value === 'ALL'
            ? undefined
            : selectedRuleScope.value,
      templateCode: templateFilter.value || undefined,
      ruleName: ruleNameFilter.value.trim() || undefined,
      dimension: dimensionFilter.value || undefined,
      enabled: enabledFilter.value === 'ALL' ? undefined : enabledFilter.value === 'ENABLED',
      deleted: showDeleted.value,
      page: rulesPage.value,
      pageSize: rulesPageSize,
    }
  }

  function changeRulesPage(page: number) {
    if (page === rulesPage.value) return
    rulesPage.value = page
    void loadRules()
  }

  async function loadRules() {
    const request = ++rulesRequestId,
      key = readKey.value
    rulesError.value = ''
    if (!canViewRules.value) {
      rules.value = []
      return
    }
    if (scopeMode.value === 'DATASOURCE' && !context.datasourceId) {
      rules.value = []
      return
    }
    rulesLoading.value = true
    try {
      const response = await listQualityRules(queryParams())
      if (disposed || request !== rulesRequestId || key !== readKey.value) return
      // 列表合同只有服务端 Page：缺记录或权威总数都属于失败，不猜旧数组或空成功。
      const page = response.data
      if (
        !page ||
        !Array.isArray(page.records) ||
        !Number.isSafeInteger(page.total) ||
        page.total < page.records.length
      ) {
        throw new Error('规则分页回执不完整')
      }
      rules.value = page.records
      rulesTotal.value = page.total
      // 删除/筛选后当前页可能已越界，退回最后一页而不是显示空页。
      if (!rules.value.length && rulesTotal.value > 0 && rulesPage.value > 1) {
        rulesPage.value = Math.max(1, Math.ceil(rulesTotal.value / rulesPageSize))
        await loadRules()
      }
    } catch {
      if (!disposed && request === rulesRequestId && key === readKey.value)
        rulesError.value = '质量规则读取失败，请重试。'
    } finally {
      if (!disposed && request === rulesRequestId && key === readKey.value) rulesLoading.value = false
    }
  }

  async function loadTemplates() {
    const request = ++templateRequestId,
      key = readKey.value
    templatesError.value = ''
    if (!canViewCheckItems.value) {
      templates.value = []
      return
    }
    templatesLoading.value = true
    try {
      const response = await listQualityRuleTemplates()
      if (disposed || request !== templateRequestId || key !== readKey.value) return
      templates.value = response.data ?? []
    } catch {
      if (!disposed && request === templateRequestId && key === readKey.value)
        templatesError.value = '支持的检查项读取失败，请重试。'
    } finally {
      if (!disposed && request === templateRequestId && key === readKey.value) templatesLoading.value = false
    }
  }

  async function loadApplicability() {
    const request = ++applicabilityRequestId,
      key = planKey.value
    const datasourceId = context.datasourceId
    const snapshotId = context.snapshotId
    applicability.value = null
    applicabilityError.value = ''
    applicabilityLoading.value = false
    if (
      scopeMode.value !== 'DATASOURCE' ||
      !datasourceId ||
      !snapshotId ||
      !planScopeValid.value ||
      !canViewRules.value ||
      context.routeSyncing ||
      context.routeSyncError
    )
      return
    applicabilityLoading.value = true
    const current = () =>
      !disposed &&
      key === planKey.value &&
      request === applicabilityRequestId &&
      datasourceId === context.datasourceId &&
      snapshotId === context.snapshotId &&
      scopeMode.value === 'DATASOURCE'
    try {
      const response = await getQualityRuleSummary(snapshotId)
      if (!current()) return
      if (
        (response.data?.snapshotId && response.data.snapshotId !== snapshotId) ||
        (response.data?.datasourceId && response.data.datasourceId !== datasourceId)
      )
        throw new Error('规划范围不一致')
      applicability.value = response.data ?? null
    } catch {
      if (current()) applicabilityError.value = '当前快照的规则适用性读取失败，不能判断生效情况。'
    } finally {
      if (current()) applicabilityLoading.value = false
    }
  }

  watch(
    [
      scopeMode,
      selectedRuleScope,
      templateFilter,
      dimensionFilter,
      ruleNameFilter,
      enabledFilter,
      showDeleted,
    ],
    () => {
      rulesPage.value = 1
      void loadRules()
    },
  )

  // 换身份或配置范围立刻清空旧读取；失败保留明确错误，不沿用另一范围的统计。
  function invalidateReads() {
    rulesRequestId++
    templateRequestId++
    applicabilityRequestId++
    rules.value = []
    rulesTotal.value = 0
    rulesLoading.value = false
    rulesError.value = ''
    templates.value = []
    templatesLoading.value = false
    templatesError.value = ''
    applicability.value = null
    applicabilityLoading.value = false
    applicabilityError.value = ''
  }
  onBeforeUnmount(() => {
    disposed = true
    invalidateReads()
  })
  return {
    applicability,
    applicabilityLoading,
    applicabilityError,
    rules,
    rulesPage,
    rulesPageSize,
    rulesTotal,
    templates,
    rulesLoading,
    templatesLoading,
    templatesError,
    rulesError,
    selectedRuleScope,
    templateFilter,
    dimensionFilter,
    ruleNameFilter,
    enabledFilter,
    showDeleted,
    advancedFiltersOpen,
    filteredRules,
    advancedFilterCount,
    hasRuleFilters,
    clearAllRuleFilters,
    clearAdvancedFilters,
    changeRulesPage,
    loadRules,
    loadTemplates,
    loadApplicability,
    invalidateReads,
  }
}
