<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Tag, RefreshCw, Upload } from 'lucide-vue-next'
import {
  listPredefinedTags,
  getFieldTags,
  addFieldTag,
  batchAddFieldTags,
  removeFieldTag,
  autoTagByPattern,
  type PredefinedTag,
  type FieldTagVO
} from '../../../api/admin/field'
import { listSnapshotTables, listSnapshotTableColumns, type ColumnMetaItem } from '../../../api/admin/governance'
import { listSnapshots } from '../../../api/admin/metadata'

const loading = ref(false)
const predefinedTags = ref<PredefinedTag[]>([])
const columns = ref<ColumnMetaItem[]>([])
const selectedColumnIds = ref<number[]>([])
const currentColumnTags = ref<FieldTagVO[]>([])
const showTagDialog = ref(false)
const selectedTagCode = ref('')

const snapshots = ref<Array<{ id: number; snapshotVersion: number }>>([])
const tables = ref<Array<{ id: number; tableName: string; tableComment?: string }>>([])

const query = reactive({
  snapshotId: undefined as number | undefined,
  tableName: ''
})

async function fetchSnapshots() {
  const res = await listSnapshots({ page: 1, size: 50 })
  snapshots.value = res.data?.records ?? []
  if (snapshots.value.length && !query.snapshotId) {
    query.snapshotId = snapshots.value[0].id
  }
}

async function fetchTables() {
  if (!query.snapshotId) return
  const res = await listSnapshotTables(query.snapshotId)
  tables.value = res.data ?? []
}

async function fetchColumns() {
  if (!query.snapshotId || !query.tableName) return
  loading.value = true
  try {
    const res = await listSnapshotTableColumns(query.snapshotId, query.tableName)
    columns.value = res.data ?? []
  } finally {
    loading.value = false
  }
}

async function fetchPredefinedTags() {
  const res = await listPredefinedTags()
  predefinedTags.value = res.data ?? []
}

function handleSelectionChange(rows: ColumnMetaItem[]) {
  selectedColumnIds.value = rows.map(r => r.id)
}

function openBatchTagDialog() {
  if (selectedColumnIds.value.length === 0) {
    ElMessage.warning('请先选择字段')
    return
  }
  selectedTagCode.value = ''
  showTagDialog.value = true
}

async function confirmBatchTag() {
  if (!selectedTagCode.value) {
    ElMessage.warning('请选择标签')
    return
  }
  try {
    const res = await batchAddFieldTags({
      columnMetaIds: selectedColumnIds.value,
      tagCode: selectedTagCode.value
    })
    ElMessage.success(`批量打标成功，已标记 ${res.data?.tagged ?? 0} 个字段`)
    showTagDialog.value = false
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '打标失败')
  }
}

async function viewColumnTags(columnId: number) {
  const res = await getFieldTags(columnId)
  currentColumnTags.value = res.data ?? []
}

async function handleRemoveTag(tagId: number) {
  await removeFieldTag(tagId)
  ElMessage.success('标签已移除')
}

async function handleAutoTag() {
  if (!query.snapshotId) return
  try {
    const res = await autoTagByPattern(query.snapshotId)
    ElMessage.success(`自动打标完成：标记 ${res.data?.tagged ?? 0} 个，跳过 ${res.data?.skipped ?? 0} 个`)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '自动打标失败')
  }
}

onMounted(async () => {
  await fetchPredefinedTags()
  await fetchSnapshots()
  if (query.snapshotId) await fetchTables()
})
</script>

<template>
  <main class="field-tag-page post-login-page">
    <header class="page-header">
      <div>
        <p>字段治理</p>
        <h1>字段标签管理</h1>
        <span class="header-subtitle">为字段打上业务标签，标记推荐、废弃、敏感等属性</span>
      </div>
      <div class="header-actions">
        <el-button @click="handleAutoTag">
          <RefreshCw :size="16" style="margin-right: 4px" />自动打标
        </el-button>
        <el-button type="primary" @click="openBatchTagDialog">
          <Tag :size="16" style="margin-right: 4px" />批量打标
        </el-button>
      </div>
    </header>

    <section class="toolbar">
      <el-select v-model="query.snapshotId" placeholder="选择快照" @change="fetchTables" style="width: 200px">
        <el-option v-for="s in snapshots" :key="s.id" :label="`快照 v${s.snapshotVersion}`" :value="s.id" />
      </el-select>
      <el-select v-model="query.tableName" placeholder="选择表" @change="fetchColumns" style="width: 240px" filterable>
        <el-option v-for="t in tables" :key="t.tableName" :label="t.tableName" :value="t.tableName" />
      </el-select>
    </section>

    <section class="content-panel">
      <el-table :data="columns" v-loading="loading" @selection-change="handleSelectionChange" stripe>
        <el-table-column type="selection" width="50" />
        <el-table-column prop="columnName" label="字段名" width="200" />
        <el-table-column prop="dataType" label="数据类型" width="150" />
        <el-table-column prop="columnComment" label="注释" />
        <el-table-column prop="governanceStatus" label="治理状态" width="120" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="viewColumnTags(row.id)">查看标签</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!columns.length && !loading" description="请选择快照和表" />
    </section>

    <el-dialog v-model="showTagDialog" title="批量打标" width="400px">
      <p style="margin-bottom: 12px">已选择 {{ selectedColumnIds.length }} 个字段</p>
      <el-select v-model="selectedTagCode" placeholder="选择标签" style="width: 100%">
        <el-option v-for="tag in predefinedTags" :key="tag.tagCode" :label="`${tag.tagName}（${tag.tagCode}）`" :value="tag.tagCode" />
      </el-select>
      <template #footer>
        <el-button @click="showTagDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmBatchTag">确认打标</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.field-tag-page { padding: 24px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; }
.page-header h1 { margin: 4px 0; font-size: 22px; color: var(--do-ink); }
.page-header p { margin: 0; font-size: 12px; color: var(--do-muted); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }
.header-actions { display: flex; gap: 8px; }
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; }
</style>
