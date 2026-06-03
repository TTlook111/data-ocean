<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Cpu, Plus, RefreshCw, Save, Trash2, Wifi } from 'lucide-vue-next'
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
  editingProviderId.value = ''
  Object.assign(providerForm, { id: '', name: '', baseUrl: '', apiKey: '' })
  providerDialogVisible.value = true
}

function openEditProvider(provider: AiProvider) {
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
        <el-tag :type="vectorizeStatus?.status === 'NORMAL' ? 'success' : 'warning'">
          {{ vectorizeStatus?.status || 'NORMAL' }}
        </el-tag>
      </div>
      <div class="status-grid">
        <div>
          <span>Chat</span>
          <strong>{{ config.activeChat?.providerId }} / {{ config.activeChat?.model }}</strong>
        </div>
        <div>
          <span>Embedding</span>
          <strong>{{ config.activeEmbedding?.providerId }} / {{ config.activeEmbedding?.model }}</strong>
        </div>
        <div>
          <span>Collection</span>
          <strong>{{ config.activeEmbedding?.collection || 'schema_knowledge' }}</strong>
        </div>
      </div>
      <el-alert
        v-if="vectorizeMessage"
        type="warning"
        show-icon
        :closable="false"
        :title="vectorizeMessage"
      />
    </section>

    <section class="section">
      <div class="section-title">
        <h3>供应商管理</h3>
        <el-button type="primary" :icon="Plus" @click="openCreateProvider">添加供应商</el-button>
      </div>
      <div class="provider-list">
        <article v-for="provider in providers" :key="provider.id" class="provider-item">
          <div class="provider-main">
            <strong>{{ provider.name || provider.id }}</strong>
            <span>{{ provider.baseUrl }}</span>
            <small>Key: {{ provider.apiKeyMasked || '未配置' }}</small>
          </div>
          <div class="provider-meta">
            <el-tag size="small">{{ provider.status || 'unknown' }}</el-tag>
            <span>{{ provider.chatModels?.length || 0 }} Chat</span>
            <span>{{ provider.embeddingModels?.length || 0 }} Embedding</span>
          </div>
          <div class="provider-actions">
            <el-button :icon="Wifi" @click="handleTestProvider(provider)">测试</el-button>
            <el-button @click="openEditProvider(provider)">编辑</el-button>
            <el-button :icon="Trash2" type="danger" plain @click="handleDeleteProvider(provider)" />
          </div>
        </article>
      </div>
    </section>

    <section class="section">
      <div class="section-title">
        <h3>模型选择</h3>
      </div>
      <el-form label-width="130px" label-position="left">
        <el-form-item label="Chat 供应商">
          <el-select v-model="form.chatProviderId" filterable>
            <el-option v-for="provider in providers" :key="provider.id" :label="provider.name || provider.id" :value="provider.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="Chat 模型">
          <el-select v-model="form.chatModel" filterable allow-create default-first-option>
            <el-option v-for="model in chatModels" :key="model.name" :label="model.displayName || model.name" :value="model.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="温度 / 超时">
          <el-input v-model="form.temperature" style="width: 110px" />
          <el-input v-model="form.timeout" style="width: 110px; margin-left: 10px" />
          <el-input v-model="form.maxRetries" style="width: 110px; margin-left: 10px" />
        </el-form-item>
        <el-form-item label="Embedding 供应商">
          <el-select v-model="form.embeddingProviderId" filterable>
            <el-option v-for="provider in providers" :key="provider.id" :label="provider.name || provider.id" :value="provider.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="Embedding 模型">
          <el-select v-model="form.embeddingModel" filterable allow-create default-first-option @change="onEmbeddingModelChange">
            <el-option v-for="model in embeddingModels" :key="model.name" :label="model.displayName || model.name" :value="model.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="向量维度">
          <el-input-number v-model="form.embeddingDimension" :min="1" :step="1" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Save" :loading="saving" @click="handleSave">保存配置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <el-dialog v-model="providerDialogVisible" title="供应商" width="520px">
      <el-form label-width="100px" label-position="left">
        <el-form-item label="ID">
          <el-input v-model="providerForm.id" :disabled="Boolean(editingProviderId)" placeholder="dashscope" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="providerForm.name" placeholder="通义千问" />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="providerForm.baseUrl" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="providerForm.apiKey" type="password" show-password placeholder="留空则不修改" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="providerDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveProvider">保存</el-button>
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

.status-grid div,
.provider-item {
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
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
}

.provider-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.provider-item {
  justify-content: space-between;
  gap: 14px;
}

.provider-main {
  flex: 1;
  min-width: 0;
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

@media (max-width: 760px) {
  .status-grid {
    grid-template-columns: 1fr;
  }

  .provider-item {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
