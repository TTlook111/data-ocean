<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { FileText, Plus, RefreshCw, Eye, Pencil, Sparkles } from 'lucide-vue-next'
import {
  listKnowledgeDocs,
  generateFromSnapshot,
  type KnowledgeDocItem,
  type KnowledgeDocQuery
} from '../../../api/admin/knowledge'
import { listDatasources, type DatasourceItem } from '../../../api/admin/datasource'
import { listSnapshots, type SnapshotItem } from '../../../api/admin/metadata'
import { knowledgeStatusLabel, knowledgeStatusType } from '../../../utils/enumLabels'

const router = useRouter()
const loading = ref(false)
const docs = ref<KnowledgeDocItem[]>([])
const total = ref(0)
const datasources = ref<DatasourceItem[]>([])

const query = reactive<KnowledgeDocQuery>({
  datasourceId: undefined,
  status: '',
  page: 1,
  pageSize: 20
})

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '草稿', value: 'DRAFT' },
  { label: '待审核', value: 'PENDING_REVIEW' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '已废弃', value: 'DEPRECATED' }
]

// 各状态的全量计数（独立于当前分页，避免用单页数据推断全局）
const stats = ref({ total: 0, published: 0, pending: 0, draft: 0 })

// AI 一键生成对话框状态
const generateDialogVisible = ref(false)
const generateLoading = ref(false)
const generateForm = reactive({ datasourceId: undefined as number | undefined, snapshotId: undefined as number | undefined })
const snapshots = ref<SnapshotItem[]>([])
const generatedDocs = ref<Array<{ id: number; title: string; tableNames: string[] }>>([])

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
    const params: any = { page: query.page, pageSize: query.pageSize }
    if (query.datasourceId) params.datasourceId = query.datasourceId
    if (query.status) params.status = query.status
    const res = await listKnowledgeDocs(params)
    docs.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch (e) {
    ElMessage.error(extractError(e, '加载文档列表失败'))
  } finally {
    loading.value = false
  }
}

// 获取各状态的全量计数（分别按状态查 total，不依赖当前分页数据）
async function fetchStats() {
  try {
    const [allRes, pubRes, pendRes, draftRes] = await Promise.all([
      listKnowledgeDocs({ page: 1, pageSize: 1 }),
      listKnowledgeDocs({ page: 1, pageSize: 1, status: 'PUBLISHED' }),
      listKnowledgeDocs({ page: 1, pageSize: 1, status: 'PENDING_REVIEW' }),
      listKnowledgeDocs({ page: 1, pageSize: 1, status: 'DRAFT' }),
    ])
    stats.value = {
      total: allRes.data?.total ?? 0,
      published: pubRes.data?.total ?? 0,
      pending: pendRes.data?.total ?? 0,
      draft: draftRes.data?.total ?? 0,
    }
  } catch {
    // 统计失败不阻断主列表展示
  }
}

async function fetchDatasources() {
  try {
    const res = await listDatasources({ page: 1, pageSize: 200 })
    datasources.value = res.data?.records ?? []
  } catch { /* ignore */ }
}

// 加载快照列表（按数据源筛选）
async function fetchSnapshots(datasourceId: number) {
  try {
    const res = await listSnapshots({ datasourceId, page: 1, size: 100 })
    snapshots.value = res.data?.records ?? []
  } catch { snapshots.value = [] }
}

// 打开 AI 一键生成对话框
function openGenerateDialog() {
  generateForm.datasourceId = undefined
  generateForm.snapshotId = undefined
  snapshots.value = []
  generatedDocs.value = []
  generateDialogVisible.value = true
}

// 数据源变更时加载快照
function onDatasourceChange(dsId: number | undefined) {
  generateForm.snapshotId = undefined
  if (dsId) {
    fetchSnapshots(dsId)
  } else {
    snapshots.value = []
  }
}

