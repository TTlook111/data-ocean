<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Cpu, Database, HardDrive, Plus, RefreshCw, Save, Server, Sparkles, Trash2, Wifi } from 'lucide-vue-next'
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

const form = reactive({
  chatProviderId: '',
  chatModel: '',
  temperature: '0.3',
  timeout: '120',
  maxRetries: '2',
  embeddingProviderId: '',
  embeddingModel: '',
  embeddingDimension: 1024,
})

const providerForm = reactive<AiProviderPayload>({
  id: '',
  name: '',
  baseUrl: '',
  apiKey: '',
})

const providers = computed(() => config.value?.providers ?? [])
const permissions = computed(() => auth.user?.permissions || auth.currentUser?.permissions || [])
const canManageAiConfig = computed(() => permissions.value.includes('*') || permissions.value.includes('system:ai-config:manage'))
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
const selectedChatProvider = computed(() => providers.value.find((item) => item.id === form.chatProviderId))
const selectedEmbeddingProvider = computed(() => providers.value.find((item) => item.id === form.embeddingProviderId))
const chatModels = computed(() => selectedChatProvider.value?.chatModels ?? [])
const embeddingModels = computed(() => selectedEmbeddingProvider.value?.embeddingModels ?? [])

function fillForm(data: AiConfig) {
  form.chatProviderId = data.activeChat?.providerId || 'dashscope'
  form.chatModel = data.activeChat?.model || data.model
  form.temperature = data.activeChat?.temperature || data.temperature || '0.3'
  form.timeout = data.activeChat?.timeout || data.timeout || '120'
  form.maxRetries = data.activeChat?.maxRetries || '2'
  form.embeddingProviderId = data.activeEmbedding?.providerId || 'dashscope'
  form.embeddingModel = data.activeEmbedding?.model || data.embeddingModel
  form.embeddingDimension = Number(data.activeEmbedding?.dimension || data.embeddingDimension || 1024)
}

async function fetchConfig() {
  loading.value = true
  try {
    const res = await getAiConfig()
    config.value = res.data ?? null
    if (config.value) fillForm(config.value)
  } catch {
    ElMessage.error('加载 AI 配置失败')
  } finally {
    loading.value = false
  }
}

function embeddingChanged() {
  const active = activeEmbedding.value
  if (!active) return false
  return active.providerId !== form.embeddingProviderId
    || active.model !== form.embeddingModel
    || Number(active.dimension) !== Number(form.embeddingDimension)
}

function buildCollectionName() {
  const safeModel = form.embeddingModel.replace(/[^a-zA-Z0-9_]/g, '_')
  return `schema_knowledge_${form.embeddingDimension}_${safeModel}`
}

