<script setup lang="ts">
import { ref, watch, computed, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Trash2 } from 'lucide-vue-next'
import { getEntityDetail, searchCatalog, type MetadataEntityItem } from '../../../api/admin/catalog'
import { createLineage, type LineageCreateRequest } from '../../../api/admin/lineageApi'
import { useAuthStore } from '../../../stores/auth'
import { useIamS1Store } from '../../../stores/iamS1'
import { entitySource } from '../../../utils/lineageContext'
import { trackLineageWrite } from '../../../composables/lineageWriteTracker'
import { getHttpErrorMessage } from '../../../utils/httpError'
const props = defineProps<{
  visible: boolean
  prefilledSourceId?: number | null
  prefilledTargetId?: number | null
}>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void; (e: 'created'): void }>()
const auth = useAuthStore(),
  iam = useIamS1Store()
const lineageType = ref<'ETL' | 'MANUAL'>('ETL'),
  description = ref(''),
  submitting = ref(false),
  error = ref('')
const sourceOptions = ref<MetadataEntityItem[]>([]),
  targetOptions = ref<MetadataEntityItem[]>([]),
  selectedSource = ref<MetadataEntityItem | null>(null),
  selectedTarget = ref<MetadataEntityItem | null>(null)
const sourceLoading = ref(false),
  targetLoading = ref(false),
  sourceColumnOptions = ref<MetadataEntityItem[]>([]),
  targetColumnOptions = ref<MetadataEntityItem[]>([])
interface MappingRow {
  id: number
  fromColumnIds: number[]
  toColumnId: number | null
  expression: string
  transformationType: 'IDENTITY' | 'TRANSFORMATION' | 'AGGREGATION'
}
const mappings = ref<MappingRow[]>([])
let rowId = 0,
  epoch = 0,
  alive = true
