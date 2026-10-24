<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { ChevronDown, Copy, ListChecks, Plus, RefreshCw, Trash2 } from 'lucide-vue-next'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import QualityCheckItemCatalog from '../../../components/admin/QualityCheckItemCatalog.vue'
import RuleApplicabilitySummary from '../../../components/admin/RuleApplicabilitySummary.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import {
  copyQualityRule,
  createQualityRule,
  deleteQualityRule,
  editQualityRule,
  listQualityRuleTemplates,
  listQualityRules,
  restoreQualityRule,
  updateRuleEnabled,
  getQualityRuleSummary,
  type QualityRuleSummary,
  getQualityRule,
  getQualityRuleHistory,
  getQualityRuleRevisions,
  type QualityRule,
  type QualityRulePayload,
  type QualityRuleTemplate,
  type QualityCheckBatchRule,
  type QualityRuleRevision,
} from '../../../api/admin/governance'
import { listSnapshots } from '../../../api/admin/metadata'
import { listSnapshotTables, listSnapshotTableColumns } from '../../../api/admin/governance'
import { qualityDimensionLabel, severityLabel } from '../../../utils/enumLabels'
import {
  RULE_LABELS,
  describeRuleCondition,
  describeRecordedRuleCondition,
  describeRuleImpact,
  describeRuleScope,
  ruleScopeLabel,
  ruleTargetTypeLabel,
  sortRuleScopes,
  ruleEvidenceLimit,
  ruleExecutionLabel,
  qualityBatchLabel,
  rulePlanStatusLabel,
  ruleBusinessGuidance,
  briefRuleCondition,
} from '../../../utils/qualityRulePresentation'
import { useRoute, useRouter } from 'vue-router'
import { buildContextQuery } from '../../../utils/adminNavigation'
import { useAdminScrollLock } from '../../../composables/useAdminScrollLock'

type RuleScope = 'GLOBAL' | 'DATASOURCE' | 'TABLE' | 'COLUMN'
type RuleScopeFilter = 'ALL' | RuleScope

const context = useAdminContextStore()
const iamS1 = useIamS1Store()
const router = useRouter()
const route = useRoute()
const pageReady = ref(false)
const applicability = ref<QualityRuleSummary | null>(null)
const applicabilityLoading = ref(false)
const applicabilityError = ref('')
let applicabilityRequestId = 0
const RULE_VIEW = 'governance:rule:view'
const RULE_MANAGE = 'governance:rule:manage'
const METADATA_VIEW = 'metadata:view'

const scopeMode = ref<'GLOBAL' | 'DATASOURCE'>('DATASOURCE')
const rules = ref<QualityRule[]>([])
const rulesPage = ref(1)
const rulesPageSize = 10
const rulesTotal = ref(0)
const templates = ref<QualityRuleTemplate[]>([])
const rulesLoading = ref(false)
const templatesLoading = ref(false)
const pageError = ref('')
const templatesError = ref('')
const rulesError = ref('')
const targetError = ref('')
const targetLoading = ref(false)
const targetTables = ref<Array<{ tableName: string; tableComment?: string }>>([])
const targetColumns = ref<Array<{ columnName: string; columnComment?: string; dataType?: string }>>([])
const targetSnapshotId = ref<number>()
const targetSnapshotVersion = ref<number>()
const selectedRuleScope = ref<RuleScopeFilter>('ALL')
const templateFilter = ref('')
const dimensionFilter = ref('')
const ruleNameFilter = ref('')
const enabledFilter = ref<'ALL' | 'ENABLED' | 'DISABLED'>('ALL')
const showDeleted = ref(false)
const advancedFiltersOpen = ref(false)
const editorOpen = ref(false)
const checkCatalogOpen = ref(false)
const coverageOpen = ref(false)
const editorCatalogOpen = ref(false)
const detailOpen = ref(false)
const editingRuleId = ref<number>()
const editingRule = ref<QualityRule | null>(null)
const detailRule = ref<QualityRule | null>(null)
const detailTab = ref<'description' | 'executions' | 'revisions'>('description')
const detailHistory = ref<QualityCheckBatchRule[]>([])
const detailRevisions = ref<QualityRuleRevision[]>([])
const detailLoading = ref(false)
const detailError = ref('')
const saveLoading = ref(false)
const rowLoading = reactive<Record<number, boolean>>({})
const form = reactive({
  ruleName: '',
  templateCode: '',
  description: '',
  scopeType: 'DATASOURCE' as RuleScope,
  datasourceId: undefined as number | undefined,
  targetTableName: '',
  targetColumnName: '',
  parameters: {} as Record<string, number | boolean>,
  severity: 'MEDIUM',
  deductionPoints: 3,
  enabled: true,
})
const formRef = ref<FormInstance>()
const parameterErrors = reactive<Record<string, string>>({})
const sortedAllowedScopes = computed(() => sortRuleScopes(currentTemplate.value?.allowedScopes || []))
const formRules = computed<FormRules>(() => ({
  ruleName: [
    { required: true, message: '请填写规则名称', trigger: 'blur' },
    { max: 200, message: '规则名称不能超过 200 个字符', trigger: 'blur' },
  ],
  templateCode: [{ required: true, message: '请选择检查项', trigger: 'change' }],
  scopeType: [{ required: true, message: '请选择适用范围', trigger: 'change' }],
  datasourceId: form.scopeType === 'GLOBAL'
    ? []
    : [{ required: true, message: '请先在顶部范围栏选择数据源', trigger: 'change' }],
  targetTableName: isTargetScoped.value
    ? [{ required: true, message: '请选择目标表', trigger: 'change' }]
    : [],
  targetColumnName: form.scopeType === 'COLUMN'
    ? [{ required: true, message: '请选择目标字段', trigger: 'change' }]
    : [],
  severity: [{ required: true, message: '请选择严重程度', trigger: 'change' }],
  deductionPoints: [
    { required: true, message: '请填写扣分', trigger: 'change' },
    { type: 'number', min: 0, max: 100, message: '扣分必须在 0 到 100 之间', trigger: 'change' },
  ],
}))
let rulesRequestId = 0
let templateRequestId = 0
let targetRequestId = 0
let detailRequestId = 0
let editorSessionId = 0
let saveRequestId = 0
let disposed = false

const canViewRules = computed(() => scopeMode.value === 'GLOBAL'
  ? iamS1.hasGlobal(RULE_VIEW)
  : Boolean(context.datasourceId && iamS1.canOnDatasource(RULE_VIEW, context.datasourceId)))
const canManageSelectedScope = computed(() => scopeMode.value === 'GLOBAL'
  ? iamS1.systemAdmin
  : Boolean(context.datasourceId && iamS1.canOnDatasource(RULE_MANAGE, context.datasourceId)))
const canViewCheckItems = computed(() => iamS1.hasGlobal(RULE_VIEW) || iamS1.datasourcesWithFunction(RULE_VIEW).length > 0)
const canConfigureRule = computed(() => canViewRules.value && canManageSelectedScope.value)
const currentTemplate = computed(() => templates.value.find((template) => template.code === form.templateCode))
const isTargetScoped = computed(() => ['TABLE', 'COLUMN'].includes(form.scopeType))
const editorTitle = computed(() => editingRuleId.value ? '编辑质量规则' : '新增质量规则')
const editorSummary = computed(() => {
  const template = currentTemplate.value
  return [
    { label: '规则编号', value: editingRule.value?.ruleCode || '新建规则' },
    { label: '配置版本', value: editingRule.value ? `v${editingRule.value.configVersion}` : '—' },
    { label: '检查项', value: template?.name || '未选择' },
    { label: '检查方式', value: template ? (template.checkType === 'DATA' ? '业务数据（只读抽样）' : '元数据检查') : '—' },
    { label: '治理维度', value: template ? qualityDimensionLabel(template.dimension) : '—' },
    { label: '检查目标', value: template ? ruleTargetTypeLabel(template.targetType) : '—' },
    {
      label: '适用范围',
      value: describeRuleScope(
        form.scopeType,
        context.currentDatasource?.name,
        form.targetTableName,
        form.targetColumnName,
        scopeMode.value === 'DATASOURCE',
      ),
    },
  ]
})
const filteredRules = computed(() => rules.value)
const advancedFilterCount = computed(() => [
  scopeMode.value === 'DATASOURCE' && selectedRuleScope.value !== 'ALL', dimensionFilter.value !== '', showDeleted.value,
].filter(Boolean).length)
const hasRuleFilters = computed(() => Boolean(ruleNameFilter.value.trim() || templateFilter.value || dimensionFilter.value
  || (scopeMode.value === 'DATASOURCE' && selectedRuleScope.value !== 'ALL') || enabledFilter.value !== 'ALL' || showDeleted.value))

function scopeLabel(scope?: string) {
  return ruleScopeLabel(scope)
}

function ruleTargetType(rule: QualityRule) {
  const template = templates.value.find((item) => item.code === rule.templateCode)
  return ruleTargetTypeLabel(template?.targetType)
}

function ruleCheckWay(rule: QualityRule) {
  return rule.checkType === 'DATA' ? '业务数据（只读抽样）' : '元数据检查'
}

function ruleCondition(rule: QualityRule) {
  const template = templates.value.find((item) => item.code === rule.templateCode)
  return describeRuleCondition(rule.templateCode, rule.parameters, template?.parameters, template?.description)
}

