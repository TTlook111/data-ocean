<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Save, Send, Sparkles, Upload } from 'lucide-vue-next'
import {
  getKnowledgeDoc,
  createKnowledgeDoc,
  updateKnowledgeDoc,
  submitReview,
  publishDoc,
  generateDraft,
  type KnowledgeDocItem
} from '../../../api/admin/knowledge'
import { listDatasources, type DatasourceItem } from '../../../api/admin/datasource'
import { listSnapshots, type SnapshotItem } from '../../../api/admin/metadata'
import { knowledgeStatusLabel, knowledgeStatusType } from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'

const route = useRoute()
const router = useRouter()
const docId = computed(() => route.params.id ? Number(route.params.id) : null)
const isNew = computed(() => !docId.value)
const adminContext = useAdminContextStore()

const loading = ref(false)
const saving = ref(false)
const datasources = ref<DatasourceItem[]>([])
const snapshots = ref<SnapshotItem[]>([])

const title = ref('')
const content = ref('')
const datasourceId = ref<number | undefined>(undefined)
const version = ref(0)
const status = ref('DRAFT')
const generateDialogVisible = ref(false)
const selectedSnapshotId = ref<number | undefined>(undefined)
const generating = ref(false)

function extractError(error: unknown, fallback: string): string {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const msg = (error as any).response?.data?.message
    if (typeof msg === 'string') return msg
  }
  return fallback
}

async function fetchDoc() {
  if (!docId.value) return
  loading.value = true
  try {
    const res = await getKnowledgeDoc(docId.value)
    const doc = res.data as KnowledgeDocItem
    title.value = doc.title
    content.value = doc.content || ''
    datasourceId.value = doc.datasourceId
    adminContext.selectDatasource(doc.datasourceId)
    adminContext.selectKnowledgeDoc(doc.id)
    version.value = doc.version
    status.value = doc.status
  } catch (e) {
    ElMessage.error(extractError(e, '加载文档失败'))
  } finally {
    loading.value = false
  }
}

async function fetchDatasources() {
  try {
    const res = await listDatasources({ page: 1, pageSize: 200 })
    datasources.value = res.data?.records ?? []
  } catch { /* ignore */ }
}

async function fetchSnapshots() {
  try {
    const res = await listSnapshots({ datasourceId: datasourceId.value || adminContext.datasourceId, page: 1, size: 50 })
    snapshots.value = res.data?.records ?? []
  } catch { /* ignore */ }
}

