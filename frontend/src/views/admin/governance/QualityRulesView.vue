<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Copy, Plus, RefreshCw, Trash2 } from 'lucide-vue-next'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
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
import { useRouter } from 'vue-router'
import { buildContextQuery } from '../../../utils/adminNavigation'

type RuleScope = 'GLOBAL' | 'DATASOURCE' | 'TABLE' | 'COLUMN'
type RuleScopeFilter = 'ALL' | RuleScope

const context = useAdminContextStore()
const iamS1 = useIamS1Store()
const router = useRouter()
const RULE_VIEW = 'governance:rule:view'
const RULE_MANAGE = 'governance:rule:manage'
const METADATA_VIEW = 'metadata:view'

const scopeMode = ref<'GLOBAL' | 'DATASOURCE'>('DATASOURCE')
const rules = ref<QualityRule[]>([])
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
const editorOpen = ref(false)
const detailOpen = ref(false)
const editingRuleId = ref<number>()
const detailRule = ref<QualityRule | null>(null)
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
let rulesRequestId = 0
let templateRequestId = 0
let targetRequestId = 0
let detailRequestId = 0
let disposed = false

const canViewRules = computed(() => scopeMode.value === 'GLOBAL'
  ? iamS1.hasGlobal(RULE_VIEW)
  : Boolean(context.datasourceId && iamS1.canOnDatasource(RULE_VIEW, context.datasourceId)))
const canManageSelectedScope = computed(() => scopeMode.value === 'GLOBAL'
  ? iamS1.systemAdmin
  : Boolean(context.datasourceId && iamS1.canOnDatasource(RULE_MANAGE, context.datasourceId)))
const currentTemplate = computed(() => templates.value.find((template) => template.code === form.templateCode))
const isTargetScoped = computed(() => ['TABLE', 'COLUMN'].includes(form.scopeType))
const editorTitle = computed(() => editingRuleId.value ? '编辑质量规则' : '新增质量规则')
const filteredRules = computed(() => rules.value)

function scopeLabel(scope?: string) {
  return ({ GLOBAL: '全局', DATASOURCE: '数据源', TABLE: '表', COLUMN: '字段' } as Record<string, string>)[scope || ''] || '未知范围'
}

function statusType(severity: string) {
  return severity === 'HIGH' ? 'danger' : severity === 'MEDIUM' ? 'warning' : 'info'
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
  }
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
    rules.value = response.data ?? []
  } catch {
    if (!disposed && request === rulesRequestId) rulesError.value = '质量规则读取失败，请重试。'
  } finally {
    if (!disposed && request === rulesRequestId) rulesLoading.value = false
  }
}

async function loadTemplates() {
  const request = ++templateRequestId
  templatesError.value = ''
  if (!canViewRules.value) {
    templates.value = []
    return
  }
  templatesLoading.value = true
  try {
    const response = await listQualityRuleTemplates()
    if (disposed || request !== templateRequestId) return
    templates.value = response.data ?? []
  } catch {
    if (!disposed && request === templateRequestId) templatesError.value = '受支持的检查模板读取失败。'
  } finally {
    if (!disposed && request === templateRequestId) templatesLoading.value = false
  }
}

async function loadPage() {
  pageError.value = ''
  try {
    await Promise.all([context.initialize(), iamS1.load()])
    scopeMode.value = context.datasourceId ? 'DATASOURCE'
      : iamS1.hasGlobal(RULE_VIEW) ? 'GLOBAL' : 'DATASOURCE'
    await Promise.all([loadTemplates(), loadRules()])
  } catch {
    pageError.value = '规则管理范围或权限读取失败，请重试。'
  }
}

async function refresh() {
  await Promise.all([loadTemplates(), loadRules()])
}

