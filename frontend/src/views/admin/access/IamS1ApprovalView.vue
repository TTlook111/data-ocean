<script setup lang="ts">
/**
 * IAM-SIMPLE-1 访问申请与审批工作区
 *
 * 我的申请与管理员审批保持独立入口：业务用户不进入后台权限工作区，
 * 管理员只看到本人负责数据源的队列；通过后生成独立的新体系授权事实。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '../../../components/common/EmptyState.vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import { useIamS1Store } from '../../../stores/iamS1'
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
  type IamS1TableOption,
} from '../../../api/iamS1'

const iamS1 = useIamS1Store()
const activeTab = ref<'mine' | 'pending' | 'handled'>('mine')
const loading = ref(false)
const datasources = ref<IamS1DatasourceRef[]>([])
const snapshots = ref<Array<{ id: number; snapshotVersion?: number }>>([])
const tables = ref<IamS1TableOption[]>([])
const columns = ref<IamS1ColumnOption[]>([])
const mine = ref<IamS1AccessRequestView[]>([])
// 两个 Tab 各自分页：待审批不再被同源的已处理记录挤出（后端按状态下推到数据库分页）。
const pendingRequests = ref<IamS1AccessRequestView[]>([])
const handledRequests = ref<IamS1AccessRequestView[]>([])
const pendingTotal = ref(0)
const handledTotal = ref(0)
const pendingPage = ref(1)
const handledPage = ref(1)
const QUEUE_PAGE_SIZE = 20
const submitting = ref(false)
const reviewing = ref<number>()

const form = reactive({
  datasourceId: undefined as number | undefined,
  snapshotId: undefined as number | undefined,
  tableName: undefined as string | undefined,
  columns: [] as string[],
  purpose: '',
  validUntil: '' as string,
})

const canSubmit = computed(() => iamS1.queryUse)
/** 读取审批队列需要“查看访问申请”，与后端 GET /access-requests/queue 的校验一致。 */
const canViewQueue = computed(() => iamS1.hasGlobal('security:approval:view'))
/**
 * Tab 级粗判用“审批”功能码本身，不用 security:approval:view：
 * 只有 view 的角色会看到可点的同意/拒绝按钮，点了必然 403。
 */
const canReview = computed(() => iamS1.hasGlobal('security:approval:review'))

/**
 * 逐条判定审批权：后端 `requireDatasourceFunction` 要求
 * “审批功能与负责源在同一个角色绑定上同时成立”，所以必须按申请的数据源判定，
 * 不能用全局功能码一刀切。
 */
function canReviewOn(row: IamS1AccessRequestView): boolean {
  return iamS1.canOnDatasource('security:approval:review', row.datasourceId)
}

async function loadDatasources() {
  // 申请入口使用用户侧资源接口（scope=APPLY）：只要求“使用问数”，
  // 不要求后台负责源，否则没有负责源的普通问数用户看不到任何可申请数据源。
  const result = await listIamS1QueryResourceDatasources('APPLY')
  datasources.value = result.data ?? []
  form.datasourceId = datasources.value[0]?.id
}

async function loadSnapshots() {
  form.snapshotId = undefined
  form.tableName = undefined
  form.columns = []
  tables.value = []
  columns.value = []
  if (!form.datasourceId) return
  const result = await listIamS1QueryResourceSnapshots(form.datasourceId, 'APPLY')
  snapshots.value = result.data ?? []
  form.snapshotId = snapshots.value[0]?.id
  await loadTables()
}

async function loadTables() {
  form.tableName = undefined
  form.columns = []
  columns.value = []
  if (!form.datasourceId || !form.snapshotId) return
  const result = await listIamS1QueryResourceTables(form.datasourceId, form.snapshotId, 'APPLY')
  tables.value = result.data ?? []
}

async function loadColumns() {
  form.columns = []
  columns.value = []
  if (!form.datasourceId || !form.snapshotId || !form.tableName) return
  const result = await listIamS1QueryResourceColumns(form.datasourceId, form.snapshotId, form.tableName, 'APPLY')
  columns.value = result.data ?? []
}

async function loadMine() {
  if (!canSubmit.value) return
  try {
    const result = await listIamS1MyAccessRequests()
    mine.value = result.data ?? []
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取我的申请失败')
  }
}

