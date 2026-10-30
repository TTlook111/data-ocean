<script setup lang="ts">
/**
 * 运行监控工作区（开发指导 §7.18）
 *
 * 固定 Tab：`服务状态 | SQL 连接池 | 告警规则`，Tab 由 `?tab=` 恢复。
 *
 * 原实现是扁平单页（0 个 Tab），且**完全没有告警规则区域**——后端 `AlertController`
 * 的四个 CRUD 接口已封装在 `api/admin/audit.ts` 中却零引用。本次补齐。
 *
 * **告警只做到规则配置为止。** 后端没有告警执行记录、历史、恢复与去重闭环，
 * 因此本页面**不显示告警次数、恢复率或历史趋势**（§7.18、§18
 * 「不把告警规则 CRUD 包装成完整告警闭环」）。
 *
 * 连接池重置属高风险操作：必须展示目标数据源并二次确认（§7.18、§9 门禁）。
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Activity, Cpu, Database, Plus, RefreshCw, RotateCcw, Server } from 'lucide-vue-next'
import {
  getPoolDashboard,
  getSystemHealth,
  resetDatasourcePool,
  type HealthData,
  type PoolDashboardInfo,
} from '../../../api/admin/system'
import {
  createAlertRule,
  listAlertRules,
  toggleAlertRule,
  updateAlertRule,
  type AlertRule,
} from '../../../api/admin/audit'
import { listSimpleDatasources } from '../../../api/admin/datasource'
import { useIamWorkspaceSession } from '../../../composables/useIamWorkspaceSession'
import { trackGovernanceWrite } from '../../../composables/governanceWriteTracker'
import GovernanceWriteNotice from '../../../components/admin/GovernanceWriteNotice.vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import TitleHelp from '../../../components/admin/TitleHelp.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const TABS = [
  { name: 'health', label: '服务状态' },
  { name: 'pools', label: 'SQL 连接池' },
  { name: 'alerts', label: '告警规则' },
] as const

/** 后端 `alert_rule` 表注释给出的取值（V21 迁移） */
const METRICS = [
  { value: 'ERROR_RATE', label: '查询错误率', unit: '%' },
  { value: 'SLOW_QUERY_COUNT', label: '慢查询数量', unit: '条' },
]
const OPERATORS = ['>', '>=', '<', '<=', '=']
const NOTIFICATION_TYPES = [
  { value: 'SYSTEM', label: '系统通知' },
  { value: 'EMAIL', label: '邮件' },
]

const session = useIamWorkspaceSession()
const iamS1 = session.iam
const writeError = ref(''),
  busy = ref(false),
  rulePage = ref(1),
  ruleTotal = ref<number | null>(null)
const readEpoch = { health: 0, pools: 0, rules: 0, names: 0 }
const owned = (k: number, name: keyof typeof readEpoch, r: number) =>
  session.current(k) && readEpoch[name] === r
const canViewRuntime = computed(() => session.canGlobal('system:runtime:view'))
const canManageRuntime = computed(() => session.canGlobal('system:runtime:manage'))
const canResetPool = computed(() => iamS1.systemAdmin && canManageRuntime.value)
const RESET_HINT = '重置连接池只能由受保护的系统管理员执行'
const MANAGE_HINT = '需要 system:runtime:manage'

const route = useRoute()
const router = useRouter()

const activeTab = ref(TABS.some((t) => t.name === route.query.tab) ? String(route.query.tab) : 'health')

const health = ref<HealthData | null>(null)
const healthLoading = ref(false)
const healthError = ref('')

const pool = ref<PoolDashboardInfo | null>(null)
const poolLoading = ref(false)
const poolError = ref('')
const resettingDatasourceId = ref<number>()

const rules = ref<AlertRule[]>([])
const rulesLoading = ref(false)
const rulesError = ref('')
const ruleDialogVisible = ref(false)
const editingRuleId = ref<number>()
const savingRule = ref(false)

const datasourceNames = ref<Record<number, string>>({})

