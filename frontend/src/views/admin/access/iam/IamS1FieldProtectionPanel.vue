<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import GovernanceWriteNotice from '../../../../components/admin/GovernanceWriteNotice.vue'
import EmptyState from '../../../../components/common/EmptyState.vue'
import ErrorState from '../../../../components/common/ErrorState.vue'
import LoadingState from '../../../../components/common/LoadingState.vue'
import MaskCandidatePanel from './MaskCandidatePanel.vue'
import { useIamWorkspaceSession } from '../../../../composables/useIamWorkspaceSession'
import { trackGovernanceWrite, pendingGovernanceWrites } from '../../../../composables/governanceWriteTracker'
import {
  listIamS1Columns,
  listIamS1Datasources,
  listIamS1FieldProtections,
  listIamS1Snapshots,
  listIamS1Tables,
  revokeIamS1FieldProtection,
  saveIamS1FieldProtection,
  type IamS1ColumnOption,
  type IamS1FieldProtectionItem,
  type IamS1SnapshotOption,
  type IamS1TableOption,
} from '../../../../api/iamS1'
const props = defineProps<{ defaultDatasourceId?: number; invalidDefaultDatasource?: boolean }>()
const session = useIamWorkspaceSession(),
  iam = session.iam
const datasources = ref<Array<{ id: number; name: string }>>([]),
  datasourceId = ref<number>(),
  snapshots = ref<IamS1SnapshotOption[]>([]),
  snapshotId = ref<number>(),
  tables = ref<IamS1TableOption[]>([]),
  tableName = ref<string>(),
  columns = ref<IamS1ColumnOption[]>([]),
  protections = ref<IamS1FieldProtectionItem[]>([])
const loading = ref(false),
  loadingColumns = ref(false),
  loadingProtections = ref(false),
  error = ref(''),
  columnError = ref(''),
  protectionError = ref(''),
  writeError = ref(''),
  busy = ref(false),
  filter = ref('')
const drafts = reactive<
  Record<number, { level: '' | 'NORMAL' | 'MASKED' | 'HIDDEN'; policy: string; reason: string }>
>({})
const maskPolicies = [
  { label: '手机号掩码', value: 'PHONE' },
  { label: '身份证掩码', value: 'ID_CARD' },
  { label: '邮箱掩码', value: 'EMAIL' },
  { label: '银行卡掩码', value: 'BANK_CARD' },
  { label: '姓名掩码', value: 'NAME' },
]
const canView = computed(
    () => !props.invalidDefaultDatasource && session.can('security:mask:view', datasourceId.value),
  ),
  canManage = computed(() => canView.value && session.can('security:mask:manage', datasourceId.value))
const key = computed(() =>
  JSON.stringify([
    session.key.value,
    datasourceId.value,
    snapshotId.value,
    tableName.value,
    props.invalidDefaultDatasource,
  ]),
)
const sourceKey = computed(() =>
  JSON.stringify([session.key.value, datasourceId.value, props.invalidDefaultDatasource]),
)
const owned = (k: string) => session.alive() && session.ready.value && key.value === k
const identity = () => session.auth.token || ''
const sourceLock = () => `iam-mask:${datasourceId.value}`
const locked = computed(() =>
  pendingGovernanceWrites.value.some((x) => x.identity === identity() && x.keys.includes(sourceLock())),
)
const shownColumns = computed(() =>
  columns.value.filter((x) =>
    `${x.columnName} ${x.columnComment || ''}`.toLowerCase().includes(filter.value.toLowerCase()),
  ),
)
const protectionLabel = (level?: string) =>
  ({ NORMAL: '正常显示', MASKED: '脱敏显示', HIDDEN: '隐藏字段' })[level || ''] || level || '未记录'
let sourceRequest = 0,
  snapshotRequest = 0,
  tableRequest = 0,
  columnRequest = 0,
  protectionRequest = 0,
  operation = 0
