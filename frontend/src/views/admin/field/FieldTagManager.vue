<script setup lang="ts">
import { computed, ref, reactive, onBeforeUnmount, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Tag, RefreshCw } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
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
import { useIamS1Store } from '../../../stores/iamS1'
import { getHttpErrorMessage } from '../../../utils/httpError'

const route = useRoute()
const loading = ref(false)
const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)
const adminContext = useAdminContextStore()
const iamS1 = useIamS1Store()
const FIELD_VIEW = 'governance:field:view'
const FIELD_MANAGE = 'governance:field:manage'
const MANAGE_HINT = '需要 governance:field:manage 与当前数据源同一绑定'
const canViewFields = computed(() => iamS1.canOnDatasource(FIELD_VIEW, adminContext.datasourceId || undefined))
const canManageFields = computed(() => iamS1.canOnDatasource(FIELD_MANAGE, adminContext.datasourceId || undefined))
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
const snapshotError = ref('')
const locationError = ref('')

// 只消费目录详情传入的白名单定位参数，不复制其他页面的 query。
let requestedSnapshotId = Number(route.query.snapshotId) || undefined
let pendingTableName = typeof route.query.tableName === 'string' ? route.query.tableName : ''
let pendingColumnMetaId = Number(route.query.columnMetaId) || undefined
let snapshotRequestId = 0
let tableRequestId = 0
let columnRequestId = 0
let disposed = false
let lastDatasourceId = adminContext.datasourceId

const query = reactive({
  snapshotId: undefined as number | undefined,
  tableName: ''
})

async function fetchSnapshots() {
  const requestId = ++snapshotRequestId
  const datasourceId = adminContext.datasourceId
  snapshotError.value = ''
  try {
    const res = await listSnapshots({ datasourceId, page: 1, size: 50 })
    if (disposed || requestId !== snapshotRequestId || datasourceId !== adminContext.datasourceId) return
    snapshots.value = res.data?.records ?? []

    if (requestedSnapshotId) {
      let found = snapshots.value.find((item) => item.id === requestedSnapshotId)
      if (!found) {
        // 历史快照可能不在首批 50 条内：按 ID 使用有权限校验的详情接口精确读取。
        try {
          const ensured = await adminContext.ensureSnapshot(requestedSnapshotId)
          if (disposed || requestId !== snapshotRequestId || datasourceId !== adminContext.datasourceId) return
          if (ensured) {
            found = ensured
            if (!snapshots.value.some((item) => item.id === ensured.id)) {
              snapshots.value = [...snapshots.value, ensured]
                .sort((a, b) => b.snapshotVersion - a.snapshotVersion)
            }
          }
        } catch (cause) {
          if (disposed || requestId !== snapshotRequestId || datasourceId !== adminContext.datasourceId) return
          snapshotError.value = `指定的快照无法加载：${getHttpErrorMessage(
            cause,
            '快照不存在、无权访问或加载失败',
          )}。未切换到其他快照。`
          query.snapshotId = undefined
          query.tableName = ''
          pendingTableName = ''
          pendingColumnMetaId = undefined
          tables.value = []
          columns.value = []
          selectedColumnIds.value = []
          return
        }
      }
      if (!found) {
        snapshotError.value = '指定的快照不存在或不属于当前数据源，未切换到其他快照。'
        query.snapshotId = undefined
        query.tableName = ''
        pendingTableName = ''
        pendingColumnMetaId = undefined
        tables.value = []
        columns.value = []
        selectedColumnIds.value = []
        return
      }
      if (disposed) return
      query.snapshotId = requestedSnapshotId
      adminContext.selectSnapshot(requestedSnapshotId)
      return
    }

    // 普通入口没有指定快照时，保留既有默认选择规则。
    if (adminContext.snapshotId && snapshots.value.some((item) => item.id === adminContext.snapshotId)) {
      query.snapshotId = adminContext.snapshotId
    } else {
      query.snapshotId = snapshots.value[0]?.id
    }
    if (query.snapshotId) {
      adminContext.selectSnapshot(query.snapshotId)
    }
  } catch (cause) {
    if (disposed || requestId !== snapshotRequestId) return
    snapshotError.value = getHttpErrorMessage(cause, '快照列表加载失败')
    snapshots.value = []
    query.snapshotId = undefined
    query.tableName = ''
    tables.value = []
    columns.value = []
    selectedColumnIds.value = []
  }
}