const ruleForm = reactive<{
  metric: string
  threshold: number | undefined
  operator: string
  notificationType: string
}>({
  metric: 'ERROR_RATE',
  threshold: 5,
  operator: '>',
  notificationType: 'SYSTEM',
})

const metricLabel = (metric: string) => METRICS.find((m) => m.value === metric)?.label || metric
const metricUnit = (metric: string) => METRICS.find((m) => m.value === metric)?.unit || ''
const notificationLabel = (type: string) => NOTIFICATION_TYPES.find((t) => t.value === type)?.label || type
const datasourceNameOf = (id: number) => datasourceNames.value[id] || `数据源 #${id}`

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

function selectTab(name: string | number) {
  activeTab.value = String(name)
  router.push({ query: { ...route.query, tab: activeTab.value } })
  ensureTabLoaded()
}

function ensureTabLoaded() {
  if (activeTab.value === 'health' && !health.value) fetchHealth()
  if (activeTab.value === 'pools' && !pool.value) fetchPools()
  if (activeTab.value === 'alerts' && !rules.value.length) fetchRules()
}

/** 服务状态徽标。状态只用颜色表达是不够的，必须同时给出中文（§11.3） */
function statusMeta(status: string) {
  const map: Record<string, { label: string; tone: string }> = {
    HEALTHY: { label: '健康', tone: 'success' },
    UNKNOWN: { label: '未确认', tone: 'info' },
    AVAILABLE: { label: '正常', tone: 'success' },
    DEGRADED: { label: '降级', tone: 'warning' },
    UNAVAILABLE: { label: '不可用', tone: 'danger' },
  }
  return map[status] || { label: status || '未知', tone: 'info' }
}