function clearDrafts() {
  Object.keys(drafts).forEach((k) => delete drafts[Number(k)])
  writeError.value = ''
  filter.value = ''
  session.cancelOwn()
  busy.value = false
  operation++
}
watch(key, clearDrafts, { flush: 'sync' })
watch(
  sourceKey,
  () => {
    snapshots.value = []
    snapshotId.value = undefined
    tables.value = []
    tableName.value = undefined
    columns.value = []
    protections.value = []
    columnError.value = ''
    protectionError.value = ''
    snapshotRequest++
    tableRequest++
    columnRequest++
    protectionRequest++
    loading.value = false
    loadingColumns.value = false
    loadingProtections.value = false
  },
  { flush: 'sync' },
)
watch(
  snapshotId,
  () => {
    tables.value = []
    tableName.value = undefined
    columns.value = []
    protections.value = []
    tableRequest++
    columnRequest++
    protectionRequest++
    loadingColumns.value = false
    loadingProtections.value = false
  },
  { flush: 'sync' },
)
watch(
  tableName,
  () => {
    columns.value = []
    columnError.value = ''
    columnRequest++
    loadingColumns.value = false
  },
  { flush: 'sync' },
)
async function initialize() {
  const r = ++sourceRequest,
    g = session.capture()
  datasources.value = []
  datasourceId.value = undefined
  error.value = ''
  if (!session.ready.value) return
  loading.value = true
  try {
    const rows = iam.systemAdmin
      ? (await listIamS1Datasources()).data || []
      : iam.datasourceCapabilities.map((x) => ({
          id: x.datasourceId,
          name: x.datasourceName || `源 #${x.datasourceId}`,
        }))
    if (!session.current(g) || r !== sourceRequest) return
    datasources.value = rows.filter((x) => session.can('security:mask:view', x.id))
    if (props.invalidDefaultDatasource) throw new Error('指定数据源标识无效，未选择其他范围。')
    if (props.defaultDatasourceId && !datasources.value.some((x) => x.id === props.defaultDatasourceId))
      throw new Error('指定源没有查看字段保护能力，未选择其他范围。')
    datasourceId.value = props.defaultDatasourceId || datasources.value[0]?.id
    await loadSource()
  } catch (e) {
    if (session.current(g) && r === sourceRequest)
      error.value = e instanceof Error ? e.message : '读取字段保护范围失败'
  } finally {
    if (session.current(g) && r === sourceRequest) loading.value = false
  }
}
async function loadSource() {
  await Promise.all([loadProtections(), loadSnapshots()])
}
async function loadSnapshots() {
  const r = ++snapshotRequest,
    s = sourceKey.value,
    ds = datasourceId.value
  error.value = ''
  if (!canView.value || !ds) return
  if (!session.can('security:permission:view', ds)) {
    error.value =
      '当前角色可读正式保护与候选，但现有字段目录需要同源 security:permission:view；未增加权限，目录维护暂不可用。'
    return
  }
  loading.value = true
  try {
    const result = await listIamS1Snapshots(ds)
    if (!session.alive() || s !== sourceKey.value || r !== snapshotRequest) return
    snapshots.value = (result.data || []).filter((x) => x.datasourceId === ds && x.status === 'PUBLISHED')
    snapshotId.value = snapshots.value[0]?.id
    await changeSnapshot()
  } catch (e) {
    if (session.alive() && s === sourceKey.value && r === snapshotRequest)
      error.value = e instanceof Error ? e.message : '读取发布快照失败'
  } finally {
    if (session.alive() && s === sourceKey.value && r === snapshotRequest) loading.value = false
  }
}
async function changeSnapshot() {
  const ds = datasourceId.value,
    snap = snapshotId.value,
    k = key.value,
    r = ++tableRequest
  error.value = ''
  if (!canView.value || !ds || !snap) return
  loading.value = true
  await Promise.all([
    loadProtections(),
    (async () => {
      try {
        const result = await listIamS1Tables(ds, snap)
        if (!owned(k) || r !== tableRequest) return
        tables.value = (result.data || []).filter(
          (x) => x.datasourceId === ds && (!snap || x.metadataSnapshotId === snap),
        )
      } catch (e) {
        if (owned(k) && r === tableRequest) error.value = e instanceof Error ? e.message : '读取表失败'
      } finally {
        if (owned(k) && r === tableRequest) loading.value = false
      }
    })(),
  ])
}
async function loadColumns() {
  const ds = datasourceId.value,
    snap = snapshotId.value,
    table = tableName.value,
    k = key.value,
    r = ++columnRequest
  columnError.value = ''
  if (!canView.value || !ds || !snap || !table || !tables.value.some((x) => x.tableName === table)) return
  loadingColumns.value = true
  try {
    const result = await listIamS1Columns(ds, snap, table)
    if (!owned(k) || r !== columnRequest) return
    columns.value = result.data || []
    for (const c of columns.value) {
      if (!drafts[c.columnMetaId]) drafts[c.columnMetaId] = { level: '', policy: '', reason: '' }
    }
  } catch (e) {
    if (owned(k) && r === columnRequest) columnError.value = e instanceof Error ? e.message : '读取字段失败'
  } finally {
    if (owned(k) && r === columnRequest) loadingColumns.value = false
  }
}
async function loadProtections() {
  const ds = datasourceId.value,
    snap = snapshotId.value,
    s = sourceKey.value,
    r = ++protectionRequest
  protectionError.value = ''
  if (!canView.value || !ds) return
  loadingProtections.value = true
  try {
    const result = await listIamS1FieldProtections({ datasourceId: ds, snapshotId: snap })
    if (!session.alive() || s !== sourceKey.value || snap !== snapshotId.value || r !== protectionRequest)
      return
    protections.value = (result.data || []).filter(
      (x) => x.datasourceId === ds && (!snap || x.metadataSnapshotId === snap),
    )
  } catch (e) {
    if (session.alive() && s === sourceKey.value && snap === snapshotId.value && r === protectionRequest)
      protectionError.value = e instanceof Error ? e.message : '读取正式保护失败'
  } finally {
    if (session.alive() && s === sourceKey.value && snap === snapshotId.value && r === protectionRequest)
      loadingProtections.value = false
  }
}
async function refreshFacts() {
  await Promise.all([loadProtections(), loadColumns()])
}
async function save(column: IamS1ColumnOption) {
  if (
    busy.value ||
    locked.value ||
    !canManage.value ||
    !columns.value.includes(column) ||
    !datasourceId.value ||
    !snapshotId.value ||
    !tableName.value
  )
    return
  const d = drafts[column.columnMetaId]
  if (
    !d ||
    (d.level !== 'NORMAL' && d.level !== 'MASKED' && d.level !== 'HIDDEN') ||
    (d.level === 'MASKED' && !maskPolicies.some((p) => p.value === d.policy))
  ) {
    writeError.value = '请明确选择保护级别；脱敏显示还须选择已支持的掩码策略。'
    return
  }
  const payload = {
    protocolVersion: 'IAM-SIMPLE-1',
    datasourceId: datasourceId.value,
    metadataSnapshotId: snapshotId.value,
    tableName: tableName.value,
    columnMetaId: column.columnMetaId,
    columnName: column.columnName,
    protectionLevel: d.level,
    maskPolicy: d.level === 'MASKED' ? d.policy : null,
    reason: d.reason.trim() || '界面维护字段保护',
  }
  await mutate(
    `源 #${payload.datasourceId} / 快照 #${payload.metadataSnapshotId} / ${payload.tableName}.${payload.columnName} #${column.columnMetaId} 保存保护`,
    `${column.columnName}（字段 #${column.columnMetaId}），数据源 #${payload.datasourceId} / 快照 #${payload.metadataSnapshotId} / ${payload.tableName}，改为${protectionLabel(d.level)}${d.level === 'MASKED' ? `（${d.policy}）` : ''}。影响所有用户；正常显示或撤销保护仍需独立数据授权。`,
    async () => {
      const result = await saveIamS1FieldProtection(payload)
      if (!Number.isSafeInteger(result.data) || Number(result.data) <= 0)
        throw new Error('保存回执缺少保护ID，结果未知，请核对正式记录')
    },
    () => columns.value.includes(column),
  )
}
async function revoke(item: IamS1FieldProtectionItem) {
  if (
    busy.value ||
    locked.value ||
    !canManage.value ||
    item.status !== 'ACTIVE' ||
    item.datasourceId !== datasourceId.value ||
    (snapshotId.value !== undefined && item.metadataSnapshotId !== snapshotId.value) ||
    !protections.value.includes(item)
  )
    return
  const frozen = { ...item }
  await mutate(
    `源 #${frozen.datasourceId} / 快照 #${frozen.metadataSnapshotId} / ${frozen.tableName}.${frozen.columnName} 撤销保护 #${frozen.id}`,
    `撤销保护 #${frozen.id}：数据源 #${frozen.datasourceId} / 快照 #${frozen.metadataSnapshotId} / ${frozen.tableName}.${frozen.columnName}（字段 #${frozen.columnMetaId}）。影响所有用户，不授予查询权。`,
    () => revokeIamS1FieldProtection(frozen.id, '界面撤销字段保护'),
    () => protections.value.includes(item) && item.status === 'ACTIVE',
  )
}
async function mutate(label: string, text: string, write: () => Promise<unknown>, valid: () => boolean) {
  const k = key.value,
    o = ++operation,
    actor = identity(),
    lock = sourceLock()
  busy.value = true
  writeError.value = ''
  try {
    await ElMessageBox.confirm(text, label, {
      type: 'warning',
      confirmButtonText: '确认变更',
      cancelButtonText: '取消',
      cancelButtonClass: session.cancelClass,
      closeOnClickModal: false,
    })
    if (!owned(k) || o !== operation || !canManage.value || !valid()) return
    await trackGovernanceWrite(actor, [lock], label, write)
    if (!owned(k) || o !== operation) return
    writeError.value = '已提交保护变更；以下按当前对象重读，输入保留。'
    await Promise.all([loadProtections(), loadColumns()])
  } catch (e) {
    if (owned(k) && o === operation && e !== 'cancel' && e !== 'close')
      writeError.value = e instanceof Error ? e.message : '写入失败；输入保留，结果未知时先核对正式记录'
  } finally {
    if (owned(k) && o === operation) busy.value = false
  }
}
watch(
  [session.generation, () => props.defaultDatasourceId, () => props.invalidDefaultDatasource],
  initialize,
  { immediate: true },
)
</script>
<template>
  <div class="iam-s1-protections">
    <GovernanceWriteNotice />
    <section class="panel scope-panel">
      <div>
        <small>独立于数据授权</small>
        <h3>字段如何展示与使用</h3>
        <p>
          维护保护不授予查询权。隐藏字段不进入问数与召回；脱敏字段只作直接展示，不用于筛选、关联、排序或分组。
        </p>
      </div>
      <div class="inline">
        <label>
          管理源
          <el-select v-model="datasourceId" placeholder="选择数据源" @change="loadSource">
            <el-option v-for="s in datasources" :key="s.id" :value="s.id" :label="s.name" />
          </el-select>
        </label>
        <label>
          已发布快照
          <el-select v-model="snapshotId" placeholder="选择发布快照" @change="changeSnapshot">
            <el-option
              v-for="s in snapshots"
              :key="s.id"
              :value="s.id"
              :label="`版本 ${s.snapshotVersion ?? s.id} · #${s.id}`"
            />
          </el-select>
        </label>
        <label>
          精确表
          <el-select v-model="tableName" placeholder="选择表" @change="loadColumns">
            <el-option
              v-for="t in tables"
              :key="t.tableName"
              :value="t.tableName"
              :label="`${t.tableName}${t.tableComment ? ' · ' + t.tableComment : ''}`"
            />
          </el-select>
        </label>
        <el-button @click="refreshFacts">重读当前保护</el-button>
      </div>
    </section>
    <ErrorState v-if="error" :message="error" @retry="initialize" />
    <LoadingState v-else-if="loading" text="正在读取发布范围…" />
    <EmptyState
      v-else-if="!canView"
      message="没有当前源的 security:mask:view；功能与源必须来自同一角色绑定。"
    />
    <template v-if="canView">
      <section class="panel">
        <div class="heading">
          <div>
            <h3>正式字段保护</h3>
            <p>选策略后显式保存，每次确认精确字段；当前展示依据来自本次重读。</p>
          </div>
          <el-input v-model="filter" clearable placeholder="筛选当前表字段" class="filter" />
        </div>
        <p v-if="!canManage" class="notice">仅查看：缺少当前源 security:mask:manage。</p>
        <p v-if="locked" class="notice">
          本源有执行中或结果未知的字段保护操作，请先核对正式记录与候选，不要重复提交。确认真实结果后可整页刷新恢复；刷新本身不证明旧操作失败。
        </p>
        <p v-if="writeError" class="notice" role="status">{{ writeError }}</p>
        <ErrorState v-if="columnError" :message="columnError" @retry="loadColumns" />
        <LoadingState v-else-if="loadingColumns" text="正在读取字段…" />
        <EmptyState v-else-if="!tableName" message="选择精确发布快照与表后维护字段。" />
        <el-table v-else :data="shownColumns">
          <el-table-column label="字段" min-width="180">
            <template #default="{ row }">
              <strong>{{ row.columnComment || row.columnName }}</strong>
              <small>{{ row.columnName }} · #{{ row.columnMetaId }}</small>
            </template>
          </el-table-column>
          <el-table-column prop="dataType" label="类型" width="130" />
          <el-table-column label="当前保护" width="120">
            <template #default="{ row }">{{ protectionLabel(row.protectionLevel) }}</template>
          </el-table-column>
          <el-table-column v-if="canManage" label="拟变更与依据" min-width="450">
            <template #default="{ row }">
              <div v-if="drafts[row.columnMetaId]" class="inline edit">
                <el-select
                  v-model="drafts[row.columnMetaId].level"
                  aria-label="拟变更保护"
                  placeholder="明确选择保护"
                >
                  <el-option label="正常显示" value="NORMAL" />
                  <el-option label="脱敏显示" value="MASKED" />
                  <el-option label="隐藏字段" value="HIDDEN" />
                </el-select>
                <el-select
                  v-if="drafts[row.columnMetaId].level === 'MASKED'"
                  v-model="drafts[row.columnMetaId].policy"
                  aria-label="掩码策略"
                >
                  <el-option v-for="p in maskPolicies" :key="p.value" :label="p.label" :value="p.value" />
                </el-select>
                <el-input v-model="drafts[row.columnMetaId].reason" placeholder="本次保护变更依据" />
                <el-button type="primary" :disabled="locked" :loading="busy" @click="save(row)">
                  保存保护
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </section>
      <section class="panel">
        <h3>{{ snapshotId ? '当前发布快照' : '当前源各快照' }}的正式记录</h3>
        <ErrorState v-if="protectionError" :message="protectionError" @retry="loadProtections" />
        <LoadingState v-else-if="loadingProtections" text="正在读取正式保护记录…" />
        <EmptyState v-else-if="!protections.length" message="本次未返回正式保护记录。" />
        <el-table v-else :data="protections">
          <el-table-column label="保护对象" min-width="240">
            <template #default="{ row }">
              {{ row.tableName }}.{{ row.columnName }}
              <small>
                记录 #{{ row.id }} · 快照 #{{ row.metadataSnapshotId }} · 字段 #{{ row.columnMetaId }}
              </small>
            </template>
          </el-table-column>
          <el-table-column label="保护" min-width="160">
            <template #default="{ row }">
              {{ protectionLabel(row.protectionLevel) }} {{ row.maskPolicyName || row.maskPolicy || '' }}
            </template>
          </el-table-column>
          <el-table-column prop="status" label="记录状态" width="120" />
          <el-table-column prop="updatedAt" label="更新时间" min-width="180" />
          <el-table-column v-if="canManage" label="操作" width="90">
            <template #default="{ row }">
              <el-button
                link
                type="danger"
                :disabled="busy || locked || row.status !== 'ACTIVE'"
                @click="revoke(row)"
              >
                撤销
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
      <MaskCandidatePanel :datasource-id="datasourceId" @changed="refreshFacts" />
    </template>
  </div>
</template>
<style scoped>
.iam-s1-protections {
  display: grid;
  gap: 20px;
}
.panel {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  min-width: 0;
}
.panel h3 {
  margin: 0 0 8px;
  font-size: 16px;
}
.panel p,
.panel small {
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}
.panel small {
  display: block;
}
.scope-panel > div:first-child > small {
  color: var(--do-primary-strong);
}
.inline,
.heading {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.heading {
  justify-content: space-between;
  margin-bottom: 16px;
}
.inline label {
  display: grid;
  gap: 6px;
  color: var(--do-muted);
  font-size: 12px;
  flex: 1;
  min-width: 180px;
  max-width: 300px;
}
.inline label .el-select {
  width: 100%;
}
.edit .el-select {
  width: 130px;
}
.edit .el-input {
  width: 190px;
}
.filter {
  width: 260px;
}
.notice {
  padding: 10px;
  background: var(--do-bg);
  border-left: 3px solid var(--do-primary);
}
@media (max-width: 720px) {
  .panel {
    padding: 14px;
  }
  .inline label {
    max-width: none;
  }
  .filter {
    width: 100%;
  }
}
</style>
