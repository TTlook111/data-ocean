<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from 'lucide-vue-next'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import {
  analyzeImpact,
  queryColumnLineage,
  queryTableLineage,
  type ImpactAnalysisVO,
  type LineageColumnVO,
  type LineageTableVO,
} from '../../../api/admin/audit'

const tableName = ref('')
const columnName = ref('')
const tableLineage = ref<LineageTableVO[]>([])
const columnLineage = ref<LineageColumnVO[]>([])
const impact = ref<ImpactAnalysisVO | null>(null)
const loading = ref(false)
const searched = ref(false)
const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)

async function searchTableLineage() {
  const table = tableName.value.trim()
  if (!table) {
    ElMessage.warning('请输入表名')
    return
  }
  loading.value = true
  searched.value = true
  columnLineage.value = []
  impact.value = null
  try {
    const res = await queryTableLineage(table)
    tableLineage.value = res.data ?? []
  } catch {
    ElMessage.error('表血缘查询失败')
  } finally {
    loading.value = false
  }
}

async function searchColumnLineage() {
  const table = tableName.value.trim()
  const column = columnName.value.trim()
  if (!table || !column) {
    ElMessage.warning('请输入表名和字段名')
    return
  }
  loading.value = true
  searched.value = true
  tableLineage.value = []
  try {
    const [colRes, impactRes] = await Promise.all([
      queryColumnLineage(table, column),
      analyzeImpact(table, column),
    ])
    columnLineage.value = colRes.data ?? []
    impact.value = impactRes.data ?? null
  } catch {
    ElMessage.error('字段血缘查询失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  if (columnName.value.trim()) {
    searchColumnLineage()
    return
  }
  searchTableLineage()
}

onMounted(() => {
  withContext(() => {
    reveal('.page-header, .content-panel, .impact-card, .toolbar', { y: 14, stagger: 0.06 })
  })
})
</script>

<template>
  <main ref="pageRef" class="lineage-page post-login-page" v-loading="loading">
    <header class="page-header">
      <div>
        <p>审计管理</p>
        <h1>血缘查看</h1>
        <span class="header-subtitle">查看表级和字段级引用关系，分析字段变更影响范围</span>
      </div>
    </header>

    <section class="toolbar">
      <el-input v-model="tableName" placeholder="表名" clearable style="width: 220px" @keyup.enter="handleSearch" />
      <el-input v-model="columnName" placeholder="字段名（选填）" clearable style="width: 220px" @keyup.enter="handleSearch" />
      <el-button type="primary" :icon="Search" @click="handleSearch">查询血缘</el-button>
    </section>

    <section v-if="impact" class="impact-card">
      <strong>变更影响分析：</strong>
      <span>{{ impact.tableName }}.{{ impact.columnName }} 被 {{ impact.dependentQueryCount }} 条查询引用</span>
      <span v-if="impact.recentQueryTaskIds?.length" class="recent-tasks">
        最近任务：{{ impact.recentQueryTaskIds.join(', ') }}
      </span>
    </section>

    <section v-if="tableLineage.length" class="content-panel">
      <h3>表级血缘</h3>
      <el-table :data="tableLineage" stripe size="small">
        <el-table-column prop="sourceTable" label="表名" width="180" />
        <el-table-column prop="relationType" label="关系" width="100" />
        <el-table-column prop="queryTaskId" label="查询ID" width="100" />
        <el-table-column prop="question" label="查询问题" min-width="260" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="时间" width="170" />
      </el-table>
    </section>

    <section v-if="columnLineage.length" class="content-panel">
      <h3>字段级血缘</h3>
      <el-table :data="columnLineage" stripe size="small">
        <el-table-column prop="sourceTable" label="表名" width="150" />
        <el-table-column prop="sourceColumn" label="字段" width="150" />
        <el-table-column prop="expression" label="表达式" min-width="180" show-overflow-tooltip />
        <el-table-column prop="aliasName" label="别名" width="120" />
        <el-table-column prop="queryTaskId" label="查询ID" width="100" />
        <el-table-column prop="question" label="查询问题" min-width="260" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="时间" width="170" />
      </el-table>
    </section>

    <el-empty
      v-if="!tableLineage.length && !columnLineage.length && !loading"
      :description="searched ? '暂无血缘记录' : '输入表名或字段名查询血缘关系'"
    />
  </main>
</template>

<style scoped>
.lineage-page {
  display: grid;
  gap: 16px;
  padding: 24px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.page-header h1 {
  margin: 4px 0;
  font-size: 22px;
  color: var(--do-ink);
}

.page-header p,
.header-subtitle,
.recent-tasks {
  color: var(--do-muted);
}

.page-header p {
  margin: 0;
  font-size: 12px;
}

.header-subtitle,
.recent-tasks {
  font-size: 13px;
}

.toolbar,
.impact-card {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.impact-card {
  padding: 12px 16px;
  border: 1px solid var(--do-primary, #4d8fdc);
  border-radius: 8px;
  background: var(--do-primary-soft, #eef8ff);
  font-size: 14px;
}

.content-panel {
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
}

.content-panel h3 {
  margin: 0 0 12px;
  font-size: 15px;
  color: var(--do-ink);
}
</style>
