<script setup lang="ts">
/**
 * 访问审批（开发指导 §7.14）
 *
 * 固定 Tab：`待我审批 | 已处理 | 已过期`，Tab 由 `?tab=` 恢复。
 *
 * 管理员审批队列。审批通过后后端会生成**有有效期的临时 ALLOW 策略**，
 * 到期自动清理；通过前会再次复查 `BLOCKED` / `DEPRECATED` 表字段，不能绕过治理限制。
 *
 * **权限边界不靠前端**：列表范围自 2026-09-12 起由后端强制收窄
 * （有 `security:manage` 看全量，其余只看自己提交的），前端隐藏菜单不构成安全边界。
 * 本页面仍只作为 `security:manage` 的管理员队列；普通用户「我的申请」入口按
 * §7.14 待产品确认后开放（API 封装已就绪）。
 *
 * 后端能力缺口（如实标注，不伪造）：
 * - 申请记录**不返回治理状态复查结果**——后端在审批时执行复查，但不把结果写回申请行。
 * - 申请人与数据源在记录上只有 ID，页面通过用户列表与数据源简表在本页解析为名称。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshCw } from 'lucide-vue-next'
import {
  listAccessApprovalRequests,
  reviewAccessApprovalRequest,
  type AccessApprovalRequestItem,
} from '../../../api/admin/permission'
import { listSimpleDatasources } from '../../../api/admin/datasource'
import { listUsers } from '../../../api/admin/user'
import { getPublishedSnapshot } from '../../../api/admin/versioning'
import { listSnapshotTableColumns, listSnapshotTables } from '../../../api/admin/governance'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import { governanceStatusLabels } from '../../../utils/enumLabels'

/**
 * 每次请求取回条数 = 后端 `PageRequest.MAX_PAGE_SIZE` 上限。
 *
 * 「已处理」需要合并 APPROVED 与 REJECTED 两个状态，而接口一次只接受一个状态，
 * 因此本页逐页取回后在本地分页。为免某个状态记录极多时一次并发过多请求，
 * 单状态最多取回 MAX_PAGES 页；未取满时在页面上如实披露，不假装是完整列表。
 */
const FETCH_SIZE = 100
/** 单状态取回页数上限（2000 条）；超出时页面披露列表不完整 */
const MAX_PAGES = 20
/** 并发取页的窗口大小，避免一次打出上百个请求 */
const FETCH_CONCURRENCY = 5
const PAGE_SIZE = 20

const TABS = [
  { name: 'pending', label: '待我审批', statuses: ['PENDING'], empty: '当前没有待审批的访问申请。' },
  { name: 'processed', label: '已处理', statuses: ['APPROVED', 'REJECTED'], empty: '还没有已处理的申请。' },
  { name: 'expired', label: '已过期', statuses: ['EXPIRED'], empty: '还没有已过期的申请。' },
] as const

const route = useRoute()
const router = useRouter()

const activeTab = ref(TABS.some((t) => t.name === route.query.tab) ? String(route.query.tab) : 'pending')
const allRows = ref<AccessApprovalRequestItem[]>([])
const serverTotal = ref(0)
const page = ref(1)
const loading = ref(true)
const error = ref('')
const reviewing = ref(false)
const detailVisible = ref(false)
const detail = ref<AccessApprovalRequestItem | null>(null)
const governanceState = ref('未检查')
const namesError = ref('')

/** ID → 名称映射；记录上只有 ID，页面负责解析 */
const datasourceNames = ref<Record<number, string>>({})
const userNames = ref<Record<number, string>>({})

const currentTab = computed(() => TABS.find((t) => t.name === activeTab.value) || TABS[0])
const rows = computed(() => allRows.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))
/** 取回条数少于服务端匹配数：单状态超过 MAX_PAGES 页，或取页期间记录发生增删 */
const truncated = computed(() => serverTotal.value > allRows.value.length)
/**
 * 治理状态标签。
 *
 * `openDetail` 成功路径写回的是后端治理状态枚举，失败路径写的是页面自述文案
 * （「检查中」「无已发布快照」等）。只有前者需要域内翻译，后者原样显示。
 */
const governanceLabel = computed(() => (
  governanceStatusLabels[governanceState.value] || governanceState.value
))

const applicantName = (id?: number) => (id ? userNames.value[id] || `用户 #${id}` : '—')
const datasourceNameOf = (id?: number) => (id ? datasourceNames.value[id] || `数据源 #${id}` : '—')
const targetOf = (row: AccessApprovalRequestItem) => `${row.tableName}${row.columnName ? '.' + row.columnName : ''}`

