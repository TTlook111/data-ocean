<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { GitCompare } from 'lucide-vue-next'
import { listDatasources, type DatasourceItem } from '../../../api/admin/datasource'
import {
  listVersionHistory,
  compareSnapshots,
  type VersionHistoryItem
} from '../../../api/admin/versioning'
import type { SchemaDiffResult } from '../../../api/admin/metadata'

const loading = ref(false)
const datasources = ref<DatasourceItem[]>([])
const selectedDatasourceId = ref<number | undefined>()
const history = ref<VersionHistoryItem[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20 })

const compareDialogVisible = ref(false)
const compareLoading = ref(false)
const diffResult = ref<SchemaDiffResult | null>(null)
const compareOldId = ref<number | undefined>()
const compareNewId = ref<number | undefined>()

const statusLabels: Record<string, string> = {
  DRAFT: '草稿', CHECKING: '校验中', ISSUE_FOUND: '存在问题',
  APPROVED: '已审核', PUBLISHED: '已发布', EXPIRED: '已过期'
}

const statusTypes: Record<string, string> = {
  DRAFT: 'info', CHECKING: 'warning', ISSUE_FOUND: 'danger',
  APPROVED: '', PUBLISHED: 'success', EXPIRED: 'info'
}

async function fetchDatasources() {
  const res = await listDatasources({ page: 1, pageSize: 200 })
  datasources.value = res.data?.records ?? []
}

async function fetchHistory() {
  if (!selectedDatasourceId.value) return
  loading.value = true
  try {
    const res = await listVersionHistory(selectedDatasourceId.value, query)
    history.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } finally {
    loading.value = false
  }
}

async function openCompare() {
  if (!compareOldId.value || !compareNewId.value) {
    ElMessage.warning('请选择两个版本进行对比')
    return
  }
  compareDialogVisible.value = true
  compareLoading.value = true
  try {
    const res = await compareSnapshots(compareOldId.value, compareNewId.value)
    diffResult.value = res.data ?? null
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '对比失败')
  } finally {
    compareLoading.value = false
  }
}

function onDatasourceChange() {
  query.page = 1
  compareOldId.value = undefined
  compareNewId.value = undefined
  fetchHistory()
}

onMounted(() => {
  fetchDatasources()
})
</script>

