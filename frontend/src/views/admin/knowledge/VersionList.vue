<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RotateCcw, Eye, RefreshCw } from 'lucide-vue-next'
import {
  getKnowledgeDoc,
  listVersions,
  rollbackVersion,
  type KnowledgeDocItem,
  type KnowledgeVersionItem
} from '../../../api/admin/knowledge'

const route = useRoute()
const docId = Number(route.params.id)

const loading = ref(false)
const doc = ref<KnowledgeDocItem | null>(null)
const versions = ref<KnowledgeVersionItem[]>([])
const previewVisible = ref(false)
const previewContent = ref('')
const previewVersion = ref(0)

const sourceOptions: Record<string, string> = {
  MANUAL: '人工编辑',
  AI_GENERATED: 'AI 生成',
  ROLLBACK: '版本回滚'
}

const sourceType = (s: string) => {
  const map: Record<string, string> = { MANUAL: '', AI_GENERATED: 'success', ROLLBACK: 'warning' }
  return map[s] ?? 'info'
}

const reviewStatusLabel = (s: string) => {
  const map: Record<string, string> = {
    DRAFT: '草稿', PENDING_REVIEW: '待审核', APPROVED: '已通过', REJECTED: '已驳回'
  }
  return map[s] ?? s
}

const reviewStatusType = (s: string) => {
  const map: Record<string, string> = {
    DRAFT: 'info', PENDING_REVIEW: 'warning', APPROVED: 'success', REJECTED: 'danger'
  }
  return map[s] ?? 'info'
}

function extractError(error: unknown, fallback: string): string {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const msg = (error as any).response?.data?.message
    if (typeof msg === 'string') return msg
  }
  return fallback
}

async function fetchDoc() {
  try {
    const res = await getKnowledgeDoc(docId)
    doc.value = res.data as KnowledgeDocItem
  } catch (e) {
    ElMessage.error(extractError(e, '加载文档信息失败'))
  }
}

async function fetchVersions() {
  loading.value = true
  try {
    const res = await listVersions(docId)
    versions.value = (res.data as KnowledgeVersionItem[]) ?? []
  } catch (e) {
    ElMessage.error(extractError(e, '加载版本列表失败'))
  } finally {
    loading.value = false
  }
}

function showPreview(v: KnowledgeVersionItem) {
  previewContent.value = v.content
  previewVersion.value = v.versionNo
  previewVisible.value = true
}

async function handleRollback(targetVersionNo: number) {
  try {
    await ElMessageBox.confirm(
      `确认回滚到版本 v${targetVersionNo}？将基于该版本创建新版本。`,
      '版本回滚'
    )
    await rollbackVersion(docId, targetVersionNo)
    ElMessage.success('回滚成功')
    fetchVersions()
    fetchDoc()
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(extractError(e, '回滚失败'))
  }
}

onMounted(() => {
  fetchDoc()
  fetchVersions()
})
</script>

<template>
  <main class="version-list-page post-login-page">
    <header class="page-header">
      <div>
        <p>知识库管理</p>
        <h1>版本历史</h1>
        <span class="header-subtitle">
          {{ doc ? `${doc.title} — 当前版本 v${doc.currentVersion}` : '加载中...' }}
        </span>
      </div>
      <el-button :icon="RefreshCw" @click="fetchVersions">刷新</el-button>
    </header>

    <section class="table-shell">
      <el-table :data="versions" v-loading="loading" stripe>
        <el-table-column prop="versionNo" label="版本号" width="90">
          <template #default="{ row }">v{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column prop="generationSource" label="生成来源" width="120">
          <template #default="{ row }">
            <el-tag :type="sourceType(row.generationSource)" size="small">
              {{ sourceOptions[row.generationSource] || row.generationSource }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reviewStatus" label="审核状态" width="110">
          <template #default="{ row }">
            <el-tag :type="reviewStatusType(row.reviewStatus)" size="small">
              {{ reviewStatusLabel(row.reviewStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="changeSummary" label="变更摘要" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="showPreview(row)">
              <Eye :size="14" style="margin-right: 4px" />查看
            </el-button>
            <el-button link type="warning" size="small" @click="handleRollback(row.versionNo)">
              <RotateCcw :size="14" style="margin-right: 4px" />回滚
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="previewVisible" :title="`版本 v${previewVersion} 内容`" width="700px">
      <div class="version-preview-content">
        <pre>{{ previewContent }}</pre>
      </div>
    </el-dialog>
  </main>
</template>

<style scoped>
.version-list-page { display: grid; gap: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; }
.page-header p { font-size: 12px; color: var(--do-muted); margin: 0 0 4px; }
.page-header h1 { font-size: 22px; margin: 0; color: var(--do-ink); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }
.table-shell { border: 1px solid var(--do-line); border-radius: 8px; overflow: hidden; background: var(--do-surface); }
.version-preview-content {
  max-height: 500px;
  overflow: auto;
  background: var(--do-bg, #f5fbef);
  border: 1px solid var(--do-line);
  border-radius: 6px;
  padding: 16px;
}
.version-preview-content pre {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--do-ink);
}
</style>