function timeOf(row: AccessApprovalRequestItem) {
  return new Date(row.approvedAt || row.createdAt).getTime() || 0
}

/**
 * 审批通过后将生成的临时策略描述。
 *
 * 这是**由申请本身推导出的说明**（申请人、对象、时长），不是后端返回的字段——
 * 后端生成策略后不会把策略回写到申请记录上。
 */
function tempPolicyText(row: AccessApprovalRequestItem) {
  return `为「${applicantName(row.requesterId)}」在「${datasourceNameOf(row.datasourceId)}」上生成 `
    + `${targetOf(row)} 的临时允许策略，有效期 ${row.requestedDuration} 小时，到期自动失效并清理。`
}

async function loadNames() {
  namesError.value = ''
  const [sources, users] = await Promise.allSettled([
    listSimpleDatasources(),
    listUsers({ page: 1, pageSize: 100 }),
  ])
  if (sources.status === 'fulfilled') {
    datasourceNames.value = Object.fromEntries((sources.value.data || []).map((d) => [d.id, d.name]))
  }
  if (users.status === 'fulfilled') {
    userNames.value = Object.fromEntries(
      (users.value.data?.records || []).map((u) => [u.id, u.realName || u.username]),
    )
  }
  if (sources.status === 'rejected' || users.status === 'rejected') {
    namesError.value = '部分名称解析失败，带编号的名称表示未能加载对应基础数据。'
  }
}