async function loadQueue() {
  if (!canViewQueue.value) return
  loading.value = true
  try {
    // 两个 Tab 各查各的页：待审批只查 PENDING，不会再被同源的已处理记录挤出。
    const [pending, handled] = await Promise.all([
      listIamS1AccessRequestQueue('PENDING', pendingPage.value, QUEUE_PAGE_SIZE),
      listIamS1AccessRequestQueue('HANDLED', handledPage.value, QUEUE_PAGE_SIZE),
    ])
    pendingRequests.value = pending.data?.records ?? []
    handledRequests.value = handled.data?.records ?? []
    pendingTotal.value = pending.data?.total ?? 0
    handledTotal.value = handled.data?.total ?? 0
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取审批队列失败')
  } finally {
    loading.value = false
  }
}

function changePendingPage(page: number) {
  pendingPage.value = page
  void loadQueue()
}

function changeHandledPage(page: number) {
  handledPage.value = page
  void loadQueue()
}

async function submitRequest() {
  if (!form.datasourceId || !form.snapshotId || !form.tableName || !form.columns.length || !form.purpose.trim()) {
    ElMessage.warning('请选择数据源、表、字段并填写申请用途')
    return
  }
  submitting.value = true
  try {
    await submitIamS1AccessRequest({
      datasourceId: form.datasourceId,
      metadataSnapshotId: form.snapshotId,
      tableName: form.tableName,
      columns: form.columns,
      rowScope: 'ALL',
      // 后端字段是 LocalDateTime：发送不带时区后缀的本地时间，避免 toISOString 的 Z 后缀被拒。
      requestedValidUntil: form.validUntil ? form.validUntil.replace(' ', 'T') : null,
      purpose: form.purpose.trim(),
    })
    ElMessage.success('申请已提交，等待审批')
    form.purpose = ''
    form.columns = []
    await loadMine()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '提交申请失败')
  } finally {
    submitting.value = false
  }
}

async function withdraw(item: IamS1AccessRequestView) {
  try {
    await ElMessageBox.confirm('撤回后需要重新申请，确认撤回吗？', '撤回申请', { type: 'warning' })
  } catch {
    return
  }
  try {
    await withdrawIamS1AccessRequest(item.id, '申请人撤回')
    ElMessage.success('申请已撤回')
    await loadMine()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '撤回申请失败')
  }
}

const MAX_APPROVAL_DAYS = 30

/**
 * 审批弹窗输入框的显示格式，**必须与下方 inputPattern 完全一致**（空格分隔、不含秒）。
 * 它只用于预填；提交时再转成后端 LocalDateTime 需要的 `YYYY-MM-DDTHH:mm:00`。
 * 两处格式不一致会让“不改日期直接点同意”被自己的校验规则拒绝。
 */
function formatLocal(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 默认批准到期时间：申请时长与 7 天中的较小值；审批不允许生成永久权限。 */
function defaultApprovedValidUntil(item: IamS1AccessRequestView): string {
  const fallback = new Date()
  fallback.setDate(fallback.getDate() + 7)
  if (!item.requestedValidUntil) return formatLocal(fallback)
  const requested = new Date(item.requestedValidUntil)
  if (Number.isNaN(requested.getTime())) return formatLocal(fallback)
  return formatLocal(requested.getTime() < fallback.getTime() ? requested : fallback)
}

async function review(item: IamS1AccessRequestView, decision: 'APPROVE' | 'REJECT') {
  const approvedColumns = decision === 'APPROVE' ? item.requestedColumns : []
  let approvedValidUntil: string | null = null
  if (decision === 'APPROVE') {
    let input: string
    try {
      const result = await ElMessageBox.prompt(
        `审批只会生成有期限的临时授权（最长 ${MAX_APPROVAL_DAYS} 天），请确认批准到期时间：`,
        '同意数据访问申请',
        {
          inputValue: defaultApprovedValidUntil(item),
          inputPattern: /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/,
          inputErrorMessage: '格式：YYYY-MM-DD HH:mm',
          confirmButtonText: '同意并生成临时授权',
          cancelButtonText: '取消',
        },
      )
      input = String(result.value).trim()
    } catch {
      return
    }
    // 后端字段是 LocalDateTime：必须发送不带时区后缀的本地时间。
    approvedValidUntil = `${input.replace(' ', 'T')}:00`
  }
  reviewing.value = item.id
  try {
    await reviewIamS1AccessRequest(item.id, {
      decision,
      approvedColumns,
      approvedValidUntil,
      reason: decision === 'APPROVE' ? '按申请范围同意' : '不符合当前治理要求',
    })
    ElMessage.success(decision === 'APPROVE' ? '已同意并生成有期限的临时授权' : '已拒绝')
    await loadQueue()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '审批失败')
  } finally {
    reviewing.value = undefined
  }
}

