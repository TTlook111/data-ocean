<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listSnapshots, type SnapshotItem } from '../api/admin/metadata'
import {
  listSnapshotTableColumns,
  listSnapshotTables,
  type ColumnMetaItem,
  type TableMetaItem,
} from '../api/admin/governance'
import { useAdminContextStore } from '../stores/adminContext'

type ScopeMode = 'datasource' | 'snapshot' | 'table' | 'column'

const props = withDefaults(defineProps<{
  datasourceId?: number
  snapshotId?: number
  tableName?: string
  columnName?: string
  mode?: ScopeMode
  showDatasource?: boolean
  showSnapshot?: boolean
  disabled?: boolean
  /**
   * 锁定快照：调用方给的 `snapshotId` 是权威值（例如「已发布快照」），
   * 用户不能改，组件也不会在它不在快照列表里时静默换成最新快照。
   */
  lockSnapshot?: boolean
  /** 锁定快照时显示的只读文案，例如「已发布快照 v3」 */
  lockSnapshotLabel?: string
  includeAllTableOption?: boolean
  includeAllColumnOption?: boolean
  allTableLabel?: string
  allColumnLabel?: string
  size?: 'large' | 'default' | 'small'
}>(), {
  mode: 'column',
  showDatasource: true,
  showSnapshot: true,
  disabled: false,
  lockSnapshot: false,
  lockSnapshotLabel: '',
  includeAllTableOption: false,
  includeAllColumnOption: false,
  allTableLabel: '全部表',
  allColumnLabel: '全部字段',
  size: 'default',
})

const emit = defineEmits<{
  (event: 'update:datasourceId', value: number | undefined): void
  (event: 'update:snapshotId', value: number | undefined): void
  (event: 'update:tableName', value: string): void
  (event: 'update:columnName', value: string): void
  (event: 'change', value: {
    datasourceId?: number
    snapshotId?: number
    tableName: string
    columnName: string
  }): void
}>()

const adminContext = useAdminContextStore()
const snapshots = ref<SnapshotItem[]>([])
const tables = ref<TableMetaItem[]>([])
const columns = ref<ColumnMetaItem[]>([])
const loadingSnapshots = ref(false)
const loadingTables = ref(false)
const loadingColumns = ref(false)
const loadError = ref<string>('')
const retryCount = ref(0)
const maxRetries = 3
let datasourceRequestId = 0
let snapshotRequestId = 0
let columnRequestId = 0
let disposed = false

const localDatasourceId = ref<number | undefined>(props.datasourceId)
const localSnapshotId = ref<number | undefined>(props.snapshotId)
const localTableName = ref(props.tableName || '')
const localColumnName = ref(props.columnName || '')

const needsSnapshot = computed(() => ['snapshot', 'table', 'column'].includes(props.mode))
const needsTable = computed(() => ['table', 'column'].includes(props.mode))
const needsColumn = computed(() => props.mode === 'column')
const datasourceDisabled = computed(() => props.disabled || adminContext.loading)
const snapshotDisabled = computed(() => props.disabled || props.lockSnapshot || !localDatasourceId.value || loadingSnapshots.value)
const tableDisabled = computed(() => props.disabled || !localSnapshotId.value || loadingTables.value)
const columnDisabled = computed(() => props.disabled || !localSnapshotId.value || !localTableName.value || loadingColumns.value)

const datasourceOptions = computed(() => adminContext.datasources)

function emitChange() {
  emit('change', {
    datasourceId: localDatasourceId.value,
    snapshotId: localSnapshotId.value,
    tableName: localTableName.value,
    columnName: localColumnName.value,
  })
}

function latestSnapshotFirst(a: SnapshotItem, b: SnapshotItem) {
  return b.snapshotVersion - a.snapshotVersion
}

async function loadSnapshotsForDatasource(datasourceId?: number) {
  const currentRequest = ++datasourceRequestId
  snapshotRequestId++
  columnRequestId++
  snapshots.value = []
  tables.value = []
  columns.value = []
  loadError.value = ''

  if (!datasourceId || !needsSnapshot.value) {
    localSnapshotId.value = undefined
    emit('update:snapshotId', undefined)
    return
  }

  loadingSnapshots.value = true
  try {
    const result = await listSnapshots({ datasourceId, page: 1, size: 50 })
    if (disposed || currentRequest !== datasourceRequestId) return
    snapshots.value = [...(result.data?.records ?? [])].sort(latestSnapshotFirst)
    retryCount.value = 0  // 重置重试计数

    if (props.lockSnapshot) {
      // 锁定时调用方的 snapshotId 就是权威值。
      // 不能因为「已发布快照不在最新 50 条里」就回落到 snapshots[0]——那很可能是草稿快照，
      // 之后提交会被后端以「表名不存在」拒绝，正是这里要避免的难懂报错。
      await loadTablesForSnapshot(localSnapshotId.value)
      return
    }
    if (!localSnapshotId.value || !snapshots.value.some((item) => item.id === localSnapshotId.value)) {
      localSnapshotId.value = snapshots.value[0]?.id
      emit('update:snapshotId', localSnapshotId.value)
    }
    await loadTablesForSnapshot(localSnapshotId.value)
  } catch (error: any) {
    if (disposed || currentRequest !== datasourceRequestId) return
    const message = error.response?.data?.message || '快照列表加载失败'
    loadError.value = message
    ElMessage.error(message)
  } finally {
    if (!disposed && currentRequest === datasourceRequestId) loadingSnapshots.value = false
  }
}

