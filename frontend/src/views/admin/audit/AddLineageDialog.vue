<script setup lang="ts">
/**
 * 血缘录入弹窗
 *
 * 参考 OpenMetadata AddLineageRequest + DataHub 手动编辑交互。
 * 支持：源/目标表搜索（仅 TABLE 类型）、列级映射（源列多选+目标列单选）、
 * 转换类型（IDENTITY/TRANSFORMATION/AGGREGATION）、表达式输入。
 * 与文档 data-lineage-research.md §4.1.3 功能一 完全一致。
 */
import { ref, watch, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Trash2 } from 'lucide-vue-next'
import { searchCatalog, type MetadataEntityItem } from '../../../api/admin/catalog'
import { createLineage, type LineageCreateRequest, type ColumnMappingItem } from '../../../api/admin/lineageApi'

const props = defineProps<{
  visible: boolean
  /** 预填源实体 ID（从图谱右键菜单传入） */
  prefilledSourceId?: number | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'created'): void
}>()

// 表单数据
const lineageType = ref<'ETL' | 'MANUAL'>('ETL')
const description = ref('')
const submitting = ref(false)

// 源实体选择
const sourceSearch = ref('')
const sourceOptions = ref<MetadataEntityItem[]>([])
const sourceLoading = ref(false)
const selectedSource = ref<MetadataEntityItem | null>(null)

// 目标实体选择
const targetSearch = ref('')
const targetOptions = ref<MetadataEntityItem[]>([])
const targetLoading = ref(false)
const selectedTarget = ref<MetadataEntityItem | null>(null)

// 列映射条目列表
interface MappingRow {
  id: number
  fromColumnIds: number[]
  fromColumnNames: string[]
  toColumnId: number | null
  toColumnName: string
  expression: string
  transformationType: 'IDENTITY' | 'TRANSFORMATION' | 'AGGREGATION'
}
let mappingIdCounter = 0
const mappings = ref<MappingRow[]>([])

// 源列/目标列下拉选项
const sourceColumnOptions = ref<MetadataEntityItem[]>([])
const targetColumnOptions = ref<MetadataEntityItem[]>([])

/** 搜索表（仅 TABLE 类型） */
async function searchTables(query: string, target: 'source' | 'target') {
  if (!query || query.length < 1) {
    if (target === 'source') sourceOptions.value = []
    else targetOptions.value = []
    return
  }
  if (target === 'source') sourceLoading.value = true
  else targetLoading.value = true
  try {
    const res = await searchCatalog({ q: query, type: 'TABLE', page: 1, size: 10 })
    const tables = (res.data ?? []).filter((e) => e.entityType === 'TABLE')
    if (target === 'source') sourceOptions.value = tables
    else targetOptions.value = tables
  } catch {
    ElMessage.error('搜索表失败')
  } finally {
    if (target === 'source') sourceLoading.value = false
    else targetLoading.value = false
  }
}

/** 选中源表后加载其下属列 */
async function loadSourceColumns() {
  try {
    // 通过搜索该表 FQN 前缀来获取列列表
    const entity = selectedSource.value
    if (!entity) return
    const res = await searchCatalog({ q: entity.fqn, type: 'COLUMN', page: 1, size: 500 })
    sourceColumnOptions.value = (res.data ?? []).filter((e) =>
      e.entityType === 'COLUMN' && e.fqn.startsWith(entity.fqn + '.'),
    )
  } catch {
    sourceColumnOptions.value = []
  }
}

/** 选中目标表后加载其下属列 */
async function loadTargetColumns() {
  try {
    const entity = selectedTarget.value
    if (!entity) return
    const res = await searchCatalog({ q: entity.fqn, type: 'COLUMN', page: 1, size: 500 })
    targetColumnOptions.value = (res.data ?? []).filter((e) =>
      e.entityType === 'COLUMN' && e.fqn.startsWith(entity.fqn + '.'),
    )
  } catch {
    targetColumnOptions.value = []
  }
}

