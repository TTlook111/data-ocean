<script setup lang="ts">
/** AI配置工作面：active、pending与源级RAG分别核验；全部动作仍由Java鉴权。 */
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import {
  Database,
  HardDrive,
  Plus,
  RefreshCw,
  Server,
  Sparkles,
  Trash2,
  Wifi,
  Check,
  Edit,
  Ruler,
} from 'lucide-vue-next'
import {
  createAiProvider,
  deleteAiProvider,
  getAiConfig,
  syncAiProviderModels,
  testAiProvider,
  updateAiConfig,
  updateAiProvider,
  type AiConfig,
  type AiProvider,
  type AiProviderPayload,
} from '../../../api/admin/system'
import { useIamWorkspaceSession } from '../../../composables/useIamWorkspaceSession'
import { trackGovernanceWrite } from '../../../composables/governanceWriteTracker'
import GovernanceWriteNotice from '../../../components/admin/GovernanceWriteNotice.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const session = useIamWorkspaceSession()
const readError = ref(''),
  writeError = ref('')
let readEpoch = 0
const dimensionDrafts = reactive<Record<string, number | undefined>>({})
const dimensionKey = (id: string, name: string) => JSON.stringify([id, name])
const dimensionFor = (id: string, model: { name: string; dimension?: number }) =>
  dimensionKey(id, model.name) in dimensionDrafts
    ? dimensionDrafts[dimensionKey(id, model.name)]
    : model.dimension