async function retryLoad() {
  if (retryCount.value >= maxRetries) {
    ElMessage.error('重试次数过多，请稍后再试')
    return
  }
  retryCount.value++
  await loadSnapshotsForDatasource(localDatasourceId.value)
}

async function loadTablesForSnapshot(snapshotId?: number) {
  const currentRequest = ++snapshotRequestId
  columnRequestId++
  tables.value = []
  columns.value = []
  loadError.value = ''

  if (!snapshotId || !needsTable.value) {
    localTableName.value = ''
    localColumnName.value = ''
    emit('update:tableName', '')
    emit('update:columnName', '')
    return
  }

  loadingTables.value = true
  try {
    const result = await listSnapshotTables(snapshotId)
    if (disposed || currentRequest !== snapshotRequestId) return
    tables.value = result.data ?? []
    if (localTableName.value && !tables.value.some((item) => item.tableName === localTableName.value)) {
      localTableName.value = ''
      localColumnName.value = ''
      emit('update:tableName', '')
      emit('update:columnName', '')
    }
    if (localTableName.value) {
      await loadColumnsForTable(snapshotId, localTableName.value)
    }
  } catch (error: any) {
    if (disposed || currentRequest !== snapshotRequestId) return
    const message = error.response?.data?.message || '数据表列表加载失败'
    loadError.value = message
    ElMessage.error(message)
  } finally {
    if (!disposed && currentRequest === snapshotRequestId) loadingTables.value = false
  }
}

async function loadColumnsForTable(snapshotId?: number, tableName?: string) {
  const currentRequest = ++columnRequestId
  columns.value = []
  loadError.value = ''

  if (!snapshotId || !tableName || !needsColumn.value) {
    localColumnName.value = ''
    emit('update:columnName', '')
    return
  }

  loadingColumns.value = true
  try {
    const result = await listSnapshotTableColumns(snapshotId, tableName)
    if (disposed || currentRequest !== columnRequestId) return
    columns.value = result.data ?? []
    if (localColumnName.value && !columns.value.some((item) => item.columnName === localColumnName.value)) {
      localColumnName.value = ''
      emit('update:columnName', '')
    }
  } catch (error: any) {
    if (disposed || currentRequest !== columnRequestId) return
    const message = error.response?.data?.message || '字段列表加载失败'
    loadError.value = message
    ElMessage.error(message)
  } finally {
    if (!disposed && currentRequest === columnRequestId) loadingColumns.value = false
  }
}

async function handleDatasourceChange(value?: number) {
  localDatasourceId.value = value
  localSnapshotId.value = undefined
  localTableName.value = ''
  localColumnName.value = ''
  emit('update:datasourceId', value)
  emit('update:snapshotId', undefined)
  emit('update:tableName', '')
  emit('update:columnName', '')
  await adminContext.selectDatasource(value)
  await loadSnapshotsForDatasource(value)
  emitChange()
}

async function handleSnapshotChange(value?: number) {
  localSnapshotId.value = value
  localTableName.value = ''
  localColumnName.value = ''
  emit('update:snapshotId', value)
  emit('update:tableName', '')
  emit('update:columnName', '')
  adminContext.selectSnapshot(value)
  await loadTablesForSnapshot(value)
  emitChange()
}

async function handleTableChange(value: string) {
  localTableName.value = value || ''
  localColumnName.value = ''
  emit('update:tableName', localTableName.value)
  emit('update:columnName', '')
  await loadColumnsForTable(localSnapshotId.value, localTableName.value)
  emitChange()
}

function handleColumnChange(value: string) {
  localColumnName.value = value || ''
  emit('update:columnName', localColumnName.value)
  emitChange()
}

onMounted(async () => {
  await adminContext.initialize()
  if (!localDatasourceId.value) {
    localDatasourceId.value = adminContext.datasourceId
    emit('update:datasourceId', localDatasourceId.value)
  }
  // 锁定时快照由调用方决定，绝不能用全局上下文（可能是草稿快照）顶替
  if (!localSnapshotId.value && !props.lockSnapshot) {
    localSnapshotId.value = adminContext.snapshotId
    emit('update:snapshotId', localSnapshotId.value)
  }
  await loadSnapshotsForDatasource(localDatasourceId.value)
})