function ruleScope(rule: QualityRule) {
  const datasourceName = rule.datasourceId
    ? context.datasources.find((item) => item.id === rule.datasourceId)?.name || `数据源 #${rule.datasourceId}`
    : undefined
  return describeRuleScope(rule.scopeType, datasourceName, rule.targetTableName, rule.targetColumnName,
    scopeMode.value === 'DATASOURCE')
}

function formatRuleTime(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value.replace('T', ' ') : new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date)
}

function revisionActionLabel(action: string) {
  return ({ BASELINE: '迁移基线', CREATE: '创建', COPY: '复制', UPDATE: '编辑', ENABLE: '启用', DISABLE: '停用', DELETE: '删除', RESTORE: '恢复' } as Record<string, string>)[action] || action
}

function handleRuleCommand(command: string, rule: QualityRule) {
  if (command === 'executions') { void openDetail(rule, 'executions'); return }
  if (command === 'toggle') { if (rule.supported && !rule.deletedAt && canManageRule(rule)) void toggleRule(rule); return }
  if (command === 'copy') void copyRule(rule)
  if (command === 'delete') void removeRule(rule)
}

function clearAdvancedFilters() {
  selectedRuleScope.value = scopeMode.value === 'GLOBAL' ? 'GLOBAL' : 'ALL'
  dimensionFilter.value = ''
  showDeleted.value = false
}

function canManageRule(rule: QualityRule) {
  return rule.scopeType === 'GLOBAL'
    ? iamS1.systemAdmin
    : Boolean(rule.datasourceId && iamS1.canOnDatasource(RULE_MANAGE, rule.datasourceId))
}

function queryParams() {
  return {
    datasourceId: scopeMode.value === 'DATASOURCE' ? context.datasourceId : undefined,
    scopeType: scopeMode.value === 'GLOBAL' ? 'GLOBAL' : selectedRuleScope.value === 'ALL' ? undefined : selectedRuleScope.value,
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
  const request = ++rulesRequestId
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
    if (disposed || request !== rulesRequestId) return
    // 兼容旧 mock/旧后端返回数组；正式接口返回分页对象。
    const payload = response.data as unknown
    rules.value = Array.isArray(payload)
      ? payload as QualityRule[]
      : (payload as { records?: QualityRule[] } | null)?.records ?? []
    rulesTotal.value = Array.isArray(payload)
      ? payload.length
      : (payload as { total?: number } | null)?.total ?? 0
    // 删除/筛选后当前页可能已越界，退回最后一页而不是显示空页。
    if (!rules.value.length && rulesTotal.value > 0 && rulesPage.value > 1) {
      rulesPage.value = Math.max(1, Math.ceil(rulesTotal.value / rulesPageSize))
      await loadRules()
    }
  } catch {
    if (!disposed && request === rulesRequestId) rulesError.value = '质量规则读取失败，请重试。'
  } finally {
    if (!disposed && request === rulesRequestId) rulesLoading.value = false
  }
}

async function loadTemplates() {
  const request = ++templateRequestId
  templatesError.value = ''
  if (!canViewCheckItems.value) {
    templates.value = []
    return
  }
  templatesLoading.value = true
  try {
    const response = await listQualityRuleTemplates()
    if (disposed || request !== templateRequestId) return
    templates.value = response.data ?? []
  } catch {
    if (!disposed && request === templateRequestId) templatesError.value = '支持的检查项读取失败，请重试。'
  } finally {
    if (!disposed && request === templateRequestId) templatesLoading.value = false
  }
}

async function loadApplicability() {
  const request = ++applicabilityRequestId
  const datasourceId = context.datasourceId
  const snapshotId = context.snapshotId
  applicability.value = null
  applicabilityError.value = ''
  applicabilityLoading.value = false
  if (scopeMode.value !== 'DATASOURCE' || !datasourceId || !snapshotId || !canViewRules.value || context.routeSyncing || context.routeSyncError) return
  applicabilityLoading.value = true
  const current = () => !disposed && request === applicabilityRequestId && datasourceId === context.datasourceId && snapshotId === context.snapshotId && scopeMode.value === 'DATASOURCE'
  try {
    const response = await getQualityRuleSummary(snapshotId)
    if (!current()) return
    if (response.data?.snapshotId && response.data.snapshotId !== snapshotId || response.data?.datasourceId && response.data.datasourceId !== datasourceId) throw new Error('规划范围不一致')
    applicability.value = response.data ?? null
  } catch { if (current()) applicabilityError.value = '当前快照的规则适用性读取失败，不能判断生效情况。' }
  finally { if (current()) applicabilityLoading.value = false }
}

function rulePlan(rule: QualityRule) {
  return applicability.value?.rules?.find(item => item.ruleId === rule.id && item.configVersion === rule.configVersion)
}
function applicabilityLabel(rule: QualityRule) {
  if (rule.deletedAt) return '已删除'
  if (!rule.enabled) return '已停用'
  if (scopeMode.value === 'GLOBAL') return '全局定义'
  if (applicabilityLoading.value) return '正在核对'
  if (applicabilityError.value) return '核对失败'
  if (!context.snapshotId) return '未选快照'
  return rulePlanStatusLabel(rulePlan(rule)?.status)
}
function applicabilityReason(rule: QualityRule) {
  if (!rule.enabled || rule.deletedAt) return '不参与后续检查，历史结果保留。'
  if (scopeMode.value === 'GLOBAL') return '切换到具体数据源和快照，才能判断此默认配置是否适用。'
  const plan = rulePlan(rule)
  if (!plan) return '尚无与当前配置版本匹配的规划依据。'
  return plan.reason || (plan.status === 'READY' ? `预计检查 ${plan.plannedTargetCount}/${plan.targetCount} 个目标；尚未执行。` : '当前快照没有适用对象。')
}

async function loadPage() {
  pageError.value = ''
  try {
    await Promise.all([context.initialize(), iamS1.load()])
    scopeMode.value = context.datasourceId ? 'DATASOURCE'
      : iamS1.hasGlobal(RULE_VIEW) ? 'GLOBAL' : 'DATASOURCE'
    await Promise.all([loadTemplates(), loadRules(), loadApplicability()])
    pageReady.value = true
    consumeTemplateIntent()
    consumeCheckCatalogIntent()
  } catch {
    pageError.value = '规则管理范围或权限读取失败，请重试。'
  }
}

async function refresh() {
  await Promise.all([loadTemplates(), loadRules(), loadApplicability()])
}

function invalidateEditorSession() {
  editorSessionId++
  targetRequestId++
  saveRequestId++
  targetLoading.value = false
  saveLoading.value = false
}

function beginEditorSession() {
  invalidateEditorSession()
  return editorSessionId
}

function isCurrentEditorFlow(sessionId: number, ruleId: number | undefined,
                             scope: RuleScope, datasourceId: number | undefined) {
  return !disposed && sessionId === editorSessionId && editingRuleId.value === ruleId
    && form.scopeType === scope && form.datasourceId === datasourceId
    && (datasourceId === undefined || context.datasourceId === datasourceId)
}

function selectScopeMode(mode: 'GLOBAL' | 'DATASOURCE') {
  if (mode === 'GLOBAL' && !iamS1.hasGlobal(RULE_VIEW)) return
  invalidateEditorSession()
  if (editorOpen.value) editorOpen.value = false
  resetForm(mode === 'GLOBAL' ? 'GLOBAL' : 'DATASOURCE')
  scopeMode.value = mode
  selectedRuleScope.value = mode === 'GLOBAL' ? 'GLOBAL' : 'ALL'
  void Promise.all([loadTemplates(), loadRules()])
}

async function loadRuleTargets(datasourceId: number, sessionId = editorSessionId,
                               ruleId = editingRuleId.value, scope = form.scopeType) {
  if (!isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)) return
  const request = ++targetRequestId
  targetLoading.value = true
  targetError.value = ''
  targetTables.value = []
  targetColumns.value = []
  targetSnapshotId.value = undefined
  targetSnapshotVersion.value = undefined
  if (!iamS1.canOnDatasource(METADATA_VIEW, datasourceId)) {
    if (isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)) {
      targetError.value = '没有该数据源的资产结构查看权限，无法选择表或字段。'
      targetLoading.value = false
    }
    return
  }
  try {
    const snapshotsResult = await listSnapshots({ datasourceId, page: 1, size: 50 })
    if (disposed || request !== targetRequestId || !isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)) return
    const latest = [...(snapshotsResult.data?.records ?? [])]
      .sort((a, b) => b.snapshotVersion - a.snapshotVersion)[0]
    if (!latest) {
      targetError.value = '该数据源没有元数据快照，无法校验目标范围。'
      return
    }
    targetSnapshotId.value = latest.id
    targetSnapshotVersion.value = latest.snapshotVersion
    const tableResult = await listSnapshotTables(latest.id)
    if (disposed || request !== targetRequestId || !isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)) return
    targetTables.value = tableResult.data ?? []
  } catch {
    if (!disposed && request === targetRequestId
      && isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)) targetError.value = '表和字段目标读取失败，请重试。'
  } finally {
    if (!disposed && request === targetRequestId) targetLoading.value = false
  }
}