const setDimension = (id: string, name: string, value: unknown) => {
  dimensionDrafts[dimensionKey(id, name)] =
    typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

const loading = ref(false)
const saving = ref(false)
const providerDialogVisible = ref(false)
const providerDialogSession = ref(0)
const providerBase = ref('')
const editingProviderId = ref('')
const config = ref<AiConfig | null>(null)
const activeTab = ref<'chat' | 'embedding'>(route.query.tab === 'embedding' ? 'embedding' : 'chat')

function selectTab(tab: 'chat' | 'embedding') {
  activeTab.value = tab
  router.push({ query: { ...route.query, tab } })
}

// 浏览器前进/后退或外部改 URL 时同步回组件状态（§15 要求前进后退可恢复）
watch(
  () => route.query.tab,
  (value) => {
    const next = value === 'embedding' ? 'embedding' : 'chat'
    if (next !== activeTab.value) activeTab.value = next
  },
)

const detectingDimension = ref(false)
const syncingProviderId = ref('')

/** Embedding 配置状态的中文标签（§11.3：状态标签使用中文，同时保留技术状态说明） */
type TagTone = 'success' | 'warning' | 'danger' | 'info'
const VECTORIZE_STATUS: Record<string, { label: string; tone: TagTone }> = {
  NORMAL: { label: '正常', tone: 'success' },
  UNKNOWN: { label: '未确认', tone: 'info' },
  REINDEX_REQUIRED: { label: '需要重建索引', tone: 'warning' },
  REINDEXING: { label: '索引重建中', tone: 'warning' },
  REINDEX_FAILED: { label: '索引重建失败', tone: 'danger' },
}

const vectorizeStatusLabel = computed(() => {
  const status = vectorizeStatus.value?.status || 'UNKNOWN'
  return VECTORIZE_STATUS[status]?.label || status
})

const vectorizeTone = computed<TagTone>(() => {
  const status = vectorizeStatus.value?.status || 'UNKNOWN'
  return VECTORIZE_STATUS[status]?.tone || 'info'
})

// 展开的卡片 ID
const expandedChatProvider = ref<string>('')
const expandedEmbeddingProvider = ref<string>('')

// 编辑中的卡片配置
const editingChatConfig = reactive({
  providerId: '',
  model: '',
  temperature: '0.3',
  timeout: '120',
  maxRetries: '2',
})

const editingEmbeddingConfig = reactive({
  providerId: '',
  model: '',
  dimension: 1024,
})

const providerForm = reactive<AiProviderPayload>({
  id: '',
  name: '',
  baseUrl: '',
  apiKey: '',
})

const providers = computed(() => config.value?.providers ?? [])
const canViewAiConfig = computed(() => session.canGlobal('system:ai-config:view'))
const canManageAiConfig = computed(() => session.canGlobal('system:ai-config:manage'))

const activeChat = computed(() => config.value?.activeChat)
const activeEmbedding = computed(() => config.value?.activeEmbedding)
const vectorizeStatus = computed(() => config.value?.vectorizeStatus)

const vectorizeMessage = computed(() => {
  if (vectorizeStatus.value?.status === 'REINDEX_REQUIRED') {
    return '已有待切换配置；各源RAG构建与全局active分别核验，不保证自动切换。'
  }
  if (vectorizeStatus.value?.status === 'REINDEXING') {
    return '索引正在重建，查询仍使用上一版 active 索引'
  }
  if (vectorizeStatus.value?.status === 'REINDEX_FAILED') {
    return '索引重建失败，查询仍使用上一版 active 索引'
  }
  return ''
})

// Providers remain maintainable before the first successful model sync.
const chatProviders = computed(() => providers.value)
const embeddingProviders = computed(() => providers.value)

function isChatActive(providerId: string, model: string) {
  return activeChat.value?.providerId === providerId && activeChat.value?.model === model
}

function isEmbeddingActive(providerId: string, model: string, dimension?: number) {
  return (
    activeEmbedding.value?.providerId === providerId &&
    activeEmbedding.value?.model === model &&
    activeEmbedding.value?.dimension === dimension
  )
}

function expandChatProvider(provider: AiProvider) {
  expandedChatProvider.value = provider.id
  editingChatConfig.providerId = provider.id
  editingChatConfig.model = activeChat.value?.providerId === provider.id ? activeChat.value.model : ''
  editingChatConfig.temperature =
    activeChat.value?.providerId === provider.id ? activeChat.value.temperature || '0.3' : '0.3'
  editingChatConfig.timeout =
    activeChat.value?.providerId === provider.id ? activeChat.value.timeout || '120' : '120'
  editingChatConfig.maxRetries =
    activeChat.value?.providerId === provider.id ? activeChat.value.maxRetries || '2' : '2'
}

function expandEmbeddingProvider(provider: AiProvider) {
  expandedEmbeddingProvider.value = provider.id
  editingEmbeddingConfig.providerId = provider.id
  editingEmbeddingConfig.model =
    activeEmbedding.value?.providerId === provider.id ? activeEmbedding.value.model : ''
  editingEmbeddingConfig.dimension =
    activeEmbedding.value?.providerId === provider.id ? Number(activeEmbedding.value.dimension || 1024) : 1024
}

function validatedConfig(data: AiConfig) {
  if (
    !data ||
    !Array.isArray(data.providers) ||
    data.providers.some((p) => !p.id || typeof p.id !== 'string') ||
    new Set(data.providers.map((p) => p.id)).size !== data.providers.length
  )
    throw new Error('配置回执不完整，不能确认为已配置')
  return data
}
function message(cause: unknown, fallback: string) {
  return (
    (cause as { response?: { data?: { message?: string } } })?.response?.data?.message ||
    (cause instanceof Error ? cause.message : fallback)
  )
}
async function fetchConfig() {
  if (!canViewAiConfig.value || loading.value) return
  const k = session.capture(),
    r = ++readEpoch
  loading.value = true
  readError.value = ''
  try {
    const result = await getAiConfig()
    if (!session.current(k) || r !== readEpoch) return
    config.value = validatedConfig(result.data)
    if (!expandedChatProvider.value && activeChat.value?.providerId)
      expandChatProvider({ id: activeChat.value.providerId } as AiProvider)
    if (!expandedEmbeddingProvider.value && activeEmbedding.value?.providerId)
      expandEmbeddingProvider({ id: activeEmbedding.value.providerId } as AiProvider)
  } catch (cause) {
    if (session.current(k) && r === readEpoch) {
      config.value = null
      readError.value = message(cause, '配置读取失败')
    }
  } finally {
    if (session.current(k) && r === readEpoch) loading.value = false
  }
}
async function mutate(
  label: string,
  write: () => Promise<unknown>,
  confirm: string,
  preflight?: (fresh: AiConfig) => void,
  accepted?: () => void,
) {
  if (!canViewAiConfig.value || !canManageAiConfig.value || saving.value) return
  const k = session.capture(),
    identity = session.auth.token
  saving.value = true
  writeError.value = ''
  try {
    await ElMessageBox.confirm(confirm, label, {
      type: 'warning',
      confirmButtonText: '确认执行',
      cancelButtonText: '取消',
      closeOnClickModal: false,
      cancelButtonClass: session.cancelClass,
    })
    if (!session.current(k) || !canManageAiConfig.value) return
    if (preflight) {
      const fresh = validatedConfig((await getAiConfig()).data)
      if (!session.current(k) || !canManageAiConfig.value) return
      preflight(fresh)
    }
    await trackGovernanceWrite(identity, ['ai:configuration'], label, write)
    if (!session.current(k)) return
    accepted?.()
    readEpoch++
    loading.value = false
    await fetchConfig()
  } catch (cause) {
    if (session.current(k) && cause !== 'cancel' && cause !== 'close') {
      writeError.value = message(cause, '操作失败；结果未知时先核对配置，不自动重发')
      if (providerForm.apiKey) {
        providerForm.apiKey = ''
        writeError.value += '；密钥输入已清除，其余草稿保留。'
      }
    }
  } finally {
    if (session.current(k)) saving.value = false
  }
}
const hasPending = (c: AiConfig) =>
  !!(
    c.pendingEmbedding ||
    c.vectorizeStatus?.pending ||
    ['REINDEX_REQUIRED', 'REINDEXING', 'REINDEX_FAILED'].includes(c.vectorizeStatus?.status || '')
  )
function chatPreflight(expected: string) {
  return (fresh: AiConfig) => {
    if (hasPending(fresh))
      throw new Error(
        '已有待切换Embedding；当前Chat保存接口可能取消该配置，本页未提交。需先单独确认后端合同。',
      )
    if (JSON.stringify(fresh.activeChat) !== expected)
      throw new Error('当前Chat配置已变化，未提交；人工草稿保留，请核对后重开。')
  }
}

function embeddingChanged(providerId: string, model: string, dimension: number) {
  const active = activeEmbedding.value
  if (!active) return false
  return (
    active.providerId !== providerId ||
    active.model !== model ||
    Number(active.dimension) !== Number(dimension)
  )
}

function buildCollectionName(providerId: string, model: string, dimension: number) {
  const safeProvider = providerId.replace(/[^a-zA-Z0-9_]/g, '_')
  const safeModel = model.replace(/[^a-zA-Z0-9_]/g, '_')
  return `schema_knowledge_${dimension}_${safeProvider}_${safeModel}`
}

function validChatDraft(chat: { temperature: string; timeout: string; maxRetries?: string }) {
  const scalar = (v: unknown) =>
    typeof v === 'string' && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(v.trim()) && Number.isFinite(Number(v))
  const valid =
    scalar(chat.temperature) &&
    Number(chat.temperature) >= 0 &&
    Number(chat.temperature) <= 2 &&
    typeof chat.timeout === 'string' &&
    /^\d+$/.test(chat.timeout.trim()) &&
    Number.isSafeInteger(Number(chat.timeout)) &&
    Number(chat.timeout) > 0 &&
    typeof chat.maxRetries === 'string' &&
    /^\d+$/.test(chat.maxRetries.trim()) &&
    Number.isSafeInteger(Number(chat.maxRetries)) &&
    Number(chat.maxRetries) >= 0
  if (!valid) writeError.value = '温度须为0–2的有限数，超时为正整数秒，重试次数为非负整数；未提交配置。'
  if (valid) {
    chat.temperature = String(Number(chat.temperature))
    chat.timeout = String(Number(chat.timeout))
    chat.maxRetries = String(Number(chat.maxRetries))
  }
  return valid
}
function verifyTarget(
  fresh: AiConfig,
  id: string,
  model: string,
  kind: 'chatModels' | 'embeddingModels',
  expected: string,
) {
  const provider = fresh.providers.find((p) => p.id === id)
  if (!provider || !provider[kind]?.some((m) => m.name === model) || JSON.stringify(provider) !== expected)
    throw new Error('目标供应商或模型已变化/未返回，未提交配置；原草稿保留。')
}
function verifyChatAck(
  data: AiConfig,
  wanted: { providerId: string; model: string; temperature: string; timeout: string; maxRetries?: string },
) {
  const active = data.activeChat
  const numeric = (v: unknown) =>
    ((typeof v === 'string' && v.trim() !== '') || typeof v === 'number') && Number.isFinite(Number(v))
  if (
    !active ||
    active.providerId !== wanted.providerId ||
    active.model !== wanted.model ||
    ['temperature', 'timeout', 'maxRetries'].some((key) => {
      const k = key as 'temperature' | 'timeout' | 'maxRetries'
      return (
        wanted[k] !== undefined &&
        (!numeric(active[k]) || !numeric(wanted[k]) || Number(active[k]) !== Number(wanted[k]))
      )
    })
  )
    throw new Error('Chat配置回执与本次参数不符，结果未知；先核对配置')
}
function verifyEmbeddingAck(
  data: AiConfig,
  wanted: { providerId: string; model: string; dimension: number },
) {
  if (
    ![data.activeEmbedding, data.pendingEmbedding, data.vectorizeStatus?.pending].some(
      (x) =>
        x?.providerId === wanted.providerId && x?.model === wanted.model && x?.dimension === wanted.dimension,
    )
  )
    throw new Error('Embedding回执未反映本次精确模型与维度，结果未知；先核对配置')
}
async function handleUseChat(provider: AiProvider, model: string) {
  if (!model || !provider.chatModels?.some((x) => x.name === model)) return
  const payload = {
      chat: {
        providerId: provider.id,
        model,
        temperature: editingChatConfig.temperature,
        timeout: editingChatConfig.timeout,
        maxRetries: editingChatConfig.maxRetries,
      },
    },
    expected = JSON.stringify(config.value?.activeChat)
  if (!validChatDraft(payload.chat)) return
  const providerFact = JSON.stringify(provider)
  await mutate(
    '切换Chat配置',
    async () => {
      const result = await updateAiConfig(payload)
      const data = validatedConfig(result.data)
      verifyChatAck(data, payload.chat)
    },
    `全局Chat改为供应商 ${provider.id} / ${model}；影响后续模型调用，不修改业务数据授权。`,
    (fresh) => {
      chatPreflight(expected)(fresh)
      verifyTarget(fresh, provider.id, model, 'chatModels', providerFact)
    },
  )
}
async function handleUseEmbedding(provider: AiProvider, model: string, dimension?: number) {
  if (!Number.isSafeInteger(dimension) || !dimension || dimension <= 0) {
    writeError.value = '请人工核对并填写该行的正整数维度；自动检测尚不能核验所选供应商。'
    return
  }
  const willChange = embeddingChanged(provider.id, model, dimension),
    original = JSON.stringify([
      config.value?.activeEmbedding,
      config.value?.pendingEmbedding,
      config.value?.vectorizeStatus?.pending,
    ])
  const payload = {
    embedding: {
      providerId: provider.id,
      model,
      dimension,
      collection: willChange
        ? buildCollectionName(provider.id, model, dimension)
        : activeEmbedding.value?.collection,
      indexVersion: activeEmbedding.value?.indexVersion || 'v1',
    },
  }
  const providerFact = JSON.stringify(provider)
  await mutate(
    '变更Embedding配置',
    async () => {
      verifyEmbeddingAck(validatedConfig((await updateAiConfig(payload)).data), payload.embedding)
    },
    `供应商 ${provider.id} / ${model} / ${dimension}维。变更可能创建或替换待生效配置；当前active与各源RAG构建分别核对，不保证自动切换。`,
    (fresh) => {
      verifyTarget(fresh, provider.id, model, 'embeddingModels', providerFact)
      if (
        JSON.stringify([fresh.activeEmbedding, fresh.pendingEmbedding, fresh.vectorizeStatus?.pending]) !==
        original
      )
        throw new Error('Embedding配置已变化，未提交；草稿保留')
    },
  )
}
async function handleSaveChatParams(provider: AiProvider, model: string) {
  const payload = { chat: { ...editingChatConfig, providerId: provider.id, model } },
    expected = JSON.stringify(config.value?.activeChat)
  if (!validChatDraft(payload.chat)) return
  const providerFact = JSON.stringify(provider)
  await mutate(
    '保存Chat参数',
    async () => {
      verifyChatAck(validatedConfig((await updateAiConfig(payload)).data), payload.chat)
    },
    `保存全局Chat ${payload.chat.providerId} / ${payload.chat.model} 的参数；不运行模型测试。`,
    (fresh) => {
      chatPreflight(expected)(fresh)
      verifyTarget(fresh, provider.id, model, 'chatModels', providerFact)
    },
  )
}

function openCreateProvider() {
  if (!canManageAiConfig.value || saving.value) return
  providerDialogSession.value++
  providerBase.value = ''
  editingProviderId.value = ''
  Object.assign(providerForm, { id: '', name: '', baseUrl: '', apiKey: '' })
  providerDialogVisible.value = true
}

function openEditProvider(provider: AiProvider) {
  if (!canManageAiConfig.value || saving.value) return
  providerDialogSession.value++
  providerBase.value = JSON.stringify(provider)
  editingProviderId.value = provider.id
  Object.assign(providerForm, {
    id: provider.id,
    name: provider.name,
    baseUrl: provider.baseUrl,
    apiKey: '',
  })
  providerDialogVisible.value = true
}

async function saveProvider() {
  if (!providerForm.id?.trim() || !providerForm.baseUrl?.trim()) {
    writeError.value = '请填写供应商ID与Base URL'
    return
  }
  const id = editingProviderId.value,
    payload = { ...providerForm },
    original = providerBase.value
  if (!id && config.value?.providers.some((p) => p.id === payload.id)) {
    writeError.value = '该供应商ID已存在，未创建或覆盖；请从原记录编辑。'
    providerForm.apiKey = ''
    return
  }
  await mutate(
    id ? '更新供应商' : '创建供应商',
    async () => {
      const result = id ? await updateAiProvider(id, payload) : await createAiProvider(payload)
      if (!result.data || result.data.id !== (id || payload.id))
        throw new Error('供应商保存回执不符，结果未知；先核对原ID')
    },
    `${id ? '更新' : '创建'}供应商 ${id || payload.id}（${payload.name || '未命名'}）；密钥只提交本次新输入，留空保留已有值，界面不读取原密钥。`,
    (fresh) => {
      if (!id && fresh.providers.some((p) => p.id === payload.id))
        throw new Error('该供应商ID已在其他会话出现，未创建或覆盖；请从原记录编辑。')
      if (id && JSON.stringify(fresh.providers.find((p) => p.id === id)) !== original)
        throw new Error('原供应商已变化或消失，未提交；人工草稿保留')
    },
    () => {
      providerForm.apiKey = ''
      providerDialogVisible.value = false
    },
  )
}
async function handleDeleteProvider(provider: AiProvider) {
  const id = provider.id,
    original = JSON.stringify(provider)
  await mutate(
    '删除供应商',
    () => deleteAiProvider(id),
    `精确删除供应商 ${id}（${provider.name || '未命名'}）。当前active/pending引用由服务端重验，不自动迁移配置。`,
    (fresh) => {
      if (JSON.stringify(fresh.providers.find((p) => p.id === id)) !== original)
        throw new Error('供应商已变化，未执行删除')
    },
  )
}
async function handleTestProvider(provider: AiProvider) {
  const id = provider.id
  await mutate(
    '测试供应商连接',
    () => testAiProvider(id),
    `供应商 ${id}：将请求供应商模型目录，并持久化连接状态和模型列表；不是业务问数验收。`,
  )
}
async function handleSyncModels(provider: AiProvider) {
  const id = provider.id
  await mutate(
    '同步供应商模型',
    () => syncAiProviderModels(id),
    `供应商 ${id}：将访问模型目录并更新已保存列表和连接状态。`,
  )
}
function handleDetectDimension(_providerId: string, _model: { name: string; dimension?: number }) {
  writeError.value = '现有检测接口不能核验所选供应商，暂不发起检测；请人工确认该行维度。'
}
watch(providerDialogVisible, (visible) => {
  if (!visible) providerForm.apiKey = ''
})
watch(
  session.key,
  () => {
    readEpoch++
    config.value = null
    loading.value = false
    saving.value = false
    providerDialogVisible.value = false
    providerForm.apiKey = ''
    readError.value = ''
    writeError.value = ''
    expandedChatProvider.value = ''
    expandedEmbeddingProvider.value = ''
    Object.keys(dimensionDrafts).forEach((key) => delete dimensionDrafts[key])
    if (canViewAiConfig.value) void fetchConfig()
  },
  { immediate: true },
)

/**
 * 触发重新向量化（§7.19）。
 *
 * 必须展示影响范围与当前状态，不作为普通保存操作的一部分——因此这里先确认，
 * 再把后端返回的进度/失败信息展示出来。
 */
</script>

<template>
  <div class="ai-config-page" v-loading="loading">
    <p class="ai-eyebrow">运营与平台 / 模型与配置</p>
    <GovernanceWriteNotice />
    <p v-if="writeError" class="config-error" role="alert">{{ writeError }}</p>
    <ErrorState v-if="readError" :message="readError" @retry="fetchConfig" />
    <p v-if="!canViewAiConfig" role="status">
      {{ session.iam.errorMessage || '当前没有AI配置查看权限，未读取配置。' }}
    </p>
    <TaskPageHeader
      title="AI 服务配置"
      description="管理供应商、模型和参数。先核对当前生效与待切换配置，再按供应商维护模型。"
    >
      <template #actions>
        <el-button
          :icon="Plus"
          :disabled="!canManageAiConfig || saving"
          :title="canManageAiConfig ? '' : '需要 system:ai-config:manage'"
          @click="openCreateProvider"
        >
          添加供应商
        </el-button>
        <el-button :icon="RefreshCw" :loading="loading" @click="fetchConfig">刷新</el-button>
      </template>
    </TaskPageHeader>

    <!-- 当前状态 -->
    <section v-if="config" class="status-section">
      <div class="status-grid">
        <div class="status-tile active">
          <span class="tile-icon chat"><Sparkles :size="18" /></span>
          <div>
            <span>当前 Chat</span>
            <strong>{{ activeChat?.providerId || '未配置' }} / {{ activeChat?.model || '—' }}</strong>
          </div>
        </div>
        <div class="status-tile active">
          <span class="tile-icon embedding"><Database :size="18" /></span>
          <div>
            <span>当前 Embedding</span>
            <strong>
              {{ activeEmbedding?.providerId || '未配置' }} / {{ activeEmbedding?.model || '—' }}
            </strong>
          </div>
        </div>
        <div class="status-tile">
          <span class="tile-icon collection"><HardDrive :size="18" /></span>
          <div>
            <span>Collection</span>
            <strong>{{ activeEmbedding?.collection || '未返回' }}</strong>
          </div>
        </div>
        <div class="status-tile">
          <span class="tile-icon status"><Server :size="18" aria-hidden="true" /></span>
          <div>
            <span>配置状态</span>
            <el-tag :type="vectorizeTone" size="small">
              {{ vectorizeStatusLabel }}
            </el-tag>
            <span class="tile-sub">{{ vectorizeStatus?.status || 'UNKNOWN' }}</span>
          </div>
        </div>
      </div>
      <el-alert
        v-if="vectorizeMessage"
        type="warning"
        show-icon
        :closable="false"
        :title="vectorizeMessage"
      />

      <!--
        影响范围、当前进度与失败信息（§7.19）。

        进度与失败原因只在后端真的写入了对应值时才出现：`completedChunks`/`totalChunks`
        目前由 `AiConfigServiceImpl.defaultVectorizeStatus()` 固定写 0，`errorMessage` 没有
        写入者，因此这两块当前不会渲染。以前用 `typeof totalChunks === 'number'` 判断，
        条件恒真，页面会永远显示一个与知识库规模无关的「0 / 0」。
      -->
      <div class="vectorize-panel">
        <div class="vectorize-panel__info">
          <p v-if="vectorizeStatus?.pending">
            待生效配置：{{ vectorizeStatus.pending.model }}（维度 {{ vectorizeStatus.pending.dimension }}）；
            当前生效：{{ vectorizeStatus.active?.model || '无' }}（维度
            {{ vectorizeStatus.active?.dimension || '—' }}）
          </p>
          <p v-else>
            当前生效：{{ vectorizeStatus?.active?.model || '未配置' }}（维度
            {{ vectorizeStatus?.active?.dimension || '—' }}）
          </p>
          <p v-if="(vectorizeStatus?.totalChunks ?? 0) > 0">
            切片进度：已完成 {{ vectorizeStatus?.completedChunks ?? 0 }} / 共
            {{ vectorizeStatus?.totalChunks }}
            <span v-if="vectorizeStatus?.failedChunks">，失败 {{ vectorizeStatus?.failedChunks }}</span>
          </p>
          <p v-if="vectorizeStatus?.errorMessage" class="is-error">
            失败原因：{{ vectorizeStatus?.errorMessage }}
          </p>
        </div>
        <el-tag type="info">
          发布后请在知识文档页显式确认 RAG 构建；Embedding 配置切换与各源 RAG 就绪分别核验。
        </el-tag>
      </div>
    </section>

    <!-- Tab 切换 -->
    <section v-if="canViewAiConfig && config" class="config-section">
      <p class="config-note">
        测试与同步会连接供应商并更新目录。现有自动维度检测尚不能核验所选供应商，暂不可用；请人工确认维度。存在待切换Embedding时，普通Chat保存暂不提交，避免旧接口取消待配置。
      </p>
      <div class="tab-header">
        <button :class="{ active: activeTab === 'chat' }" @click="selectTab('chat')">
          <Sparkles :size="16" aria-hidden="true" />
          Chat 配置
        </button>
        <button :class="{ active: activeTab === 'embedding' }" @click="selectTab('embedding')">
          <Database :size="16" aria-hidden="true" />
          Embedding 配置
        </button>
      </div>

      <!-- Chat 配置列表 -->
      <div v-if="activeTab === 'chat'" class="provider-cards">
        <EmptyState
          v-if="chatProviders.length === 0"
          message="暂无 Chat 供应商。添加供应商并测试连接后即可获取可用模型列表。"
          action-text="添加供应商"
          @action="openCreateProvider"
        />

        <article
          v-for="provider in chatProviders"
          :key="provider.id"
          class="provider-card"
          :class="{ expanded: expandedChatProvider === provider.id }"
        >
          <!-- 卡片头部 -->
          <div class="card-header" @click="expandChatProvider(provider)">
            <button
              type="button"
              class="card-title provider-opener"
              @click.stop="expandChatProvider(provider)"
              :aria-expanded="expandedChatProvider === provider.id"
            >
              <Server :size="16" />
              <strong>{{ provider.name || provider.id }}</strong>
              <el-tag
                size="small"
                :type="
                  provider.status === 'connected'
                    ? 'success'
                    : provider.status === 'failed'
                      ? 'danger'
                      : 'info'
                "
              >
                {{
                  provider.status === 'connected'
                    ? '已连接'
                    : provider.status === 'failed'
                      ? '测试失败'
                      : '尚未确认'
                }}
              </el-tag>
            </button>
            <div class="card-summary">
              <span>{{ provider.baseUrl }}</span>
              <span>{{ provider.chatModels ? provider.chatModels.length : '未返回' }} 个模型</span>
            </div>
            <div class="card-actions" @click.stop>
              <el-button
                :icon="Wifi"
                size="small"
                :disabled="!canManageAiConfig || saving"
                circle
                title="测试连接"
                aria-label="测试供应商连接"
                @click="handleTestProvider(provider)"
              />
              <el-button
                :icon="RefreshCw"
                size="small"
                circle
                title="同步模型"
                aria-label="同步供应商模型列表"
                :loading="syncingProviderId === provider.id"
                :disabled="!canManageAiConfig || saving"
                @click="handleSyncModels(provider)"
              />
              <el-button
                size="small"
                :disabled="!canManageAiConfig || saving"
                circle
                title="编辑"
                aria-label="编辑供应商"
                @click="openEditProvider(provider)"
              >
                <Edit :size="12" />
              </el-button>
              <el-button
                :icon="Trash2"
                size="small"
                type="danger"
                :disabled="!canManageAiConfig || saving"
                plain
                circle
                aria-label="删除供应商"
                @click="handleDeleteProvider(provider)"
              />
            </div>
          </div>

          <!-- 卡片详情（展开后显示） -->
          <div v-if="expandedChatProvider === provider.id" class="card-body">
            <p v-if="!provider.chatModels?.length" class="config-note">
              尚无返回的Chat模型；仍可维护供应商，按需测试或同步模型目录。
            </p>
            <div class="model-list">
              <div
                v-for="model in provider.chatModels"
                :key="model.name"
                class="model-item"
                :class="{ active: isChatActive(provider.id, model.name) }"
              >
                <div class="model-info">
                  <strong>{{ model.displayName || model.name }}</strong>
                  <el-tag v-if="isChatActive(provider.id, model.name)" type="success" size="small">
                    使用中
                  </el-tag>
                </div>
                <div class="model-params">
                  <label>
                    <span>温度</span>
                    <el-input
                      v-model="editingChatConfig.temperature"
                      size="small"
                      :disabled="!canManageAiConfig || saving"
                      @click.stop
                    />
                  </label>
                  <label>
                    <span>超时(秒)</span>
                    <el-input
                      v-model="editingChatConfig.timeout"
                      size="small"
                      :disabled="!canManageAiConfig || saving"
                      @click.stop
                    />
                  </label>
                  <label>
                    <span>重试</span>
                    <el-input
                      v-model="editingChatConfig.maxRetries"
                      size="small"
                      :disabled="!canManageAiConfig || saving"
                      @click.stop
                    />
                  </label>
                </div>
                <div class="model-actions">
                  <el-button
                    v-if="!isChatActive(provider.id, model.name)"
                    type="primary"
                    size="small"
                    :icon="Check"
                    :loading="saving"
                    :disabled="!canManageAiConfig || saving"
                    @click.stop="handleUseChat(provider, model.name)"
                  >
                    使用
                  </el-button>
                  <el-button v-else type="success" size="small" :icon="Check" disabled>使用中</el-button>
                  <el-button
                    v-if="isChatActive(provider.id, model.name)"
                    size="small"
                    :loading="saving"
                    :disabled="!canManageAiConfig || saving"
                    @click.stop="handleSaveChatParams(provider, model.name)"
                  >
                    保存参数
                  </el-button>
                </div>
              </div>
            </div>
          </div>
        </article>
      </div>

      <!-- Embedding 配置列表 -->
      <div v-if="activeTab === 'embedding'" class="provider-cards">
        <EmptyState
          v-if="embeddingProviders.length === 0"
          message="暂无 Embedding 供应商。添加供应商并测试连接后即可获取可用模型列表。"
          action-text="添加供应商"
          @action="openCreateProvider"
        />

        <article
          v-for="provider in embeddingProviders"
          :key="provider.id"
          class="provider-card"
          :class="{ expanded: expandedEmbeddingProvider === provider.id }"
        >
          <!-- 卡片头部 -->
          <div class="card-header" @click="expandEmbeddingProvider(provider)">
            <button
              type="button"
              class="card-title provider-opener"
              @click.stop="expandEmbeddingProvider(provider)"
              :aria-expanded="expandedEmbeddingProvider === provider.id"
            >
              <Server :size="16" />
              <strong>{{ provider.name || provider.id }}</strong>
              <el-tag
                size="small"
                :type="
                  provider.status === 'connected'
                    ? 'success'
                    : provider.status === 'failed'
                      ? 'danger'
                      : 'info'
                "
              >
                {{
                  provider.status === 'connected'
                    ? '已连接'
                    : provider.status === 'failed'
                      ? '测试失败'
                      : '尚未确认'
                }}
              </el-tag>
            </button>
            <div class="card-summary">
              <span>{{ provider.baseUrl }}</span>
              <span>{{ provider.embeddingModels ? provider.embeddingModels.length : '未返回' }} 个模型</span>
            </div>
            <div class="card-actions" @click.stop>
              <el-button
                :icon="Wifi"
                size="small"
                :disabled="!canManageAiConfig || saving"
                circle
                title="测试连接"
                aria-label="测试供应商连接"
                @click="handleTestProvider(provider)"
              />
              <el-button
                :icon="RefreshCw"
                size="small"
                circle
                title="同步模型"
                aria-label="同步供应商模型列表"
                :loading="syncingProviderId === provider.id"
                :disabled="!canManageAiConfig || saving"
                @click="handleSyncModels(provider)"
              />
              <el-button
                size="small"
                :disabled="!canManageAiConfig || saving"
                circle
                title="编辑"
                aria-label="编辑供应商"
                @click="openEditProvider(provider)"
              >
                <Edit :size="12" />
              </el-button>
              <el-button
                :icon="Trash2"
                size="small"
                type="danger"
                :disabled="!canManageAiConfig || saving"
                plain
                circle
                aria-label="删除供应商"
                @click="handleDeleteProvider(provider)"
              />
            </div>
          </div>

          <!-- 卡片详情（展开后显示） -->
          <div v-if="expandedEmbeddingProvider === provider.id" class="card-body">
            <p v-if="!provider.embeddingModels?.length" class="config-note">
              尚无返回的Embedding模型；仍可维护供应商，按需测试或同步模型目录。
            </p>
            <div class="model-list">
              <div
                v-for="model in provider.embeddingModels"
                :key="model.name"
                class="model-item"
                :class="{
                  active: isEmbeddingActive(provider.id, model.name, dimensionFor(provider.id, model)),
                }"
              >
                <div class="model-info">
                  <strong>{{ model.displayName || model.name }}</strong>
                  <span v-if="model.dimension" class="dimension-tag">{{ model.dimension }} 维</span>
                  <el-tag
                    v-if="isEmbeddingActive(provider.id, model.name, dimensionFor(provider.id, model))"
                    type="success"
                    size="small"
                  >
                    使用中
                  </el-tag>
                </div>
                <div class="model-params">
                  <label>
                    <span>向量维度</span>
                    <el-input-number
                      :model-value="dimensionFor(provider.id, model)"
                      @update:model-value="
                        (value: number | undefined) => setDimension(provider.id, model.name, value)
                      "
                      size="small"
                      :min="1"
                      :step="1"
                      :disabled="!canManageAiConfig || saving"
                      @click.stop
                    />
                  </label>
                  <!-- 自动检测维度：后端 /detect-dimension 早已存在，此前页面只能手填 -->
                  <el-button
                    size="small"
                    :icon="Ruler"
                    :loading="detectingDimension"
                    :disabled="true"
                    title="当前接口不能核验所选供应商，请人工确认维度"
                    aria-label="自动检测向量维度"
                    @click.stop="handleDetectDimension(provider.id, model)"
                  >
                    自动检测
                  </el-button>
                </div>
                <div class="model-actions">
                  <el-button
                    v-if="!isEmbeddingActive(provider.id, model.name, dimensionFor(provider.id, model))"
                    type="primary"
                    size="small"
                    :icon="Check"
                    :loading="saving"
                    :disabled="!canManageAiConfig || saving"
                    @click.stop="handleUseEmbedding(provider, model.name, dimensionFor(provider.id, model))"
                  >
                    使用
                  </el-button>
                  <el-button v-else type="success" size="small" :icon="Check" disabled>使用中</el-button>
                </div>
              </div>
            </div>
            <div
              v-if="activeEmbedding?.providerId !== provider.id && expandedEmbeddingProvider === provider.id"
              class="change-notice"
            >
              <el-alert type="warning" show-icon :closable="false">
                切换 Embedding 后需要重新向量化知识库
              </el-alert>
            </div>
          </div>
        </article>
      </div>
    </section>

    <!-- 供应商编辑对话框 -->
    <el-dialog
      v-model="providerDialogVisible"
      title="供应商"
      width="520px"
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <p v-if="writeError" role="alert">{{ writeError }}</p>
      <el-form :key="providerDialogSession" label-position="top" :disabled="saving">
        <el-form-item label="ID">
          <el-input
            v-model="providerForm.id"
            :disabled="Boolean(editingProviderId) || !canManageAiConfig || saving"
            placeholder="dashscope"
          />
        </el-form-item>
        <el-form-item label="名称">
          <el-input
            v-model="providerForm.name"
            :disabled="!canManageAiConfig || saving"
            placeholder="通义千问"
          />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input
            v-model="providerForm.baseUrl"
            :disabled="!canManageAiConfig || saving"
            placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1"
          />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="providerForm.apiKey"
            type="password"
            show-password
            :disabled="!canManageAiConfig || saving"
            placeholder="留空则不修改"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="providerDialogVisible = false">取消</el-button>
        <el-button v-if="canManageAiConfig" type="primary" :loading="saving" @click="saveProvider">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.provider-opener {
  border: 0;
  padding: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}