watch(selectedSource, (val) => {
  if (val) loadSourceColumns()
  else sourceColumnOptions.value = []
})

watch(selectedTarget, (val) => {
  if (val) loadTargetColumns()
  else targetColumnOptions.value = []
})

/** 添加一行列映射 */
function addMapping() {
  mappings.value.push({
    id: ++mappingIdCounter,
    fromColumnIds: [],
    fromColumnNames: [],
    toColumnId: null,
    toColumnName: '',
    expression: '',
    transformationType: 'IDENTITY',
  })
}

/** 删除一行列映射 */
function removeMapping(row: MappingRow) {
  const idx = mappings.value.findIndex((m) => m.id === row.id)
  if (idx >= 0) mappings.value.splice(idx, 1)
}

/** 检查表单是否可提交 */
const canSubmit = computed(() => {
  if (!selectedSource.value || !selectedTarget.value) return false
  if (selectedSource.value.id === selectedTarget.value.id) return false
  return true
})

/** 提交 */
async function handleSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const body: LineageCreateRequest = {
      sourceEntityId: selectedSource.value!.id,
      targetEntityId: selectedTarget.value!.id,
      lineageType: lineageType.value,
      description: description.value || undefined,
      columnMappings:
        mappings.value.length > 0
          ? mappings.value
              .filter((m) => m.fromColumnIds.length > 0 && m.toColumnId)
              .map(
                (m): ColumnMappingItem => ({
                  fromColumns: m.fromColumnIds,
                  toColumn: m.toColumnId!,
                  expression: m.expression || undefined,
                  transformationType: m.transformationType,
                }),
              )
          : undefined,
    }
    await createLineage(body)
    ElMessage.success('血缘关系创建成功')
    emit('created')
    resetForm()
    emit('update:visible', false)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '创建失败')
  } finally {
    submitting.value = false
  }
}

/** 重置表单 */
function resetForm() {
  lineageType.value = 'ETL'
  description.value = ''
  sourceSearch.value = ''
  selectedSource.value = null
  sourceOptions.value = []
  targetSearch.value = ''
  selectedTarget.value = null
  targetOptions.value = []
  mappings.value = []
  sourceColumnOptions.value = []
  targetColumnOptions.value = []
}

function handleClose() {
  resetForm()
  emit('update:visible', false)
}

// 监听预填源实体
watch(
  () => props.prefilledSourceId,
  (val) => {
    if (val && props.visible) {
      // TODO: 通过 ID 加载实体并设置 selectedSource
    }
  },
)
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="添加血缘关系"
    width="680px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @close="handleClose"
  >
    <el-form label-position="top" :disabled="submitting">
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
          placeholder="🔍 搜索表..."
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
          placeholder="🔍 搜索表..."
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
        <span style="font-weight: 600; font-size: 14px;">列级映射（可选）</span>
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
              <el-option
                v-for="col in sourceColumnOptions"
                :key="col.id"
                :label="col.name"
                :value="col.id"
              />
            </el-select>

            <span class="mapping-arrow">→</span>

            <!-- 目标列（单选） -->
            <el-select
              v-model="row.toColumnId"
              placeholder="选择目标列"
              style="flex: 1"
              :disabled="!selectedTarget"
            >
              <el-option
                v-for="col in targetColumnOptions"
                :key="col.id"
                :label="col.name"
                :value="col.id"
              />
            </el-select>
          </div>

          <div class="mapping-row__detail">
            <!-- 转换类型 -->
            <el-select
              v-model="row.transformationType"
              style="width: 140px"
              size="small"
            >
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

          <el-button
            type="danger"
            text
            :icon="Trash2"
            size="small"
            @click="removeMapping(row)"
          />
        </div>
      </div>

      <div v-else-if="selectedSource && selectedTarget" class="mapping-empty">
        <span>尚未添加列映射，仅创建表级血缘边</span>
      </div>

      <el-divider />
    </el-form>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="handleSubmit">
        确认添加
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
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