onMounted(async () => {
  await iamS1.load()
  try {
    await Promise.all([loadDatasources(), loadMine(), loadQueue()])
    await loadSnapshots()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '初始化审批工作区失败')
  }
})
</script>

<template>
  <div class="iam-s1-approvals">
    <TaskPageHeader
      title="访问申请与审批"
      description="普通用户只看本人申请；管理员只处理负责数据源的队列。通过后生成有期限的个人授权，待审批申请不授予查询权。"
    />

    <el-tabs v-model="activeTab">
      <el-tab-pane label="我的申请" name="mine">
        <div v-if="!canSubmit" class="panel">
          <EmptyState message="没有“使用问数”权限，无法发起申请。请先让管理员分配包含“使用问数”的角色。" />
        </div>
        <template v-else>
          <section class="panel">
            <h3 class="panel-title">发起申请</h3>
            <div class="inline">
              <el-select v-model="form.datasourceId" placeholder="数据源" class="w200" @change="loadSnapshots">
                <el-option v-for="item in datasources" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
              <el-select v-model="form.snapshotId" placeholder="已发布快照" class="w200" @change="loadTables">
                <el-option
                  v-for="item in snapshots"
                  :key="item.id"
                  :label="`版本 ${item.snapshotVersion ?? item.id}`"
                  :value="item.id"
                />
              </el-select>
              <el-select v-model="form.tableName" placeholder="表" class="w200" @change="loadColumns">
                <el-option v-for="item in tables" :key="item.tableName" :label="item.tableName" :value="item.tableName" />
              </el-select>
              <el-select v-model="form.columns" multiple filterable placeholder="申请字段" class="w260">
                <el-option
                  v-for="item in columns"
                  :key="item.columnMetaId"
                  :label="item.columnComment || item.columnName"
                  :value="item.columnName"
                  :disabled="!item.selectable"
                />
              </el-select>
              <el-date-picker
                v-model="form.validUntil"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
                placeholder="希望到期时间（可留空）"
              />
              <el-input v-model="form.purpose" placeholder="申请用途，例如：月度销售分析" class="w280" />
              <el-button type="primary" :loading="submitting" @click="submitRequest">提交申请</el-button>
            </div>
            <p class="hint">第一期只支持“全部记录”的字段级申请，不接受记录条件或手写 SQL。</p>
            <p class="hint">
              审批只会生成有期限的临时授权（最长 30 天），到期自动失效；审批人不能审批本人的申请。
            </p>
            <el-alert
              v-if="!datasources.length"
              type="warning"
              show-icon
              :closable="false"
              title="当前没有可申请的数据源"
              description="可申请数据源只要求“使用问数”和已发布元数据快照。若列表为空，请联系管理员先完成数据源接入与元数据发布。"
            />
          </section>

          <section class="panel">
            <h3 class="panel-title">我的申请（{{ mine.length }}）</h3>
            <EmptyState v-if="!mine.length" message="还没有提交过申请。选择数据源和字段后提交，管理员审批通过即可获得有期限的查询权。" />
            <el-table v-else :data="mine" size="small">
              <el-table-column prop="datasourceName" label="数据源" width="140" />
              <el-table-column prop="tableName" label="表" width="140" />
              <el-table-column label="字段" min-width="200">
                <template #default="{ row }">{{ row.requestedColumns.join('、') }}</template>
              </el-table-column>
              <el-table-column prop="purpose" label="用途" min-width="180" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">{{ row.statusName }}</template>
              </el-table-column>
              <el-table-column label="审批" min-width="200">
                <template #default="{ row }">
                  <span v-if="row.approval">
                    {{ row.approval.decisionName }}（{{ row.approval.reviewerName || row.approval.reviewerId }}）
                    <small v-if="row.approval.generatedGrantId" class="muted">
                      已生成授权 {{ row.approval.generatedGrantId }}
                    </small>
                  </span>
                  <span v-else class="muted">等待审批</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="100">
                <template #default="{ row }">
                  <el-button v-if="row.status === 'PENDING'" link type="warning" @click="withdraw(row)">撤回</el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </template>
      </el-tab-pane>

      <el-tab-pane label="待我审批" name="pending">
        <section class="panel">
          <EmptyState
            v-if="!canReview"
            message="没有“审批访问申请”权限：需要 IAM-SIMPLE-1 角色包含该功能，并且该角色负责目标数据源。"
          />
          <EmptyState v-else-if="!pendingRequests.length" message="没有待审批的申请。" />
          <el-table v-else v-loading="loading" :data="pendingRequests" size="small">
            <el-table-column prop="requesterName" label="申请人" width="120" />
            <el-table-column prop="datasourceName" label="数据源" width="140" />
            <el-table-column prop="tableName" label="表" width="140" />
            <el-table-column label="申请字段" min-width="200">
              <template #default="{ row }">{{ row.requestedColumns.join('、') }}</template>
            </el-table-column>
            <el-table-column prop="purpose" label="用途" min-width="180" />
            <el-table-column prop="rowScopeName" label="记录范围" width="110" />
            <el-table-column label="希望到期" width="170">
              <template #default="{ row }">{{ row.requestedValidUntil || '不限' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="170">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :disabled="!canReviewOn(row)"
                  :loading="reviewing === row.id"
                  @click="review(row, 'APPROVE')"
                >同意</el-button>
                <el-button
                  link
                  type="danger"
                  :disabled="!canReviewOn(row)"
                  :loading="reviewing === row.id"
                  @click="review(row, 'REJECT')"
                >拒绝</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-if="pendingTotal > QUEUE_PAGE_SIZE"
            class="pager"
            layout="total, prev, pager, next"
            :total="pendingTotal"
            :page-size="QUEUE_PAGE_SIZE"
            :current-page="pendingPage"
            @current-change="changePendingPage"
          />
          <p class="hint">
            通过范围必须是申请范围的子集，时间不超过申请时长；禁止规则、隐藏字段和未发布资源不能审批开通。
          </p>
        </section>
      </el-tab-pane>

      <el-tab-pane label="已处理" name="handled">
        <section class="panel">
          <EmptyState v-if="!handledRequests.length" message="还没有已处理的申请。" />
          <el-table v-else :data="handledRequests" size="small">
            <el-table-column prop="requesterName" label="申请人" width="120" />
            <el-table-column prop="tableName" label="表" width="140" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">{{ row.statusName }}</template>
            </el-table-column>
            <el-table-column label="审批结论" min-width="260">
              <template #default="{ row }">
                <span v-if="row.approval">
                  {{ row.approval.decisionName }}；
                  {{ row.approval.generatedGrantId ? `生成授权 ${row.approval.generatedGrantId}` : '未生成授权' }}；
                  {{ row.approval.reason || '未填写理由' }}
                </span>
                <span v-else class="muted">无审批记录</span>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-if="handledTotal > QUEUE_PAGE_SIZE"
            class="pager"
            layout="total, prev, pager, next"
            :total="handledTotal"
            :page-size="QUEUE_PAGE_SIZE"
            :current-page="handledPage"
            @current-change="changeHandledPage"
          />
        </section>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.iam-s1-approvals {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.pager { margin-top: 16px; justify-content: flex-end; }
.panel {
  border: 1px solid var(--do-line);
  border-radius: 10px;
  padding: 18px;
  background: var(--do-surface);
  margin-top: 8px;
}
.panel-title {
  margin: 0 0 12px;
  font-size: 15px;
}
.inline {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.hint {
  margin: 10px 0 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
}
.muted {
  color: var(--do-muted);
  font-size: 12px;
}
.w200 {
  width: 200px;
}
.w260 {
  width: 260px;
}
.w280 {
  width: 280px;
}
</style>