async function loadColumns(tableName: string, sessionId = editorSessionId,
                           ruleId = editingRuleId.value, scope = form.scopeType,
                           datasourceId = form.datasourceId) {
  if (!isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)) return
  form.targetTableName = tableName
  form.targetColumnName = ''
  targetColumns.value = []
  const snapshotId = targetSnapshotId.value
  if (!tableName || !snapshotId || !isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)) return
  const request = ++targetRequestId
  targetLoading.value = true
  targetError.value = ''
  try {
    const result = await listSnapshotTableColumns(snapshotId, tableName)
    if (disposed || request !== targetRequestId || !isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)
      || tableName !== form.targetTableName || snapshotId !== targetSnapshotId.value) return
    targetColumns.value = result.data ?? []
  } catch {
    if (!disposed && request === targetRequestId
      && isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)) targetError.value = '字段目标读取失败，请重试。'
  } finally {
    if (!disposed && request === targetRequestId) targetLoading.value = false
  }
}

function clearParameterErrors() {
  Object.keys(parameterErrors).forEach((key) => delete parameterErrors[key])
}

function validateParameters(): boolean {
  clearParameterErrors()
  const template = currentTemplate.value
  if (!template) return false
  for (const parameter of template.parameters) {
    const value = form.parameters[parameter.key]
    if (value === null || value === undefined || (typeof value === 'number' && !Number.isFinite(value))) {
      parameterErrors[parameter.key] = `请填写${parameter.label}`
      continue
    }
    if (parameter.type === 'BOOLEAN') continue
    const numeric = Number(value)
    if (parameter.type === 'INTEGER' && !Number.isInteger(numeric)) {
      parameterErrors[parameter.key] = `${parameter.label}必须是整数`
    } else if (parameter.minimum !== undefined && numeric < parameter.minimum) {
      parameterErrors[parameter.key] = `${parameter.label}不能小于 ${parameter.minimum}`
    } else if (parameter.maximum !== undefined && numeric > parameter.maximum) {
      parameterErrors[parameter.key] = `${parameter.label}不能大于 ${parameter.maximum}`
    }
  }
  return Object.keys(parameterErrors).length === 0
}

useAdminScrollLock(() => editorOpen.value || detailOpen.value || checkCatalogOpen.value || coverageOpen.value)

watch(() => form.parameters, (parameters) => {
  for (const key of Object.keys(parameterErrors)) {
    const value = parameters[key]
    if (value !== null && value !== undefined) delete parameterErrors[key]
  }
}, { deep: true })

function resetParameters(templateCode: string) {
  editorCatalogOpen.value = false
  targetRequestId++
  targetLoading.value = false
  clearParameterErrors()
  const template = templates.value.find((item) => item.code === templateCode)
  form.parameters = Object.fromEntries((template?.parameters ?? []).map((parameter) => [
    parameter.key,
    parameter.type === 'BOOLEAN' ? Boolean(parameter.defaultValue) : Number(parameter.defaultValue),
  ]))
  const allowedScopes = sortRuleScopes(template?.allowedScopes ?? [])
  if (template && !allowedScopes.includes(form.scopeType) && allowedScopes.length) {
    form.scopeType = allowedScopes[0] as RuleScope
  }
  form.targetTableName = ''
  form.targetColumnName = ''
  targetColumns.value = []
}

async function changeFormScope(scope: RuleScope) {
  targetRequestId++
  targetLoading.value = false
  targetError.value = ''
  form.scopeType = scope
  form.targetTableName = ''
  form.targetColumnName = ''
  targetColumns.value = []
  if (scope === 'GLOBAL') {
    form.datasourceId = undefined
    targetTables.value = []
    targetSnapshotId.value = undefined
    return
  }
  form.datasourceId = context.datasourceId
  const sessionId = editorSessionId
  const ruleId = editingRuleId.value
  const datasourceId = form.datasourceId
  if (scope === 'TABLE' || scope === 'COLUMN') {
    if (!context.datasourceId) {
      targetError.value = '请先选择数据源。'
      return
    }
    await loadRuleTargets(context.datasourceId, sessionId, ruleId, scope)
    if (!isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)) return
  }
}

function resetForm(scope: RuleScope) {
  editingRuleId.value = undefined
  editingRule.value = null
  form.ruleName = ''
  form.templateCode = ''
  form.description = ''
  form.scopeType = scope
  form.datasourceId = scope === 'GLOBAL' ? undefined : context.datasourceId
  form.targetTableName = ''
  form.targetColumnName = ''
  form.parameters = {}
  form.severity = 'MEDIUM'
  form.deductionPoints = 3
  form.enabled = true
  targetError.value = ''
  targetTables.value = []
  targetColumns.value = []
  clearParameterErrors()
  void nextTick(() => formRef.value?.clearValidate())
}

function consumeTemplateIntent() {
  if (!pageReady.value || templatesLoading.value || templatesError.value || context.routeSyncing || context.routeSyncError) return
  const requestedDatasourceId = Number(route.query.datasourceId) || undefined
  if (requestedDatasourceId && requestedDatasourceId !== context.datasourceId) return
  const code = typeof route.query.createTemplate === 'string' ? route.query.createTemplate : ''
  if (!code) return
  const template = templates.value.find(item => item.code === code)
  if (!template) { ElMessage.warning('该检查项不可用，请重新选择。') }
  else {
    const global = route.query.createScope === 'GLOBAL'
    if (global && !iamS1.systemAdmin) { ElMessage.warning('全局规则仅受保护系统管理员可以维护。') }
    else if (!global && !context.datasourceId) { return }
    else if (!global && !iamS1.canOnDatasource(RULE_MANAGE, context.datasourceId)) { ElMessage.warning('当前账号没有维护此数据源规则的权限。') }
    else if (!global && !iamS1.canOnDatasource(RULE_VIEW, context.datasourceId)) { ElMessage.warning('当前账号没有查看此数据源规则的权限。') }
    else {
      selectScopeMode(global ? 'GLOBAL' : 'DATASOURCE')
      createRule()
      form.templateCode = template.code
      resetParameters(template.code)
      form.ruleName = template.name
      editorCatalogOpen.value = false
    }
  }
  const query = { ...route.query }
  delete query.createTemplate
  delete query.createScope
  delete query.showChecks
  void router.replace({ query })
}

function openCheckCatalog() {
  if (canViewCheckItems.value) checkCatalogOpen.value = true
}

function consumeCheckCatalogIntent() {
  if (!pageReady.value || context.routeSyncing || context.routeSyncError || editorOpen.value || route.query.createTemplate || route.query.showChecks !== '1') return
  const requestedDatasourceId = Number(route.query.datasourceId) || undefined
  if (requestedDatasourceId && requestedDatasourceId !== context.datasourceId) return
  openCheckCatalog()
  const query = { ...route.query }
  delete query.showChecks
  void router.replace({ query })
}

function selectEditorCheckItem(code: string) {
  if (!canConfigureRule.value || saveLoading.value) return
  const item = templates.value.find(item => item.code === code)
  if (!item) return
  form.templateCode = code
  resetParameters(code)
  if (!form.ruleName.trim()) form.ruleName = item.name
  editorCatalogOpen.value = false
}

function createFromCheckItem(code: string) {
  if (!canConfigureRule.value || !templates.value.some(item => item.code === code)) return
  checkCatalogOpen.value = false
  createRule()
  selectEditorCheckItem(code)
}

function createRule() {
  if (!canManageSelectedScope.value) {
    ElMessage.warning(scopeMode.value === 'GLOBAL' ? '全局规则仅受保护系统管理员可以维护。' : '当前账号没有维护此数据源规则的权限。')
    return
  }
  const initialScope: RuleScope = scopeMode.value === 'GLOBAL' ? 'GLOBAL' : 'DATASOURCE'
  beginEditorSession()
  resetForm(initialScope)
  editorCatalogOpen.value = false
  editorOpen.value = true
}

async function editRule(rule: QualityRule) {
  if (!canManageRule(rule) || !rule.supported) return
  const template = templates.value.find((item) => item.code === rule.templateCode)
  if (!template) {
    ElMessage.error('当前规则对应的检查项不可用，无法编辑。')
    return
  }
  const sessionId = beginEditorSession()
  resetForm(rule.scopeType)
  editingRuleId.value = rule.id
  editingRule.value = rule
  form.ruleName = rule.ruleName
  form.templateCode = rule.templateCode || ''
  form.description = rule.description || ''
  form.scopeType = rule.scopeType
  form.datasourceId = rule.datasourceId ?? undefined
  form.targetTableName = rule.targetTableName || ''
  form.targetColumnName = rule.targetColumnName || ''
  form.parameters = Object.fromEntries(template.parameters.map((parameter) => [
    parameter.key,
    parameter.type === 'BOOLEAN'
      ? Boolean(rule.parameters?.[parameter.key] ?? parameter.defaultValue)
      : Number(rule.parameters?.[parameter.key] ?? parameter.defaultValue),
  ]))
  form.severity = rule.severity
  form.deductionPoints = Number(rule.deductionPoints)
  form.enabled = rule.enabled === 1
  const scope = rule.scopeType
  // 后端全局规则的 datasourceId 是 null；会话校验使用 undefined，必须先归一化。
  const datasourceId = rule.datasourceId ?? undefined
  if (rule.scopeType === 'TABLE' || rule.scopeType === 'COLUMN') {
    if (rule.datasourceId) await loadRuleTargets(rule.datasourceId, sessionId, rule.id, scope)
    if (!isCurrentEditorFlow(sessionId, rule.id, scope, datasourceId)) return
    if (rule.scopeType === 'COLUMN' && rule.targetTableName) {
      await loadColumns(rule.targetTableName, sessionId, rule.id, scope, datasourceId)
      if (!isCurrentEditorFlow(sessionId, rule.id, scope, datasourceId)
        || form.targetTableName !== rule.targetTableName) return
    }
    form.targetTableName = rule.targetTableName || ''
    form.targetColumnName = rule.targetColumnName || ''
  }
  if (!isCurrentEditorFlow(sessionId, rule.id, scope, datasourceId)) return
  editorCatalogOpen.value = false
  editorOpen.value = true
}