.provider-opener:focus-visible {
  outline: 2px solid var(--do-primary);
  outline-offset: 5px;
}
.ai-eyebrow {
  margin: 0;
  color: var(--do-primary);
  font-size: 12px;
  font-weight: 650;
}
.config-note {
  padding: 14px 0;
  margin: 0;
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.7;
  border-bottom: 1px solid var(--do-line);
}
.config-error {
  color: var(--do-danger);
  font-size: 13px;
  line-height: 1.6;
}
.ai-config-page .provider-card,
.ai-config-page .status-tile {
  box-shadow: none;
  background: var(--do-surface);
  border-radius: 12px;
}
.ai-config-page .status-tile.active {
  border-color: var(--do-line);
  background: var(--do-bg);
}
.ai-config-page :deep(.el-dialog) {
  max-width: calc(100vw - 24px);
}
.tile-sub {
  display: block;
  margin-top: 4px;
  color: var(--do-muted);
  font-size: 11px;
}

.vectorize-panel {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
}

.vectorize-panel__info {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.vectorize-panel__info p {
  margin: 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
}

.vectorize-panel__info p.is-error {
  color: var(--do-danger);
}

.ai-config-page {
  max-width: 1080px;
}

/* 状态区域 */
.status-section {
  padding: 18px 20px;
  margin-bottom: 18px;
  border: 1px solid var(--do-glass-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-glass-data-bg);
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.status-tile {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  background: var(--do-glass-panel-bg);
}

.status-tile.active {
  border-color: var(--do-primary);
  background: var(--do-tone-blue-bg);
}

.tile-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 8px;
  flex: 0 0 auto;
}

.tile-icon.chat {
  color: var(--do-tone-blue);
  background: var(--do-tone-blue-bg);
}

.tile-icon.embedding {
  color: var(--do-tone-green);
  background: var(--do-tone-green-bg);
}

.tile-icon.collection {
  color: var(--do-tone-purple);
  background: var(--do-tone-purple-bg);
}

.tile-icon.status {
  color: var(--do-tone-orange);
  background: var(--do-tone-orange-bg);
}

.status-tile > div {
  min-width: 0;
}

.status-tile span {
  display: block;
  font-size: 12px;
  color: var(--do-muted);
}

.status-tile strong {
  display: block;
  margin-top: 4px;
  color: var(--do-ink);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 配置区域 */
.config-section {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  overflow: hidden;
}

.tab-header {
  display: flex;
  border-bottom: 1px solid var(--do-line);
}

.tab-header button {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px 20px;
  border: none;
  background: transparent;
  color: var(--do-muted);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.tab-header button:hover {
  color: var(--do-ink);
  background: var(--do-primary-soft);
}

.tab-header button.active {
  color: var(--do-primary-strong);
  background: var(--do-glass-data-bg);
  border-bottom: 2px solid var(--do-primary);
}

/* 供应商卡片 */
.provider-cards {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.empty-state {
  padding: 40px 20px;
  text-align: center;
  color: var(--do-muted);
}

.provider-card {
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-glass-data-bg);
  transition: all 0.2s;
}

.provider-card:hover {
  border-color: var(--do-primary);
  box-shadow: var(--do-shadow-hover);
}

.provider-card.expanded {
  border-color: var(--do-primary);
  box-shadow: 0 4px 16px var(--do-shadow);
}

.card-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 16px;
  cursor: pointer;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.card-title strong {
  color: var(--do-ink);
}

.card-summary {
  flex: 1;
  display: flex;
  gap: 16px;
  min-width: 0;
}

.card-summary span {
  font-size: 12px;
  color: var(--do-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-actions {
  display: flex;
  gap: 4px;
  flex: 0 0 auto;
}

/* 卡片详情 */
.card-body {
  padding: 0 16px 16px;
  border-top: 1px solid var(--do-line);
}

.model-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}

.model-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 6px;
  background: var(--do-glass-panel-bg);
}

.model-item.active {
  border-color: var(--do-accent);
  background: var(--do-success-soft);
}

.model-info {
  min-width: 120px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.model-info strong {
  color: var(--do-ink);
  font-size: 13px;
}

.dimension-tag {
  font-size: 11px;
  color: var(--do-muted);
  padding: 2px 6px;
  background: var(--do-primary-soft);
  border-radius: 4px;
}

.model-params {
  flex: 1;
  display: flex;
  gap: 10px;
}

.model-params label {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.model-params span {
  font-size: 11px;
  color: var(--do-muted);
}

.model-params .el-input,
.model-params .el-input-number {
  width: 80px;
}

.model-actions {
  display: flex;
  gap: 8px;
  flex: 0 0 auto;
}

.change-notice {
  margin-top: 12px;
}
</style>
