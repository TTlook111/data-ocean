<script setup lang="ts">
/**
 * IAM-SIMPLE-1 数据授权面板
 *
 * 一次说明“谁能查哪些数据”：对象、数据范围、有效期/生效范围三项必选。
 * 字段未选不代表全部字段；记录条件只接受“字段 + 条件 + 值”的结构化选择，不提供手写 SQL 入口。
 */
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '../../../../components/common/EmptyState.vue'
import { useIamWorkspaceSession } from '../../../../composables/useIamWorkspaceSession'
import { trackGovernanceWrite } from '../../../../composables/governanceWriteTracker'
import GovernanceWriteNotice from '../../../../components/admin/GovernanceWriteNotice.vue'
import { useAdminScrollLock } from '../../../../composables/useAdminScrollLock'
import { getHttpErrorMessage } from '../../../../utils/httpError'
import { useIamS1Store } from '../../../../stores/iamS1'
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
  invalidDefaultDatasource?: boolean
}>()

const iamS1 = useIamS1Store(),
  session = useIamWorkspaceSession()
const reading = reactive({
  subjects: false,
  templates: false,
  snapshots: false,
  tables: false,
  columns: false,
})
const editorOpen = ref(false),
  readError = ref(''),
  editorError = ref(''),
  helperError = ref(''),
  confirmOpen = ref(false)
const grantKeyword = ref(''),
  grantsPage = ref(1),
  listStatus = ref('ALL')
let initRequest = 0,
  sourceRequest = 0,
  subjectRequest = 0,
  snapshotRequest = 0,
  tableRequest = 0,
  columnRequest = 0,
  listRequest = 0,
  templateRequest = 0,
  operation = 0,
  formSession = 0
const VIEW = 'security:permission:view',
  MANAGE = 'security:permission:manage'
const canView = computed(() => !props.invalidDefaultDatasource && session.can(VIEW, datasourceId.value)),
  canManage = computed(() => canView.value && session.can(MANAGE, datasourceId.value))
const scopeKey = computed(() => `${session.key.value}|${datasourceId.value}`)
function current(key: string) {
  return session.alive() && session.ready.value && key === scopeKey.value
}
useAdminScrollLock(() => confirmOpen.value)

const loading = ref(false)
const datasources = ref<Array<Pick<IamS1DatasourceRef, 'id' | 'name'>>>([])
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
  const subject = subjects.value.find(
    (item) => item.id === form.subjectId && item.subjectType === form.subjectType,
  )
  const datasource = datasources.value.find((item) => item.id === datasourceId.value)
  const subjectLabel = subject
    ? subject.name +
      (form.subjectType === 'DEPARTMENT' && form.departmentScope === 'INCLUDE_DESCENDANTS'
        ? '及下级部门'
        : '')
    : '（尚未选择对象）'
  const target = form.tableName
    ? `${datasource?.name ?? '该数据源'} 的 ${form.tableName} 表`
    : denyMode.value
      ? `${datasource?.name ?? '该数据源'} 的所有表`
      : '（尚未选择表和字段）'
  const columns = form.selectedColumns.map(columnNameOf)
  const effectText = denyMode.value ? '禁止' : '允许'
  const validity = form.longTerm
    ? '长期有效'
    : form.validUntil
      ? `有效期至 ${form.validUntil}`
      : '请选择结束时间'
  const conditions =
    denyMode.value || !form.conditions.length
      ? '全部记录'
      : `只看 ${form.conditions.map((item) => `${item.columnName} ${item.operatorCode} ${item.value}`).join(form.matchType === 'ANY' ? '，或 ' : '，且 ')} 的记录`
  return `${effectText}${subjectLabel}查询${target}，字段：${denyMode.value ? '全部字段' : columns.length ? columns.join('、') : '（尚未选择字段）'}，${conditions}，${validity}`
})

