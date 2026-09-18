<script setup lang="ts">
/**
 * IAM-SIMPLE-1 数据授权面板
 *
 * 一次说明“谁能查哪些数据”：对象、数据范围、有效期/生效范围三项必选。
 * 字段未选不代表全部字段；记录条件只接受“字段 + 条件 + 值”的结构化选择，不提供手写 SQL 入口。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EmptyState from '../../../../components/common/EmptyState.vue'
import {
  createIamS1DataGrant,
  listIamS1Columns,
  listIamS1DataGrants,
  listIamS1Datasources,
  listIamS1GrantTemplates,
  listIamS1Snapshots,
  listIamS1Subjects,
  listIamS1Tables,
  revokeIamS1DataGrant,
  type IamS1ColumnOption,
  type IamS1DataGrant,
  type IamS1DatasourceRef,
  type IamS1GrantTemplate,
  type IamS1RowConditionPayload,
  type IamS1SubjectOption,
  type IamS1TableOption,
} from '../../../../api/iamS1'

const props = defineProps<{
  /** 进入工作区时默认选中的数据源（来自侧栏上下文） */
  defaultDatasourceId?: number
}>()

const loading = ref(false)
const datasources = ref<IamS1DatasourceRef[]>([])
const datasourceId = ref<number>()
const snapshots = ref<Array<{ id: number; snapshotVersion?: number }>>([])
const snapshotId = ref<number>()
const tables = ref<IamS1TableOption[]>([])
const columns = ref<IamS1ColumnOption[]>([])
const grants = ref<IamS1DataGrant[]>([])
const subjects = ref<IamS1SubjectOption[]>([])
const templates = ref<IamS1GrantTemplate[]>([])
const templateCode = ref<string>()
const denyMode = ref(false)
const saving = ref(false)

const form = reactive({
  subjectType: 'DEPARTMENT' as 'USER' | 'ROLE' | 'DEPARTMENT',
  subjectId: undefined as number | undefined,
  departmentScope: 'SELF' as 'SELF' | 'INCLUDE_DESCENDANTS',
  tableName: undefined as string | undefined,
  selectedColumns: [] as number[],
  longTerm: true,
  validUntil: '' as string,
  matchType: 'ALL' as 'ALL' | 'ANY',
  conditions: [] as Array<{ columnName: string; operatorCode: string; value: string }>,
  reason: '' as string,
})

const selectableColumns = computed(() => columns.value.filter((item) => item.selectable))
const columnNameOf = (columnMetaId: number) =>
  columns.value.find((item) => item.columnMetaId === columnMetaId)?.columnName ?? String(columnMetaId)

/** 保存前始终展示完整中文摘要，让默认值可见。 */
const summaryText = computed(() => {
  const subject = subjects.value.find((item) => item.id === form.subjectId)
  const datasource = datasources.value.find((item) => item.id === datasourceId.value)
  const subjectLabel = subject
    ? subject.name + (form.subjectType === 'DEPARTMENT' && form.departmentScope === 'INCLUDE_DESCENDANTS' ? '及下级部门' : '')
    : '（尚未选择对象）'
  const target = form.tableName ? `${datasource?.name ?? '该数据源'} 的 ${form.tableName} 表` : '（尚未选择表和字段）'
  const columns = form.selectedColumns.map(columnNameOf)
  const effectText = denyMode.value ? '禁止' : '允许'
  const validity = form.longTerm ? '长期有效' : form.validUntil ? `有效期至 ${form.validUntil}` : '请选择结束时间'
  const conditions = denyMode.value || !form.conditions.length
    ? '全部记录'
    : `只看 ${form.conditions.map((item) => `${item.columnName} ${item.operatorCode} ${item.value}`).join('，且 ')} 的记录`
  return `${effectText}${subjectLabel}查询${target}，字段：${columns.length ? columns.join('、') : '（尚未选择字段）'}，${conditions}，${validity}`
})

