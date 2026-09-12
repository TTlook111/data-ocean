<script setup lang="ts">
/**
 * 授权管理工作区（开发指导 §7.13）
 *
 * 合并原 `AccessControl.vue`（数据源授权 + 内嵌权限预览）与 `PolicyEditor.vue`（表列策略），
 * 形成三个固定 Tab：`数据源授权 | 表列策略 | 最终权限预览`。
 *
 * **主体驱动的操作流程**：先选数据源（由全局 `ScopeBar` 提供），再选主体（用户/角色/部门），
 * 然后依次查看数据源级授权 → 表列范围/行过滤/脱敏 → 最终权限结果。
 *
 * **数据源范围不再由页面自建选择器提供。** 原实现直连 `/api/admin/datasources/simple`
 * 自建了一个下拉，与 `AdminShell` 注入的 `ScopeBar` 同页并存且互不同步——这既违反
 * §6.2 规则 8「页面内部不得再创建一套与全局上下文无关的数据源选择状态」，也让
 * §16.4「管理员不会因顶部和页面数据源不一致而误授权」不成立。现统一读 `adminContext`。
 *
 * 后端事实（permission 模块）：
 * - 数据源级授权与表列策略是两层概念，分别对应 `/datasource-access` 与 `/access-policies`。
 * - 最终权限必须用 `/decision` 接口计算，不能只列规则（§3.11）。
 * - **后端能力现状**（2026-09-12 更新）：`AccessPolicyVO` 已暴露 `priority` /
 *   `validFrom` / `validUntil`，`DatasourcePermissionVO` 已暴露 `expiresAt`，
 *   因此「表列策略」与「数据源授权」两个 Tab 都能展示真实数据。
 *   仅**数据源级授权的 `priority`** 仍无数据来源，在对应 Tab 内如实说明，不伪造字段。
 */
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, RefreshCw, Trash2 } from 'lucide-vue-next'
import {
  createAccessPolicy,
  deleteAccessPolicy,
  getDatasourcePermissionDecision,
  grantDatasourcePermission,
  listAccessPolicies,
  listDatasourcePermissions,
  revokeDatasourcePermission,
  updateDatasourcePermission,
  type AccessPolicyItem,
  type AccessPolicyPayload,
  type DatasourcePermissionDecision,
  type DatasourcePermissionItem,
  type DatasourcePermissionPayload,
} from '../../../api/admin/permission'
import { listDepartments, listRoles, listUsers, type DepartmentNode } from '../../../api/admin/user'
import { getPublishedSnapshot } from '../../../api/admin/versioning'
import { useAdminContextStore } from '../../../stores/adminContext'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import ResourceScopeSelector from '../../../components/ResourceScopeSelector.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

/** 用户下拉一次最多取 100 条：`PageRequest.MAX_PAGE_SIZE` 是后端硬上限 */
const USER_PAGE_SIZE = 100

interface SubjectOption { id: number; name: string }

/** 将部门树拍平为一维列表，供主体下拉选择 */
function flattenDepartments(nodes: DepartmentNode[]): Array<{ id: number; name: string }> {
  const result: Array<{ id: number; name: string }> = []
  const walk = (list: DepartmentNode[]) => {
    for (const node of list) {
      result.push({ id: node.id, name: node.deptName })
      if (node.children && node.children.length > 0) walk(node.children)
    }
  }
  walk(nodes)
  return result
}

const route = useRoute()
const router = useRouter()
const context = useAdminContextStore()

/** 当前数据源由全局上下文决定，页面不再自建选择器 */
const datasourceId = computed(() => context.datasourceId)
const datasourceName = computed(() =>
  context.currentDatasource?.name || (datasourceId.value ? `数据源 #${datasourceId.value}` : '未选择数据源'))

const TAB_NAMES = ['grants', 'policies', 'decision'] as const
const activeTab = ref(TAB_NAMES.includes(route.query.tab as never) ? String(route.query.tab) : 'grants')

// 页面级主体选择：驱动「数据源授权」与「表列策略」两个 Tab
const subjectType = ref('ROLE')
const subjectId = ref<number>()
const roles = ref<SubjectOption[]>([])
const users = ref<SubjectOption[]>([])
const departments = ref<SubjectOption[]>([])
const usersTotal = ref(0)
const subjectsLoading = ref(false)
const subjectsError = ref('')

