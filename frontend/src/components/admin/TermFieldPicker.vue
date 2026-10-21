<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getTermFieldTables, getTermFieldColumns, type TermColumn } from '../../api/admin/glossary'
import { useIamS1Store } from '../../stores/iamS1'
import { getHttpErrorMessage } from '../../utils/httpError'
import ErrorState from '../common/ErrorState.vue'
const props = defineProps<{ modelValue: TermColumn[]; sources: Array<{ id: number; name: string }> }>()
const emit = defineEmits<{ 'update:modelValue': [TermColumn[]] }>()
const iam = useIamS1Store()
const sources = computed(() => props.sources.filter(s => iam.canOnDatasource('glossary:manage', s.id)))
const datasourceId = ref<number>()
const tableName = ref('')
const keyword = ref('')
const page = ref(1)
const tables = ref<Array<{ id: number; tableName: string; tableComment?: string }>>([])
const columns = ref<TermColumn[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
let requestId = 0
let tableRequestId = 0
let timer: ReturnType<typeof setTimeout> | undefined
async function loadTables() {
  const token = ++tableRequestId
  loading.value = false
  ++requestId
  tableName.value = ''; tables.value = []; columns.value = []; total.value = 0; error.value = ''
  if (!datasourceId.value) return
  loading.value = true
  try { const result = await getTermFieldTables(datasourceId.value); if (token === tableRequestId) {
    tables.value = result.data
    const existing = props.modelValue.find(c => c.datasourceId === datasourceId.value && tables.value.some(t => t.tableName === c.tableName))
    if (existing) tableName.value = existing.tableName
  } }
  catch (cause) { if (token === tableRequestId) error.value = getHttpErrorMessage(cause, '表列表加载失败') }
  finally { if (token === tableRequestId) loading.value = false }
}
async function loadColumns() {
  const token = ++requestId
  loading.value = false
  columns.value = []; total.value = 0; error.value = ''
  if (!datasourceId.value || !tableName.value) return
  loading.value = true
  try {
    const result = await getTermFieldColumns({ datasourceId: datasourceId.value, tableName: tableName.value,
      keyword: keyword.value.trim(), pageNo: page.value, pageSize: 20 })
    if (token === requestId) { columns.value = result.data.records; total.value = result.data.total }
  } catch (cause) { if (token === requestId) error.value = getHttpErrorMessage(cause, '字段加载失败') }
  finally { if (token === requestId) loading.value = false }
}
watch(() => props.modelValue, columns => {
  if (!datasourceId.value && columns[0]) datasourceId.value = columns[0].datasourceId
}, { immediate: true })
watch(datasourceId, loadTables, { immediate: true })
watch(tableName, () => { page.value = 1; loadColumns() })
watch(keyword, () => { clearTimeout(timer); ++requestId; timer = setTimeout(() => { page.value = 1; loadColumns() }, 250) })
watch(page, loadColumns)
onBeforeUnmount(() => { clearTimeout(timer); ++requestId; ++tableRequestId })
function toggle(column: TermColumn) {
  emit('update:modelValue', props.modelValue.some(c => c.entityId === column.entityId)
    ? props.modelValue.filter(c => c.entityId !== column.entityId) : [...props.modelValue, column])
}
</script>
<template>
  <section class="field-picker" aria-label="关联业务字段">
    <p>选择术语对应的字段。可关联多个字段，也可以先保存未关联的草稿。</p>
    <div class="field-filters">
      <label>数据源<el-select v-model="datasourceId" placeholder="选择数据源" clearable>
        <el-option v-for="source in sources" :key="source.id" :value="source.id" :label="source.name" />
      </el-select></label>
      <label>数据表<el-select v-model="tableName" placeholder="选择已发布的表" :disabled="!datasourceId" filterable clearable>
        <el-option v-for="table in tables" :key="table.id" :value="table.tableName" :label="`${table.tableName}${table.tableComment ? ' · ' + table.tableComment : ''}`" />
      </el-select></label>
      <label>查找字段<el-input v-model="keyword" placeholder="字段名或中文说明" :disabled="!tableName" clearable /></label>
    </div>
    <ErrorState v-if="error" :message="error" @retry="tableName ? loadColumns() : loadTables()" />
    <p v-else-if="!sources.length">当前没有可维护的数据源，仍可保存业务定义草稿。</p>
    <el-table v-else v-loading="loading" :data="columns" empty-text="请选择数据源和表，或调整搜索条件" max-height="280">
      <el-table-column prop="columnName" label="字段" min-width="140" />
      <el-table-column prop="description" label="业务说明" min-width="160" />
      <el-table-column prop="columnType" label="类型" width="140" class-name="field-type" show-overflow-tooltip />
      <el-table-column label="选择" width="85"><template #default="{ row }">
        <el-button link type="primary" @click="toggle(row)">{{ modelValue.some(c => c.entityId === row.entityId) ? '移除' : '添加' }}</el-button>
      </template></el-table-column>
    </el-table>
    <el-pagination v-if="total > 20" v-model:current-page="page" :total="total" :page-size="20" layout="prev, pager, next" />
    <div class="chosen-fields" aria-live="polite">
      <strong>{{ modelValue.length ? `已选 ${modelValue.length} 个字段` : '尚未关联字段' }}</strong>
      <div v-for="column in modelValue" :key="column.entityId" class="chosen-field">
        <span>{{ column.datasourceName }} / {{ column.tableName }} / {{ column.columnName }}</span>
        <el-button link type="danger" :aria-label="`移除${column.columnName}`" @click="toggle(column)">移除</el-button>
      </div>
    </div>
  </section>
</template>
<style scoped>
.field-picker { display: grid; gap: 12px; min-width: 0; }
.field-picker p { margin: 0; color: var(--do-muted); font-size: 13px; }
.field-filters { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
label { display: grid; gap: 6px; color: var(--do-ink); font-size: 13px; }
.field-picker :deep(.field-type .cell) { white-space: nowrap; }
.chosen-fields { padding: 12px; background: var(--do-bg); border-radius: var(--do-radius-md); font-size: 13px; }
.chosen-field { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.chosen-field span { overflow-wrap: anywhere; }
@media (max-width: 700px) { .field-filters { grid-template-columns: 1fr; } }
</style>
