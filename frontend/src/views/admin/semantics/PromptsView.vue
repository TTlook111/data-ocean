<script setup lang="ts">
/**
 * Prompt 策略工作区
 *
 * 按真实场景展示策略、固定合同、独立生效版本、审核及实际调用来源。
 *
 * 后端事实（PromptTemplateServiceImpl）：
 * - 状态机为 DRAFT -> PENDING_REVIEW -> APPROVED / REJECTED。
 * - 保存内容会新建一个 DRAFT 版本，并把模板状态重置为 DRAFT；APPROVED 表示该版本是当前生效的活跃版本，
 *   不能简化成「已发布」。
 * - PENDING_REVIEW 状态禁止编辑和回滚。
 * - 启停独立控制已批准生效版本，不受另一个草稿版本状态影响。
 *   `enabled` 决定 getActiveContent 能否取到模板，停用后问数链路回退到内置默认模板。
 */
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useIamWorkspaceSession } from '../../../composables/useIamWorkspaceSession'
import { trackGovernanceWrite } from '../../../composables/governanceWriteTracker'
import GovernanceWriteNotice from '../../../components/admin/GovernanceWriteNotice.vue'
import { ElMessageBox } from 'element-plus'
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
  listPromptScenes,
  listPromptInvocations,
  validatePromptPolicy,
  type PromptSceneVO,
  type PromptInvocationVO,
  type PromptEffectivenessVO,
  type PromptTemplateVO,
  type PromptVersionVO,
} from '../../../api/admin/prompt'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

function consumerLabel(code: string) {
  return scenes.value.find((scene) => scene.code === code)?.name || '当前目录未列调用方'
}

function fallbackLabel(reason: string) {
  return (
    (
      {
        MISSING: '配置不存在',
        DISABLED: '后台策略已停用或暂无批准版本',
        UNAVAILABLE: '策略服务不可用',
        INVALID_TEMPLATE: '模板不符合场景合同',
      } as Record<string, string>
    )[reason] || '—'
  )
}

const route = useRoute()
const router = useRouter()
const session = useIamWorkspaceSession()
const iamS1 = session.iam

/**
 * Prompt 三个功能码都是 B0 冻结的**全局**功能（模板不挂数据源），三者互不包含：
 * 查看用 prompt:view，保存/启停/提交/回滚用 prompt:manage，审核通过/驳回用 prompt:approve。
 * 这里与后端 @IamS1Global 同一判定口径；前端不是安全边界，后端仍会独立拒绝。
 */
const canView = computed(() => session.canGlobal('prompt:view'))
const canManage = computed(() => session.canGlobal('prompt:manage'))
const canApprove = computed(() => session.canGlobal('prompt:approve'))
const VIEW_HINT = '没有“查看 AI 提示词”能力：需要角色拥有 prompt:view。'
const MANAGE_HINT = '没有“维护 AI 提示词”能力：需要角色拥有 prompt:manage。审核权不自动带来维护权。'
const APPROVE_HINT = '没有“审核 AI 提示词”能力：需要角色拥有 prompt:approve。维护权不自动带来审核权。'

const templates = ref<PromptTemplateVO[]>([])
const scenes = ref<PromptSceneVO[]>([])
const versions = ref<PromptVersionVO[]>([])
const effectiveness = ref<PromptEffectivenessVO[]>([])
/** 选中模板与当前 Tab 由 URL 决定，刷新/分享/前进后退都能恢复（开发指导 §15、§8.3） */
const activeCode = ref(String(route.query.code || ''))
const activeTab = ref(String(route.query.tab || 'content'))
const keyword = ref('')
const analysisDays = ref(Number(route.query.days) || 30)
const editContent = ref('')
const editSystem = ref('')
const validation = ref('')
const validating = ref(false)
const invocationRows = ref<PromptInvocationVO[]>([])
const invocationTotal = ref(0)
const invocationPage = ref(1)
const invocationLoading = ref(false)
const invocationError = ref('')
let selectionRevision = 0
let loadRevision = 0
let editRevision = 0
let versionsRequest = 0,
  effectsRequest = 0,
  invocationsRequest = 0,
  validationRequest = 0,
  writeRequest = 0
const writeError = ref(''),
  baselineDraft = ref(''),
  draftConflict = ref(false)
const currentDraft = () => JSON.stringify([editSystem.value, editContent.value, changeSummary.value])
const dirty = computed(() => !!baselineDraft.value && currentDraft() !== baselineDraft.value)
const knownVersions = new Map<string, number>()
const localDrafts = new Map<
  string,
  { system: string; user: string; summary: string; base: number; baseline: string }
>()
const refreshNotice = ref('')
const changeSummary = ref('')

const loading = ref(false)
const error = ref('')
const saving = ref(false)
const actionLoading = ref(false)
const versionsLoading = ref(false)
const effectLoading = ref(false)
/** 版本历史与效果统计各自的错误态；接口失败必须渲染错误，不能降级成「暂无数据」 */
const versionsError = ref('')
const effectivenessError = ref('')

const selected = computed(
  () => templates.value.find((item) => item.templateCode === activeCode.value) || null,
)
const selectedScene = computed(() => scenes.value.find((scene) => scene.code === activeCode.value))
const status = computed(() => selected.value?.status || 'UNKNOWN')
const canEdit = computed(
  () => !!selectedScene.value && ['DRAFT', 'APPROVED', 'REJECTED'].includes(status.value),
)
const nodeLabel = computed(() => (selected.value ? consumerLabel(selected.value.templateCode) : ''))

const filteredTemplates = computed(() => {
  const catalog = scenes.value.map(
    (scene) =>
      templates.value.find((item) => item.templateCode === scene.code) || {
        id: 0,
        templateCode: scene.code,
        templateName: scene.name,
        scenario: scene.group,
        content: '',
        currentVersion: 0,
        activeVersion: 0,
        status: 'DRAFT' as const,
        enabled: false,
        updatedAt: '',
      },
  )
  const entries = [
    ...catalog,
    ...templates.value.filter((item) => !scenes.value.some((scene) => scene.code === item.templateCode)),
  ]
  const query = keyword.value.trim().toLowerCase()
  if (!query) return entries
  return entries.filter((item) =>
    [item.templateCode, item.templateName, item.scenario].some((value) =>
      value?.toLowerCase().includes(query),
    ),
  )
})

