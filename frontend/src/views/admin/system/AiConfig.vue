<script setup lang="ts">
/**
 * AI 配置（开发指导 §7.19）
 *
 * 补齐两处：
 * 1. **Embedding 维度检测**：后端 `/detect-dimension` 早已存在且前端已封装，
 *    但页面零引用，只能让用户手工填写维度。现补「自动检测」入口。
 * 2. **模型同步**：后端 `/providers/{id}/sync-models` 存在，前端此前零封装，
 *    页面只能靠「测试连接成功后隐式重拉」间接刷新模型列表。
 * Tab 写入 URL（`?tab=chat|embedding`），状态标签改用中文（§11.3）。
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Cpu,
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
  detectEmbeddingDimension,
  getAiConfig,
  syncAiProviderModels,
  testAiProvider,
  updateAiConfig,
  updateAiProvider,
  type AiConfig,
  type AiProvider,
  type AiProviderPayload,
} from '../../../api/admin/system'
import { useAuthStore } from '../../../stores/auth'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const providerDialogVisible = ref(false)
const editingProviderId = ref('')
const config = ref<AiConfig | null>(null)
const activeTab = ref<'chat' | 'embedding'>(
  route.query.tab === 'embedding' ? 'embedding' : 'chat',
)

function selectTab(tab: 'chat' | 'embedding') {
  activeTab.value = tab
  router.push({ query: { ...route.query, tab } })
}

// 浏览器前进/后退或外部改 URL 时同步回组件状态（§15 要求前进后退可恢复）
watch(() => route.query.tab, (value) => {
  const next = value === 'embedding' ? 'embedding' : 'chat'
  if (next !== activeTab.value) activeTab.value = next
})

const detectingDimension = ref(false)
const syncingProviderId = ref('')

/** 索引状态的中文标签（§11.3：状态标签使用中文，同时保留技术状态说明） */
type TagTone = 'success' | 'warning' | 'danger' | 'info'
const VECTORIZE_STATUS: Record<string, { label: string; tone: TagTone }> = {
  NORMAL: { label: '正常', tone: 'success' },
  REINDEX_REQUIRED: { label: '需要重建索引', tone: 'warning' },
  REINDEXING: { label: '索引重建中', tone: 'warning' },
  REINDEX_FAILED: { label: '索引重建失败', tone: 'danger' },
}

const vectorizeStatusLabel = computed(() => {
  const status = vectorizeStatus.value?.status || 'NORMAL'
  return VECTORIZE_STATUS[status]?.label || status
})