async function fetchHealth() {
  if (!canViewRuntime.value || healthLoading.value) return
  const k = session.capture(),
    r = ++readEpoch.health
  healthLoading.value = true
  healthError.value = ''
  health.value = null
  try {
    const data = (await getSystemHealth()).data
    if (!owned(k, 'health', r)) return
    if (
      !data ||
      !data.overall ||
      [data.pythonService, data.mysql, data.redis].some(
        (x) => !x || typeof x.status !== 'string' || !x.status,
      )
    )
      throw new Error('健康状态回执不完整，不能确认为健康')
    health.value = data
  } catch (cause) {
    if (owned(k, 'health', r)) healthError.value = apiError(cause, '服务健康状态加载失败')
  } finally {
    if (owned(k, 'health', r)) healthLoading.value = false
  }
}
async function fetchPools() {
  if (!canViewRuntime.value || poolLoading.value) return
  const k = session.capture(),
    r = ++readEpoch.pools
  poolLoading.value = true
  poolError.value = ''
  pool.value = null
  try {
    const data = (await getPoolDashboard()).data
    if (!owned(k, 'pools', r)) return
    if (data?.error) throw new Error(`Python服务连接失败：${data.error}`)
    if (
      !data ||
      !Number.isSafeInteger(data.activePools) ||
      data.activePools < 0 ||
      !Array.isArray(data.pools) ||
      data.pools.some((x) => !Number.isSafeInteger(x.datasourceId) || x.datasourceId <= 0) ||
      new Set(data.pools.map((x) => x.datasourceId)).size !== data.pools.length
    )
      throw new Error('连接池回执不完整，数量未确认')
    pool.value = data
  } catch (cause) {
    if (owned(k, 'pools', r)) poolError.value = apiError(cause, '连接池状态加载失败')
  } finally {
    if (owned(k, 'pools', r)) poolLoading.value = false
  }
  if (session.current(k)) void fetchDatasourceNames()
}
async function fetchRules() {
  if (!canViewRuntime.value || rulesLoading.value) return
  const k = session.capture(),
    r = ++readEpoch.rules,
    page = rulePage.value
  rulesLoading.value = true
  rulesError.value = ''
  rules.value = []
  ruleTotal.value = null
  try {
    const data = (await listAlertRules({ page, pageSize: 20 })).data
    if (!owned(k, 'rules', r) || page !== rulePage.value) return
    if (
      !data ||
      !Array.isArray(data.records) ||
      !Number.isSafeInteger(data.total) ||
      data.total < 0 ||
      data.records.some((x) => !Number.isSafeInteger(x.id) || x.id <= 0 || typeof x.enabled !== 'boolean')
    )
      throw new Error('告警规则回执不完整')
    rules.value = data.records
    ruleTotal.value = data.total
  } catch (cause) {
    if (owned(k, 'rules', r) && page === rulePage.value)
      rulesError.value = apiError(cause, '告警规则加载失败')
  } finally {
    if (owned(k, 'rules', r)) rulesLoading.value = false
  }
}
function changeRulePage(page: number) {
  rulePage.value = page
  readEpoch.rules++
  rulesLoading.value = false
  void fetchRules()
}
async function fetchDatasourceNames() {
  const k = session.capture(),
    r = ++readEpoch.names
  if (!session.ready.value || !(iamS1.systemAdmin || iamS1.datasourcesWithFunction('datasource:view').length))
    return
  try {
    const rows = (await listSimpleDatasources()).data
    if (owned(k, 'names', r) && Array.isArray(rows))
      datasourceNames.value = Object.fromEntries(
        rows.filter((x) => iamS1.canOnDatasource('datasource:view', x.id)).map((x) => [x.id, x.name]),
      )
  } catch {
    if (owned(k, 'names', r)) datasourceNames.value = {}
  }
}
async function handleResetPool(datasourceId: number) {
  if (!canResetPool.value || busy.value || !pool.value?.pools.some((x) => x.datasourceId === datasourceId))
    return
  const k = session.capture(),
    identity = session.auth.token
  busy.value = true
  writeError.value = ''
  try {
    const { value: reason } = await ElMessageBox.prompt(
      `重置数据源 #${datasourceId}「${datasourceNameOf(datasourceId)}」的SQL连接池？会影响该源正在执行的查询。`,
      '确认重置连接池',
      {
        type: 'warning',
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        inputPlaceholder: '重置原因',
        inputPattern: /\S+/,
        inputErrorMessage: '必须说明重置原因',
        closeOnClickModal: false,
        cancelButtonClass: session.cancelClass,
      },
    )
    if (
      !session.current(k) ||
      !canResetPool.value ||
      !pool.value?.pools.some((x) => x.datasourceId === datasourceId)
    )
      return
    resettingDatasourceId.value = datasourceId
    await trackGovernanceWrite(
      identity,
      [`runtime:pool:${datasourceId}`],
      `重置连接池 #${datasourceId}`,
      () => resetDatasourcePool(datasourceId, { confirmed: true, reason: String(reason) }),
    )
    if (session.current(k)) {
      ElMessage.success('连接池重置已受理')
      readEpoch.pools++
      poolLoading.value = false
      await fetchPools()
    }
  } catch (cause) {
    if (session.current(k) && cause !== 'cancel' && cause !== 'close')
      writeError.value = apiError(cause, '重置结果未知时先核对连接池，不自动重发')
  } finally {
    if (session.current(k)) {
      busy.value = false
      resettingDatasourceId.value = undefined
    }
  }
}

function openCreateRule() {
  if (!canManageRuntime.value || busy.value) {
    ElMessage.warning(MANAGE_HINT)
    return
  }
  editingRuleId.value = undefined
  ruleForm.metric = 'ERROR_RATE'
  ruleForm.threshold = 5
  ruleForm.operator = '>'
  ruleForm.notificationType = 'SYSTEM'
  ruleDialogVisible.value = true
}

function openEditRule(row: AlertRule) {
  if (!canManageRuntime.value || busy.value) {
    ElMessage.warning(MANAGE_HINT)
    return
  }
  editingRuleId.value = row.id
  ruleForm.metric = row.metric
  ruleForm.threshold =
    typeof row.threshold === 'number' && Number.isFinite(row.threshold) ? row.threshold : undefined
  ruleForm.operator = row.operator
  ruleForm.notificationType = row.notificationType
  ruleDialogVisible.value = true
}

