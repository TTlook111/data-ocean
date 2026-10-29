<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createUser,
  deleteUser,
  downloadUserImportTemplate,
  exportUsers,
  getUser,
  importUsers,
  listDepartments,
  listUsers,
  resetUserPassword,
  updateUser,
  updateUserStatus,
  type DepartmentNode,
  type UserItem,
  type UserPayload,
  type UserQuery,
} from '../../../api/admin/user'
import { useIamWorkspaceSession } from '../../../composables/useIamWorkspaceSession'
import { trackGovernanceWrite, pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
import GovernanceWriteNotice from '../../../components/admin/GovernanceWriteNotice.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
const session = useIamWorkspaceSession(),
  iamS1 = session.iam
const selfId = computed(() => session.auth.currentUser?.id ?? session.auth.user?.userId),
  actorKey = computed(() => JSON.stringify([session.key.value, selfId.value])),
  owned = (k: string) => session.alive() && session.ready.value && actorKey.value === k
const canViewUsers = computed(() => session.canGlobal('organization:user:view')),
  canManageUsers = computed(() => canViewUsers.value && session.canGlobal('organization:user:manage')),
  canExportUsers = computed(() => canViewUsers.value && session.canGlobal('organization:user:export')),
  canViewDepartments = computed(() => session.canGlobal('organization:department:view'))
const users = ref<UserItem[]>([]),
  total = ref<number>(),
  departments = ref<DepartmentNode[]>([]),
  loading = ref(false),
  errorMessage = ref(''),
  optionLoading = ref(false),
  optionError = ref(''),
  writeError = ref(''),
  saving = ref(false),
  rowBusy = ref(false),
  importLoading = ref(false),
  exportLoading = ref(false),
  templateLoading = ref(false)
const query = reactive<UserQuery>({ page: 1, pageSize: 20 }),
  queryKey = computed(() => JSON.stringify([actorKey.value, query])),
  listAcceptedKey = ref('')
const formRef = ref<FormInstance>(),
  importInputRef = ref<HTMLInputElement>(),
  dialogVisible = ref(false),
  editingId = ref<number>(),
  editingUser = ref<UserItem>(),
  form = reactive<UserPayload>({
    username: '',
    password: '',
    realName: '',
    email: '',
    phone: '',
    departmentId: undefined,
  })
const detailVisible = ref(false),
  detailLoading = ref(false),
  detailError = ref(''),
  detail = ref<UserItem | null>(null),
  detailTarget = ref<UserItem>()
const passwordVisible = ref(false),
  tempPassword = ref(''),
  passwordTarget = ref<{ id: number; username: string }>(),
  passwordCopying = ref(false)
const importReport = ref<{ filename: string; success: number; failed: number; errors: string[] }>()
const identity = () => session.auth.token || '',
  userLock = (id?: number) => `iam-user:${id ?? 'new:' + form.username?.trim()}`
const importLocked = computed(() =>
    pendingGovernanceWrites.value.some(
      (x) => x.identity === identity() && x.keys.includes('iam-users-import'),
    ),
  ),
  locked = (id?: number) =>
    importLocked.value ||
    pendingGovernanceWrites.value.some((x) => x.identity === identity() && x.keys.includes(userLock(id)))
const statusOptions = [
    { label: '正常', value: 1 },
    { label: '禁用', value: 2 },
    { label: '锁定', value: 3 },
  ],
  statusLabel = (s: number) => statusOptions.find((x) => x.value === s)?.label || `未识别（${s ?? '未记录'}）`
const rules = computed<FormRules>(() => ({
  username: editingId.value
    ? []
    : [
        { required: true, message: '请输入用户名', trigger: 'blur' },
        { pattern: /^[A-Za-z0-9_]{4,50}$/, message: '用户名为4–50位字母、数字或下划线', trigger: 'blur' },
      ],
  password: editingId.value
    ? []
    : [
        { required: true, message: '请输入初始密码', trigger: 'blur' },
        {
          pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,32}$/,
          message: '密码8–32位且包含字母与数字',
          trigger: 'blur',
        },
      ],
  realName: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { min: 2, max: 50, message: '姓名2–50位', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  phone: [],
}))
const departmentPathMap = computed(() => {
  const map = new Map<number, string>()
  function walk(rows: DepartmentNode[], parents: string[] = []) {
    for (const row of rows) {
      const names = [...parents, row.deptName]
      map.set(row.id, names.join(' / '))
      walk(row.children || [], names)
    }
  }
  walk(departments.value)
  return map
})
const departmentText = (u: UserItem) =>
  u.departmentId
    ? departmentPathMap.value.get(u.departmentId) || u.departmentName || `部门 #${u.departmentId}`
    : '未分配部门'
const currentRow = (u: UserItem) =>
  canManageUsers.value &&
  listAcceptedKey.value === queryKey.value &&
  users.value.includes(u) &&
  Number.isSafeInteger(u.id) &&
  u.id > 0
const canDelete = (u: UserItem) => currentRow(u) && !!selfId.value && u.id !== selfId.value
function message(e: unknown, fallback: string) {
  return (e as any)?.response?.data?.message || (e instanceof Error ? e.message : fallback)
}
let listRequest = 0,
  optionRequest = 0,
  detailRequest = 0,
  editorEpoch = 0,
  saveOperation = 0,
  rowOperation = 0,
  fileOperation = 0,
  exportOperation = 0,
  templateOperation = 0,
  passwordEpoch = 0,
  filtersReady = false,
  filterTimer: ReturnType<typeof setTimeout> | undefined
function clearPassword() {
  passwordEpoch++
  tempPassword.value = ''
  passwordTarget.value = undefined
  passwordVisible.value = false
  passwordCopying.value = false
}
function resetForm() {
  editorEpoch++
  saveOperation++
  editingId.value = undefined
  editingUser.value = undefined
  Object.assign(form, {
    username: '',
    password: '',
    realName: '',
    email: '',
    phone: '',
    departmentId: undefined,
  })
  saving.value = false
  formRef.value?.clearValidate?.()
}
function closeEditor() {
  dialogVisible.value = false
  resetForm()
  session.cancelOwn()
}
function closeDetail() {
  detailRequest++
  detailVisible.value = false
  detail.value = null
  detailTarget.value = undefined
  detailError.value = ''
  detailLoading.value = false
}
watch(
  detailVisible,
  (v) => {
    if (!v) closeDetail()
  },
  { flush: 'sync' },
)
watch(
  passwordVisible,
  (v) => {
    if (!v) clearPassword()
  },
  { flush: 'sync' },
)
watch(
  dialogVisible,
  (v) => {
    if (!v) resetForm()
  },
  { flush: 'sync' },
)
async function initialize() {
  filtersReady = false
  clearTimeout(filterTimer)
  listRequest++
  optionRequest++
  rowOperation++
  fileOperation++
  exportOperation++
  templateOperation++
  closeEditor()
  closeDetail()
  clearPassword()
  users.value = []
  total.value = undefined
  departments.value = []
  listAcceptedKey.value = ''
  errorMessage.value = ''
  optionError.value = ''
  writeError.value = ''
  importReport.value = undefined
  loading.value = false
  optionLoading.value = false
  rowBusy.value = false
  importLoading.value = false
  exportLoading.value = false
  templateLoading.value = false
  Object.assign(query, {
    username: undefined,
    realName: undefined,
    departmentId: undefined,
    status: undefined,
    page: 1,
    pageSize: 20,
  })
  const k = actorKey.value
  if (owned(k)) filtersReady = true
  await Promise.all([fetchUsers(), fetchOptions()])
}
async function fetchUsers() {
  clearTimeout(filterTimer)
  const k = queryKey.value,
    r = ++listRequest
  errorMessage.value = ''
  if (!canViewUsers.value) {
    loading.value = false
    return
  }
  loading.value = true
  listAcceptedKey.value = ''
  try {
    const result = await listUsers({ ...query })
    if (!session.alive() || k !== queryKey.value || r !== listRequest) return
    const data = result.data
    if (
      !data ||
      !Array.isArray(data.records) ||
      !Number.isSafeInteger(data.total) ||
      data.total < 0 ||
      data.records.some((u) => !Number.isSafeInteger(u.id) || u.id <= 0)
    )
      throw new Error('用户分页回执不完整')
    users.value = data.records
    total.value = data.total
    listAcceptedKey.value = k
  } catch (e) {
    if (session.alive() && k === queryKey.value && r === listRequest) {
      users.value = []
      total.value = undefined
      errorMessage.value = message(e, '用户列表读取失败')
    }
  } finally {
    if (session.alive() && k === queryKey.value && r === listRequest) loading.value = false
  }
}
async function fetchOptions() {
  const k = actorKey.value,
    r = ++optionRequest
  optionError.value = ''
  if (!canViewDepartments.value) {
    departments.value = []
    optionLoading.value = false
    return
  }
  optionLoading.value = true
  try {
    const result = await listDepartments()
    if (!owned(k) || r !== optionRequest) return
    departments.value = result.data || []
  } catch (e) {
    if (owned(k) && r === optionRequest) optionError.value = message(e, '部门辅助目录读取失败')
  } finally {
    if (owned(k) && r === optionRequest) optionLoading.value = false
  }
}
watch(
  () => [query.username, query.realName, query.departmentId, query.status],
  () => {
    if (!filtersReady) return
    listRequest++
    listAcceptedKey.value = ''
    loading.value = true
    clearTimeout(filterTimer)
    filterTimer = setTimeout(() => {
      query.page = 1
      void fetchUsers()
    }, 300)
  },
  { flush: 'sync' },
)
function search() {
  query.page = 1
  void fetchUsers()
}
function resetFilters() {
  Object.assign(query, {
    username: undefined,
    realName: undefined,
    departmentId: undefined,
    status: undefined,
    page: 1,
  })
  void fetchUsers()
}
async function openDetail(row: UserItem) {
  if (!canViewUsers.value || !Number.isSafeInteger(row.id)) return
  const k = actorKey.value,
    r = ++detailRequest,
    id = row.id
  detailTarget.value = { ...row }
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = null
  try {
    const result = await getUser(id)
    if (!owned(k) || r !== detailRequest || !detailVisible.value) return
    if (!result.data || result.data.id !== id) throw new Error('用户详情回执与目标ID不符')
    detail.value = result.data
  } catch (e) {
    if (owned(k) && r === detailRequest && detailVisible.value) detailError.value = message(e, '详情读取失败')
  } finally {
    if (owned(k) && r === detailRequest) detailLoading.value = false
  }
}
function openCreate() {
  if (!canManageUsers.value || saving.value || importLocked.value) return
  resetForm()
  dialogVisible.value = true
}
function openEdit(row: UserItem) {
  if (!currentRow(row) || saving.value || locked(row.id)) return
  resetForm()
  editingId.value = row.id
  editingUser.value = { ...row }
  Object.assign(form, {
    username: row.username,
    password: '',
    realName: row.realName,
    email: row.email || '',
    phone: row.phone || '',
    departmentId: row.departmentId,
  })
  dialogVisible.value = true
}
async function saveUser() {
  if (!canManageUsers.value || saving.value || locked(editingId.value) || !dialogVisible.value) return
  const k = actorKey.value,
    e = editorEpoch,
    o = ++saveOperation,
    id = editingId.value,
    signature = JSON.stringify(form),
    actor = identity(),
    key = userLock(id)
  saving.value = true
  writeError.value = ''
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (
      !valid ||
      !owned(k) ||
      e !== editorEpoch ||
      o !== saveOperation ||
      !canManageUsers.value ||
      JSON.stringify(form) !== signature
    )
      return
    const original = editingUser.value,
      dept = form.departmentId ?? undefined,
      originalDept = original?.departmentId ?? undefined
    if (id && (!original || original.id !== id)) return
    if (id && original?.departmentId && dept === undefined) {
      writeError.value = '已有主部门暂不支持直接清空，请保留或改选有效部门。'
      return
    }
    if (
      dept !== originalDept &&
      dept !== undefined &&
      (!canViewDepartments.value || optionError.value || !departmentPathMap.value.has(dept))
    ) {
      writeError.value = '新部门未经过当前有权目录核验；原部门与其他输入保留。'
      return
    }
    if (id && original) {
      const fresh = (await getUser(id)).data
      if (
        !owned(k) ||
        e !== editorEpoch ||
        o !== saveOperation ||
        !canManageUsers.value ||
        JSON.stringify(form) !== signature
      )
        return
      if (
        !fresh ||
        fresh.id !== id ||
        fresh.username !== original.username ||
        (['realName', 'email', 'phone', 'departmentId'] as const).some(
          (field) => (fresh[field] ?? null) !== (original[field] ?? null),
        )
      ) {
        writeError.value = '账号资料或主部门已在其他会话变化；原草稿保留，请核对后重新打开编辑，不自动覆盖。'
        return
      }
    }
    const payload: UserPayload = {
      realName: form.realName,
      email: form.email,
      phone: form.phone,
      departmentId: dept,
    }
    if (id && dept !== originalDept) {
      await ElMessageBox.confirm(
        `用户 #${id} ${original?.username}：主部门从 ${original?.departmentId ? '#' + original.departmentId : '未分配'} 改为 ${dept ? '#' + dept : '未分配'}，可能改变部门默认数据授权的主体匹配；不修改角色或负责源。`,
        '变更精确用户主部门',
        {
          type: 'warning',
          confirmButtonText: '确认变更部门',
          cancelButtonText: '取消',
          cancelButtonClass: session.cancelClass,
          closeOnClickModal: false,
        },
      )
      if (!owned(k) || e !== editorEpoch || o !== saveOperation || !canManageUsers.value) return
    }
    await trackGovernanceWrite(actor, [key], `用户 ${id ? '#' + id : form.username} 保存资料`, async () => {
      if (id) return updateUser(id, payload)
      const result = await createUser({ ...payload, username: form.username, password: form.password })
      if (!Number.isSafeInteger(result.data?.id) || Number(result.data?.id) <= 0)
        throw new Error('创建回执缺少用户ID，结果未知，请核对账号列表')
      return result
    })
    if (!owned(k) || e !== editorEpoch || o !== saveOperation) return
    if (JSON.stringify(form) === signature) closeEditor()
    else writeError.value = '原资料已保存，新的输入保留。'
    await fetchUsers()
  } catch (cause) {
    if (owned(k) && e === editorEpoch && o === saveOperation && cause !== 'cancel' && cause !== 'close')
      writeError.value = message(cause, '保存失败；草稿保留，未知先核对原账号')
  } finally {
    if (owned(k) && e === editorEpoch && o === saveOperation) saving.value = false
  }
}
async function rowWrite(
  row: UserItem,
  label: string,
  effect: string,
  write: () => Promise<unknown>,
  after?: () => void,
) {
  if (!currentRow(row) || rowBusy.value || locked(row.id)) return
  const k = actorKey.value,
    o = ++rowOperation,
    id = row.id,
    name = row.username,
    key = userLock(id),
    actor = identity()
  rowBusy.value = true
  writeError.value = ''
  try {
    await ElMessageBox.confirm(
      `用户 #${id} ${name}（${row.realName}）：${effect}。目标账号与管理员保留规则以Java实际校验为准。`,
      label,
      {
        type: 'warning',
        confirmButtonText: '确认' + label,
        cancelButtonText: '取消',
        cancelButtonClass: session.cancelClass,
        closeOnClickModal: false,
      },
    )
    if (!owned(k) || o !== rowOperation || !currentRow(row)) return
    await trackGovernanceWrite(actor, [key], `用户 #${id} ${name} ${label}`, write)
    if (!owned(k) || o !== rowOperation) return
    after?.()
    await fetchUsers()
  } catch (cause) {
    if (owned(k) && o === rowOperation && cause !== 'cancel' && cause !== 'close')
      writeError.value = message(cause, '操作失败，结果未知先核对精确账号')
  } finally {
    if (owned(k) && o === rowOperation) rowBusy.value = false
  }
}
async function changeStatus(row: UserItem, status: number) {
  if (![1, 2].includes(status) || ![1, 2, 3].includes(row.status) || row.status === status) return
  await rowWrite(
    row,
    status === 2 ? '禁用账号' : row.status === 3 ? '解锁账号' : '启用账号',
    status === 2 ? '禁用将阻止后续登录与使用' : '状态将改为正常，角色与数据授权仍独立判定',
    () => updateUserStatus(row.id, status),
  )
}
async function removeUser(row: UserItem) {
  if (!canDelete(row)) return
  await rowWrite(
    row,
    '删除账号',
    '删除会停用该用户的S1绑定并撤回直接用户授权，同时失效登录；不提供界面恢复',
    () => deleteUser(row.id),
  )
}
async function resetPassword(row: UserItem) {
  if (row.id === selfId.value) {
    writeError.value = '本人请使用“修改本人密码”入口，避免重置后旧登录失效而丢失临时凭据。'
    return
  }
  const resetActor = actorKey.value,
    resetEpoch = passwordEpoch
  await rowWrite(row, '重置密码', '旧凭据失效；新临时密码仅展示本次响应，用户须登录后修改', async () => {
    const result = await resetUserPassword(row.id)
    if (typeof result.data?.tempPassword !== 'string' || !result.data.tempPassword)
      throw new Error('重置回执缺少临时密码，结果未知；不要直接重复重置')
    if (!session.alive()) return
    if (!owned(resetActor) || resetEpoch !== passwordEpoch) return
    tempPassword.value = result.data.tempPassword
    passwordTarget.value = { id: row.id, username: row.username }
    passwordVisible.value = true
  })
}
// A reset response is only consumable by the actor and one-time display session that started it.
async function startResetPassword(row: UserItem) {
  if (row.id === selfId.value) {
    writeError.value = '本人请使用“修改本人密码”入口。'
    return
  }
  if (!currentRow(row) || rowBusy.value || locked(row.id)) return
  clearPassword()
  await resetPassword(row)
}
async function copyPassword() {
  const value = tempPassword.value,
    k = actorKey.value,
    e = passwordEpoch
  if (!value || !passwordVisible.value || passwordCopying.value) return
  passwordCopying.value = true
  try {
    await navigator.clipboard.writeText(value)
    if (owned(k) && e === passwordEpoch) ElMessage.success('临时密码已复制')
  } catch {
    if (owned(k) && e === passwordEpoch) writeError.value = '复制失败，请在本次窗口内手动记录临时密码。'
  } finally {
    if (owned(k) && e === passwordEpoch) passwordCopying.value = false
  }
}
function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob),
    a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}