const sequences = { source: 0, target: 0, sourceColumns: 0, targetColumns: 0 }
const scope = computed(() => JSON.stringify([auth.token, iam.snapshot, iam.loaded, iam.errorMessage]))
const canUseDirectory = computed(
  () =>
    iam.loaded &&
    !iam.errorMessage &&
    (iam.systemAdmin ||
      iam.datasourceCapabilities.some(
        (x) => x.functionCodes.includes('metadata:view') && x.functionCodes.includes('lineage:manage'),
      )),
)
function eligible(entity: MetadataEntityItem) {
  const source = entitySource(entity)
  return (
    !!source &&
    entity.entityType === 'TABLE' &&
    iam.canOnDatasource('lineage:manage', source) &&
    iam.canOnDatasource('metadata:view', source)
  )
}
function current(captured: number, key: string) {
  return alive && props.visible && captured === epoch && key === scope.value && canUseDirectory.value
}
async function searchTables(query: string, side: 'source' | 'target') {
  const result = side === 'source' ? sourceOptions : targetOptions,
    busy = side === 'source' ? sourceLoading : targetLoading
  const ticket = ++sequences[side],
    captured = epoch,
    key = scope.value
  result.value = []
  if (!canUseDirectory.value || !query.trim()) return
  busy.value = true
  try {
    const r = await searchCatalog({ q: query.trim(), type: 'TABLE', page: 1, size: 30 })
    if (current(captured, key) && ticket === sequences[side]) result.value = (r.data || []).filter(eligible)
  } catch (cause) {
    if (current(captured, key) && ticket === sequences[side])
      error.value = getHttpErrorMessage(cause, '表搜索失败')
  } finally {
    if (current(captured, key) && ticket === sequences[side]) busy.value = false
  }
}
function columnBelongsTo(column: MetadataEntityItem, table: MetadataEntityItem) {
  try {
    return JSON.parse(column.entityMetadata || '{}').table_name === table.name
  } catch {
    return false
  }
}
async function loadColumns(side: 'source' | 'target') {
  const selected = side === 'source' ? selectedSource : selectedTarget,
    options = side === 'source' ? sourceColumnOptions : targetColumnOptions
  const slot = side === 'source' ? 'sourceColumns' : 'targetColumns',
    ticket = ++sequences[slot],
    captured = epoch,
    key = scope.value,
    entity = selected.value
  options.value = []
  mappings.value = []
  if (!entity || !eligible(entity)) return
  try {
    const r = await searchCatalog({
      q: entity.fqn,
      type: 'COLUMN',
      datasourceId: entitySource(entity),
      page: 1,
      size: 500,
    })
    if (current(captured, key) && ticket === sequences[slot] && selected.value?.id === entity.id)
      options.value = (r.data || []).filter(
        (x) =>
          x.entityType === 'COLUMN' &&
          entitySource(x) === entitySource(entity) &&
          x.fqn.startsWith(entity.fqn + '.') &&
          columnBelongsTo(x, entity),
      )
  } catch (cause) {
    if (current(captured, key) && ticket === sequences[slot])
      error.value = getHttpErrorMessage(cause, '字段选项读取失败；可仅记录表级关系')
  }
}
watch(
  selectedSource,
  () => {
    void loadColumns('source')
  },
  { flush: 'sync' },
)
watch(
  selectedTarget,
  () => {
    void loadColumns('target')
  },
  { flush: 'sync' },
)
function addMapping() {
  mappings.value.push({
    id: ++rowId,
    fromColumnIds: [],
    toColumnId: null,
    expression: '',
    transformationType: 'IDENTITY',
  })
}
function removeMapping(row: MappingRow) {
  mappings.value = mappings.value.filter((x) => x.id !== row.id)
}
const mappingValid = computed(() =>
  mappings.value.every(
    (row) =>
      row.fromColumnIds.length > 0 &&
      row.toColumnId !== null &&
      row.fromColumnIds.every((id) => sourceColumnOptions.value.some((x) => x.id === id)) &&
      targetColumnOptions.value.some((x) => x.id === row.toColumnId),
  ),
)
const canSubmit = computed(
  () =>
    canUseDirectory.value &&
    !!selectedSource.value &&
    !!selectedTarget.value &&
    eligible(selectedSource.value) &&
    eligible(selectedTarget.value) &&
    selectedSource.value.id !== selectedTarget.value.id &&
    mappingValid.value,
)
async function handleSubmit() {
  if (!canSubmit.value || submitting.value) return
  const captured = epoch,
    key = scope.value,
    identity = auth.token
  const body: LineageCreateRequest = {
    sourceEntityId: selectedSource.value!.id,
    targetEntityId: selectedTarget.value!.id,
    lineageType: lineageType.value,
    description: description.value || undefined,
    columnMappings: mappings.value.length
      ? mappings.value.map((row) => ({
          fromColumns: [...row.fromColumnIds],
          toColumn: row.toColumnId!,
          expression: row.expression || undefined,
          transformationType: row.transformationType,
        }))
      : undefined,
  }
  submitting.value = true
  error.value = ''
  try {
    await trackLineageWrite(identity, `create:${body.sourceEntityId}:${body.targetEntityId}:LINEAGE`, () =>
      createLineage(body),
    )
    if (!current(captured, key)) return
    ElMessage.success('关系创建成功')
    emit('created')
    resetForm()
    emit('update:visible', false)
  } catch (cause) {
    if (current(captured, key))
      error.value = getHttpErrorMessage(cause, '创建未确认成功，请先核对关系记录；不要重复提交')
  } finally {
    if (current(captured, key)) submitting.value = false
  }
}
function resetForm() {
  epoch++
  lineageType.value = 'ETL'
  description.value = ''
  selectedSource.value = null
  selectedTarget.value = null
  sourceOptions.value = []
  targetOptions.value = []
  sourceColumnOptions.value = []
  targetColumnOptions.value = []
  mappings.value = []
  error.value = ''
  submitting.value = false
  sourceLoading.value = false
  targetLoading.value = false
}
function handleClose() {
  if (submitting.value) return
  resetForm()
  emit('update:visible', false)
}
async function applyPrefilledEntity(id: number, side: 'source' | 'target') {
  const ticket = ++sequences[side],
    captured = epoch,
    key = scope.value
  try {
    const entity = (await getEntityDetail(id)).data.entity
    if (!current(captured, key) || ticket !== sequences[side]) return
    if (!eligible(entity)) throw new Error('预填对象缺少实际源的关系维护或元数据查看能力')
    if (side === 'source') {
      sourceOptions.value = [entity]
      selectedSource.value = entity
    } else {
      targetOptions.value = [entity]
      selectedTarget.value = entity
    }
  } catch (cause) {
    if (current(captured, key)) error.value = getHttpErrorMessage(cause, '预填对象读取失败')
  }
}
watch(
  () => [props.visible, props.prefilledSourceId, props.prefilledTargetId] as const,
  ([visible, source, target]) => {
    resetForm()
    if (!visible || !canUseDirectory.value) return
    if (source) void applyPrefilledEntity(source, 'source')
    if (target) void applyPrefilledEntity(target, 'target')
  },
  { immediate: true },
)
watch(
  scope,
  () => {
    resetForm()
    emit('update:visible', false)
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  alive = false
  epoch++
})
</script>
<template>
  <el-dialog
    :model-value="visible"
    title="添加血缘关系"
    width="680px"
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    @update:model-value="
      (v: boolean) => {
        if (!submitting) emit('update:visible', v)
      }
    "
    @close="handleClose"
  >
    <p v-if="!canUseDirectory" role="status">
      表搜索与列映射需要实际数据源的 metadata:view 和 lineage:manage 能力；这不会自动补充授权。
    </p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <el-form label-position="top" :disabled="submitting || !canUseDirectory">
      <!-- 血缘类型 -->
      <el-form-item label="血缘类型">
        <el-radio-group v-model="lineageType">
          <el-radio value="ETL">ETL 流转</el-radio>
          <el-radio value="MANUAL">手动标注</el-radio>
        </el-radio-group>
      </el-form-item>

      <!-- 源实体搜索 -->
      <el-form-item label="源实体（上游表）">
        <el-select
          v-model="selectedSource"
          value-key="id"
          placeholder="搜索有权维护的表…"
          filterable
          remote
          :remote-method="(q: string) => searchTables(q, 'source')"
          :loading="sourceLoading"
          style="width: 100%"
          popper-class="lineage-entity-select"
        >
          <el-option
            v-for="item in sourceOptions"
            :key="item.id"
            :label="`${item.name} (${item.fqn || ''})`"
            :value="item"
          />
        </el-select>
      </el-form-item>

      <!-- 目标实体搜索 -->
      <el-form-item label="目标实体（下游表）">
        <el-select
          v-model="selectedTarget"
          value-key="id"
          placeholder="搜索有权维护的表…"
          filterable
          remote
          :remote-method="(q: string) => searchTables(q, 'target')"
          :loading="targetLoading"
          style="width: 100%"
          popper-class="lineage-entity-select"
        >
          <el-option
            v-for="item in targetOptions"
            :key="item.id"
            :label="`${item.name} (${item.fqn || ''})`"
            :value="item"
          />
        </el-select>
      </el-form-item>

      <!-- 描述 -->
      <el-form-item label="描述">
        <el-input
          v-model="description"
          placeholder="例：daily_stats 由 orders 聚合产出"
          maxlength="500"
          show-word-limit
        />
      </el-form-item>

      <!-- 列级映射（可折叠） -->
      <el-divider />
      <div class="mapping-header">
        <span style="font-weight: 600; font-size: 14px">列级映射（可选）</span>
        <el-button type="primary" text :icon="Plus" @click="addMapping">添加列映射</el-button>
      </div>

      <div v-if="mappings.length" class="mapping-list">
        <div v-for="row in mappings" :key="row.id" class="mapping-row">
          <div class="mapping-row__cols">
            <!-- 源列（多选） -->
            <el-select
              v-model="row.fromColumnIds"
              multiple
              placeholder="选择源列"
              style="flex: 1"
              :disabled="!selectedSource"
            >
              <el-option v-for="col in sourceColumnOptions" :key="col.id" :label="col.name" :value="col.id" />
            </el-select>

            <span class="mapping-arrow">→</span>

            <!-- 目标列（单选） -->
            <el-select
              v-model="row.toColumnId"
              placeholder="选择目标列"
              style="flex: 1"
              :disabled="!selectedTarget"
            >
              <el-option v-for="col in targetColumnOptions" :key="col.id" :label="col.name" :value="col.id" />
            </el-select>
          </div>

          <div class="mapping-row__detail">
            <!-- 转换类型 -->
            <el-select v-model="row.transformationType" style="width: 140px" size="small">
              <el-option label="直传 (IDENTITY)" value="IDENTITY" />
              <el-option label="转换 (TRANSFORMATION)" value="TRANSFORMATION" />
              <el-option label="聚合 (AGGREGATION)" value="AGGREGATION" />
            </el-select>

            <!-- 表达式 -->
            <el-input
              v-model="row.expression"
              placeholder="转换表达式，如 SUM(amount * price)"
              size="small"
              style="flex: 1"
            />
          </div>

          <el-button type="danger" text :icon="Trash2" size="small" @click="removeMapping(row)" />
        </div>
      </div>

      <div v-else-if="selectedSource && selectedTarget" class="mapping-empty">
        <span>尚未添加列映射，仅创建表级血缘边</span>
      </div>

      <p v-if="mappings.length && !mappingValid" class="form-error">
        每一行都需选定当前源表的源列及当前目标表的目标列；未完成的映射不会被静默丢弃。
      </p>
      <el-divider />
    </el-form>

    <template #footer>
      <el-button :disabled="submitting" @click="handleClose">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="handleSubmit">
        确认添加
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-error {
  color: var(--do-danger);
  font-size: 13px;
  line-height: 1.8;
}
.mapping-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.mapping-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mapping-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-bg);
}

.mapping-row__cols {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}

.mapping-arrow {
  color: var(--do-muted);
  font-weight: 700;
  font-size: 16px;
  flex-shrink: 0;
}

.mapping-row__detail {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  flex: 1;
}

.mapping-empty {
  padding: 20px;
  text-align: center;
  color: var(--do-muted);
  font-size: 13px;
  border: 1px dashed var(--do-line);
  border-radius: 8px;
}
</style>