function selectScopeMode(mode: 'GLOBAL' | 'DATASOURCE') {
  if (mode === 'GLOBAL' && !iamS1.hasGlobal(RULE_VIEW)) return
  scopeMode.value = mode
  selectedRuleScope.value = mode === 'GLOBAL' ? 'GLOBAL' : 'ALL'
  void Promise.all([loadTemplates(), loadRules()])
}

async function loadRuleTargets(datasourceId: number) {
  const request = ++targetRequestId
  targetLoading.value = true
  targetError.value = ''
  targetTables.value = []
  targetColumns.value = []
  targetSnapshotId.value = undefined
  targetSnapshotVersion.value = undefined
  if (!iamS1.canOnDatasource(METADATA_VIEW, datasourceId)) {
    targetError.value = '没有该数据源的资产结构查看权限，无法选择表或字段。'
    targetLoading.value = false
    return
  }
  try {
    const snapshotsResult = await listSnapshots({ datasourceId, page: 1, size: 50 })
    if (disposed || request !== targetRequestId || datasourceId !== context.datasourceId) return
    const latest = [...(snapshotsResult.data?.records ?? [])]
      .sort((a, b) => b.snapshotVersion - a.snapshotVersion)[0]
    if (!latest) {
      targetError.value = '该数据源没有元数据快照，无法校验目标范围。'
      return
    }
    targetSnapshotId.value = latest.id
    targetSnapshotVersion.value = latest.snapshotVersion
    const tableResult = await listSnapshotTables(latest.id)
    if (disposed || request !== targetRequestId || datasourceId !== context.datasourceId) return
    targetTables.value = tableResult.data ?? []
  } catch {
    if (!disposed && request === targetRequestId) targetError.value = '表和字段目标读取失败，请重试。'
  } finally {
    if (!disposed && request === targetRequestId) targetLoading.value = false
  }
}

async function loadColumns(tableName: string) {
  form.targetTableName = tableName
  form.targetColumnName = ''
  targetColumns.value = []
  if (!tableName || !targetSnapshotId.value) return
  const request = ++targetRequestId
  targetLoading.value = true
  targetError.value = ''
  try {
    const result = await listSnapshotTableColumns(targetSnapshotId.value, tableName)
    if (disposed || request !== targetRequestId || tableName !== form.targetTableName) return
    targetColumns.value = result.data ?? []
  } catch {
    if (!disposed && request === targetRequestId) targetError.value = '字段目标读取失败，请重试。'
  } finally {
    if (!disposed && request === targetRequestId) targetLoading.value = false
  }
}

function resetParameters(templateCode: string) {
  const template = templates.value.find((item) => item.code === templateCode)
  form.parameters = Object.fromEntries((template?.parameters ?? []).map((parameter) => [
    parameter.key,
    parameter.type === 'BOOLEAN' ? Boolean(parameter.defaultValue) : Number(parameter.defaultValue),
  ]))
  if (template && !template.allowedScopes.includes(form.scopeType)) form.scopeType = template.allowedScopes[0] as RuleScope
  form.targetTableName = ''
  form.targetColumnName = ''
  targetColumns.value = []
}

async function changeFormScope(scope: RuleScope) {
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
  if (scope === 'TABLE' || scope === 'COLUMN') {
    if (!context.datasourceId) {
      targetError.value = '请先选择数据源。'
      return
    }
    await loadRuleTargets(context.datasourceId)
  }
}

function resetForm(scope: RuleScope) {
  editingRuleId.value = undefined
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
}

function createRule() {
  if (!canManageSelectedScope.value) {
    ElMessage.warning(scopeMode.value === 'GLOBAL' ? '全局规则仅受保护系统管理员可以维护。' : '当前账号没有维护此数据源规则的权限。')
    return
  }
  const initialScope: RuleScope = scopeMode.value === 'GLOBAL' ? 'GLOBAL' : 'DATASOURCE'
  resetForm(initialScope)
  editorOpen.value = true
}