async function downloadTemplate() {
  if (!canViewUsers.value || templateLoading.value) return
  const k = actorKey.value,
    o = ++templateOperation
  templateLoading.value = true
  try {
    const blob = await downloadUserImportTemplate()
    if (owned(k) && o === templateOperation && canViewUsers.value)
      downloadBlob(blob, 'dataocean-user-import-template.csv')
  } catch (e) {
    if (owned(k) && o === templateOperation) writeError.value = message(e, '模板下载失败')
  } finally {
    if (owned(k) && o === templateOperation) templateLoading.value = false
  }
}
function openImportPicker() {
  if (canManageUsers.value && !importLoading.value && !importLocked.value) importInputRef.value?.click()
}
async function handleImportFile(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0]
  input.value = ''
  if (
    !file ||
    !canManageUsers.value ||
    importLoading.value ||
    pendingGovernanceWrites.value.some(
      (x) => x.identity === identity() && x.keys.some((k) => k.startsWith('iam-user')),
    )
  )
    return
  const k = actorKey.value,
    o = ++fileOperation,
    actor = identity()
  importLoading.value = true
  writeError.value = ''
  importReport.value = undefined
  try {
    await trackGovernanceWrite(actor, ['iam-users-import'], `导入用户文件 ${file.name}`, async () => {
      const result = await importUsers(file),
        data = result.data
      if (
        !data ||
        !Number.isSafeInteger(data.success) ||
        data.success < 0 ||
        !Number.isSafeInteger(data.failed) ||
        data.failed < 0 ||
        !Array.isArray(data.errors) ||
        data.errors.some((e) => typeof e !== 'string') ||
        data.errors.length !== data.failed
      )
        throw new Error('导入回执不完整，结果未知，先核对账号及原文件，不自动补导')
      if (owned(k) && o === fileOperation) importReport.value = { filename: file.name, ...data }
      return result
    })
    if (owned(k) && o === fileOperation) await fetchUsers()
  } catch (e) {
    if (owned(k) && o === fileOperation) writeError.value = message(e, '导入失败，未知先核对原文件对应账号')
  } finally {
    if (owned(k) && o === fileOperation) importLoading.value = false
  }
}
async function exportCurrentUsers() {
  if (!canExportUsers.value || exportLoading.value) return
  const k = actorKey.value,
    q = queryKey.value,
    params = { ...query },
    o = ++exportOperation
  exportLoading.value = true
  try {
    const blob = await exportUsers(params)
    if (owned(k) && q === queryKey.value && o === exportOperation && canExportUsers.value)
      downloadBlob(blob, 'dataocean-users.csv')
    else if (owned(k) && o === exportOperation)
      writeError.value = '导出期间筛选范围已变化，旧文件未下载，请按当前范围重新导出。'
  } catch (e) {
    if (owned(k) && o === exportOperation) writeError.value = message(e, '导出失败')
  } finally {
    if (owned(k) && o === exportOperation) exportLoading.value = false
  }
}
watch(actorKey, initialize, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => {
  clearTimeout(filterTimer)
  clearPassword()
  form.password = ''
  session.cancelOwn()
})
</script>
<template>
  <main class="user-workspace">
    <GovernanceWriteNotice />
    <div class="user-heading">
      <div>
        <h3>账号与主部门</h3>
        <p>
          账号资料、S1角色绑定、后台负责源与业务数据授权分别维护。当前资料接口不返回S1角色事实，不按用户名猜账号类别。
        </p>
      </div>
      <div class="actions">
        <a v-if="selfId" href="/change-password" class="self-password-link">修改本人密码</a>
        <input
          ref="importInputRef"
          type="file"
          accept=".csv,text/csv"
          class="hidden-file-input"
          @change="handleImportFile"
        />
        <el-button :disabled="!canViewUsers" :loading="templateLoading" @click="downloadTemplate">
          导入模板
        </el-button>
        <el-button
          :disabled="!canManageUsers || importLocked"
          :loading="importLoading"
          @click="openImportPicker"
        >
          导入用户
        </el-button>
        <el-button :disabled="!canExportUsers" :loading="exportLoading" @click="exportCurrentUsers">
          导出当前筛选
        </el-button>
        <el-button type="primary" :disabled="!canManageUsers || importLocked" @click="openCreate">
          新增用户
        </el-button>
      </div>
    </div>
    <p v-if="writeError" class="notice" role="status">{{ writeError }}</p>
    <section v-if="importReport" class="panel import-report">
      <h4>本次导入回执 · {{ importReport.filename }}</h4>
      <p>
        成功 {{ importReport.success }} 条；失败
        {{ importReport.failed }} 条。仅创建账号，不自动分配角色或授权；部分成功不自动重导。
      </p>
      <ul v-if="importReport.errors.length">
        <li v-for="(e, i) in importReport.errors" :key="i">{{ e }}</li>
      </ul>
    </section>
    <section class="panel">
      <div class="filters">
        <el-input v-model="query.username" clearable placeholder="搜索用户名" />
        <el-input v-model="query.realName" clearable placeholder="搜索姓名" />
        <el-tree-select
          v-if="canViewDepartments && !optionError"
          v-model="query.departmentId"
          clearable
          check-strictly
          :data="departments"
          :loading="optionLoading"
          node-key="id"
          placeholder="筛选部门"
          :props="{ label: 'deptName', children: 'children' }"
        />
        <el-select v-model="query.status" clearable placeholder="全部状态">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
      <p v-if="!canViewDepartments">
        没有organization:department:view，部门辅助目录不可用；用户列表不受影响，已有部门按接口事实显示。
      </p>
      <ErrorState v-if="optionError" :message="optionError" @retry="fetchOptions" />
    </section>
    <section class="panel">
      <div class="list-heading">
        <strong>当前筛选命中 {{ total ?? '—' }} 个用户</strong>
        <small>服务端分页；维护、导出分别核对功能能力</small>
      </div>
      <ErrorState v-if="iamS1.errorMessage" :message="iamS1.errorMessage" @retry="iamS1.load(true)" />
      <LoadingState v-else-if="!iamS1.loaded || loading" text="正在读取当前账号列表…" />
      <ErrorState v-else-if="errorMessage" :message="errorMessage" @retry="fetchUsers" />
      <EmptyState v-else-if="!canViewUsers" message="没有organization:user:view，不能读取用户资料。" />
      <EmptyState v-else-if="!users.length" message="当前筛选没有返回用户。" />
      <el-table v-else :data="users" row-key="id">
        <el-table-column label="账号" min-width="230">
          <template #default="{ row }">
            <strong>{{ row.realName || row.username }}</strong>
            <small>{{ row.username }} · #{{ row.id }}</small>
          </template>
        </el-table-column>
        <el-table-column label="主部门" min-width="200">
          <template #default="{ row }">{{ departmentText(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">{{ statusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column label="最近登录" min-width="170">
          <template #default="{ row }">{{ row.lastLoginAt || '未记录登录' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="260">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              link
              :disabled="!currentRow(row) || saving || rowBusy || locked(row.id)"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-dropdown trigger="click">
              <el-button link :disabled="!currentRow(row) || rowBusy || locked(row.id)">更多操作</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="row.status === 1" @click="changeStatus(row, 2)">
                    禁用账号
                  </el-dropdown-item>
                  <el-dropdown-item v-if="row.status === 2" @click="changeStatus(row, 1)">
                    启用账号
                  </el-dropdown-item>
                  <el-dropdown-item v-if="row.status === 3" @click="changeStatus(row, 1)">
                    解锁账号
                  </el-dropdown-item>
                  <el-dropdown-item v-if="row.id !== selfId" @click="startResetPassword(row)">
                    重置密码
                  </el-dropdown-item>
                  <el-dropdown-item v-else disabled>本人请用“修改本人密码”入口</el-dropdown-item>
                  <el-dropdown-item :disabled="!canDelete(row)" @click="removeUser(row)">
                    删除账号
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="!loading && !errorMessage && total !== undefined && total > 0"
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total,sizes,prev,pager,next"
        @change="fetchUsers"
      />
    </section>
    <el-drawer v-model="detailVisible" title="精确用户资料" size="min(560px,95vw)">
      <LoadingState v-if="detailLoading" text="正在读取用户详情…" />
      <ErrorState
        v-else-if="detailError"
        :message="detailError"
        @retry="detailTarget && openDetail(detailTarget)"
      />
      <dl v-else-if="detail" class="user-detail">
        <dt>用户</dt>
        <dd>#{{ detail.id }} · {{ detail.username }}</dd>
        <dt>姓名</dt>
        <dd>{{ detail.realName || '未记录' }}</dd>
        <dt>主部门</dt>
        <dd>{{ departmentText(detail) }}</dd>
        <dt>状态</dt>
        <dd>{{ statusLabel(detail.status) }}</dd>
        <dt>邮箱</dt>
        <dd>{{ detail.email || '未记录' }}</dd>
        <dt>手机</dt>
        <dd>{{ detail.phone || '未记录' }}</dd>
        <dt>最近登录</dt>
        <dd>{{ detail.lastLoginAt || '未记录' }}</dd>
        <dt>创建时间</dt>
        <dd>{{ detail.createdAt || '未记录' }}</dd>
        <dt>角色与负责源</dt>
        <dd>在独立绑定页核对；本接口不返回S1事实，不把空旧角色数组当作无角色。</dd>
      </dl>
    </el-drawer>
    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑用户 #' + editingId : '创建账号'"
      width="min(640px,94vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <p>这里只保存账号资料，不写入旧roleIds，不分配后台负责源或业务数据权。</p>
      <p v-if="writeError" class="notice" role="status">{{ writeError }}</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" :disabled="saving">
        <div class="form-grid">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" :disabled="!!editingId" placeholder="字母、数字或下划线" />
          </el-form-item>
          <el-form-item v-if="!editingId" label="初始密码" prop="password">
            <el-input v-model="form.password" type="password" show-password autocomplete="new-password" />
          </el-form-item>
          <el-form-item label="姓名" prop="realName"><el-input v-model="form.realName" /></el-form-item>
          <el-form-item label="邮箱" prop="email"><el-input v-model="form.email" /></el-form-item>
          <el-form-item label="手机" prop="phone"><el-input v-model="form.phone" /></el-form-item>
          <el-form-item
            :label="editingUser?.departmentId ? '主部门（可改选，暂不清空）' : '主部门（可未分配）'"
          >
            <el-tree-select
              v-if="canViewDepartments && !optionError"
              v-model="form.departmentId"
              :clearable="!editingUser?.departmentId"
              check-strictly
              :data="departments"
              :loading="optionLoading"
              node-key="id"
              :props="{ label: 'deptName', children: 'children' }"
              placeholder="未分配不获得部门默认授权"
            />
            <span v-else>
              {{
                editingUser ? departmentText(editingUser) : '未分配部门'
              }}；辅助目录不可用，保留原部门，可维护其他资料。
            </span>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="closeEditor">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="locked(editingId)" @click="saveUser">
          保存用户
        </el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="passwordVisible"
      :title="'本次临时密码 · 用户 #' + (passwordTarget?.id ?? '')"
      width="min(540px,94vw)"
      :close-on-click-modal="false"
    >
      <p>
        {{
          passwordTarget?.username
        }}；仅本次响应展示。关闭会清除页面内的临时密码，不自动复制；用户须登录后修改。
      </p>
      <code class="temporary-password">{{ tempPassword }}</code>
      <template #footer>
        <el-button @click="clearPassword">已记录并关闭</el-button>
        <el-button type="primary" :loading="passwordCopying" @click="copyPassword">复制临时密码</el-button>
      </template>
    </el-dialog>
  </main>
