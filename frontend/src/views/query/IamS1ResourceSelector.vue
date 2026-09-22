<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  IAM_S1_DEFAULT_QUERY_USAGES,
  IAM_S1_EXTENDED_QUERY_USAGES,
  listIamS1QueryResourceColumns,
  listIamS1QueryResourceSnapshots,
  listIamS1QueryResourceTables,
  type IamS1ColumnOption,
  type IamS1ColumnUsage,
  type IamS1TableDeclaration,
  type IamS1TableOption,
} from '../../api/iamS1'

const props = defineProps<{
  datasourceId?: number
  canQuery: boolean
}>()

const declarations = defineModel<IamS1TableDeclaration[]>({ default: [] })
const snapshots = ref<Array<{ id: number; snapshotVersion?: number }>>([])
const snapshotId = ref<number>()
const tables = ref<IamS1TableOption[]>([])
const columns = ref<IamS1ColumnOption[]>([])
const draftTable = ref<string>()
const draftColumns = ref<string[]>([])
const extendedUsage = ref(false)
const protectionLevels = ref<Record<string, string>>({})
const loading = ref(false)

function usagesFor(column: IamS1ColumnOption): IamS1ColumnUsage[] {
  if (column.protectionLevel === 'MASKED') return ['PROJECTION']
  if (column.protectionLevel === 'HIDDEN') return []
  return extendedUsage.value
    ? [...IAM_S1_DEFAULT_QUERY_USAGES, ...IAM_S1_EXTENDED_QUERY_USAGES]
    : [...IAM_S1_DEFAULT_QUERY_USAGES]
}

function refreshUsages() {
  for (const declaration of declarations.value) {
    const usage: Record<string, IamS1ColumnUsage[]> = {}
    for (const column of declaration.referencedColumns) {
      const level = protectionLevels.value[`${declaration.tableName}.${column}`]
      usage[column] = level === 'MASKED'
        ? ['PROJECTION']
        : level === 'HIDDEN'
          ? []
          : extendedUsage.value
            ? [...IAM_S1_DEFAULT_QUERY_USAGES, ...IAM_S1_EXTENDED_QUERY_USAGES]
            : [...IAM_S1_DEFAULT_QUERY_USAGES]
    }
    declaration.columnUsages = usage
  }
}

async function loadSnapshots() {
  snapshots.value = []
  tables.value = []
  columns.value = []
  declarations.value = []
  snapshotId.value = undefined
  draftTable.value = undefined
  draftColumns.value = []
  if (!props.datasourceId || !props.canQuery) return
  const result = await listIamS1QueryResourceSnapshots(props.datasourceId, 'QUERY')
  snapshots.value = result.data ?? []
  snapshotId.value = snapshots.value[0]?.id
  await loadTables()
}

async function loadTables() {
  tables.value = []
  columns.value = []
  declarations.value = []
  draftTable.value = undefined
  draftColumns.value = []
  if (!props.datasourceId || !snapshotId.value || !props.canQuery) return
  const result = await listIamS1QueryResourceTables(props.datasourceId, snapshotId.value, 'QUERY')
  tables.value = result.data ?? []
}

async function loadColumns() {
  columns.value = []
  draftColumns.value = []
  if (!props.datasourceId || !snapshotId.value || !draftTable.value || !props.canQuery) return
  const result = await listIamS1QueryResourceColumns(
    props.datasourceId, snapshotId.value, draftTable.value, 'QUERY',
  )
  columns.value = result.data ?? []
}