const missingItems = computed(() => {
  const missing: string[] = []
  if (!form.subjectId) missing.push('授权给谁（对象）')
  if (!datasourceId.value) missing.push('哪个数据源（生效范围）')
  if (!form.tableName || !form.selectedColumns.length) missing.push('哪些表和字段（数据范围）')
  if (!form.longTerm && !form.validUntil) missing.push('有效期（生效范围）')
  return missing
})

async function loadDatasources() {
  const result = await listIamS1Datasources()
  datasources.value = result.data ?? []
  if (!datasourceId.value) {
    const preferred = datasources.value.find((item) => item.id === props.defaultDatasourceId)
    datasourceId.value = preferred?.id ?? datasources.value[0]?.id
  }
}

async function loadSubjects() {
  const result = await listIamS1Subjects()
  subjects.value = result.data ?? []
}

async function loadTemplates() {
  const result = await listIamS1GrantTemplates()
  templates.value = result.data ?? []
}

/** 后端字段是 LocalDateTime：必须发送不带时区后缀的本地时间，避免 toISOString 的 Z 后缀被拒。 */
function formatLocalDateTime(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** 日期选择器使用 `YYYY-MM-DD HH:mm:ss`，转成后端可解析的本地时间字符串。 */
function formatPickerValue(date: Date): string {
  return formatLocalDateTime(date).replace('T', ' ')
}

function applyTemplate(code: string | undefined) {
  const template = templates.value.find((item) => item.code === code)
  if (!template) return
  form.subjectType = template.subjectType
  form.subjectId = undefined
  form.departmentScope = template.departmentScope ?? 'SELF'
  if (template.validDays) {
    form.longTerm = false
    const until = new Date()
    until.setDate(until.getDate() + template.validDays)
    form.validUntil = formatPickerValue(until)
  } else {
    form.longTerm = true
    form.validUntil = ''
  }
  ElMessage.info(`已套用模板：${template.name}`)
}

async function loadSnapshots() {
  snapshotId.value = undefined
  tables.value = []
  columns.value = []
  if (!datasourceId.value) return
  const result = await listIamS1Snapshots(datasourceId.value)
  snapshots.value = result.data ?? []
  snapshotId.value = snapshots.value[0]?.id
}

async function loadTables() {
  form.tableName = undefined
  form.selectedColumns = []
  columns.value = []
  if (!datasourceId.value || !snapshotId.value) return
  const result = await listIamS1Tables(datasourceId.value, snapshotId.value)
  tables.value = result.data ?? []
}

async function loadColumns() {
  form.selectedColumns = []
  columns.value = []
  form.conditions = []
  if (!datasourceId.value || !snapshotId.value || !form.tableName) return
  const result = await listIamS1Columns(datasourceId.value, snapshotId.value, form.tableName)
  columns.value = result.data ?? []
}

async function loadGrants() {
  if (!datasourceId.value) return
  loading.value = true
  try {
    const result = await listIamS1DataGrants({ datasourceId: datasourceId.value })
    grants.value = result.data ?? []
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取数据授权失败')
  } finally {
    loading.value = false
  }
}

function addCondition() {
  const first = selectableColumns.value[0]
  form.conditions.push({ columnName: first?.columnName ?? '', operatorCode: 'EQ', value: '' })
}

function inferValueType(columnName: string, value: string): string {
  const column = columns.value.find((item) => item.columnName === columnName)
  const dataType = (column?.dataType ?? '').toUpperCase()
  const numeric = /INT|DECIMAL|NUMERIC|DOUBLE|FLOAT|REAL/.test(dataType)
  const booleanType = /BOOL|BIT/.test(dataType)
  const dateType = /DATE/.test(dataType)
  const timeType = /TIME/.test(dataType)
  if (timeType) return 'DATETIME'
  if (dateType) return 'DATE'
  if (booleanType) return 'BOOLEAN'
  if (numeric) return /^[-+]?\d+$/.test(value.trim()) ? 'INTEGER' : 'DECIMAL'
  return 'STRING'
}

function buildConditions(): IamS1RowConditionPayload[] {
  return form.conditions
    .filter((item) => item.columnName)
    .map((item) => {
      const column = columns.value.find((entry) => entry.columnName === item.columnName)
      const nullOperator = item.operatorCode === 'IS_NULL' || item.operatorCode === 'IS_NOT_NULL'
      const collection = item.operatorCode === 'IN' || item.operatorCode === 'NOT_IN'
      const scalarType = nullOperator ? 'NULL' : inferValueType(item.columnName, item.value)
      const valueType = collection ? `${scalarType}_LIST` : scalarType
      const values = item.value
        .split(',')
        .map((part) => part.trim())
        .filter(Boolean)
      let structuredValueJson: string | null = null
      if (!nullOperator) {
        if (collection) {
          const typed = values.map((value) => {
            if (scalarType === 'INTEGER') return Number(value)
            if (scalarType === 'DECIMAL') return Number(value)
            if (scalarType === 'BOOLEAN') return value === 'true'
            return value
          })
          structuredValueJson = JSON.stringify(typed)
        } else if (scalarType === 'INTEGER' || scalarType === 'DECIMAL') {
          structuredValueJson = JSON.stringify(Number(values[0]))
        } else if (scalarType === 'BOOLEAN') {
          structuredValueJson = JSON.stringify(values[0] === 'true')
        } else {
          structuredValueJson = JSON.stringify(values[0] ?? '')
        }
      }
      return {
        columnMetaId: column?.columnMetaId ?? 0,
        columnName: item.columnName,
        operatorCode: item.operatorCode,
        valueType,
        structuredValueJson,
      }
    })
}

async function save() {
  if (missingItems.value.length) {
    ElMessage.warning(`请先补齐必选项：${missingItems.value.join('、')}`)
    return
  }
  if (denyMode.value && form.conditions.length) {
    ElMessage.warning('“禁止查询”不支持记录级条件，请选择整个数据源或指定的表')
    return
  }
  if (denyMode.value && form.selectedColumns.length) {
    ElMessage.warning('“禁止查询”只支持整个数据源或整张表的字段，不支持仅禁止部分字段')
    return
  }
  saving.value = true
  try {
    const columnsPayload = form.selectedColumns.map((columnMetaId) => ({
      columnMetaId,
      columnName: columnNameOf(columnMetaId),
    }))
    await createIamS1DataGrant({
      protocolVersion: 'IAM-SIMPLE-1',
      subjectType: form.subjectType,
      subjectId: form.subjectId!,
      departmentScope: form.subjectType === 'DEPARTMENT' ? form.departmentScope : null,
      datasourceId: datasourceId.value!,
      resourceScope: form.tableName ? 'TABLE' : 'DATASOURCE',
      metadataSnapshotId: form.tableName ? snapshotId.value ?? null : null,
      tableName: form.tableName ?? null,
      effect: denyMode.value ? 'DENY' : 'ALLOW',
      validFrom: formatLocalDateTime(new Date()),
      validUntil: form.longTerm ? null : form.validUntil.replace(' ', 'T'),
      rowMatchType: form.matchType,
      columns: denyMode.value ? [] : columnsPayload,
      rowConditions: denyMode.value ? [] : buildConditions(),
      reason: form.reason || undefined,
    })
    ElMessage.success('数据授权已保存')
    form.conditions = []
    form.reason = ''
    await loadGrants()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存数据授权失败')
  } finally {
    saving.value = false
  }
}

async function revoke(grant: IamS1DataGrant) {
  try {
    await revokeIamS1DataGrant(grant.id, '界面撤销')
    ElMessage.success('数据授权已撤销')
    await loadGrants()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '撤销数据授权失败')
  }
}