async function handleSave() {
  if (!canManageAiConfig.value) {
    ElMessage.warning('当前账号只有查看权限，不能修改 AI 配置')
    return
  }
  if (!form.chatProviderId || !form.chatModel || !form.embeddingProviderId || !form.embeddingModel) {
    ElMessage.warning('请选择供应商和模型')
    return
  }

  const willChangeEmbedding = embeddingChanged()
  if (willChangeEmbedding) {
    await ElMessageBox.confirm(
      '切换 Embedding 后会先构建 pending 索引。新索引发布成功前，查询仍使用上一版索引；成功后系统再清理旧索引。',
      '确认切换 Embedding',
      { type: 'warning', confirmButtonText: '确认并保存', cancelButtonText: '取消' },
    )
  }

  saving.value = true
  try {
    const payload = {
      chat: {
        providerId: form.chatProviderId,
        model: form.chatModel,
        temperature: form.temperature,
        timeout: form.timeout,
        maxRetries: form.maxRetries,
      },
      embedding: {
        providerId: form.embeddingProviderId,
        model: form.embeddingModel,
        dimension: Number(form.embeddingDimension),
        collection: willChangeEmbedding ? buildCollectionName() : activeEmbedding.value?.collection,
        indexVersion: activeEmbedding.value?.indexVersion || 'v1',
      },
    }
    const res = await updateAiConfig(payload)
    config.value = res.data ?? null
    if (config.value) fillForm(config.value)
    ElMessage.success(willChangeEmbedding ? '配置已保存，Embedding 已标记为待重建' : '配置已保存')
  } catch (error) {
    if (error !== 'cancel') ElMessage.error('保存失败')
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
    ElMessage.warning('当前账号只有查看权限，不能修改供应商')
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

function onEmbeddingModelChange(modelName: string) {
  const item = embeddingModels.value.find((model) => model.name === modelName)
  if (item?.dimension) form.embeddingDimension = item.dimension
}

fetchConfig()
</script>

<template>
  <div class="ai-config-page" v-loading="loading">
    <header class="page-header">
      <div class="header-title">
        <Cpu :size="22" />
        <div>
          <h2>AI 服务配置</h2>
          <p>管理供应商、模型选择和索引切换状态。</p>
        </div>
      </div>
      <el-button :icon="RefreshCw" :loading="loading" @click="fetchConfig">刷新</el-button>
    </header>

    <section v-if="config" class="section">
      <div class="section-title">
        <h3>当前生效</h3>
        <el-tag :type="vectorizeTagType">
          {{ vectorizeStatus?.status || 'NORMAL' }}
        </el-tag>
      </div>
      <div class="status-grid">
        <div class="status-tile">
          <span class="tile-icon chat"><Sparkles :size="18" /></span>
          <div>
            <span>Chat 模型</span>
            <strong>{{ config.activeChat?.providerId }} / {{ config.activeChat?.model }}</strong>
          </div>
        </div>
        <div class="status-tile">
          <span class="tile-icon embedding"><Database :size="18" /></span>
          <div>
            <span>Embedding 模型</span>
            <strong>{{ config.activeEmbedding?.providerId }} / {{ config.activeEmbedding?.model }}</strong>
          </div>
        </div>
        <div class="status-tile">
          <span class="tile-icon collection"><HardDrive :size="18" /></span>
          <div>
            <span>Collection</span>
            <strong>{{ config.activeEmbedding?.collection || 'schema_knowledge' }}</strong>
          </div>
        </div>
      </div>
      <el-alert
        v-if="vectorizeMessage"
        type="warning"
        show-icon
        :closable="false"
        :title="vectorizeMessage"
      >
        <template #default>
          <div class="alert-action">
            <span>pending 配置不会影响当前查询，完成向量化后再切换为 active。</span>
            <el-button size="small" type="warning" plain disabled>重新向量化</el-button>
          </div>
        </template>
      </el-alert>
    </section>

    <section class="section">
      <div class="section-title">
        <h3>供应商管理</h3>
        <el-button v-if="canManageAiConfig" type="primary" :icon="Plus" @click="openCreateProvider">添加供应商</el-button>
      </div>
      <div class="provider-list">
        <article v-for="provider in providers" :key="provider.id" class="provider-item">
          <div class="provider-main">
            <div class="provider-name">
              <span class="provider-icon"><Server :size="16" /></span>
              <strong>{{ provider.name || provider.id }}</strong>
              <el-tag size="small" :type="provider.status === 'connected' ? 'success' : provider.status === 'failed' ? 'danger' : 'info'">
                {{ provider.status || 'unknown' }}
              </el-tag>
            </div>
            <span>{{ provider.baseUrl }}</span>
            <small>Key: {{ provider.apiKeyMasked || '未配置' }}</small>
          </div>
          <div class="provider-meta">
            <el-tag size="small" effect="plain">{{ provider.chatModels?.length || 0 }} Chat</el-tag>
            <el-tag size="small" effect="plain">{{ provider.embeddingModels?.length || 0 }} Embedding</el-tag>
          </div>
          <div class="provider-actions">
            <el-button :icon="Wifi" circle title="测试连接" :disabled="!canManageAiConfig" @click="handleTestProvider(provider)" />
            <el-button circle title="编辑" :disabled="!canManageAiConfig" @click="openEditProvider(provider)">编</el-button>
            <el-button :icon="Trash2" type="danger" plain :disabled="!canManageAiConfig" @click="handleDeleteProvider(provider)" />
          </div>
        </article>
      </div>
    </section>

    <section class="section">
      <div class="section-title">
        <h3>模型选择</h3>
      </div>
      <div class="model-grid">
        <el-form label-width="96px" label-position="left" class="model-panel">
          <div class="model-panel-title">
            <Sparkles :size="18" />
            <strong>Chat 配置</strong>
          </div>
          <el-form-item label="供应商">
            <el-select v-model="form.chatProviderId" filterable :disabled="!canManageAiConfig">
              <el-option v-for="provider in providers" :key="provider.id" :label="provider.name || provider.id" :value="provider.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="模型">
            <el-select v-model="form.chatModel" filterable allow-create default-first-option :disabled="!canManageAiConfig">
              <el-option v-for="model in chatModels" :key="model.name" :label="model.displayName || model.name" :value="model.name" />
            </el-select>
          </el-form-item>
          <div class="compact-inputs">
            <label>
              <span>温度</span>
              <el-input v-model="form.temperature" :disabled="!canManageAiConfig" />
            </label>
            <label>
              <span>超时秒数</span>
              <el-input v-model="form.timeout" :disabled="!canManageAiConfig" />
            </label>
            <label>
              <span>重试次数</span>
              <el-input v-model="form.maxRetries" :disabled="!canManageAiConfig" />
            </label>
          </div>
        </el-form>

        <el-form label-width="96px" label-position="left" class="model-panel">
          <div class="model-panel-title">
            <Database :size="18" />
            <strong>Embedding 配置</strong>
          </div>
          <el-form-item label="供应商">
            <el-select v-model="form.embeddingProviderId" filterable :disabled="!canManageAiConfig">
              <el-option v-for="provider in providers" :key="provider.id" :label="provider.name || provider.id" :value="provider.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="模型">
            <el-select v-model="form.embeddingModel" filterable allow-create default-first-option :disabled="!canManageAiConfig" @change="onEmbeddingModelChange">
              <el-option v-for="model in embeddingModels" :key="model.name" :label="model.displayName || model.name" :value="model.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="向量维度">
            <el-input-number v-model="form.embeddingDimension" :min="1" :step="1" :disabled="!canManageAiConfig" />
          </el-form-item>
        </el-form>
      </div>
      <div class="form-actions">
        <el-button v-if="canManageAiConfig" type="primary" :icon="Save" :loading="saving" @click="handleSave">保存配置</el-button>
      </div>
    </section>

    <el-dialog v-model="providerDialogVisible" title="供应商" width="520px">
      <el-form label-width="100px" label-position="left">
        <el-form-item label="ID">
          <el-input v-model="providerForm.id" :disabled="Boolean(editingProviderId) || !canManageAiConfig" placeholder="dashscope" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="providerForm.name" :disabled="!canManageAiConfig" placeholder="通义千问" />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="providerForm.baseUrl" :disabled="!canManageAiConfig" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="providerForm.apiKey" type="password" show-password :disabled="!canManageAiConfig" placeholder="留空则不修改" />
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
  max-width: 980px;
}

.page-header,
.section-title,
.provider-item,
.provider-actions,
.provider-meta {
  display: flex;
  align-items: center;
}

.page-header {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;
}

.header-title {
  display: flex;
  gap: 12px;
  color: var(--do-primary-strong);
}

.header-title h2,
.section-title h3 {
  margin: 0;
  color: var(--do-ink);
}

.header-title p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--do-muted);
}