const permissions = ref<DatasourcePermissionItem[]>([])
const grantsLoading = ref(false)
const grantsError = ref('')

const policies = ref<AccessPolicyItem[]>([])
const policiesLoading = ref(false)
const policiesError = ref('')
const policyTableFilter = ref('')
let grantsRequestId = 0
let policiesRequestId = 0

const decisionUserId = ref<number>()
const decisionLoading = ref(false)
const decisionError = ref('')
const decision = ref<DatasourcePermissionDecision>()

const grantDialogVisible = ref(false)
const policyDialogVisible = ref(false)
const actionLoading = ref(false)

const subjectTypes = [
  { value: 'USER', label: '用户' },
  { value: 'ROLE', label: '角色' },
  { value: 'DEPARTMENT', label: '部门' },
]

const accessTypes = [
  { value: 'ALLOW', label: '允许' },
  { value: 'DENY', label: '禁止' },
  { value: 'MASK', label: '脱敏' },
]

const maskStrategies = [
  { value: 'PHONE', label: '手机号' },
  { value: 'ID_CARD', label: '身份证' },
  { value: 'EMAIL', label: '邮箱' },
  { value: 'BANK_CARD', label: '银行卡' },
  { value: 'NAME', label: '姓名' },
]

const subjectOptions = computed(() => {
  if (subjectType.value === 'USER') return users.value
  if (subjectType.value === 'DEPARTMENT') return departments.value
  return roles.value
})

const subjectTypeLabel = (type: string) => subjectTypes.find((t) => t.value === type)?.label || type

const grantForm = reactive<DatasourcePermissionPayload>({
  datasourceId: 0,
  subjectType: 'ROLE',
  subjectId: 0,
  accessEffect: 'ALLOW',
  canQuery: true,
  canExport: false,
  canViewSql: true,
})

const policyForm = reactive<AccessPolicyPayload>({
  datasourceId: 0,
  subjectType: 'ROLE',
  subjectId: 0,
  tableName: '',
  columnName: '',
  accessType: 'DENY',
  maskStrategy: '',
  rowFilterExpression: '',
})
/** 策略对话框里的表/列选择器需要快照范围，只用于选表，不写回全局上下文 */
/**
 * 新建策略时锁定的资源范围。
 *
 * 表/字段必须取自**已发布快照**——后端 `validateTableName` / `validateColumnName`
 * 也只认发布快照（`AccessPolicyServiceImpl`）。因此这里不用全局 `adminContext.snapshotId`
 * （可能是草稿快照），并把快照选择器锁死，避免用户手动切到草稿后拿到难懂的报错。
 */
const policySnapshot = ref<number>()
const policySnapshotLabel = ref('')

const decisionSourceText = computed(() => {
  if (!decision.value) return ''
  const labels: Record<string, string> = {
    USER: '用户直接授权',
    ROLE: '角色授权',
    DEPARTMENT: '部门授权',
    NONE: '无有效授权',
    '*': '超级管理员权限',
  }
  return labels[decision.value.decisionSource] || decision.value.decisionSource
})

const effectLabel = (effect?: string) => effect === 'DENY' ? '禁止' : effect === 'ALLOW' ? '允许' : '无授权'
const visiblePermissions = computed(() => subjectId.value
  ? permissions.value.filter((item) => item.subjectId === subjectId.value)
  : permissions.value)

const accessTypeTag = (type: string) => (type === 'DENY' ? 'danger' : type === 'MASK' ? 'warning' : 'success')
const accessTypeLabel = (type: string) => accessTypes.find((t) => t.value === type)?.label || type

/** 主操作随 Tab 变化，保证同一时刻只有一个最突出的操作（§11.2） */
const primaryAction = computed(() => {
  if (activeTab.value === 'grants') return { label: '新增授权', run: openGrantDialog }
  if (activeTab.value === 'policies') return { label: '新增策略', run: openPolicyDialog }
  return null
})

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

function selectTab(tab: string | number) {
  activeTab.value = String(tab)
  router.push({ query: { ...route.query, tab: activeTab.value } })
}

