<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, X, RefreshCw, ChevronDown, ChevronUp } from 'lucide-vue-next'
import {
  listKnowledgeDocs,
  approveDoc,
  rejectDoc,
  type KnowledgeDocItem
} from '../../../api/admin/knowledge'

const loading = ref(false)
const docs = ref<KnowledgeDocItem[]>([])
const total = ref(0)
const expandedId = ref<number | null>(null)
const page = ref(1)
const pageSize = 20

function extractError(error: unknown, fallback: string): string {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const msg = (error as any).response?.data?.message
    if (typeof msg === 'string') return msg
  }
  return fallback
}

async function fetchDocs() {
  loading.value = true
  try {
    const res = await listKnowledgeDocs({
      status: 'PENDING_REVIEW',
      page: page.value,
      pageSize
    })
    docs.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch (e) {
    ElMessage.error(extractError(e, '加载待审核列表失败'))
  } finally {
    loading.value = false
  }
}

function toggleExpand(id: number) {
  expandedId.value = expandedId.value === id ? null : id
}

async function handleApprove(doc: KnowledgeDocItem) {
  try {
    await ElMessageBox.confirm(
      `确认通过文档「${doc.title}」的审核？`,
      '审核通过'
    )
    await approveDoc(doc.id)
    ElMessage.success('审核已通过')
    fetchDocs()
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(extractError(e, '操作失败'))
  }
}

async function handleReject(doc: KnowledgeDocItem) {
  try {
    const { value: reason } = await ElMessageBox.prompt(
      '请输入驳回原因',
      `驳回文档「${doc.title}」`,
      {
        confirmButtonText: '驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '请说明驳回原因...',
        inputValidator: (val: string) => {
          if (!val || !val.trim()) return '请输入驳回原因'
          return true
        }
      }
    )
    await rejectDoc(doc.id, reason)
    ElMessage.success('已驳回')
    fetchDocs()
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(extractError(e, '操作失败'))
  }
}

onMounted(() => {
  fetchDocs()
})
</script>

<template>
  <main class="review-page post-login-page">
    <section class="page-actions">
      <el-button :icon="RefreshCw" @click="fetchDocs">刷新</el-button>
    </section>

    <el-empty v-if="!loading && docs.length === 0" description="暂无待审核文档" />

    <section class="review-list" v-loading="loading">
      <div v-for="doc in docs" :key="doc.id" class="review-card">
        <div class="review-card-header" @click="toggleExpand(doc.id)">
          <div class="review-card-info">
            <h3>{{ doc.title }}</h3>
            <span class="review-meta">版本 {{ doc.currentVersion }} · 更新于 {{ doc.updatedAt }}</span>
          </div>
          <div class="review-card-actions">
            <el-button type="success" size="small" @click.stop="handleApprove(doc)">
              <Check :size="14" style="margin-right: 4px" />通过
            </el-button>
            <el-button type="danger" size="small" @click.stop="handleReject(doc)">
              <X :size="14" style="margin-right: 4px" />驳回
            </el-button>
            <component :is="expandedId === doc.id ? ChevronUp : ChevronDown" :size="18" class="expand-icon" />
          </div>
        </div>
        <div v-if="expandedId === doc.id" class="review-card-body">
          <pre>{{ doc.content || '（无内容）' }}</pre>
        </div>
      </div>
    </section>

    <el-pagination v-if="total > pageSize" class="pager" background layout="total, prev, pager, next"
                   :total="total" :page-size="pageSize"
                   v-model:current-page="page" @current-change="fetchDocs" />
  </main>
</template>

<style scoped>
.review-page { display: grid; gap: 16px; }
.review-list { display: grid; gap: 12px; }
.review-card {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  overflow: hidden;
}
.review-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  cursor: pointer;
  transition: background 0.15s;
}
.review-card-header:hover { background: var(--do-primary-soft); }
.review-card-info h3 { margin: 0; font-size: 15px; color: var(--do-ink); }
.review-meta { font-size: 12px; color: var(--do-muted); }
.review-card-actions { display: flex; align-items: center; gap: 8px; }
.expand-icon { color: var(--do-muted); }
.review-card-body {
  border-top: 1px solid var(--do-line);
  padding: 16px 18px;
  max-height: 400px;
  overflow: auto;
  background: var(--do-bg, #f5fbef);
}
.review-card-body pre {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--do-ink);
}
.pager { margin-top: 16px; justify-content: flex-end; }
</style>
