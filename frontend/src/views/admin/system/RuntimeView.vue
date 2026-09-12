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
import { computed, onMounted, reactive, ref, watch } from 'vue'
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
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
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

const ruleForm = reactive({
  metric: 'ERROR_RATE',
  threshold: 5,
  operator: '>',
  notificationType: 'SYSTEM',
})

const metricLabel = (metric: string) => METRICS.find((m) => m.value === metric)?.label || metric
const metricUnit = (metric: string) => METRICS.find((m) => m.value === metric)?.unit || ''
const notificationLabel = (type: string) =>
  NOTIFICATION_TYPES.find((t) => t.value === type)?.label || type
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
    AVAILABLE: { label: '正常', tone: 'success' },
    DEGRADED: { label: '降级', tone: 'warning' },
    UNAVAILABLE: { label: '不可用', tone: 'danger' },
  }
  return map[status] || { label: status || '未知', tone: 'info' }
}

async function fetchHealth() {
  healthLoading.value = true
  healthError.value = ''
  try {
    health.value = (await getSystemHealth()).data
  } catch (cause) {
    health.value = null
    // 失败必须渲染错误态。原实现把 healthData 置 null，页面最终显示
    // 「暂无健康状态数据」——把失败说成了没有数据（§11.3、§18）。
    healthError.value = apiError(cause, '服务健康状态加载失败')
  } finally {
    healthLoading.value = false
  }
}

async function fetchPools() {
  poolLoading.value = true
  poolError.value = ''
  try {
    pool.value = (await getPoolDashboard()).data
  } catch (cause) {
    pool.value = null
    poolError.value = apiError(cause, '连接池状态加载失败')
  } finally {
    poolLoading.value = false
  }
}

async function fetchRules() {
  rulesLoading.value = true
  rulesError.value = ''
  try {
    const result = await listAlertRules({ page: 1, pageSize: 50 })
    rules.value = result.data?.records || []
  } catch (cause) {
    rules.value = []
    rulesError.value = apiError(cause, '告警规则加载失败')
  } finally {
    rulesLoading.value = false
  }
}

async function fetchDatasourceNames() {
  try {
    datasourceNames.value = Object.fromEntries((((await listSimpleDatasources()).data) || []).map((d) => [d.id, d.name]))
  } catch {
    datasourceNames.value = {}
  }
}