const missingItems = computed(() => {
  const missing: string[] = []
  if (!form.subjectId) missing.push('授权给谁（对象）')
  if (!datasourceId.value) missing.push('哪个数据源（生效范围）')
  if (!denyMode.value && (!form.tableName || !form.selectedColumns.length))
    missing.push('哪些表和字段（数据范围）')
  if (form.tableName && !snapshotId.value) missing.push('表所属的已发布快照')
  if (!form.longTerm && !form.validUntil) missing.push('有效期（生效范围）')
  return missing
})

const filteredGrants = computed(() =>
  grants.value.filter(
    (g) =>
      (listStatus.value === 'ALL' || g.status === listStatus.value) &&
      (!grantKeyword.value.trim() ||
        `${g.summary} ${g.subjectName || ''} ${g.tableName || ''}`
          .toLowerCase()
          .includes(grantKeyword.value.trim().toLowerCase())),
  ),
)
const visibleGrants = computed(() =>
  filteredGrants.value.slice((grantsPage.value - 1) * 20, grantsPage.value * 20),
)
watch([grantKeyword, listStatus], () => {
  grantsPage.value = 1
})
async function loadDatasources() {
  const g = session.capture(),
    request = ++sourceRequest
  if (!session.canGlobal(VIEW)) {
    datasources.value = []
    return
  }
  const rows = iamS1.systemAdmin
    ? ((await listIamS1Datasources()).data ?? [])
    : iamS1.datasourceCapabilities.map((d) => ({ id: d.datasourceId, name: d.datasourceName }))
  if (!session.current(g) || request !== sourceRequest) return
  datasources.value = rows.filter((d) => session.can(VIEW, d.id))
  if (props.invalidDefaultDatasource) throw new Error('指定数据源标识无效，未选择其他范围。')
  const preferred = props.defaultDatasourceId
  if (preferred !== undefined && !datasources.value.some((d) => d.id === preferred))
    throw new Error('指定源没有同绑定的授权查看能力，未选择其他范围。')
  datasourceId.value =
    preferred ?? datasources.value.find((d) => d.id === datasourceId.value)?.id ?? datasources.value[0]?.id
}
async function loadSubjects() {
  const request = ++subjectRequest,
    key = scopeKey.value,
    source = datasourceId.value
  subjects.value = []
  if (!source || !canView.value) return
  reading.subjects = true
  try {
    const result = await listIamS1Subjects('GRANT', source)
    if (current(key) && request === subjectRequest) subjects.value = result.data ?? []
  } catch (cause) {
    if (current(key) && request === subjectRequest)
      helperError.value = getHttpErrorMessage(cause, '授权对象读取失败，请重试；不要求用户目录额外权限')
  } finally {
    if (current(key) && request === subjectRequest) reading.subjects = false
  }
}
async function loadTemplates() {
  const request = ++templateRequest,
    key = scopeKey.value
  templates.value = []
  if (!session.canGlobal(VIEW)) return
  reading.templates = true
  try {
    const result = await listIamS1GrantTemplates()
    if (current(key) && request === templateRequest) templates.value = result.data ?? []
  } catch (cause) {
    if (current(key) && request === templateRequest)
      helperError.value = getHttpErrorMessage(cause, '模板不可用，仍可人工填写')
  } finally {
    if (current(key) && request === templateRequest) reading.templates = false
  }
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
  const request = ++snapshotRequest,
    key = scopeKey.value,
    source = datasourceId.value
  snapshotId.value = undefined
  snapshots.value = []
  tables.value = []
  columns.value = []
  form.tableName = undefined
  form.selectedColumns = []
  form.conditions = []
  tableRequest++
  columnRequest++
  if (!source || !canView.value) return
  reading.snapshots = true
  try {
    const result = await listIamS1Snapshots(source)
    if (!current(key) || request !== snapshotRequest) return
    snapshots.value = result.data ?? []
    snapshotId.value = snapshots.value[0]?.id
    await loadTables()
  } catch (cause) {
    if (current(key) && request === snapshotRequest)
      helperError.value = getHttpErrorMessage(
        cause,
        '发布快照读取失败，不能配置表级授权；源级禁止仍按其自身条件处理',
      )
  } finally {
    if (current(key) && request === snapshotRequest) reading.snapshots = false
  }
}
async function loadTables() {
  const request = ++tableRequest,
    key = scopeKey.value,
    source = datasourceId.value,
    snapshot = snapshotId.value
  form.tableName = undefined
  form.selectedColumns = []
  form.conditions = []
  tables.value = []
  columns.value = []
  columnRequest++
  if (!source || !snapshot || !canView.value) return
  reading.tables = true
  try {
    const result = await listIamS1Tables(source, snapshot)
    if (current(key) && request === tableRequest && snapshot === snapshotId.value)
      tables.value = result.data ?? []
  } catch (cause) {
    if (current(key) && request === tableRequest)
      helperError.value = getHttpErrorMessage(cause, '表目录读取失败，不能用空目录覆盖已提交授权')
  } finally {
    if (current(key) && request === tableRequest) reading.tables = false
  }
}
async function loadColumns() {
  const request = ++columnRequest,
    key = scopeKey.value,
    source = datasourceId.value,
    snapshot = snapshotId.value,
    table = form.tableName
  form.selectedColumns = []
  form.conditions = []
  columns.value = []
  if (!source || !snapshot || !table || !canView.value) return
  reading.columns = true
  try {
    const result = await listIamS1Columns(source, snapshot, table)
    if (
      current(key) &&
      request === columnRequest &&
      snapshot === snapshotId.value &&
      table === form.tableName
    )
      columns.value = result.data ?? []
  } catch (cause) {
    if (current(key) && request === columnRequest)
      helperError.value = getHttpErrorMessage(cause, '字段读取失败，请重试')
  } finally {
    if (current(key) && request === columnRequest) reading.columns = false
  }
}
async function loadGrants() {
  const request = ++listRequest,
    key = scopeKey.value,
    source = datasourceId.value
  grants.value = []
  readError.value = ''
  loading.value = false
  grantsPage.value = 1
  if (!source || !canView.value) return
  loading.value = true
  try {
    const result = await listIamS1DataGrants({ datasourceId: source })
    if (current(key) && request === listRequest) grants.value = result.data ?? []
  } catch (cause) {
    if (current(key) && request === listRequest)
      readError.value = getHttpErrorMessage(cause, '数据授权读取失败，不能判断为空')
  } finally {
    if (current(key) && request === listRequest) loading.value = false
  }
}
function openEditor() {
  if (!canManage.value || saving.value || editorOpen.value) return
  formSession++
  editorOpen.value = true
  editorError.value = ''
  helperError.value = ''
  if (!subjects.value.length) void loadSubjects()
  if (!templates.value.length) void loadTemplates()
  if (!snapshotId.value) void loadSnapshots()
  else if (!tables.value.length) void loadTables()
  else if (form.tableName && !columns.value.length) void loadColumns()
}
function addCondition() {
  const first = selectableColumns.value[0]
  form.conditions.push({ columnName: first?.columnName ?? '', operatorCode: 'EQ', value: '' })
}