<template>
  <main class="version-history-page post-login-page">
    <header class="page-header">
      <div>
        <p>元数据版本管理</p>
        <h1>版本历史</h1>
        <span class="header-subtitle">查看数据源元数据快照演变历程，对比不同版本差异</span>
      </div>
    </header>

    <section class="toolbar">
      <el-select v-model="selectedDatasourceId" placeholder="选择数据源" clearable
                 @change="onDatasourceChange" style="width: 280px">
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>

      <div class="compare-bar" v-if="history.length >= 2">
        <el-select v-model="compareOldId" placeholder="旧版本" style="width: 150px" size="small">
          <el-option v-for="h in history" :key="h.snapshotId" :label="'v' + h.snapshotVersion" :value="h.snapshotId" />
        </el-select>
        <span class="compare-arrow">vs</span>
        <el-select v-model="compareNewId" placeholder="新版本" style="width: 150px" size="small">
          <el-option v-for="h in history" :key="h.snapshotId" :label="'v' + h.snapshotVersion" :value="h.snapshotId" />
        </el-select>
        <el-button size="small" type="primary" @click="openCompare">
          <GitCompare :size="14" /> 对比
        </el-button>
      </div>
    </section>

    <section class="timeline-section" v-loading="loading">
      <el-empty v-if="!selectedDatasourceId" description="请选择数据源" />
      <el-empty v-else-if="!loading && history.length === 0" description="暂无版本记录" />
      <div v-else class="timeline">
        <div v-for="item in history" :key="item.snapshotId" class="timeline-item">
          <div class="timeline-dot" :class="'dot-' + item.status.toLowerCase().replace('_','-')"></div>
          <div class="timeline-content">
            <div class="timeline-header">
              <strong>v{{ item.snapshotVersion }}</strong>
              <el-tag :type="statusTypes[item.status] || 'info'" size="small">
                {{ statusLabels[item.status] || item.status }}
              </el-tag>
              <span class="timeline-time">{{ item.createdAt }}</span>
            </div>
            <div class="timeline-meta">
              <span>{{ item.tableCount }} 表 / {{ item.columnCount }} 字段</span>
              <span v-if="item.qualityScore != null">质量分: {{ item.qualityScore }}</span>
              <span v-if="item.publishedAt">发布于: {{ item.publishedAt }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <el-pagination v-if="total > query.size" class="pager"
      v-model:current-page="query.page" :page-size="query.size" :total="total"
      layout="total, prev, pager, next" @current-change="fetchHistory" />

    <el-dialog v-model="compareDialogVisible" title="版本对比" width="750px">
      <div v-loading="compareLoading">
        <el-empty v-if="!diffResult" description="无差异数据" />
        <div v-else class="diff-result">
          <div v-if="diffResult.addedTables?.length" class="diff-section">
            <h4>新增表 ({{ diffResult.addedTables.length }})</h4>
            <el-tag v-for="t in diffResult.addedTables" :key="t" type="success" size="small" class="diff-tag">{{ t }}</el-tag>
          </div>
          <div v-if="diffResult.removedTables?.length" class="diff-section">
            <h4>删除表 ({{ diffResult.removedTables.length }})</h4>
            <el-tag v-for="t in diffResult.removedTables" :key="t" type="danger" size="small" class="diff-tag">{{ t }}</el-tag>
          </div>
          <div v-if="diffResult.addedColumns?.length" class="diff-section">
            <h4>新增字段 ({{ diffResult.addedColumns.length }})</h4>
            <div v-for="c in diffResult.addedColumns" :key="c.tableName + c.columnName" class="diff-item">
              {{ c.tableName }}.{{ c.columnName }} ({{ c.newType }})
            </div>
          </div>
          <div v-if="diffResult.removedColumns?.length" class="diff-section">
            <h4>删除字段 ({{ diffResult.removedColumns.length }})</h4>
            <div v-for="c in diffResult.removedColumns" :key="c.tableName + c.columnName" class="diff-item">
              {{ c.tableName }}.{{ c.columnName }} ({{ c.oldType }})
            </div>
          </div>
          <div v-if="diffResult.modifiedColumns?.length" class="diff-section">
            <h4>变更字段 ({{ diffResult.modifiedColumns.length }})</h4>
            <div v-for="c in diffResult.modifiedColumns" :key="c.tableName + c.columnName" class="diff-item">
              {{ c.tableName }}.{{ c.columnName }}: {{ c.oldType }} → {{ c.newType }}
            </div>
          </div>
          <el-empty v-if="!diffResult.addedTables?.length && !diffResult.removedTables?.length && !diffResult.addedColumns?.length && !diffResult.removedColumns?.length && !diffResult.modifiedColumns?.length" description="两个版本无差异" />
        </div>
      </div>
    </el-dialog>
  </main>
</template>

<style scoped>
.version-history-page { max-width: 1100px; }
.toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 20px; flex-wrap: wrap; }
.compare-bar { display: flex; align-items: center; gap: 8px; }
.compare-arrow { color: var(--do-muted); font-size: 13px; }
.timeline { position: relative; padding-left: 24px; }
.timeline::before {
  content: ''; position: absolute; left: 7px; top: 0; bottom: 0;
  width: 2px; background: var(--do-line);
}
.timeline-item { position: relative; margin-bottom: 20px; }
.timeline-dot {
  position: absolute; left: -20px; top: 4px;
  width: 12px; height: 12px; border-radius: 50%; border: 2px solid #fff;
}
.dot-draft { background: #909399; }
.dot-checking { background: #e6a23c; }
.dot-issue-found { background: #f56c6c; }
.dot-approved { background: #409eff; }
.dot-published { background: #67c23a; }
.dot-expired { background: #c0c4cc; }
.timeline-content {
  padding: 12px 16px; background: var(--do-surface);
  border: 1px solid var(--do-line); border-radius: 8px;
}
.timeline-header { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.timeline-time { font-size: 12px; color: var(--do-muted); margin-left: auto; }
.timeline-meta { display: flex; gap: 16px; font-size: 13px; color: var(--do-muted); }
.diff-section { margin-bottom: 16px; }
.diff-section h4 { margin-bottom: 8px; font-size: 14px; }
.diff-tag { margin: 2px 4px; }
.diff-item { font-size: 13px; padding: 4px 0; color: var(--do-ink); }
.pager { margin-top: 16px; justify-content: flex-end; }
</style>
