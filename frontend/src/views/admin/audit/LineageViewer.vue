<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from 'lucide-vue-next'
import { queryTableLineage, queryColumnLineage, analyzeImpact, type LineageTableVO, type LineageColumnVO, type ImpactAnalysisVO } from '../../../api/admin/audit'

const tableName = ref('')
const columnName = ref('')
const tableLineage = ref<LineageTableVO[]>([])
const columnLineage = ref<LineageColumnVO[]>([])
const impact = ref<ImpactAnalysisVO | null>(null)
const loading = ref(false)

async function searchTableLineage() {
  if (!tableName.value) { ElMessage.warning('请输入表名'); return }
  loading.value = true
  try {
    const res = await queryTableLineage(tableName.value)
    tableLineage.value = res.data ?? []
    columnLineage.value = []
    impact.value = null
  } finally { loading.value = false }
}

async function searchColumnLineage() {
  if (!tableName.value || !columnName.value) { ElMessage.warning('请输入表名和字段名'); return }
  loading.value = true
  try {
    const [colRes, impactRes] = await Promise.all([
      queryColumnLineage(tableName.value, columnName.value),
      analyzeImpact(tableName.value, columnName.value)
    ])
    columnLineage.value = colRes.data ?? []
    impact.value = impactRes.data ?? null
  } finally { loading.value = false }
}
</script>

<template>
  <main class="lineage-page post-login-page">
    <header class="page-header">
      <div>
        <p>审计管理</p>
        <h1>血缘查看</h1>
        <span class="header-subtitle">查看表级和字段级依赖关系，分析变更影响范围</span>
      </div>
    </header>

    <section class="toolbar">
      <el-input v-model="tableName" placeholder="表名" style="width: 200px" />
      <el-input v-model="columnName" placeholder="字段名（选填）" style="width: 200px" />
      <el-button type="primary" @click="columnName ? searchColumnLineage() : searchTableLineage()">
        <Search :size="16" style="margin-right:4px" />查询血缘
      </el-button>
    </section>

    <section v-if="impact" class="impact-card">
      <strong>变更影响分析：</strong>
      <span>{{ impact.tableName }}.{{ impact.columnName }} 被 {{ impact.dependentQueryCount }} 条查询引用</span>
    </section>

    <section class="content-panel" v-if="tableLineage.length">
      <h3 style="margin: 0 0 12px">表级血缘</h3>
      <el-table :data="tableLineage" v-loading="loading" stripe size="small">
        <el-table-column prop="sourceTable" label="表名" width="200" />
        <el-table-column prop="relationType" label="关系" width="120" />
        <el-table-column prop="queryTaskId" label="查询ID" width="100" />
        <el-table-column prop="createdAt" label="时间" width="170" />
      </el-table>
    </section>

    <section class="content-panel" v-if="columnLineage.length" style="margin-top: 16px">
      <h3 style="margin: 0 0 12px">字段级血缘</h3>
      <el-table :data="columnLineage" v-loading="loading" stripe size="small">
        <el-table-column prop="sourceTable" label="表名" width="150" />
        <el-table-column prop="sourceColumn" label="字段" width="150" />
        <el-table-column prop="expression" label="表达式" min-width="200" />
        <el-table-column prop="aliasName" label="别名" width="120" />
        <el-table-column prop="createdAt" label="时间" width="170" />
      </el-table>
    </section>

    <el-empty v-if="!tableLineage.length && !columnLineage.length && !loading" description="输入表名或字段名查询血缘关系" />
  </main>
</template>

<style scoped>
.lineage-page { padding: 24px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; }
.page-header h1 { margin: 4px 0; font-size: 22px; color: var(--do-ink); }
.page-header p { margin: 0; font-size: 12px; color: var(--do-muted); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
.impact-card { padding: 12px 16px; border-radius: 8px; background: var(--do-primary-soft, #eef8ff); border: 1px solid var(--do-primary, #4d8fdc); margin-bottom: 16px; font-size: 14px; }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; }
</style>