async function saveRule() {
  if (!canManageRuntime.value || busy.value || savingRule.value) return
  const k = session.capture(),
    id = editingRuleId.value,
    identity = session.auth.token
  const payload = { ...ruleForm }
  if (
    !METRICS.some((x) => x.value === payload.metric) ||
    !OPERATORS.includes(payload.operator) ||
    !NOTIFICATION_TYPES.some((x) => x.value === payload.notificationType) ||
    typeof payload.threshold !== 'number' ||
    !Number.isFinite(payload.threshold) ||
    payload.threshold < 0
  ) {
    writeError.value = '请选择合法指标、条件、通知方式和非负阈值'
    return
  }
  busy.value = true
  savingRule.value = true
  writeError.value = ''
  try {
    await trackGovernanceWrite(
      identity,
      [`runtime:rule:${id || 'new'}`],
      `保存告警规则 ${id || '新建'}`,
      async () => {
        const result = id ? await updateAlertRule(id, payload) : await createAlertRule(payload)
        if (
          !result.data ||
          !Number.isSafeInteger(result.data.id) ||
          result.data.id <= 0 ||
          (id && result.data.id !== id)
        )
          throw new Error('规则保存回执不完整，结果未知；先核对原记录')
        return result
      },
    )
    if (session.current(k)) {
      ruleDialogVisible.value = false
      readEpoch.rules++
      rulesLoading.value = false
      await fetchRules()
    }
  } catch (cause) {
    if (session.current(k)) writeError.value = apiError(cause, '规则保存失败，草稿保留；未知结果先核对')
  } finally {
    if (session.current(k)) {
      savingRule.value = false
      busy.value = false
    }
  }
}
const ruleSignature = (row: AlertRule) =>
  JSON.stringify([row.id, row.metric, row.threshold, row.operator, row.notificationType, row.enabled])
async function handleToggleRule(row: AlertRule) {
  if (!canManageRuntime.value || busy.value || typeof row.enabled !== 'boolean') return
  const k = session.capture(),
    id = row.id,
    signature = ruleSignature(row),
    enabled = row.enabled,
    identity = session.auth.token
  busy.value = true
  writeError.value = ''
  try {
    await ElMessageBox.confirm(
      `规则 #${id}：${metricLabel(row.metric)} ${row.operator} ${row.threshold} ${metricUnit(row.metric)}。确认${enabled ? '停用' : '启用'}配置？启用不代表已执行监控。`,
      '确认告警规则状态',
      {
        confirmButtonText: '确认变更',
        cancelButtonText: '取消',
        closeOnClickModal: false,
        cancelButtonClass: session.cancelClass,
      },
    )
    if (
      !session.current(k) ||
      !canManageRuntime.value ||
      !rules.value.some((x) => x.id === id && ruleSignature(x) === signature)
    )
      return
    await trackGovernanceWrite(identity, [`runtime:rule:${id}`], `切换告警规则 #${id}`, () =>
      toggleAlertRule(id),
    )
    if (session.current(k)) {
      readEpoch.rules++
      rulesLoading.value = false
      await fetchRules()
    }
  } catch (cause) {
    if (session.current(k) && cause !== 'cancel' && cause !== 'close')
      writeError.value = apiError(cause, '状态变更结果未知时核对原规则，不自动重复切换')
  } finally {
    if (session.current(k)) busy.value = false
  }
}