async function loadSubjects() {
  subjectsLoading.value = true
  subjectsError.value = ''
  try {
    const [rolesRes, usersRes, deptTree] = await Promise.all([
      listRoles(),
      listUsers({ page: 1, pageSize: USER_PAGE_SIZE }),
      listDepartments(),
    ])
    roles.value = (rolesRes.data || []).map((r) => ({ id: r.id, name: r.roleName }))
    const records = usersRes.data?.records || []
    usersTotal.value = usersRes.data?.total ?? records.length
    users.value = records.map((u) => ({ id: u.id, name: u.realName || u.username }))
    departments.value = flattenDepartments(deptTree.data || []).map((d) => ({ id: d.id, name: d.name }))
  } catch (cause) {
    roles.value = []
    users.value = []
    departments.value = []
    // 主体加载失败必须显式呈现：静默置空会让「请选择主体」变成无解的死路（§11.3）
    subjectsError.value = apiError(cause, '授权主体（用户/角色/部门）加载失败')
  } finally {
    subjectsLoading.value = false
  }
}

async function loadPermissions() {
  if (!datasourceId.value) return
  const current = ++grantsRequestId
  grantsLoading.value = true
  grantsError.value = ''
  try {
    const res = await listDatasourcePermissions(datasourceId.value, subjectType.value || undefined)
    if (current === grantsRequestId) permissions.value = res.data || []
  } catch (cause) {
    if (current === grantsRequestId) {
      permissions.value = []
      grantsError.value = apiError(cause, '授权列表加载失败')
    }
  } finally {
    if (current === grantsRequestId) grantsLoading.value = false
  }
}

async function loadPolicies() {
  if (!datasourceId.value) return
  const current = ++policiesRequestId
  policiesLoading.value = true
  policiesError.value = ''
  try {
    const res = await listAccessPolicies(datasourceId.value, subjectType.value || undefined, subjectId.value, policyTableFilter.value || undefined)
    if (current === policiesRequestId) policies.value = res.data || []
  } catch (cause) {
    if (current === policiesRequestId) {
      policies.value = []
      policiesError.value = apiError(cause, '策略列表加载失败')
    }
  } finally {
    if (current === policiesRequestId) policiesLoading.value = false
  }
}

async function previewDecision() {
  if (!datasourceId.value || !decisionUserId.value) {
    ElMessage.warning('请先选择数据源和用户')
    return
  }
  decisionLoading.value = true
  decisionError.value = ''
  try {
    decision.value = (await getDatasourcePermissionDecision(datasourceId.value, decisionUserId.value)).data
  } catch (cause) {
    decision.value = undefined
    decisionError.value = apiError(cause, '权限决策计算失败')
  } finally {
    decisionLoading.value = false
  }
}

function openGrantDialog() {
  if (!datasourceId.value) {
    ElMessage.warning('请先在顶部选择数据源')
    return
  }
  grantForm.datasourceId = datasourceId.value
  prefillingGrant = true
  grantForm.subjectType = subjectType.value
  grantForm.subjectId = subjectId.value || 0
  nextTick(() => { prefillingGrant = false })
  grantForm.accessEffect = 'ALLOW'
  grantForm.canQuery = true
  grantForm.canExport = false
  grantForm.canViewSql = true
  grantForm.expiresAt = undefined
  grantDialogVisible.value = true
}

async function handleGrant() {
  if (!grantForm.subjectId) {
    ElMessage.warning('请选择授权主体')
    return
  }
  actionLoading.value = true
  try {
    await grantDatasourcePermission(grantForm)
    ElMessage.success('授权成功')
    grantDialogVisible.value = false
    await loadPermissions()
  } catch (cause) {
    ElMessage.error(apiError(cause, '授权失败'))
  } finally {
    actionLoading.value = false
  }
}

async function handleToggle(row: DatasourcePermissionItem, field: 'canQuery' | 'canExport' | 'canViewSql') {
  try {
    await updateDatasourcePermission(row.id, { [field]: !row[field] })
    row[field] = !row[field]
  } catch (cause) {
    ElMessage.error(apiError(cause, '更新授权失败'))
  }
}

async function handleEffectChange(row: DatasourcePermissionItem, effect: 'ALLOW' | 'DENY') {
  try {
    await updateDatasourcePermission(row.id, { accessEffect: effect })
    row.accessEffect = effect
  } catch (cause) {
    ElMessage.error(apiError(cause, '更新授权效果失败'))
  }
}