async function editRule(rule: QualityRule) {
  if (!canManageRule(rule) || !rule.supported) return
  const template = templates.value.find((item) => item.code === rule.templateCode)
  if (!template) {
    ElMessage.error('当前规则没有可用的编辑模板。')
    return
  }
  resetForm(rule.scopeType)
  editingRuleId.value = rule.id
  form.ruleName = rule.ruleName
  form.templateCode = rule.templateCode || ''
  form.description = rule.description || ''
  form.scopeType = rule.scopeType
  form.datasourceId = rule.datasourceId
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
  if (rule.scopeType === 'TABLE' || rule.scopeType === 'COLUMN') {
    if (rule.datasourceId) await loadRuleTargets(rule.datasourceId)
    if (rule.scopeType === 'COLUMN' && rule.targetTableName) await loadColumns(rule.targetTableName)
    form.targetTableName = rule.targetTableName || ''
    form.targetColumnName = rule.targetColumnName || ''
  }
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
  if (!template || !form.ruleName.trim()) {
    ElMessage.warning('请填写规则名称并选择检查类型。')
    return
  }
  if ((form.scopeType === 'TABLE' || form.scopeType === 'COLUMN') && !form.targetTableName) {
    ElMessage.warning('请选择实际元数据表。')
    return
  }
  if (form.scopeType === 'COLUMN' && !form.targetColumnName) {
    ElMessage.warning('请选择实际元数据字段。')
    return
  }
  if (targetError.value && isTargetScoped.value) {
    ElMessage.warning(targetError.value)
    return
  }
  saveLoading.value = true
  try {
    if (editingRuleId.value) await editQualityRule(editingRuleId.value, payload())
    else await createQualityRule(payload())
    editorOpen.value = false
    ElMessage.success(editingRuleId.value ? '规则配置已保存并生成新版本。' : '规则已创建并进入校验执行链。')
    await loadRules()
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '规则保存失败。')
  } finally {
    saveLoading.value = false
  }
}

async function copyRule(rule: QualityRule) {
  if (!canManageRule(rule)) return
  try {
    await copyQualityRule(rule.id)
    ElMessage.success('规则副本已创建，默认停用。')
    await loadRules()
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
    await loadRules()
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '规则状态更新失败。')
    await loadRules()
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
    await loadRules()
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
    await loadRules()
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '恢复规则失败。')
  } finally {
    rowLoading[rule.id] = false
  }
}

