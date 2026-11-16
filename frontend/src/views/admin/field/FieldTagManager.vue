<script setup lang="ts">
import { computed, ref, reactive, onBeforeUnmount, onMounted, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Tag, RefreshCw } from 'lucide-vue-next'
import { useGovernanceScope } from '../../../composables/useGovernanceScope'
import { trackGovernanceWrite } from '../../../composables/governanceWriteTracker'
import { useAdminScrollLock } from '../../../composables/useAdminScrollLock'
import GovernanceWriteNotice from '../../../components/admin/GovernanceWriteNotice.vue'
import { positiveId } from '../../../utils/lineageContext'
import {
  listPredefinedTags,
  getFieldTags,
  batchAddFieldTags,
  autoTagByPattern,
  type PredefinedTag,
  type FieldTagVO,
} from '../../../api/admin/field'
import {
  listSnapshotTables,
  listSnapshotTableColumns,
  type ColumnMetaItem,
} from '../../../api/admin/governance'
import { listSnapshots, type SnapshotItem } from '../../../api/admin/metadata'
import { governanceStatusLabel } from '../../../utils/enumLabels'
import { getHttpErrorMessage } from '../../../utils/httpError'
import LoadingState from '../../../components/common/LoadingState.vue'
const props = defineProps<{ embedded?: boolean }>(),
  scope = useGovernanceScope(),
  adminContext = scope.context,
  route = scope.route
const tableLoading = ref(false),
  rangeLoading = ref(false)
const loading = ref(false),
  writeBusy = ref(false),
  tagLoading = ref(false),
  tagError = ref(''),
  writeReceipt = ref('')
const canViewFields = computed(() => scope.canSource('governance:field:view')),
  canManageFields = computed(() => scope.canSource('governance:field:manage')),
  canReadCatalog = computed(() => scope.canSource('metadata:view'))
const MANAGE_HINT = '没有当前数据源的字段维护权限'
const identityKey = computed(() =>
  JSON.stringify([
    scope.auth.token,
    scope.iam.snapshot,
    scope.iam.loaded,
    scope.iam.errorMessage,
    adminContext.datasourceId,
    route.query.datasourceId,
    adminContext.loading,
    adminContext.routeSyncing,
  ]),
)
const scopePending = computed(() => scope.scopeLoading.value)
const predefinedTags = ref<PredefinedTag[]>([]),
  columns = ref<ColumnMetaItem[]>([]),
  selectedColumnIds = ref<number[]>([]),
  currentColumnTags = ref<FieldTagVO[]>([])
const showTagDialog = ref(false),
  showColumnTagsDialog = ref(false),
  currentColumnName = ref(''),
  selectedTagCode = ref('')
const snapshots = ref<SnapshotItem[]>([]),
  tables = ref<Array<{ id: number; tableName: string; tableComment?: string }>>([]),
  snapshotError = ref(''),
  locationError = ref('')
const query = reactive({ snapshotId: undefined as number | undefined, tableName: '' })
let disposed = false,
  boot = 0,
  tableRequest = 0,
  columnRequest = 0,
  tagRequest = 0,
  dialogSession = 0
let pendingTableName = '',
  pendingColumnMetaId: number | undefined