function payload(): QualityRulePayload {
  return {
    ruleName: form.ruleName.trim(),
    templateCode: form.templateCode,
    description: form.description.trim() || undefined,
    scopeType: form.scopeType,
    datasourceId: form.datasourceId,
    targetTableName: form.targetTableName || undefined,
    targetColumnName: form.targetColumnName || undefined,
    parameters: form.parameters,
    severity: form.severity,
    deductionPoints: Number(form.deductionPoints),
    enabled: form.enabled,
  }
}

async function saveRule() {
  const template = currentTemplate.value
  if (!template) {
    ElMessage.warning('请选择检查项。')
    return
  }
  if (!form.ruleName.trim()) {
    ElMessage.warning('请填写规则名称。')
    return
  }
  if (form.scopeType !== 'GLOBAL' && !form.datasourceId) {
    ElMessage.warning('请先在顶部范围栏选择数据源。')
    return
  }
  if (!validateParameters()) {
    ElMessage.warning(Object.values(parameterErrors)[0] || '请检查规则参数后再保存。')
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (targetError.value && isTargetScoped.value) {
    ElMessage.warning(targetError.value)
    return
  }
  const sessionId = editorSessionId
  const ruleId = editingRuleId.value
  const scope = form.scopeType
  const datasourceId = form.datasourceId
  if (!isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)) return
  const requestPayload = payload()
  const requestId = ++saveRequestId
  const wasEditing = ruleId !== undefined
  saveLoading.value = true
  try {
    if (ruleId !== undefined) await editQualityRule(ruleId, requestPayload)
    else await createQualityRule(requestPayload)
    if (!isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)) return
    editorOpen.value = false
    ElMessage.success(wasEditing ? '规则配置已保存并生成新版本。' : '规则已创建并进入校验执行链。')
    rulesPage.value = 1
    await Promise.all([loadRules(), loadApplicability()])
  } catch (error) {
    if (!isCurrentEditorFlow(sessionId, ruleId, scope, datasourceId)) return
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '规则保存失败。')
  } finally {
    if (requestId === saveRequestId) saveLoading.value = false
  }
}

async function copyRule(rule: QualityRule) {
  if (!canManageRule(rule)) return
  try {
    await copyQualityRule(rule.id)
    ElMessage.success('规则副本已创建，默认停用。')
    await Promise.all([loadRules(), loadApplicability()])
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '复制规则失败。')
  }
}

async function toggleRule(rule: QualityRule) {
  if (!canManageRule(rule)) return
  rowLoading[rule.id] = true
  try {
    await updateRuleEnabled(rule.id, rule.enabled !== 1)
    ElMessage.success(rule.enabled === 1 ? '规则已停用。' : '规则已启用。')
    await Promise.all([loadRules(), loadApplicability()])
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '规则状态更新失败。')
    await Promise.all([loadRules(), loadApplicability()])
  } finally {
    rowLoading[rule.id] = false
  }
}

async function removeRule(rule: QualityRule) {
  if (!canManageRule(rule)) return
  try {
    await ElMessageBox.confirm(`删除「${rule.ruleName}」将立即停用该规则，已有问题与历史结果会保留。`, '删除质量规则', { type: 'warning' })
  } catch { return }
  rowLoading[rule.id] = true
  try {
    await deleteQualityRule(rule.id)
    ElMessage.success('规则已停用并移入删除历史。')
    await Promise.all([loadRules(), loadApplicability()])
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '删除规则失败。')
  } finally {
    rowLoading[rule.id] = false
  }
}

async function restoreRule(rule: QualityRule) {
  if (!canManageRule(rule)) return
  rowLoading[rule.id] = true
  try {
    await restoreQualityRule(rule.id)
    ElMessage.success('规则已恢复为停用状态。')
    await Promise.all([loadRules(), loadApplicability()])
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '恢复规则失败。')
  } finally {
    rowLoading[rule.id] = false
  }
}

async function openDetail(rule: QualityRule, tab: 'description' | 'executions' | 'revisions' = 'description') {
  const request = ++detailRequestId
  detailRule.value = rule
  detailHistory.value = []
  detailRevisions.value = []
  detailError.value = ''
  detailTab.value = tab
  detailOpen.value = true
  detailLoading.value = true
  try {
    const [ruleResult, historyResult, revisionsResult] = await Promise.all([
      getQualityRule(rule.id, true),
      getQualityRuleHistory(rule.id),
      getQualityRuleRevisions(rule.id),
    ])
    if (disposed || request !== detailRequestId) return
    detailRule.value = ruleResult.data ?? rule
    detailHistory.value = historyResult.data ?? []
    detailRevisions.value = revisionsResult.data ?? []
  } catch {
    if (!disposed && request === detailRequestId) detailError.value = '规则详情或历史版本读取失败。'
  } finally {
    if (!disposed && request === detailRequestId) detailLoading.value = false
  }
}

function detailRetry() {
  if (detailRule.value) void openDetail(detailRule.value, detailTab.value)
}

function openExecutionIssues(entry: QualityCheckBatchRule) {
  const datasourceId = entry.batchDatasourceId
  if (!datasourceId || !entry.snapshotId || !iamS1.canOnDatasource('governance:issue:view', datasourceId)) return
  router.push({ path: '/admin/governance/issues', query: {
    ...buildContextQuery('datasource-snapshot', { datasourceId, snapshotId: entry.snapshotId }),
    issueDomain: 'SOURCE', dimension: entry.dimension,
    ...(entry.targetTableName ? { tableName: entry.targetTableName } : {}),
  } })
}

function executionSource(entry: QualityCheckBatchRule) {
  return entry.batchDatasourceId ? context.datasources.find(item => item.id === entry.batchDatasourceId)?.name || `数据源 #${entry.batchDatasourceId}` : '执行来源未记录'
}


watch([scopeMode, selectedRuleScope, templateFilter, dimensionFilter, ruleNameFilter, enabledFilter, showDeleted], () => {
  rulesPage.value = 1
  void loadRules()
})
watch(() => context.datasourceId, async () => {
  invalidateEditorSession()
  if (editorOpen.value) editorOpen.value = false
  resetForm(scopeMode.value === 'GLOBAL' ? 'GLOBAL' : 'DATASOURCE')
  if (detailOpen.value) { detailRequestId++; detailOpen.value = false; detailRule.value = null; detailHistory.value = []; detailRevisions.value = []; detailLoading.value = false }
  checkCatalogOpen.value = false
  if (scopeMode.value === 'DATASOURCE') await loadRules()
  consumeTemplateIntent()
  consumeCheckCatalogIntent()
})
watch(editorOpen, (open) => {
  if (!open) invalidateEditorSession()
}, { flush: 'sync' })
watch(() => [route.query.createTemplate, route.query.createScope, route.query.showChecks, context.routeSyncing, context.routeSyncError], () => { consumeTemplateIntent(); consumeCheckCatalogIntent() })
watch(() => [context.snapshotId, context.datasourceId, context.routeSyncing, context.routeSyncError, scopeMode.value], () => { void loadApplicability() })
onMounted(loadPage)
onBeforeUnmount(() => {
  disposed = true
  applicabilityRequestId++
  invalidateEditorSession()
  rulesRequestId++
  templateRequestId++
  targetRequestId++
  detailRequestId++
})
</script>