.section {
  padding: 18px 20px;
  margin-bottom: 18px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
}

.section-title {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.status-tile,
.provider-item {
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
}

.status-tile {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  background: rgba(255, 255, 255, 0.72);
}

.status-tile > div {
  min-width: 0;
}

.tile-icon,
.provider-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  border-radius: 8px;
}

.tile-icon {
  width: 34px;
  height: 34px;
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

.status-grid span,
.provider-main span,
.provider-main small {
  display: block;
  color: var(--do-muted);
  font-size: 12px;
}

.status-grid strong {
  display: block;
  margin-top: 4px;
  color: var(--do-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.alert-action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.provider-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.provider-item {
  justify-content: space-between;
  gap: 14px;
  background: rgba(255, 255, 255, 0.7);
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.provider-item:hover {
  border-color: var(--do-primary);
  box-shadow: 0 8px 22px rgba(15, 23, 42, 0.06);
}

.provider-main {
  flex: 1;
  min-width: 0;
}

.provider-name {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.provider-icon {
  width: 28px;
  height: 28px;
  color: var(--do-primary-strong);
  background: rgba(37, 99, 235, 0.08);
}

.provider-main span,
.provider-main small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.provider-meta,
.provider-actions {
  gap: 8px;
}

.model-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.model-panel {
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.68);
}

.model-panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  color: var(--do-primary-strong);
}

.compact-inputs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.compact-inputs label {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.compact-inputs span {
  font-size: 12px;
  color: var(--do-muted);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

@media (max-width: 760px) {
  .status-grid,
  .model-grid,
  .compact-inputs {
    grid-template-columns: 1fr;
  }

  .provider-item {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
