<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import EmptyState from '../../../components/common/EmptyState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import GovernanceWriteNotice from '../../../components/admin/GovernanceWriteNotice.vue'
import { useIamWorkspaceSession } from '../../../composables/useIamWorkspaceSession'
import { trackGovernanceWrite, pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
import {
  listIamS1AccessRequestQueue,
  listIamS1MyAccessRequests,
  listIamS1QueryResourceColumns,
  listIamS1QueryResourceDatasources,
  listIamS1QueryResourceSnapshots,
  listIamS1QueryResourceTables,
  reviewIamS1AccessRequest,
  submitIamS1AccessRequest,
  withdrawIamS1AccessRequest,
  type IamS1AccessRequestView,
  type IamS1ColumnOption,
  type IamS1DatasourceRef,
  type IamS1SnapshotOption,
  type IamS1TableOption,
} from '../../../api/iamS1'
const session = useIamWorkspaceSession(),
  iam = session.iam
const selfId = computed(() => session.auth.currentUser?.id ?? session.auth.user?.userId),
  actorKey = computed(() => JSON.stringify([session.key.value, selfId.value]))
const activeTab = ref<'mine' | 'pending' | 'handled'>('mine'),
  editorOpen = ref(false),
  loadingResources = ref(false),
  resourceError = ref(''),
  mineError = ref(''),
  loadingMine = ref(false),
  writeError = ref(''),
  submitting = ref(false),
  reviewing = ref(false)
const datasources = ref<IamS1DatasourceRef[]>([]),
  snapshots = ref<IamS1SnapshotOption[]>([]),
  tables = ref<IamS1TableOption[]>([]),
  columns = ref<IamS1ColumnOption[]>([]),
  mine = ref<IamS1AccessRequestView[]>([])
const queue = reactive({
  pending: { rows: [] as IamS1AccessRequestView[], page: 1, total: 0, loading: false, error: '' },
  handled: { rows: [] as IamS1AccessRequestView[], page: 1, total: 0, loading: false, error: '' },
})
const form = reactive({
  datasourceId: undefined as number | undefined,
  snapshotId: undefined as number | undefined,
  tableName: undefined as string | undefined,
  columns: [] as string[],
  purpose: '',
  validUntil: '',
})
const reviewTarget = ref<IamS1AccessRequestView>(),
  reviewDraft = reactive({
    decision: 'APPROVE' as 'APPROVE' | 'REJECT',
    columns: [] as string[],
    validUntil: '',
    reason: '',
  })
const canSubmit = computed(() => session.ready.value && iam.queryUse),
  canViewQueue = computed(() => session.canGlobal('security:approval:view'))
const identity = () => session.auth.token || '',
  owned = (k: string) => session.alive() && session.ready.value && actorKey.value === k
const resourceKey = computed(() =>
  JSON.stringify([actorKey.value, form.datasourceId, form.snapshotId, form.tableName]),
)
const resourceOwned = (k: string) => session.alive() && session.ready.value && resourceKey.value === k
const requestLock = (id: number) => `iam-request:${id}`
const submitLock = computed(() => `iam-request-new:${selfId.value}:${form.datasourceId}`)
const locked = (key: string) =>
  pendingGovernanceWrites.value.some((x) => x.identity === identity() && x.keys.includes(key))
const currentRows = computed(() => (activeTab.value === 'mine' ? mine.value : queue[activeTab.value].rows))
const currentError = computed(() =>
  activeTab.value === 'mine' ? mineError.value : queue[activeTab.value].error,
)
const currentLoading = computed(() =>
  activeTab.value === 'mine' ? loadingMine.value : queue[activeTab.value].loading,
)
let sourceRequest = 0,
  resourceBusyOwner = 0,
  snapshotRequest = 0,
  tableRequest = 0,
  columnRequest = 0,
  mineRequest = 0,
  pendingRequest = 0,
  handledRequest = 0,
  submitOperation = 0,
  withdrawOperation = 0,
  reviewEpoch = 0
function canReviewOn(row: IamS1AccessRequestView) {
  return (
    canViewQueue.value &&
    !!selfId.value &&
    row.requesterId !== selfId.value &&
    row.status === 'PENDING' &&
    session.can('security:approval:review', row.datasourceId)
  )
}
function scopeText(row: IamS1AccessRequestView) {
  return `申请 #${row.id} / 申请人 #${row.requesterId} / 源 #${row.datasourceId} / 快照 #${row.metadataSnapshotId} / ${row.tableName} / 字段 ${row.requestedColumns.join('、')}`
}
function closeReview() {
  reviewEpoch++
  reviewTarget.value = undefined
  session.cancelOwn()
  reviewing.value = false
}
watch(
  () => form.datasourceId,
  () => {
    snapshotRequest++
    tableRequest++
    columnRequest++
    form.snapshotId = undefined
    form.tableName = undefined
    form.columns = []
    snapshots.value = []
    tables.value = []
    columns.value = []
    resourceBusyOwner++
    loadingResources.value = false
    resourceError.value = ''
    submitOperation++
    submitting.value = false
    session.cancelOwn()
  },
  { flush: 'sync' },
)
watch(
  () => form.snapshotId,
  () => {
    submitOperation++
    submitting.value = false
    tableRequest++
    columnRequest++
    form.tableName = undefined
    form.columns = []
    tables.value = []
    columns.value = []
    resourceBusyOwner++
    loadingResources.value = false
  },
  { flush: 'sync' },
)
watch(
  () => form.tableName,
  () => {
    submitOperation++
    submitting.value = false
    columnRequest++
    form.columns = []
    columns.value = []
    resourceBusyOwner++
    loadingResources.value = false
  },
  { flush: 'sync' },
)
async function initialize() {
  sourceRequest++
  snapshotRequest++
  tableRequest++
  columnRequest++
  mineRequest++
  pendingRequest++
  handledRequest++
  submitOperation++
  withdrawOperation++
  closeReview()
  datasources.value = []
  form.datasourceId = undefined
  form.purpose = ''
  form.validUntil = ''
  editorOpen.value = false
  mine.value = []
  mineError.value = ''
  resourceError.value = ''
  writeError.value = ''
  loadingMine.value = false
  submitting.value = false
  for (const q of Object.values(queue)) {
    q.rows = []
    q.page = 1
    q.total = 0
    q.loading = false
    q.error = ''
  }
  if (!session.ready.value) return
  activeTab.value = canSubmit.value ? 'mine' : 'pending'
  await loadCurrent()
}
async function loadCurrent() {
  if (activeTab.value === 'mine') await loadMine()
  else await loadQueue(activeTab.value)
}
async function loadMine() {
  const r = ++mineRequest,
    k = actorKey.value
  mine.value = []
  mineError.value = ''
  if (!canSubmit.value) return
  loadingMine.value = true
  try {
    const result = await listIamS1MyAccessRequests()
    if (!owned(k) || r !== mineRequest) return
    const rows = result.data || []
    if (rows.some((x) => x.requesterId !== selfId.value)) throw new Error('本人申请回执混入其他账号，未展示')
    mine.value = rows
  } catch (e) {
    if (owned(k) && r === mineRequest) mineError.value = e instanceof Error ? e.message : '读取本人申请失败'
  } finally {
    if (owned(k) && r === mineRequest) loadingMine.value = false
  }
}
async function loadQueue(kind: 'pending' | 'handled') {
  const q = queue[kind],
    page = q.page,
    k = actorKey.value,
    r = kind === 'pending' ? ++pendingRequest : ++handledRequest
  q.rows = []
  q.error = ''
  if (!canViewQueue.value) return
  q.loading = true
  try {
    const result = await listIamS1AccessRequestQueue(kind === 'pending' ? 'PENDING' : 'HANDLED', page, 20)
    if (!owned(k) || page !== q.page || r !== (kind === 'pending' ? pendingRequest : handledRequest)) return
    const data = result.data
    if (!data || !Array.isArray(data.records) || !Number.isSafeInteger(data.total) || data.total < 0)
      throw new Error('审批分页回执不完整')
    q.rows = data.records
    q.total = data.total
  } catch (e) {
    if (owned(k) && page === q.page && r === (kind === 'pending' ? pendingRequest : handledRequest))
      q.error = e instanceof Error ? e.message : '读取审批队列失败'
  } finally {
    if (owned(k) && page === q.page && r === (kind === 'pending' ? pendingRequest : handledRequest))
      q.loading = false
  }
}
function selectTab() {
  closeReview()
  void loadCurrent()
}
function changePage(page: number) {
  if (activeTab.value === 'mine') return
  closeReview()
  queue[activeTab.value].page = page
  void loadQueue(activeTab.value)
}
async function openEditor() {
  if (!canSubmit.value || editorOpen.value) return
  editorOpen.value = true
  if (!datasources.value.length) await loadDatasources()
}
async function loadDatasources() {
  const r = ++sourceRequest,
    k = actorKey.value
  resourceError.value = ''
  if (!canSubmit.value) return
  const busyOwner = ++resourceBusyOwner
  loadingResources.value = true
  try {
    const result = await listIamS1QueryResourceDatasources('APPLY')
    if (!owned(k) || r !== sourceRequest) return
    datasources.value = result.data || []
    form.datasourceId = datasources.value[0]?.id
    await loadSnapshots()
  } catch (e) {
    if (owned(k) && r === sourceRequest)
      resourceError.value = e instanceof Error ? e.message : '读取申请资源失败'
  } finally {
    if (owned(k) && r === sourceRequest && busyOwner === resourceBusyOwner) loadingResources.value = false
  }
}
async function loadSnapshots() {
  const ds = form.datasourceId,
    k = resourceKey.value,
    r = ++snapshotRequest
  resourceError.value = ''
  if (!canSubmit.value || !ds || !datasources.value.some((x) => x.id === ds)) return
  const busyOwner = ++resourceBusyOwner
  loadingResources.value = true
  try {
    const result = await listIamS1QueryResourceSnapshots(ds, 'APPLY')
    if (!resourceOwned(k) || r !== snapshotRequest) return
    snapshots.value = (result.data || []).filter((x) => x.datasourceId === ds && x.status === 'PUBLISHED')
    form.snapshotId = snapshots.value[0]?.id
    await loadTables()
  } catch (e) {
    if (resourceOwned(k) && r === snapshotRequest)
      resourceError.value = e instanceof Error ? e.message : '读取申请快照失败'
  } finally {
    if (
      session.alive() &&
      actorKey.value === JSON.parse(k)[0] &&
      ds === form.datasourceId &&
      r === snapshotRequest &&
      busyOwner === resourceBusyOwner
    ) {
      loadingResources.value = false
    }
  }
}
async function loadTables() {
  const ds = form.datasourceId,
    snap = form.snapshotId,
    k = resourceKey.value,
    r = ++tableRequest
  if (!canSubmit.value || !ds || !snap) return
  resourceError.value = ''
  const busyOwner = ++resourceBusyOwner
  loadingResources.value = true
  try {
    const result = await listIamS1QueryResourceTables(ds, snap, 'APPLY')
    if (!resourceOwned(k) || r !== tableRequest) return
    tables.value = (result.data || []).filter((x) => x.datasourceId === ds && x.metadataSnapshotId === snap)
  } catch (e) {
    if (resourceOwned(k) && r === tableRequest)
      resourceError.value = e instanceof Error ? e.message : '读取申请表失败'
  } finally {
    if (resourceOwned(k) && r === tableRequest && busyOwner === resourceBusyOwner)
      loadingResources.value = false
  }
}
async function loadColumns() {
  const ds = form.datasourceId,
    snap = form.snapshotId,
    table = form.tableName,
    k = resourceKey.value,
    r = ++columnRequest
  if (
    !canSubmit.value ||
    !ds ||
    !snap ||
    !table ||
    !tables.value.some((x) => x.tableName === table && x.selectable)
  )
    return
  resourceError.value = ''
  const busyOwner = ++resourceBusyOwner
  loadingResources.value = true
  try {
    const result = await listIamS1QueryResourceColumns(ds, snap, table, 'APPLY')
    if (!resourceOwned(k) || r !== columnRequest) return
    columns.value = result.data || []
  } catch (e) {
    if (resourceOwned(k) && r === columnRequest)
      resourceError.value = e instanceof Error ? e.message : '读取申请字段失败'
  } finally {
    if (resourceOwned(k) && r === columnRequest && busyOwner === resourceBusyOwner)
      loadingResources.value = false
  }
}
async function submitRequest() {
  if (
    submitting.value ||
    !canSubmit.value ||
    loadingResources.value ||
    resourceError.value ||
    locked(submitLock.value)
  )
    return
  const ds = form.datasourceId,
    snap = form.snapshotId,
    table = form.tableName
  writeError.value = ''
  if (
    !selfId.value ||
    !ds ||
    !snap ||
    !table ||
    !form.columns.length ||
    !form.purpose.trim() ||
    !snapshots.value.some((x) => x.id === snap) ||
    !tables.value.some((x) => x.tableName === table && x.selectable) ||
    form.columns.some((c) => !columns.value.some((x) => x.columnName === c && x.selectable))
  ) {
    writeError.value = '请明确发布范围、可申请字段和用途；字段未选不表示全部。'
    return
  }
  if (
    form.validUntil &&
    (!Number.isFinite(new Date(form.validUntil).getTime()) ||
      new Date(form.validUntil).getTime() <= Date.now())
  ) {
    writeError.value = '希望到期时间必须是未来的有效本地时间。'
    return
  }
  const payload = {
      datasourceId: ds,
      metadataSnapshotId: snap,
      tableName: table,
      columns: [...form.columns],
      rowScope: 'ALL',
      requestedValidUntil: form.validUntil ? form.validUntil.replace(' ', 'T') : null,
      purpose: form.purpose.trim(),
    },
    k = resourceKey.value,
    signature = JSON.stringify(form),
    o = ++submitOperation,
    lock = submitLock.value,
    actor = identity()
  submitting.value = true
  try {
    await trackGovernanceWrite(
      actor,
      [lock],
      `本人 #${selfId.value} / 源 #${ds} / 快照 #${snap} / ${table} 新访问申请`,
      async () => {
        const result = await submitIamS1AccessRequest(payload)
        if (!Number.isSafeInteger(result.data) || Number(result.data) <= 0)
          throw new Error('提交回执缺少申请ID，结果未知，先核对本人申请')
      },
    )
    if (!resourceOwned(k) || o !== submitOperation) return
    if (JSON.stringify(form) === signature) {
      form.purpose = ''
      form.columns = []
      editorOpen.value = false
    }
    writeError.value = '已提交，待审批不授予查询权。'
    await loadMine()
  } catch (e) {
    if (resourceOwned(k) && o === submitOperation)
      writeError.value = e instanceof Error ? e.message : '提交失败，输入保留；结果未知先核对'
  } finally {
    if (resourceOwned(k) && o === submitOperation) submitting.value = false
  }
}
async function withdraw(row: IamS1AccessRequestView) {
  if (
    !canSubmit.value ||
    row.requesterId !== selfId.value ||
    row.status !== 'PENDING' ||
    !mine.value.includes(row) ||
    reviewing.value ||
    locked(requestLock(row.id))
  )
    return
  const k = actorKey.value,
    o = ++withdrawOperation,
    actor = identity(),
    frozen = { ...row, requestedColumns: [...row.requestedColumns] }
  reviewing.value = true
  writeError.value = ''
  try {
    await ElMessageBox.confirm(`${scopeText(frozen)}。撤回后如需访问须重新申请。`, '撤回本人申请', {
      type: 'warning',
      confirmButtonText: '确认撤回',
      cancelButtonText: '取消',
      cancelButtonClass: session.cancelClass,
      closeOnClickModal: false,
    })
    if (
      !owned(k) ||
      o !== withdrawOperation ||
      !canSubmit.value ||
      !mine.value.includes(row) ||
      row.status !== 'PENDING' ||
      row.requesterId !== selfId.value
    )
      return
    await trackGovernanceWrite(actor, [requestLock(frozen.id)], scopeText(frozen) + ' 撤回', () =>
      withdrawIamS1AccessRequest(frozen.id, '申请人撤回'),
    )
    if (owned(k) && o === withdrawOperation) await loadMine()
  } catch (e) {
    if (owned(k) && o === withdrawOperation && e !== 'cancel' && e !== 'close')
      writeError.value = e instanceof Error ? e.message : '撤回失败，先核对原申请'
  } finally {
    if (owned(k) && o === withdrawOperation) reviewing.value = false
  }
}
function formatLocal(date: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())} ${p(date.getHours())}:${p(date.getMinutes())}:00`
}
function review(row: IamS1AccessRequestView, decision: 'APPROVE' | 'REJECT') {
  if (
    reviewing.value ||
    !canReviewOn(row) ||
    !queue.pending.rows.includes(row) ||
    locked(requestLock(row.id))
  )
    return
  closeReview()
  reviewTarget.value = { ...row, requestedColumns: [...row.requestedColumns] }
  reviewDraft.decision = decision
  reviewDraft.columns = [...row.requestedColumns]
  reviewDraft.reason = ''
  const end = Math.min(
    Date.now() + 7 * 86400000,
    row.requestedValidUntil ? new Date(row.requestedValidUntil).getTime() : Infinity,
  )
  reviewDraft.validUntil = formatLocal(new Date(end))
}
async function confirmReview() {
  const row = reviewTarget.value
  if (
    !row ||
    reviewing.value ||
    !canReviewOn(row) ||
    !queue.pending.rows.some((x) => JSON.stringify(x) === JSON.stringify(row)) ||
    locked(requestLock(row.id))
  )
    return
  writeError.value = ''
  if (!reviewDraft.reason.trim()) {
    writeError.value = '请填写本次审批依据。'
    return
  }
  const until = new Date(reviewDraft.validUntil).getTime(),
    max = Math.min(
      Date.now() + 30 * 86400000,
      row.requestedValidUntil ? new Date(row.requestedValidUntil).getTime() : Infinity,
    )
  if (
    reviewDraft.decision === 'APPROVE' &&
    (!reviewDraft.columns.length ||
      reviewDraft.columns.some((c) => !row.requestedColumns.includes(c)) ||
      !Number.isFinite(until) ||
      until <= Date.now() ||
      until > max)
  ) {
    writeError.value = '批准字段须为申请的非空子集；到期须晚于现在，且不超过申请期限或30天。'
    return
  }
  const payload = {
      decision: reviewDraft.decision,
      approvedColumns: reviewDraft.decision === 'APPROVE' ? [...reviewDraft.columns] : [],
      approvedValidUntil:
        reviewDraft.decision === 'APPROVE' ? reviewDraft.validUntil.replace(' ', 'T') : null,
      reason: reviewDraft.reason.trim(),
    },
    k = actorKey.value,
    e = reviewEpoch,
    actor = identity()
  reviewing.value = true
  try {
    await ElMessageBox.confirm(
      `${scopeText(row)}。${payload.decision === 'APPROVE' ? `批准字段 ${payload.approvedColumns.join('、')}，至 ${reviewDraft.validUntil}；通过后生成临时授权，服务端仍重验DENY、隐藏和来源状态。` : '拒绝本申请，不创建授权。'}依据：${payload.reason}`,
      '确认审批精确范围',
      {
        type: 'warning',
        confirmButtonText: payload.decision === 'APPROVE' ? '确认批准并生成临时授权' : '确认拒绝申请',
        cancelButtonText: '取消',
        cancelButtonClass: session.cancelClass,
        closeOnClickModal: false,
      },
    )
    if (
      !owned(k) ||
      e !== reviewEpoch ||
      reviewTarget.value !== row ||
      !canReviewOn(row) ||
      !queue.pending.rows.some((x) => JSON.stringify(x) === JSON.stringify(row))
    )
      return
    await trackGovernanceWrite(actor, [requestLock(row.id)], scopeText(row) + ' 审批', async () => {
      const result = await reviewIamS1AccessRequest(row.id, payload),
        data = result.data
      if (
        !data ||
        data.requestId !== row.id ||
        (payload.decision === 'APPROVE'
          ? !Number.isSafeInteger(data.generatedGrantId) || Number(data.generatedGrantId) <= 0
          : data.generatedGrantId !== '')
      )
        throw new Error('审批回执缺少精确申请或授权ID，结果未知，原依据保留；先核对真实申请记录')
      return result
    })
    if (!owned(k) || e !== reviewEpoch) return
    closeReview()
    await loadQueue('pending')
  } catch (cause) {
    if (owned(k) && e === reviewEpoch && cause !== 'cancel' && cause !== 'close')
      writeError.value = cause instanceof Error ? cause.message : '审批失败；依据保留，结果未知先核对原申请'
  } finally {
    if (owned(k) && e === reviewEpoch) reviewing.value = false
  }
}
watch(actorKey, initialize, { immediate: true, flush: 'sync' })
</script>
<template>
  <div class="iam-s1-approvals">
    <TaskPageHeader
      title="访问申请与审批"
      eyebrow="权限与组织 / 临时业务访问"
      description-mode="inline"
      description="明确为什么需要哪些字段，再由负责该源的审批人核对。待审批不授予查询权，通过后生成有期限的个人授权。"
    />
    <GovernanceWriteNotice />
    <ErrorState v-if="iam.errorMessage" :message="iam.errorMessage" @retry="iam.load(true)" />
    <LoadingState v-else-if="!iam.loaded" text="正在核对身份与权限摘要…" />
    <template v-else>
      <div class="approval-contract">
        <strong>申请范围 → 同源人工审批 → 临时授权 → 到期失效</strong>
        <p>
          只能申请全部记录的指定字段；不填写SQL或记录条件。审批不绕过DENY、隐藏、废弃或阻断对象，不允许自审。
        </p>
      </div>
      <el-tabs v-model="activeTab" @tab-change="selectTab">
        <el-tab-pane label="我的申请" name="mine" />
        <el-tab-pane label="待审批" name="pending" />
        <el-tab-pane label="已处理" name="handled" />
      </el-tabs>
      <p v-if="writeError" class="notice" role="status">{{ writeError }}</p>
      <EmptyState
        v-if="activeTab === 'mine' && !canSubmit"
        message="缺少使用问数能力，不能发起或读取本人申请；审批队列使用独立查看能力。"
      />
      <EmptyState
        v-else-if="activeTab !== 'mine' && !canViewQueue"
        message="没有security:approval:view，不能读取审批队列。"
      />
      <template v-else>
        <div class="toolbar">
          <div>
            <h3>
              {{
                activeTab === 'mine'
                  ? '本人已提交的申请'
                  : activeTab === 'pending'
                    ? '逐条核对待审批申请'
                    : '已处理申请及实际结论'
              }}
            </h3>
            <p>
              {{
                activeTab === 'mine'
                  ? '列表来自本人申请接口。'
                  : '队列按待审批/已处理分别服务端分页；查看与审批独立判定。'
              }}
            </p>
          </div>
          <div>
            <el-button @click="loadCurrent">重读当前列表</el-button>
            <el-button v-if="activeTab === 'mine'" type="primary" @click="openEditor">发起访问申请</el-button>
          </div>
        </div>
        <section v-if="activeTab === 'mine' && editorOpen" class="panel request-editor">
          <div class="toolbar">
            <h3>说明本次访问需要</h3>
            <el-button @click="editorOpen = false">关闭配置</el-button>
          </div>
          <ErrorState v-if="resourceError" :message="resourceError" @retry="loadDatasources" />
          <LoadingState v-if="loadingResources" text="正在读取APPLY申请资源…" />
          <div class="form-grid">
            <label>
              可申请数据源
              <el-select v-model="form.datasourceId" placeholder="选择数据源" @change="loadSnapshots">
                <el-option v-for="d in datasources" :key="d.id" :value="d.id" :label="d.name" />
              </el-select>
            </label>
            <label>
              发布快照
              <el-select v-model="form.snapshotId" placeholder="选择快照" @change="loadTables">
                <el-option
                  v-for="s in snapshots"
                  :key="s.id"
                  :value="s.id"
                  :label="`版本 ${s.snapshotVersion ?? s.id} · #${s.id}`"
                />
              </el-select>
            </label>
            <label>
              申请表
              <el-select v-model="form.tableName" placeholder="选择表" @change="loadColumns">
                <el-option
                  v-for="t in tables"
                  :key="t.tableName"
                  :value="t.tableName"
                  :label="t.tableName"
                  :disabled="!t.selectable"
                />
              </el-select>
            </label>
            <label>
              申请字段
              <el-select v-model="form.columns" multiple placeholder="明确字段，空不表示全部">
                <el-option
                  v-for="c in columns"
                  :key="c.columnMetaId"
                  :value="c.columnName"
                  :label="c.columnComment || c.columnName"
                  :disabled="!c.selectable"
                />
              </el-select>
            </label>
            <label>
              希望到期时间
              <el-date-picker
                v-model="form.validUntil"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
                placeholder="留空由审批人指定期限"
              />
            </label>
            <label>
              申请用途
              <el-input v-model="form.purpose" placeholder="本次需要这些字段的具体用途" />
            </label>
          </div>
          <p v-if="!loadingResources && !resourceError && !datasources.length">
            本次未返回可申请源；APPLY需要使用问数及已发布快照，不要求后台负责源。
          </p>
          <el-button
            type="primary"
            :loading="submitting"
            :disabled="loadingResources || locked(submitLock)"
            @click="submitRequest"
          >
            提交申请
          </el-button>
        </section>
        <section v-if="reviewTarget && activeTab === 'pending'" class="panel review-editor">
          <div class="toolbar">
            <h3>{{ reviewDraft.decision === 'APPROVE' ? '核对批准范围' : '填写拒绝依据' }}</h3>
            <el-button :disabled="reviewing" @click="closeReview">关闭审核</el-button>
          </div>
          <p>{{ scopeText(reviewTarget) }}</p>
          <p>
            原用途：{{ reviewTarget.purpose }}；原申请到期：{{
              reviewTarget.requestedValidUntil || '未指定（批准仍须有期限）'
            }}
          </p>
          <div class="form-grid">
            <label v-if="reviewDraft.decision === 'APPROVE'">
              批准字段（原申请子集）
              <el-select v-model="reviewDraft.columns" multiple :disabled="reviewing">
                <el-option v-for="c in reviewTarget.requestedColumns" :key="c" :label="c" :value="c" />
              </el-select>
            </label>
            <label v-if="reviewDraft.decision === 'APPROVE'">
              批准到期时间
              <el-date-picker
                v-model="reviewDraft.validUntil"
                :disabled="reviewing"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
              />
            </label>
            <label>
              审批依据
              <el-input
                v-model="reviewDraft.reason"
                :disabled="reviewing"
                placeholder="本次审批的具体判断依据"
              />
            </label>
          </div>
          <el-button
            type="primary"
            :loading="reviewing"
            :disabled="locked(requestLock(reviewTarget.id))"
            @click="confirmReview"
          >
            {{ reviewDraft.decision === 'APPROVE' ? '核对并批准' : '核对并拒绝' }}
          </el-button>
        </section>
        <section class="panel">
          <ErrorState v-if="currentError" :message="currentError" @retry="loadCurrent" />
          <LoadingState v-else-if="currentLoading" text="正在读取当前申请列表…" />
          <EmptyState
            v-else-if="!currentRows.length"
            :message="activeTab === 'mine' ? '本次未返回本人申请。' : '当前队列本页没有申请。'"
          />
          <el-table v-else :data="currentRows">
            <el-table-column label="申请与范围" min-width="240">
              <template #default="{ row }">
                <strong>#{{ row.id }} · {{ row.datasourceName || '源 #' + row.datasourceId }}</strong>
                <small>
                  {{ row.tableName }} · 快照 #{{ row.metadataSnapshotId }} · 申请人
                  {{ row.requesterName || row.requesterId }}
                </small>
              </template>
            </el-table-column>
            <el-table-column label="申请字段与用途" min-width="250">
              <template #default="{ row }">
                {{ row.requestedColumns.join('、') }}
                <small>{{ row.purpose }}</small>
              </template>
            </el-table-column>
            <el-table-column label="状态与期限" min-width="210">
              <template #default="{ row }">
                {{ row.statusName || row.status }}
                <small>
                  申请到期：{{ row.requestedValidUntil || '未指定' }}；{{ row.rowScopeName || row.rowScope }}
                </small>
              </template>
            </el-table-column>
            <el-table-column label="实际审批记录" min-width="230">
              <template #default="{ row }">
                <template v-if="row.approval">
                  {{ row.approval.decisionName || row.approval.decision }} ·
                  {{ row.approval.reviewerName || row.approval.reviewerId }}
                  <small>
                    {{ row.approval.reason || '未填写理由' }}；
                    <span v-if="row.approval.decision === 'APPROVED'">
                      批准字段：{{
                        row.approval.approvedColumns?.length
                          ? row.approval.approvedColumns.join('、')
                          : '未返回，不能从申请范围推断'
                      }}；
                    </span>
                    {{
                      row.approval.generatedGrantId ? '授权 #' + row.approval.generatedGrantId : '未生成授权'
                    }}；批准到期 {{ row.approval.approvedValidUntil || '未记录' }}
                  </small>
                </template>
                <span v-else>{{ row.status === 'PENDING' ? '尚未审批' : '未返回审批记录' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" min-width="165">
              <template #default="{ row }">
                <el-button
                  v-if="activeTab === 'mine' && row.status === 'PENDING'"
                  link
                  type="warning"
                  :disabled="reviewing || locked(requestLock(row.id))"
                  @click="withdraw(row)"
                >
                  撤回
                </el-button>
                <template v-if="activeTab === 'pending'">
                  <el-button
                    link
                    type="primary"
                    :disabled="!canReviewOn(row) || reviewing || locked(requestLock(row.id))"
                    @click="review(row, 'APPROVE')"
                  >
                    同意
                  </el-button>
                  <el-button
                    link
                    type="danger"
                    :disabled="!canReviewOn(row) || reviewing || locked(requestLock(row.id))"
                    @click="review(row, 'REJECT')"
                  >
                    拒绝
                  </el-button>
                  <small v-if="row.requesterId === selfId">不能审批本人的申请</small>
                  <small v-else-if="!canReviewOn(row)">缺少本源审批能力</small>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-if="activeTab !== 'mine' && !currentError"
            :current-page="queue[activeTab].page"
            :page-size="20"
            :total="queue[activeTab].total"
            layout="total,prev,pager,next"
            @current-change="changePage"
          />
        </section>
      </template>
    </template>
  </div>
</template>
<style scoped>
.iam-s1-approvals {
  display: grid;
  gap: 20px;
}
.panel,
.approval-contract {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  min-width: 0;
}
.approval-contract {
  border-left: 3px solid var(--do-primary);
}
.toolbar {
  display: flex;
  gap: 16px;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
}
.toolbar h3 {
  margin: 0;
  font-size: 17px;
}
.toolbar p,
.panel p,
.approval-contract p {
  font-size: 12px;
  color: var(--do-muted);
  line-height: 1.8;
  margin: 8px 0;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin: 16px 0;
}
.form-grid label {
  display: grid;
  gap: 6px;
  font-size: 12px;
  color: var(--do-muted);
}
.form-grid :deep(.el-date-editor) {
  width: 100%;
}
.panel small {
  display: block;
  font-size: 12px;
  color: var(--do-muted);
  line-height: 1.7;
}
.notice {
  padding: 12px;
  background: var(--do-bg);
  border-left: 3px solid var(--do-primary);
}
.el-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
@media (max-width: 950px) {
  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .panel {
    padding: 14px;
  }
}
</style>