function apiError(cause: unknown, fallback: string) {
  return (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
    || (cause instanceof Error ? cause.message : fallback)
}

async function fetchAllByStatus(status: string) {
  const first = await listAccessApprovalRequests({ status, page: 1, size: FETCH_SIZE })
  const records = [...(first.data?.records || [])]
  const total = first.data?.total || records.length
  const pages = Math.min(Math.ceil(total / FETCH_SIZE), MAX_PAGES)
  // 按窗口分批并发，页面只按真实取回条数与服务端 total 的差额决定是否披露
  for (let start = 2; start <= pages; start += FETCH_CONCURRENCY) {
    const batch: number[] = []
    for (let index = start; index < start + FETCH_CONCURRENCY && index <= pages; index += 1) {
      batch.push(index)
    }
    const results = await Promise.all(
      batch.map((index) => listAccessApprovalRequests({ status, page: index, size: FETCH_SIZE })),
    )
    results.forEach((result) => records.push(...(result.data?.records || [])))
  }
  return { records, total }
}

async function load() {
  loading.value = true
  error.value = ''
  const tab = currentTab.value
  try {
    const results = await Promise.all(
      tab.statuses.map((status) => fetchAllByStatus(status)),
    )
    const merged = results.flatMap((r) => r.records)
    merged.sort((a, b) => timeOf(b) - timeOf(a))
    allRows.value = merged
    serverTotal.value = results.reduce((sum, r) => sum + r.total, 0)
    page.value = 1
  } catch (cause) {
    allRows.value = []
    serverTotal.value = 0
    error.value = apiError(cause, '访问审批列表加载失败')
  } finally {
    loading.value = false
  }
}

function selectTab(name: string | number) {
  activeTab.value = String(name)
  router.push({ query: { ...route.query, tab: activeTab.value } })
  load()
}

/** 治理状态复查的请求序号：连点两行时，先发的响应不得覆盖后发的 */
let governanceRequestId = 0

async function openDetail(row: AccessApprovalRequestItem) {
  detail.value = row
  detailVisible.value = true
  const current = ++governanceRequestId
  governanceState.value = '检查中'
  try {
    const published = (await getPublishedSnapshot(row.datasourceId)).data
    let next = '对象不存在'
    if (!published?.snapshotId) {
      next = '无已发布快照'
    } else if (row.columnName) {
      const columns = (await listSnapshotTableColumns(published.snapshotId, row.tableName)).data || []
      next = columns.find((column) => column.columnName === row.columnName)?.governanceStatus || '对象不存在'
    } else {
      const tables = (await listSnapshotTables(published.snapshotId)).data || []
      next = tables.find((table) => table.tableName === row.tableName)?.governanceStatus || '对象不存在'
    }
    if (current === governanceRequestId) governanceState.value = next
  } catch (cause) {
    if (current === governanceRequestId) governanceState.value = apiError(cause, '复查失败')
  }
}

async function review(row: AccessApprovalRequestItem, approved: boolean) {
  let reason: string | undefined
  if (approved) {
    // 通过会真的写出一条有有效期的临时策略，属于需要展示影响的操作（§16.5）
    try {
      await ElMessageBox.confirm(tempPolicyText(row), '确认通过访问申请', {
        type: 'warning',
        confirmButtonText: '确认通过',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  } else {
    try {
      const result = await ElMessageBox.prompt(
        '请输入拒绝理由，便于申请人回到正确的治理或授权流程。',
        '拒绝访问申请',
        {
          inputPlaceholder: '拒绝理由',
          inputValidator: (value) => Boolean(value?.trim()) || '拒绝理由不能为空',
          confirmButtonText: '确认拒绝',
          cancelButtonText: '取消',
        },
      )
      reason = result.value
    } catch {
      return
    }
  }

  reviewing.value = true
  try {
    await reviewAccessApprovalRequest(row.id, { approved, reason })
    ElMessage.success(approved ? '申请已通过，临时策略已生成' : '申请已拒绝')
    detailVisible.value = false
    await load()
  } catch (cause) {
    ElMessage.error(apiError(cause, '审批操作失败'))
  } finally {
    reviewing.value = false
  }
}

onMounted(async () => {
  await loadNames()
  await load()
})

watch(() => route.query.tab, (value) => {
  const next = String(value || 'pending')
  if (TABS.some((t) => t.name === next) && next !== activeTab.value) {
    activeTab.value = next
    load()
  }
})
</script>

<template>
  <div class="admin-page approval-page">
    <TaskPageHeader
      title="访问审批"
      description="管理员审批队列。通过后会生成有有效期的临时允许策略；审批时后端会再次复查治理状态，不绕过 BLOCKED / DEPRECATED 限制。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
      </template>
    </TaskPageHeader>

    <el-alert title="安全边界" type="warning" :closable="false">
      列表范围由后端强制收窄：拥有 security:manage 的账号看到全量审批队列，其余账号只能看到自己提交的申请。
      本页面作为管理员队列使用；普通用户「我的申请」入口按开发指导 §7.14 待产品确认后开放。
    </el-alert>

    <el-tabs :model-value="activeTab" @update:model-value="selectTab">
      <el-tab-pane v-for="tab in TABS" :key="tab.name" :label="tab.label" :name="tab.name" />
    </el-tabs>

    <!-- namesError 的提示必须先于下面的 v-if/v-else-if/v-else 链：写成三个兄弟节点时，
         el-alert 的 v-if 会另起一条链，把 LoadingState 与主列表都吞掉，名称解析失败即整页空白 -->
    <el-alert v-if="namesError" type="warning" :closable="false" :title="namesError" />
    <ErrorState v-if="error" :message="error" @retry="load" />
    <LoadingState v-else-if="loading" variant="skeleton" :rows="5" />
    <section v-else class="approval-page__card">
      <p v-if="truncated" class="approval-page__truncated">
        服务端匹配 {{ serverTotal }} 条，本页取回 {{ allRows.length }} 条并在本地分页，
        列表因此不完整。请结合数据源与状态进一步缩小范围。
      </p>
      <EmptyState v-if="!rows.length" :message="currentTab.empty" />
      <template v-else>
        <el-table :data="rows" v-loading="reviewing" stripe @row-click="openDetail">
          <el-table-column prop="id" label="申请号" width="90" />
          <el-table-column label="申请人" width="130">
            <template #default="{ row }">{{ applicantName(row.requesterId) }}</template>
          </el-table-column>
          <el-table-column label="数据源" width="150">
            <template #default="{ row }">{{ datasourceNameOf(row.datasourceId) }}</template>
          </el-table-column>
          <el-table-column label="申请对象" min-width="170">
            <template #default="{ row }">{{ targetOf(row) }}</template>
          </el-table-column>
          <el-table-column prop="requestReason" label="申请理由" min-width="200" show-overflow-tooltip />
          <el-table-column label="时长" width="90">
            <template #default="{ row }">{{ row.requestedDuration }} 小时</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }"><BusinessStatusBadge :status="row.status" /></template>
          </el-table-column>
          <el-table-column label="创建时间" prop="createdAt" width="170" />
          <el-table-column label="操作" fixed="right" width="150">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
              <template v-if="row.status === 'PENDING'">
                <el-button link type="success" @click.stop="review(row, true)">通过</el-button>
                <el-button link type="danger" @click.stop="review(row, false)">拒绝</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="allRows.length > PAGE_SIZE"
          v-model:current-page="page"
          class="approval-page__pagination"
          layout="total, prev, pager, next"
          :total="allRows.length"
          :page-size="PAGE_SIZE"
        />
      </template>
    </section>

    <!-- 审批详情：展示对象、影响与将生成的临时策略（§7.14、§16.5） -->
    <el-drawer v-model="detailVisible" title="访问申请详情" size="520px">
      <div v-if="detail" class="approval-page__detail">
        <dl>
          <div><dt>申请号</dt><dd>{{ detail.id }}</dd></div>
          <div><dt>状态</dt><dd><BusinessStatusBadge :status="detail.status" /></dd></div>
          <div><dt>申请人</dt><dd>{{ applicantName(detail.requesterId) }}</dd></div>
          <div><dt>数据源</dt><dd>{{ datasourceNameOf(detail.datasourceId) }}</dd></div>
          <div><dt>申请对象</dt><dd class="is-mono">{{ targetOf(detail) }}</dd></div>
          <div><dt>申请理由</dt><dd>{{ detail.requestReason || '（未填写）' }}</dd></div>
          <div><dt>申请时长</dt><dd>{{ detail.requestedDuration }} 小时</dd></div>
          <div><dt>创建时间</dt><dd>{{ detail.createdAt }}</dd></div>
          <div><dt>审批人</dt><dd>{{ detail.approverId ? applicantName(detail.approverId) : '尚未审批' }}</dd></div>
          <div><dt>审批时间</dt><dd>{{ detail.approvedAt || '—' }}</dd></div>
          <div><dt>过期时间</dt><dd>{{ detail.expiresAt || '—' }}</dd></div>
          <div v-if="detail.rejectReason"><dt>拒绝原因</dt><dd>{{ detail.rejectReason }}</dd></div>
        </dl>

        <section class="approval-page__detail-section">
          <h3>审批后将生成的临时策略</h3>
          <p>{{ tempPolicyText(detail) }}</p>
        </section>

        <section class="approval-page__detail-section">
          <h3>治理状态复查</h3>
          <p>当前治理状态：<BusinessStatusBadge :status="governanceState" :label="governanceLabel" /></p>
          <p class="approval-page__gap">
            后端在审批时会再次检查该表/字段是否处于 <code>BLOCKED</code> 或 <code>DEPRECATED</code>，
            被阻断的申请无法通过。上面展示的是对象<strong>当前</strong>的治理状态；申请记录本身不返回
            审批时的复查明细，因此这里不伪造一个「复查通过」的标记。
          </p>
        </section>

        <div v-if="detail.status === 'PENDING'" class="approval-page__detail-actions">
          <el-button type="primary" :loading="reviewing" @click="review(detail, true)">通过申请</el-button>
          <el-button type="danger" plain :loading="reviewing" @click="review(detail, false)">拒绝申请</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.approval-page__card {
  margin-top: 8px;
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
}

.approval-page__truncated {
  margin: 0 0 12px;
  padding: 10px 12px;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}

.approval-page__pagination {
  justify-content: flex-end;
  margin-top: 16px;
}

.approval-page__detail {
  display: grid;
  gap: 16px;
}

.approval-page__detail dl {
  display: grid;
  grid-template-columns: 84px 1fr;
  gap: 10px;
  margin: 0;
}

.approval-page__detail dt {
  color: var(--do-muted);
  font-size: 12px;
}

.approval-page__detail dd {
  margin: 0;
  color: var(--do-ink);
  font-size: 13px;
  line-height: 1.6;
  word-break: break-all;
}

.approval-page__detail dd.is-mono {
  font-family: var(--do-font-mono, monospace);
}

.approval-page__detail-section {
  display: grid;
  gap: 6px;
}

.approval-page__detail-section h3 {
  margin: 0;
  color: var(--do-ink);
  font-size: 14px;
}

.approval-page__detail-section p {
  margin: 0;
  color: var(--do-ink);
  font-size: 13px;
  line-height: 1.7;
}

.approval-page__gap {
  padding: 10px 12px;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
  color: var(--do-muted);
  font-size: 12px;
}

.approval-page__detail-actions {
  display: flex;
  gap: 10px;
}
</style>