async function fetchTables() {
  const requestId = ++tableRequestId
  const snapshotId = query.snapshotId
  const datasourceId = adminContext.datasourceId
  locationError.value = ''
  selectedColumnIds.value = []
  columns.value = []
  if (!snapshotId) {
    tables.value = []
    query.tableName = ''
    return
  }
  try {
    const res = await listSnapshotTables(snapshotId)
    if (disposed || requestId !== tableRequestId
        || query.snapshotId !== snapshotId
        || adminContext.datasourceId !== datasourceId) return
    tables.value = res.data ?? []

    if (pendingTableName) {
      const requested = pendingTableName
      pendingTableName = ''
      const found = tables.value.find(
        (table) => table.tableName.toLowerCase() === requested.toLowerCase(),
      )
      if (!found) {
        locationError.value = `来源快照中未找到表“${requested}”，未自动选择其他表。`
        pendingColumnMetaId = undefined
        query.tableName = ''
        return
      }
      query.tableName = found.tableName
      await fetchColumns()
      return
    }
    pendingColumnMetaId = undefined
    query.tableName = ''
  } catch (cause) {
    if (disposed || requestId !== tableRequestId || query.snapshotId !== snapshotId) return
    tables.value = []
    query.tableName = ''
    locationError.value = getHttpErrorMessage(cause, '快照表列表加载失败')
  }
}

async function fetchColumns() {
  const requestId = ++columnRequestId
  const snapshotId = query.snapshotId
  const tableName = query.tableName
  const datasourceId = adminContext.datasourceId
  if (!snapshotId || !tableName) {
    columns.value = []
    loading.value = false
    return
  }
  loading.value = true
  try {
    const res = await listSnapshotTableColumns(snapshotId, tableName)
    if (disposed || requestId !== columnRequestId
        || query.snapshotId !== snapshotId
        || query.tableName !== tableName
        || adminContext.datasourceId !== datasourceId) return
    columns.value = res.data ?? []
    selectedColumnIds.value = []

    if (pendingColumnMetaId) {
      const requested = pendingColumnMetaId
      pendingColumnMetaId = undefined
      const column = columns.value.find((item) => item.id === requested)
      if (!column) {
        locationError.value = '当前来源快照的表中未找到指定字段，未自动选择其他字段。'
        return
      }
      selectedColumnIds.value = [column.id]
      await viewColumnTags(column)
    }
  } catch (cause) {
    if (disposed || requestId !== columnRequestId
        || query.snapshotId !== snapshotId
        || query.tableName !== tableName) return
    columns.value = []
    selectedColumnIds.value = []
    locationError.value = getHttpErrorMessage(cause, '字段列表加载失败')
  } finally {
    if (!disposed && requestId === columnRequestId) loading.value = false
  }
}

async function handleSnapshotChange(id?: number) {
  // 来源切换后旧表、旧字段和旧选择全部失效。
  tableRequestId++
  columnRequestId++
  pendingTableName = ''
  pendingColumnMetaId = undefined
  locationError.value = ''
  snapshotError.value = ''
  query.snapshotId = id
  query.tableName = ''
  tables.value = []
  columns.value = []
  selectedColumnIds.value = []
  adminContext.selectSnapshot(id)
  await fetchTables()
}

async function fetchPredefinedTags() {
  if (!canViewFields.value) {
    predefinedTags.value = []
    return
  }
  const res = await listPredefinedTags()
  predefinedTags.value = res.data ?? []
}

function handleSelectionChange(rows: ColumnMetaItem[]) {
  selectedColumnIds.value = rows.map(r => r.id)
}

function openBatchTagDialog() {
  if (!canManageFields.value) {
    ElMessage.warning(MANAGE_HINT)
    return
  }
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
  const snapshotId = query.snapshotId
  const tableName = query.tableName
  const columnMetaIds = [...selectedColumnIds.value]
  if (!columnMetaIds.length) {
    ElMessage.warning('请先选择字段')
    return
  }
  try {
    const res = await batchAddFieldTags({ columnMetaIds, tagCode: selectedTagCode.value })
    if (disposed || query.snapshotId !== snapshotId || query.tableName !== tableName) return
    ElMessage.success(`批量打标成功，已标记 ${res.data?.tagged ?? 0} 个字段`)
    showTagDialog.value = false
  } catch (cause) {
    if (disposed || query.snapshotId !== snapshotId || query.tableName !== tableName) return
    ElMessage.error(getHttpErrorMessage(cause, '打标失败'))
  }
}

async function viewColumnTags(row: ColumnMetaItem) {
  const snapshotId = query.snapshotId
  const tableName = query.tableName
  currentColumnName.value = row.columnName
  try {
    const res = await getFieldTags(row.id)
    if (disposed || query.snapshotId !== snapshotId || query.tableName !== tableName) return
    currentColumnTags.value = res.data ?? []
    showColumnTagsDialog.value = true
  } catch (cause) {
    if (!disposed && query.snapshotId === snapshotId && query.tableName === tableName) {
      ElMessage.error(getHttpErrorMessage(cause, '字段标签加载失败'))
    }
  }
}

