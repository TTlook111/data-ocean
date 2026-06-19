<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
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
} from 'lucide-vue-next'
import {
  createAiProvider,
  deleteAiProvider,
  getAiConfig,
  testAiProvider,
  updateAiConfig,
  updateAiProvider,
  type AiConfig,
  type AiProvider,
  type AiProviderPayload,
} from '../../../api/admin/system'
import { useAuthStore } from '../../../stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const providerDialogVisible = ref(false)
const editingProviderId = ref('')
const config = ref<AiConfig | null>(null)
const activeTab = ref<'chat' | 'embedding'>('chat')

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

const vectorizeTagType = computed(() => {
  if (vectorizeStatus.value?.status === 'NORMAL') return 'success'
  if (vectorizeStatus.value?.status === 'REINDEX_FAILED') return 'danger'
  return 'warning'
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

function embeddingChanged() {
  const active = activeEmbedding.value
  if (!active) return false
  return (
    active.providerId !== editingEmbeddingConfig.providerId
    || active.model !== editingEmbeddingConfig.model
    || Number(active.dimension) !== Number(editingEmbeddingConfig.dimension)
  )
}

function buildCollectionName() {
  const safeModel = editingEmbeddingConfig.model.replace(/[^a-zA-Z0-9_]/g, '_')
  return `schema_knowledge_${editingEmbeddingConfig.dimension}_${safeModel}`
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

async function handleUseEmbedding(provider: AiProvider, model: string, dimension: number) {
  if (!canManageAiConfig.value) {
    ElMessage.warning('当前账号只有查看权限')
    return
  }

  const willChange = embeddingChanged()

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
        collection: willChange ? buildCollectionName() : activeEmbedding.value?.collection,
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
          <span class="tile-icon status"><Server :size="18" /></span>
          <div>
            <span>索引状态</span>
            <el-tag :type="vectorizeTagType" size="small">
              {{ vectorizeStatus?.status || 'NORMAL' }}
            </el-tag>
          </div>
        </div>
      </div>
      <el-alert v-if="vectorizeMessage" type="warning" show-icon :closable="false" :title="vectorizeMessage" />
    </section>

    <!-- Tab 切换 -->
    <section class="config-section">
      <div class="tab-header">
        <button :class="{ active: activeTab === 'chat' }" @click="activeTab = 'chat'">
          <Sparkles :size="16" />
          Chat 配置
        </button>
        <button :class="{ active: activeTab === 'embedding' }" @click="activeTab = 'embedding'">
          <Database :size="16" />
          Embedding 配置
        </button>
      </div>

      <!-- Chat 配置列表 -->
      <div v-if="activeTab === 'chat'" class="provider-cards">
        <div v-if="chatProviders.length === 0" class="empty-state">
          <p>暂无 Chat 供应商，请先添加供应商并测试连接获取模型列表。</p>
        </div>

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
              <el-button :icon="Wifi" size="small" circle title="测试连接" @click="handleTestProvider(provider)" />
              <el-button size="small" circle title="编辑" @click="openEditProvider(provider)">
                <Edit :size="12" />
              </el-button>
              <el-button :icon="Trash2" size="small" type="danger" plain circle @click="handleDeleteProvider(provider)" />
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
        <div v-if="embeddingProviders.length === 0" class="empty-state">
          <p>暂无 Embedding 供应商，请先添加供应商并测试连接获取模型列表。</p>
        </div>

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
              <el-button :icon="Wifi" size="small" circle title="测试连接" @click="handleTestProvider(provider)" />
              <el-button size="small" circle title="编辑" @click="openEditProvider(provider)">
                <Edit :size="12" />
              </el-button>
              <el-button :icon="Trash2" size="small" type="danger" plain circle @click="handleDeleteProvider(provider)" />
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
                      v-model="editingEmbeddingConfig.dimension"
                      size="small"
                      :min="1"
                      :step="1"
                      :disabled="!canManageAiConfig"
                      @click.stop
                    />
                  </label>
                </div>
                <div class="model-actions">
                  <el-button
                    v-if="!isEmbeddingActive(provider.id, model.name)"
                    type="primary"
                    size="small"
                    :icon="Check"
                    :loading="saving"
                    @click.stop="handleUseEmbedding(provider, model.name, editingEmbeddingConfig.dimension)"
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
            <div v-if="embeddingChanged() && expandedEmbeddingProvider === provider.id" class="change-notice">
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
  background: rgba(37, 99, 235, 0.04);
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
  color: #2563eb;
  background: #eff6ff;
}

.tile-icon.embedding {
  color: #047857;
  background: #ecfdf5;
}

.tile-icon.collection {
  color: #7c3aed;
  background: #f5f3ff;
}

.tile-icon.status {
  color: #d97706;
  background: #fffbeb;
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
  box-shadow: 0 4px 16px rgba(37, 99, 235, 0.1);
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
  border-color: #10b981;
  background: rgba(16, 185, 129, 0.04);
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

@media (max-width: 760px) {
  .status-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .card-header {
    flex-wrap: wrap;
  }

  .card-summary {
    width: 100%;
  }

  .model-item {
    flex-wrap: wrap;
  }

  .model-params {
    width: 100%;
  }
}
</style>