<template>
  <main class="quality-rules-page post-login-page governance-workspace governance-surface">
    <TaskPageHeader title="质量规则" description="为当前数据源配置检查对象和异常条件。全局默认可由更具体的本源规则覆盖；检查覆盖与执行记录在详情中查看。">
      <template #actions><el-button type="primary" :icon="Plus" :disabled="!canConfigureRule || templatesLoading" @click="createRule">新增规则</el-button></template>
    </TaskPageHeader>
    <ErrorState v-if="pageError" :message="pageError" @retry="loadPage" />
    <section v-else class="quality-rules-page__workspace">
      <div class="quality-rules-page__toolbar">
        <div class="quality-rules-page__scope-switch" role="group" aria-label="规则管理范围">
          <button type="button" :aria-pressed="scopeMode === 'DATASOURCE'" :class="{ active: scopeMode === 'DATASOURCE' }" @click="selectScopeMode('DATASOURCE')">当前数据源</button>
          <button type="button" :aria-pressed="scopeMode === 'GLOBAL'" :class="{ active: scopeMode === 'GLOBAL' }" :disabled="!iamS1.hasGlobal(RULE_VIEW)" @click="selectScopeMode('GLOBAL')">全局规则库</button>
        </div>
        <div class="quality-rules-page__toolbar-actions">
          <el-button v-if="scopeMode === 'DATASOURCE' && canViewRules && context.snapshotId" link type="primary" @click="coverageOpen = true">检查覆盖<el-tag v-if="(applicability?.notApplicableCount || 0) + (applicability?.invalidCount || 0)" size="small" type="warning">{{ (applicability?.notApplicableCount || 0) + (applicability?.invalidCount || 0) }} 项待核对</el-tag></el-button>
          <el-button link :icon="ListChecks" :disabled="!canViewCheckItems" @click="openCheckCatalog">检查项说明</el-button>
          <el-button :icon="RefreshCw" :loading="rulesLoading || templatesLoading" aria-label="刷新质量规则" @click="refresh" />
        </div>
      </div>
      <p v-if="scopeMode === 'GLOBAL'" class="quality-rules-page__scope-note">全局默认配置用于各数据源的后续检查，仅系统管理员可维护。</p>
      <p v-else-if="!context.datasourceId" class="quality-rules-page__scope-note" role="status">请先在顶部选择数据源。</p>
      <p v-if="route.query.createTemplate && !context.datasourceId && route.query.createScope !== 'GLOBAL'" role="status" class="quality-rules-page__scope-note">选择数据源后继续配置所选检查项。</p>
      <ErrorState v-if="applicabilityError && scopeMode === 'DATASOURCE'" :message="applicabilityError" @retry="loadApplicability" />
      <div class="quality-rules-page__filters">
        <el-input v-model="ruleNameFilter" clearable placeholder="搜索规则名称" aria-label="搜索规则名称" />
        <el-select v-model="enabledFilter" aria-label="启用状态"><el-option label="全部状态" value="ALL" /><el-option label="已启用" value="ENABLED" /><el-option label="已停用" value="DISABLED" /></el-select>
        <el-button :aria-expanded="advancedFiltersOpen" aria-controls="quality-rule-advanced-filters" @click="advancedFiltersOpen = !advancedFiltersOpen">更多筛选<el-tag v-if="advancedFilterCount || templateFilter" size="small" round>{{ advancedFilterCount + (templateFilter ? 1 : 0) }}</el-tag><ChevronDown :size="14" aria-hidden="true" /></el-button>
        <span v-if="!rulesLoading && canViewRules" class="quality-rules-page__result-count">共 {{ rulesTotal }} 条规则</span>
      </div>
      <div v-if="advancedFiltersOpen" id="quality-rule-advanced-filters" class="quality-rules-page__advanced-filters">
        <el-select v-model="templateFilter" clearable filterable placeholder="全部检查项" aria-label="检查项筛选"><el-option v-for="item in templates" :key="item.code" :label="item.name" :value="item.code" /></el-select>
        <el-select v-model="selectedRuleScope" aria-label="规则范围筛选" :disabled="scopeMode === 'GLOBAL'"><el-option label="全部范围" value="ALL" /><el-option label="全局默认" value="GLOBAL" /><el-option label="数据源级" value="DATASOURCE" /><el-option label="表级" value="TABLE" /><el-option label="字段级" value="COLUMN" /></el-select>
        <el-select v-model="dimensionFilter" clearable placeholder="全部维度" aria-label="治理维度筛选"><el-option v-for="dimension in ['COMPLETENESS', 'ACCURACY', 'CONSISTENCY', 'TIMELINESS', 'TRACEABILITY']" :key="dimension" :label="qualityDimensionLabel(dimension)" :value="dimension" /></el-select>
        <el-checkbox v-model="showDeleted">查看已删除</el-checkbox><el-button text @click="clearAdvancedFilters(); templateFilter = ''">清除筛选</el-button>
      </div>
      <ErrorState v-if="templatesError" :message="templatesError" @retry="loadTemplates" />
      <ErrorState v-if="rulesError" :message="rulesError" @retry="loadRules" />
      <p v-else-if="!canViewRules" class="quality-rules-page__permission">没有当前范围的“查看治理规则”权限。</p>
      <div v-else v-loading="rulesLoading || templatesLoading" class="quality-rules-page__table-wrap">
        <el-table :data="filteredRules" row-key="id" :empty-text="hasRuleFilters ? '没有符合筛选条件的质量规则。' : '当前管理范围暂无质量规则。'">
          <el-table-column prop="ruleName" label="规则" min-width="280"><template #default="{ row }">
            <button class="quality-rules-page__name-link" type="button" @click="openDetail(row)">{{ row.ruleName }}</button>
            <small class="quality-rules-page__condition">{{ briefRuleCondition(row.templateCode, row.parameters) }}</small>
          </template></el-table-column>
          <el-table-column label="来源" width="132"><template #default="{ row }"><el-tag size="small" :type="row.scopeType === 'GLOBAL' ? 'info' : 'primary'">{{ row.scopeType === 'GLOBAL' ? '全局默认' : '本源专用' }}</el-tag></template></el-table-column>
          <el-table-column label="适用对象" min-width="190"><template #default="{ row }"><span class="quality-rules-page__target" :title="ruleScope(row)">{{ row.targetTableName ? `${row.targetTableName}${row.targetColumnName ? '.' + row.targetColumnName : ''}` : row.scopeType === 'GLOBAL' ? '全部适用对象' : '当前数据源' }}</span></template></el-table-column>
          <el-table-column label="状态" width="155"><template #default="{ row }">
            <span v-if="row.deletedAt" class="quality-rules-page__state">已删除</span><span v-else-if="!row.supported" class="quality-rules-page__state quality-rules-page__state--warning">检查项不可用</span><span v-else class="quality-rules-page__state" :class="{ 'quality-rules-page__state--enabled': row.enabled === 1 }">{{ row.enabled === 1 ? '已启用' : '已停用' }}</span>
            <el-tooltip v-if="row.enabled && scopeMode === 'DATASOURCE' && rulePlan(row) && rulePlan(row)?.status !== 'READY'" :content="applicabilityReason(row)" placement="top"><button type="button" class="quality-rules-page__attention" @click="openDetail(row)">需核对</button></el-tooltip>
          </template></el-table-column>
          <el-table-column label="操作" width="156" fixed="right"><template #default="{ row }"><div class="quality-rules-page__row-actions">
            <el-button v-if="showDeleted" link type="primary" :disabled="!canManageRule(row)" :loading="rowLoading[row.id]" @click="restoreRule(row)">恢复</el-button>
            <el-button v-else-if="row.supported" link type="primary" :disabled="!canManageRule(row)" @click="editRule(row)">编辑</el-button>
            <el-dropdown trigger="click" @command="(command: string) => handleRuleCommand(command, row)"><el-button link :aria-label="`${row.ruleName}更多操作`" :loading="rowLoading[row.id]">更多<ChevronDown :size="14" aria-hidden="true" /></el-button><template #dropdown><el-dropdown-menu>
              <el-dropdown-item command="executions">执行记录</el-dropdown-item>
              <el-dropdown-item v-if="!showDeleted" command="toggle" :disabled="!row.supported || !canManageRule(row)">{{ row.enabled === 1 ? '停用规则' : '启用规则' }}</el-dropdown-item>
              <el-dropdown-item v-if="!showDeleted" command="copy" :disabled="!row.supported || !canManageRule(row)"><Copy :size="14" aria-hidden="true" />复制规则</el-dropdown-item>
              <el-dropdown-item v-if="!showDeleted" command="delete" :disabled="!canManageRule(row)" class="quality-rules-page__danger-action"><Trash2 :size="14" aria-hidden="true" />删除规则</el-dropdown-item>
            </el-dropdown-menu></template></el-dropdown>
          </div></template></el-table-column>
        </el-table>
        <el-pagination v-if="!rulesLoading && !rulesError && rulesTotal > 0" class="quality-rules-page__pagination" layout="total, prev, pager, next" :total="rulesTotal" :page-size="rulesPageSize" :current-page="rulesPage" @current-change="changeRulesPage" />
      </div>
    </section>
    <el-drawer v-model="coverageOpen" title="当前快照检查覆盖" size="min(680px, 96vw)" append-to-body destroy-on-close class="governance-drawer"><RuleApplicabilitySummary :summary="applicability" :loading="applicabilityLoading" :error="applicabilityError" @retry="loadApplicability" /></el-drawer>

    <el-dialog v-model="checkCatalogOpen" title="支持的检查项" width="min(960px, calc(100vw - 32px))" top="6vh" append-to-body destroy-on-close class="quality-check-catalog-dialog governance-dialog">
      <p class="quality-rule-form__intro">检查项提供可复用的检查方法，规则负责配置对象与条件。例如，同一个空值率检查项，可以建立“订单客户编号超过 5%”和“客户手机号超过 20%”两条规则。</p>
      <QualityCheckItemCatalog :items="templates" :loading="templatesLoading" :error="templatesError" :selectable="canConfigureRule" action-label="用此检查项创建规则" :disabled-reason="scopeMode === 'GLOBAL' ? '没有全局规则维护权限；可返回规则页选择有维护权限的数据源。' : '没有当前数据源的规则维护权限，仅可查看检查项说明。'" @select="createFromCheckItem" @retry="loadTemplates" />
      <template #footer><el-button @click="checkCatalogOpen = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog
      v-model="editorOpen"
      :title="editorTitle"
      class="quality-rule-editor-dialog governance-dialog"
      width="min(1100px, calc(100vw - 32px))"
      top="6vh"
      append-to-body
      destroy-on-close
    >
      <div class="quality-rule-form">
        <details v-if="editingRuleId" class="quality-rule-form__context"><summary>规则编号与配置版本</summary><div class="quality-rule-form__derived" aria-label="当前规则摘要">
          <div v-for="item in editorSummary" :key="item.label">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div></details>
        <p class="quality-rule-form__scope"><strong>{{ form.scopeType === 'GLOBAL' ? '正在维护全局默认配置' : '正在维护本源专用配置' }}</strong><span>{{ form.scopeType === 'GLOBAL' ? '影响所有数据源的后续检查；更具体的专用规则优先。' : context.currentDatasource?.name || '请先选择数据源' }}</span></p>

        <el-form ref="formRef" :model="form" :rules="formRules" label-position="top">
          <section class="quality-rule-form__section">
            <h3>1. 选择检查项</h3>
            <el-form-item :label="RULE_LABELS.templateCode" prop="templateCode">
              <el-select v-model="form.templateCode" filterable :loading="templatesLoading" @change="resetParameters">
                <el-option v-for="item in templates" :key="item.code" :label="item.name" :value="item.code" />
              </el-select>
            </el-form-item>
            <el-button link type="primary" :aria-expanded="editorCatalogOpen" aria-controls="rule-editor-check-catalog" @click="editorCatalogOpen = !editorCatalogOpen">{{ editorCatalogOpen ? '收起检查项说明' : '浏览检查项用途和示例' }}</el-button>
            <QualityCheckItemCatalog v-if="editorCatalogOpen" id="rule-editor-check-catalog" :items="templates" :loading="templatesLoading" :error="templatesError" :selected-code="form.templateCode" :selectable="canConfigureRule && !saveLoading" @select="selectEditorCheckItem" @retry="loadTemplates" />
            <details v-if="currentTemplate && !editorCatalogOpen" class="quality-rule-form__guidance"><summary>适用说明</summary>
              <p class="quality-rule-form__target-source"><strong>检查项说明</strong>{{ currentTemplate.description }}</p>
              <p class="quality-rule-form__target-source"><strong>适用场景</strong>{{ ruleBusinessGuidance(currentTemplate.code).scenario }}</p><p class="quality-rule-form__target-source"><strong>使用前提</strong>{{ ruleBusinessGuidance(currentTemplate.code).prerequisite }}</p>
              <p class="quality-rule-form__target-source">检查方式：{{ currentTemplate.checkType === 'DATA' ? '业务数据（只读抽样）' : '元数据检查' }} · 治理维度：{{ qualityDimensionLabel(currentTemplate.dimension) }} · 检查目标：{{ ruleTargetTypeLabel(currentTemplate.targetType) }}</p>
            </details>
          </section>

          <section v-if="currentTemplate" class="quality-rule-form__section">
            <h3>2. 指定检查对象</h3>
            <div class="quality-rule-form__grid">
              <el-form-item :label="RULE_LABELS.scopeType" prop="scopeType">
                <el-select :model-value="form.scopeType" @change="(value: RuleScope) => changeFormScope(value)">
                  <el-option v-for="scope in sortedAllowedScopes" :key="scope" :label="scopeLabel(scope)" :value="scope" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.scopeType !== 'GLOBAL'" :label="RULE_LABELS.datasource" prop="datasourceId">
                <el-input :model-value="context.currentDatasource?.name || ''" readonly placeholder="请先在范围栏选择数据源" />
              </el-form-item>
              <el-form-item v-if="form.scopeType === 'TABLE' || form.scopeType === 'COLUMN'" :label="RULE_LABELS.targetTable" prop="targetTableName">
                <el-select v-model="form.targetTableName" filterable :loading="targetLoading" :disabled="!targetTables.length" @change="loadColumns">
                  <el-option v-for="table in targetTables" :key="table.tableName" :label="table.tableComment ? `${table.tableName} · ${table.tableComment}` : table.tableName" :value="table.tableName" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.scopeType === 'COLUMN'" :label="RULE_LABELS.targetColumn" prop="targetColumnName">
                <el-select v-model="form.targetColumnName" filterable :loading="targetLoading" :disabled="!targetColumns.length">
                  <el-option v-for="column in targetColumns" :key="column.columnName" :label="column.columnComment ? `${column.columnName} · ${column.columnComment}` : `${column.columnName} · ${column.dataType}`" :value="column.columnName" />
                </el-select>
              </el-form-item>
            </div>
            <p v-if="isTargetScoped && targetSnapshotVersion" class="quality-rule-form__target-source">目标来自数据源当前快照 v{{ targetSnapshotVersion }}；规则保存稳定名称，执行时按数据源和对象名称重新解析。</p>
            <p v-if="targetError" class="quality-rule-form__error" role="alert">{{ targetError }}</p>
          </section>

          <section v-if="currentTemplate" class="quality-rule-form__section">
            <h3>3. 设置异常条件</h3>
            <template v-if="currentTemplate.parameters.length">
              <div class="quality-rule-form__grid">
                <el-form-item
                  v-for="parameter in currentTemplate.parameters"
                  :key="parameter.key"
                  :label="parameter.label"
                  :error="parameterErrors[parameter.key]"
                  required
                >
                  <el-switch v-if="parameter.type === 'BOOLEAN'" v-model="form.parameters[parameter.key]" />
                  <el-input-number v-else v-model="form.parameters[parameter.key]" :min="parameter.minimum" :max="parameter.maximum" :step="parameter.type === 'INTEGER' ? 1 : 0.01" :precision="parameter.type === 'INTEGER' ? 0 : 4" />
                  <small v-if="currentTemplate.code === 'DATA_NULL_RATE_HIGH' && parameter.key === 'threshold'" class="quality-rule-form__parameter-hint">按 0–1 的比例填写，例如 0.05 表示 5%。</small>
                </el-form-item>
              </div>

            </template>
            <p v-else class="quality-rule-form__target-source">此检查项使用固定条件，无需配置额外参数。</p>
          </section>

          <section v-if="currentTemplate" class="quality-rule-form__section">
            <h3>4. 配置风险与处理建议</h3>
            <el-form-item :label="RULE_LABELS.ruleName" prop="ruleName"><el-input v-model="form.ruleName" maxlength="200" show-word-limit placeholder="例如：订单客户编号空值率不得超过 5%" /></el-form-item>
            <el-form-item :label="RULE_LABELS.description"><el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" placeholder="例如：由订单负责人核对缺失客户编号并补齐来源数据" /></el-form-item>
            <div class="quality-rule-form__grid">
              <el-form-item :label="RULE_LABELS.severity" prop="severity">
                <el-select v-model="form.severity"><el-option label="高" value="HIGH" /><el-option label="中" value="MEDIUM" /><el-option label="低" value="LOW" /></el-select>
              </el-form-item>
              <el-form-item :label="RULE_LABELS.deductionPoints" prop="deductionPoints"><el-input-number v-model="form.deductionPoints" :min="0" :max="100" :step="0.5" :precision="1" /></el-form-item>
            </div>
            <el-checkbox v-model="form.enabled">保存后启用</el-checkbox>
          </section>
          <section v-if="currentTemplate" class="quality-rule-form__section quality-rule-form__review">
            <h3>保存前核对</h3><p>配置预览（不执行检查）</p>
            <p class="quality-rule-form__preview"><strong>{{ editorSummary.find(item => item.label === '适用范围')?.value }}</strong>{{ briefRuleCondition(currentTemplate.code, form.parameters) }}</p>
            <p>{{ ruleEvidenceLimit(currentTemplate.code) }}</p>
            <p>保存后{{ form.enabled ? '启用，参与后续检查' : '停用，不参与后续检查' }}；{{ describeRuleImpact(form.severity, form.deductionPoints) }}。实际覆盖由执行时的对象状态、权限和更具体规则决定。</p>
          </section>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="editorOpen = false">取消</el-button>
        <el-button type="primary" :loading="saveLoading" :disabled="!canManageSelectedScope" @click="saveRule">保存规则</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailOpen" title="质量规则详情" :size="'min(760px, 100vw)'" append-to-body destroy-on-close class="quality-rule-detail-drawer governance-drawer" @closed="detailRequestId++">
      <div v-if="detailRule" class="quality-rule-detail">
        <header class="quality-rule-detail__header">
          <div class="quality-rule-detail__heading">
            <div class="quality-rule-detail__title-block">
              <span class="quality-rule-detail__eyebrow">质量规则</span>
              <h2>{{ detailRule.ruleName }}</h2>
              <p>{{ templates.find((item) => item.code === detailRule?.templateCode)?.name || detailRule.templateCode || '检查项不可用' }} · {{ ruleCheckWay(detailRule) }} · {{ qualityDimensionLabel(detailRule.dimension) }} · 检查{{ ruleTargetType(detailRule) }}</p>
            </div>
            <div class="quality-rule-detail__badges">
              <el-tag size="small" :type="detailRule.enabled === 1 ? 'success' : 'info'">{{ detailRule.enabled === 1 ? '已启用' : '已停用' }}</el-tag>
              <el-tag v-if="detailRule.deletedAt" size="small" type="info">已删除</el-tag>
              <el-tag v-if="detailRule.overridden" size="small" type="warning">存在专用配置</el-tag>
            </div>
          </div>
          <dl class="quality-rule-detail__summary">
            <div><dt>{{ RULE_LABELS.templateCode }}</dt><dd>{{ templates.find((item) => item.code === detailRule?.templateCode)?.name || detailRule.templateCode || '检查项不可用' }}</dd></div>
            <div><dt>{{ RULE_LABELS.scopeType }}</dt><dd>{{ ruleScope(detailRule) }}</dd></div>
            <div><dt>{{ RULE_LABELS.severity }}</dt><dd>{{ severityLabel(detailRule.severity) }} · 扣 {{ detailRule.deductionPoints }} 分</dd></div>
            <div><dt>配置版本</dt><dd>v{{ detailRule.configVersion }} · {{ detailRule.enabled === 1 ? '已启用' : '已停用' }}</dd></div>
          </dl>
        </header>
        <el-tabs v-model="detailTab" aria-label="规则详情内容">
          <el-tab-pane label="检查说明" name="description">
            <section class="quality-rule-detail__section">
              <h3>检查项与目标</h3>
              <dl>
                <div><dt>{{ RULE_LABELS.templateCode }}</dt><dd>{{ templates.find((item) => item.code === detailRule?.templateCode)?.name || detailRule.templateCode || '检查项不可用' }}</dd></div>
                <div><dt>{{ RULE_LABELS.checkType }}</dt><dd>{{ ruleCheckWay(detailRule) }}</dd></div>
                <div><dt>{{ RULE_LABELS.dimension }}</dt><dd>{{ qualityDimensionLabel(detailRule.dimension) }}</dd></div>
                <div><dt>{{ RULE_LABELS.targetType }}</dt><dd>{{ ruleTargetType(detailRule) }}</dd></div>
              </dl>
            </section>
            <section class="quality-rule-detail__section">
              <h3>检查项说明</h3>
              <p>{{ templates.find((item) => item.code === detailRule?.templateCode)?.description || '检查项未提供说明。' }}</p>
              <details class="quality-rule-detail__guidance"><summary>什么时候适合使用？</summary><p>{{ ruleBusinessGuidance(detailRule.templateCode).scenario }}</p><p>{{ ruleBusinessGuidance(detailRule.templateCode).prerequisite }}</p></details><p class="quality-rule-detail__condition">{{ ruleCondition(detailRule) }}</p><p>{{ ruleEvidenceLimit(detailRule.templateCode) }}</p>
            </section>
            <section class="quality-rule-detail__section">
              <h3>当前快照的生效与处理影响</h3><p><strong>{{ applicabilityLabel(detailRule) }}</strong> · {{ applicabilityReason(detailRule) }}</p>
              <dl><div><dt>{{ RULE_LABELS.scopeType }}</dt><dd>{{ ruleScope(detailRule) }}</dd></div><div><dt>{{ RULE_LABELS.severity }}</dt><dd>{{ severityLabel(detailRule.severity) }} · 扣 {{ detailRule.deductionPoints }} 分</dd></div><div><dt>当前配置</dt><dd>v{{ detailRule.configVersion }} · {{ detailRule.enabled === 1 ? '已启用' : '已停用' }}<template v-if="detailRule.deletedAt"> · 已删除</template></dd></div></dl>
              <p v-if="detailRule.overridden" class="quality-rule-detail__warning">存在更具体的「{{ detailRule.overriddenBy }}」配置；实际是否覆盖及覆盖多少，以当前快照规划为准。执行优先级：字段、表、数据源、全局。</p>
              <p v-else class="quality-rule-detail__note">同一检查按字段、表、数据源、全局的优先级只应用一个规则实例。</p>
            </section>
            <section class="quality-rule-detail__section"><h3>处理建议（本规则）</h3><p>{{ detailRule.description || '未填写处理建议。' }}</p></section>
            <details class="quality-rule-detail__technical"><summary>技术详情</summary><p>检查项编码：<code>{{ detailRule.templateCode || detailRule.ruleCode }}</code></p><pre>{{ JSON.stringify(detailRule.parameters || {}, null, 2) }}</pre></details>
            <p v-if="!detailRule.supported" class="quality-rule-form__error">{{ detailRule.unsupportedReason || '此规则无法映射到当前可执行模板。' }}</p>
          </el-tab-pane>
          <el-tab-pane label="执行记录" name="executions">
            <ErrorState v-if="detailError" :message="detailError" @retry="detailRetry" />
            <div v-else-if="detailLoading" class="quality-rule-detail__note" role="status">正在读取执行历史…</div>
            <section v-else class="quality-rule-detail__history">
              <h3>实际执行版本</h3><p>这里展示历史批次记录；问题入口查看该快照、维度及目标表的当前问题，不是当次批次的问题清单。</p>
          <template v-if="detailHistory.length">
            <article v-for="entry in detailHistory" :key="`${entry.batchId}-${entry.ruleVersion}`">
              <div><strong>v{{ entry.ruleVersion }} · {{ qualityBatchLabel(entry.batchStatus) }}</strong><span>{{ formatRuleTime(entry.batchStartedAt) }}</span></div>
              <p>{{ executionSource(entry) }} · 快照 {{ entry.snapshotId ? `#${entry.snapshotId}` : '未记录' }} · 批次 #{{ entry.batchId }}</p>
              <small>{{ scopeLabel(entry.scopeType) }} · {{ ruleExecutionLabel(entry.status, entry.issueCount) }} · 检查 {{ entry.checkedTargetCount }}/{{ entry.targetCount }} 个目标 · {{ entry.issueCount }} 个问题</small>
              <p v-if="entry.reason">{{ entry.reason }}</p>
              <p v-if="entry.ruleVersion !== detailRule.configVersion">此记录使用历史配置 v{{ entry.ruleVersion }}，当前配置为 v{{ detailRule.configVersion }}。</p>
              <p>{{ ruleEvidenceLimit(entry.templateCode) }}</p>
              <el-button v-if="entry.batchDatasourceId && entry.snapshotId && iamS1.canOnDatasource('governance:issue:view', entry.batchDatasourceId)" link type="primary" @click="openExecutionIssues(entry)">查看同范围当前问题</el-button>
            </article>
          </template>
              <p v-else>暂无可追溯执行记录。早期未关联批次的结果无法还原当时规则依据。</p>
            </section>
          </el-tab-pane>
          <el-tab-pane label="配置记录" name="revisions">
            <ErrorState v-if="detailError" :message="detailError" @retry="detailRetry" />
            <div v-else-if="detailLoading" class="quality-rule-detail__note" role="status">正在读取配置记录…</div>
            <section v-else class="quality-rule-detail__history">
              <h3>配置变更历史</h3>
          <template v-if="detailRevisions.length">
            <article v-for="revision in detailRevisions" :key="revision.id">
              <div><strong>v{{ revision.configVersion }} · {{ revisionActionLabel(revision.action) }}</strong><span>{{ formatRuleTime(revision.changedAt) }}</span></div>
              <small>{{ revision.ruleName }} · {{ scopeLabel(revision.scopeType) }} · {{ severityLabel(revision.severity) }} · 扣 {{ revision.deductionPoints }} 分 · {{ revision.enabled === 1 ? '启用' : '停用' }}<template v-if="revision.deleted"> · 已删除</template></small>
              <p>{{ describeRecordedRuleCondition(revision.templateCode, revision.parameters) }}</p><details class="quality-rule-detail__technical"><summary>原始参数记录</summary><pre>{{ JSON.stringify(revision.parameters || {}, null, 2) }}</pre></details>
            </article>
            <p v-if="detailRevisions.some((revision) => revision.action === 'BASELINE')">迁移基线只记录迁移时可核对的配置，不代表更早的变更流水。</p>
          </template>
              <p v-else>暂无可追溯配置记录；迁移前的变更需查现有审计记录。</p>
            </section>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>
  </main>