/**
 * 条件值类型必须**只由列的元数据类型决定**，不能看“用户填的值像什么”。
 *
 * 反例：DECIMAL(10,2) 的金额列填 `100`，按“值像整数”会被推成 INTEGER，
 * 而后端 `IamS1RowConditionValidator.ensureTypeCompatible` 对 INTEGER 只接受
 * 含 INT / YEAR / BIT 的类型，`DECIMAL(10,2)` 不在其中 → 保存必然失败，
 * 而界面没有让用户改值类型的入口。
 *
 * 规则与后端 ensureTypeCompatible 的分支逐一对应，改动必须两侧同步。
 */
function inferValueType(columnName: string): string {
  const column = columns.value.find((item) => item.columnName === columnName)
  const dataType = (column?.dataType ?? '').toUpperCase()
  if (/BOOL|BIT/.test(dataType)) return 'BOOLEAN'
  if (/INT|YEAR/.test(dataType)) return 'INTEGER'
  if (/DECIMAL|NUMERIC|DOUBLE|FLOAT|REAL/.test(dataType)) return 'DECIMAL'
  // TIME 必须排在 DATE 之前：DATETIME / TIMESTAMP 同时含两者。
  if (/TIME/.test(dataType)) return 'DATETIME'
  if (/DATE/.test(dataType)) return 'DATE'
  return 'STRING'
}