const ownCancelClass = `tag-confirm-${Math.random().toString(36).slice(2)}`
function cancelOwn() {
  document.querySelector<HTMLButtonElement>(`.${ownCancelClass}`)?.click()
}
function current(key: string) {
  return !disposed && key === identityKey.value && canViewFields.value
}
function selectionKey() {
  return `${identityKey.value}|${query.snapshotId}|${query.tableName}`
}
function reset() {
  tableRequest++
  columnRequest++
  tagRequest++
  dialogSession++
  cancelOwn()
  writeBusy.value = false
  showTagDialog.value = false
  showColumnTagsDialog.value = false
  tagLoading.value = false
  tagError.value = ''
  tables.value = []
  columns.value = []
  snapshots.value = []
  selectedColumnIds.value = []
  currentColumnTags.value = []
  query.snapshotId = undefined
  query.tableName = ''
  snapshotError.value = ''
  locationError.value = ''
  loading.value = false
  writeReceipt.value = ''
  tableLoading.value = false
  rangeLoading.value = false
}
useAdminScrollLock(() => showTagDialog.value || showColumnTagsDialog.value)
async function initialize() {
  const ticket = ++boot,
    key = identityKey.value
  reset()
  predefinedTags.value = []
  if (!canViewFields.value) return
  void fetchPredefinedTags(key, ticket)
  if (!canReadCatalog.value) {
    snapshotError.value = '没有metadata:view，不能读取标签所需的表字段目录；不自动补权。'
    return
  }
  pendingTableName = typeof route.query.tableName === 'string' ? route.query.tableName : ''
  pendingColumnMetaId = positiveId(route.query.columnMetaId)
  if (route.query.columnMetaId !== undefined && !pendingColumnMetaId) {
    locationError.value = '指定字段标识无效，未改用其他字段。'
    return
  }
  rangeLoading.value = true
  try {
    const raw = route.query.snapshotId,
      requested = positiveId(raw)
    if (raw !== undefined && !requested) throw new Error('指定的快照标识无效，未切换到其他快照。')
    if (props.embedded) {
      snapshots.value = adminContext.snapshots || []
      if (adminContext.routeSyncError || (requested && requested !== adminContext.snapshotId))
        throw new Error(adminContext.routeSyncError || '公共快照尚未核验，未切换到其他快照。')
      query.snapshotId = adminContext.snapshotId
    } else {
      const result = await listSnapshots({ datasourceId: adminContext.datasourceId, page: 1, size: 50 })
      if (!current(key) || ticket !== boot) return
      snapshots.value = result.data?.records ?? []
      if (requested) {
        let found = snapshots.value.find(
          (x) => x.id === requested && x.datasourceId === adminContext.datasourceId,
        )
        if (!found) {
          const ensured = await adminContext.ensureSnapshot(requested)
          if (!current(key) || ticket !== boot) return
          if (ensured?.id === requested && ensured.datasourceId === adminContext.datasourceId) {
            found = ensured
            snapshots.value = [...snapshots.value, ensured]
          }
        }
        if (!found) throw new Error('指定的快照不存在或不属于当前数据源，未切换到其他快照。')
        query.snapshotId = requested
      } else
        query.snapshotId =
          snapshots.value.find((x) => x.id === adminContext.snapshotId)?.id ?? snapshots.value[0]?.id
      if (query.snapshotId) adminContext.selectSnapshot(query.snapshotId)
    }
    if (query.snapshotId) await fetchTables()
  } catch (cause) {
    if (current(key) && ticket === boot) {
      snapshotError.value = `指定的快照无法加载：${getHttpErrorMessage(cause, '范围读取失败')}。未切换到其他快照。`
      query.snapshotId = undefined
      tables.value = []
      columns.value = []
    }
  } finally {
    if (current(key) && ticket === boot) rangeLoading.value = false
  }
}
async function fetchPredefinedTags(key = identityKey.value, ticket = boot) {
  if (!canViewFields.value) return
  try {
    const res = await listPredefinedTags()
    if (current(key) && ticket === boot) predefinedTags.value = res.data ?? []
  } catch {
    if (current(key) && ticket === boot) writeReceipt.value = '预置标签读取失败，不能选择新标签，请刷新。'
  }
}
async function fetchTables() {
  const request = ++tableRequest,
    key = identityKey.value,
    snapshot = query.snapshotId
  columnRequest++
  tagRequest++
  tables.value = []
  columns.value = []
  selectedColumnIds.value = []
  query.tableName = ''
  locationError.value = ''
  if (!snapshot || !canReadCatalog.value || !canViewFields.value) return
  tableLoading.value = true
  try {
    const res = await listSnapshotTables(snapshot)
    if (!current(key) || request !== tableRequest || snapshot !== query.snapshotId) return
    tables.value = res.data ?? []
    if (pendingTableName) {
      const target = pendingTableName
      pendingTableName = ''
      const table = tables.value.find((t) => t.tableName.toLowerCase() === target.toLowerCase())
      if (!table) {
        locationError.value = `来源快照中未找到表“${target}”，未自动选择其他表。`
        return
      }
      query.tableName = table.tableName
      await fetchColumns()
    }
  } catch (cause) {
    if (current(key) && request === tableRequest)
      locationError.value = getHttpErrorMessage(cause, '表列表读取失败')
  } finally {
    if (current(key) && request === tableRequest) tableLoading.value = false
  }
}
async function fetchColumns() {
  const request = ++columnRequest,
    key = selectionKey(),
    snapshot = query.snapshotId,
    table = query.tableName
  tagRequest++
  dialogSession++
  showTagDialog.value = false
  showColumnTagsDialog.value = false
  columns.value = []
  selectedColumnIds.value = []
  loading.value = false
  locationError.value = ''
  if (
    !snapshot ||
    !canViewFields.value ||
    !canReadCatalog.value ||
    !tables.value.some((t) => t.tableName === table)
  )
    return
  loading.value = true
  try {
    const res = await listSnapshotTableColumns(snapshot, table)
    if (disposed || request !== columnRequest || key !== selectionKey()) return
    columns.value = res.data ?? []
    if (pendingColumnMetaId) {
      const id = pendingColumnMetaId
      pendingColumnMetaId = undefined
      const column = columns.value.find((c) => c.id === id)
      if (!column) {
        locationError.value = '当前来源快照的表中未找到指定字段，未自动选择其他字段。'
        return
      }
      selectedColumnIds.value = [id]
      await viewColumnTags(column)
    }
  } catch (cause) {
    if (!disposed && request === columnRequest && key === selectionKey())
      locationError.value = getHttpErrorMessage(cause, '字段读取失败')
  } finally {
    if (!disposed && request === columnRequest && key === selectionKey()) loading.value = false
  }
}
async function handleSnapshotChange(id?: number) {
  pendingTableName = ''
  pendingColumnMetaId = undefined
  query.snapshotId = id
  adminContext.selectSnapshot(id)
  await fetchTables()
}
function handleSelectionChange(rows: ColumnMetaItem[]) {
  selectedColumnIds.value = rows.filter((r) => columns.value.some((c) => c.id === r.id)).map((r) => r.id)
}
const batchTarget = ref<{ snapshot: number; table: string; ids: number[]; key: string; session: number }>()
function openBatchTagDialog() {
  if (!canManageFields.value || !query.snapshotId || !selectedColumnIds.value.length || writeBusy.value)
    return
  const ids = selectedColumnIds.value.filter((id) => columns.value.some((c) => c.id === id))
  if (!ids.length) return
  dialogSession++
  batchTarget.value = {
    snapshot: query.snapshotId,
    table: query.tableName,
    ids,
    key: selectionKey(),
    session: dialogSession,
  }
  selectedTagCode.value = ''
  writeReceipt.value = ''
  showTagDialog.value = true
}
async function confirmBatchTag() {
  const target = batchTarget.value,
    tag = selectedTagCode.value
  if (
    writeBusy.value ||
    !showTagDialog.value ||
    !target ||
    target.session !== dialogSession ||
    target.key !== selectionKey() ||
    !canManageFields.value ||
    !predefinedTags.value.some((t) => t.tagCode === tag)
  )
    return
  writeBusy.value = true
  writeReceipt.value = ''
  const owned = () =>
    !disposed && target.session === dialogSession && target.key === selectionKey() && canManageFields.value
  try {
    const res = await trackGovernanceWrite(
      scope.auth.token,
      [`field-tags-source:${adminContext.datasourceId}`],
      `标签 ${tag} · 快照 #${target.snapshot} / ${target.table} / 字段 ${target.ids.join(',')}`,
      async () => {
        const result = await batchAddFieldTags({ columnMetaIds: [...target.ids], tagCode: tag })
        if (!Number.isFinite(result.data?.tagged))
          throw new Error('标签回执数量未知，请核对原字段，勿重复提交')
        return result
      },
    )
    if (!owned()) return
    writeReceipt.value = `请求 ${target.ids.length} 个字段，服务端新增 ${res.data.tagged} 个标签；其余是否已存在以原字段记录为准。`
    showTagDialog.value = false
  } catch (cause) {
    if (owned())
      writeReceipt.value = getHttpErrorMessage(
        cause,
        cause instanceof Error ? cause.message : '打标未确认完成，原选择保留',
      )
  } finally {
    if (owned()) writeBusy.value = false
  }
}
async function viewColumnTags(row: ColumnMetaItem) {
  if (!canViewFields.value || !columns.value.some((c) => c.id === row.id)) return
  const request = ++tagRequest,
    key = selectionKey(),
    id = row.id
  currentColumnName.value = row.columnName
  currentColumnTags.value = []
  tagError.value = ''
  tagLoading.value = true
  showColumnTagsDialog.value = true
  const owned = () =>
    !disposed && request === tagRequest && key === selectionKey() && showColumnTagsDialog.value
  try {
    const res = await getFieldTags(id)
    if (owned()) currentColumnTags.value = res.data ?? []
  } catch (cause) {
    if (owned()) tagError.value = getHttpErrorMessage(cause, '字段标签读取失败')
  } finally {
    if (owned()) tagLoading.value = false
  }
}
async function handleAutoTag() {
  if (writeBusy.value || !canManageFields.value || !adminContext.datasourceId) return
  const key = identityKey.value,
    ds = adminContext.datasourceId
  writeBusy.value = true
  writeReceipt.value = ''
  try {
    await ElMessageBox.confirm(
      '此操作按字段名模式处理当前数据源的全部采集版本，不仅是顶部选中的快照。它只添加标签，不确认语义或奖励使用分。',
      '确认源级模式打标',
      {
        type: 'warning',
        confirmButtonText: '确认处理整个数据源',
        cancelButtonText: '取消',
        cancelButtonClass: ownCancelClass,
      },
    )
    if (!current(key) || !canManageFields.value) return
    const result = await trackGovernanceWrite(
      scope.auth.token,
      [`field-tags-source:${ds}`],
      `源级模式打标 · 数据源 #${ds}`,
      async () => {
        const res = await autoTagByPattern(ds)
        if (!Number.isFinite(res.data?.tagged) || !Number.isFinite(res.data?.skipped))
          throw new Error('源级打标回执不完整，结果未知，请核对原源记录')
        return res
      },
    )
    if (current(key))
      writeReceipt.value = `源级模式打标：新增 ${result.data.tagged} 个，跳过 ${result.data.skipped} 个；请核对实际字段标签。`
  } catch (cause) {
    if (current(key) && cause !== 'cancel' && cause !== 'close')
      writeReceipt.value = getHttpErrorMessage(
        cause,
        cause instanceof Error ? cause.message : '源级打标未确认完成',
      )
  } finally {
    if (current(key)) writeBusy.value = false
  }
}
watch(
  identityKey,
  () => {
    void initialize()
  },
  { flush: 'sync' },
)
watch(
  () => [adminContext.snapshotId, route.query.snapshotId, adminContext.routeSyncError],
  () => {
    if (props.embedded) void initialize()
  },
  { flush: 'sync' },
)
watch(
  showColumnTagsDialog,
  (open) => {
    if (!open) {
      tagRequest++
      tagLoading.value = false
    }
  },
  { flush: 'sync' },
)
onMounted(() => {
  void initialize()
})
onBeforeUnmount(() => {
  disposed = true
  boot++
  tableRequest++
  columnRequest++
  tagRequest++
  dialogSession++
  cancelOwn()
})
</script>