</template>
<style scoped>
.user-workspace {
  display: grid;
  gap: 20px;
}
.user-heading,
.actions,
.list-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.user-heading h3 {
  font-size: 18px;
  margin: 0;
}
.user-heading p,
.panel p {
  font-size: 12px;
  color: var(--do-muted);
  line-height: 1.8;
}
.user-heading > div:first-child {
  max-width: 700px;
}
.panel {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  min-width: 0;
}
.filters {
  display: grid;
  grid-template-columns: repeat(4, minmax(130px, 1fr)) auto auto;
  gap: 12px;
}
.list-heading {
  margin-bottom: 18px;
}
.panel small {
  display: block;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}
.el-pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18px;
}
.notice {
  padding: 12px;
  border-left: 3px solid var(--do-primary);
  background: var(--do-bg);
  line-height: 1.8;
  font-size: 13px;
}
.hidden-file-input {
  display: none;
}
.user-detail {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 14px;
  font-size: 13px;
  line-height: 1.7;
}
.user-detail dt {
  color: var(--do-muted);
}
.user-detail dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.temporary-password {
  display: block;
  font-size: 20px;
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  overflow-wrap: anywhere;
}
.import-report ul {
  font-size: 12px;
  line-height: 1.8;
}
@media (max-width: 1150px) {
  .filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .panel {
    padding: 14px;
  }
  .form-grid,
  .filters {
    grid-template-columns: minmax(0, 1fr);
  }
  .user-detail {
    grid-template-columns: 80px minmax(0, 1fr);
  }
}
</style>
