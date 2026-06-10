<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CheckCircle2, History, RefreshCw, RotateCcw, Save, Search, Send, TrendingUp, XCircle } from 'lucide-vue-next'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import {
  approvePrompt,
  getPromptEffectiveness,
  getPromptTemplate,
  getPromptVersions,
  listPromptTemplates,
  PROMPT_STATUS_MAP,
  rejectPrompt,
  rollbackPromptVersion,
  submitPromptForReview,
  updatePromptTemplate,
  type PromptEffectivenessVO,
  type PromptStatus,
  type PromptTemplateVO,
  type PromptVersionVO,
} from '../../../api/admin/prompt'

const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)

/** 模板编码 → Agent 节点映射（前端硬编码，与 Python 端保持一致） */
const NODE_LABEL_MAP: Record<string, { label: string; connected: boolean }> = {
  sql_generation:     { label: 'SQL 生成节点',   connected: true },
  chart_generation:   { label: '图表生成节点',   connected: true },
  intent_recognition: { label: '意图识别节点',   connected: true },
}

function getNodeInfo(code: string) {
  return NODE_LABEL_MAP[code] || { label: '', connected: false }
}

const loading = ref(false)
const saving = ref(false)
const submitting = ref(false)
const approving = ref(false)
const rejecting = ref(false)
const versionLoading = ref(false)
const analysisLoading = ref(false)
const templates = ref<PromptTemplateVO[]>([])
const versions = ref<PromptVersionVO[]>([])
const effectiveness = ref<PromptEffectivenessVO[]>([])
const activeCode = ref('')
const activeTab = ref('editor')
const keyword = ref('')
const analysisDays = ref(30)
const editContent = ref('')
const changeSummary = ref('')

/** 获取状态显示信息 */
function getStatusInfo(status: PromptStatus) {
  return PROMPT_STATUS_MAP[status] || { label: status, type: 'info' as const }
}

const selectedTemplate = computed(() => templates.value.find((item) => item.templateCode === activeCode.value))
const filteredTemplates = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) return templates.value
  return templates.value.filter((item) =>
    [item.templateCode, item.templateName, item.scenario].some((value) => value?.toLowerCase().includes(query)),
  )
})

const activeEffectiveness = computed(() =>
  effectiveness.value.filter((item) => !activeCode.value || item.templateCode === activeCode.value),
)

const summaryStats = computed(() => {
  const rows = activeEffectiveness.value
  const totalQueries = rows.reduce((sum, row) => sum + row.totalQueries, 0)
  const weightedSuccess = rows.reduce((sum, row) => sum + row.successRate * row.totalQueries, 0)
  const avgSuccess = totalQueries ? weightedSuccess / totalQueries : 0
  const avgTime = rows.length ? rows.reduce((sum, row) => sum + row.avgExecutionTimeMs, 0) / rows.length : 0
  const feedbackCount = rows.reduce((sum, row) => sum + row.feedbackCount, 0)
  return { totalQueries, avgSuccess, avgTime, feedbackCount }
})

function extractError(error: unknown, fallback: string): string {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const msg = (error as any).response?.data?.message
    if (typeof msg === 'string') return msg
  }
  return fallback
}

function formatPercent(value?: number) {
  return `${Number(value || 0).toFixed(1)}%`
}