async function handleResetPool(datasourceId: number) {
  // 高风险操作：展示目标对象、影响范围并要求二次确认（§7.18、§9 门禁）
  try {
    await ElMessageBox.confirm(
      `重置数据源「${datasourceNameOf(datasourceId)}」的 SQL 连接池？`
      + '该数据源上正在执行的查询会失去连接，正在排队的请求会重新建连。确认继续？',
      '确认重置连接池',
      { type: 'warning', confirmButtonText: '确认重置', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  resettingDatasourceId.value = datasourceId
  try {
    await resetDatasourcePool(datasourceId)
    ElMessage.success('连接池已重置')
    await fetchPools()
  } catch (cause) {
    ElMessage.error(apiError(cause, '连接池重置失败'))
  } finally {
    resettingDatasourceId.value = undefined
  }
}

function openCreateRule() {
  editingRuleId.value = undefined
  ruleForm.metric = 'ERROR_RATE'
  ruleForm.threshold = 5
  ruleForm.operator = '>'
  ruleForm.notificationType = 'SYSTEM'
  ruleDialogVisible.value = true
}

function openEditRule(row: AlertRule) {
  editingRuleId.value = row.id
  ruleForm.metric = row.metric
  ruleForm.threshold = Number(row.threshold)
  ruleForm.operator = row.operator
  ruleForm.notificationType = row.notificationType
  ruleDialogVisible.value = true
}

async function saveRule() {
  if (!ruleForm.metric || Number.isNaN(Number(ruleForm.threshold))) {
    ElMessage.warning('请选择监控指标并填写有效阈值')
    return
  }
  savingRule.value = true
  try {
    const payload = {
      metric: ruleForm.metric,
      threshold: Number(ruleForm.threshold),
      operator: ruleForm.operator,
      notificationType: ruleForm.notificationType,
    }
    if (editingRuleId.value) {
      await updateAlertRule(editingRuleId.value, payload)
      ElMessage.success('告警规则已更新')
    } else {
      await createAlertRule(payload)
      ElMessage.success('告警规则已创建')
    }
    ruleDialogVisible.value = false
    await fetchRules()
  } catch (cause) {
    ElMessage.error(apiError(cause, editingRuleId.value ? '更新告警规则失败' : '创建告警规则失败'))
  } finally {
    savingRule.value = false
  }
}

async function handleToggleRule(row: AlertRule) {
  try {
    await toggleAlertRule(row.id)
    row.enabled = !row.enabled
    ElMessage.success(row.enabled ? '规则已启用' : '规则已停用')
  } catch (cause) {
    ElMessage.error(apiError(cause, '切换规则状态失败'))
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

onMounted(async () => {
  await fetchDatasourceNames()
  await refreshCurrent()
})

watch(() => route.query.tab, (value) => {
  const next = String(value || 'health')
  if (TABS.some((t) => t.name === next) && next !== activeTab.value) {
    activeTab.value = next
    ensureTabLoaded()
  }
})
</script>

<template>
  <main class="admin-page runtime-page">
    <TaskPageHeader
      title="运行监控"
      description="观察依赖服务、SQL 连接池与告警规则配置。告警当前只支持规则配置，没有执行历史与恢复闭环。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="refreshing" @click="refreshCurrent">刷新</el-button>
      </template>
    </TaskPageHeader>

    <el-tabs :model-value="activeTab" @update:model-value="selectTab">
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
          <EmptyState v-else message="没有取到健康状态。点击刷新重新请求。" action-text="刷新" @action="fetchHealth" />
        </section>
      </el-tab-pane>

      <el-tab-pane label="SQL 连接池" name="pools">
        <section class="runtime-page__panel">
          <p class="runtime-page__note">
            当前活跃连接池 {{ pool?.activePools ?? 0 }} 个。重置连接池是高风险操作，
            会影响该数据源上正在执行的查询。
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
                  @click="handleResetPool(row.datasourceId)"
                >重置连接池</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="告警规则" name="alerts">
        <section class="runtime-page__panel">
          <div class="runtime-page__section-heading">
            <div>
              <h3>告警规则配置</h3>
              <p>
                当前**只支持规则配置**：可以定义监控指标与阈值并启停。
                后端没有告警执行记录、历史、恢复与去重闭环，因此本页不展示告警次数、
                恢复率或历史趋势。
              </p>
            </div>
            <el-button type="primary" :icon="Plus" @click="openCreateRule">新增规则</el-button>
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
                <el-switch :model-value="row.enabled" size="small" @change="handleToggleRule(row)" />
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="175" />
            <el-table-column label="操作" width="100" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEditRule(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
          <p class="runtime-page__note">
            规则启用后由后端定时任务评估；在评估逻辑与历史闭环落地前，
            本页不呈现任何执行结果统计。
          </p>
          <p class="runtime-page__gap">
            后端缺口：告警规则**没有删除端点**（`AlertController` 只有 列表 / 创建 / 更新 / 启停四个），
            因此本页不提供删除操作——不做一个点了会失败的按钮。需要停用规则时请使用启停开关。
          </p>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="ruleDialogVisible" :title="editingRuleId ? '编辑告警规则' : '新增告警规则'" width="520px">
      <el-form label-width="92px">
        <el-form-item label="监控指标">
          <el-select v-model="ruleForm.metric" style="width: 100%">
            <el-option v-for="m in METRICS" :key="m.value" :label="`${m.label}（${m.value}）`" :value="m.value" />
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
        <el-button @click="ruleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingRule" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
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
