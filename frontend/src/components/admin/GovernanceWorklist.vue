<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { RefreshCw, Sparkles, Pause, ArrowRight, ClipboardCheck } from 'lucide-vue-next'
import { useGovernanceScope } from '../../composables/useGovernanceScope'
import { pauseGovernance, governanceError, discoverGovernance, listGovernanceTasks, retryGovernance, getGovernanceRules, type GovernanceTaskRow } from '../../api/admin/governanceWorkflow'
import { runSemanticCheck } from '../../api/admin/fieldSemantic'
import ErrorState from '../common/ErrorState.vue'
import EmptyState from '../common/EmptyState.vue'
import LoadingState from '../common/LoadingState.vue'
const { context, ready, scopeLoading, scopeIssue, can, capture, isCurrent, generation } = useGovernanceScope()
const router = useRouter()
const rows = ref<GovernanceTaskRow[]>([]), total = ref(0), page = ref(1), loading = ref(false), busy = ref(false), error = ref('')
const summary = ref<{ queued: number; running: number; failed: number }>()
const rules = ref<Array<{ code: string; name: string; target: string; requirement: string; version: number }>>([])
const rulesError = ref(''), rulesLoading = ref(false), rulesOpen = ref<string[]>([]), notice = ref('')
const canView = computed(() => can('governance:field:view'))
const canStart = computed(() => canView.value && can('governance:check') && can('governance:field:manage') && can('knowledge:manage') && can('knowledge:view'))
const canCheck = computed(() => canView.value && can('governance:check'))
const canPause = computed(() => canView.value && can('governance:field:manage'))
function canRetry(row: GovernanceTaskRow) { return canPause.value && (row.targetType !== 'BUSINESS' || can('knowledge:manage')) }
let epoch = 0, operation = 0, rulesRequest = 0, disposed = false, timer: ReturnType<typeof setTimeout> | undefined
const stages: Record<string, string> = { NEEDS_CLASSIFICATION: '待补充标签', NEEDS_GOVERNANCE: '待治理', HUMAN_EDITING: '人工补充', AWAITING_REVIEW: '待审核', AWAITING_RECHECK: '待复查', ROUND_COMPLETED: '本轮完成', NOT_APPLICABLE: '不适用', UNAVAILABLE: '来源或权限待核对' }
const aiLabels: Record<string, string> = { QUEUED: 'AI 待处理', AI_RUNNING: 'AI 处理中', AI_READY: '候选已就绪', AI_FAILED: 'AI 未完成', HUMAN_READY: '人工接管' }
const nextLabels: Record<string, string> = { NEEDS_CLASSIFICATION: '补充标签', AWAITING_REVIEW: '核对并审核', AWAITING_RECHECK: '查看复查依据', ROUND_COMPLETED: '查看记录', NOT_APPLICABLE: '查看依据' }
function canOpen(row: GovernanceTaskRow) { return canView.value && row.stage !== 'UNAVAILABLE' && (row.targetType !== 'BUSINESS' || can('knowledge:view')) }
async function load() {
  const request = ++epoch, scope = capture(), snapshot = context.snapshotId
  if (timer) clearTimeout(timer)
  rows.value = []; total.value = 0; summary.value = undefined; error.value = ''
  if (!canView.value || !snapshot) { loading.value = false; return }
  const current = () => !disposed && request === epoch && isCurrent(scope) && canView.value
  loading.value = true
  try {
    const result = await listGovernanceTasks(snapshot, page.value)
    if (!current()) return
    rows.value = result.data.records; total.value = result.data.total; summary.value = result.data.aiSummary
    if (summary.value && (summary.value.queued || summary.value.running)) timer = setTimeout(load, 5000)
  } catch (cause) { if (current()) error.value = governanceError(cause, '治理任务读取失败，请重试。') || '治理任务读取失败，请重试。' }
  finally { if (current()) loading.value = false }
}
async function mutate(allowed: () => boolean, action: () => Promise<unknown>, success: (result: any) => string) {
  if (busy.value || !allowed()) return
  const request = ++operation, scope = capture()
  const current = () => !disposed && request === operation && isCurrent(scope) && allowed()
  busy.value = true; notice.value = ''
  try {
    const result = await action()
    if (!current()) return
    notice.value = success(result); ElMessage.success(notice.value); await load()
  } catch (cause) { if (current()) { notice.value = governanceError(cause, '') || (cause instanceof Error ? cause.message : '操作未完成，请刷新后核对来源、修订或权限。'); ElMessage.error(notice.value) } }
  finally { if (!disposed && request === operation && isCurrent(scope)) busy.value = false }
}
function start(ai: boolean) {
  const snapshot = context.snapshotId
  if (!snapshot) return
  mutate(() => canStart.value, () => discoverGovernance(snapshot, ai), result => { page.value = 1; return `已扫描 ${result.data.scanned} 个字段，新增 ${result.data.created} 项治理任务；候选仍需人工核对。` })
}
function check() {
  const snapshot = context.snapshotId
  if (!snapshot) return
  mutate(() => canCheck.value, async () => {
    const response = await runSemanticCheck(snapshot)
    if (response.data.snapshotId !== snapshot || response.data.datasourceId !== context.datasourceId) throw new Error('复查结果来源不一致')
    if (response.data.status !== 'SUCCESS') throw new Error(response.data.errorSummary || '语义复查未完整完成，不能视为全部通过')
    return response
  }, () => '语义复查完成，未通过项继续保留；发布和索引状态不变。')
}
function retry(row: GovernanceTaskRow) { const id = row.id; mutate(() => canRetry(row), () => retryGovernance(id), () => '已请求重试此项 AI；请等待后台状态。') }
function pause() { const snapshot = context.snapshotId; if (snapshot) mutate(() => canPause.value, () => pauseGovernance(snapshot), () => '本轮 AI 已暂停，可人工接管；晚到候选由后台处理。') }
async function loadRules(active: unknown) {
  if (!(Array.isArray(active) ? active.length : !!active) || !canView.value || rules.value.length || rulesLoading.value) return
  const request = ++rulesRequest, scope = capture()
  rulesLoading.value = true; rulesError.value = ''
  try { const result = await getGovernanceRules(); if (isCurrent(scope) && request === rulesRequest && canView.value) rules.value = result.data }
  catch { if (isCurrent(scope) && request === rulesRequest) rulesError.value = '检查规则说明读取失败，请重新展开重试。' }
  finally { if (isCurrent(scope) && request === rulesRequest) rulesLoading.value = false }
}
function open(row: GovernanceTaskRow) {
  if (!canOpen(row)) return
  if (row.targetType === 'BUSINESS') { router.push({ path: '/admin/governance/fields', query: { tab: 'business', datasourceId: context.datasourceId, snapshotId: context.snapshotId } }); return }
  if (!row.columnMetaId || !row.tableName) return
  router.push({ path: row.stage === 'NEEDS_CLASSIFICATION' ? '/admin/governance/fields' : '/admin/semantics/field-explanations', query: { ...(row.stage === 'NEEDS_CLASSIFICATION' ? { tab: 'tags' } : {}), datasourceId: context.datasourceId, snapshotId: context.snapshotId, tableName: row.tableName, columnMetaId: row.columnMetaId } })
}
watch([generation, canView], () => {
  epoch++; operation++; rulesRequest++; busy.value = false; loading.value = false; page.value = 1
  rules.value = []; rulesError.value = ''; rulesLoading.value = false; rulesOpen.value = []; notice.value = ''; load()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { disposed = true; epoch++; operation++; rulesRequest++; if (timer) clearTimeout(timer) })
</script>
<template>
  <section class="workflow-list">
    <div class="workflow-actions">
      <div><h2>待办与本轮进展</h2><p>AI 整理建议，人工确认业务含义，再按当前修订复查。</p></div>
      <div class="workflow-actions__buttons">
        <el-button :icon="RefreshCw" :disabled="!canView || busy" @click="load">刷新</el-button>
        <el-button v-if="summary && (summary.queued || summary.running)" :icon="Pause" :disabled="!canPause || busy" @click="pause">暂停 AI</el-button>
        <el-button :icon="ClipboardCheck" :disabled="!canCheck || busy" @click="check">复查治理结果</el-button>
        <el-button :disabled="!canStart || busy" title="发现缺口并创建人工任务；不会暂停已运行的 AI" @click="start(false)">人工接管</el-button>
        <el-button type="primary" :icon="Sparkles" :disabled="!canStart || busy" :loading="busy" @click="start(true)">开始治理</el-button>
      </div>
    </div>
    <p v-if="notice" class="workflow-notice" role="status">{{ notice }}</p>
    <LoadingState v-if="scopeLoading" text="正在核验数据源、快照与能力…" />
    <p v-else-if="!ready" class="workflow-scope-message">{{ scopeIssue }}</p>
    <p v-else-if="!canView" class="workflow-scope-message">没有当前范围的字段查看权限。</p>
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <LoadingState v-else-if="loading && !rows.length" variant="skeleton" :rows="5" />
    <template v-else>
      <div class="workflow-status-line" role="status" aria-atomic="true"><strong>共 {{ total }} 项任务</strong><span v-if="summary">AI 待处理 {{ summary.queued }} · 处理中 {{ summary.running }} · 首轮失败 {{ summary.failed }}</span><span v-else>AI 汇总未返回</span><span>任务数来自当前快照后台统计</span></div>
      <div class="workflow-table">
        <el-table v-if="rows.length" :data="rows" v-loading="loading">
          <el-table-column label="治理对象" min-width="230"><template #default="{ row }"><button class="workflow-object" type="button" :disabled="!canOpen(row)" @click="open(row)">{{ row.targetType === 'BUSINESS' ? row.tableName : `${row.tableName}.${row.columnName}` }}</button><p>{{ row.targetType === 'BUSINESS' ? '业务知识' : '字段解释' }} · {{ row.origin === 'FEEDBACK' ? '来自已确认的问数反馈' : '来自知识完整性检查' }}</p></template></el-table-column>
          <el-table-column label="当前阶段" min-width="130"><template #default="{ row }"><span class="workflow-stage" :data-stage="row.stage">{{ stages[row.stage] || row.stage }}</span></template></el-table-column>
          <el-table-column label="处理提示与下一步" min-width="300"><template #default="{ row }"><span>{{ row.reason }}</span><p>{{ aiLabels[row.aiStatus] || row.aiStatus }}</p></template></el-table-column>
          <el-table-column label="操作" width="200" fixed="right"><template #default="{ row }"><el-button link type="primary" :icon="ArrowRight" :disabled="!canOpen(row)" @click="open(row)">{{ nextLabels[row.stage] || '处理' }}</el-button><el-button v-if="row.aiStatus === 'AI_FAILED'" link :disabled="!canRetry(row) || busy" @click="retry(row)">重试 AI</el-button></template></el-table-column>
        </el-table>
        <EmptyState v-else message="当前快照还没有治理任务。开始治理会发现知识缺口，AI 候选仍需人工确认。" />
        <el-pagination v-if="total > 20" v-model:current-page="page" :total="total" :page-size="20" layout="prev,pager,next,total" @current-change="load" />
      </div>
      <el-collapse v-model="rulesOpen" class="workflow-rules" @change="loadRules"><el-collapse-item title="本次会检查什么 · 规则与边界" name="rules"><p>这里检查业务知识完整性，与来源质量分开；本轮完成仍不表示文档已发布或索引已生效。</p><p v-if="rulesLoading">正在读取规则说明…</p><p v-if="rulesError" role="alert">{{ rulesError }}</p><ul><li v-for="rule in rules" :key="rule.code"><strong>{{ rule.name }}</strong> · {{ rule.target }}：{{ rule.requirement }}</li></ul></el-collapse-item></el-collapse>
    </template>
  </section>
</template>
<style scoped>
.workflow-list { min-width:0; display:grid; gap:16px; }
.workflow-actions { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:16px; }
.workflow-actions h2 { margin:0 0 7px; font-size:18px; font-weight:600; }
.workflow-actions p,.workflow-list :deep(td p) { font-size:12px; color:var(--do-muted); margin:5px 0; line-height:1.6; }
.workflow-actions__buttons { display:flex; flex-wrap:wrap; gap:8px; }
.workflow-actions__buttons :deep(.el-button + .el-button) { margin:0; }
.workflow-status-line { display:flex; flex-wrap:wrap; gap:8px 20px; padding:14px 0; border-top:1px solid var(--do-line); color:var(--do-muted); font-size:12px; }
.workflow-status-line strong { font-size:14px; font-weight:600; color:var(--do-ink); }
.workflow-status-line > span:last-child { margin-left:auto; }
.workflow-table { min-width:0; overflow:hidden; background:var(--do-surface); border:1px solid var(--do-line); border-radius:12px; }
.workflow-table :deep(.el-table) { width:100%; }
.workflow-table :deep(.el-pagination) { padding:14px; justify-content:flex-end; }
.workflow-object { color:var(--do-ink); border:0; background:transparent; padding:0; font:600 13px ui-monospace,Consolas,monospace; text-align:left; cursor:pointer; }
.workflow-object:disabled { cursor:default; color:var(--do-muted); }
.workflow-stage { color:var(--do-primary-strong); font-size:12px; font-weight:600; }
.workflow-stage[data-stage='ROUND_COMPLETED'] { color:var(--do-success); }
.workflow-stage[data-stage='UNAVAILABLE'] { color:var(--do-warning); }
.workflow-notice,.workflow-scope-message { margin:0; padding:14px 16px; border-left:2px solid var(--do-primary); background:var(--do-soft); font-size:13px; line-height:1.7; }
.workflow-rules { border:0; color:var(--do-muted); }
.workflow-rules :deep(.el-collapse-item__header) { background:transparent; color:var(--do-muted); }
.workflow-rules :deep(.el-collapse-item__wrap) { background:transparent; }
@media(max-width:760px) { .workflow-actions__buttons { width:100%; } .workflow-status-line > span:last-child { margin-left:0; } }
</style>