onMounted(async () => {
  try {
    await Promise.all([loadDatasources(), loadSubjects(), loadTemplates()])
    await loadSnapshots()
    await loadTables()
    await loadGrants()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '初始化授权配置失败')
  }
})
</script>

<template>
  <div class="iam-s1-grants">
    <section class="panel">
      <h3 class="panel-title">配置数据授权</h3>
      <p class="panel-hint">
        一次说明“谁能查哪些数据”。字段未选不代表全部字段；记录条件只能用“字段 + 条件 + 值”，不提供手写 SQL。
      </p>

      <div class="grid">
        <label class="field">
          <span class="label">常用模板（可选）</span>
          <el-select v-model="templateCode" placeholder="选择一个常见场景" clearable @change="applyTemplate">
            <el-option v-for="item in templates" :key="item.code" :label="item.name" :value="item.code" />
          </el-select>
          <small v-if="templateCode" class="hint">
            {{ templates.find((item) => item.code === templateCode)?.description }}
          </small>
        </label>

        <label class="field">
          <span class="label">授权给谁（必选）</span>
          <div class="inline">
            <el-select v-model="form.subjectType" class="w120" @change="form.subjectId = undefined">
              <el-option label="用户" value="USER" />
              <el-option label="角色" value="ROLE" />
              <el-option label="部门" value="DEPARTMENT" />
            </el-select>
            <el-select v-model="form.subjectId" filterable placeholder="选择对象" class="flex1">
              <el-option
                v-for="item in subjects.filter((entry) => entry.subjectType === form.subjectType)"
                :key="`${item.subjectType}-${item.id}`"
                :label="item.name"
                :value="item.id"
              />
            </el-select>
          </div>
          <small v-if="form.subjectType === 'DEPARTMENT'" class="hint">
            <el-radio-group v-model="form.departmentScope" size="small">
              <el-radio-button value="SELF">仅本部门</el-radio-button>
              <el-radio-button value="INCLUDE_DESCENDANTS">包含下级部门</el-radio-button>
            </el-radio-group>
          </small>
        </label>

        <label class="field">
          <span class="label">哪个数据源（必选）</span>
          <el-select v-model="datasourceId" placeholder="选择数据源" @change="loadSnapshots().then(loadTables).then(loadGrants)">
            <el-option v-for="item in datasources" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </label>

        <label class="field">
          <span class="label">已发布元数据快照</span>
          <el-select v-model="snapshotId" placeholder="选择快照" @change="loadTables">
            <el-option
              v-for="item in snapshots"
              :key="item.id"
              :label="`版本 ${item.snapshotVersion ?? item.id}（已发布）`"
              :value="item.id"
            />
          </el-select>
        </label>

        <label class="field">
          <span class="label">哪些表（必选，留空表示整个数据源，仅用于禁止查询）</span>
          <el-select v-model="form.tableName" placeholder="选择表" clearable @change="loadColumns">
            <el-option
              v-for="item in tables"
              :key="item.tableName"
              :label="`${item.tableName}${item.tableComment ? '（' + item.tableComment + '）' : ''}`"
              :value="item.tableName"
              :disabled="!item.selectable"
            />
          </el-select>
        </label>

        <label class="field">
          <span class="label">哪些字段（必选，至少一个）</span>
          <el-select v-model="form.selectedColumns" multiple filterable placeholder="必须明确选择字段" :disabled="denyMode">
            <el-option
              v-for="item in selectableColumns"
              :key="item.columnMetaId"
              :label="`${item.columnComment || item.columnName}${item.protectionLevel === 'MASKED' ? '（脱敏显示）' : ''}`"
              :value="item.columnMetaId"
            />
          </el-select>
          <small v-if="form.tableName && !selectableColumns.length" class="hint warn">
            该表没有可选字段：隐藏字段或治理状态不允许的字段不能授权。
          </small>
        </label>

        <label class="field">
          <span class="label">限制记录（默认全部记录）</span>
          <div class="inline">
            <el-select v-model="form.matchType" class="w120" :disabled="denyMode">
              <el-option label="全部满足" value="ALL" />
              <el-option label="任一满足" value="ANY" />
            </el-select>
            <el-button size="small" :disabled="denyMode || !form.tableName" @click="addCondition">添加记录条件</el-button>
          </div>
          <div v-for="(condition, index) in form.conditions" :key="index" class="inline condition-row">
            <el-select v-model="condition.columnName" class="flex1" placeholder="字段">
              <el-option
                v-for="item in selectableColumns"
                :key="item.columnMetaId"
                :label="item.columnComment || item.columnName"
                :value="item.columnName"
              />
            </el-select>
            <el-select v-model="condition.operatorCode" class="w140">
              <el-option label="等于" value="EQ" />
              <el-option label="不等于" value="NE" />
              <el-option label="大于" value="GT" />
              <el-option label="大于等于" value="GE" />
              <el-option label="小于" value="LT" />
              <el-option label="小于等于" value="LE" />
              <el-option label="属于" value="IN" />
              <el-option label="不属于" value="NOT_IN" />
              <el-option label="为空" value="IS_NULL" />
              <el-option label="不为空" value="IS_NOT_NULL" />
            </el-select>
            <el-input
              v-model="condition.value"
              class="flex1"
              placeholder="值（属于/不属于用逗号分隔）"
              :disabled="condition.operatorCode === 'IS_NULL' || condition.operatorCode === 'IS_NOT_NULL'"
            />
            <el-button size="small" link type="danger" @click="form.conditions.splice(index, 1)">移除</el-button>
          </div>
        </label>

        <label class="field">
          <span class="label">有效期（必选）</span>
          <div class="inline">
            <el-checkbox v-model="form.longTerm">长期有效</el-checkbox>
            <el-date-picker
              v-if="!form.longTerm"
              v-model="form.validUntil"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              placeholder="选择结束时间"
            />
          </div>
        </label>

        <label class="field">
          <span class="label">允许 / 禁止</span>
          <el-switch
            v-model="denyMode"
            active-text="禁止查询"
            inactive-text="允许查询"
            inline-prompt
          />
          <small class="hint">禁止优先；禁止规则不支持记录级条件和部分字段。</small>
        </label>

        <label class="field">
          <span class="label">变更理由（可选）</span>
          <el-input v-model="form.reason" placeholder="例如：销售部新增订单分析需求" />
        </label>
      </div>

      <div class="summary">
        <strong>保存前完整摘要：</strong>
        <span>{{ summaryText }}</span>
      </div>
      <p v-if="missingItems.length" class="hint warn">还缺：{{ missingItems.join('、') }}</p>
      <el-button type="primary" :loading="saving" @click="save">保存数据授权</el-button>
    </section>

    <section class="panel">
      <h3 class="panel-title">当前数据源的授权（{{ grants.length }}）</h3>
      <EmptyState
        v-if="!grants.length"
        message="还没有数据授权。请先选择部门或用户，并添加可查询的表和字段。"
      />
      <el-table v-else v-loading="loading" :data="grants" size="small">
        <el-table-column prop="summary" label="授权摘要" min-width="360" />
        <el-table-column prop="subjectTypeName" label="对象类型" width="100" />
        <el-table-column prop="grantSource" label="来源" width="120">
          <template #default="{ row }">{{ row.grantSource === 'APPROVAL' ? '访问审批通过' : '管理员配置' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">{{ row.status === 'ACTIVE' ? '生效中' : '已撤销' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'ACTIVE'"
              link
              type="danger"
              @click="revoke(row)"
            >撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.iam-s1-grants {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.panel {
  border: 1px solid var(--do-line);
  border-radius: 10px;
  padding: 18px;
  background: var(--do-surface);
}
.panel-title {
  margin: 0 0 6px;
  font-size: 15px;
}
.panel-hint {
  margin: 0 0 14px;
  color: var(--do-muted);
  font-size: 13px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 14px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.label {
  font-size: 13px;
  color: var(--do-text-secondary, var(--do-muted));
}
.inline {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.condition-row {
  margin-top: 6px;
}
.flex1 {
  flex: 1;
}
.w120 {
  width: 120px;
}
.w140 {
  width: 140px;
}
.hint {
  color: var(--do-muted);
  font-size: 12px;
}
.hint.warn {
  color: #d48806;
}
.summary {
  margin: 16px 0 12px;
  padding: 12px;
  border-radius: 8px;
  background: var(--do-surface-muted, #f6f7f9);
  font-size: 13px;
  line-height: 1.6;
}
</style>
