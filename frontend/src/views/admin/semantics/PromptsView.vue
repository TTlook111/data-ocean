<script setup lang="ts">
/**
 * Prompt 策略工作区
 *
 * 采用「模板列表 + 模板详情」结构，详情固定四个 Tab：当前内容、审核流程、版本历史、效果统计。
 * 替代旧的 PromptManager.vue（把编辑和审核动作挤在同一个工作面板中）。
 *
 * 后端事实（PromptTemplateServiceImpl）：
 * - 状态机为 DRAFT -> PENDING_REVIEW -> APPROVED / REJECTED。
 * - 保存内容会新建一个 DRAFT 版本，并把模板状态重置为 DRAFT；APPROVED 表示该版本是当前生效的活跃版本，
 *   不能简化成「已发布」。
 * - PENDING_REVIEW 状态禁止编辑和回滚。
 * - 启停（enabled）接口 2026-09-12 补齐，APPROVED 状态的模板可在本页面启用或停用。
 *   `enabled` 决定 getActiveContent 能否取到模板，停用后问数链路回退到内置默认模板。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CheckCircle2, Power, RefreshCw, RotateCcw, Save, Search, Send, XCircle } from 'lucide-vue-next'
import {
  approvePrompt,
  getPromptEffectiveness,
  getPromptVersions,
  listPromptTemplates,
  rejectPrompt,
  rollbackPromptVersion,
  setPromptEnabled,
  submitPromptForReview,
  updatePromptTemplate,
  type PromptEffectivenessVO,
  type PromptTemplateVO,
  type PromptVersionVO,
} from '../../../api/admin/prompt'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

/** 模板编码 → Agent 节点映射（与 Python 端保持一致；未映射的模板不标注节点） */
const NODE_LABEL_MAP: Record<string, string> = {
  sql_generation: 'SQL 生成节点',
  chart_generation: '图表生成节点',
  intent_recognition: '意图识别节点',
}

const templates = ref<PromptTemplateVO[]>([])
const versions = ref<PromptVersionVO[]>([])
const effectiveness = ref<PromptEffectivenessVO[]>([])
const activeCode = ref('')
const activeTab = ref('content')
const keyword = ref('')
const analysisDays = ref(30)
const editContent = ref('')
const changeSummary = ref('')

const loading = ref(false)
const error = ref('')
const saving = ref(false)
const actionLoading = ref(false)
const versionsLoading = ref(false)
const effectLoading = ref(false)

const selected = computed(() => templates.value.find((item) => item.templateCode === activeCode.value) || null)
const status = computed(() => selected.value?.status || 'DRAFT')
const canEdit = computed(() => status.value !== 'PENDING_REVIEW')
const nodeLabel = computed(() => (selected.value ? NODE_LABEL_MAP[selected.value.templateCode] || '' : ''))

const filteredTemplates = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) return templates.value
  return templates.value.filter((item) =>
    [item.templateCode, item.templateName, item.scenario].some((value) => value?.toLowerCase().includes(query)),
  )
})

/** 效果统计只展示当前选中模板（未选中时展示全部） */
const activeEffectiveness = computed(() =>
  effectiveness.value.filter((item) => !activeCode.value || item.templateCode === activeCode.value),
)

const summaryStats = computed(() => {
  const rows = activeEffectiveness.value
  const totalQueries = rows.reduce((sum, row) => sum + row.totalQueries, 0)
  const weightedSuccess = rows.reduce((sum, row) => sum + row.successRate * row.totalQueries, 0)
  const feedbackCount = rows.reduce((sum, row) => sum + row.feedbackCount, 0)
  const positiveFeedback = rows.reduce((sum, row) => sum + row.positiveFeedbackCount, 0)
  return {
    totalQueries,
    avgSuccess: totalQueries ? weightedSuccess / totalQueries : 0,
    avgTime: rows.length ? rows.reduce((sum, row) => sum + row.avgExecutionTimeMs, 0) / rows.length : 0,
    feedbackCount,
    positiveRate: feedbackCount ? (positiveFeedback / feedbackCount) * 100 : 0,
  }
})

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