async function handleRevoke(row: DatasourcePermissionItem) {
  try {
    await ElMessageBox.confirm(
      `撤销「${row.subjectName}」在数据源「${datasourceName.value}」上的授权？撤销后该主体将失去这层访问能力。`,
      '确认撤销授权',
      { type: 'warning', confirmButtonText: '确认撤销', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await revokeDatasourcePermission(row.id)
    ElMessage.success('已撤销')
    await loadPermissions()
  } catch (cause) {
    ElMessage.error(apiError(cause, '撤销失败'))
  }
}

async function openPolicyDialog() {
  if (!datasourceId.value) {
    ElMessage.warning('请先在顶部选择数据源')
    return
  }
  policyForm.datasourceId = datasourceId.value
  prefillingPolicy = true
  policyForm.subjectType = subjectType.value
  policyForm.subjectId = subjectId.value || 0
  nextTick(() => { prefillingPolicy = false })
  policyForm.tableName = ''
  policyForm.columnName = ''
  policyForm.accessType = 'DENY'
  policyForm.maskStrategy = ''
  policyForm.rowFilterExpression = ''
  try {
    const published = (await getPublishedSnapshot(datasourceId.value)).data
    policySnapshot.value = published?.snapshotId
    policySnapshotLabel.value = published
      ? `已发布快照 v${published.snapshotVersion}`
      : ''
  } catch (cause) {
    ElMessage.error(apiError(cause, '已发布快照加载失败，暂不能创建策略'))
    return
  }
  if (!policySnapshot.value) {
    ElMessage.warning('当前数据源没有已发布快照，暂不能创建策略')
    return
  }
  policyDialogVisible.value = true
}

async function handleCreatePolicy() {
  policyForm.datasourceId = datasourceId.value || 0
  if (!policyForm.subjectId) {
    ElMessage.warning('请选择策略主体')
    return
  }
  if (!policyForm.tableName) {
    ElMessage.warning('请选择表')
    return
  }
  actionLoading.value = true
  try {
    await createAccessPolicy(policyForm)
    ElMessage.success('策略创建成功')
    policyDialogVisible.value = false
    await loadPolicies()
  } catch (cause) {
    ElMessage.error(apiError(cause, '创建策略失败'))
  } finally {
    actionLoading.value = false
  }
}

async function handleDeletePolicy(row: AccessPolicyItem) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.subjectName}」在表 ${row.tableName}${row.columnName ? '.' + row.columnName : ''} 上的${accessTypeLabel(row.accessType)}策略？`,
      '确认删除策略',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteAccessPolicy(row.id)
    ElMessage.success('已删除')
    await loadPolicies()
  } catch (cause) {
    ElMessage.error(apiError(cause, '删除策略失败'))
  }
}

/**
 * 同一批查询条件（数据源 + 主体 + 表筛选）的重复刷新合并。
 *
 * `context.initialize()` 在没有持久化数据源时会写入第一个数据源，从而立刻触发
 * 下面的 `watch(datasourceId)`；此时 `onMounted` 的 reloadAll 可能仍在飞行。
 * 响应串号已由 `loadPermissions` / `loadPolicies` 的序号解决，这一层只负责不发重复请求。
 */
let inflightReload = ''
async function reloadAll() {
  const signature = [datasourceId.value, subjectType.value, subjectId.value, policyTableFilter.value].join('|')
  if (inflightReload === signature) return
  inflightReload = signature
  try {
    await Promise.all([loadPermissions(), loadPolicies()])
  } finally {
    if (inflightReload === signature) inflightReload = ''
  }
}

/** 初始化失败的原因；无数据源时也要能看见失败，不能伪装成「还没选数据源」 */
const initError = ref('')

async function bootstrap() {
  initError.value = ''
  try {
    await context.initialize()
    await loadSubjects()
    await reloadAll()
  } catch (cause) {
    initError.value = apiError(cause, '授权管理初始化失败')
    grantsError.value = initError.value
    policiesError.value = initError.value
  }
}

onMounted(bootstrap)

// 数据源切换（顶部 ScopeBar 或工作区导航）时两个列表都要跟随
watch(datasourceId, async () => {
  decision.value = undefined
  decisionError.value = ''
  await reloadAll()
})

// 页面级主体变化时，两个列表按新主体重新查询；若主体是用户则同步给权限预览
watch([subjectType, subjectId], async () => {
  if (subjectType.value === 'USER') decisionUserId.value = subjectId.value
  await reloadAll()
})

/**
 * 主体类型切换必须清空主体，否则可能把授权发给上一个类型的主体。
 *
 * 但打开对话框时也会把 `subjectType` 预填为页面级选择，那一次不应清空刚预填的
 * `subjectId`——用 `prefilling*` 标记把「预填」与「用户切换」区分开。
 * watch 默认异步 flush，所以标记要延到下一次 tick 再落下。
 */
let prefillingGrant = false
let prefillingPolicy = false
watch(() => grantForm.subjectType, () => { if (!prefillingGrant) grantForm.subjectId = 0 })
watch(() => policyForm.subjectType, () => { if (!prefillingPolicy) policyForm.subjectId = 0 })

watch(() => route.query.tab, (value) => {
  const next = String(value || 'grants')
  if (TAB_NAMES.includes(next as never) && next !== activeTab.value) activeTab.value = next
})
</script>

<template>
  <main class="admin-page access-page">
    <TaskPageHeader
      title="授权管理"
      description="按主体查看数据源级授权、表列策略与最终权限结果。数据源由顶部范围条决定，页面内不再另建选择器。"
    >
      <template #status>
        <span class="access-page__scope">当前数据源：{{ datasourceName }}</span>
      </template>
      <template #actions>
        <el-button :icon="RefreshCw" :loading="grantsLoading || policiesLoading" @click="reloadAll">刷新</el-button>
        <el-button v-if="primaryAction" type="primary" :icon="Plus" @click="primaryAction.run">
          {{ primaryAction.label }}
        </el-button>
      </template>
    </TaskPageHeader>

    <!-- 主体选择：驱动「数据源授权」与「表列策略」两个 Tab（§7.13 的主体驱动流程） -->
    <section class="access-page__subject">
      <div class="access-page__subject-label">
        <strong>选择主体</strong>
        <span>先选主体，再查看它在当前数据源上被授予了什么。</span>
      </div>
      <el-radio-group v-model="subjectType" size="small">
        <el-radio-button v-for="t in subjectTypes" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
      </el-radio-group>
      <el-select
        v-model="subjectId"
        filterable
        clearable
        :loading="subjectsLoading"
        :disabled="Boolean(subjectsError)"
        placeholder="全部主体"
        class="access-page__subject-select"
      >
        <el-option v-for="opt in subjectOptions" :key="opt.id" :label="opt.name" :value="opt.id" />
      </el-select>
      <span v-if="subjectsError" class="access-page__hint is-error">{{ subjectsError }}</span>
      <span v-else-if="subjectType === 'USER' && usersTotal > USER_PAGE_SIZE" class="access-page__hint">
        共 {{ usersTotal }} 名用户，下拉仅显示前 {{ USER_PAGE_SIZE }} 名。
      </span>
    </section>

    <!-- 初始化失败时 datasourceId 会一直是空，若先判空态就会把失败伪装成「还没选数据源」。
         错误态必须排在空态之前，否则它永远不可达。 -->
    <ErrorState
      v-if="initError && !datasourceId"
      :message="initError"
      @retry="bootstrap"
    />

    <EmptyState
      v-else-if="!datasourceId"
      message="授权管理需要先确定数据源。请使用顶部的数据源范围条选择要授权的数据源。"
      action-text="去数据源接入"
      @action="router.push('/admin/data-sources')"
    />

    <el-tabs v-else :model-value="activeTab" @update:model-value="selectTab">
      <el-tab-pane label="数据源授权" name="grants">
        <section class="access-page__panel">
          <p class="access-page__note">
            第一层：主体能否访问该数据源。<code>禁止</code>优先于<code>允许</code>，
            任一维度为禁止即不可访问。
          </p>
          <ErrorState v-if="grantsError" :message="grantsError" @retry="loadPermissions" />
          <LoadingState v-else-if="grantsLoading" variant="skeleton" :rows="4" />
          <EmptyState
            v-else-if="!visiblePermissions.length"
            message="当前主体筛选下没有授权记录。未授权的主体不能查询该数据源。"
            action-text="新增授权"
            @action="openGrantDialog"
          />
          <el-table v-else :data="visiblePermissions" stripe>
            <el-table-column label="主体类型" width="100">
              <template #default="{ row }">{{ subjectTypeLabel(row.subjectType) }}</template>
            </el-table-column>
            <el-table-column prop="subjectName" label="主体名称" min-width="140" />
            <el-table-column label="授权效果" width="110" align="center">
              <template #default="{ row }">
                <el-select
                  :model-value="row.accessEffect || 'ALLOW'"
                  size="small"
                  style="width: 92px"
                  @change="(value: 'ALLOW' | 'DENY') => handleEffectChange(row, value)"
                >
                  <el-option label="允许" value="ALLOW" />
                  <el-option label="禁止" value="DENY" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="查询" width="80" align="center">
              <template #default="{ row }">
                <el-switch :model-value="row.canQuery" size="small" @change="handleToggle(row, 'canQuery')" />
              </template>
            </el-table-column>
            <el-table-column label="导出" width="80" align="center">
              <template #default="{ row }">
                <el-switch :model-value="row.canExport" size="small" @change="handleToggle(row, 'canExport')" />
              </template>
            </el-table-column>
            <el-table-column label="查看 SQL" width="90" align="center">
              <template #default="{ row }">
                <el-switch :model-value="row.canViewSql" size="small" @change="handleToggle(row, 'canViewSql')" />
              </template>
            </el-table-column>
            <el-table-column label="有效期" width="180">
              <template #default="{ row }">
                <span v-if="row.expiresAt">{{ row.expiresAt }}</span>
                <span v-else class="access-page__muted">长期有效</span>
              </template>
            </el-table-column>
            <el-table-column prop="grantedAt" label="授权时间" width="170" />
            <el-table-column label="操作" width="80" align="center">
              <template #default="{ row }">
                <el-button type="danger" link size="small" aria-label="撤销授权" @click="handleRevoke(row)">
                  <Trash2 :size="14" />
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <p class="access-page__gap">
            后端缺口：授权列表接口未返回 <code>priority</code>，因此<strong>无法展示优先级</strong>。
            授权效果与有效期可见，优先级不存在前端可展示的数据来源。
          </p>
        </section>
      </el-tab-pane>

      <el-tab-pane label="表列策略" name="policies">
        <section class="access-page__panel">
          <p class="access-page__note">
            第二层：允许访问哪些表、字段，是否存在行过滤和脱敏。字段留空表示表级策略。
          </p>
          <el-input v-model="policyTableFilter" clearable placeholder="按表名筛选" style="max-width: 260px" @change="loadPolicies" />
          <ErrorState v-if="policiesError" :message="policiesError" @retry="loadPolicies" />
          <LoadingState v-else-if="policiesLoading" variant="skeleton" :rows="4" />
          <EmptyState
            v-else-if="!policies.length"
            message="当前筛选条件下没有表列策略。未配置策略时，主体按数据源级授权决定可访问范围。"
            action-text="新增策略"
            @action="openPolicyDialog"
          />
          <el-table v-else :data="policies" stripe>
            <el-table-column prop="subjectName" label="主体" min-width="130">
              <template #default="{ row }">
                <el-tag size="small" type="info" class="access-page__tag">{{ subjectTypeLabel(row.subjectType) }}</el-tag>
                {{ row.subjectName }}
              </template>
            </el-table-column>
            <el-table-column prop="tableName" label="表名" width="150" />
            <el-table-column label="字段" width="130">
              <template #default="{ row }">{{ row.columnName || '(表级)' }}</template>
            </el-table-column>
            <el-table-column label="访问类型" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="accessTypeTag(row.accessType)" size="small">{{ accessTypeLabel(row.accessType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="脱敏策略" width="110">
              <template #default="{ row }">{{ row.maskStrategy || '—' }}</template>
            </el-table-column>
            <el-table-column label="行级过滤" min-width="180">
              <template #default="{ row }">
                <code v-if="row.rowFilterExpression" class="access-page__code">{{ row.rowFilterExpression }}</code>
                <span v-else class="access-page__muted">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="priority" label="优先级" width="90" />
            <el-table-column label="策略有效期" min-width="210">
              <template #default="{ row }">{{ row.validFrom || '立即' }} 至 {{ row.validUntil || '永久' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="80" align="center">
              <template #default="{ row }">
                <el-button type="danger" link size="small" aria-label="删除策略" @click="handleDeletePolicy(row)">
                  <Trash2 :size="14" />
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="最终权限预览" name="decision">
        <section class="access-page__panel">
          <p class="access-page__note">
            使用 <code>/decision</code> 接口计算指定用户在<strong>当前数据源</strong>上的最终结果，
            包含来源与冲突。只看规则列表无法判断最终效果。
          </p>
          <div class="access-page__decision-controls">
            <el-select
              v-model="decisionUserId"
              filterable
              clearable
              :loading="subjectsLoading"
              :disabled="Boolean(subjectsError)"
              placeholder="选择要计算权限的用户"
              class="access-page__decision-select"
            >
              <el-option v-for="user in users" :key="user.id" :label="user.name" :value="user.id" />
            </el-select>
            <el-button type="primary" :loading="decisionLoading" :disabled="!decisionUserId" @click="previewDecision">
              计算权限
            </el-button>
          </div>
          <ErrorState v-if="decisionError" :message="decisionError" @retry="previewDecision" />
          <template v-else-if="decision">
            <dl class="access-page__decision-facts">
              <div>
                <dt>最终效果</dt>
                <dd>
                  <el-tag :type="decision.accessEffect === 'DENY' ? 'danger' : decision.accessEffect === 'ALLOW' ? 'success' : 'info'">
                    {{ effectLabel(decision.accessEffect) }}
                  </el-tag>
                  <span class="access-page__muted">（{{ decision.accessEffect }}）</span>
                </dd>
              </div>
              <div><dt>决策来源</dt><dd>{{ decisionSourceText }}</dd></div>
              <div><dt>查询</dt><dd>{{ decision.canQuery ? '允许' : '禁止' }}</dd></div>
              <div><dt>导出</dt><dd>{{ decision.canExport ? '允许' : '禁止' }}</dd></div>
              <div><dt>查看 SQL</dt><dd>{{ decision.canViewSql ? '可见' : '隐藏' }}</dd></div>
              <div><dt>命中角色</dt><dd>{{ decision.roleIds?.length ? decision.roleIds.join('、') : '无' }}</dd></div>
            </dl>
            <p v-if="decision.accessEffect === 'DENY'" class="access-page__deny-note">
              该用户在<strong>当前数据源</strong>上的最终决策是禁止。即使某个角色或部门存在允许，
              只要任一维度为禁止，结果就是不可访问——因此不能仅凭「存在允许授权」判断可访问。
            </p>
          </template>
          <EmptyState
            v-else
            message="选择一个用户后计算最终权限。结果由部门、角色和用户直接授权合并得出。"
          />
        </section>
      </el-tab-pane>
    </el-tabs>

    <!-- 新增授权 -->
    <el-dialog v-model="grantDialogVisible" title="新增数据源授权" width="520px">
      <!-- 写操作前再次显示目标数据源与主体（§7.13） -->
      <el-alert type="info" :closable="false" class="access-page__confirm-scope">
        <template #title>
          目标数据源：<strong>{{ datasourceName }}</strong>
        </template>
      </el-alert>
      <el-form label-width="92px">
        <el-form-item label="主体类型">
          <el-radio-group v-model="grantForm.subjectType">
            <el-radio-button v-for="t in subjectTypes" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="选择主体">
          <el-select v-model="grantForm.subjectId" filterable placeholder="请选择" style="width: 100%">
            <el-option
              v-for="opt in (grantForm.subjectType === 'USER' ? users : grantForm.subjectType === 'DEPARTMENT' ? departments : roles)"
              :key="opt.id"
              :label="opt.name"
              :value="opt.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="授权效果">
          <el-radio-group v-model="grantForm.accessEffect">
            <el-radio-button value="ALLOW">允许</el-radio-button>
            <el-radio-button value="DENY">禁止</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="权限项">
          <el-checkbox v-model="grantForm.canQuery">查询</el-checkbox>
          <el-checkbox v-model="grantForm.canExport">导出</el-checkbox>
          <el-checkbox v-model="grantForm.canViewSql">查看 SQL</el-checkbox>
        </el-form-item>
        <el-form-item label="有效期">
          <el-date-picker
            v-model="grantForm.expiresAt"
            type="datetime"
            placeholder="留空表示长期有效"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="grantDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="handleGrant">确定授权</el-button>
      </template>
    </el-dialog>

    <!-- 新增策略 -->
    <el-dialog v-model="policyDialogVisible" title="新增表列策略" width="560px">
      <el-alert type="info" :closable="false" class="access-page__confirm-scope">
        <template #title>
          目标数据源：<strong>{{ datasourceName }}</strong>
        </template>
      </el-alert>
      <el-form label-width="92px">
        <el-form-item label="主体类型">
          <el-radio-group v-model="policyForm.subjectType">
            <el-radio-button v-for="t in subjectTypes" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="选择主体">
          <el-select v-model="policyForm.subjectId" filterable placeholder="请选择" style="width: 100%">
            <el-option
              v-for="opt in (policyForm.subjectType === 'USER' ? users : policyForm.subjectType === 'DEPARTMENT' ? departments : roles)"
              :key="opt.id"
              :label="opt.name"
              :value="opt.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="表与字段">
          <ResourceScopeSelector
            v-model:datasource-id="context.datasourceId"
            v-model:snapshot-id="policySnapshot"
            v-model:table-name="policyForm.tableName"
            v-model:column-name="policyForm.columnName"
            mode="column"
            :show-datasource="false"
            :lock-snapshot="true"
            :lock-snapshot-label="policySnapshotLabel"
          />
        </el-form-item>
        <p class="access-page__form-hint">
          资源范围取自<strong>已发布快照</strong>（后端只校验发布快照），因此快照不可切换。
          字段留空表示表级策略；选择字段后可配置字段级禁止或脱敏。
        </p>
        <el-form-item label="访问类型">
          <el-radio-group v-model="policyForm.accessType">
            <el-radio-button v-for="t in accessTypes" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="policyForm.accessType === 'MASK'" label="脱敏策略">
          <el-select v-model="policyForm.maskStrategy" placeholder="选择脱敏方式" style="width: 100%">
            <el-option v-for="s in maskStrategies" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="行级过滤">
          <el-input v-model="policyForm.rowFilterExpression" placeholder="如 region = '华东'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="policyDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="handleCreatePolicy">确定</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.access-page {
  display: grid;
  gap: 16px;
}

.access-page__scope {
  color: var(--do-muted);
  font-size: 13px;
}

.access-page__subject {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 14px 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.access-page__subject-label {
  display: grid;
  gap: 3px;
  margin-right: 4px;
}

.access-page__subject-label strong {
  color: var(--do-ink);
  font-size: 13px;
}

.access-page__subject-label span {
  color: var(--do-muted);
  font-size: 12px;
}

.access-page__subject-select {
  width: 220px;
}

.access-page__hint {
  color: var(--do-muted);
  font-size: 12px;
}

.access-page__hint.is-error {
  color: var(--do-danger);
}

.access-page__panel {
  display: grid;
  gap: 12px;
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.access-page__note {
  margin: 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}

.access-page__gap {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}

.access-page__decision-controls {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.access-page__decision-select {
  width: 260px;
}

.access-page__decision-facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  margin: 0;
  padding: 14px;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
}

.access-page__decision-facts dt {
  color: var(--do-muted);
  font-size: 12px;
}

.access-page__decision-facts dd {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 4px 0 0;
  color: var(--do-ink);
  font-size: 13px;
}

.access-page__deny-note {
  margin: 0;
  padding: 12px;
  border-radius: var(--do-radius-md);
  background: var(--do-danger-soft);
  color: var(--do-ink);
  font-size: 12px;
  line-height: 1.7;
}

.access-page__confirm-scope {
  margin-bottom: 14px;
}

.access-page__form-hint {
  margin: -8px 0 12px 92px;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.5;
}

.access-page__muted {
  color: var(--do-muted);
}

.access-page__code {
  font-size: 12px;
}

.access-page__tag {
  margin-right: 6px;
}

@media (max-width: 900px) {
  .access-page__subject-select,
  .access-page__decision-select {
    width: 100%;
  }

  .access-page__form-hint {
    margin-left: 0;
  }
}
</style>
