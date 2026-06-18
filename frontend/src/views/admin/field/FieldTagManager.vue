<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Tag, RefreshCw } from 'lucide-vue-next'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import {
  listPredefinedTags,
  getFieldTags,
  batchAddFieldTags,
  autoTagByPattern,
  type PredefinedTag,
  type FieldTagVO
} from '../../../api/admin/field'
import { listSnapshotTables, listSnapshotTableColumns, type ColumnMetaItem } from '../../../api/admin/governance'
import { listSnapshots, type SnapshotItem } from '../../../api/admin/metadata'
import { useAdminContextStore } from '../../../stores/adminContext'

const loading = ref(false)
const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)
const adminContext = useAdminContextStore()
const predefinedTags = ref<PredefinedTag[]>([])
const columns = ref<ColumnMetaItem[]>([])
const selectedColumnIds = ref<number[]>([])
const currentColumnTags = ref<FieldTagVO[]>([])
const showTagDialog = ref(false)
const showColumnTagsDialog = ref(false)
const currentColumnName = ref('')
const selectedTagCode = ref('')

const snapshots = ref<SnapshotItem[]>([])
const tables = ref<Array<{ id: number; tableName: string; tableComment?: string }>>([])

const query = reactive({
  snapshotId: undefined as number | undefined,
  tableName: ''
})

async function fetchSnapshots() {
  const res = await listSnapshots({ datasourceId: adminContext.datasourceId, page: 1, size: 50 })
  snapshots.value = res.data?.records ?? []
  if (adminContext.snapshotId && snapshots.value.some((item) => item.id === adminContext.snapshotId)) {
    query.snapshotId = adminContext.snapshotId
  } else {
    query.snapshotId = snapshots.value[0]?.id
  }
  if (query.snapshotId) {
    adminContext.selectSnapshot(query.snapshotId)
  }
}

async function fetchTables() {
  if (!query.snapshotId) {
    tables.value = []
    columns.value = []
    query.tableName = ''
    return
  }
  const res = await listSnapshotTables(query.snapshotId)
  tables.value = res.data ?? []
  query.tableName = ''
  columns.value = []
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

async function handleSnapshotChange(id?: number) {
  adminContext.selectSnapshot(id)
  await fetchTables()
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

async function viewColumnTags(row: ColumnMetaItem) {
  currentColumnName.value = row.columnName
  const res = await getFieldTags(row.id)
  currentColumnTags.value = res.data ?? []
  showColumnTagsDialog.value = true
}

async function handleAutoTag() {
  if (!query.snapshotId) return
  const snapshot = snapshots.value.find(s => s.id === query.snapshotId)
  if (!snapshot?.datasourceId) {
    ElMessage.warning('当前快照缺少数据源信息，无法自动打标')
    return
  }
  try {
    const res = await autoTagByPattern(snapshot.datasourceId)
    ElMessage.success(`自动打标完成：标记 ${res.data?.tagged ?? 0} 个，跳过 ${res.data?.skipped ?? 0} 个`)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '自动打标失败')
  }
}

onMounted(async () => {
  withContext(() => {
    reveal('.toolbar, .content-panel', { y: 14, stagger: 0.06 })
  })
  await fetchPredefinedTags()
  await adminContext.initialize()
  await fetchSnapshots()
  if (query.snapshotId) await fetchTables()
})

watch(
  () => adminContext.snapshotId,
  async (snapshotId) => {
    if (!snapshotId || query.snapshotId === snapshotId) return
    query.snapshotId = snapshotId
    await fetchTables()
  },
)

watch(
  () => adminContext.datasourceId,
  async () => {
    await fetchSnapshots()
    await fetchTables()
  },
)
</script>

<template>
  <main ref="pageRef" class="field-tag-page post-login-page">
    <section class="page-actions">
      <div class="header-actions">
        <el-button @click="handleAutoTag">
          <RefreshCw :size="16" style="margin-right: 4px" />自动打标
        </el-button>
        <el-button type="primary" @click="openBatchTagDialog">
          <Tag :size="16" style="margin-right: 4px" />批量打标
        </el-button>
      </div>
    </section>

    <section class="toolbar">
      <el-select v-model="query.snapshotId" placeholder="选择快照" @change="handleSnapshotChange" style="width: 200px">
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
            <el-button link type="primary" size="small" @click="viewColumnTags(row)">查看标签</el-button>
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

    <el-dialog v-model="showColumnTagsDialog" :title="`${currentColumnName} 标签`" width="520px">
      <el-table v-if="currentColumnTags.length" :data="currentColumnTags" size="small">
        <el-table-column prop="tagName" label="标签" min-width="140" />
        <el-table-column prop="tagCode" label="编码" min-width="120" />
        <el-table-column prop="source" label="来源" width="110" />
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
      </el-table>
      <el-empty v-else description="该字段暂无标签" />
      <template #footer>
        <el-button type="primary" @click="showColumnTagsDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.field-tag-page { padding: 24px; }
.header-actions { display: flex; gap: 8px; }
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; }
</style>