// 执行 AI 一键生成
async function handleGenerate() {
  if (!generateForm.datasourceId || !generateForm.snapshotId) {
    ElMessage.warning('请选择数据源和快照')
    return
  }
  generateLoading.value = true
  try {
    const res = await generateFromSnapshot(generateForm.datasourceId, generateForm.snapshotId)
    generatedDocs.value = res.data ?? []
    ElMessage.success(`AI 已生成 ${generatedDocs.value.length} 份 skills.md 文档`)
    // 刷新列表和统计
    fetchDocs()
    fetchStats()
  } catch (e) {
    ElMessage.error(extractError(e, 'AI 生成失败'))
  } finally {
    generateLoading.value = false
  }
}

// 跳转到文档编辑页
function goEditDoc(id: number) {
  generateDialogVisible.value = false
  router.push({ name: 'admin-knowledge-editor', params: { id } })
}

function goCreate() {
  router.push({ name: 'admin-knowledge-editor' })
}

function goEdit(id: number) {
  router.push({ name: 'admin-knowledge-editor', params: { id } })
}

function goVersions(id: number) {
  router.push({ name: 'admin-knowledge-versions', params: { id } })
}

onMounted(() => {
  fetchDocs()
  fetchDatasources()
  fetchStats()
})
</script>

<template>
  <main class="knowledge-dashboard post-login-page">
    <section class="page-actions">
      <div class="header-actions">
        <el-button @click="goCreate">
          <Plus :size="16" style="margin-right: 6px" />手动新建
        </el-button>
        <el-button type="primary" @click="openGenerateDialog">
          <Sparkles :size="16" style="margin-right: 6px" />AI 一键生成
        </el-button>
      </div>
    </section>

    <section class="stat-cards">
      <div class="stat-card">
        <span class="stat-value">{{ stats.total }}</span>
        <span class="stat-label">总文档数</span>
      </div>
      <div class="stat-card stat-published">
        <span class="stat-value">{{ stats.published }}</span>
        <span class="stat-label">已发布</span>
      </div>
      <div class="stat-card stat-pending">
        <span class="stat-value">{{ stats.pending }}</span>
        <span class="stat-label">待审核</span>
      </div>
      <div class="stat-card stat-draft">
        <span class="stat-value">{{ stats.draft }}</span>
        <span class="stat-label">草稿</span>
      </div>
    </section>

    <section class="toolbar">
      <el-select v-model="query.datasourceId" placeholder="全部数据源" clearable
                 style="width: 200px" @change="fetchDocs">
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
      <el-select v-model="query.status" style="width: 140px" @change="fetchDocs">
        <el-option v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-button :icon="RefreshCw" @click="fetchDocs" />
    </section>

    <section class="table-shell">
      <el-table :data="docs" v-loading="loading" stripe>
        <template #empty>
          <div class="do-empty-state knowledge-empty">
            <span class="do-empty-icon"><FileText :size="22" /></span>
            <h3>还没有知识文档</h3>
            <p>点击「AI 一键生成」让 AI 自动分析业务域并生成 skills.md 文档。</p>
            <el-button type="primary" @click="openGenerateDialog">
              <Sparkles :size="15" style="margin-right: 6px" />AI 一键生成
            </el-button>
          </div>
        </template>
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <FileText :size="14" style="margin-right: 6px; vertical-align: middle; color: var(--do-primary)" />
            {{ row.title }}
          </template>
        </el-table-column>
        <el-table-column prop="datasourceId" label="数据源" width="150">
          <template #default="{ row }">
            {{ datasources.find(d => d.id === row.datasourceId)?.name || row.datasourceId }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="knowledgeStatusType(row.status)" size="small">{{ knowledgeStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="currentVersion" label="版本" width="80">
          <template #default="{ row }">版本 {{ row.currentVersion }}</template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="170" />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" title="编辑文档" aria-label="编辑文档" @click="goEdit(row.id)">
              <Pencil :size="14" />
            </el-button>
            <el-button link size="small" title="查看版本" aria-label="查看版本" @click="goVersions(row.id)">
              <Eye :size="14" />
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-pagination class="pager" background layout="total, prev, pager, next"
                   :total="total" :page-size="query.pageSize"
                   v-model:current-page="query.page" @current-change="fetchDocs" />

    <!-- AI 一键生成对话框 -->
    <el-dialog v-model="generateDialogVisible" title="AI 一键生成 skills.md" width="560px" :close-on-click-modal="!generateLoading">
      <!-- 未生成时：选择数据源和快照 -->
      <div v-if="generatedDocs.length === 0" class="generate-form">
        <p class="generate-hint">AI 将自动分析表结构，识别业务域，为每个域生成一份独立的 skills.md 文档。</p>
        <el-form label-width="80px">
          <el-form-item label="数据源">
            <el-select v-model="generateForm.datasourceId" placeholder="选择数据源" style="width: 100%"
                       @change="onDatasourceChange">
              <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="快照">
            <el-select v-model="generateForm.snapshotId" placeholder="选择元数据快照" style="width: 100%"
                       :disabled="!generateForm.datasourceId">
              <el-option v-for="s in snapshots" :key="s.id"
                         :label="`v${s.snapshotVersion} — ${s.createdAt}`" :value="s.id" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>

      <!-- 生成完成：显示结果 -->
      <div v-else class="generate-result">
        <el-alert type="success" :closable="false" show-icon
                  :title="`AI 识别出 ${generatedDocs.length} 个业务域，已生成 ${generatedDocs.length} 份 skills.md 文档`" />
        <div class="generated-doc-list">
          <div v-for="doc in generatedDocs" :key="doc.id" class="generated-doc-item" @click="goEditDoc(doc.id)">
            <FileText :size="16" style="color: var(--do-primary)" />
            <div class="generated-doc-info">
              <strong>{{ doc.title }}</strong>
              <small>覆盖 {{ doc.tableNames?.length ?? 0 }} 张表</small>
            </div>
            <Pencil :size="14" style="color: var(--do-muted)" />
          </div>
        </div>
        <p class="generate-footer">点击文档可跳转到编辑页进行审核修改</p>
      </div>

      <template #footer>
        <el-button @click="generateDialogVisible = false">
          {{ generatedDocs.length > 0 ? '关闭' : '取消' }}
        </el-button>
        <el-button v-if="generatedDocs.length === 0" type="primary" :loading="generateLoading"
                   @click="handleGenerate">
          <Sparkles :size="16" style="margin-right: 6px" />
          {{ generateLoading ? 'AI 分析中...' : '开始生成' }}
        </el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.knowledge-dashboard { display: grid; gap: 16px; }
.header-actions { display: flex; gap: 8px; }
.stat-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.stat-card {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  box-shadow: var(--do-shadow);
}
.stat-value { font-size: 26px; font-weight: 600; color: var(--do-ink); }
.stat-label { font-size: 12px; color: var(--do-muted); }
.stat-published .stat-value { color: var(--do-primary); }
.stat-pending .stat-value { color: #e6a23c; }
.stat-draft .stat-value { color: #909399; }
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.table-shell {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  overflow: hidden;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}
.knowledge-empty {
  min-height: 260px;
}
.pager { margin-top: 16px; justify-content: flex-end; }

/* AI 一键生成对话框 */
.generate-form { padding: 8px 0; }
.generate-hint { font-size: 13px; color: var(--do-muted); margin-bottom: 16px; }
.generate-result { padding: 8px 0; }
.generated-doc-list { margin-top: 16px; display: grid; gap: 8px; }
.generated-doc-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.2s;
}
.generated-doc-item:hover { border-color: var(--do-primary); }
.generated-doc-info { flex: 1; min-width: 0; }
.generated-doc-info strong { display: block; font-size: 14px; color: var(--do-ink); }
.generated-doc-info small { font-size: 12px; color: var(--do-muted); }
.generate-footer { font-size: 12px; color: var(--do-muted); margin-top: 12px; text-align: center; }
</style>