async function openDetail(rule: QualityRule) {
  const request = ++detailRequestId
  detailRule.value = rule
  detailHistory.value = []
  detailRevisions.value = []
  detailError.value = ''
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

function openStatusMaintenance() {
  router.push({
    path: '/admin/governance/status',
    query: buildContextQuery('datasource-snapshot', {
      datasourceId: context.datasourceId,
      snapshotId: context.snapshotId,
    }),
  })
}

watch([scopeMode, selectedRuleScope, templateFilter, dimensionFilter, ruleNameFilter, enabledFilter, showDeleted], () => { void loadRules() })
watch(() => context.datasourceId, async () => {
  if (scopeMode.value === 'DATASOURCE') await loadRules()
  if (editorOpen.value && isTargetScoped.value && context.datasourceId) await loadRuleTargets(context.datasourceId)
})
onMounted(loadPage)
onBeforeUnmount(() => {
  disposed = true
  rulesRequestId++
  templateRequestId++
  targetRequestId++
  detailRequestId++
})
</script>

<template>
  <main class="quality-rules-page post-login-page">
    <TaskPageHeader title="质量规则" description="使用已注册的检查模板创建可执行规则。全局规则适用于所有数据源；来源范围规则按稳定的表名和字段名解析到后续快照。" />

    <ErrorState v-if="pageError" :message="pageError" @retry="loadPage" />
    <section v-else class="quality-rules-page__workspace">
      <div class="quality-rules-page__toolbar">
        <div class="quality-rules-page__scope-switch" role="group" aria-label="规则管理范围">
          <button type="button" :class="{ active: scopeMode === 'DATASOURCE' }" @click="selectScopeMode('DATASOURCE')">数据源规则</button>
          <button type="button" :class="{ active: scopeMode === 'GLOBAL' }" :disabled="!iamS1.hasGlobal(RULE_VIEW)" :title="!iamS1.hasGlobal(RULE_VIEW) ? '没有查看全局规则的权限' : '全局规则只允许受保护系统管理员维护'" @click="selectScopeMode('GLOBAL')">全局规则</button>
        </div>
        <div class="quality-rules-page__toolbar-actions">
          <el-button @click="openStatusMaintenance">资产状态维护</el-button>
          <el-button :icon="RefreshCw" :loading="rulesLoading || templatesLoading" @click="refresh">刷新</el-button>
          <el-button type="primary" :icon="Plus" :disabled="!canManageSelectedScope || templatesLoading" @click="createRule">新增规则</el-button>
        </div>
      </div>

      <p v-if="scopeMode === 'GLOBAL'" class="quality-rules-page__scope-note">
        全局规则不受上方快照范围限制，会影响所有数据源；只有受保护系统管理员可以创建和修改。
      </p>
      <p v-else-if="!context.datasourceId" class="quality-rules-page__scope-note">请先选择数据源以查看其规则。</p>
      <p v-else-if="!canManageSelectedScope" class="quality-rules-page__scope-note">当前账号没有此数据源的规则管理权限；仍可按查看权限浏览规则。</p>

      <div class="quality-rules-page__filters">
        <el-input v-model="ruleNameFilter" clearable placeholder="搜索规则名称" aria-label="搜索规则名称" style="width: 190px" />
        <el-select v-model="selectedRuleScope" aria-label="规则范围筛选" :disabled="scopeMode === 'GLOBAL'" style="width: 150px">
          <el-option label="全部可见范围" value="ALL" />
          <el-option label="全局" value="GLOBAL" />
          <el-option label="数据源" value="DATASOURCE" />
          <el-option label="表" value="TABLE" />
          <el-option label="字段" value="COLUMN" />
        </el-select>
        <el-select v-model="templateFilter" clearable filterable placeholder="检查类型" style="width: 240px">
          <el-option v-for="template in templates" :key="template.code" :label="template.name" :value="template.code" />
        </el-select>
        <el-select v-model="dimensionFilter" clearable placeholder="治理维度" style="width: 160px">
          <el-option v-for="dimension in ['COMPLETENESS', 'ACCURACY', 'CONSISTENCY', 'TIMELINESS', 'TRACEABILITY']" :key="dimension" :label="qualityDimensionLabel(dimension)" :value="dimension" />
        </el-select>
        <el-select v-model="enabledFilter" aria-label="启用状态" style="width: 140px">
          <el-option label="全部状态" value="ALL" />
          <el-option label="已启用" value="ENABLED" />
          <el-option label="已停用" value="DISABLED" />
        </el-select>
        <el-checkbox v-model="showDeleted">查看已删除</el-checkbox>
      </div>

      <ErrorState v-if="templatesError" :message="templatesError" @retry="loadTemplates" />
      <ErrorState v-if="rulesError" :message="rulesError" @retry="loadRules" />
      <p v-else-if="!canViewRules" class="quality-rules-page__permission">没有当前范围的“查看治理规则”权限。</p>
      <div v-else v-loading="rulesLoading || templatesLoading" class="quality-rules-page__table-wrap">
        <el-table :data="filteredRules" row-key="id" empty-text="当前筛选范围没有质量规则">
          <el-table-column prop="ruleName" label="规则名称" min-width="200">
            <template #default="{ row }">
              <button class="quality-rules-page__name-link" type="button" @click="openDetail(row)">{{ row.ruleName }}</button>
              <el-tag v-if="!row.supported" size="small" type="danger">无执行模板</el-tag>
              <el-tag v-else-if="row.overridden" size="small" type="warning" :title="`受规则「${row.overriddenBy}」覆盖`">有覆盖</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="检查类型" min-width="190">
            <template #default="{ row }">{{ templates.find((template) => template.code === row.templateCode)?.name || row.templateCode || '未映射模板' }}</template>
          </el-table-column>
          <el-table-column label="治理维度" width="112">
            <template #default="{ row }">{{ qualityDimensionLabel(row.dimension) }}</template>
          </el-table-column>
          <el-table-column label="生效范围" min-width="190">
            <template #default="{ row }">
              <span>{{ scopeLabel(row.scopeType) }}</span>
              <small v-if="row.datasourceId"> · {{ context.datasources.find((item) => item.id === row.datasourceId)?.name || `数据源 #${row.datasourceId}` }}</small>
              <small v-if="row.targetTableName"> · {{ row.targetTableName }}</small>
              <small v-if="row.targetColumnName">.{{ row.targetColumnName }}</small>
            </template>
          </el-table-column>
          <el-table-column label="级别 / 扣分" width="148">
            <template #default="{ row }"><el-tag size="small" :type="statusType(row.severity)">{{ severityLabel(row.severity) }}</el-tag><span class="quality-rules-page__deduction">−{{ row.deductionPoints }}</span></template>
          </el-table-column>
          <el-table-column label="版本" width="76" align="center"><template #default="{ row }">v{{ row.configVersion }}</template></el-table-column>
          <el-table-column label="启用" width="88">
            <template #default="{ row }">
              <el-switch v-if="!showDeleted && row.supported" :model-value="row.enabled === 1" :loading="rowLoading[row.id]" :disabled="!canManageRule(row)" :aria-label="`${row.ruleName}启用状态`" @change="toggleRule(row)" />
              <span v-else-if="showDeleted">已删除</span>
              <span v-else>不可执行</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="250" fixed="right">
            <template #default="{ row }">
              <div class="quality-rules-page__row-actions">
                <el-button v-if="showDeleted" link type="primary" :disabled="!canManageRule(row)" :loading="rowLoading[row.id]" @click="restoreRule(row)">恢复</el-button>
                <template v-else>
                  <el-button link @click="openDetail(row)">详情</el-button>
                  <el-button v-if="row.supported" link type="primary" :disabled="!canManageRule(row)" @click="editRule(row)">编辑</el-button>
                  <el-button v-if="row.supported" link :disabled="!canManageRule(row)" @click="copyRule(row)"><Copy :size="14" aria-hidden="true" />复制</el-button>
                  <el-button link type="danger" :disabled="!canManageRule(row)" :loading="rowLoading[row.id]" @click="removeRule(row)"><Trash2 :size="14" aria-hidden="true" />删除</el-button>
                </template>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <el-dialog v-model="editorOpen" :title="editorTitle" width="min(880px, calc(100vw - 32px))" append-to-body destroy-on-close>
      <div class="quality-rule-form">
        <el-form label-position="top">
          <div class="quality-rule-form__grid">
            <el-form-item label="规则名称" required><el-input v-model="form.ruleName" maxlength="200" show-word-limit /></el-form-item>
            <el-form-item label="检查类型" required>
              <el-select v-model="form.templateCode" filterable :loading="templatesLoading" @change="resetParameters">
                <el-option v-for="template in templates" :key="template.code" :label="template.name" :value="template.code" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" /></el-form-item>

          <section v-if="currentTemplate" class="quality-rule-form__section">
            <h3>配置目标和条件</h3>
            <p>{{ currentTemplate.description }} · {{ qualityDimensionLabel(currentTemplate.dimension) }} · {{ currentTemplate.checkType === 'DATA' ? '只读数据级检查' : '元数据检查' }}</p>
            <div class="quality-rule-form__grid">
              <el-form-item label="作用范围" required>
                <el-select :model-value="form.scopeType" @change="(value: RuleScope) => changeFormScope(value)">
                  <el-option v-for="scope in currentTemplate.allowedScopes" :key="scope" :label="scopeLabel(scope)" :value="scope" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.scopeType !== 'GLOBAL'" label="数据源" required>
                <el-input :model-value="context.currentDatasource?.name || ''" readonly placeholder="请先在范围栏选择数据源" />
              </el-form-item>
              <el-form-item v-if="form.scopeType === 'TABLE' || form.scopeType === 'COLUMN'" label="目标表" required>
                <el-select v-model="form.targetTableName" filterable :loading="targetLoading" :disabled="!targetTables.length" @change="loadColumns">
                  <el-option v-for="table in targetTables" :key="table.tableName" :label="table.tableComment ? `${table.tableName} · ${table.tableComment}` : table.tableName" :value="table.tableName" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.scopeType === 'COLUMN'" label="目标字段" required>
                <el-select v-model="form.targetColumnName" filterable :loading="targetLoading" :disabled="!targetColumns.length">
                  <el-option v-for="column in targetColumns" :key="column.columnName" :label="column.columnComment ? `${column.columnName} · ${column.columnComment}` : `${column.columnName} · ${column.dataType}`" :value="column.columnName" />
                </el-select>
              </el-form-item>
            </div>
            <p v-if="isTargetScoped && targetSnapshotVersion" class="quality-rule-form__target-source">目标来自数据源当前快照 v{{ targetSnapshotVersion }}；规则保存稳定名称，执行时按数据源和对象名称重新解析。</p>
            <p v-if="targetError" class="quality-rule-form__error" role="alert">{{ targetError }}</p>
            <div v-if="currentTemplate.parameters.length" class="quality-rule-form__grid">
              <el-form-item v-for="parameter in currentTemplate.parameters" :key="parameter.key" :label="parameter.label" required>
                <el-switch v-if="parameter.type === 'BOOLEAN'" v-model="form.parameters[parameter.key]" />
                <el-input-number v-else v-model="form.parameters[parameter.key]" :min="parameter.minimum" :max="parameter.maximum" :step="parameter.type === 'INTEGER' ? 1 : 0.01" :precision="parameter.type === 'INTEGER' ? 0 : 4" />
              </el-form-item>
            </div>
            <p v-else class="quality-rule-form__target-source">此模板使用固定的检查处理逻辑，不开放 SQL、脚本或自由表达式参数。</p>
          </section>

          <section v-if="currentTemplate" class="quality-rule-form__section">
            <h3>设置严重程度与扣分</h3>
            <div class="quality-rule-form__grid">
              <el-form-item label="严重程度" required>
                <el-select v-model="form.severity"><el-option label="高" value="HIGH" /><el-option label="中" value="MEDIUM" /><el-option label="低" value="LOW" /></el-select>
              </el-form-item>
              <el-form-item label="扣分" required><el-input-number v-model="form.deductionPoints" :min="0" :max="100" :step="0.5" :precision="1" /></el-form-item>
            </div>
            <el-checkbox v-model="form.enabled">保存后启用</el-checkbox>
          </section>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="editorOpen = false">取消</el-button>
        <el-button type="primary" :loading="saveLoading" :disabled="!canManageSelectedScope" @click="saveRule">保存规则</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailOpen" title="质量规则详情" width="min(760px, calc(100vw - 32px))" append-to-body @closed="detailRequestId++">
      <div v-if="detailRule" class="quality-rule-detail">
        <div><span>规则名称</span><strong>{{ detailRule.ruleName }}</strong></div>
        <div><span>检查模板</span><strong>{{ templates.find((item) => item.code === detailRule?.templateCode)?.name || detailRule.templateCode || '未映射模板' }}</strong></div>
        <div><span>生效范围</span><strong>{{ scopeLabel(detailRule.scopeType) }}<template v-if="detailRule.datasourceId"> · {{ context.datasources.find((item) => item.id === detailRule?.datasourceId)?.name || `数据源 #${detailRule.datasourceId}` }}</template><template v-if="detailRule.targetTableName"> · {{ detailRule.targetTableName }}</template><template v-if="detailRule.targetColumnName">.{{ detailRule.targetColumnName }}</template></strong></div>
        <div><span>治理维度</span><strong>{{ qualityDimensionLabel(detailRule.dimension) }} · {{ severityLabel(detailRule.severity) }} · 扣 {{ detailRule.deductionPoints }} 分</strong></div>
        <div><span>配置版本</span><strong>v{{ detailRule.configVersion }}</strong></div>
        <div><span>参数</span><code>{{ JSON.stringify(detailRule.parameters || {}) }}</code></div>
        <p v-if="detailRule.description">{{ detailRule.description }}</p>
        <p v-if="detailRule.overridden" class="quality-rule-detail__warning">存在更具体的启用规则「{{ detailRule.overriddenBy }}」覆盖部分目标。优先级为字段 → 表 → 数据源 → 全局。</p>
        <p v-else class="quality-rule-detail__note">执行时按字段 → 表 → 数据源 → 全局确定唯一生效实例，不对同一检查重复扣分。</p>
        <p v-if="!detailRule.supported" class="quality-rule-form__error">{{ detailRule.unsupportedReason || '此规则无法映射到当前可执行模板。' }}</p>
        <ErrorState v-if="detailError" :message="detailError" />
        <div v-else-if="detailLoading" class="quality-rule-detail__note" role="status">正在读取执行历史…</div>
        <section v-else class="quality-rule-detail__history">
          <h3>实际执行版本</h3>
          <template v-if="detailHistory.length">
            <article v-for="entry in detailHistory" :key="`${entry.batchId}-${entry.ruleVersion}`">
              <div><strong>v{{ entry.ruleVersion }} · {{ entry.batchStatus }}</strong><span>{{ formatRuleTime(entry.batchStartedAt) }}</span></div>
              <small>{{ scopeLabel(entry.scopeType) }} · {{ entry.status }} · 检查 {{ entry.checkedTargetCount }}/{{ entry.targetCount }} 个目标 · {{ entry.issueCount }} 个问题</small>
              <p v-if="entry.reason">{{ entry.reason }}</p>
            </article>
          </template>
          <p v-else>尚无带配置快照的执行记录。早期未关联批次的结果无法还原当时规则依据。</p>
        </section>
        <section v-if="!detailLoading && !detailError" class="quality-rule-detail__history">
          <h3>配置变更历史</h3>
          <template v-if="detailRevisions.length">
            <article v-for="revision in detailRevisions" :key="revision.id">
              <div><strong>v{{ revision.configVersion }} · {{ revisionActionLabel(revision.action) }}</strong><span>{{ formatRuleTime(revision.changedAt) }}</span></div>
              <small>{{ revision.ruleName }} · {{ scopeLabel(revision.scopeType) }} · {{ severityLabel(revision.severity) }} · 扣 {{ revision.deductionPoints }} 分 · {{ revision.enabled === 1 ? '启用' : '停用' }}<template v-if="revision.deleted"> · 已删除</template></small>
              <p>{{ JSON.stringify(revision.parameters || {}) }}</p>
            </article>
            <p v-if="detailRevisions.some((revision) => revision.action === 'BASELINE')">迁移基线只记录迁移时可核对的配置，不代表更早的变更流水。</p>
          </template>
          <p v-else>规则配置历史从新版本模型启用后开始记录；更早变更需查现有审计记录。</p>
        </section>
      </div>
      <template #footer><el-button @click="detailOpen = false">关闭</el-button></template>
    </el-dialog>
  </main>
</template>

<style scoped>
.quality-rules-page { display: grid; gap: 16px; min-width: 0; }
.quality-rules-page__workspace { min-width: 0; padding: 18px 20px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); }
.quality-rules-page__toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.quality-rules-page__scope-switch { display: inline-flex; gap: 4px; padding: 3px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); background: var(--do-bg); }
.quality-rules-page__scope-switch button { min-height: 32px; padding: 0 12px; border: 0; border-radius: var(--do-radius-sm); color: var(--do-muted); background: transparent; cursor: pointer; font-size: 13px; }
.quality-rules-page__scope-switch button.active { color: var(--do-primary-strong); background: var(--do-surface); box-shadow: var(--do-shadow-sm); }
.quality-rules-page__scope-switch button:disabled { cursor: not-allowed; opacity: .5; }
.quality-rules-page__toolbar-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.quality-rules-page__scope-note { margin: 12px 0 0; color: var(--do-muted); font-size: 12px; line-height: 1.5; }
.quality-rules-page__filters { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 16px 0 12px; }
.quality-rules-page__permission { padding: 20px 0; color: var(--do-muted); }
.quality-rules-page__table-wrap { min-width: 0; overflow-x: auto; }
.quality-rules-page__name-link { margin-right: 8px; border: 0; padding: 0; color: var(--do-primary-strong); background: transparent; cursor: pointer; font: inherit; font-weight: 600; }
.quality-rules-page__deduction { margin-left: 8px; color: var(--do-muted); font-size: 12px; }
.quality-rules-page__row-actions { display: flex; align-items: center; gap: 4px 10px; white-space: nowrap; }
.quality-rule-form { max-height: min(68vh, 720px); overflow-y: auto; padding: 0 4px; }
.quality-rule-form__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.quality-rule-form__section { margin-top: 8px; padding-top: 14px; border-top: 1px solid var(--do-line); }
.quality-rule-form__section h3 { margin: 0 0 4px; color: var(--do-ink); font-size: 15px; }
.quality-rule-form__section > p { margin: 0 0 12px; color: var(--do-muted); font-size: 12px; line-height: 1.5; }
.quality-rule-form__target-source { margin: -4px 0 12px !important; }
.quality-rule-form__error { margin: 8px 0; color: var(--do-danger); font-size: 13px; }
.quality-rule-detail { display: grid; gap: 12px; }
.quality-rule-detail > div { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 12px; }
.quality-rule-detail > div > span { color: var(--do-muted); font-size: 13px; }
.quality-rule-detail > div > strong,.quality-rule-detail code { color: var(--do-ink); font-size: 13px; overflow-wrap: anywhere; }
.quality-rule-detail p { margin: 0; color: var(--do-muted); font-size: 13px; line-height: 1.5; }
.quality-rule-detail .quality-rule-detail__warning { color: var(--do-warning); }
.quality-rule-detail .quality-rule-detail__note { color: var(--do-muted); }
.quality-rule-detail__history { margin-top: 8px; padding-top: 12px; border-top: 1px solid var(--do-line); }
.quality-rule-detail__history h3 { margin: 0 0 8px; color: var(--do-ink); font-size: 14px; }
.quality-rule-detail__history article { display: grid; gap: 4px; padding: 10px 0; border-top: 1px solid var(--do-line); }
.quality-rule-detail__history article > div { display: flex; justify-content: space-between; gap: 12px; }
.quality-rule-detail__history article strong { color: var(--do-ink); font-size: 13px; }
.quality-rule-detail__history span,.quality-rule-detail__history small,.quality-rule-detail__history p,.quality-rule-detail__history > p { margin: 0; color: var(--do-muted); font-size: 12px; }
@media (max-width: 760px) {
  .quality-rules-page__workspace { padding: 14px 12px; }
  .quality-rules-page__toolbar-actions { width: 100%; }
  .quality-rule-form__grid { grid-template-columns: minmax(0, 1fr); }
  .quality-rule-detail > div { grid-template-columns: minmax(0, 1fr); gap: 4px; }
}
</style>