function formatPoolTime(value?: number) {
  if (!value) return '—'
  const date = new Date(value * 1000)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

async function refreshCurrent() {
  if (activeTab.value === 'health') return fetchHealth()
  if (activeTab.value === 'pools') return fetchPools()
  return fetchRules()
}

const refreshing = computed(() => healthLoading.value || poolLoading.value || rulesLoading.value)

watch(
  session.key,
  () => {
    Object.keys(readEpoch).forEach((key) => readEpoch[key as keyof typeof readEpoch]++)
    health.value = null
    pool.value = null
    rules.value = []
    datasourceNames.value = {}
    ruleTotal.value = null
    healthLoading.value = false
    poolLoading.value = false
    rulesLoading.value = false
    healthError.value = ''
    poolError.value = ''
    rulesError.value = ''
    writeError.value = ''
    ruleDialogVisible.value = false
    busy.value = false
    savingRule.value = false
    resettingDatasourceId.value = undefined
    if (canViewRuntime.value) void refreshCurrent()
  },
  { immediate: true },
)

watch(
  () => route.query.tab,
  (value) => {
    const next = String(value || 'health')
    if (TABS.some((t) => t.name === next) && next !== activeTab.value) {
      activeTab.value = next
      ensureTabLoaded()
    }
  },
)
</script>

<template>
  <main class="admin-page runtime-page">
    <p class="runtime-eyebrow">运营与平台 / 运行事实</p>
    <GovernanceWriteNotice />
    <p v-if="writeError" class="runtime-page__alert" role="alert">{{ writeError }}</p>
    <TaskPageHeader
      title="运行监控"
      description="观察依赖服务、SQL 连接池与告警规则配置。告警当前只支持规则配置，没有执行历史与恢复闭环。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="refreshing" @click="refreshCurrent">刷新</el-button>
      </template>
    </TaskPageHeader>

    <ErrorState v-if="!canViewRuntime" message="当前没有运行状态查看权限，未请求服务。" />
    <el-tabs v-else :model-value="activeTab" @update:model-value="selectTab">
      <el-tab-pane label="服务状态" name="health">
        <section class="runtime-page__panel">
          <ErrorState v-if="healthError" :message="healthError" @retry="fetchHealth" />
          <LoadingState v-else-if="healthLoading && !health" variant="skeleton" :rows="4" />
          <template v-else-if="health">
            <div class="runtime-page__grid">
              <article class="runtime-page__card runtime-page__card--wide">
                <Activity :size="24" aria-hidden="true" />
                <div>
                  <h3>系统总体状态</h3>
                  <el-tag :type="statusMeta(health.overall).tone as 'success'" size="small">
                    {{ statusMeta(health.overall).label }}
                  </el-tag>
                  <p class="runtime-page__meta">检查时间 {{ health.checkTime }}</p>
                </div>
              </article>

              <article class="runtime-page__card">
                <Cpu :size="24" aria-hidden="true" />
                <div>
                  <h3>Python AI 服务</h3>
                  <el-tag :type="statusMeta(health.pythonService.status).tone as 'success'" size="small">
                    {{ statusMeta(health.pythonService.status).label }}
                  </el-tag>
                  <p class="runtime-page__meta">{{ health.pythonService.description }}</p>
                  <p v-if="health.pythonService.lastCheckTime" class="runtime-page__meta">
                    最后检查 {{ health.pythonService.lastCheckTime }}
                  </p>
                  <p v-if="health.pythonService.consecutiveFailures" class="runtime-page__meta">
                    连续失败 {{ health.pythonService.consecutiveFailures }} 次
                  </p>
                  <p v-if="health.pythonService.lastErrorMessage" class="runtime-page__alert">
                    {{ health.pythonService.lastErrorMessage }}
                  </p>
                </div>
              </article>

              <article class="runtime-page__card">
                <Database :size="24" aria-hidden="true" />
                <div>
                  <h3>MySQL 数据库</h3>
                  <el-tag :type="statusMeta(health.mysql.status).tone as 'success'" size="small">
                    {{ statusMeta(health.mysql.status).label }}
                  </el-tag>
                  <p class="runtime-page__meta">{{ health.mysql.description }}</p>
                </div>
              </article>

              <article class="runtime-page__card">
                <Server :size="24" aria-hidden="true" />
                <div>
                  <h3>Redis 缓存</h3>
                  <el-tag :type="statusMeta(health.redis.status).tone as 'success'" size="small">
                    {{ statusMeta(health.redis.status).label }}
                  </el-tag>
                  <p class="runtime-page__meta">{{ health.redis.description }}</p>
                </div>
              </article>
            </div>
          </template>
          <EmptyState
            v-else
            message="没有取到健康状态。点击刷新重新请求。"
            action-text="刷新"
            @action="fetchHealth"
          />
        </section>
      </el-tab-pane>

      <el-tab-pane label="SQL 连接池" name="pools">
        <section class="runtime-page__panel">
          <p class="runtime-page__note">
            <!-- poolError 时不能报「0 个」：那是把「取不到」说成「没有」，与下方错误态自相矛盾 -->
            <template v-if="poolError">连接池状态当前不可用。</template>
            <template v-else>当前活跃连接池 {{ pool?.activePools ?? '未确认' }} 个。</template>
            重置连接池是高风险操作，会影响该数据源上正在执行的查询。
          </p>
          <ErrorState v-if="poolError" :message="poolError" @retry="fetchPools" />
          <LoadingState v-else-if="poolLoading && !pool" variant="skeleton" :rows="4" />
          <EmptyState
            v-else-if="!pool?.pools?.length"
            message="当前没有活跃的 SQL 连接池。数据源被查询或健康检查后会创建连接池。"
          />
          <el-table v-else :data="pool.pools" border row-key="datasourceId">
            <el-table-column label="数据源" min-width="160">
              <template #default="{ row }">{{ datasourceNameOf(row.datasourceId) }}</template>
            </el-table-column>
            <el-table-column prop="poolSize" label="连接数" width="100" />
            <el-table-column label="创建时间" width="170">
              <template #default="{ row }">{{ formatPoolTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="最后使用" width="170">
              <template #default="{ row }">{{ formatPoolTime(row.lastUsedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="140" align="center">
              <template #default="{ row }">
                <el-button
                  type="danger"
                  plain
                  size="small"
                  :icon="RotateCcw"
                  :loading="resettingDatasourceId === row.datasourceId"
                  :disabled="!canResetPool || busy"
                  :title="canResetPool ? '' : RESET_HINT"
                  @click="handleResetPool(row.datasourceId)"
                >
                  重置连接池
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="告警规则" name="alerts">
        <section class="runtime-page__panel">
          <div class="runtime-page__section-heading">
            <div class="runtime-page__section-title">
              <TitleHelp
                label="告警规则配置"
                text="当前只支持规则配置：可以定义监控指标与阈值并启停。后端没有告警执行记录、历史、恢复与去重闭环，因此本页不展示告警次数、恢复率或历史趋势。"
              />
              <h3>告警规则配置</h3>
              <span class="runtime-page__configuration-only">仅支持配置</span>
            </div>
            <el-button
              type="primary"
              :icon="Plus"
              :disabled="!canManageRuntime || busy"
              :title="canManageRuntime ? '' : MANAGE_HINT"
              @click="openCreateRule"
            >
              新增规则
            </el-button>
          </div>
          <ErrorState v-if="rulesError" :message="rulesError" @retry="fetchRules" />
          <LoadingState v-else-if="rulesLoading" variant="skeleton" :rows="4" />
          <EmptyState
            v-else-if="!rules.length"
            message="还没有告警规则。创建规则后可以定义监控指标与触发阈值。"
            action-text="新增规则"
            @action="openCreateRule"
          />
          <el-table v-else :data="rules" stripe>
            <el-table-column label="监控指标" min-width="150">
              <template #default="{ row }">{{ metricLabel(row.metric) }}</template>
            </el-table-column>
            <el-table-column label="触发条件" width="150">
              <template #default="{ row }">
                {{ row.operator }} {{ row.threshold }} {{ metricUnit(row.metric) }}
              </template>
            </el-table-column>
            <el-table-column label="通知方式" width="120">
              <template #default="{ row }">{{ notificationLabel(row.notificationType) }}</template>
            </el-table-column>
            <el-table-column label="启用" width="90" align="center">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.enabled"
                  size="small"
                  :disabled="!canManageRuntime || busy"
                  @change="handleToggleRule(row)"
                />
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="175" />
            <el-table-column label="操作" width="100" align="center">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :disabled="!canManageRuntime || busy"
                  @click="openEditRule(row)"
                >
                  编辑
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-if="ruleTotal !== null && ruleTotal > 20"
            :current-page="rulePage"
            :page-size="20"
            :total="ruleTotal"
            layout="prev, pager, next, total"
            @current-change="changeRulePage"
          />
          <p class="runtime-page__note">
            当前仅保存规则配置，后端尚未实现定时评估；启用状态不代表规则正在执行。
          </p>
          <p class="runtime-page__gap">
            后端缺口：告警规则没有删除端点（AlertController 只有列表、创建、更新、启停四个），
            因此本页不提供删除操作——不做一个点了会失败的按钮。需要停用规则时请使用启停开关。
          </p>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="ruleDialogVisible"
      :title="editingRuleId ? '编辑告警规则' : '新增告警规则'"
      width="520px"
      :close-on-click-modal="!savingRule"
      :close-on-press-escape="!savingRule"
      :show-close="!savingRule"
    >
      <p v-if="writeError" role="alert">{{ writeError }}</p>
      <el-form label-position="top" :disabled="savingRule">
        <el-form-item label="监控指标">
          <el-select v-model="ruleForm.metric" style="width: 100%">
            <el-option
              v-for="m in METRICS"
              :key="m.value"
              :label="`${m.label}（${m.value}）`"
              :value="m.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="比较运算符">
          <el-select v-model="ruleForm.operator" style="width: 100%">
            <el-option v-for="op in OPERATORS" :key="op" :label="op" :value="op" />
          </el-select>
        </el-form-item>
        <el-form-item label="阈值">
          <el-input-number v-model="ruleForm.threshold" :min="0" :step="1" style="width: 100%" />
          <span class="runtime-page__hint">单位：{{ metricUnit(ruleForm.metric) }}</span>
        </el-form-item>
        <el-form-item label="通知方式">
          <el-select v-model="ruleForm.notificationType" style="width: 100%">
            <el-option v-for="n in NOTIFICATION_TYPES" :key="n.value" :label="n.label" :value="n.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="savingRule" @click="ruleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingRule" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.runtime-eyebrow {
  margin: 0;
  font-size: 12px;
  font-weight: 650;
  color: var(--do-primary);
}
.runtime-page {
  display: grid;
  gap: 20px;
}
.runtime-page__card {
  box-shadow: none !important;
  border-radius: 12px !important;
}
.runtime-page__card--wide {
  background: var(--do-bg) !important;
}
.runtime-page :deep(.el-dialog) {
  max-width: calc(100vw - 24px);
}
.runtime-page__panel {
  display: grid;
  gap: 14px;
}

.runtime-page__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.runtime-page__card {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
  color: var(--do-primary-strong);
}

.runtime-page__card--wide {
  grid-column: 1 / -1;
}

.runtime-page__card div {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.runtime-page__card h3 {
  margin: 0;
  color: var(--do-ink);
  font-size: 14px;
}

.runtime-page__meta {
  margin: 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
}

.runtime-page__alert {
  margin: 0;
  color: var(--do-danger);
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
}

.runtime-page__note,
.runtime-page__hint {
  margin: 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}

.runtime-page__note {
  padding: 10px 12px;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
}

.runtime-page__gap {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}

.runtime-page__hint {
  margin-left: 8px;
}

.runtime-page__section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.runtime-page__section-title {
  position: relative;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding-right: 14px;
}
.runtime-page__configuration-only {
  padding: 3px 8px;
  border-radius: var(--do-radius-full);
  color: var(--do-muted);
  background: var(--do-soft);
  font-size: 12px;
}

.runtime-page__section-heading h3 {
  margin: 0;
  color: var(--do-ink);
  font-size: 15px;
}

.runtime-page__section-heading p {
  margin: 5px 0 0;
  max-width: 720px;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}
</style>