const vectorizeTone = computed<TagTone>(() => {
  const status = vectorizeStatus.value?.status || 'NORMAL'
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
const permissions = computed(() => auth.user?.permissions || auth.currentUser?.permissions || [])
const canManageAiConfig = computed(
  () => permissions.value.includes('*') || permissions.value.includes('system:ai-config:manage'),
)

const activeChat = computed(() => config.value?.activeChat)
const activeEmbedding = computed(() => config.value?.activeEmbedding)
const vectorizeStatus = computed(() => config.value?.vectorizeStatus)

const vectorizeMessage = computed(() => {
  if (vectorizeStatus.value?.status === 'REINDEX_REQUIRED') {
    return 'Embedding 配置已变更，需要重新向量化后才会切换为 active'
  }
  if (vectorizeStatus.value?.status === 'REINDEXING') {
    return '索引正在重建，查询仍使用上一版 active 索引'
  }
  if (vectorizeStatus.value?.status === 'REINDEX_FAILED') {
    return '索引重建失败，查询仍使用上一版 active 索引'
  }
  return ''
})

// Chat 供应商列表（有 Chat 模型的）
const chatProviders = computed(() =>
  providers.value.filter((p) => p.chatModels && p.chatModels.length > 0),
)

// Embedding 供应商列表（有 Embedding 模型的）
const embeddingProviders = computed(() =>
  providers.value.filter((p) => p.embeddingModels && p.embeddingModels.length > 0),
)

function isChatActive(providerId: string, model: string) {
  return activeChat.value?.providerId === providerId && activeChat.value?.model === model
}

function isEmbeddingActive(providerId: string, model: string) {
  return activeEmbedding.value?.providerId === providerId && activeEmbedding.value?.model === model
}

function expandChatProvider(provider: AiProvider) {
  expandedChatProvider.value = provider.id
  editingChatConfig.providerId = provider.id
  editingChatConfig.model = activeChat.value?.providerId === provider.id ? activeChat.value.model : ''
  editingChatConfig.temperature = activeChat.value?.providerId === provider.id ? activeChat.value.temperature || '0.3' : '0.3'
  editingChatConfig.timeout = activeChat.value?.providerId === provider.id ? activeChat.value.timeout || '120' : '120'
  editingChatConfig.maxRetries = activeChat.value?.providerId === provider.id ? activeChat.value.maxRetries || '2' : '2'
}

function expandEmbeddingProvider(provider: AiProvider) {
  expandedEmbeddingProvider.value = provider.id
  editingEmbeddingConfig.providerId = provider.id
  editingEmbeddingConfig.model = activeEmbedding.value?.providerId === provider.id ? activeEmbedding.value.model : ''
  editingEmbeddingConfig.dimension = activeEmbedding.value?.providerId === provider.id
    ? Number(activeEmbedding.value.dimension || 1024)
    : 1024
}

async function fetchConfig() {
  loading.value = true
  try {
    const res = await getAiConfig()
    config.value = res.data ?? null
    // 默认展开当前使用的供应商
    if (activeChat.value?.providerId) {
      expandChatProvider({ id: activeChat.value.providerId } as AiProvider)
    }
    if (activeEmbedding.value?.providerId) {
      expandEmbeddingProvider({ id: activeEmbedding.value.providerId } as AiProvider)
    }
  } catch {
    ElMessage.error('加载 AI 配置失败')
  } finally {
    loading.value = false
  }
}

function embeddingChanged(providerId: string, model: string, dimension: number) {
  const active = activeEmbedding.value
  if (!active) return false
  return (
    active.providerId !== providerId
    || active.model !== model
    || Number(active.dimension) !== Number(dimension)
  )
}

function buildCollectionName(providerId: string, model: string, dimension: number) {
  const safeProvider = providerId.replace(/[^a-zA-Z0-9_]/g, '_')
  const safeModel = model.replace(/[^a-zA-Z0-9_]/g, '_')
  return `schema_knowledge_${dimension}_${safeProvider}_${safeModel}`
}

async function handleUseChat(provider: AiProvider, model: string) {
  if (!canManageAiConfig.value) {
    ElMessage.warning('当前账号只有查看权限')
    return
  }

  saving.value = true
  try {
    const payload = {
      chat: {
        providerId: provider.id,
        model,
        temperature: editingChatConfig.temperature,
        timeout: editingChatConfig.timeout,
        maxRetries: editingChatConfig.maxRetries,
      },
    }
    const res = await updateAiConfig(payload)
    config.value = res.data ?? null
    ElMessage.success('Chat 配置已切换')
  } catch {
    ElMessage.error('切换失败')
  } finally {
    saving.value = false
  }
}

async function handleUseEmbedding(provider: AiProvider, model: string, dimension?: number) {
  if (!canManageAiConfig.value) {
    ElMessage.warning('当前账号只有查看权限')
    return
  }
  // 维度必须来自被点击的这一行：此前这里会回落到 editingEmbeddingConfig.dimension，
  // 而该字段只在展开「当前 active 供应商」时才是真值，其余情况恒为硬编码 1024，
  // 于是「模型没填维度就直接点使用」会静默按 1024 提交，且确认框里看不到这个数字。
  if (!dimension || dimension <= 0) {
    ElMessage.warning('请先填写该模型的 Embedding 维度，或用行内「自动检测」取得维度后再使用')
    return
  }

  const willChange = embeddingChanged(provider.id, model, dimension)

  if (willChange) {
    await ElMessageBox.confirm(
      `切换 Embedding 模型后需要重新向量化知识库。新索引构建完成前，查询仍使用旧索引。`,
      '确认切换 Embedding',
      { type: 'warning', confirmButtonText: '确认并切换', cancelButtonText: '取消' },
    )
  }

  saving.value = true
  try {
    const payload = {
      embedding: {
        providerId: provider.id,
        model,
        dimension,
        collection: willChange ? buildCollectionName(provider.id, model, dimension) : activeEmbedding.value?.collection,
        indexVersion: activeEmbedding.value?.indexVersion || 'v1',
      },
    }
    const res = await updateAiConfig(payload)
    config.value = res.data ?? null
    ElMessage.success(willChange ? 'Embedding 已切换，待重新向量化' : 'Embedding 配置已切换')
  } catch (error) {
    if (error !== 'cancel') ElMessage.error('切换失败')
  } finally {
    saving.value = false
  }
}

async function handleSaveChatParams() {
  if (!canManageAiConfig.value) return

  saving.value = true
  try {
    const payload = {
      chat: {
        providerId: editingChatConfig.providerId,
        model: editingChatConfig.model,
        temperature: editingChatConfig.temperature,
        timeout: editingChatConfig.timeout,
        maxRetries: editingChatConfig.maxRetries,
      },
    }
    const res = await updateAiConfig(payload)
    config.value = res.data ?? null
    ElMessage.success('Chat 参数已保存')
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

function openCreateProvider() {
  if (!canManageAiConfig.value) return
  editingProviderId.value = ''
  Object.assign(providerForm, { id: '', name: '', baseUrl: '', apiKey: '' })
  providerDialogVisible.value = true
}

function openEditProvider(provider: AiProvider) {
  if (!canManageAiConfig.value) return
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
  if (!canManageAiConfig.value) {
    ElMessage.warning('当前账号只有查看权限')
    return
  }
  if (!providerForm.id || !providerForm.baseUrl) {
    ElMessage.warning('请填写供应商 ID 和 Base URL')
    return
  }
  try {
    if (editingProviderId.value) {
      await updateAiProvider(editingProviderId.value, providerForm)
    } else {
      await createAiProvider(providerForm)
    }
    providerDialogVisible.value = false
    ElMessage.success('供应商已保存')
    await fetchConfig()
  } catch {
    ElMessage.error('供应商保存失败')
  }
}

async function handleDeleteProvider(provider: AiProvider) {
  if (!canManageAiConfig.value) return
  await ElMessageBox.confirm(`确认删除供应商 ${provider.name || provider.id}？`, '删除供应商', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消',
  })
  try {
    await deleteAiProvider(provider.id)
    ElMessage.success('供应商已删除')
    await fetchConfig()
  } catch {
    ElMessage.error('删除失败，可能正在被 active 或 pending 配置引用')
  }
}

async function handleTestProvider(provider: AiProvider) {
  if (!canManageAiConfig.value) return
  try {
    await testAiProvider(provider.id)
    ElMessage.success('连接测试完成')
    await fetchConfig()
  } catch {
    ElMessage.error('连接测试失败')
  }
}

/** 同步供应商模型列表（§7.19 要求的「模型同步」显式入口） */
async function handleSyncModels(provider: AiProvider) {
  if (!canManageAiConfig.value) return
  syncingProviderId.value = provider.id
  try {
    await syncAiProviderModels(provider.id)
    ElMessage.success('模型列表已同步')
    await fetchConfig()
  } catch (cause) {
    const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '模型同步失败')
  } finally {
    syncingProviderId.value = ''
  }
}

/** 自动检测 Embedding 维度（后端 /detect-dimension），避免手工填写出错 */
async function handleDetectDimension(providerId: string, model: { name: string; dimension?: number }) {
  if (!canManageAiConfig.value) return
  detectingDimension.value = true
  try {
    const result = await detectEmbeddingDimension({
      providerId,
      model: model.name,
    })
    const dimension = result.data?.dimension
    if (typeof dimension === 'number' && dimension > 0) {
      model.dimension = dimension
      ElMessage.success(`检测到维度 ${dimension}`)
    } else {
      ElMessage.warning('未检测到维度，请确认模型与密钥是否可用')
    }
  } catch (cause) {
    const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '维度检测失败')
  } finally {
    detectingDimension.value = false
  }
}

/**
 * 触发重新向量化（§7.19）。
 *
 * 必须展示影响范围与当前状态，不作为普通保存操作的一部分——因此这里先确认，
 * 再把后端返回的进度/失败信息展示出来。
 */
fetchConfig()
</script>

<template>
  <div class="ai-config-page" v-loading="loading">
    <section class="page-actions">
      <div class="header-title">
        <Cpu :size="22" />
        <div>
          <h2>AI 服务配置</h2>
          <p>管理供应商、模型和参数。点击卡片展开详情，选择使用的配置。</p>
        </div>
      </div>
      <div class="header-actions">
        <el-button :icon="Plus" @click="openCreateProvider">添加供应商</el-button>
        <el-button :icon="RefreshCw" :loading="loading" @click="fetchConfig">刷新</el-button>
      </div>
    </section>

    <!-- 当前状态 -->
    <section v-if="config" class="status-section">
      <div class="status-grid">
        <div class="status-tile active">
          <span class="tile-icon chat"><Sparkles :size="18" /></span>
          <div>
            <span>当前 Chat</span>
            <strong>{{ activeChat?.providerId }} / {{ activeChat?.model }}</strong>
          </div>
        </div>
        <div class="status-tile active">
          <span class="tile-icon embedding"><Database :size="18" /></span>
          <div>
            <span>当前 Embedding</span>
            <strong>{{ activeEmbedding?.providerId }} / {{ activeEmbedding?.model }}</strong>
          </div>
        </div>
        <div class="status-tile">
          <span class="tile-icon collection"><HardDrive :size="18" /></span>
          <div>
            <span>Collection</span>
            <strong>{{ activeEmbedding?.collection || 'schema_knowledge' }}</strong>
          </div>
        </div>
        <div class="status-tile">
          <span class="tile-icon status"><Server :size="18" aria-hidden="true" /></span>
          <div>
            <span>索引状态</span>
            <el-tag :type="vectorizeTone" size="small">
              {{ vectorizeStatusLabel }}
            </el-tag>
            <span class="tile-sub">{{ vectorizeStatus?.status || 'NORMAL' }}</span>
          </div>
        </div>
      </div>
      <el-alert v-if="vectorizeMessage" type="warning" show-icon :closable="false" :title="vectorizeMessage" />

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
            当前生效：{{ vectorizeStatus.active?.model || '无' }}（维度 {{ vectorizeStatus.active?.dimension || '—' }}）
          </p>
          <p v-else>当前生效：{{ vectorizeStatus?.active?.model || '未配置' }}（维度 {{ vectorizeStatus?.active?.dimension || '—' }}）</p>
          <p v-if="(vectorizeStatus?.totalChunks ?? 0) > 0">
            切片进度：已完成 {{ vectorizeStatus?.completedChunks ?? 0 }} / 共 {{ vectorizeStatus?.totalChunks }}
            <span v-if="vectorizeStatus?.failedChunks">，失败 {{ vectorizeStatus?.failedChunks }}</span>
          </p>
          <p v-if="vectorizeStatus?.errorMessage" class="is-error">
            失败原因：{{ vectorizeStatus?.errorMessage }}
          </p>
        </div>
        <el-tag type="info">全量重建编排尚未开放；发布知识文档时会创建安全的版本级索引任务</el-tag>
      </div>
    </section>

    <!-- Tab 切换 -->
    <section class="config-section">
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
            <div class="card-title">
              <Server :size="16" />
              <strong>{{ provider.name || provider.id }}</strong>
              <el-tag
                size="small"
                :type="provider.status === 'connected' ? 'success' : provider.status === 'failed' ? 'danger' : 'info'"
              >
                {{ provider.status || 'unknown' }}
              </el-tag>
            </div>
            <div class="card-summary">
              <span>{{ provider.baseUrl }}</span>
              <span>{{ provider.chatModels?.length || 0 }} 个模型</span>
            </div>
            <div class="card-actions" @click.stop>
              <el-button :icon="Wifi" size="small" circle title="测试连接" aria-label="测试供应商连接" @click="handleTestProvider(provider)" />
              <el-button
                :icon="RefreshCw"
                size="small"
                circle
                title="同步模型"
                aria-label="同步供应商模型列表"
                :loading="syncingProviderId === provider.id"
                @click="handleSyncModels(provider)"
              />
              <el-button size="small" circle title="编辑" aria-label="编辑供应商" @click="openEditProvider(provider)">
                <Edit :size="12" />
              </el-button>
              <el-button :icon="Trash2" size="small" type="danger" plain circle aria-label="删除供应商" @click="handleDeleteProvider(provider)" />
            </div>
          </div>

          <!-- 卡片详情（展开后显示） -->
          <div v-if="expandedChatProvider === provider.id" class="card-body">
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
                      :disabled="!canManageAiConfig"
                      @click.stop
                    />
                  </label>
                  <label>
                    <span>超时(秒)</span>
                    <el-input
                      v-model="editingChatConfig.timeout"
                      size="small"
                      :disabled="!canManageAiConfig"
                      @click.stop
                    />
                  </label>
                  <label>
                    <span>重试</span>
                    <el-input
                      v-model="editingChatConfig.maxRetries"
                      size="small"
                      :disabled="!canManageAiConfig"
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
                    @click.stop="handleUseChat(provider, model.name)"
                  >
                    使用
                  </el-button>
                  <el-button
                    v-else
                    type="success"
                    size="small"
                    :icon="Check"
                    disabled
                  >
                    使用中
                  </el-button>
                  <el-button
                    v-if="isChatActive(provider.id, model.name)"
                    size="small"
                    :loading="saving"
                    @click.stop="handleSaveChatParams"
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
            <div class="card-title">
              <Server :size="16" />
              <strong>{{ provider.name || provider.id }}</strong>
              <el-tag
                size="small"
                :type="provider.status === 'connected' ? 'success' : provider.status === 'failed' ? 'danger' : 'info'"
              >
                {{ provider.status || 'unknown' }}
              </el-tag>
            </div>
            <div class="card-summary">
              <span>{{ provider.baseUrl }}</span>
              <span>{{ provider.embeddingModels?.length || 0 }} 个模型</span>
            </div>
            <div class="card-actions" @click.stop>
              <el-button :icon="Wifi" size="small" circle title="测试连接" aria-label="测试供应商连接" @click="handleTestProvider(provider)" />
              <el-button
                :icon="RefreshCw"
                size="small"
                circle
                title="同步模型"
                aria-label="同步供应商模型列表"
                :loading="syncingProviderId === provider.id"
                @click="handleSyncModels(provider)"
              />
              <el-button size="small" circle title="编辑" aria-label="编辑供应商" @click="openEditProvider(provider)">
                <Edit :size="12" />
              </el-button>
              <el-button :icon="Trash2" size="small" type="danger" plain circle aria-label="删除供应商" @click="handleDeleteProvider(provider)" />
            </div>
          </div>

          <!-- 卡片详情（展开后显示） -->
          <div v-if="expandedEmbeddingProvider === provider.id" class="card-body">
            <div class="model-list">
              <div
                v-for="model in provider.embeddingModels"
                :key="model.name"
                class="model-item"
                :class="{ active: isEmbeddingActive(provider.id, model.name) }"
              >
                <div class="model-info">
                  <strong>{{ model.displayName || model.name }}</strong>
                  <span v-if="model.dimension" class="dimension-tag">{{ model.dimension }} 维</span>
                  <el-tag v-if="isEmbeddingActive(provider.id, model.name)" type="success" size="small">
                    使用中
                  </el-tag>
                </div>
                <div class="model-params">
                  <label>
                    <span>向量维度</span>
                    <el-input-number
                      v-model="model.dimension"
                      size="small"
                      :min="1"
                      :step="1"
                      :disabled="!canManageAiConfig"
                      @click.stop
                    />
                  </label>
                  <!-- 自动检测维度：后端 /detect-dimension 早已存在，此前页面只能手填 -->
                  <el-button
                    size="small"
                    :icon="Ruler"
                    :loading="detectingDimension"
                    :disabled="!canManageAiConfig"
                    aria-label="自动检测向量维度"
                    @click.stop="handleDetectDimension(provider.id, model)"
                  >自动检测</el-button>
                </div>
                <div class="model-actions">
                  <el-button
                    v-if="!isEmbeddingActive(provider.id, model.name)"
                    type="primary"
                    size="small"
                    :icon="Check"
                    :loading="saving"
                    @click.stop="handleUseEmbedding(provider, model.name, model.dimension)"
                  >
                    使用
                  </el-button>
                  <el-button
                    v-else
                    type="success"
                    size="small"
                    :icon="Check"
                    disabled
                  >
                    使用中
                  </el-button>
                </div>
              </div>
            </div>
            <div v-if="activeEmbedding?.providerId !== provider.id && expandedEmbeddingProvider === provider.id" class="change-notice">
              <el-alert type="warning" show-icon :closable="false">
                切换 Embedding 后需要重新向量化知识库
              </el-alert>
            </div>
          </div>
        </article>
      </div>
    </section>

    <!-- 供应商编辑对话框 -->
    <el-dialog v-model="providerDialogVisible" title="供应商" width="520px">
      <el-form label-width="100px" label-position="left">
        <el-form-item label="ID">
          <el-input
            v-model="providerForm.id"
            :disabled="Boolean(editingProviderId) || !canManageAiConfig"
            placeholder="dashscope"
          />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="providerForm.name" :disabled="!canManageAiConfig" placeholder="通义千问" />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input
            v-model="providerForm.baseUrl"
            :disabled="!canManageAiConfig"
            placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1"
          />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="providerForm.apiKey"
            type="password"
            show-password
            :disabled="!canManageAiConfig"
            placeholder="留空则不修改"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="providerDialogVisible = false">取消</el-button>
        <el-button v-if="canManageAiConfig" type="primary" @click="saveProvider">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
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

.header-title {
  display: flex;
  gap: 12px;
  color: var(--do-primary-strong);
}

.header-title h2 {
  margin: 0;
  color: var(--do-ink);
}

.header-title p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--do-muted);
}

.header-actions {
  display: flex;
  gap: 8px;
}

/* 状态区域 */
.status-section {
  padding: 18px 20px;
  margin-bottom: 18px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
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
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
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
  background: rgba(255, 255, 255, 0.5);
}

.tab-header button.active {
  color: var(--do-primary-strong);
  background: rgba(255, 255, 255, 0.8);
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
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.7);
  transition: all 0.2s;
}

.provider-card:hover {
  border-color: var(--do-primary);
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.06);
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
  background: rgba(255, 255, 255, 0.5);
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
  background: rgba(0, 0, 0, 0.04);
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
