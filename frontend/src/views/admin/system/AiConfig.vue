<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Cpu, Save, RefreshCw, Eye, EyeOff } from 'lucide-vue-next'
import { getAiConfig, updateAiConfig, type AiConfig, type AiConfigPayload } from '../../../api/admin/system'

const loading = ref(false)
const saving = ref(false)
const showApiKey = ref(false)
const config = ref<AiConfig | null>(null)

const form = ref<AiConfigPayload>({
  apiKey: '',
  baseUrl: '',
  model: '',
  temperature: '',
  timeout: '',
  embeddingModel: '',
  embeddingDimension: '',
})

const formRules = {
  temperature: [{ validator: (_r: unknown, v: string, cb: (e?: Error) => void) => {
    const n = Number(v)
    if (v && (isNaN(n) || n < 0 || n > 2)) cb(new Error('温度范围 0-2'))
    else cb()
  }, trigger: 'blur' }],
  timeout: [{ validator: (_r: unknown, v: string, cb: (e?: Error) => void) => {
    const n = Number(v)
    if (v && (isNaN(n) || n < 1 || n > 600)) cb(new Error('超时范围 1-600 秒'))
    else cb()
  }, trigger: 'blur' }],
  embeddingDimension: [{ validator: (_r: unknown, v: string, cb: (e?: Error) => void) => {
    const n = Number(v)
    if (v && (isNaN(n) || n < 1)) cb(new Error('维度必须为正整数'))
    else cb()
  }, trigger: 'blur' }],
}

async function fetchConfig() {
  loading.value = true
  try {
    const res = await getAiConfig()
    config.value = res.data ?? null
    if (config.value) {
      form.value = {
        apiKey: '',
        baseUrl: config.value.baseUrl,
        model: config.value.model,
        temperature: config.value.temperature,
        timeout: config.value.timeout,
        embeddingModel: config.value.embeddingModel,
        embeddingDimension: config.value.embeddingDimension,
      }
    }
  } catch {
    ElMessage.error('加载 AI 配置失败')
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  saving.value = true
  try {
    const payload: AiConfigPayload = {}
    if (form.value.apiKey) payload.apiKey = form.value.apiKey
    payload.baseUrl = form.value.baseUrl
    payload.model = form.value.model
    payload.temperature = form.value.temperature
    payload.timeout = form.value.timeout
    payload.embeddingModel = form.value.embeddingModel
    payload.embeddingDimension = form.value.embeddingDimension

    const res = await updateAiConfig(payload)
    config.value = res.data ?? null
    form.value.apiKey = ''
    ElMessage.success('AI 配置已更新，服务将在数秒内使用新配置')
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(fetchConfig)
</script>

<template>
  <div class="ai-config-page" v-loading="loading">
    <header class="page-header">
      <div class="header-title">
        <Cpu :size="22" />
        <div>
          <h2>AI 服务配置</h2>
          <p>管理 LLM 模型、API 端点和 Embedding 配置。修改后自动热更新，无需重启服务。</p>
        </div>
      </div>
      <el-button :icon="RefreshCw" :loading="loading" @click="fetchConfig">刷新</el-button>
    </header>

    <el-form
      v-if="config"
      :model="form"
      :rules="formRules"
      label-width="140px"
      label-position="left"
      class="config-form"
    >
      <section class="form-section">
        <h3>LLM 模型配置</h3>

        <el-form-item label="API Key">
          <div class="api-key-row">
            <el-input
              v-model="form.apiKey"
              :type="showApiKey ? 'text' : 'password'"
              placeholder="留空则不修改当前密钥"
              autocomplete="off"
            >
              <template #suffix>
                <el-icon class="eye-toggle" @click="showApiKey = !showApiKey">
                  <component :is="showApiKey ? EyeOff : Eye" />
                </el-icon>
              </template>
            </el-input>
            <span class="current-hint" v-if="config.apiKeyMasked">当前：{{ config.apiKeyMasked }}</span>
          </div>
        </el-form-item>

        <el-form-item label="API 端点" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
        </el-form-item>

        <el-form-item label="模型名称" prop="model">
          <el-input v-model="form.model" placeholder="qwen-plus" />
        </el-form-item>

        <el-form-item label="温度" prop="temperature">
          <el-input v-model="form.temperature" placeholder="0.3" style="width: 120px" />
          <span class="form-hint">0-2，越低越确定</span>
        </el-form-item>

        <el-form-item label="超时（秒）" prop="timeout">
          <el-input v-model="form.timeout" placeholder="120" style="width: 120px" />
        </el-form-item>
      </section>

      <section class="form-section">
        <h3>Embedding 配置</h3>

        <el-form-item label="Embedding 模型" prop="embeddingModel">
          <el-input v-model="form.embeddingModel" placeholder="text-embedding-v4" />
        </el-form-item>

        <el-form-item label="向量维度" prop="embeddingDimension">
          <el-input v-model="form.embeddingDimension" placeholder="1024" style="width: 120px" />
        </el-form-item>
      </section>

      <div class="form-actions">
        <el-button type="primary" :icon="Save" :loading="saving" @click="handleSave">保存并更新</el-button>
        <span class="save-hint">保存后 AI 服务将在数秒内自动使用新配置</span>
      </div>
    </el-form>
  </div>
</template>

<style scoped>
.ai-config-page {
  max-width: 720px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 28px;
}

.header-title {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  color: var(--do-primary-strong);
}

.header-title h2 {
  margin: 0 0 4px;
  font-size: 20px;
  color: var(--do-ink);
}

.header-title p {
  margin: 0;
  font-size: 13px;
  color: var(--do-muted);
}

.config-form {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.form-section {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  background: var(--do-surface);
}

.form-section h3 {
  margin: 0 0 18px;
  font-size: 15px;
  color: var(--do-ink);
  padding-bottom: 10px;
  border-bottom: 1px solid var(--do-line);
}

.api-key-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.current-hint {
  font-size: 12px;
  color: var(--do-muted);
}

.eye-toggle {
  cursor: pointer;
  color: var(--do-muted);
}

.form-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--do-muted);
}

.form-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

.save-hint {
  font-size: 12px;
  color: var(--do-muted);
}
</style>