function formatPercent(value?: number) {
  return `${Number(value || 0).toFixed(1)}%`
}

function formatTime(value?: number) {
  const ms = Number(value || 0)
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${ms.toFixed(0)}ms`
}

function selectTab(tab: string) {
  activeTab.value = tab
  if (tab === 'versions' && !versions.value.length) loadVersions()
  if (tab === 'effectiveness' && !effectiveness.value.length) loadEffectiveness()
}

async function loadTemplates(preferredCode?: string) {
  loading.value = true
  error.value = ''
  try {
    const result = await listPromptTemplates({ page: 1, pageSize: 100 })
    templates.value = result.data?.records || []
    const next = preferredCode
      || (templates.value.some((item) => item.templateCode === activeCode.value) ? activeCode.value : '')
      || templates.value[0]?.templateCode
      || ''
    if (next) await selectTemplate(next)
    else {
      activeCode.value = ''
      editContent.value = ''
    }
  } catch (cause) {
    templates.value = []
    error.value = apiError(cause, 'Prompt 模板加载失败')
  } finally {
    loading.value = false
  }
}

async function selectTemplate(code: string) {
  activeCode.value = code
  versions.value = []
  editContent.value = templates.value.find((item) => item.templateCode === code)?.content || ''
  changeSummary.value = ''
  if (activeTab.value === 'versions') await loadVersions()
  if (activeTab.value === 'effectiveness') await loadEffectiveness()
}

/**
 * 启用或停用模板。
 *
 * `enabled` 决定 `getActiveContent` 能否取到该模板，是 Prompt 策略的生效开关。
 * 后端 2026-09-12 补上启停接口后，前端才能操作它（此前只能展示）。
 * 后端仅允许对 APPROVED 状态的模板启停。
 */
async function toggleEnabled() {
  const template = selected.value
  if (!template) return
  const next = !template.enabled
  try {
    await ElMessageBox.confirm(
      next
        ? `启用「${template.templateName}」后，该模板会参与 Prompt 组装并生效。确认启用？`
        : `停用「${template.templateName}」后，取模板内容会直接报错，问数链路将回退到内置默认模板。确认停用？`,
      next ? '启用模板' : '停用模板',
      { type: next ? 'info' : 'warning' },
    )
    actionLoading.value = true
    const result = await setPromptEnabled(template.templateCode, next)
    ElMessage.success(next ? '模板已启用' : '模板已停用')
    const updated = result.data
    if (updated) {
      templates.value = templates.value.map((item) =>
        item.templateCode === updated.templateCode ? { ...item, enabled: updated.enabled } : item)
    } else {
      await loadTemplates(template.templateCode)
    }
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '模板启停失败'))
  } finally {
    actionLoading.value = false
  }
}

async function loadVersions() {
  if (!activeCode.value) return
  versionsLoading.value = true
  try {
    versions.value = (await getPromptVersions(activeCode.value)).data || []
  } catch (cause) {
    versions.value = []
    ElMessage.error(apiError(cause, '版本历史加载失败'))
  } finally {
    versionsLoading.value = false
  }
}

async function loadEffectiveness() {
  effectLoading.value = true
  try {
    effectiveness.value = (await getPromptEffectiveness(analysisDays.value)).data || []
  } catch (cause) {
    effectiveness.value = []
    ElMessage.error(apiError(cause, '效果统计加载失败'))
  } finally {
    effectLoading.value = false
  }
}

async function save() {
  if (!selected.value) return
  if (!editContent.value.trim()) {
    ElMessage.warning('模板内容不能为空')
    return
  }
  saving.value = true
  try {
    await updatePromptTemplate(selected.value.templateCode, {
      content: editContent.value,
      changeSummary: changeSummary.value || '手动编辑保存',
    })
    ElMessage.success('已保存为新草稿版本，模板状态回到草稿')
    changeSummary.value = ''
    await loadTemplates(activeCode.value)
  } catch (cause) {
    ElMessage.error(apiError(cause, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function submit() {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm(
      `提交「${selected.value.templateName}」审核？提交后到审核完成前不能编辑。`,
      '提交审核',
    )
    actionLoading.value = true
    await submitPromptForReview(selected.value.templateCode)
    ElMessage.success('已提交审核')
    await loadTemplates(activeCode.value)
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '提交审核失败'))
  } finally {
    actionLoading.value = false
  }
}

async function approve() {
  if (!selected.value) return
  try {
    const result = await ElMessageBox.prompt(
      '确认通过审核？通过后该版本成为当前生效的活跃模板，请填写变更摘要以便追溯。',
      '审核通过',
      {
        inputPlaceholder: '变更摘要（可留空）',
        confirmButtonText: '确认通过',
        cancelButtonText: '取消',
      },
    )
    actionLoading.value = true
    await approvePrompt(selected.value.templateCode, result.value)
    ElMessage.success('审核已通过，该版本成为活跃模板')
    await loadTemplates(activeCode.value)
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '审核通过失败'))
  } finally {
    actionLoading.value = false
  }
}

async function reject() {
  if (!selected.value) return
  try {
    const result = await ElMessageBox.prompt('请说明拒绝原因', '审核拒绝', {
      inputValidator: (value) => Boolean(value?.trim()) || '拒绝原因不能为空',
      confirmButtonText: '确认拒绝',
      cancelButtonText: '取消',
    })
    actionLoading.value = true
    await rejectPrompt(selected.value.templateCode, result.value)
    ElMessage.success('已拒绝')
    await loadTemplates(activeCode.value)
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '审核拒绝失败'))
  } finally {
    actionLoading.value = false
  }
}

const previewVisible = ref(false)
const previewVersion = ref<PromptVersionVO | null>(null)

function showVersion(item: PromptVersionVO) {
  previewVersion.value = item
  previewVisible.value = true
}

async function rollback(item: PromptVersionVO) {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm(
      `回滚到版本 ${item.versionNo}？该版本内容会写回模板内容并创建新的草稿版本。`,
      '版本回滚',
      { type: 'warning', confirmButtonText: '确认回滚', cancelButtonText: '取消' },
    )
    actionLoading.value = true
    await rollbackPromptVersion(selected.value.templateCode, item.versionNo)
    ElMessage.success('已回滚，目标版本内容写为新的草稿版本，需重新提交审核')
    await Promise.all([loadTemplates(activeCode.value), loadVersions()])
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '回滚失败'))
  } finally {
    actionLoading.value = false
  }
}

onMounted(() => {
  loadTemplates()
  loadEffectiveness()
})

watch(analysisDays, () => {
  if (activeTab.value === 'effectiveness') loadEffectiveness()
})
</script>

<template>
  <div class="admin-page prompts-page">
    <TaskPageHeader
      eyebrow="语义中心"
      title="Prompt 策略"
      description="维护 Agent 节点的 Prompt 模板。已批准表示该版本是当前生效的活跃模板，不等于对外发布。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadTemplates(activeCode)">刷新</el-button>
        <el-button v-if="canEdit && selected" :type="status === 'APPROVED' ? 'primary' : 'default'" :icon="Save" :loading="saving" @click="save">
          保存并创建新版本
        </el-button>
        <el-button v-if="selected && (status === 'DRAFT' || status === 'REJECTED')" type="primary" :icon="Send" :loading="actionLoading" @click="submit">
          提交审核
        </el-button>
        <template v-if="selected && status === 'PENDING_REVIEW'">
          <el-button type="primary" :icon="CheckCircle2" :loading="actionLoading" @click="approve">审核通过</el-button>
          <el-button type="danger" plain :icon="XCircle" :loading="actionLoading" @click="reject">审核拒绝</el-button>
        </template>
      </template>
    </TaskPageHeader>

    <ErrorState v-if="error" :message="error" @retry="loadTemplates()" />

    <div v-else class="prompts-layout">
      <aside class="panel">
        <header class="panel__heading">
          <div>
            <h2>模板列表</h2>
            <p>{{ filteredTemplates.length }} / {{ templates.length }} 个模板</p>
          </div>
        </header>
        <el-input v-model="keyword" placeholder="搜索编码 / 名称 / 场景" clearable :prefix-icon="Search" />
        <LoadingState v-if="loading" text="正在读取模板…" />
        <EmptyState v-else-if="!filteredTemplates.length" message="没有匹配的 Prompt 模板。" />
        <ul v-else class="template-list">
          <li
            v-for="item in filteredTemplates"
            :key="item.templateCode"
            class="template-list__item"
            :class="{ 'is-active': item.templateCode === activeCode }"
          >
            <button type="button" class="template-list__select" @click="selectTemplate(item.templateCode)">
              <strong>{{ item.templateName }}</strong>
              <small>{{ item.templateCode }}{{ NODE_LABEL_MAP[item.templateCode] ? ' · ' + NODE_LABEL_MAP[item.templateCode] : '' }}</small>
              <span class="template-list__meta">v{{ item.currentVersion }} · {{ item.scenario || '未标注场景' }}</span>
            </button>
            <BusinessStatusBadge :status="item.status" />
          </li>
        </ul>
      </aside>

      <section class="panel detail">
        <EmptyState v-if="!selected" message="请选择左侧模板查看内容、审核流程、版本和效果。" />
        <template v-else>
          <header class="detail__heading">
            <div>
              <h2>{{ selected.templateName }}</h2>
              <p>
                {{ selected.templateCode }} · 当前版本 v{{ selected.currentVersion }}
                <template v-if="nodeLabel"> · 生效节点：{{ nodeLabel }}</template>
              </p>
            </div>
            <div class="detail__badges">
              <BusinessStatusBadge :status="selected.status" />
              <el-tag :type="selected.enabled ? 'success' : 'info'" size="small">
                {{ selected.enabled ? '模板已启用' : '模板未启用' }}
              </el-tag>
              <el-button
                v-if="status === 'APPROVED'"
                size="small"
                :icon="Power"
                :type="selected.enabled ? 'danger' : 'primary'"
                plain
                :loading="actionLoading"
                @click="toggleEnabled"
              >{{ selected.enabled ? '停用' : '启用' }}</el-button>
            </div>
          </header>

          <el-tabs :model-value="activeTab" @update:model-value="selectTab">
            <el-tab-pane label="当前内容" name="content">
              <p v-if="status === 'PENDING_REVIEW'" class="note">
                待审核状态的模板不能编辑。请先完成审核，再创建新版本。
              </p>
              <el-input
                v-model="editContent"
                type="textarea"
                :rows="20"
                :disabled="!canEdit"
                placeholder="模板内容"
                class="content-editor"
              />
              <el-input
                v-if="canEdit"
                v-model="changeSummary"
                placeholder="变更摘要（可选，用于版本追溯）"
                class="content-summary"
              />
              <div v-if="canEdit" class="content-actions">
                <el-button type="primary" :icon="Save" :loading="saving" @click="save">保存并创建新版本</el-button>
              </div>
              <p class="note">
                保存会新建一个草稿版本，模板状态回到草稿；只有审核通过后新内容才会成为活跃模板。
              </p>
            </el-tab-pane>

            <el-tab-pane label="审核流程" name="review">
              <dl class="facts">
                <div><dt>当前状态</dt><dd><BusinessStatusBadge :status="selected.status" /></dd></div>
                <div><dt>当前版本</dt><dd>v{{ selected.currentVersion }}</dd></div>
                <div><dt>最近更新</dt><dd>{{ selected.updatedAt || '—' }}</dd></div>
              </dl>
              <p class="note">
                状态流转为 草稿 → 待审核 → 已批准 或 已拒绝。已批准表示该版本是当前生效的活跃模板，
                与知识文档的「已发布」含义不同，页面不做混用。
              </p>
              <div class="flow-actions">
                <el-button v-if="status === 'DRAFT' || status === 'REJECTED'" type="primary" :icon="Send" :loading="actionLoading" @click="submit">
                  提交审核
                </el-button>
                <template v-if="status === 'PENDING_REVIEW'">
                  <el-button type="primary" :icon="CheckCircle2" :loading="actionLoading" @click="approve">审核通过</el-button>
                  <el-button type="danger" plain :icon="XCircle" :loading="actionLoading" @click="reject">审核拒绝</el-button>
                </template>
                <span v-if="status === 'APPROVED'" class="note">
                  当前版本已生效。如需修改，请在「当前内容」保存新版本并重新走审核。
                </span>
              </div>
            </el-tab-pane>

            <el-tab-pane label="版本历史" name="versions">
              <div class="tab-actions">
                <el-button :icon="RefreshCw" :loading="versionsLoading" @click="loadVersions">刷新版本</el-button>
              </div>
              <LoadingState v-if="versionsLoading" variant="skeleton" :rows="4" />
              <EmptyState v-else-if="!versions.length" message="暂无版本记录。保存内容后会产生版本。" />
              <el-table v-else :data="versions" stripe>
                <el-table-column label="版本" width="90"><template #default="{ row }">v{{ row.versionNo }}</template></el-table-column>
                <el-table-column label="状态" width="120"><template #default="{ row }"><BusinessStatusBadge :status="row.status" /></template></el-table-column>
                <el-table-column label="生效" width="80">
                  <template #default="{ row }">
                    <el-tag v-if="row.isActive" type="success" size="small">当前生效</el-tag>
                    <span v-else class="muted">—</span>
                  </template>
                </el-table-column>
                <el-table-column prop="changeSummary" label="变更摘要" min-width="200" show-overflow-tooltip />
                <el-table-column prop="createdAt" label="创建时间" width="175" />
                <el-table-column label="操作" width="150" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="showVersion(row)">查看</el-button>
                    <el-button
                      v-if="!row.isActive && status !== 'PENDING_REVIEW'"
                      link
                      type="warning"
                      :loading="actionLoading"
                      @click="rollback(row)"
                    >
                      <RotateCcw :size="14" />回滚
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane label="效果统计" name="effectiveness">
              <div class="tab-actions">
                <el-select v-model="analysisDays" style="width: 130px">
                  <el-option :label="'近 7 天'" :value="7" />
                  <el-option :label="'近 30 天'" :value="30" />
                  <el-option :label="'近 90 天'" :value="90" />
                </el-select>
                <el-button :icon="RefreshCw" :loading="effectLoading" @click="loadEffectiveness">刷新统计</el-button>
              </div>
              <LoadingState v-if="effectLoading" variant="skeleton" :rows="4" />
              <template v-else>
                <section class="metric-row">
                  <div class="metric"><span>查询总数</span><strong>{{ summaryStats.totalQueries }}</strong></div>
                  <div class="metric"><span>加权成功率</span><strong>{{ formatPercent(summaryStats.avgSuccess) }}</strong></div>
                  <div class="metric"><span>平均耗时</span><strong>{{ formatTime(summaryStats.avgTime) }}</strong></div>
                  <div class="metric"><span>反馈数</span><strong>{{ summaryStats.feedbackCount }}</strong></div>
                  <div class="metric"><span>正向反馈率</span><strong>{{ formatPercent(summaryStats.positiveRate) }}</strong></div>
                </section>
                <EmptyState v-if="!activeEffectiveness.length" message="该模板在所选时间范围内没有查询记录，无法给出效果结论。" />
                <el-table v-else :data="activeEffectiveness" stripe size="small">
                  <el-table-column prop="templateCode" label="模板编码" width="170" />
                  <el-table-column label="版本" width="80"><template #default="{ row }">v{{ row.versionNo }}</template></el-table-column>
                  <el-table-column prop="totalQueries" label="查询数" width="90" />
                  <el-table-column label="成功率" width="100"><template #default="{ row }">{{ formatPercent(row.successRate) }}</template></el-table-column>
                  <el-table-column label="平均耗时" width="110"><template #default="{ row }">{{ formatTime(row.avgExecutionTimeMs) }}</template></el-table-column>
                  <el-table-column prop="feedbackCount" label="反馈数" width="90" />
                  <el-table-column label="正向反馈率" min-width="110">
                    <template #default="{ row }">{{ formatPercent(row.positiveFeedbackRate) }}</template>
                  </el-table-column>
                </el-table>
              </template>
            </el-tab-pane>
          </el-tabs>
        </template>
      </section>
    </div>

    <el-dialog v-model="previewVisible" :title="`版本 ${previewVersion?.versionNo} 内容`" width="760px">
      <pre class="dialog-pre">{{ previewVersion?.content || '（空内容）' }}</pre>
    </el-dialog>
  </div>
</template>

<style scoped>
.prompts-page { display: grid; gap: 16px; }

.prompts-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 16px; align-items: start; }

.panel {
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.panel__heading { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 12px; }
.panel__heading h2 { margin: 0; color: var(--do-ink); font-size: 15px; }
.panel__heading p { margin: 4px 0 0; color: var(--do-muted); font-size: 12px; }

.template-list { display: grid; gap: 8px; margin: 12px 0 0; padding: 0; list-style: none; max-height: 620px; overflow: auto; }
.template-list__item { display: grid; gap: 6px; padding: 10px 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); background: var(--do-bg); }
.template-list__item.is-active { border-color: var(--do-primary); background: var(--do-primary-soft); }
.template-list__select { display: grid; gap: 3px; width: 100%; padding: 0; border: 0; color: inherit; background: transparent; cursor: pointer; text-align: left; }
.template-list__select strong { color: var(--do-ink); font-size: 13px; }
.template-list__select small { color: var(--do-muted); font-size: 11px; }
.template-list__meta { color: var(--do-muted); font-size: 11px; }

.detail { display: grid; gap: 8px; }
.detail__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.detail__heading h2 { margin: 0; color: var(--do-ink); font-size: 17px; }
.detail__heading p { margin: 4px 0 0; color: var(--do-muted); font-size: 12px; }
.detail__badges { display: flex; align-items: center; gap: 8px; }

.content-editor :deep(.el-textarea__inner) { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 13px; line-height: 1.7; }
.content-summary { margin-top: 10px; }
.content-actions { margin-top: 12px; }
.tab-actions { display: flex; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.flow-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }

.facts { display: grid; gap: 8px; margin: 0 0 12px; }
.facts > div { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 10px; }
.facts dt { color: var(--do-muted); font-size: 12px; }
.facts dd { margin: 0; color: var(--do-ink); font-size: 13px; }

.note { margin: 10px 0 0; color: var(--do-muted); font-size: 12px; line-height: 1.7; }
.muted { color: var(--do-muted); font-size: 12px; }

.metric-row { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.metric { display: grid; gap: 4px; padding: 12px 14px; border-radius: var(--do-radius-md); background: var(--do-bg); }
.metric span { color: var(--do-muted); font-size: 12px; }
.metric strong { color: var(--do-ink); font-size: 20px; }

.dialog-pre { max-height: 520px; margin: 0; padding: 14px; overflow: auto; border-radius: var(--do-radius-md); background: var(--do-bg); color: var(--do-ink); font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }

@media (max-width: 1180px) {
  .prompts-layout { grid-template-columns: minmax(0, 1fr); }
  .metric-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