function policyParts(content: string) {
  try {
    const value = JSON.parse(content)
    if (value.formatVersion === 1 && typeof value.system === 'string' && typeof value.user === 'string')
      return value
  } catch {
    /* 兼容已有纯文本版本，不改变历史内容。 */
  }
  return { system: '', user: content }
}

function policyContent() {
  return JSON.stringify({ formatVersion: 1, system: editSystem.value, user: editContent.value })
}

async function validatePolicy() {
  const k = session.capture()
  if (!selected.value || !canView.value || !canManage.value || validating.value) return
  const revision = selectionRevision
  const code = activeCode.value
  const content = policyContent(),
    request = ++validationRequest
  validating.value = true
  validation.value = ''
  try {
    const response = await validatePromptPolicy(code, content)
    if (
      session.current(k) &&
      revision === selectionRevision &&
      request === validationRequest &&
      content === policyContent()
    ) {
      validation.value = `合同 v${response.data.contractVersion} 校验通过；以下仅为合成变量预览，未调用模型。\n\n角色指令：\n${response.data.systemPreview}\n\n任务指令：\n${response.data.userPreview}`
    }
  } catch (cause) {
    if (
      session.current(k) &&
      revision === selectionRevision &&
      request === validationRequest &&
      content === policyContent()
    )
      validation.value = apiError(cause, '校验失败')
  } finally {
    if (session.current(k) && revision === selectionRevision && request === validationRequest)
      validating.value = false
  }
}

async function loadInvocations(page = 1) {
  const k = session.capture()
  if (!canView.value || !selectedScene.value) return
  const revision = selectionRevision
  const code = activeCode.value
  const request = ++invocationsRequest
  invocationPage.value = page
  invocationRows.value = []
  invocationTotal.value = 0
  invocationLoading.value = true
  invocationError.value = ''
  try {
    const response = await listPromptInvocations(code, page)
    if (
      !session.current(k) ||
      revision !== selectionRevision ||
      request !== invocationsRequest ||
      invocationPage.value !== page
    )
      return
    if (
      !response.data ||
      !Array.isArray(response.data.records) ||
      !Number.isSafeInteger(response.data.total) ||
      response.data.total < 0 ||
      response.data.records.some((item) => item.sceneCode !== code || !item.invocationId)
    )
      throw new Error('调用回执不完整或错属场景，未应用')
    invocationRows.value = response.data.records
    invocationTotal.value = response.data.total
  } catch (cause) {
    if (
      session.current(k) &&
      revision === selectionRevision &&
      request === invocationsRequest &&
      invocationPage.value === page
    )
      invocationError.value = apiError(cause, '调用记录加载失败')
  } finally {
    if (
      session.current(k) &&
      revision === selectionRevision &&
      request === invocationsRequest &&
      invocationPage.value === page
    )
      invocationLoading.value = false
  }
}

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
    avgSuccess: totalQueries ? weightedSuccess / totalQueries : undefined,
    avgTime: rows.length ? rows.reduce((sum, row) => sum + row.avgExecutionTimeMs, 0) / rows.length : 0,
    feedbackCount,
    positiveRate: feedbackCount ? (positiveFeedback / feedbackCount) * 100 : undefined,
  }
})

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

function formatPercent(value?: number) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  return `${value.toFixed(1)}%`
}