function formatTime(value?: number) {
  const ms = Number(value || 0)
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${ms.toFixed(0)}ms`
}

async function fetchTemplates(preferredCode?: string) {
  loading.value = true
  try {
    const res = await listPromptTemplates({ page: 1, pageSize: 100 })
    templates.value = res.data?.records ?? []
    const nextCode = preferredCode || activeCode.value || templates.value[0]?.templateCode || ''
    if (nextCode) {
      await selectTemplate(nextCode)
    }
  } catch (error) {
    ElMessage.error(extractError(error, '加载 Prompt 模板失败'))
  } finally {
    loading.value = false
  }
}

async function selectTemplate(code: string) {
  activeCode.value = code
  try {
    const res = await getPromptTemplate(code)
    const detail = res.data
    const index = templates.value.findIndex((item) => item.templateCode === code)
    if (detail && index >= 0) {
      templates.value.splice(index, 1, detail)
    }
    editContent.value = detail?.content || ''
    changeSummary.value = ''
    await Promise.all([fetchVersions(code), fetchEffectiveness()])
  } catch (error) {
    ElMessage.error(extractError(error, '加载模板详情失败'))
  }
}

async function fetchVersions(code = activeCode.value) {
  if (!code) return
  versionLoading.value = true
  try {
    const res = await getPromptVersions(code)
    versions.value = res.data ?? []
  } catch (error) {
    ElMessage.error(extractError(error, '加载版本历史失败'))
  } finally {
    versionLoading.value = false
  }
}

async function fetchEffectiveness() {
  analysisLoading.value = true
  try {
    const res = await getPromptEffectiveness(analysisDays.value)
    effectiveness.value = res.data ?? []
  } catch (error) {
    effectiveness.value = []
    ElMessage.error(extractError(error, '加载效果分析失败'))
  } finally {
    analysisLoading.value = false
  }
}

async function handleSave() {
  if (!activeCode.value || !editContent.value.trim()) {
    ElMessage.warning('请先选择模板并填写内容')
    return
  }
  saving.value = true
  try {
    const res = await updatePromptTemplate(activeCode.value, {
      content: editContent.value,
      changeSummary: changeSummary.value || '后台手动更新',
    })
    ElMessage.success('Prompt 模板已保存')
    const updated = res.data
    const index = templates.value.findIndex((item) => item.templateCode === activeCode.value)
    if (updated && index >= 0) templates.value.splice(index, 1, updated)
    changeSummary.value = ''
    await Promise.all([fetchVersions(), fetchEffectiveness()])
  } catch (error) {
    ElMessage.error(extractError(error, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function handleRollback(versionNo: number) {
  if (!activeCode.value) return
  try {
    await ElMessageBox.confirm(`确认基于 v${versionNo} 生成回滚草稿？提交审核通过后才会发布。`, '版本回滚')
    const res = await rollbackPromptVersion(activeCode.value, versionNo)
    ElMessage.success(`已基于 v${versionNo} 生成草稿`)
    const updated = res.data
    const index = templates.value.findIndex((item) => item.templateCode === activeCode.value)
    if (updated && index >= 0) templates.value.splice(index, 1, updated)
    editContent.value = updated?.content || editContent.value
    await Promise.all([fetchVersions(), fetchEffectiveness()])
  } catch (error) {
    if (error === 'cancel') return
    ElMessage.error(extractError(error, '回滚失败'))
  }
}

/** 提交审核 */
async function handleSubmitForReview() {
  if (!activeCode.value) return
  try {
    await ElMessageBox.confirm('确认提交审核？提交后将无法编辑，直到审核完成。', '提交审核')
    submitting.value = true
    const res = await submitPromptForReview(activeCode.value)
    ElMessage.success('已提交审核')
    const updated = res.data
    const index = templates.value.findIndex((item) => item.templateCode === activeCode.value)
    if (updated && index >= 0) templates.value.splice(index, 1, updated)
    await fetchVersions()
  } catch (error) {
    if (error === 'cancel') return
    ElMessage.error(extractError(error, '提交审核失败'))
  } finally {
    submitting.value = false
  }
}

/** 审核通过 */
async function handleApprove() {
  if (!activeCode.value) return
  try {
    await ElMessageBox.confirm('确认审核通过？通过后模板将自动启用。', '审核通过')
    approving.value = true
    const res = await approvePrompt(activeCode.value, changeSummary.value || undefined)
    ElMessage.success('审核已通过')
    const updated = res.data
    const index = templates.value.findIndex((item) => item.templateCode === activeCode.value)
    if (updated && index >= 0) templates.value.splice(index, 1, updated)
    changeSummary.value = ''
    await Promise.all([fetchVersions(), fetchEffectiveness()])
  } catch (error) {
    if (error === 'cancel') return
    ElMessage.error(extractError(error, '审核失败'))
  } finally {
    approving.value = false
  }
}

/** 审核拒绝 */
async function handleReject() {
  if (!activeCode.value) return
  try {
    const { value } = await ElMessageBox.prompt('请输入拒绝原因', '审核拒绝', {
      inputType: 'textarea',
      inputPlaceholder: '请输入拒绝原因（必填）',
      inputValidator: (value) => value.trim().length > 0 || '拒绝原因不能为空',
    })
    rejecting.value = true
    const res = await rejectPrompt(activeCode.value, value)
    ElMessage.success('已拒绝')
    const updated = res.data
    const index = templates.value.findIndex((item) => item.templateCode === activeCode.value)
    if (updated && index >= 0) templates.value.splice(index, 1, updated)
    await fetchVersions()
  } catch (error) {
    if (error === 'cancel') return
    ElMessage.error(extractError(error, '拒绝失败'))
  } finally {
    rejecting.value = false
  }
}

watch(analysisDays, () => {
  fetchEffectiveness()
})

onMounted(() => {
  withContext(() => {
    reveal('.page-header, .template-sidebar, .workspace-panel, .metric-card', { y: 14, stagger: 0.05 })
  })
  fetchTemplates()
})
</script>

<template>
  <main ref="pageRef" class="prompt-page post-login-page">
    <header class="page-header">
      <div>
        <p>AI 调优</p>
        <h1>Prompt 管理</h1>
        <span class="header-subtitle">维护 Agent 模板版本，并按审计结果查看查询表现。</span>
      </div>
      <div class="header-actions">
        <el-select v-model="analysisDays" style="width: 128px">
          <el-option label="近 7 天" :value="7" />
          <el-option label="近 30 天" :value="30" />
          <el-option label="近 90 天" :value="90" />
        </el-select>
        <el-button :loading="loading || analysisLoading" @click="fetchTemplates(activeCode)">
          <RefreshCw :size="16" />
        </el-button>
      </div>
    </header>

    <section class="prompt-layout">
      <aside class="template-sidebar">
        <el-input v-model="keyword" placeholder="搜索模板" clearable>
          <template #prefix><Search :size="15" /></template>
        </el-input>
        <div v-loading="loading" class="template-list">
          <button
            v-for="item in filteredTemplates"
            :key="item.templateCode"
            class="template-item"
            :class="{ active: item.templateCode === activeCode }"
            type="button"
            @click="selectTemplate(item.templateCode)"
          >
            <span class="template-item-main">
              <strong>{{ item.templateName }}</strong>
              <small>{{ item.templateCode }}</small>
              <span class="template-item-tags">
                <el-tag
                  size="small"
                  :type="getStatusInfo(item.status).type"
                  effect="plain"
                >
                  {{ getStatusInfo(item.status).label }}
                </el-tag>
                <el-tag
                  size="small"
                  :type="getNodeInfo(item.templateCode).connected ? 'success' : 'warning'"
                  effect="plain"
                >
                  {{ getNodeInfo(item.templateCode).connected ? '✅ 使用中' : '⚠️ 未接入' }}
                </el-tag>
                <el-tag v-if="getNodeInfo(item.templateCode).label" size="small" effect="plain" type="info">
                  {{ getNodeInfo(item.templateCode).label }}
                </el-tag>
              </span>
            </span>
            <el-tag size="small" effect="plain">v{{ item.currentVersion }}</el-tag>
          </button>
          <el-empty v-if="!filteredTemplates.length && !loading" description="暂无模板" />
        </div>
      </aside>

      <section class="workspace-panel">
        <div v-if="selectedTemplate" class="template-meta">
          <div>
            <strong>{{ selectedTemplate.templateName }}</strong>
            <span class="template-meta-scenario">{{ selectedTemplate.scenario }}</span>
            <span v-if="getNodeInfo(selectedTemplate.templateCode).connected" class="template-meta-hint template-meta-hint--active">
              此模板已被「{{ getNodeInfo(selectedTemplate.templateCode).label }}」实时调用，修改后立即生效
            </span>
            <span v-else class="template-meta-hint template-meta-hint--inactive">
              此模板尚未接入 Agent 流程，修改不会影响查询结果
            </span>
          </div>
          <div class="template-tags">
            <el-tag size="small" :type="getStatusInfo(selectedTemplate.status).type">
              {{ getStatusInfo(selectedTemplate.status).label }}
            </el-tag>
            <el-tag size="small" :type="selectedTemplate.enabled ? 'success' : 'info'">
              {{ selectedTemplate.enabled ? '启用中' : '已停用' }}
            </el-tag>
            <el-tag size="small" effect="plain">当前 v{{ selectedTemplate.currentVersion }}</el-tag>
          </div>
        </div>

        <div class="metrics-row">
          <div class="metric-card">
            <span>调用查询</span>
            <strong>{{ summaryStats.totalQueries }}</strong>
          </div>
          <div class="metric-card">
            <span>成功率</span>
            <strong>{{ formatPercent(summaryStats.avgSuccess) }}</strong>
          </div>
          <div class="metric-card">
            <span>平均耗时</span>
            <strong>{{ formatTime(summaryStats.avgTime) }}</strong>
          </div>
          <div class="metric-card">
            <span>反馈数</span>
            <strong>{{ summaryStats.feedbackCount }}</strong>
          </div>
        </div>

        <el-tabs v-model="activeTab" class="prompt-tabs">
          <el-tab-pane name="editor">
            <template #label>
              <span class="tab-label"><Save :size="15" />模板编辑</span>
            </template>
            <div class="editor-grid">
              <el-input
                v-model="editContent"
                type="textarea"
                :rows="22"
                resize="vertical"
                placeholder="选择模板后编辑 Prompt 内容"
                :disabled="selectedTemplate?.status === 'PENDING_REVIEW'"
              />
              <div class="editor-side">
                <el-input
                  v-model="changeSummary"
                  type="textarea"
                  :rows="5"
                  maxlength="200"
                  show-word-limit
                  placeholder="版本变更摘要"
                />
                <div class="editor-actions">
                  <!-- 编辑操作：已通过模板会保存为新的草稿版本 -->
                  <el-button
                    v-if="selectedTemplate?.status !== 'PENDING_REVIEW'"
                    type="primary"
                    :loading="saving"
                    @click="handleSave"
                  >
                    <Save :size="16" />{{ selectedTemplate?.status === 'APPROVED' ? '保存为草稿' : '保存新版本' }}
                  </el-button>
                  <!-- 提交审核：只有 DRAFT 状态才能提交 -->
                  <el-button
                    v-if="selectedTemplate?.status === 'DRAFT'"
                    type="success"
                    :loading="submitting"
                    @click="handleSubmitForReview"
                  >
                    <Send :size="16" />提交审核
                  </el-button>
                  <!-- 审核操作：只有 PENDING_REVIEW 状态才能审核 -->
                  <el-button
                    v-if="selectedTemplate?.status === 'PENDING_REVIEW'"
                    type="success"
                    :loading="approving"
                    @click="handleApprove"
                  >
                    <CheckCircle2 :size="16" />审核通过
                  </el-button>
                  <el-button
                    v-if="selectedTemplate?.status === 'PENDING_REVIEW'"
                    type="danger"
                    :loading="rejecting"
                    @click="handleReject"
                  >
                    <XCircle :size="16" />审核拒绝
                  </el-button>
                </div>
                <!-- 状态提示 -->
                <div v-if="selectedTemplate" class="status-hint">
                  <el-alert
                    v-if="selectedTemplate.status === 'DRAFT'"
                    title="草稿状态"
                    description="编辑完成后请提交审核"
                    type="info"
                    :closable="false"
                    show-icon
                  />
                  <el-alert
                    v-else-if="selectedTemplate.status === 'PENDING_REVIEW'"
                    title="待审核状态"
                    description="等待审核人审核，审核通过后才能被 Python 端使用"
                    type="warning"
                    :closable="false"
                    show-icon
                  />
                  <el-alert
                    v-else-if="selectedTemplate.status === 'APPROVED'"
                    title="已通过状态"
                    description="模板已审核通过，正在被 Python 端使用；编辑保存后会生成草稿，不影响线上版本"
                    type="success"
                    :closable="false"
                    show-icon
                  />
                  <el-alert
                    v-else-if="selectedTemplate.status === 'REJECTED'"
                    title="已拒绝状态"
                    description="模板审核未通过，请根据拒绝原因修改后重新提交"
                    type="error"
                    :closable="false"
                    show-icon
                  />
                </div>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane name="versions">
            <template #label>
              <span class="tab-label"><History :size="15" />版本历史</span>
            </template>
            <el-table :data="versions" v-loading="versionLoading" stripe>
              <el-table-column prop="versionNo" label="版本" width="100">
                <template #default="{ row }">
                  <el-tag :type="row.isActive ? 'success' : 'info'" size="small">v{{ row.versionNo }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="getStatusInfo(row.status).type" size="small" effect="plain">
                    {{ getStatusInfo(row.status).label }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="changeSummary" label="变更摘要" min-width="220" show-overflow-tooltip />
              <el-table-column prop="createdAt" label="创建时间" width="180" />
              <el-table-column label="活跃" width="100">
                <template #default="{ row }">
                  <span class="state-inline">
                    <CheckCircle2 v-if="row.isActive" :size="15" />
                    {{ row.isActive ? '当前' : '历史' }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" :disabled="row.isActive" @click="handleRollback(row.versionNo)">
                    <RotateCcw :size="14" />回滚
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane name="analysis">
            <template #label>
              <span class="tab-label"><TrendingUp :size="15" />效果分析</span>
            </template>
            <el-table :data="activeEffectiveness" v-loading="analysisLoading" stripe>
              <el-table-column prop="templateCode" label="模板编码" min-width="180" show-overflow-tooltip />
              <el-table-column prop="versionNo" label="版本" width="90">
                <template #default="{ row }">v{{ row.versionNo }}</template>
              </el-table-column>
              <el-table-column prop="totalQueries" label="调用查询" width="110" />
              <el-table-column prop="successRate" label="成功率" width="120">
                <template #default="{ row }">{{ formatPercent(row.successRate) }}</template>
              </el-table-column>
              <el-table-column prop="avgExecutionTimeMs" label="平均耗时" width="120">
                <template #default="{ row }">{{ formatTime(row.avgExecutionTimeMs) }}</template>
              </el-table-column>
              <el-table-column prop="positiveFeedbackRate" label="正向反馈率" width="130">
                <template #default="{ row }">{{ formatPercent(row.positiveFeedbackRate) }}</template>
              </el-table-column>
              <el-table-column prop="feedbackCount" label="反馈数" width="100" />
            </el-table>
            <el-empty
              v-if="!activeEffectiveness.length && !analysisLoading"
              :description="
                activeCode && getNodeInfo(activeCode).connected
                  ? '暂无审计数据，请执行几次查询后再查看'
                  : activeCode && !getNodeInfo(activeCode).connected
                    ? '此模板尚未接入 Agent 流程，暂无效果数据'
                    : '暂无带 Prompt 版本的审计数据'
              "
            />
          </el-tab-pane>
        </el-tabs>
      </section>
    </section>
  </main>
</template>

<style scoped>
.prompt-page {
  display: grid;
  gap: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.page-header p {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--do-muted);
}

.page-header h1 {
  margin: 0;
  font-size: 22px;
  color: var(--do-ink);
}

.header-subtitle {
  display: block;
  margin-top: 6px;
  font-size: 13px;
  color: var(--do-muted);
}

.header-actions {
  display: flex;
  gap: 8px;
}

.prompt-layout {
  display: grid;
  grid-template-columns: minmax(260px, 320px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.template-sidebar,
.workspace-panel {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
}

.template-sidebar {
  display: grid;
  gap: 12px;
  padding: 14px;
  position: sticky;
  top: 16px;
}

.template-list {
  display: grid;
  gap: 8px;
  min-height: 220px;
}

.template-item {
  display: flex;
  width: 100%;
  min-height: 64px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: #fff;
  color: var(--do-ink);
  text-align: left;
  cursor: pointer;
}

.template-item:hover,
.template-item.active {
  border-color: var(--do-primary);
  background: var(--do-primary-soft);
}

.template-item strong,
.template-item small {
  display: block;
}

.template-item strong {
  font-size: 14px;
}

.template-item small {
  margin-top: 4px;
  font-size: 12px;
  color: var(--do-muted);
}

.template-item-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.template-item-tags {
  display: flex;
  gap: 4px;
  margin-top: 4px;
  flex-wrap: wrap;
}

.workspace-panel {
  min-width: 0;
  padding: 16px;
}

.template-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-bottom: 14px;
}

.template-meta strong,
.template-meta span {
  display: block;
}

.template-meta strong {
  font-size: 16px;
  color: var(--do-ink);
}

.template-meta span {
  margin-top: 4px;
  font-size: 12px;
  color: var(--do-muted);
}

.template-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.template-meta-scenario {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--do-muted);
}

.template-meta-hint {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  padding: 4px 8px;
  border-radius: 4px;
}

.template-meta-hint--active {
  color: #529b2e;
  background: #f0f9eb;
}

.template-meta-hint--inactive {
  color: #b98900;
  background: #fdf6ec;
}

.metrics-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.metric-card {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  padding: 12px;
  background: #fff;
}

.metric-card span {
  display: block;
  font-size: 12px;
  color: var(--do-muted);
}

.metric-card strong {
  display: block;
  margin-top: 6px;
  font-size: 22px;
  color: var(--do-ink);
}

.prompt-tabs {
  min-width: 0;
}

.tab-label,
.state-inline {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}

.editor-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240px;
  gap: 14px;
}

.editor-grid :deep(.el-textarea__inner) {
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
}

.editor-side {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.editor-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.editor-actions .el-button {
  width: 100%;
}

.status-hint {
  margin-top: 8px;
}

.status-hint :deep(.el-alert) {
  padding: 8px 12px;
}

.status-hint :deep(.el-alert__title) {
  font-size: 13px;
}

.status-hint :deep(.el-alert__description) {
  font-size: 12px;
  margin-top: 4px;
}

@media (max-width: 1100px) {
  .prompt-layout,
  .editor-grid {
    grid-template-columns: 1fr;
  }

  .template-sidebar {
    position: static;
  }

  .metrics-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