function buildConditions(): IamS1RowConditionPayload[] {
  return form.conditions
    .filter((item) => item.columnName)
    .map((item) => {
      const column = columns.value.find((entry) => entry.columnName === item.columnName)
      const nullOperator = item.operatorCode === 'IS_NULL' || item.operatorCode === 'IS_NOT_NULL'
      const collection = item.operatorCode === 'IN' || item.operatorCode === 'NOT_IN'
      const scalarType = nullOperator ? 'NULL' : inferValueType(item.columnName)
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
          structuredValueJson = JSON.stringify(Number(item.value.trim()))
        } else if (scalarType === 'BOOLEAN') {
          structuredValueJson = JSON.stringify(item.value.trim() === 'true')
        } else {
          structuredValueJson = JSON.stringify(item.value.trim())
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

function validateConditions() {
  for (const c of form.conditions) {
    if (!selectableColumns.value.some((x) => x.columnName === c.columnName))
      throw new Error('记录条件必须选择当前表的可用字段')
    if (
      !['EQ', 'NE', 'GT', 'GE', 'LT', 'LE', 'IN', 'NOT_IN', 'IS_NULL', 'IS_NOT_NULL'].includes(c.operatorCode)
    )
      throw new Error('记录条件运算符无效')
    if (['IS_NULL', 'IS_NOT_NULL'].includes(c.operatorCode)) continue
    const type = inferValueType(c.columnName),
      parts = ['IN', 'NOT_IN'].includes(c.operatorCode)
        ? c.value.split(',').map((s) => s.trim())
        : [c.value.trim()]
    if (!parts.length || parts.some((x) => !x)) throw new Error('请填写完整条件值，不能将空值转换为0')
    if (
      (type === 'INTEGER' || type === 'DECIMAL') &&
      parts.some(
        (x) =>
          canonicalNumber(x) === null ||
          canonicalNumber(x) !== canonicalNumber(String(Number(x))) ||
          (type === 'INTEGER' && !Number.isSafeInteger(Number(x))),
      )
    )
      throw new Error('数值格式或精度无法无损表达，原值未提交；不会自动舍入或接受十六进制')
    if (
      (type === 'INTEGER' || type === 'DECIMAL') &&
      parts.some((x) => !Number.isFinite(Number(x)) || (type === 'INTEGER' && !Number.isInteger(Number(x))))
    )
      throw new Error('数值条件格式不正确，请修正原输入')
    if (type === 'BOOLEAN' && parts.some((x) => x !== 'true' && x !== 'false'))
      throw new Error('布尔条件只能填写true或false，不会把其他文字静默转成false')
  }
}
async function save() {
  if (saving.value || !editorOpen.value || !canManage.value) return
  if (missingItems.value.length) {
    editorError.value = `请先补齐：${missingItems.value.join('、')}`
    return
  }
  if (!subjects.value.some((x) => x.id === form.subjectId && x.subjectType === form.subjectType)) {
    editorError.value = '授权对象不属于当前源的合法选项，请重选'
    return
  }
  if (form.tableName && !tables.value.some((x) => x.tableName === form.tableName && x.selectable)) {
    editorError.value = '表未核验或不可授权'
    return
  }
  if (
    !denyMode.value &&
    form.selectedColumns.some((id) => !selectableColumns.value.some((c) => c.columnMetaId === id))
  ) {
    editorError.value = '包含不可授权的字段，请核对当前快照'
    return
  }
  try {
    if (!denyMode.value) validateConditions()
  } catch (cause) {
    editorError.value = cause instanceof Error ? cause.message : '条件错误'
    return
  }
  const key = scopeKey.value,
    sessionId = formSession,
    op = ++operation,
    identity = session.auth.token
  const body = {
    protocolVersion: 'IAM-SIMPLE-1',
    subjectType: form.subjectType,
    subjectId: form.subjectId!,
    departmentScope: form.subjectType === 'DEPARTMENT' ? form.departmentScope : null,
    datasourceId: datasourceId.value!,
    resourceScope: form.tableName ? ('TABLE' as const) : ('DATASOURCE' as const),
    metadataSnapshotId: form.tableName ? (snapshotId.value ?? null) : null,
    tableName: form.tableName ?? null,
    effect: denyMode.value ? ('DENY' as const) : ('ALLOW' as const),
    validFrom: formatLocalDateTime(new Date()),
    validUntil: form.longTerm ? null : form.validUntil.replace(' ', 'T'),
    rowMatchType: form.matchType,
    columns: denyMode.value
      ? []
      : form.selectedColumns.map((id) => ({ columnMetaId: id, columnName: columnNameOf(id) })),
    rowConditions: denyMode.value ? [] : buildConditions(),
    reason: form.reason || undefined,
  }
  const summary = summaryText.value,
    signature = JSON.stringify([form, denyMode.value, snapshotId.value])
  const owned = () => current(key) && sessionId === formSession && op === operation && canManage.value
  saving.value = true
  editorError.value = ''
  try {
    if (body.effect === 'DENY') {
      confirmOpen.value = true
      await ElMessageBox.confirm(
        `数据源 #${body.datasourceId}；对象 ${body.subjectType} #${body.subjectId}；${body.tableName ? `表 ${body.tableName} / 快照 #${body.metadataSnapshotId}` : '整个数据源（不绑定快照）'}。${summary}。禁止优先，将影响后续问数。`,
        '确认禁止范围',
        {
          type: 'warning',
          confirmButtonText: '确认保存禁止',
          cancelButtonText: '取消',
          cancelButtonClass: session.cancelClass,
          closeOnClickModal:false,
        },
      )
      if (!owned()) return
    }
    if (!owned()) return
    await trackGovernanceWrite(
      identity,
      [`iam-grants:${body.datasourceId}:${body.subjectType}:${body.subjectId}`],
      `数据授权 · ${summary}`,
      async () => {
        const result = await createIamS1DataGrant(body)
        if (!Number.isSafeInteger(result.data) || result.data <= 0)
          throw new Error('创建回执缺少有效授权ID，结果未知，请重读原对象授权，勿重复提交')
        return result
      },
    )
    if (!owned()) return
    ElMessage.success('授权记录已保存，实际查询仍由当前权限服务重新计算')
    if (signature === JSON.stringify([form, denyMode.value, snapshotId.value])) {
      editorOpen.value = false
      resetForm()
    } else editorError.value = '原输入已保存，新输入仍保留；请核对授权记录再继续'
    await loadGrants()
  } catch (cause) {
    if (owned() && cause !== 'cancel' && cause !== 'close')
      editorError.value = getHttpErrorMessage(
        cause,
        cause instanceof Error ? cause.message : '保存未确认完成，原草稿保留',
      )
  } finally {
    if (op === operation) {
      saving.value = false
      confirmOpen.value = false
    }
  }
}
async function revoke(grant: IamS1DataGrant) {
  if (
    saving.value ||
    !canManage.value ||
    grant.datasourceId !== datasourceId.value ||
    !grants.value.some((g) => g.id === grant.id && g.status === 'ACTIVE')
  )
    return
  const target = JSON.parse(JSON.stringify(grant)) as IamS1DataGrant,
    key = scopeKey.value,
    op = ++operation,
    identity = session.auth.token
  const owned = () => current(key) && op === operation && session.can(MANAGE, target.datasourceId)
  saving.value = true
  confirmOpen.value = true
  try {
    await ElMessageBox.confirm(
      `撤销授权 #${target.id}：${target.summary}。数据源 #${target.datasourceId}，对象 ${target.subjectType} #${target.subjectId}；撤销后需按当前规则重新授权。`,
      '确认撤销授权',
      {
        type: 'warning',
        confirmButtonText: '确认撤销',
        cancelButtonText: '取消',
        cancelButtonClass: session.cancelClass,
          closeOnClickModal:false,
      },
    )
    if (!owned()) return
    await trackGovernanceWrite(
      identity,
      [
        `iam-grants:${target.datasourceId}:${target.subjectType}:${target.subjectId}`,
        `iam-grant:${target.id}`,
      ],
      `撤销授权 #${target.id}`,
      () => revokeIamS1DataGrant(target.id, '界面撤销'),
    )
    if (owned()) await loadGrants()
  } catch (cause) {
    if (owned() && cause !== 'cancel' && cause !== 'close')
      readError.value = getHttpErrorMessage(
        cause,
        cause instanceof Error ? cause.message : '撤销未确认完成，请核对原授权',
      )
  } finally {
    if (op === operation) {
      saving.value = false
      confirmOpen.value = false
    }
  }
}
async function initialize() {
  const request = ++initRequest,
    g = session.capture()
  readError.value = ''
  datasources.value = []
  datasourceId.value = undefined
  if (!session.canGlobal(VIEW)) return
  try {
    await loadDatasources()
    if (!session.current(g) || request !== initRequest) return
    await loadGrants()
  } catch (cause) {
    if (session.current(g) && request === initRequest)
      readError.value = getHttpErrorMessage(cause, '源范围读取失败')
  }
}
function resetForm() {
  templateCode.value = undefined
  Object.assign(form, {
    subjectType: 'DEPARTMENT',
    subjectId: undefined,
    departmentScope: 'SELF',
    tableName: undefined,
    selectedColumns: [],
    longTerm: true,
    validUntil: '',
    matchType: 'ALL',
    conditions: [],
    reason: '',
  })
  denyMode.value = false
  columns.value = []
}
function canonicalNumber(text: string) {
  const match = text.match(/^(-?)(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/)
  if (!match) return null
  let digits = (match[2]! + (match[3] || '')).replace(/^0+/, ''),
    exponent = Number(match[4] || 0) - (match[3]?.length || 0)
  if (!digits) return '0'
  while (digits.endsWith('0')) {
    digits = digits.slice(0, -1)
    exponent++
  }
  return `${match[1]}${digits}e${exponent}`
}
function resetSource() {
  subjectRequest++
  snapshotRequest++
  tableRequest++
  columnRequest++
  listRequest++
  templateRequest++
  operation++
  formSession++
  session.cancelOwn()
  saving.value = false
  confirmOpen.value = false
  editorOpen.value = false
  subjects.value = []
  snapshots.value = []
  snapshotId.value = undefined
  tables.value = []
  columns.value = []
  grants.value = []
  loading.value = false
  form.subjectId = undefined
  form.tableName = undefined
  form.selectedColumns = []
  form.conditions = []
  editorError.value = ''
  helperError.value = ''
  readError.value = ''
  Object.keys(reading).forEach((k) => (reading[k as keyof typeof reading] = false))
  resetForm()
}
function closeEditor() {
  if (saving.value) return
  formSession++
  subjectRequest++
  snapshotRequest++
  tableRequest++
  columnRequest++
  templateRequest++
  editorOpen.value = false
  Object.keys(reading).forEach((k) => (reading[k as keyof typeof reading] = false))
}
watch(datasourceId, resetSource, { flush: 'sync' })
async function refresh() {
  if (!session.ready.value) {
    await iamS1.load(true)
    if (session.ready.value) await initialize()
  } else if (datasourceId.value && canView.value) await loadGrants()
  else await initialize()
}
async function onDatasourceChange() {
  await loadGrants()
}
watch(
  session.generation,
  () => {
    initRequest++
    operation++
    formSession++
    session.cancelOwn()
    editorOpen.value = false
    saving.value = false
    confirmOpen.value = false
    grants.value = []
    subjects.value = []
    snapshots.value = []
    tables.value = []
    columns.value = []
    form.subjectId = undefined
    form.selectedColumns = []
    form.conditions = []
    loading.value = false
    void initialize()
  },
  { immediate: true, flush: 'sync' },
)
watch(
  () => [props.defaultDatasourceId, props.invalidDefaultDatasource],
  () => {
    resetSource()
    void initialize()
  },
  { flush: 'sync' },
)
onMounted(async () => {
  await iamS1.load(Boolean(iamS1.errorMessage))
  if (session.ready.value && !datasourceId.value) void initialize()
})
onBeforeUnmount(() => {
  initRequest++
  sourceRequest++
  subjectRequest++
  snapshotRequest++
  tableRequest++
  columnRequest++
  listRequest++
  templateRequest++
  operation++
  formSession++
  session.cancelOwn()
})
</script>

<template>
  <div class="iam-s1-grants">
    <GovernanceWriteNotice />
    <div class="grants-toolbar">
      <label>
        当前管理源
        <el-select
          v-model="datasourceId"
          aria-label="数据授权管理源"
          placeholder="选择同绑定有权源"
          @change="onDatasourceChange"
        >
          <el-option v-for="d in datasources" :key="d.id" :value="d.id" :label="d.name" />
        </el-select>
      </label>
      <div>
        <el-button :loading="loading" @click="refresh">刷新授权</el-button>
        <el-button type="primary" :disabled="!canManage || saving" @click="openEditor">
          新增数据授权
        </el-button>
      </div>
    </div>
    <p v-if="!canView && !readError" role="status">
      没有当前源的授权查看能力；功能和负责源必须来自同一角色绑定。
    </p>
    <section v-if="editorOpen" class="panel grant-editor">
      <div class="grant-editor-heading">
        <h3>新增一条数据授权</h3>
        <el-button :disabled="saving" @click="closeEditor">关闭配置</el-button>
      </div>
      <p v-if="editorError" role="alert">{{ editorError }}</p>
      <p v-if="helperError" role="status">{{ helperError }}</p>
      <p v-if="Object.values(reading).some(Boolean)" role="status">正在核对授权对象、模板与已发布资源…</p>
      <h3 class="panel-title">配置数据授权</h3>
      <p class="panel-hint">
        一次说明“谁能查哪些数据”。字段未选不代表全部字段；记录条件只能用“字段 + 条件 + 值”，不提供手写 SQL。
      </p>

      <el-form :disabled="saving || !canManage">
        <div class="grid">
          <label class="field">
            <span class="label">常用模板（可选）</span>
            <el-select
              v-model="templateCode"
              placeholder="选择一个常见场景"
              clearable
              @change="applyTemplate"
            >
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
            <span class="label">
              {{ denyMode ? '禁止范围：留空覆盖本源全部表，或选择一张表' : '哪些表（必选）' }}
            </span>
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

          <label v-if="!denyMode" class="field">
            <span class="label">哪些字段（必选，至少一个）</span>
            <el-select
              v-model="form.selectedColumns"
              multiple
              filterable
              placeholder="必须明确选择字段"
              :disabled="denyMode"
            >
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

          <label v-if="!denyMode" class="field">
            <span class="label">限制记录（默认全部记录）</span>
            <div class="inline">
              <el-select v-model="form.matchType" class="w120" :disabled="denyMode">
                <el-option label="全部满足" value="ALL" />
                <el-option label="任一满足" value="ANY" />
              </el-select>
              <el-button size="small" :disabled="denyMode || !form.tableName" @click="addCondition">
                添加记录条件
              </el-button>
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
              <el-button size="small" link type="danger" @click="form.conditions.splice(index, 1)">
                移除
              </el-button>
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
            <el-switch v-model="denyMode" active-text="禁止查询" inactive-text="允许查询" inline-prompt />
            <small class="hint">禁止优先；禁止规则不支持记录级条件和部分字段。</small>
          </label>

          <label class="field">
            <span class="label">变更理由（可选）</span>
            <el-input v-model="form.reason" placeholder="例如：销售部新增订单分析需求" />
          </label>
        </div>
      </el-form>
      <div class="summary">
        <strong>保存前完整摘要：</strong>
        <span>{{ summaryText }}</span>
      </div>
      <p v-if="missingItems.length" class="hint warn">还缺：{{ missingItems.join('、') }}</p>
      <el-button type="primary" :loading="saving" :disabled="!canManage" @click="save">
        保存数据授权
      </el-button>
    </section>

    <section class="panel">
      <h3 class="panel-title">
        已返回的授权记录
        <small>{{ loading ? '正在读取' : readError ? '未完成核对' : grants.length + ' 条' }}</small>
      </h3>
      <div class="grant-list-filters">
        <el-input v-model="grantKeyword" clearable placeholder="搜索摘要、对象或表" />
        <el-select v-model="listStatus">
          <el-option label="全部状态" value="ALL" />
          <el-option label="启用记录" value="ACTIVE" />
          <el-option label="已撤销" value="REVOKED" />
        </el-select>
      </div>
      <p v-if="readError" role="alert">
        {{ readError }}
        <el-button @click="refresh">重读原范围</el-button>
      </p>
      <p v-else-if="loading" role="status">正在读取当前源的授权，不能判断为空…</p>
      <EmptyState
        v-else-if="!filteredGrants.length && canView"
        :message="
          grants.length
            ? '没有符合本地筛选的记录。'
            : '当前源未返回授权记录；查看失败与无权不会显示为零记录。'
        "
      />
      <el-table v-else-if="canView" :data="visibleGrants" size="small">
        <el-table-column prop="summary" label="授权摘要" min-width="360" />
        <el-table-column prop="subjectTypeName" label="对象类型" width="100" />
        <el-table-column prop="grantSource" label="来源" width="120">
          <template #default="{ row }">
            {{ row.grantSource === 'APPROVAL' ? '访问审批通过' : '管理员配置' }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            {{
              row.status === 'ACTIVE'
                ? '启用记录'
                : row.status === 'REVOKED'
                  ? '已撤销'
                  : row.status || '未提供'
            }}
          </template>
        </el-table-column>
        <el-table-column label="结束时间" min-width="165">
          <template #default="{ row }">{{ row.validUntil || '长期；仍受当前权限保护' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'ACTIVE'"
              link
              type="danger"
              :disabled="!canManage || saving"
              @click="revoke(row)"
            >
              撤销
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="filteredGrants.length > 20"
        v-model:current-page="grantsPage"
        :total="filteredGrants.length"
        :page-size="20"
        layout="total,prev,pager,next"
      />
      <p class="hint">
        筛选和分页仅针对本次接口返回的记录；授权不包含问数、SQL或导出功能开关，也不赋予后台负责源。
      </p>
    </section>
  </div>
</template>

<style scoped>
.grants-toolbar,
.grant-editor-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.grants-toolbar > label {
  display: grid;
  gap: 6px;
  font-size: 12px;
  color: var(--do-muted);
  width: min(320px, 100%);
}
.grant-list-filters {
  display: flex;
  gap: 10px;
  margin: 12px 0;
}
.grant-list-filters > .el-input {
  max-width: 360px;
}
.grant-list-filters > .el-select {
  width: 150px;
}
.grant-editor-heading h3 {
  font-size: 17px;
  margin: 0;
}
.panel-title small {
  font-size: 12px;
  color: var(--do-muted);
  font-weight: 400;
  margin-left: 6px;
}
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
  grid-template-columns: repeat(auto-fit, minmax(min(320px, 100%), 1fr));
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
  color: var(--do-warning);
}
.summary {
  margin: 16px 0 12px;
  padding: 12px;
  border-radius: 8px;
  background: var(--do-soft);
  font-size: 13px;
  line-height: 1.6;
}
</style>