function formatTime(value?: number) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  const ms = value
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${ms.toFixed(0)}ms`
}

/**
 * 切换详情 Tab。
 *
 * 用 `push` 而不是 `replace`：开发指导 §15 要求「URL 刷新、前进和后退恢复」，
 * 只有 push 才能让浏览器后退回到上一个 Tab。全站 Tab 统一切换语义。
 */
function selectTab(tab: string) {
  activeTab.value = tab
  router.push({ hash: route.hash, query: { ...route.query, tab } })
  if (tab === 'versions' && !versions.value.length) loadVersions()
  if (tab === 'effectiveness' && !effectiveness.value.length) loadEffectiveness()
  if (tab === 'invocations') loadInvocations()
}

async function loadTemplates(preferredCode?: string) {
  const k = session.capture()
  const revision = ++loadRevision
  const selectionAtStart = selectionRevision
  const editAtStart = editRevision,
    wasDirty = dirty.value
  // 无 prompt:view 时不要发这个必然 403 的请求，改为在页面上说明原因
  if (!canView.value) {
    templates.value = []
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  refreshNotice.value = ''
  try {
    const [result, catalog] = await Promise.all([
      listPromptTemplates({ page: 1, pageSize: 100 }),
      listPromptScenes(),
    ])
    if (!session.current(k) || revision !== loadRevision || selectionAtStart !== selectionRevision) return
    if (editAtStart !== editRevision || wasDirty) {
      refreshNotice.value =
        '当前编辑已保留，刷新未应用；可明确放弃草稿后重读。刷新期间有新修改，当前编辑已保留，未应用刷新结果。'
      return
    }
    if (
      (result.data?.records || []).some(
        (item) => item.currentVersion < (knownVersions.get(item.templateCode) || 0),
      )
    ) {
      refreshNotice.value = '刷新版本早于已核回执，保留当前版本与输入；请稍后重读。'
      return
    }
    if (!Array.isArray(result.data?.records) || !Array.isArray(catalog.data))
      throw new Error('场景目录或策略回执不完整，不能按空配置解释')
    templates.value = result.data.records
    scenes.value = catalog.data || []
    const next =
      preferredCode || activeCode.value || templates.value[0]?.templateCode || scenes.value[0]?.code || ''
    if (next) await selectTemplate(next)
    else {
      activeCode.value = ''
      editContent.value = ''
    }
  } catch (cause) {
    if (!session.current(k) || revision !== loadRevision || selectionAtStart !== selectionRevision) return
    if (editAtStart !== editRevision || wasDirty) {
      refreshNotice.value = `${apiError(cause, '刷新失败')}；当前编辑已保留。`
      return
    }
    templates.value = []
    error.value = apiError(cause, 'Prompt 模板加载失败')
  } finally {
    if (session.current(k) && revision === loadRevision) loading.value = false
  }
}

async function selectTemplate(code: string, preserveWrite = false) {
  if (!canView.value) return
  if (activeCode.value && code !== activeCode.value && dirty.value)
    localDrafts.set(activeCode.value, {
      system: editSystem.value,
      user: editContent.value,
      summary: changeSummary.value,
      base: selected.value?.currentVersion || 0,
      baseline: baselineDraft.value,
    })
  session.cancelOwn()
  if (code !== activeCode.value) {
    previewVisible.value = false
    previewVersion.value = null
  }
  selectionRevision++
  if (!preserveWrite) writeRequest++
  refreshNotice.value = ''
  if (!preserveWrite) {
    saving.value = false
    actionLoading.value = false
  }
  versionsLoading.value = false
  activeCode.value = code
  validation.value = ''
  validating.value = false
  invocationRows.value = []
  invocationTotal.value = 0
  invocationError.value = ''
  versions.value = []
  versionsError.value = ''
  effectivenessError.value = ''
  const parts = policyParts(templates.value.find((item) => item.templateCode === code)?.content || '')
  editContent.value = parts.user
  editSystem.value = parts.system || `你是${consumerLabel(code)}助手。遵循场景的固定执行合同。`
  changeSummary.value = ''
  baselineDraft.value = currentDraft()
  draftConflict.value = false
  const saved = localDrafts.get(code)
  if (saved) {
    editSystem.value = saved.system
    editContent.value = saved.user
    changeSummary.value = saved.summary
    baselineDraft.value = saved.baseline
    draftConflict.value = saved.base !== (selected.value?.currentVersion || 0)
    refreshNotice.value = draftConflict.value
      ? '本地草稿基于其他版本，未自动提交；请核对或明确放弃后重读。'
      : '已恢复此场景尚未保存的本地草稿。'
  }
  router.replace({ hash: route.hash, query: { ...route.query, code } })
  if (activeTab.value === 'versions') await loadVersions()
  if (activeTab.value === 'effectiveness') await loadEffectiveness()
  if (activeTab.value === 'invocations') await loadInvocations()
}

/**
 * 启用或停用模板。
 *
 * `enabled` 决定 `getActiveContent` 能否取到该模板，是 Prompt 策略的生效开关。
 * 后端 2026-09-12 补上启停接口后，前端才能操作它（此前只能展示）。
 * 后端要求存在可核验的已批准生效版本，草稿不替代该版本。
 */
function receipt(data: PromptTemplateVO | null | undefined, code: string, state?: string, version?: number) {
  if (
    !data ||
    data.templateCode !== code ||
    !Number.isSafeInteger(data.currentVersion) ||
    data.currentVersion <= 0 ||
    typeof data.enabled !== 'boolean' ||
    (state && data.status !== state) ||
    (version !== undefined && data.currentVersion !== version)
  )
    throw new Error('策略回执错属或不完整，结果未知；先核对原版本，原输入保留。')
  return data
}
async function runWrite(
  label: string,
  permission: 'manage' | 'approve',
  write: () => Promise<{ data: PromptTemplateVO }>,
  confirm?: () => Promise<unknown>,
  preserveDirty = false,
) {
  if (
    !canView.value ||
    !(permission === 'manage' ? canManage.value : canApprove.value) ||
    saving.value ||
    actionLoading.value ||
    loading.value ||
    !selected.value ||
    !selectedScene.value ||
    draftConflict.value
  )
    return
  if (!Number.isSafeInteger(selected.value.currentVersion) || selected.value.currentVersion <= 0) {
    writeError.value = '精确版本未确认，未提交；请重读策略。'
    return
  }
  const k = session.capture(),
    revision = selectionRevision,
    editAtStart = editRevision,
    template = { ...selected.value },
    request = ++writeRequest,
    wasDirty = dirty.value,
    identity = session.auth.token,
    submittedDraft = currentDraft()
  if (label === '保存新草稿') saving.value = true
  else actionLoading.value = true
  writeError.value = ''
  try {
    if (confirm) await confirm()
    if (
      !session.current(k) ||
      revision !== selectionRevision ||
      !(permission === 'manage' ? canManage.value : canApprove.value)
    )
      return
    if (confirm && editAtStart !== editRevision) {
      writeError.value = '确认期间有新编辑，未提交操作；原输入保留。'
      return
    }
    const result = await trackGovernanceWrite(
      identity,
      [`prompt:${template.templateCode}`],
      `${label} ${template.templateCode} v${template.currentVersion}`,
      write,
    )
    if (!session.current(k)) return
    const updated = result.data
    knownVersions.set(
      template.templateCode,
      Math.max(knownVersions.get(template.templateCode) || 0, updated.currentVersion),
    )
    const observedVersion = templates.value.find(item=>item.templateCode===template.templateCode)?.currentVersion || 0
    knownVersions.set(template.templateCode,Math.max(knownVersions.get(template.templateCode) || 0,observedVersion))
    if(updated.currentVersion < observedVersion) {
      if(activeCode.value===template.templateCode) {
        refreshNotice.value='原操作已受理，但页面已读到更高版本；保留较新版本与当前输入，请核对后重读。'
        draftConflict.value=true
      }
      return
    }
    templates.value = templates.value.map((item) =>
      item.templateCode === template.templateCode ? updated : item,
    )
    if (revision !== selectionRevision) {
      // 同一账号切场景后，回执仍属于原策略；推进原对象缓存，避免返回时重发已保存内容。
      const parked = localDrafts.get(template.templateCode)
      if (parked) {
        if (!preserveDirty && JSON.stringify([parked.system, parked.user, parked.summary]) === submittedDraft)
          localDrafts.delete(template.templateCode)
        else if (!preserveDirty) {
          parked.base = updated.currentVersion
          const parts = policyParts(updated.content)
          parked.baseline = JSON.stringify([parts.system, parts.user, ''])
        }
      }
      if (!preserveDirty && activeCode.value === template.templateCode && currentDraft() === submittedDraft) {
        baselineDraft.value = currentDraft()
        draftConflict.value = false
        refreshNotice.value = '原场景操作已核回执；当前显示版本已更新。'
      }
      return
    }
    if (editAtStart !== editRevision || (preserveDirty && wasDirty)) {
      refreshNotice.value = '前一稿操作已受理；当前新编辑尚未保存，已保留本地输入。'
      return
    }
    localDrafts.delete(template.templateCode)
    baselineDraft.value = currentDraft()
    await selectTemplate(template.templateCode, true)
    await loadTemplates(template.templateCode)
  } catch (cause) {
    if (session.current(k) && revision === selectionRevision && cause !== 'cancel' && cause !== 'close')
      writeError.value = apiError(cause, '操作失败，原输入保留；结果未知先核对原版本')
  } finally {
    if (session.current(k) && request === writeRequest) {
      saving.value = false
      actionLoading.value = false
    }
  }
}
const confirmOptions = { closeOnClickModal: false, cancelButtonClass: session.cancelClass }
async function discardAndReload() {
  const k = session.capture(),
    revision = selectionRevision,
    code = activeCode.value
  try {
    await ElMessageBox.confirm(
      `放弃场景 ${code} 尚未保存的本地编辑并重读？`,
      '重读并放弃本地草稿',
      confirmOptions,
    )
    if (!session.current(k) || revision !== selectionRevision) return
    localDrafts.delete(code)
    baselineDraft.value = currentDraft()
    draftConflict.value = false
    await loadTemplates(code)
  } catch {}
}
async function toggleEnabled() {
  const t = selected.value
  if (!t || !selectedScene.value || !t.activeVersion) return
  const code = t.templateCode,
    next = !t.enabled
  await runWrite(
    next ? '启用模板' : '停用模板',
    'manage',
    async () => {
      const r = await setPromptEnabled(code, next)
      receipt(r?.data, code)
      if (r.data.enabled !== next) throw new Error('启停回执不符，结果未知；核对原策略')
      return r
    },
    () =>
      ElMessageBox.confirm(
        `${code}：${next ? '启用' : '停用'}已批准版本v${t.activeVersion}；停用回退内置内容，不关闭AI功能。`,
        next ? '启用模板' : '停用模板',
        confirmOptions,
      ),
    true,
  )
}

async function loadVersions() {
  const k = session.capture()
  if (!canView.value || !activeCode.value) return
  const request = ++versionsRequest
  const revision = selectionRevision
  const code = activeCode.value
  versionsLoading.value = true
  versionsError.value = ''
  try {
    const response = await getPromptVersions(code)
    if (session.current(k) && revision === selectionRevision && request === versionsRequest) {
      if (
        !Array.isArray(response.data) ||
        response.data.some(
          (v) => !Number.isSafeInteger(v.versionNo) || v.versionNo <= 0 || typeof v.content !== 'string',
        )
      )
        throw new Error('版本回执不完整，未应用')
      versions.value = response.data
    }
  } catch (cause) {
    if (!session.current(k) || revision !== selectionRevision || request !== versionsRequest) return
    versions.value = []
    // 接口失败必须呈现为错误态。此前只弹一次 toast 再置空数组，页面最终渲染成
    // 「暂无版本记录」——把失败说成了空数据（§11.3、§18）。
    versionsError.value = apiError(cause, '版本历史加载失败')
  } finally {
    if (session.current(k) && revision === selectionRevision && request === versionsRequest)
      versionsLoading.value = false
  }
}

async function loadEffectiveness() {
  if (!canView.value) return
  const k = session.capture()
  const request = ++effectsRequest,
    revision = selectionRevision,
    days = analysisDays.value
  effectLoading.value = true
  effectivenessError.value = ''
  effectiveness.value = []
  try {
    const result = await getPromptEffectiveness(days)
    if (
      session.current(k) &&
      request === effectsRequest &&
      revision === selectionRevision &&
      days === analysisDays.value
    ) {
      if (
        !Array.isArray(result.data) ||
        result.data.some(
          (row) =>
            !row.templateCode ||
            [
              'totalQueries',
              'successRate',
              'avgExecutionTimeMs',
              'feedbackCount',
              'positiveFeedbackCount',
            ].some(
              (key) =>
                typeof row[key as keyof PromptEffectivenessVO] !== 'number' ||
                !Number.isFinite(row[key as keyof PromptEffectivenessVO] as number),
            ),
        )
      )
        throw new Error('统计回执不完整，不能按零解释')
      effectiveness.value = result.data
    }
  } catch (cause) {
    if (session.current(k) && request === effectsRequest && revision === selectionRevision)
      effectivenessError.value = apiError(cause, '效果统计加载失败')
  } finally {
    if (session.current(k) && request === effectsRequest && revision === selectionRevision)
      effectLoading.value = false
  }
}

async function save() {
  const t = selected.value
  if (!t || !selectedScene.value || !canEdit.value || !editContent.value.trim()) return
  const code = t.templateCode,
    version = t.currentVersion,
    payload = { content: policyContent(), changeSummary: changeSummary.value || '手动编辑保存' }
  await runWrite('保存新草稿', 'manage', async () => {
    const r = await updatePromptTemplate(code, payload)
    receipt(r?.data, code, 'DRAFT')
    if (r.data.currentVersion <= version || r.data.content !== payload.content)
      throw new Error('新草稿回执未反映本次内容与新版本，结果未知；原输入保留。')
    return r
  })
}
function requireSavedDraft() {
  if (dirty.value) {
    writeError.value = '本地编辑尚未保存，请先保存新草稿，再审核本次精确版本。'
    return false
  }
  return true
}
async function submit() {
  const t = selected.value
  if (!t || t.status !== 'DRAFT' || !requireSavedDraft()) return
  const code = t.templateCode,
    v = t.currentVersion
  await runWrite(
    '提交审核',
    'manage',
    async () => {
      const r = await submitPromptForReview(code, v)
      receipt(r?.data, code, 'PENDING_REVIEW', v)
      return r
    },
    () =>
      ElMessageBox.confirm(`提交场景 ${code} v${v} 审核？审核完成前不能编辑。`, '提交审核', confirmOptions),
  )
}
async function approve() {
  const t = selected.value
  if (!t || t.status !== 'PENDING_REVIEW' || !requireSavedDraft()) return
  const code = t.templateCode,
    v = t.currentVersion
  let reason = ''
  await runWrite(
    '审核通过并激活',
    'approve',
    async () => {
      const r = await approvePrompt(code, v, reason)
      receipt(r?.data, code, 'APPROVED', v)
      if (!r.data.enabled || r.data.activeVersion !== v)
        throw new Error('激活回执不符，结果未知；先核对批准版本')
      return r
    },
    async () => {
      const r = await ElMessageBox.prompt(`批准场景 ${code} v${v} 并激活，影响后续此场景调用。`, '审核通过', {
        ...confirmOptions,
        inputPlaceholder: '变更摘要（可留空）',
        confirmButtonText: '确认通过',
        cancelButtonText: '取消',
      })
      reason = r.value
    },
  )
}
async function reject() {
  const t = selected.value
  if (!t || t.status !== 'PENDING_REVIEW' || !requireSavedDraft()) return
  const code = t.templateCode,
    v = t.currentVersion
  let reason = ''
  await runWrite(
    '审核拒绝',
    'approve',
    async () => {
      const r = await rejectPrompt(code, v, reason)
      receipt(r?.data, code, 'REJECTED', v)
      return r
    },
    async () => {
      const r = await ElMessageBox.prompt(`拒绝场景 ${code} v${v}，请说明原因`, '审核拒绝', {
        ...confirmOptions,
        inputValidator: (value) => Boolean(value?.trim()) || '拒绝原因不能为空',
        confirmButtonText: '确认拒绝',
        cancelButtonText: '取消',
      })
      reason = r.value
    },
  )
}

const previewVisible = ref(false)
const previewVersion = ref<PromptVersionVO | null>(null)

function showVersion(item: PromptVersionVO) {
  if (
    !canView.value ||
    !versions.value.some(
      (v) => v.id === item.id && v.versionNo === item.versionNo && v.content === item.content,
    )
  )
    return
  previewVersion.value = { ...item }
  previewVisible.value = true
}

async function rollback(item: PromptVersionVO) {
  if (!requireSavedDraft()) return
  const t = selected.value
  if (
    !t ||
    !selectedScene.value ||
    t.status === 'PENDING_REVIEW' ||
    !versions.value.some(
      (v) => v.id === item.id && v.versionNo === item.versionNo && v.content === item.content,
    )
  )
    return
  const code = t.templateCode,
    current = t.currentVersion,
    version = item.versionNo,
    content = item.content
  await runWrite(
    '版本回滚',
    'manage',
    async () => {
      const r = await rollbackPromptVersion(code, version)
      receipt(r?.data, code, 'DRAFT')
      if (r.data.currentVersion <= current || r.data.content !== content)
        throw new Error('回滚回执不符，结果未知；先核对原版本')
      return r
    },
    () =>
      ElMessageBox.confirm(
        `场景 ${code}：版本v${version}的内容写为新草稿，不立即替换当前生效版本。`,
        '版本回滚',
        { ...confirmOptions, confirmButtonText: '确认回滚', cancelButtonText: '取消' },
      ),
  )
}

onMounted(() => {
  void iamS1.load()
})
watch(
  session.key,
  () => {
    selectionRevision++
    loadRevision++
    versionsRequest++
    effectsRequest++
    invocationsRequest++
    validationRequest++
    writeRequest++
    templates.value = []
    scenes.value = []
    versions.value = []
    effectiveness.value = []
    invocationRows.value = []
    invocationTotal.value = 0
    editSystem.value = ''
    editContent.value = ''
    changeSummary.value = ''
    baselineDraft.value = ''
    localDrafts.clear()
    knownVersions.clear()
    draftConflict.value = false
    saving.value = false
    actionLoading.value = false
    loading.value = false
    versionsLoading.value = false
    effectLoading.value = false
    invocationLoading.value = false
    validating.value = false
    validation.value = ''
    writeError.value = ''
    error.value = ''
    refreshNotice.value = ''
    previewVisible.value = false
    previewVersion.value = null
    if (canView.value) void loadTemplates()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  selectionRevision++
  loadRevision++
  versionsRequest++
  effectsRequest++
  invocationsRequest++
  validationRequest++
  writeRequest++
  localDrafts.clear()
  knownVersions.clear()
})

watch(analysisDays, () => {
  router.replace({ hash: route.hash, query: { ...route.query, days: String(analysisDays.value) } })
  if (activeTab.value === 'effectiveness') loadEffectiveness()
})

watch([editSystem, editContent], () => {
  validation.value = ''
})
watch(
  [editSystem, editContent, changeSummary],
  () => {
    editRevision++
  },
  { flush: 'sync' },
)

// 浏览器前进/后退或外部改 URL 时同步回组件状态（§15 要求前进后退可恢复）
watch(
  () => route.query.tab,
  (value) => {
    const next = String(value || 'content')
    if (next === activeTab.value) return
    activeTab.value = next
    if (next === 'versions' && !versions.value.length) loadVersions()
    if (next === 'invocations') loadInvocations()
    if (next === 'effectiveness' && !effectiveness.value.length) loadEffectiveness()
  },
)

watch(
  () => route.query.code,
  (value) => {
    const next = String(value || '')
    // 自身写入 URL 时不再回环；仅在 URL 指向别的模板时才切换
    if (!next || next === activeCode.value) return
    selectTemplate(next)
  },
)
</script>

<template>
  <div class="admin-page prompts-page">
    <GovernanceWriteNotice />
    <p v-if="writeError" class="prompt-write-error" role="alert">{{ writeError }}</p>
    <p v-if="dirty" class="note">
      本地编辑尚未保存。
      <el-button text @click="discardAndReload">重读并放弃本地草稿</el-button>
    </p>
    <TaskPageHeader
      eyebrow="运营与平台"
      title="Prompt 策略"
      description="按真实 AI 场景维护策略。草稿与生效版本独立；权限、输出格式和执行门禁由程序强制校验。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadTemplates(activeCode)">刷新</el-button>
        <el-button
          v-if="canEdit && selected"
          :type="status === 'APPROVED' ? 'primary' : 'default'"
          :icon="Save"
          :loading="saving"
          :disabled="!canManage || saving || actionLoading || loading || draftConflict"
          @click="save"
        >
          保存并创建新版本
        </el-button>
        <el-button
          v-if="selectedScene && selected && status === 'DRAFT'"
          type="primary"
          :icon="Send"
          :loading="actionLoading"
          :disabled="!canManage || saving || actionLoading || loading || draftConflict"
          @click="submit"
        >
          提交审核
        </el-button>
        <template v-if="selectedScene && selected && status === 'PENDING_REVIEW'">
          <el-button
            type="primary"
            :icon="CheckCircle2"
            :loading="actionLoading"
            :disabled="!canApprove || saving || actionLoading || loading || draftConflict"
            :title="canApprove ? '' : APPROVE_HINT"
            @click="approve"
          >
            审核通过并激活
          </el-button>
          <el-button
            type="danger"
            plain
            :icon="XCircle"
            :loading="actionLoading"
            :disabled="!canApprove || saving || actionLoading || loading || draftConflict"
            :title="canApprove ? '' : APPROVE_HINT"
            @click="reject"
          >
            审核拒绝
          </el-button>
        </template>
      </template>
    </TaskPageHeader>

    <details v-if="canView && !loading && !error" class="prompt-coverage">
      <summary>策略如何生效</summary>
      <p>
        当前
        {{ scenes.length }}
        类业务生成与核验场景均已接入策略解析。配置不存在、停用、不可用或合同无效时使用内置策略；实际来源以调用记录为准。
      </p>
      <dl class="facts">
        <div>
          <dt>可编辑策略</dt>
          <dd>角色指令与任务指令一起版本化。仅支持场景提供的简单变量；校验预览使用合成数据，不调用模型。</dd>
        </div>
        <div>
          <dt>固定合同</dt>
          <dd>
            上下文权限过滤、JSON/SQL 输出校验、模型预算和人工审核由程序负责；停用后台策略不会关闭 AI 功能。
          </dd>
        </div>
        <div>
          <dt>历史模板</dt>
          <dd>intent_recognition 已退役，不再允许编辑、审核激活或启停；历史版本保留。</dd>
        </div>
        <div>
          <dt>其他 AI 调用</dt>
          <dd>向量化、检索向量生成及模型连通性检测不属于这里的业务生成模板。</dd>
        </div>
      </dl>
    </details>

    <p v-if="refreshNotice" class="note" role="status">{{ refreshNotice }}</p>
    <ErrorState v-if="error" :message="error" @retry="loadTemplates()" />

    <div v-else class="prompts-layout">
      <aside class="panel">
        <header class="panel__heading">
          <div>
            <h2>AI 场景目录</h2>
            <p v-if="canView && !loading">
              {{ scenes.length }} 个调用场景 · {{ templates.length }} 条配置记录
            </p>
          </div>
        </header>
        <el-input v-model="keyword" placeholder="搜索编码 / 名称 / 场景" clearable :prefix-icon="Search" />
        <LoadingState v-if="loading" text="正在读取模板…" />
        <LoadingState v-else-if="!iamS1.loaded && !iamS1.errorMessage" text="正在确认当前能力…" />
        <ErrorState v-else-if="iamS1.errorMessage" :message="iamS1.errorMessage" @retry="iamS1.load(true)" />
        <EmptyState v-else-if="!canView" :message="VIEW_HINT" />
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
              <span class="template-list__meta">
                {{ scenes.find((scene) => scene.code === item.templateCode)?.group || '历史配置' }}
              </span>
              <small>{{ item.templateCode }} · {{ consumerLabel(item.templateCode) }}</small>
              <span class="template-list__meta">
                v{{ item.currentVersion }} · {{ item.scenario || '未标注场景' }}
              </span>
            </button>
            <span v-if="!item.id" class="muted">配置未初始化 · 使用内置策略</span>
            <span v-else-if="!scenes.some((scene) => scene.code === item.templateCode)" class="muted">
              未列当前调用方 · 仅供查看
            </span>
            <BusinessStatusBadge v-else :status="item.status" />
          </li>
        </ul>
      </aside>

      <section class="panel detail">
        <details v-if="selectedScene" class="prompt-coverage">
          <summary>{{ selectedScene.name }} · 场景合同 v{{ selectedScene.contractVersion }}</summary>
          <dl class="facts">
            <div>
              <dt>调用方</dt>
              <dd>{{ selectedScene.consumer }}</dd>
            </div>
            <div>
              <dt>运行时变量</dt>
              <dd>{{ selectedScene.variables.join('、') }}（input 是权限筛选后的结构化 JSON）</dd>
            </div>
            <div v-for="variable in selectedScene.variables" :key="variable">
              <dt>{{ variable }}</dt>
              <dd>{{ selectedScene.variableDescriptions?.[variable] || '由调用方提供的场景变量' }}</dd>
            </div>
            <div>
              <dt>输出合同</dt>
              <dd>{{ selectedScene.outputContract }}</dd>
            </div>
            <div>
              <dt>固定约束</dt>
              <dd>{{ selectedScene.fixedConstraints }}</dd>
            </div>
          </dl>
        </details>
        <EmptyState
          v-if="!selected"
          :message="
            selectedScene
              ? '此场景已接入调用，但后台配置尚未初始化。当前页面可查看合同，初始化需由维护者按项目流程处理；目前使用内置策略。'
              : '请选择左侧场景查看策略、版本和调用来源。'
          "
        />
        <template v-else>
          <header class="detail__heading">
            <div>
              <h2>{{ selected.templateName }}</h2>
              <p>
                {{ selected.templateCode }} · 编辑版本 v{{ selected.currentVersion }} · 生效版本
                {{ selected.activeVersion ? 'v' + selected.activeVersion : '暂无' }}
                <template v-if="nodeLabel">· 调用方：{{ nodeLabel }}</template>
              </p>
            </div>
            <div class="detail__badges">
              <BusinessStatusBadge :status="selected.status" />
              <el-tag :type="selected.enabled && selectedScene ? 'success' : 'info'" size="small">
                {{
                  !selectedScene ? '未列当前调用方' : selected.enabled ? '优先使用后台策略' : '使用内置策略'
                }}
              </el-tag>
              <el-button
                v-if="selectedScene && selected.activeVersion"
                size="small"
                :icon="Power"
                :type="selected.enabled ? 'danger' : 'primary'"
                plain
                :loading="actionLoading"
                :disabled="!canManage || saving || actionLoading || loading || draftConflict"
                :title="canManage ? '' : MANAGE_HINT"
                @click="toggleEnabled"
              >
                {{ selected.enabled ? '改用内置策略' : '使用后台策略' }}
              </el-button>
            </div>
          </header>

          <el-tabs :model-value="activeTab" @update:model-value="selectTab">
            <el-tab-pane label="策略内容" name="content">
              <p v-if="status === 'PENDING_REVIEW'" class="note">
                待审核状态的模板不能编辑。请先完成审核，再创建新版本。
              </p>
              <label for="prompt-system">角色指令（不允许动态变量）</label>
              <el-input
                id="prompt-system"
                v-model="editSystem"
                type="textarea"
                :rows="4"
                :disabled="!canEdit || !canManage"
                class="content-editor"
              />
              <label for="prompt-user">任务指令与变量</label>
              <el-input
                id="prompt-user"
                v-model="editContent"
                type="textarea"
                :rows="12"
                :disabled="!canEdit || !canManage"
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
                <el-button
                  :loading="validating"
                  :disabled="!canManage || saving || actionLoading || loading || draftConflict"
                  @click="validatePolicy"
                >
                  校验并预览
                </el-button>
                <el-button
                  type="primary"
                  :icon="Save"
                  :loading="saving"
                  :disabled="!canManage || saving || actionLoading || loading || draftConflict"
                  @click="save"
                >
                  保存并创建新版本
                </el-button>
              </div>
              <p class="note">
                保存会新建一个草稿版本，模板状态回到草稿；只有审核通过后新内容才会成为活跃模板。
              </p>
              <pre v-if="validation" class="dialog-pre" role="status">{{ validation }}</pre>
              <details v-if="selected.activeVersion" class="prompt-coverage">
                <summary>
                  查看已批准生效版本 v{{ selected.activeVersion }}（{{
                    selected.enabled ? '优先使用' : '当前停用'
                  }}）
                </summary>
                <pre class="dialog-pre"
                  >{{ policyParts(selected.activeContent || '').system }}
{{ policyParts(selected.activeContent || '').user }}</pre
                >
              </details>
            </el-tab-pane>

            <el-tab-pane label="审核流程" name="review">
              <dl class="facts">
                <div>
                  <dt>当前状态</dt>
                  <dd><BusinessStatusBadge :status="selected.status" /></dd>
                </div>
                <div>
                  <dt>当前版本</dt>
                  <dd>v{{ selected.currentVersion }}</dd>
                </div>
                <div>
                  <dt>最近更新</dt>
                  <dd>{{ selected.updatedAt || '—' }}</dd>
                </div>
              </dl>
              <p class="note">
                状态流转为 草稿 → 待审核 → 已批准 或 已拒绝。已批准表示该版本是当前生效的活跃模板，
                与知识文档的「已发布」含义不同，页面不做混用。
              </p>
              <div class="flow-actions">
                <el-button
                  v-if="selectedScene && status === 'DRAFT'"
                  type="primary"
                  :icon="Send"
                  :loading="actionLoading"
                  :disabled="!canManage || saving || actionLoading || loading || draftConflict"
                  @click="submit"
                >
                  提交审核
                </el-button>
                <template v-if="selectedScene && status === 'PENDING_REVIEW'">
                  <el-button
                    type="primary"
                    :icon="CheckCircle2"
                    :loading="actionLoading"
                    :disabled="!canApprove || saving || actionLoading || loading || draftConflict"
                    :title="canApprove ? '' : APPROVE_HINT"
                    @click="approve"
                  >
                    审核通过并激活
                  </el-button>
                  <el-button
                    type="danger"
                    plain
                    :icon="XCircle"
                    :loading="actionLoading"
                    :disabled="!canApprove || saving || actionLoading || loading || draftConflict"
                    :title="canApprove ? '' : APPROVE_HINT"
                    @click="reject"
                  >
                    审核拒绝
                  </el-button>
                </template>
                <span v-if="status === 'APPROVED'" class="note">
                  此版本已批准；是否使用后台策略由独立开关控制。修改后保存草稿，不影响当前生效内容。
                </span>
              </div>
            </el-tab-pane>

            <el-tab-pane label="版本历史" name="versions">
              <div class="tab-actions">
                <el-button :icon="RefreshCw" :loading="versionsLoading" @click="loadVersions">
                  刷新版本
                </el-button>
              </div>
              <ErrorState v-if="versionsError" :message="versionsError" @retry="loadVersions" />
              <LoadingState v-else-if="versionsLoading" variant="skeleton" :rows="4" />
              <EmptyState v-else-if="!versions.length" message="暂无版本记录。保存内容后会产生版本。" />
              <el-table v-else :data="versions" stripe>
                <el-table-column label="版本" width="90">
                  <template #default="{ row }">v{{ row.versionNo }}</template>
                </el-table-column>
                <el-table-column label="状态" width="120">
                  <template #default="{ row }"><BusinessStatusBadge :status="row.status" /></template>
                </el-table-column>
                <el-table-column label="生效" width="80">
                  <template #default="{ row }">
                    <el-tag v-if="row.isActive" type="success" size="small">
                      {{ selectedScene && selected.enabled ? '当前生效' : '保留活跃版本' }}
                    </el-tag>
                    <span v-else class="muted">—</span>
                  </template>
                </el-table-column>
                <el-table-column
                  prop="changeSummary"
                  label="变更摘要"
                  min-width="200"
                  show-overflow-tooltip
                />
                <el-table-column prop="createdAt" label="创建时间" width="175" />
                <el-table-column label="操作" width="150" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="showVersion(row)">查看</el-button>
                    <el-button
                      v-if="selectedScene && !row.isActive && status !== 'PENDING_REVIEW' && canManage"
                      link
                      type="warning"
                      :loading="actionLoading"
                      @click="rollback(row)"
                    >
                      <RotateCcw :size="14" />
                      回滚
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane v-if="selectedScene" label="调用记录" name="invocations">
              <p class="note">
                模型返回不等于格式、业务或人工审核通过。回执保存失败会记录服务日志，不能视为完整计费账本。
              </p>
              <el-button :loading="invocationLoading" @click="loadInvocations(invocationPage)">
                刷新调用记录
              </el-button>
              <ErrorState
                v-if="invocationError"
                :message="invocationError"
                @retry="loadInvocations(invocationPage)"
              />
              <LoadingState v-else-if="invocationLoading" text="正在读取调用记录…" />
              <EmptyState
                v-else-if="!invocationRows.length"
                message="暂无调用回执，不能据此断言场景未执行。"
              />
              <el-table v-else :data="invocationRows">
                <el-table-column prop="createdAt" label="调用时间" width="175" />
                <el-table-column label="实际策略" min-width="150">
                  <template #default="{ row }">
                    {{ row.source === 'MANAGED' ? '后台 v' + row.versionNo : '内置策略' }} · 合同 v{{
                      row.contractVersion
                    }}
                  </template>
                </el-table-column>
                <el-table-column label="回退原因" min-width="160">
                  <template #default="{ row }">{{ fallbackLabel(row.fallbackReason) }}</template>
                </el-table-column>
                <el-table-column label="模型状态" min-width="125">
                  <template #default="{ row }">
                    {{ row.outcome === 'MODEL_RETURNED' ? '已返回待校验内容' : '调用失败' }}
                  </template>
                </el-table-column>
                <el-table-column prop="durationMs" label="耗时 ms" width="100" />
                <el-table-column
                  prop="contentHash"
                  label="调用内容摘要指纹"
                  min-width="150"
                  show-overflow-tooltip
                />
                <el-table-column prop="taskId" label="关联任务" min-width="140" show-overflow-tooltip />
              </el-table>
              <el-pagination
                :current-page="invocationPage"
                :page-size="10"
                :total="invocationTotal"
                layout="total, prev, pager, next"
                @current-change="loadInvocations"
              />
            </el-tab-pane>
            <el-tab-pane label="效果说明与历史统计" name="effectiveness">
              <p class="note">
                {{
                  selectedScene?.metric || '已退役配置，仅保留历史关联数据。'
                }}。下方历史统计来自查询任务审计，不能作为单次模型调用成功率、人工采纳率或当前完整覆盖率。
              </p>
              <div class="tab-actions">
                <el-select v-model="analysisDays" style="width: 130px">
                  <el-option :label="'近 7 天'" :value="7" />
                  <el-option :label="'近 30 天'" :value="30" />
                  <el-option :label="'近 90 天'" :value="90" />
                </el-select>
                <el-button :icon="RefreshCw" :loading="effectLoading" @click="loadEffectiveness">
                  刷新统计
                </el-button>
              </div>
              <ErrorState
                v-if="effectivenessError"
                :message="effectivenessError"
                @retry="loadEffectiveness"
              />
              <LoadingState v-else-if="effectLoading" variant="skeleton" :rows="4" />
              <EmptyState
                v-else-if="!activeEffectiveness.length"
                message="暂无该场景的历史任务统计，不能按零成功率解释。"
              />
              <template v-else>
                <section class="metric-row">
                  <div class="metric">
                    <span>查询总数</span>
                    <strong>{{ summaryStats.totalQueries }}</strong>
                  </div>
                  <div class="metric">
                    <span>加权成功率</span>
                    <strong>{{ formatPercent(summaryStats.avgSuccess) }}</strong>
                  </div>
                  <div class="metric">
                    <span title="各版本平均耗时的均值，未按查询数加权">版本均值耗时</span>
                    <strong>{{ formatTime(summaryStats.avgTime) }}</strong>
                  </div>
                  <div class="metric">
                    <span>反馈数</span>
                    <strong>{{ summaryStats.feedbackCount }}</strong>
                  </div>
                  <div class="metric">
                    <span>正向反馈率</span>
                    <strong>{{ formatPercent(summaryStats.positiveRate) }}</strong>
                  </div>
                </section>
                <EmptyState
                  v-if="!activeEffectiveness.length"
                  message="该模板在所选时间范围内没有查询记录，无法给出效果结论。"
                />
                <el-table v-else :data="activeEffectiveness" stripe size="small">
                  <el-table-column prop="templateCode" label="模板编码" width="170" />
                  <el-table-column label="版本" width="80">
                    <template #default="{ row }">v{{ row.versionNo }}</template>
                  </el-table-column>
                  <el-table-column prop="totalQueries" label="查询数" width="90" />
                  <el-table-column label="成功率" width="100">
                    <template #default="{ row }">{{ formatPercent(row.successRate) }}</template>
                  </el-table-column>
                  <el-table-column label="平均耗时" width="110">
                    <template #default="{ row }">{{ formatTime(row.avgExecutionTimeMs) }}</template>
                  </el-table-column>
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

    <el-dialog
      v-model="previewVisible"
      :title="`${activeCode} · 版本 ${previewVersion?.versionNo} 内容`"
      width="760px"
    >
      <pre class="dialog-pre">{{ previewVersion?.content || '（空内容）' }}</pre>
    </el-dialog>
  </div>
</template>

<style scoped>
.prompt-write-error {
  margin: 0;
  padding: 12px;
  border-left: 3px solid var(--do-danger);
  color: var(--do-danger);
  font-size: 13px;
}
.prompts-page :deep(.el-dialog) {
  max-width: calc(100vw - 24px);
}
.prompts-page {
  display: grid;
  gap: 16px;
}

.prompts-layout {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.panel {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: none;
}

.panel__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;
}
.panel__heading h2 {
  margin: 0;
  color: var(--do-ink);
  font-size: 15px;
}
.panel__heading p {
  margin: 4px 0 0;
  color: var(--do-muted);
  font-size: 12px;
}

.template-list {
  display: grid;
  gap: 8px;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
  max-height: 620px;
  overflow: auto;
}
.template-list__item {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
}
.template-list__item.is-active {
  border-color: var(--do-primary);
  background: var(--do-primary-soft);
}
.template-list__select {
  display: grid;
  gap: 3px;
  width: 100%;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.template-list__select strong {
  color: var(--do-ink);
  font-size: 13px;
}
.template-list__select small {
  color: var(--do-muted);
  font-size: 11px;
}
.template-list__meta {
  color: var(--do-muted);
  font-size: 11px;
}

.detail > * { min-width:0; max-width:100%; }
.detail :deep(.el-tabs) { min-width:0; width:100%; }
.detail {
  display: grid;
  gap: 8px;
}
.detail__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.detail__heading h2 {
  margin: 0;
  color: var(--do-ink);
  font-size: 17px;
}
.detail__heading p {
  margin: 4px 0 0;
  color: var(--do-muted);
  font-size: 12px;
}
.detail__badges {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.detail__heading {
  flex-wrap: wrap;
}
.content-editor {
  margin: 6px 0 12px;
}
.facts dd {
  overflow-wrap: anywhere;
}

.content-editor :deep(.el-textarea__inner) {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  line-height: 1.7;
}
.content-summary {
  margin-top: 10px;
}
.content-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.content-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.tab-actions {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.flow-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 12px;
}

.facts {
  display: grid;
  gap: 8px;
  margin: 0 0 12px;
}
.facts > div {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 10px;
}
.facts dt {
  color: var(--do-muted);
  font-size: 12px;
}
.facts dd {
  margin: 0;
  color: var(--do-ink);
  font-size: 13px;
}

.prompt-coverage {
  color: var(--do-ink);
  font-size: 13px;
  line-height: 1.7;
}
.prompt-coverage summary {
  cursor: pointer;
  padding: 8px 0;
}
.prompt-coverage summary:focus-visible {
  outline: 2px solid var(--do-primary);
  outline-offset: 2px;
}
.prompt-coverage p {
  margin: 4px 0 12px;
}
.note {
  margin: 10px 0 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}
.muted {
  color: var(--do-muted);
  font-size: 12px;
}

.metric-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.metric {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
}
.metric span {
  color: var(--do-muted);
  font-size: 12px;
}
.metric strong {
  color: var(--do-ink);
  font-size: 20px;
}

.dialog-pre {
  max-height: 520px;
  margin: 0;
  padding: 14px;
  overflow: auto;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
  color: var(--do-ink);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 1180px) {
  .prompts-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .metric-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