<template>
  <main class="field-tag-page post-login-page">
    <GovernanceWriteNotice />
    <p v-if="!canViewFields" role="status">
      {{ scope.datasourceIssue.value || '没有当前源的字段查看能力，未读取字段目录。' }}
    </p>
    <div class="tag-contract">
      <h3>字段业务标签</h3>
      <p>
        先核对精确快照的字段，再选择预置分类。标签不等于语义已审核，也不会自动计入使用分。源级模式打标覆盖本源全部采集版本，执行前需单独确认。
      </p>
    </div>
    <p v-if="writeReceipt" class="tag-receipt" role="status">{{ writeReceipt }}</p>
    <el-alert
      v-if="snapshotError"
      class="field-tag-alert"
      type="error"
      :closable="false"
      :title="snapshotError"
    />
    <el-alert
      v-if="locationError"
      class="field-tag-alert"
      type="warning"
      :closable="false"
      :title="locationError"
    />

    <section class="toolbar">
      <el-select
        v-if="!props.embedded"
        v-model="query.snapshotId"
        placeholder="选择快照"
        @change="handleSnapshotChange"
        style="width: 200px"
      >
        <el-option v-for="s in snapshots" :key="s.id" :label="`快照 v${s.snapshotVersion}`" :value="s.id" />
      </el-select>
      <el-select
        v-model="query.tableName"
        aria-label="选择字段表"
        placeholder="选择表"
        :disabled="!canReadCatalog || !query.snapshotId || !!snapshotError"
        @change="fetchColumns"
        style="width: 240px"
        filterable
      >
        <el-option
          v-for="t in tables"
          :key="t.tableName"
          :label="t.tableComment ? `${t.tableName} · ${t.tableComment}` : t.tableName"
          :value="t.tableName"
        />
      </el-select>
      <div class="header-actions">
        <el-button
          :loading="writeBusy"
          :disabled="!canManageFields || writeBusy"
          :title="canManageFields ? '按当前数据源自动识别标签' : MANAGE_HINT"
          @click="handleAutoTag"
        >
          <RefreshCw :size="16" style="margin-right: 4px" />
          自动打标
        </el-button>
        <el-button
          type="primary"
          :disabled="!canManageFields || writeBusy || !selectedColumnIds.length"
          :title="canManageFields ? '选择字段后批量打标' : MANAGE_HINT"
          @click="openBatchTagDialog"
        >
          <Tag :size="16" style="margin-right: 4px" />
          批量打标
        </el-button>
      </div>
    </section>

    <section class="content-panel">
      <el-table
        v-if="query.tableName && !snapshotError && !locationError"
        :empty-text="loading ? '正在读取字段…' : '当前表没有字段'"
        :data="columns"
        v-loading="loading"
        @selection-change="handleSelectionChange"
        stripe
      >
        <el-table-column type="selection" width="50" />
        <el-table-column prop="columnName" label="字段名" width="200" />
        <el-table-column prop="dataType" label="数据类型" width="150" />
        <el-table-column prop="columnComment" label="注释" />
        <el-table-column label="治理状态" width="120">
          <template #default="{ row }">{{ governanceStatusLabel(row.governanceStatus) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="viewColumnTags(row)">查看标签</el-button>
          </template>
        </el-table-column>
      </el-table>
      <LoadingState v-else-if="scopePending || rangeLoading || tableLoading" text="正在确认字段查看范围…" />
      <el-empty
        v-else-if="!snapshotError && !locationError"
        :description="query.snapshotId ? '选择一张表，查看字段标签' : '请在顶部选择快照'"
        :image-size="40"
      />
    </section>

    <el-dialog
      class="governance-dialog"
      v-model="showTagDialog"
      title="批量打标"
      :show-close="!writeBusy"
      :close-on-click-modal="false"
      :close-on-press-escape="!writeBusy"
      width="min(440px, 94vw)"
    >
      <p style="margin-bottom: 12px">已冻结 {{ batchTarget?.ids.length }} 个字段</p>
      <p v-if="writeReceipt" role="alert">{{ writeReceipt }}</p>
      <el-select :disabled="writeBusy" v-model="selectedTagCode" placeholder="选择标签" style="width: 100%">
        <el-option
          v-for="tag in predefinedTags"
          :key="tag.tagCode"
          :label="`${tag.tagName}（${tag.tagCode}）`"
          :value="tag.tagCode"
        />
      </el-select>
      <template #footer>
        <el-button :disabled="writeBusy" @click="showTagDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="writeBusy"
          :disabled="!canManageFields || !selectedTagCode"
          @click="confirmBatchTag"
        >
          确认打标
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      class="governance-dialog"
      v-model="showColumnTagsDialog"
      :title="`${currentColumnName} 标签`"
      width="min(560px, 94vw)"
    >
      <p v-if="tagError" role="alert">{{ tagError }}</p>
      <LoadingState v-else-if="tagLoading" text="正在读取精确字段标签…" />
      <el-table v-else-if="currentColumnTags.length" :data="currentColumnTags" size="small">
        <el-table-column prop="tagName" label="标签" min-width="140" />
        <el-table-column prop="tagCode" label="编码" min-width="120" />
        <el-table-column prop="source" label="来源" width="110" />
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
      </el-table>
      <el-empty v-else description="该字段暂无标签" />
      <template #footer>
        <el-button type="primary" @click="showColumnTagsDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.tag-contract {
  padding: 16px 20px;
  background: var(--do-primary-soft);
  border-radius: 8px;
}
.tag-contract h3 {
  margin: 0;
  font-size: 16px;
}
.tag-contract p,
.tag-receipt {
  font-size: 13px;
  line-height: 1.7;
  color: var(--do-muted);
}
.field-tag-page {
  min-width: 0;
  width: 100%;
  padding: 0;
}
.header-actions {
  display: flex;
  gap: 8px;
}
.field-tag-alert {
  margin-bottom: 12px;
}
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin: 14px 0 18px;
}
.toolbar .header-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.toolbar :deep(.el-select) {
  min-width: 0;
  max-width: 100%;
}
.content-panel {
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  padding: 16px;
}
</style>
