<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Database, MessageSquareText, RefreshCw } from 'lucide-vue-next'
import { listMyDatasources, type UserDatasourceItem } from '../../api/datasource'

const loading = ref(false)
const errorMessage = ref('')
const datasources = ref<UserDatasourceItem[]>([])
const selectedId = ref<number>()
const question = ref('')
const questionInputRef = ref()

async function focusQuestionInput() {
  await nextTick()
  questionInputRef.value?.focus?.()
}

function selectDatasource(id: number) {
  if (selectedId.value !== id) {
    question.value = ''
  }
  selectedId.value = id
  focusQuestionInput()
}

async function fetchDatasources() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listMyDatasources()
    datasources.value = result.data
    if (result.data.length === 1) {
      selectedId.value = result.data[0].id
      focusQuestionInput()
    } else if (!result.data.some((item) => item.id === selectedId.value)) {
      selectedId.value = undefined
      question.value = ''
    }
  } catch (error: unknown) {
    datasources.value = []
    selectedId.value = undefined
    question.value = ''
    errorMessage.value =
      typeof error === 'object' &&
      error !== null &&
      'response' in error &&
      typeof (error as { response?: { data?: { message?: string } } }).response?.data?.message === 'string'
        ? (error as { response: { data: { message: string } } }).response.data.message
        : '数据源加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function startQuery() {
  if (!selectedId.value || !question.value.trim()) return
  ElMessage.info('查询对话页将在后续模块开放')
}

onMounted(fetchDatasources)
</script>

<template>
  <main class="query-page post-login-page">
    <section class="query-shell">
      <header class="page-header">
        <div>
          <p>智能查询</p>
          <h1>选择本次查询的数据源</h1>
          <span class="header-subtitle">每次自然语言查询限定在一个已授权、已启用的数据源内执行。</span>
        </div>
        <el-button :loading="loading" @click="fetchDatasources">
          <RefreshCw :size="16" />
          刷新
        </el-button>
      </header>

      <section class="datasource-area">
        <div v-if="loading && !datasources.length" class="skeleton-grid">
          <el-skeleton v-for="item in 3" :key="item" animated class="datasource-skeleton">
            <template #template>
              <el-skeleton-item variant="circle" class="skeleton-icon" />
              <el-skeleton-item variant="h3" style="width: 60%" />
              <el-skeleton-item variant="text" style="width: 45%" />
              <el-skeleton-item variant="text" style="width: 88%" />
            </template>
          </el-skeleton>
        </div>

        <el-result v-else-if="errorMessage" icon="error" title="数据源加载失败" :sub-title="errorMessage">
          <template #extra>
            <el-button type="primary" @click="fetchDatasources">重试</el-button>
          </template>
        </el-result>

        <el-empty v-else-if="!datasources.length" description="暂无可用数据源">
          <span class="empty-tip">请联系管理员开通数据源访问权限</span>
        </el-empty>

        <section v-else class="datasource-grid" v-loading="loading">
          <button
            v-for="datasource in datasources"
            :key="datasource.id"
            type="button"
            class="datasource-option"
            :class="{ selected: datasource.id === selectedId }"
            @click="selectDatasource(datasource.id)"
          >
            <span v-if="datasource.id === selectedId" class="selected-mark">
              <Check :size="14" />
            </span>
            <span class="icon-box">
              <Database :size="20" />
            </span>
            <span class="datasource-copy">
              <strong>{{ datasource.name }}</strong>
              <small>库名：{{ datasource.databaseName }}</small>
              <em>{{ datasource.description || '已授权的业务数据源' }}</em>
            </span>
            <span class="select-action">选择此数据源</span>
          </button>
        </section>
      </section>

      <footer class="query-footer">
        <el-input
          ref="questionInputRef"
          v-model="question"
          :disabled="!selectedId"
          :placeholder="selectedId ? '例如：查询上个月销售额最高的10个产品' : '请先选择一个数据源'"
          @keyup.enter="startQuery"
        >
          <template #prefix>
            <MessageSquareText :size="16" />
          </template>
        </el-input>
        <el-button type="primary" :disabled="!selectedId || !question.trim()" @click="startQuery">开始查询</el-button>
      </footer>
    </section>
  </main>
</template>

<style scoped>
.query-page {
  display: grid;
}

.query-shell {
  display: grid;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-header p {
  margin: 0 0 6px;
  color: var(--do-primary);
  font-weight: 800;
}

.page-header h1 {
  margin: 0;
  font-size: 24px;
  color: var(--do-ink);
}

.datasource-area {
  min-height: 260px;
}

.skeleton-grid,
.datasource-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.datasource-skeleton,
.datasource-option {
  min-height: 176px;
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.skeleton-icon {
  width: 44px;
  height: 44px;
  margin-bottom: 14px;
}

.datasource-option {
  position: relative;
  display: grid;
  grid-template-columns: 44px 1fr;
  grid-template-rows: 1fr auto;
  gap: 14px;
  color: var(--do-ink);
  text-align: left;
  cursor: pointer;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.datasource-option:hover {
  border-color: var(--do-primary);
  box-shadow: 0 14px 30px rgba(77, 143, 220, 0.16);
  transform: translateY(-2px);
}

.datasource-option.selected {
  border-color: var(--do-primary);
  box-shadow: 0 0 0 3px rgba(77, 143, 220, 0.16), var(--do-shadow);
}

.selected-mark {
  position: absolute;
  top: 10px;
  left: 10px;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  background: var(--do-primary);
}

.icon-box {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: var(--do-primary);
  background: var(--do-primary-soft);
}

.datasource-copy strong,
.datasource-copy small,
.datasource-copy em {
  display: block;
}

.datasource-copy strong {
  margin-bottom: 7px;
  font-size: 17px;
}

.datasource-copy small {
  color: var(--do-muted);
  font-size: 13px;
}

.datasource-copy em {
  display: -webkit-box;
  height: 42px;
  margin-top: 12px;
  overflow: hidden;
  color: var(--do-muted);
  font-style: normal;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.select-action {
  grid-column: 1 / -1;
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 900;
}

.query-footer {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.empty-tip {
  color: var(--do-muted);
}
</style>