function addDeclaration() {
  if (!draftTable.value || !draftColumns.value.length) {
    ElMessage.warning('请选择表和至少一个字段；空字段不表示全部字段')
    return
  }
  const current = declarations.value.find((item) => item.tableName === draftTable.value)
  const referencedColumns = current
    ? Array.from(new Set([...current.referencedColumns, ...draftColumns.value]))
    : [...draftColumns.value]
  for (const option of columns.value) {
    protectionLevels.value[`${draftTable.value}.${option.columnName}`] = option.protectionLevel ?? 'NORMAL'
  }
  const columnUsages = Object.fromEntries(referencedColumns.map((column) => {
    const level = protectionLevels.value[`${draftTable.value}.${column}`]
    return [column, level === 'MASKED'
      ? ['PROJECTION']
      : level === 'HIDDEN'
        ? []
        : usagesFor({ columnName: column, protectionLevel: level } as IamS1ColumnOption)]
  })) as Record<string, IamS1ColumnUsage[]>
  if (current) {
    current.referencedColumns = referencedColumns
    current.columnUsages = columnUsages
  } else {
    declarations.value = [...declarations.value, {
      tableName: draftTable.value,
      referencedColumns,
      columnUsages,
    }]
  }
  draftColumns.value = []
}

watch(() => props.datasourceId, () => void loadSnapshots())
watch(() => props.canQuery, () => void loadSnapshots())
watch(extendedUsage, refreshUsages)
onMounted(() => void loadSnapshots())
</script>

<template>
  <section class="resource-selector" aria-label="IAM-SIMPLE-1 查询资源声明">
    <div class="resource-heading">
      <div>
        <strong>查询资源声明</strong>
        <small>只从当前 S1 数据授权返回可选表和字段</small>
      </div>
      <span v-if="declarations.length">已声明 {{ declarations.length }} 张表</span>
    </div>
    <div v-if="!canQuery" class="resource-empty">当前账号没有“使用问数”能力。</div>
    <div v-else class="resource-controls">
      <el-select v-model="snapshotId" class="resource-select" placeholder="已发布快照" @change="loadTables">
        <el-option v-for="item in snapshots" :key="item.id" :label="`快照版本 ${item.snapshotVersion ?? item.id}`" :value="item.id" />
      </el-select>
      <el-select v-model="draftTable" class="resource-select" placeholder="选择表" @change="loadColumns">
        <el-option v-for="item in tables" :key="item.tableName" :label="item.tableName" :value="item.tableName" />
      </el-select>
      <el-select v-model="draftColumns" class="resource-columns" multiple filterable placeholder="选择字段">
        <el-option
          v-for="item in columns"
          :key="item.columnMetaId"
          :label="`${item.columnComment || item.columnName}${item.selectable ? '' : '（不可查询）'}`"
          :value="item.columnName"
          :disabled="!item.selectable"
        />
      </el-select>
      <el-button :loading="loading" @click="addDeclaration">加入声明</el-button>
      <el-checkbox v-model="extendedUsage">允许排序、分组、聚合和子查询</el-checkbox>
    </div>
    <div v-if="canQuery && !snapshots.length" class="resource-empty">当前没有可用于问数的 S1 数据资源。</div>
    <div v-if="declarations.length" class="declared-list">
      <div v-for="(item, index) in declarations" :key="item.tableName" class="declared-row">
        <span><strong>{{ item.tableName }}</strong><small>{{ item.referencedColumns.join('、') }}</small></span>
        <button type="button" @click="declarations.splice(index, 1)">移除</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.resource-selector { display: grid; gap: 10px; padding: 12px 14px; border: 1px solid var(--do-line); border-radius: 11px; background: rgba(255, 255, 255, .76); }
.resource-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.resource-heading strong, .resource-heading small { display: block; }
.resource-heading strong { color: var(--do-ink); font-size: 12px; }
.resource-heading small, .resource-heading span, .resource-empty { color: var(--do-muted); font-size: 11px; }
.resource-heading small { margin-top: 3px; }
.resource-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.resource-select { width: 170px; }
.resource-columns { min-width: 220px; flex: 1 1 260px; }
.declared-list { display: grid; gap: 5px; }
.declared-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 7px 9px; border-radius: 8px; background: var(--do-primary-soft); }
.declared-row strong, .declared-row small { display: block; }
.declared-row strong { color: var(--do-ink); font-size: 12px; }
.declared-row small { margin-top: 3px; color: var(--do-muted); font-size: 11px; }
.declared-row button { border: 0; color: var(--do-danger); background: transparent; cursor: pointer; font-size: 11px; }
</style>