onBeforeUnmount(() => {
  disposed = true
  datasourceRequestId++
  snapshotRequestId++
  columnRequestId++
})

watch(() => props.datasourceId, async (value) => {
  if (value === localDatasourceId.value) return
  localDatasourceId.value = value
  await loadSnapshotsForDatasource(value)
  emitChange()
})

watch(() => props.snapshotId, async (value) => {
  if (value === localSnapshotId.value) return
  localSnapshotId.value = value
  await loadTablesForSnapshot(value)
  emitChange()
})

watch(() => props.tableName, async (value) => {
  const next = value || ''
  if (next === localTableName.value) return
  localTableName.value = next
  await loadColumnsForTable(localSnapshotId.value, next)
})

watch(() => props.columnName, (value) => {
  localColumnName.value = value || ''
})
</script>

<template>
  <div class="resource-scope-selector">
    <el-select
      v-if="showDatasource"
      v-model="localDatasourceId"
      :disabled="datasourceDisabled"
      :size="size"
      filterable
      clearable
      placeholder="选择数据源"
      class="scope-select scope-select--datasource"
      @change="handleDatasourceChange"
    >
      <el-option
        v-for="item in datasourceOptions"
        :key="item.id"
        :label="`${item.name}${item.databaseName ? ` / ${item.databaseName}` : ''}`"
        :value="item.id"
      />
    </el-select>

    <!-- 快照选择器（带错误处理）；lockSnapshot 时改为只读展示，不给用户切到草稿快照的机会 -->
    <div v-if="showSnapshot && needsSnapshot" class="scope-select-wrapper">
      <span v-if="lockSnapshot" class="scope-locked" :class="`scope-locked--${size}`">
        {{ lockSnapshotLabel || '已发布快照' }}
      </span>
      <el-select
        v-else
        v-model="localSnapshotId"
        :disabled="snapshotDisabled || !!loadError"
        :loading="loadingSnapshots"
        :size="size"
        filterable
        clearable
        :placeholder="loadError ? '加载失败' : '选择快照'"
        class="scope-select scope-select--snapshot"
        :class="{ 'is-error': !!loadError }"
        @change="handleSnapshotChange"
      >
        <el-option
          v-for="item in snapshots"
          :key="item.id"
          :label="`快照 v${item.snapshotVersion}${item.status ? ` / ${item.status}` : ''}`"
          :value="item.id"
        />
      </el-select>

      <!-- 错误提示和重试按钮 -->
      <div v-if="loadError" class="scope-error">
        <span class="scope-error__message">{{ loadError }}</span>
        <el-button
          type="primary"
          link
          size="small"
          :loading="loadingSnapshots"
          @click="retryLoad"
        >
          重试
        </el-button>
      </div>
    </div>

    <el-select
      v-if="needsTable"
      v-model="localTableName"
      :disabled="tableDisabled"
      :loading="loadingTables"
      :size="size"
      filterable
      clearable
      placeholder="选择表"
      class="scope-select scope-select--table"
      @change="handleTableChange"
    >
      <el-option v-if="includeAllTableOption" :label="allTableLabel" value="" />
      <el-option
        v-for="item in tables"
        :key="item.tableName"
        :label="item.tableComment ? `${item.tableName} / ${item.tableComment}` : item.tableName"
        :value="item.tableName"
      />
    </el-select>

    <el-select
      v-if="needsColumn"
      v-model="localColumnName"
      :disabled="columnDisabled"
      :loading="loadingColumns"
      :size="size"
      filterable
      clearable
      placeholder="选择字段"
      class="scope-select scope-select--column"
      @change="handleColumnChange"
    >
      <el-option v-if="includeAllColumnOption" :label="allColumnLabel" value="" />
      <el-option
        v-for="item in columns"
        :key="item.columnName"
        :label="item.columnComment ? `${item.columnName} / ${item.columnComment}` : item.columnName"
        :value="item.columnName"
      />
    </el-select>
  </div>
</template>

<style scoped>
.resource-scope-selector {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 12px;
}

.scope-select-wrapper {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.scope-select--datasource {
  width: 240px;
}

.scope-select--snapshot {
  width: 200px;
}

.scope-select--table {
  width: 240px;
}

.scope-select--column {
  width: 220px;
}

.scope-locked {
  display: inline-flex;
  align-items: center;
  height: 32px;
  padding: 0 12px;
  border: 1px dashed var(--do-line);
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
  color: var(--do-muted);
  font-size: 13px;
  white-space: nowrap;
}

.scope-locked--large { height: 40px; }
.scope-locked--small { height: 24px; padding: 0 8px; font-size: 12px; }

.scope-select.is-error :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}

.scope-error {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 4px;
}

.scope-error__message {
  color: var(--el-color-danger);
  font-size: 12px;
}

</style>