async function handleSave() {
  if (!title.value.trim()) {
    ElMessage.warning('请输入文档标题')
    return
  }
  saving.value = true
  try {
    if (isNew.value) {
      if (!datasourceId.value) {
        ElMessage.warning('请选择数据源')
        saving.value = false
        return
      }
      const res = await createKnowledgeDoc({
        datasourceId: datasourceId.value,
        title: title.value,
        content: content.value
      })
      ElMessage.success('文档创建成功')
      adminContext.selectDatasource(datasourceId.value)
      if (res.data?.id) adminContext.selectKnowledgeDoc(res.data.id)
      router.replace({ name: 'admin-knowledge-editor', params: { id: res.data?.id } })
    } else {
      await updateKnowledgeDoc(docId.value!, {
        title: title.value,
        content: content.value,
        version: version.value,
        changeSummary: '手动编辑保存'
      })
      ElMessage.success('保存成功')
      fetchDoc()
    }
  } catch (e) {
    ElMessage.error(extractError(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function handleSubmitReview() {
  if (!docId.value) return
  try {
    await ElMessageBox.confirm('确认提交审核？提交后将无法编辑，直到审核完成。', '提交审核')
    await submitReview(docId.value)
    ElMessage.success('已提交审核')
    fetchDoc()
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(extractError(e, '提交审核失败'))
  }
}

async function handlePublish() {
  if (!docId.value) return
  try {
    await ElMessageBox.confirm('确认发布此文档？发布后内容将向量化进入 RAG 知识库。', '发布文档')
    await publishDoc(docId.value)
    ElMessage.success('发布成功')
    fetchDoc()
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(extractError(e, '发布失败'))
  }
}

function openGenerateDialog() {
  generateDialogVisible.value = true
  selectedSnapshotId.value = adminContext.snapshotId
}

async function handleGenerate() {
  if (!docId.value || !selectedSnapshotId.value) {
    ElMessage.warning('请选择快照')
    return
  }
  generating.value = true
  try {
    const res = await generateDraft(docId.value, selectedSnapshotId.value)
    generateDialogVisible.value = false
    // 后端生成草稿时已写入新版本并递增乐观锁 version，必须重新拉取文档刷新本地 version，
    // 否则随后的“保存草稿”会携带过期 version 触发乐观锁冲突。
    await fetchDoc()
    adminContext.refresh()
    // fetchDoc 会用服务端最新内容覆盖 content，这里再确保展示本次生成的草稿内容
    content.value = res.data?.content || content.value
    ElMessage.success('AI 草稿已生成，请检查后保存')
  } catch (e) {
    ElMessage.error(extractError(e, 'AI 生成失败'))
  } finally {
    generating.value = false
  }
}

const canEdit = computed(() => ['DRAFT', 'APPROVED'].includes(status.value) || isNew.value)
const canSubmitReview = computed(() => status.value === 'DRAFT' && !isNew.value)
const canPublish = computed(() => status.value === 'APPROVED' && !isNew.value)

onMounted(async () => {
  await adminContext.initialize()
  datasourceId.value = adminContext.datasourceId
  selectedSnapshotId.value = adminContext.snapshotId
  fetchDatasources()
  fetchSnapshots()
  if (!isNew.value) fetchDoc()
})
</script>

<template>
  <main class="skills-editor post-login-page" v-loading="loading">
    <section class="page-actions">
      <div class="header-actions">
        <el-button @click="handleSave" :loading="saving" :disabled="!canEdit">
          <Save :size="16" style="margin-right: 6px" />保存草稿
        </el-button>
        <el-button v-if="!isNew" @click="openGenerateDialog" :disabled="!canEdit">
          <Sparkles :size="16" style="margin-right: 6px" />AI 生成
        </el-button>
        <el-button v-if="canSubmitReview" type="warning" @click="handleSubmitReview">
          <Send :size="16" style="margin-right: 6px" />提交审核
        </el-button>
        <el-button v-if="canPublish" type="primary" @click="handlePublish">
          <Upload :size="16" style="margin-right: 6px" />发布
        </el-button>
      </div>
    </section>

    <section class="editor-meta">
      <el-input v-model="title" placeholder="文档标题" :disabled="!canEdit" style="flex: 1" />
      <el-select v-model="datasourceId" placeholder="选择数据源" :disabled="!isNew" style="width: 200px">
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
      <el-tag v-if="!isNew" :type="knowledgeStatusType(status)" size="small">
        {{ knowledgeStatusLabel(status) }}
      </el-tag>
      <span v-if="!isNew" class="version-badge">版本 {{ version }}</span>
    </section>

    <section class="editor-body">
      <div class="editor-pane">
        <div class="pane-title">编辑</div>
        <el-input
          v-model="content"
          type="textarea"
          :rows="24"
          placeholder="在此编写 skills.md 内容（Markdown 格式）..."
          :disabled="!canEdit"
          resize="vertical"
        />
      </div>
      <div class="preview-pane">
        <div class="pane-title">预览</div>
        <div class="preview-content">
          <pre>{{ content || '（暂无内容）' }}</pre>
        </div>
      </div>
    </section>

    <el-dialog v-model="generateDialogVisible" title="AI 生成草稿" width="460px">
      <p style="margin: 0 0 12px; color: var(--do-muted); font-size: 13px;">
        选择一个元数据快照，AI 将基于快照内容生成 skills.md 草稿。
      </p>
      <el-select v-model="selectedSnapshotId" placeholder="选择快照" style="width: 100%">
        <el-option v-for="s in snapshots" :key="s.id" :value="s.id"
                   :label="`快照 #${s.id} - ${s.datasourceName}（版本 ${s.snapshotVersion}）`" />
      </el-select>
      <template #footer>
        <el-button @click="generateDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="generating" @click="handleGenerate">生成</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.skills-editor { display: grid; gap: 16px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.editor-meta { display: flex; gap: 12px; align-items: center; }
.version-badge { font-size: 12px; color: var(--do-muted); background: var(--do-primary-soft); padding: 2px 8px; border-radius: 4px; }
.editor-body { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; min-height: 500px; }
.editor-pane, .preview-pane {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.pane-title {
  padding: 10px 14px;
  font-size: 12px;
  font-weight: 600;
  color: var(--do-muted);
  border-bottom: 1px solid var(--do-line);
  text-transform: uppercase;
}
.editor-pane :deep(.el-textarea__inner) {
  border: none;
  border-radius: 0;
  flex: 1;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  line-height: 1.6;
  resize: none !important;
  min-height: 460px !important;
}
.preview-content {
  padding: 14px;
  flex: 1;
  overflow: auto;
  font-size: 13px;
  line-height: 1.7;
  color: var(--do-ink);
}
.preview-content pre {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-family: inherit;
}
</style>
