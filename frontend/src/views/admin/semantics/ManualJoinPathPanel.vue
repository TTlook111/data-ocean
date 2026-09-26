<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, RefreshCw, Trash2 } from 'lucide-vue-next'
import {
  createManualJoinPath,
  deleteManualJoinPath,
  getManualJoinPathOptions,
  listManualJoinPaths,
  type ManualJoinPath,
  type ManualJoinPathOptions,
} from '../../../api/admin/metadata'

const props = defineProps<{
  datasourceId: number
  snapshotId: number
  canView: boolean
  canManage: boolean
}>()

const detail = ref<ManualJoinPathOptions | null>(null)
const relations = ref<ManualJoinPath[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const sourceTableId = ref<number>()
const sourceColumnId = ref<number>()
const targetTableId = ref<number>()
const targetColumnId = ref<number>()

const tables = computed(() => detail.value?.tables || [])
const sourceTable = computed(() => tables.value.find((table) => table.id === sourceTableId.value))
const targetTable = computed(() => tables.value.find((table) => table.id === targetTableId.value))
const sourceColumns = computed(() => sourceTable.value?.columns || [])
const targetColumns = computed(() => targetTable.value?.columns || [])

function apiError(cause: unknown, fallback: string) {
  return (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
    || (cause instanceof Error ? cause.message : fallback)
}

async function load() {
  if (!props.canView) {
    relations.value = []
    error.value = '需要 lineage:view 并负责该数据源才能查看关系事实。'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const [schema, paths] = await Promise.all([
      getManualJoinPathOptions(props.datasourceId, props.snapshotId),
      listManualJoinPaths(props.datasourceId, props.snapshotId),
    ])
    detail.value = schema.data
    relations.value = paths.data || []
  } catch (cause) {
    detail.value = null
    relations.value = []
    error.value = apiError(cause, 'Join Path 加载失败')
  } finally {
    loading.value = false
  }
}

async function addRelation() {
  if (!props.canManage) {
    ElMessage.warning('需要 lineage:manage 并负责该数据源，才能明确确认人工 Join。')
    return
  }
  if (!sourceTable.value || !targetTable.value || !sourceColumnId.value || !targetColumnId.value) {
    ElMessage.warning('请选择两端的表和字段。')
    return
  }
    const sourceColumn = sourceColumns.value.find((column) => column.id === sourceColumnId.value)
    const targetColumn = targetColumns.value.find((column) => column.id === targetColumnId.value)
  if (!sourceColumn || !targetColumn) return
  const condition = `${sourceTable.value.tableName}.${sourceColumn.columnName} = ${targetTable.value.tableName}.${targetColumn.columnName}`
  try {
    await ElMessageBox.confirm(
      `确认将「${condition}」登记为本快照中可执行的人工 Join Path？此操作明确确认该关联条件；血缘关系不会因此变成 Join。`,
      '确认人工 Join Path',
      { type: 'warning', confirmButtonText: '确认关系', cancelButtonText: '取消' },
    )
    saving.value = true
    await createManualJoinPath(props.datasourceId, {
      snapshotId: props.snapshotId,
      sourceTable: sourceTable.value.tableName,
      sourceColumn: sourceColumn.columnName,
      targetTable: targetTable.value.tableName,
      targetColumn: targetColumn.columnName,
      confirmed: true,
    })
    ElMessage.success('人工 Join Path 已确认；请更新、审核并发布对应知识文档，再单独确认 RAG build。')
    sourceColumnId.value = undefined
    targetColumnId.value = undefined
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '确认 Join Path 失败'))
  } finally {
    saving.value = false
  }
}

async function removeRelation(row: ManualJoinPath) {
  if (!props.canManage) return
  try {
    await ElMessageBox.confirm(
      `删除「${row.sourceTable}.${row.sourceColumn} = ${row.targetTable}.${row.targetColumn}」的人工确认关系？`,
      '删除人工 Join Path',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
    saving.value = true
    await deleteManualJoinPath(props.datasourceId, row.id)
    ElMessage.success('已删除人工 Join Path')
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '删除 Join Path 失败'))
  } finally {
    saving.value = false
  }
}

watch(() => [props.datasourceId, props.snapshotId, props.canView], load)
onMounted(load)
</script>

<template>
  <section class="join-path-panel">
    <header class="join-path-panel__header">
      <div>
        <h3>本快照的人工 Join Path</h3>
        <p>只记录已确认的可执行关联条件。数据血缘与字段派生在单独章节呈现，不会被当作 Join。</p>
      </div>
      <el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
    </header>

    <el-alert
      v-if="props.canManage"
      type="info"
      :closable="false"
      title="新关系不会自动发布知识或重建向量。请先更新、审核并发布对应知识文档，再由有权人员明确确认 RAG build。"
    />
    <el-alert v-if="error" type="error" :closable="false" :title="error" />

    <div v-if="props.canManage && detail" class="join-path-form">
      <el-select v-model="sourceTableId" placeholder="源表" clearable @change="sourceColumnId = undefined">
        <el-option v-for="table in tables" :key="table.id" :label="table.tableName" :value="table.id" />
      </el-select>
      <el-select v-model="sourceColumnId" placeholder="源字段" clearable :disabled="!sourceTableId">
        <el-option v-for="column in sourceColumns" :key="column.id" :label="column.columnName" :value="column.id" />
      </el-select>
      <span class="join-path-equals">=</span>
      <el-select v-model="targetTableId" placeholder="目标表" clearable @change="targetColumnId = undefined">
        <el-option v-for="table in tables" :key="table.id" :label="table.tableName" :value="table.id" />
      </el-select>
      <el-select v-model="targetColumnId" placeholder="目标字段" clearable :disabled="!targetTableId">
        <el-option v-for="column in targetColumns" :key="column.id" :label="column.columnName" :value="column.id" />
      </el-select>
      <el-button type="primary" :icon="Plus" :loading="saving" @click="addRelation">明确确认</el-button>
    </div>

    <el-table v-if="!loading && !error" :data="relations" stripe size="small">
      <el-table-column label="已确认关系" min-width="320">
        <template #default="{ row }">
          {{ row.sourceTable }}.{{ row.sourceColumn }} = {{ row.targetTable }}.{{ row.targetColumn }}
        </template>
      </el-table-column>
      <el-table-column prop="reviewStatus" label="审核状态" width="120" />
      <el-table-column prop="reviewedAt" label="确认时间" min-width="170" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button v-if="props.canManage" link type="danger" :icon="Trash2" :loading="saving" @click="removeRelation(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.join-path-panel { display: grid; gap: 14px; margin: 16px 0; padding: 18px; border: 1px solid var(--do-line); border-radius: 12px; background: var(--do-surface); }
.join-path-panel__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.join-path-panel__header h3 { margin: 0; color: var(--do-ink); font-size: 14px; }
.join-path-panel__header p { margin: 5px 0 0; color: var(--do-muted); font-size: 12px; line-height: 1.6; }
.join-path-form { display: grid; grid-template-columns: minmax(130px, 1fr) minmax(130px, 1fr) 20px minmax(130px, 1fr) minmax(130px, 1fr) auto; align-items: center; gap: 8px; }
.join-path-equals { color: var(--do-muted); text-align: center; }
@media (max-width: 1000px) { .join-path-form { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
