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
import {
  generationSourceLabel,
  generationSourceType,
  knowledgeStatusLabel,
  knowledgeStatusType,
} from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'

const route = useRoute()
const docId = Number(route.params.id)
const adminContext = useAdminContextStore()

const loading = ref(false)
const doc = ref<KnowledgeDocItem | null>(null)
const versions = ref<KnowledgeVersionItem[]>([])
const previewVisible = ref(false)
const previewContent = ref('')
const previewVersion = ref(0)

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
    if (doc.value) {
      adminContext.selectDatasource(doc.value.datasourceId)
      adminContext.selectKnowledgeDoc(doc.value.id)
    }
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
      `确认回滚到版本 ${targetVersionNo}？将基于该版本创建新版本。`,
      '版本回滚'
    )
    await rollbackVersion(docId, targetVersionNo)
    ElMessage.success('回滚成功')
    fetchVersions()
    fetchDoc()
    adminContext.refresh()
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
    <section class="page-actions">
      <el-button :icon="RefreshCw" @click="fetchVersions">刷新</el-button>
    </section>

    <section class="table-shell">
      <el-table :data="versions" v-loading="loading" stripe>
        <el-table-column prop="versionNo" label="版本号" width="90">
          <template #default="{ row }">版本 {{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column prop="generationSource" label="生成来源" width="120">
          <template #default="{ row }">
            <el-tag :type="generationSourceType(row.generationSource)" size="small">
              {{ generationSourceLabel(row.generationSource) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reviewStatus" label="审核状态" width="110">
          <template #default="{ row }">
            <el-tag :type="knowledgeStatusType(row.reviewStatus)" size="small">
              {{ knowledgeStatusLabel(row.reviewStatus) }}
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

    <el-dialog v-model="previewVisible" :title="`版本 ${previewVersion} 内容`" width="700px">
      <div class="version-preview-content">
        <pre>{{ previewContent }}</pre>
      </div>
    </el-dialog>
  </main>
</template>

<style scoped>
.version-list-page { display: grid; gap: 16px; }
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
