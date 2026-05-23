<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { FileText, Plus, RefreshCw, Eye, Pencil } from 'lucide-vue-next'
import {
  listKnowledgeDocs,
  type KnowledgeDocItem,
  type KnowledgeDocQuery
} from '../../../api/admin/knowledge'
import { listDatasources, type DatasourceItem } from '../../../api/admin/datasource'
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

const stats = computed(() => {
  const all = docs.value
  return {
    total: total.value,
    published: all.filter(d => d.status === 'PUBLISHED').length,
    pending: all.filter(d => d.status === 'PENDING_REVIEW').length,
    draft: all.filter(d => d.status === 'DRAFT').length
  }
})

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

async function fetchDatasources() {
  try {
    const res = await listDatasources({ page: 1, pageSize: 200 })
    datasources.value = res.data?.records ?? []
  } catch { /* ignore */ }
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
})
</script>

<template>
  <main class="knowledge-dashboard post-login-page">
    <header class="page-header">
      <div>
        <p>知识库管理</p>
        <h1>知识库总览</h1>
        <span class="header-subtitle">管理 skills.md 文档的生命周期</span>
      </div>
      <el-button type="primary" @click="goCreate">
        <Plus :size="16" style="margin-right: 6px" />新建文档
      </el-button>
    </header>

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
            <el-button link type="primary" size="small" @click="goEdit(row.id)">
              <Pencil :size="14" />
            </el-button>
            <el-button link size="small" @click="goVersions(row.id)">
              <Eye :size="14" />
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-pagination class="pager" background layout="total, prev, pager, next"
                   :total="total" :page-size="query.pageSize"
                   v-model:current-page="query.page" @current-change="fetchDocs" />
  </main>
</template>

<style scoped>
.knowledge-dashboard { display: grid; gap: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; }
.page-header p { font-size: 12px; color: var(--do-muted); margin: 0 0 4px; }
.page-header h1 { font-size: 22px; margin: 0; color: var(--do-ink); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }
.stat-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.stat-card {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stat-value { font-size: 26px; font-weight: 600; color: var(--do-ink); }
.stat-label { font-size: 12px; color: var(--do-muted); }
.stat-published .stat-value { color: var(--do-primary); }
.stat-pending .stat-value { color: #e6a23c; }
.stat-draft .stat-value { color: #909399; }
.toolbar { display: flex; gap: 12px; }
.table-shell { border: 1px solid var(--do-line); border-radius: 8px; overflow: hidden; background: var(--do-surface); }
.pager { margin-top: 16px; justify-content: flex-end; }
</style>