async function handleAutoTag() {
  if (!canManageFields.value) {
    ElMessage.warning(MANAGE_HINT)
    return
  }
  if (!query.snapshotId) return
  const datasourceId = adminContext.datasourceId
  const snapshot = snapshots.value.find(s => s.id === query.snapshotId)
  if (!snapshot?.datasourceId) {
    ElMessage.warning('当前快照缺少数据源信息，无法自动打标')
    return
  }
  try {
    const res = await autoTagByPattern(snapshot.datasourceId)
    if (disposed || adminContext.datasourceId !== datasourceId) return
    ElMessage.success(`自动打标完成：标记 ${res.data?.tagged ?? 0} 个，跳过 ${res.data?.skipped ?? 0} 个`)
  } catch (cause) {
    if (disposed || adminContext.datasourceId !== datasourceId) return
    ElMessage.error(getHttpErrorMessage(cause, '自动打标失败'))
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
    if (!snapshotId) {
      // 显式指定的快照加载失败时保留错误，不用最新快照覆盖。
      if (requestedSnapshotId) return
      tableRequestId++
      columnRequestId++
      query.snapshotId = undefined
      query.tableName = ''
      tables.value = []
      columns.value = []
      selectedColumnIds.value = []
      return
    }
    if (query.snapshotId === snapshotId) return
    tableRequestId++
    columnRequestId++
    pendingTableName = ''
    pendingColumnMetaId = undefined
    locationError.value = ''
    query.snapshotId = snapshotId
    query.tableName = ''
    tables.value = []
    columns.value = []
    selectedColumnIds.value = []
    await fetchTables()
  },
)

watch(
  () => adminContext.datasourceId,
  async (datasourceId) => {
    const hadDatasource = lastDatasourceId !== undefined
    lastDatasourceId = datasourceId
    tableRequestId++
    columnRequestId++
    if (hadDatasource) {
      // 真正的数据源切换后旧定位参数和旧字段选择不再适用；
      // 初始化时从空上下文解析出数据源则保留 URL 中的精确定位。
      requestedSnapshotId = undefined
      pendingTableName = ''
      pendingColumnMetaId = undefined
    }
    locationError.value = ''
    snapshotError.value = ''
    query.snapshotId = undefined
    query.tableName = ''
    tables.value = []
    columns.value = []
    selectedColumnIds.value = []
    if (!datasourceId) {
      snapshots.value = []
      return
    }
    await fetchSnapshots()
    if (query.snapshotId) await fetchTables()
  },
)

onBeforeUnmount(() => {
  disposed = true
  snapshotRequestId++
  tableRequestId++
  columnRequestId++
})
</script>

<template>
  <main ref="pageRef" class="field-tag-page post-login-page">
    <section class="page-actions">
      <div class="header-actions">
        <el-button :disabled="!canManageFields" :title="canManageFields ? '' : MANAGE_HINT" @click="handleAutoTag">
          <RefreshCw :size="16" style="margin-right: 4px" />自动打标
        </el-button>
        <el-button type="primary" :disabled="!canManageFields" :title="canManageFields ? '' : MANAGE_HINT" @click="openBatchTagDialog">
          <Tag :size="16" style="margin-right: 4px" />批量打标
        </el-button>
      </div>
    </section>

    <el-alert
      v-if="snapshotError"
      class="field-tag-alert"
      type="error"
      :closable="false"
      :title="snapshotError"
    />
    <el-alert
      v-if="locationError"
      class="field-tag-alert"
      type="warning"
      :closable="false"
      :title="locationError"
    />

    <section class="toolbar">
      <el-select v-model="query.snapshotId" placeholder="选择快照" @change="handleSnapshotChange" style="width: 200px">
        <el-option v-for="s in snapshots" :key="s.id" :label="`快照 v${s.snapshotVersion}`" :value="s.id" />
      </el-select>
      <el-select
        v-model="query.tableName"
        placeholder="选择表"
        :disabled="!query.snapshotId || !!snapshotError"
        @change="fetchColumns"
        style="width: 240px"
        filterable
      >
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
      <el-empty
        v-if="!columns.length && !loading"
        :description="snapshotError || locationError ? '当前指定来源无法定位字段' : '请选择快照和表'"
      />
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
.field-tag-page { min-width: 0; width: 100%; padding: 0; }
.header-actions { display: flex; gap: 8px; }
.field-tag-alert { margin-bottom: 12px; }
.toolbar { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.toolbar :deep(.el-select) { min-width: 0; max-width: 100%; }
.content-panel { background: var(--do-glass-data-bg); border: 1px solid var(--do-glass-line); border-radius: var(--do-radius-lg); box-shadow: var(--do-shadow); padding: 16px; }
</style>
