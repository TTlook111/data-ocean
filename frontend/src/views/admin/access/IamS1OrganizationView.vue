<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import GovernanceWriteNotice from '../../../components/admin/GovernanceWriteNotice.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import UserList from '../user/UserList.vue'
import DepartmentTree from '../user/DepartmentTree.vue'
import { useIamWorkspaceSession } from '../../../composables/useIamWorkspaceSession'
import { trackGovernanceWrite, pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
import {
  assignIamS1UserRole,
  bindIamS1Datasources,
  createIamS1Role,
  deleteIamS1Role,
  disableIamS1UserRole,
  listIamS1Datasources,
  listIamS1Functions,
  listIamS1RoleTemplates,
  listIamS1Roles,
  listIamS1Subjects,
  listIamS1UserRoles,
  removeIamS1UserRole,
  removeIamS1BindingDatasource,
  updateIamS1Role,
  type IamS1DatasourceRef,
  type IamS1FunctionCatalogItem,
  type IamS1Role,
  type IamS1RoleTemplate,
  type IamS1SubjectOption,
  type IamS1UserRoleBinding,
} from '../../../api/iamS1'
const session = useIamWorkspaceSession(),
  iamS1 = session.iam,
  route = useRoute(),
  router = useRouter()
const selfId = computed(() => session.auth.currentUser?.id ?? session.auth.user?.userId),
  actorKey = computed(() => JSON.stringify([session.key.value, selfId.value])),
  epoch = ref(0)
const TABS = ['users', 'departments', 'roles', 'bindings', 'catalog'] as const,
  typeTab = (v: unknown) => (TABS.includes(v as any) ? (v as (typeof TABS)[number]) : 'users')
const activeTab = ref(typeTab(route.query.tab)),
  roles = ref<IamS1Role[]>([]),
  bindingRoles = ref<IamS1Role[]>([]),
  templates = ref<IamS1RoleTemplate[]>([]),
  functions = ref<IamS1FunctionCatalogItem[]>([]),
  datasources = ref<IamS1DatasourceRef[]>([]),
  users = ref<IamS1SubjectOption[]>([]),
  bindings = ref<IamS1UserRoleBinding[]>([])
const selectedUserId = ref<number>(),
  assignRoleId = ref<number>(),
  roleKeyword = ref(''),
  catalogKeyword = ref(''),
  roleFormVisible = ref(false),
  editingRoleId = ref<number>(),
  editingRole = ref<IamS1Role>(),
  savingRole = ref(false),
  bindingBusy = ref(false),
  writeError = ref('')
const loading = reactive<Record<string, boolean>>({}),
  errors = reactive<Record<string, string>>({}),
  requests: Record<string, number> = {}
const bindingDatasourceDraft = reactive<Record<number, number[]>>({}),
  bindingReason = reactive<Record<number, string>>({})
const roleForm = reactive({
  roleCode: '',
  roleName: '',
  description: '',
  functionCodes: [] as string[],
  reason: '',
  status: 1,
})
const canViewRoles = computed(() => session.canGlobal('organization:role:view')),
  canManageRoles = computed(() => canViewRoles.value && session.canGlobal('organization:role:manage')),
  canViewCatalog = computed(() => session.canGlobal('organization:permission:view')),
  canManageBindings = computed(() => session.ready.value && iamS1.systemAdmin)
const canViewUsers = computed(() => session.canGlobal('organization:user:view')),
  canViewDepartments = computed(() => session.canGlobal('organization:department:view'))
const canMutateUser = computed(
  () =>
    canManageBindings.value &&
    !!selfId.value &&
    !!selectedUserId.value &&
    selfId.value !== selectedUserId.value &&
    users.value.some((u) => u.id === selectedUserId.value),
)
const owned = (k: string) => session.alive() && session.ready.value && actorKey.value === k,
  identity = () => session.auth.token || '',
  roleLock = (id?: number) => `iam-role:${id ?? roleForm.roleCode.trim().toUpperCase()}`,
  userLock = (id = selectedUserId.value) => `iam-bindings:${id}`
const locked = (key: string) =>
  pendingGovernanceWrites.value.some((x) => x.identity === identity() && x.keys.includes(key))
function groupedFunctions(keyword: string) {
  const m = new Map<string, IamS1FunctionCatalogItem[]>()
  for (const f of functions.value.filter((x) =>
    `${x.name} ${x.code} ${x.description}`.toLowerCase().includes(keyword.toLowerCase()),
  )) {
    const list = m.get(f.domain) || []
    list.push(f)
    m.set(f.domain, list)
  }
  return [...m].map(([domain, items]) => ({ domain, items }))
}
const catalogDomains = computed(() => groupedFunctions(catalogKeyword.value)),
  editorDomains = computed(() => groupedFunctions(''))
let editorEpoch = 0,
  bindingEpoch = 0
function resetRoleForm() {
  editorEpoch++
  editingRoleId.value = undefined
  editingRole.value = undefined
  Object.assign(roleForm, {
    roleCode: '',
    roleName: '',
    description: '',
    functionCodes: [],
    reason: '',
    status: 1,
  })
  savingRole.value = false
}
function closeRole() {
  resetRoleForm()
  roleFormVisible.value = false
  session.cancelOwn()
}
function currentBinding(b: IamS1UserRoleBinding) {
  return canMutateUser.value && b.userId === selectedUserId.value && bindings.value.includes(b)
}
async function read<T>(
  name: string,
  allow: boolean,
  call: () => Promise<{ data: T }>,
  apply: (v: T) => void,
) {
  const k = actorKey.value,
    r = (requests[name] || 0) + 1,
    user = selectedUserId.value
  requests[name] = r
  errors[name] = ''
  if (!allow) {
    loading[name] = false
    return
  }
  loading[name] = true
  try {
    const result = await call()
    if (!owned(k) || r !== requests[name] || (name === 'bindings' && user !== selectedUserId.value)) return
    apply(result.data)
  } catch (e) {
    if (owned(k) && r === requests[name] && (name !== 'bindings' || user === selectedUserId.value))
      errors[name] = e instanceof Error ? e.message : '读取失败'
  } finally {
    if (owned(k) && r === requests[name] && (name !== 'bindings' || user === selectedUserId.value))
      loading[name] = false
  }
}
async function loadRoles() {
  await read(
    'roles',
    canViewRoles.value,
    () => listIamS1Roles(roleKeyword.value.trim() || undefined),
    (data) => {
      roles.value = data || []
    },
  )
}
async function loadBindingRoles() {
  await read(
    'bindingRoles',
    canManageBindings.value,
    () => listIamS1Roles(),
    (data) => {
      bindingRoles.value = data || []
    },
  )
}
async function loadFunctions() {
  await read(
    'functions',
    canViewCatalog.value,
    () => listIamS1Functions(),
    (data) => {
      functions.value = data || []
    },
  )
}
async function loadTemplates() {
  await read(
    'templates',
    canManageRoles.value,
    () => listIamS1RoleTemplates(),
    (data) => {
      templates.value = data || []
    },
  )
}
async function loadUsers(keyword = '') {
  await read(
    'users',
    canManageBindings.value,
    () => listIamS1Subjects('ORGANIZATION', undefined, 'USER', keyword.trim() || undefined),
    (data) => {
      const selected = users.value.find((u) => u.id === selectedUserId.value)
      users.value = (data || []).filter((u) => u.subjectType === 'USER')
      if (selected && !users.value.some((u) => u.id === selected.id)) users.value.unshift(selected)
    },
  )
}
async function loadDatasources() {
  await read(
    'sources',
    canManageBindings.value,
    () => listIamS1Datasources(),
    (data) => {
      datasources.value = data || []
    },
  )
}
async function loadBindings() {
  bindings.value = []
  const target = selectedUserId.value
  if (!target) return
  await read(
    'bindings',
    canManageBindings.value && users.value.some((x) => x.id === target),
    () => listIamS1UserRoles(target),
    (data) => {
      if (
        (data || []).some(
          (b) =>
            b.userId !== target ||
            !Number.isSafeInteger(b.userRoleId) ||
            b.userRoleId <= 0 ||
            !Number.isSafeInteger(b.roleId) ||
            b.roleId <= 0 ||
            typeof b.roleEnabled !== 'boolean' ||
            typeof b.bindingEnabled !== 'boolean' ||
            typeof b.protectedRole !== 'boolean' ||
            !Array.isArray(b.responsibleDatasources) ||
            b.responsibleDatasources.some(
              (s) => !Number.isSafeInteger(s.id) || s.id <= 0 || typeof s.enabled !== 'boolean',
            ),
        )
      )
        throw new Error('绑定回执与目标用户不符或缺精确负责源，未展示')
      bindings.value = data || []
    },
  )
}
async function loadActive() {
  if (activeTab.value === 'roles') await loadRoles()
  if (activeTab.value === 'catalog') await loadFunctions()
  if (activeTab.value === 'bindings' && canManageBindings.value)
    await Promise.all([loadUsers(), loadBindingRoles(), loadDatasources()])
}
async function initialize() {
  epoch.value++
  editorEpoch++
  bindingEpoch++
  Object.keys(requests).forEach((k) => requests[k]++)
  Object.keys(loading).forEach((k) => (loading[k] = false))
  Object.keys(errors).forEach((k) => (errors[k] = ''))
  roles.value = []
  bindingRoles.value = []
  templates.value = []
  functions.value = []
  users.value = []
  datasources.value = []
  bindings.value = []
  selectedUserId.value = undefined
  assignRoleId.value = undefined
  Object.keys(bindingDatasourceDraft).forEach((k) => delete bindingDatasourceDraft[Number(k)])
  Object.keys(bindingReason).forEach((k) => delete bindingReason[Number(k)])
  closeRole()
  bindingBusy.value = false
  writeError.value = ''
  if (!route.query.tab)
    activeTab.value = canViewUsers.value
      ? 'users'
      : canViewDepartments.value
        ? 'departments'
        : canViewRoles.value
          ? 'roles'
          : canViewCatalog.value
            ? 'catalog'
            : 'bindings'
  await loadActive()
}
function selectTab(value: string | number) {
  activeTab.value = typeTab(value)
  session.cancelOwn()
  void router.replace({ query: { ...route.query, tab: activeTab.value } })
  void loadActive()
}
watch(
  () => route.query.tab,
  (value) => {
    const tab = typeTab(value)
    if (tab !== activeTab.value) {
      activeTab.value = tab
      session.cancelOwn()
      void loadActive()
    }
  },
)
watch(
  selectedUserId,
  () => {
    bindingEpoch++
    requests.bindings = (requests.bindings || 0) + 1
    bindings.value = []
    assignRoleId.value = undefined
    bindingBusy.value = false
    loading.bindings = false
    errors.bindings = ''
    Object.keys(bindingDatasourceDraft).forEach((k) => delete bindingDatasourceDraft[Number(k)])
    Object.keys(bindingReason).forEach((k) => delete bindingReason[Number(k)])
    session.cancelOwn()
  },
  { flush: 'sync' },
)
function roleVerified(r: IamS1Role) {
  return (
    Number.isSafeInteger(r.id) &&
    r.id > 0 &&
    typeof r.enabled === 'boolean' &&
    typeof r.protectedRole === 'boolean' &&
    typeof r.builtIn === 'boolean' &&
    Array.isArray(r.functionCodes)
  )
}
function roleStatus(r: IamS1Role) {
  return !roleVerified(r)
    ? '状态未确认'
    : r.protectedRole
      ? '受保护系统角色'
      : r.enabled
        ? '普通角色 · 启用'
        : '普通角色 · 停用'
}
async function openRole(role?: IamS1Role) {
  if (role && !roleVerified(role)) {
    writeError.value = '角色状态或保护标记未确认，未打开写配置，请重读精确角色。'
    return
  }
  if (
    !canManageRoles.value ||
    savingRole.value ||
    (role && (!roles.value.includes(role) || role.protectedRole))
  )
    return
  resetRoleForm()
  editingRoleId.value = role?.id
  editingRole.value = role ? { ...role, functionCodes: [...role.functionCodes] } : undefined
  if (role)
    Object.assign(roleForm, {
      roleCode: role.roleCode,
      roleName: role.roleName,
      description: role.description || '',
      functionCodes: [...role.functionCodes],
      status: role.enabled ? 1 : 0,
    })
  roleFormVisible.value = true
  await Promise.all([loadTemplates(), loadFunctions()])
}
const startEdit = (r: IamS1Role) => openRole(r)
function applyTemplate(t: IamS1RoleTemplate) {
  if (
    !canManageRoles.value ||
    savingRole.value ||
    !templates.value.includes(t) ||
    (t.systemAdminOnly && !iamS1.systemAdmin)
  )
    return
  roleForm.roleCode = roleForm.roleCode || t.code
  roleForm.roleName = t.name
  roleForm.description = t.description
  roleForm.functionCodes = [...t.functionCodes]
}
function isIncludedByDependency(code: string) {
  return roleForm.functionCodes.some(
    (s) => s !== code && functions.value.find((f) => f.code === s)?.dependencies.includes(code),
  )
}
async function saveRole() {
  if (savingRole.value || !canManageRoles.value || locked(roleLock(editingRoleId.value))) return
  writeError.value = ''
  if (!roleForm.roleCode.trim() || !roleForm.roleName.trim() || !roleForm.functionCodes.length) {
    writeError.value = '请填写编码、中文名，并明确至少一个功能。'
    return
  }
  const id = editingRoleId.value,
    original = editingRole.value
  if (id && (!original || original.protectedRole)) return
  const payload = {
      ...roleForm,
      roleCode: roleForm.roleCode.trim().toUpperCase(),
      roleName: roleForm.roleName.trim(),
      functionCodes: [...roleForm.functionCodes],
    },
    k = actorKey.value,
    e = editorEpoch,
    signature = JSON.stringify(roleForm),
    key = roleLock(id),
    actor = identity()
  savingRole.value = true
  try {
    await trackGovernanceWrite(
      actor,
      [key],
      `${id ? '角色 #' + id : '新角色 ' + payload.roleCode} 保存功能`,
      async () => {
        if (id) return updateIamS1Role(id, payload)
        const result = await createIamS1Role(payload)
        if (!Number.isSafeInteger(result.data) || Number(result.data) <= 0)
          throw new Error('创建回执缺角色ID，结果未知，先核对角色列表')
        return result
      },
    )
    if (!owned(k) || e !== editorEpoch) return
    if (JSON.stringify(roleForm) === signature) closeRole()
    else writeError.value = '原角色变更已提交，保存期间的新输入保留。'
    await loadRoles()
  } catch (cause) {
    if (owned(k) && e === editorEpoch)
      writeError.value = cause instanceof Error ? cause.message : '保存失败；409保留草稿，结果未知先核对'
  } finally {
    if (owned(k) && e === editorEpoch) savingRole.value = false
  }
}
async function removeRole(role: IamS1Role) {
  if (!roleVerified(role)) {
    writeError.value = '角色状态或保护标记未确认，不能删除。'
    return
  }
  if (
    !canManageRoles.value ||
    role.protectedRole ||
    !roles.value.includes(role) ||
    savingRole.value ||
    locked(roleLock(role.id))
  )
    return
  const k = actorKey.value,
    e = editorEpoch,
    key = roleLock(role.id),
    actor = identity()
  savingRole.value = true
  writeError.value = ''
  try {
    await ElMessageBox.confirm(
      `删除角色 #${role.id} ${role.roleName}（${role.roleCode}）及其功能配置。服务端会拒绝有任何成员或操作者本人持有的角色；不解除其他绑定或数据授权。`,
      '删除精确角色',
      {
        type: 'warning',
        confirmButtonText: '确认删除角色',
        cancelButtonText: '取消',
        cancelButtonClass: session.cancelClass,
        closeOnClickModal: false,
      },
    )
    if (!owned(k) || e !== editorEpoch || !canManageRoles.value || !roles.value.includes(role)) return
    await trackGovernanceWrite(actor, [key], `删除角色 #${role.id} ${role.roleCode}`, () =>
      deleteIamS1Role(role.id, '界面删除角色'),
    )
    if (owned(k) && e === editorEpoch) await loadRoles()
  } catch (cause) {
    if (owned(k) && e === editorEpoch && cause !== 'cancel' && cause !== 'close')
      writeError.value = cause instanceof Error ? cause.message : '删除失败，先核对原角色'
  } finally {
    if (owned(k) && e === editorEpoch) savingRole.value = false
  }
}
async function bindingWrite(
  label: string,
  text: string,
  write: () => Promise<unknown>,
  valid: () => boolean,
  after?: () => void,
) {
  if (bindingBusy.value || !canMutateUser.value || locked(userLock()) || !valid()) return
  const k = actorKey.value,
    e = bindingEpoch,
    target = selectedUserId.value,
    key = userLock(),
    actor = identity()
  bindingBusy.value = true
  writeError.value = ''
  try {
    await ElMessageBox.confirm(text, label, {
      type: 'warning',
      confirmButtonText: '确认变更绑定',
      cancelButtonText: '取消',
      cancelButtonClass: session.cancelClass,
      closeOnClickModal: false,
    })
    if (
      !owned(k) ||
      e !== bindingEpoch ||
      target !== selectedUserId.value ||
      !canMutateUser.value ||
      !valid()
    )
      return
    await trackGovernanceWrite(actor, [key], text, write)
    if (!owned(k) || e !== bindingEpoch) return
    after?.()
    await loadBindings()
  } catch (cause) {
    if (owned(k) && e === bindingEpoch && cause !== 'cancel' && cause !== 'close')
      writeError.value =
        cause instanceof Error ? cause.message : '变更失败；409保留负责源草稿，未知先核对精确绑定'
  } finally {
    if (owned(k) && e === bindingEpoch) bindingBusy.value = false
  }
}
async function assignRole(roleId = assignRoleId.value) {
  const user = selectedUserId.value,
    role = bindingRoles.value.find((r) => r.id === roleId)
  if (!user || !role || role.protectedRole || !role.enabled || errors.bindingRoles || loading.bindingRoles)
    return
  await bindingWrite(
    '分配角色',
    `用户 #${user} 加入角色 #${role.id} ${role.roleName}。改变该用户功能和既有角色数据授权的主体匹配；后台负责源仍单独绑定。`,
    async () => {
      const r = await assignIamS1UserRole(user, role.id, '界面分配角色')
      if (!Number.isSafeInteger(r.data) || Number(r.data) <= 0) throw new Error('分配回执缺绑定ID，结果未知')
    },
    () => selectedUserId.value === user && bindingRoles.value.includes(role),
    () => {
      if (assignRoleId.value === role.id) assignRoleId.value = undefined
    },
  )
}
async function removeBinding(b: IamS1UserRoleBinding) {
  const user = b.userId
  await bindingWrite(
    '解除精确角色绑定',
    `用户 #${user} / 角色 #${b.roleId} ${b.roleName} / 绑定 #${b.userRoleId}：解除会删除该绑定及其负责源关联；既有个人/部门/角色数据授权仍由Java解析。受保护管理员的最低保留人数由服务端检查。`,
    () => removeIamS1UserRole(user, b.roleId, '界面解除角色'),
    () => currentBinding(b),
  )
}
async function disableBinding(b: IamS1UserRoleBinding) {
  if (!b.bindingEnabled) return
  await bindingWrite(
    '停用精确角色绑定',
    `用户 #${b.userId} / 角色 #${b.roleId} ${b.roleName} / 绑定 #${b.userRoleId}：停用后不再按该绑定提供功能/负责源。受保护管理员最低保留人数仍由服务端检查。`,
    () => disableIamS1UserRole(b.userId, b.roleId, '界面停用角色绑定'),
    () => currentBinding(b) && b.bindingEnabled,
  )
}
async function saveBindingDatasources(b: IamS1UserRoleBinding) {
  if (
    !currentBinding(b) ||
    !b.bindingEnabled ||
    !b.roleEnabled ||
    b.protectedRole ||
    errors.sources ||
    loading.sources
  )
    return
  const ids = [...new Set(bindingDatasourceDraft[b.userRoleId] || [])]
  if (!ids.length || ids.some((id) => !datasources.value.some((s) => s.id === id && s.enabled))) {
    writeError.value = '请选择当前启用源；追加不移除原负责源。'
    return
  }
  const reason = bindingReason[b.userRoleId]?.trim() || '界面追加后台负责源'
  await bindingWrite(
    '追加本绑定负责源',
    `用户 #${b.userId} / 角色 #${b.roleId} / 绑定 #${b.userRoleId}：追加源 ${ids.map((id) => '#' + id).join('、')}。不移除其他源，不授予查询数据；只能与本绑定功能配对。`,
    () => bindIamS1Datasources(b.userRoleId, ids, reason),
    () => currentBinding(b) && b.bindingEnabled && b.roleEnabled && !b.protectedRole,
    () => {
      if (JSON.stringify(bindingDatasourceDraft[b.userRoleId] || []) === JSON.stringify(ids))
        bindingDatasourceDraft[b.userRoleId] = []
    },
  )
}
async function removeSource(b: IamS1UserRoleBinding, s: IamS1DatasourceRef) {
  if (
    !currentBinding(b) ||
    !b.bindingEnabled ||
    !b.roleEnabled ||
    b.protectedRole ||
    !b.responsibleDatasources.includes(s)
  )
    return
  await bindingWrite(
    '移除单条负责源',
    `用户 #${b.userId} / 角色 #${b.roleId} / 绑定 #${b.userRoleId}：只移除负责源 #${s.id} ${s.name}，不改其他角色绑定或独立业务授权。`,
    () => removeIamS1BindingDatasource(b.userRoleId, s.id, '界面移除单条后台负责源'),
    () =>
      currentBinding(b) &&
      b.responsibleDatasources.includes(s) &&
      b.bindingEnabled &&
      b.roleEnabled &&
      !b.protectedRole,
  )
}
watch(actorKey, initialize, { immediate: true, flush: 'sync' })
</script>
<template>
  <div class="iam-s1-org">
    <TaskPageHeader
      title="组织、角色与负责源"
      eyebrow="权限与组织 / 组织配置"
      description-mode="inline"
      description="先明确角色能做什么，再逐个用户角色绑定负责源。部门与业务数据授权、后台管理范围分别维护。"
    />
    <GovernanceWriteNotice />
    <ErrorState v-if="iamS1.errorMessage" :message="iamS1.errorMessage" @retry="iamS1.load(true)" />
    <LoadingState v-else-if="!iamS1.loaded" text="正在核对身份与能力摘要…" />
    <template v-else>
      <div class="org-contract">
        <div>
          <small>用户与部门</small>
          <strong>谁在组织里</strong>
          <span>账号身份、部门归属分别维护</span>
        </div>
        <div>
          <small>角色功能</small>
          <strong>角色能做什么</strong>
          <span>模板不带特权，也不自动授予数据</span>
        </div>
        <div>
          <small>每个用户角色绑定</small>
          <strong>在哪些源管理</strong>
          <span>功能与源来自同一绑定，不跨角色拼接</span>
        </div>
      </div>
      <el-tabs :model-value="activeTab" @update:model-value="selectTab">
        <el-tab-pane label="用户" name="users" />
        <el-tab-pane label="部门" name="departments" />
        <el-tab-pane label="角色" name="roles" />
        <el-tab-pane label="用户角色与负责源" name="bindings" />
        <el-tab-pane label="功能目录" name="catalog" />
      </el-tabs>
      <p v-if="writeError" class="notice" role="status">{{ writeError }}</p>
      <UserList v-if="activeTab === 'users' && canViewUsers" :key="epoch" />
      <DepartmentTree v-else-if="activeTab === 'departments' && canViewDepartments" :key="epoch" />
      <template v-else-if="activeTab === 'roles' && canViewRoles">
        <div class="section-heading">
          <div>
            <h3>角色与功能配置</h3>
            <p>角色名称不产生特权。数据授权、后台负责源仍按各自事实计算。</p>
          </div>
          <div class="actions">
            <el-input
              v-model="roleKeyword"
              clearable
              placeholder="搜索角色名称或编码"
              @keyup.enter="loadRoles"
            />
            <el-button @click="loadRoles">搜索 / 重读</el-button>
            <el-button v-if="canManageRoles" type="primary" @click="openRole()">新建角色</el-button>
          </div>
        </div>
        <section v-if="roleFormVisible && canManageRoles" class="panel role-editor">
          <div class="section-heading">
            <h3>{{ editingRoleId ? '编辑角色 #' + editingRoleId : '创建普通角色' }}</h3>
            <el-button :disabled="savingRole" @click="closeRole">关闭编辑</el-button>
          </div>
          <div class="form-grid">
            <label>
              角色编码
              <el-input v-model="roleForm.roleCode" placeholder="角色编码（英文/数字）" />
            </label>
            <label>
              中文名称
              <el-input v-model="roleForm.roleName" placeholder="角色中文名称" />
            </label>
            <label>
              用途
              <el-input v-model="roleForm.description" placeholder="说明角色用途" />
            </label>
            <label>
              变更依据
              <el-input v-model="roleForm.reason" placeholder="本次角色功能变更依据" />
            </label>
          </div>
          <details>
            <summary>按已有模板填充当前草稿</summary>
            <LoadingState v-if="loading.templates" text="读取模板…" />
            <ErrorState v-if="errors.templates" :message="errors.templates" @retry="loadTemplates" />
            <div class="template-grid">
              <button
                v-for="t in templates"
                :key="t.code"
                class="template-card"
                :disabled="savingRole || (t.systemAdminOnly && !iamS1.systemAdmin)"
                @click="applyTemplate(t)"
              >
                <strong>{{ t.name }}</strong>
                <span>{{ t.description }}</span>
                <small>{{ t.capabilitySummary }}；数据：{{ t.dataHint }}</small>
              </button>
            </div>
          </details>
          <h4>本次功能（{{ roleForm.functionCodes.length }}）</h4>
          <p v-if="!canViewCatalog" class="notice">
            缺少organization:permission:view，功能目录不可用。已选/模板功能保留，不自动补权或删除；服务端仍验证依赖与可配置范围。
          </p>
          <ErrorState v-if="errors.functions" :message="errors.functions" @retry="loadFunctions" />
          <LoadingState v-if="loading.functions" text="读取功能说明…" />
          <el-checkbox-group
            v-model="roleForm.functionCodes"
            v-if="canViewCatalog && !errors.functions"
            class="function-matrix"
          >
            <div v-for="group in editorDomains" :key="group.domain">
              <h4>{{ group.domain }}</h4>
              <div v-for="f in group.items" :key="f.code" class="function-row">
                <el-checkbox
                  :value="f.code"
                  :disabled="
                    savingRole ||
                    ((!f.active || (f.systemAdminOnly && !iamS1.systemAdmin)) &&
                      !roleForm.functionCodes.includes(f.code))
                  "
                >
                  <strong>{{ f.name }}</strong>
                  <span>{{ f.description }}</span>
                  <small>
                    {{ f.code }}；{{
                      f.systemAdminOnly
                        ? '仅系统管理员可配置'
                        : isIncludedByDependency(f.code)
                          ? '随已选功能依赖包含'
                          : '依赖：' + (f.dependencies.join('、') || '无')
                    }}
                  </small>
                </el-checkbox>
              </div>
            </div>
          </el-checkbox-group>
          <p class="code-summary">
            已选功能码：{{
              roleForm.functionCodes.join('、') || '尚未选择'
            }}；编辑保留原启用状态，不自动重新启用。
          </p>
          <el-button
            type="primary"
            :loading="savingRole"
            :disabled="locked(roleLock(editingRoleId))"
            @click="saveRole"
          >
            保存角色
          </el-button>
        </section>
        <section class="panel">
          <ErrorState v-if="errors.roles" :message="errors.roles" @retry="loadRoles" />
          <LoadingState v-else-if="loading.roles" text="读取角色列表…" />
          <EmptyState v-else-if="!roles.length" message="本次未返回匹配角色。" />
          <el-table v-else :data="roles">
            <el-table-column label="角色" min-width="220">
              <template #default="{ row }">
                <strong>{{ row.roleName }}</strong>
                <small>{{ row.roleCode }} · #{{ row.id }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="capabilitySummary" label="功能摘要" min-width="300" />
            <el-table-column prop="memberCount" label="启用成员" width="100" />
            <el-table-column label="角色状态" width="150">
              <template #default="{ row }">
                {{ roleStatus(row) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <template v-if="canManageRoles && !row.protectedRole">
                  <el-button
                    link
                    :disabled="savingRole || !roleVerified(row) || locked(roleLock(row.id))"
                    @click="startEdit(row)"
                  >
                    编辑
                  </el-button>
                  <el-button
                    link
                    type="danger"
                    :disabled="savingRole || !roleVerified(row) || locked(roleLock(row.id))"
                    @click="removeRole(row)"
                  >
                    删除
                  </el-button>
                </template>
                <span v-else>{{ row.protectedRole ? '服务端固定维护' : '仅查看' }}</span>
              </template>
            </el-table-column>
          </el-table>
          <p>列表为本次返回记录；删除仍有任何成员或本人持有的角色会被服务端拒绝。</p>
        </section>
      </template>
      <template v-else-if="activeTab === 'bindings' && canManageBindings">
        <section class="panel">
          <h3>逐个用户、逐个角色绑定管理</h3>
          <p>
            后台绑定只由系统管理员维护，不能修改本人；不同角色绑定的源不能拼给另一角色功能。负责源本身不授予查询数据。
          </p>
          <ErrorState v-if="errors.users" :message="errors.users" @retry="loadUsers" />
          <ErrorState v-if="errors.bindingRoles" :message="errors.bindingRoles" @retry="loadBindingRoles" />
          <div class="actions">
            <el-select
              v-model="selectedUserId"
              filterable
              remote
              reserve-keyword
              :remote-method="loadUsers"
              placeholder="搜索用户（姓名/账号）"
              :loading="loading.users"
              @change="loadBindings"
            >
              <el-option v-for="u in users" :key="u.id" :value="u.id" :label="`${u.name} · #${u.id}`" />
            </el-select>
            <el-select
              v-model="assignRoleId"
              placeholder="选择要分配的普通角色"
              :disabled="!canMutateUser || bindingBusy"
              :loading="loading.bindingRoles"
            >
              <el-option
                v-for="r in bindingRoles.filter((r) => !r.protectedRole && r.enabled)"
                :key="r.id"
                :value="r.id"
                :label="r.roleName"
              />
            </el-select>
            <el-button
              type="primary"
              :disabled="!canMutateUser || !assignRoleId || bindingBusy || locked(userLock())"
              @click="assignRole()"
            >
              分配角色
            </el-button>
            <el-button @click="loadBindings">重读该用户绑定</el-button>
          </div>
          <p v-if="selectedUserId === selfId" class="notice">
            可以核对本人绑定，不能从此修改本人的角色或负责源。
          </p>
        </section>
        <ErrorState v-if="errors.bindings" :message="errors.bindings" @retry="loadBindings" />
        <LoadingState v-else-if="loading.bindings" text="读取精确用户角色绑定…" />
        <EmptyState v-else-if="!selectedUserId" message="先选择具体用户。" />
        <EmptyState v-else-if="!bindings.length" message="该用户本次未返回角色绑定。" />
        <section v-for="b in bindings" :key="b.userRoleId" class="panel binding-block">
          <div class="section-heading">
            <div>
              <h3>
                {{ b.roleName }}
                <small>绑定 #{{ b.userRoleId }} · 用户 #{{ b.userId }} · 角色 #{{ b.roleId }}</small>
              </h3>
              <p>
                {{ b.capabilitySummary }}；{{ b.bindingEnabled ? '绑定启用' : '绑定停用' }} /
                {{ b.roleEnabled ? '角色启用' : '角色停用' }}
              </p>
            </div>
            <div class="actions">
              <el-button
                type="warning"
                :disabled="!currentBinding(b) || !b.bindingEnabled || bindingBusy || locked(userLock())"
                @click="disableBinding(b)"
              >
                停用绑定
              </el-button>
              <el-button
                type="danger"
                :disabled="!currentBinding(b) || bindingBusy || locked(userLock())"
                @click="removeBinding(b)"
              >
                解除角色
              </el-button>
            </div>
          </div>
          <h4>本绑定已返回的负责源</h4>
          <div class="source-list">
            <div v-for="s in b.responsibleDatasources" :key="s.id">
              <span>{{ s.name }} · #{{ s.id }} · {{ s.enabled ? '源启用' : '源停用' }}</span>
              <el-button
                link
                type="danger"
                :disabled="
                  !currentBinding(b) ||
                  !b.bindingEnabled ||
                  !b.roleEnabled ||
                  b.protectedRole ||
                  bindingBusy ||
                  locked(userLock())
                "
                @click="removeSource(b, s)"
              >
                移除此负责源
              </el-button>
            </div>
            <p v-if="!b.responsibleDatasources.length">本绑定未返回负责源；这不表示另一绑定的源可以通用。</p>
          </div>
          <template v-if="!b.protectedRole">
            <h4>追加负责源</h4>
            <ErrorState v-if="errors.sources" :message="errors.sources" @retry="loadDatasources" />
            <div class="actions">
              <el-select
                v-model="bindingDatasourceDraft[b.userRoleId]"
                multiple
                placeholder="选择本次追加的启用源"
                :loading="loading.sources"
                :disabled="
                  !currentBinding(b) || !b.bindingEnabled || !b.roleEnabled || bindingBusy || !!errors.sources
                "
              >
                <el-option
                  v-for="s in datasources.filter(
                    (s) => s.enabled && !b.responsibleDatasources.some((old) => old.id === s.id),
                  )"
                  :key="s.id"
                  :value="s.id"
                  :label="s.name"
                />
              </el-select>
              <el-input v-model="bindingReason[b.userRoleId]" placeholder="本次负责源变更依据" />
              <el-button
                :disabled="
                  !currentBinding(b) ||
                  !b.bindingEnabled ||
                  !b.roleEnabled ||
                  bindingBusy ||
                  !!errors.sources ||
                  locked(userLock())
                "
                @click="saveBindingDatasources(b)"
              >
                确认追加负责源
              </el-button>
            </div>
            <p>追加接口只新增/启用所选关联，取消选择不会移除原源；移除使用上方单条精确动作。</p>
          </template>
          <p v-else>受保护系统管理员角色不维护源绑定；服务端固定规则仍适用。</p>
        </section>
      </template>
      <template v-else-if="activeTab === 'catalog' && canViewCatalog">
        <section class="panel">
          <div class="section-heading">
            <div>
              <h3>固定功能目录 · 只读</h3>
              <p>功能由版本脚本维护，页面不新增或修改功能点；已返回数量不代表角色已获授权。</p>
            </div>
            <el-input
              v-model="catalogKeyword"
              clearable
              placeholder="筛选功能名称/编码/说明"
              class="catalog-search"
            />
          </div>
          <ErrorState v-if="errors.functions" :message="errors.functions" @retry="loadFunctions" />
          <LoadingState v-else-if="loading.functions" text="读取固定功能目录…" />
          <template v-else>
            <p>本次返回 {{ functions.length }} 项；按域查看依赖、工作区和配置限制。</p>
            <section v-for="g in catalogDomains" :key="g.domain" class="catalog-domain">
              <h4>{{ g.domain }}</h4>
              <el-table :data="g.items">
                <el-table-column prop="name" label="功能" width="180" />
                <el-table-column prop="description" label="允许做什么" min-width="250" />
                <el-table-column prop="code" label="功能码" min-width="240" />
                <el-table-column prop="workspace" label="工作区" min-width="150" />
                <el-table-column label="依赖与限制" min-width="220">
                  <template #default="{ row }">
                    {{ row.dependencies.join('、') || '无依赖' }}
                    <small>
                      {{ row.systemAdminOnly ? '仅系统管理员可配置' : '普通角色可配置' }}；{{
                        row.active ? '有效' : '停用'
                      }}
                    </small>
                  </template>
                </el-table-column>
              </el-table>
            </section>
            <EmptyState v-if="!catalogDomains.length" message="本次返回目录中无匹配功能。" />
          </template>
        </section>
      </template>
      <EmptyState
        v-else
        :message="`当前入口缺少查看能力：${activeTab === 'users' ? 'organization:user:view' : activeTab === 'departments' ? 'organization:department:view' : activeTab === 'roles' ? 'organization:role:view' : activeTab === 'catalog' ? 'organization:permission:view' : '系统管理员后台绑定维护'}。未调用其他目录补权。`"
      />
    </template>
  </div>
</template>
<style scoped>
.iam-s1-org {
  display: grid;
  gap: 20px;
}
.org-contract {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
}
.org-contract > div {
  display: grid;
  gap: 8px;
}
.org-contract small {
  color: var(--do-primary-strong);
}
.org-contract span,
.panel p,
.section-heading p,
.code-summary {
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.8;
}
.panel {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  min-width: 0;
}
.panel h3,
.section-heading h3 {
  margin: 0;
  font-size: 17px;
}
.panel small {
  display: block;
  font-size: 12px;
  color: var(--do-muted);
  line-height: 1.7;
}
.section-heading {
  display: flex;
  justify-content: space-between;
  align-items: start;
  gap: 16px;
  flex-wrap: wrap;
}
.actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.actions > .el-input,
.actions > .el-select {
  width: 260px;
  max-width: 100%;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin: 20px 0;
}
.form-grid label {
  display: grid;
  gap: 6px;
  font-size: 12px;
  color: var(--do-muted);
}
.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(230px, 100%), 1fr));
  gap: 12px;
  margin-top: 14px;
}
.template-card {
  display: grid;
  gap: 8px;
  text-align: left;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-bg);
  cursor: pointer;
}
.template-card:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.template-card span {
  font-size: 12px;
  color: var(--do-muted);
}
.function-matrix {
  max-height: 440px;
  overflow: auto;
  margin: 12px 0;
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
}
.function-row {
  display: block;
  margin-bottom: 10px;
}
.function-row :deep(.el-checkbox) {
  height: auto;
  align-items: start;
}
.function-row :deep(.el-checkbox__label) {
  white-space: normal;
}
.function-row span {
  display: block;
  font-size: 12px;
  color: var(--do-muted);
}
.source-list {
  display: grid;
  gap: 8px;
}
.source-list > div {
  display: flex;
  gap: 12px;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid var(--do-line);
  padding: 8px 0;
  font-size: 13px;
}
.notice {
  padding: 12px;
  background: var(--do-bg);
  border-left: 3px solid var(--do-primary);
}
.catalog-search {
  width: 300px;
  max-width: 100%;
}
.catalog-domain {
  margin-top: 24px;
}
@media (max-width: 800px) {
  .org-contract {
    grid-template-columns: minmax(0, 1fr);
  }
  .panel {
    padding: 14px;
  }
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