</template>

<style scoped>
.quality-rules-page__workspace { min-width: 0; }
.quality-rules-page__toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; border-bottom: 1px solid var(--do-line); }
.quality-rules-page__scope-switch { display: flex; gap: 26px; }
.quality-rules-page__scope-switch button { min-height: 46px; padding: 0 0 12px; margin-bottom: -1px; border: 0; border-bottom: 2px solid transparent; color: var(--do-muted); background: transparent; font: inherit; font-size: 14px; cursor: pointer; }
.quality-rules-page__scope-switch button.active { color: var(--do-primary-strong); border-bottom-color: var(--do-primary); font-weight: 600; }
.quality-rules-page__scope-switch button:disabled { opacity: .5; cursor: not-allowed; }
.quality-rules-page__toolbar-actions { display: flex; align-items: center; gap: 12px; padding-bottom: 10px; }
.quality-rules-page__scope-note { color: var(--do-muted); font-size: 12px; line-height: 1.6; margin: 12px 0 0; }
.quality-rules-page__filters { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin: 22px 0 16px; }
.quality-rules-page__filters > :deep(.el-input) { width: 260px; }.quality-rules-page__filters > :deep(.el-select) { width: 150px; }
.quality-rules-page__result-count { margin-left: auto; color: var(--do-muted); font-size: 12px; }
.quality-rules-page__advanced-filters { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; padding-bottom: 16px; }
.quality-rules-page__advanced-filters :deep(.el-select) { width: 180px; }
.quality-rules-page__permission { padding: 30px 0; color: var(--do-muted); }
.quality-rules-page__table-wrap { min-width: 0; overflow-x: auto; }.quality-rules-page__pagination { justify-content: flex-end; margin-top: 18px; }
.quality-rules-page__name-link { border: 0; padding: 0; background: none; color: var(--do-ink); font: inherit; font-size: 14px; font-weight: 600; cursor: pointer; text-align: left; }
.quality-rules-page__name-link:hover { color: var(--do-primary-strong); }.quality-rules-page__name-link:focus-visible { outline: 2px solid var(--do-primary); outline-offset: 4px; }
.quality-rules-page__condition { display: block; margin-top: 5px; font-size: 12px; color: var(--do-muted); line-height: 1.5; }
.quality-rules-page__target { font-size: 13px; overflow-wrap: anywhere; color: var(--do-muted); }
.quality-rules-page__state { font-size: 13px; color: var(--do-muted); }.quality-rules-page__state--enabled { color: var(--do-ink); }.quality-rules-page__state--warning { color: var(--do-warning); }
.quality-rules-page__attention { display: block; padding: 0; margin-top: 4px; border: 0; background: none; font-size: 12px; color: var(--do-warning); cursor: pointer; }
.quality-rules-page__row-actions { display: flex; align-items: center; gap: 10px; white-space: nowrap; }
.quality-rule-form { padding: 0 4px; }
.quality-rule-form :deep(.el-form) { display: grid; grid-template-columns: minmax(0,1fr) 270px; gap: 14px; align-items: start; }
.quality-rule-form__section { grid-column: 1; }
.quality-rule-form__review { grid-column: 2; grid-row: 1 / span 5; position: sticky; top: 0; padding: 18px; background: var(--do-soft) !important; border: 0; border-radius: 8px; }
.quality-rule-form__scope { display: flex; flex-wrap: wrap; gap: 5px 16px; padding: 0 0 12px; border-radius: 0; background: transparent; font-size: 13px; color: var(--do-ink); }
.quality-rule-form__review p { overflow-wrap: anywhere; }
.quality-rule-form__scope span { color: var(--do-muted); }
@media (max-width: 950px) { .quality-rule-form :deep(.el-form) { grid-template-columns: minmax(0,1fr); } .quality-rule-form__review { grid-column: 1; grid-row: auto; position: static; } }
.quality-rule-form__parameter-hint { display: block; width: 100%; margin-top: 6px; color: var(--do-muted); font-size: 12px; }
.quality-rule-form__intro { margin: 0 0 14px; color: var(--do-muted); font-size: 13px; line-height: 1.7; }
.quality-rule-form :deep(.quality-check-catalog) { margin-top: 14px; }
:global(.quality-check-catalog-dialog .el-dialog__body) { max-height: calc(88vh - 150px); overflow-y: auto; }
.quality-rule-form__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.quality-rule-form__section { margin: 0; padding: 14px 0; border: 0; border-bottom: 1px solid var(--do-line); border-radius: 0; background: transparent; }
.quality-rule-form__section h3 { margin: 0 0 4px; color: var(--do-ink); font-size: 15px; }
.quality-rule-form__section > p { margin: 0 0 12px; color: var(--do-muted); font-size: 12px; line-height: 1.5; }
.quality-rule-form__target-source { margin: -4px 0 12px !important; }
.quality-rule-form__derived {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 8px 16px;
  margin: 8px 0 12px;
  padding: 10px 12px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
}
.quality-rule-form__derived div { display: grid; gap: 2px; }
.quality-rule-form__derived span { color: var(--do-muted); font-size: 11px; }
.quality-rule-form__derived strong { color: var(--do-ink); font-size: 13px; font-weight: 600; }
.quality-rule-form__context { margin: 0 0 14px; font-size: 12px; color: var(--do-muted); }
.quality-rule-form__context summary,.quality-rule-form__guidance summary { cursor: pointer; font-size: 12px; color: var(--do-muted); }
.quality-rule-form__guidance { margin-top: 14px; }.quality-rule-form__guidance p { margin-top: 8px; }
.quality-rule-form__target-source strong { margin-right: 6px; }
:global(.quality-rule-editor-dialog .el-dialog__body) {
  max-height: calc(90vh - 140px);
  overflow-y: auto;
  padding-right: 8px;
}
.quality-rule-form__preview { margin: 0 0 12px; padding: 10px 12px; border-left: 3px solid var(--do-primary); border-radius: var(--do-radius-sm); color: var(--do-ink); background: var(--do-bg); font-size: 13px; line-height: 1.5; }
.quality-rule-form__preview strong { display: block; margin-bottom: 4px; }
.quality-rule-form__error { margin: 8px 0; color: var(--do-danger); font-size: 13px; }
:global(.quality-rule-detail-drawer .el-drawer__body) { background: var(--do-bg); }
:global(.quality-rule-detail-drawer .el-drawer__header) { margin-bottom: 0; padding-bottom: 12px; }
.quality-rule-detail { display: grid; gap: 16px; }
.quality-rule-detail__header {
  display: grid;
  gap: 16px;
  padding: 0 0 16px;
  border: 0;
  border-bottom: 1px solid var(--do-line);
  border-radius: 0;
  background: var(--do-surface);
}
.quality-rule-detail__heading { display: flex; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; gap: 12px 20px; }
.quality-rule-detail__title-block { display: grid; gap: 5px; min-width: 0; }
.quality-rule-detail__eyebrow { color: var(--do-primary-strong); font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.quality-rule-detail__title-block h2 { margin: 0; color: var(--do-ink); font-size: 20px; line-height: 1.3; overflow-wrap: anywhere; }
.quality-rule-detail__title-block p { margin: 0; color: var(--do-muted); font-size: 12.5px; line-height: 1.5; }
.quality-rule-detail__badges { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.quality-rule-detail__summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 10px 18px;
  margin: 0;
  padding: 12px 14px;
  border-radius: var(--do-radius-md);
  background: color-mix(in srgb, var(--do-surface) 76%, transparent);
}
.quality-rule-detail__summary > div { display: grid; gap: 2px; min-width: 0; }
.quality-rule-detail__summary dt { color: var(--do-muted); font-size: 11px; }
.quality-rule-detail__summary dd { margin: 0; color: var(--do-ink); font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }
.quality-rule-detail__section {
  display: grid;
  gap: 9px;
  padding: 16px 0;
  border: 0;
  border-bottom: 1px solid var(--do-line);
  border-radius: 0;
  background: transparent;
}
.quality-rule-detail__section h3 { margin: 0; color: var(--do-ink); font-size: 14px; }
.quality-rule-detail__condition { margin: 0; padding: 10px 12px; border-radius: var(--do-radius-sm); background: var(--do-bg); }
.quality-rule-detail__section dl { display: grid; gap: 8px; margin: 0; }
.quality-rule-detail__section dl > div { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 12px; }
.quality-rule-detail__section dt { color: var(--do-muted); font-size: 12.5px; }
.quality-rule-detail__section dd { margin: 0; color: var(--do-ink); font-size: 13px; overflow-wrap: anywhere; }
.quality-rule-detail__technical {
  padding: 14px 0;
  border: 0;
  border-top: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  color: var(--do-muted);
  font-size: 13px;
}
.quality-rule-detail__technical summary { cursor: pointer; }
.quality-rule-detail__technical pre { max-height: 220px; overflow: auto; padding: 12px; border-radius: var(--do-radius-sm); color: var(--do-ink); background: var(--do-bg); }
.quality-rule-detail code { color: var(--do-ink); font-size: 13px; overflow-wrap: anywhere; }
.quality-rule-detail p { margin: 0; color: var(--do-muted); font-size: 13px; line-height: 1.5; }
.quality-rule-detail .quality-rule-detail__warning { color: var(--do-warning); }
.quality-rule-detail .quality-rule-detail__note { color: var(--do-muted); }
.quality-rule-detail__history { margin-top: 8px; padding-top: 12px; border-top: 1px solid var(--do-line); }
.quality-rule-detail__history h3 { margin: 0 0 8px; color: var(--do-ink); font-size: 14px; }
.quality-rule-detail__history article { display: grid; gap: 9px; margin-top: 0; padding: 18px 0; border: 0; border-bottom: 1px solid var(--do-line); border-radius: 0; background: transparent; }
.quality-rule-detail__history article > div { display: flex; justify-content: space-between; gap: 12px; }
.quality-rule-detail__history article strong { color: var(--do-ink); font-size: 13px; }
.quality-rule-detail__history span,.quality-rule-detail__history small,.quality-rule-detail__history p,.quality-rule-detail__history > p { margin: 0; color: var(--do-muted); font-size: 12px; }
@media (max-width: 760px) {
  .quality-rules-page__workspace { padding: 0; }
  .quality-rules-page__toolbar-actions { width: 100%; }
  .quality-rule-form__grid { grid-template-columns: minmax(0, 1fr); }
  .quality-rule-detail > div { grid-template-columns: minmax(0, 1fr); gap: 4px; }
  .quality-rule-detail__section dl > div { grid-template-columns: minmax(0, 1fr); gap: 3px; }
}
</style>
