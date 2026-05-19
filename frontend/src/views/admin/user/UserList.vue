<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { RotateCcw, Search } from 'lucide-vue-next'
import {
  createUser,
  deleteUser,
  listDepartments,
  listRoles,
  listUsers,
  resetUserPassword,
  updateUser,
  updateUserStatus,
  type DepartmentNode,
  type RoleItem,
  type UserItem,
  type UserPayload,
  type UserQuery,
} from '../../../api/admin/user'
import { useAuthStore } from '../../../stores/auth'

const STATUS_NORMAL = 1
const STATUS_DISABLED = 2
const STATUS_LOCKED = 3

const auth = useAuthStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const optionLoading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number>()
const users = ref<UserItem[]>([])
const roles = ref<RoleItem[]>([])
const departments = ref<DepartmentNode[]>([])
const total = ref(0)
const errorMessage = ref('')
const filtersReady = ref(false)
let filterTimer: ReturnType<typeof setTimeout> | undefined

const query = reactive<UserQuery>({
  page: 1,
  pageSize: 20,
})

const form = reactive<UserPayload>({
  username: '',
  password: '',
  realName: '',
  email: '',
  phone: '',
  departmentId: undefined,
  roleIds: [],
})

const statusOptions = [
  { label: '正常', value: STATUS_NORMAL },
  { label: '禁用', value: STATUS_DISABLED },
  { label: '锁定', value: STATUS_LOCKED },
]

const rules = computed<FormRules>(() => ({
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 50, message: '用户名需为2-50位', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]+$/, message: '用户名仅支持字母、数字和下划线', trigger: 'blur' },
  ],
  password: editingId.value
    ? []
    : [
        { required: true, message: '请输入密码', trigger: 'blur' },
        { min: 8, max: 32, message: '密码需为8-32位', trigger: 'blur' },
        { pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/, message: '密码至少包含字母和数字', trigger: 'blur' },
      ],
  realName: [
    { required: true, message: '请输入真实姓名', trigger: 'blur' },
    { min: 2, max: 20, message: '真实姓名需为2-20位', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  phone: [{ pattern: /^1\d{10}$/, message: '请输入11位手机号', trigger: 'blur' }],
  departmentId: [{ required: true, message: '请选择部门', trigger: 'change' }],
  roleIds: [{ required: true, type: 'array', min: 1, message: '至少选择一个角色', trigger: 'change' }],
}))

const currentUserId = computed(() => auth.currentUser?.id || auth.user?.userId)
const isFiltered = computed(() => Boolean(query.username || query.realName || query.departmentId || query.status))
const departmentPathMap = computed(() => {
  const map = new Map<number, string>()

  function walk(nodes: DepartmentNode[], parents: string[] = []) {
    nodes.forEach((node) => {
      const path = [...parents, node.deptName].join('/')
      map.set(node.id, path)
      if (node.children?.length) {
        walk(node.children, [...parents, node.deptName])
      }
    })
  }

  walk(departments.value)
  return map
})

watch(
  () => [query.username, query.realName],
  () => {
    if (!filtersReady.value) return
    window.clearTimeout(filterTimer)
    filterTimer = window.setTimeout(() => {
      query.page = 1
      fetchUsers()
    }, 300)
  },
)

watch(
  () => [query.departmentId, query.status],
  () => {
    if (!filtersReady.value) return
    query.page = 1
    fetchUsers()
  },
)

function resetForm() {
  editingId.value = undefined
  Object.assign(form, {
    username: '',
    password: '',
    realName: '',
    email: '',
    phone: '',
    departmentId: undefined,
    roleIds: [],
  })
  formRef.value?.clearValidate()
}

function statusLabel(status: number) {
  return statusOptions.find((item) => item.value === status)?.label || '未知'
}

function statusTagType(status: number) {
  if (status === STATUS_NORMAL) return 'success'
  if (status === STATUS_LOCKED) return 'danger'
  return 'info'
}

function departmentText(row: UserItem) {
  if (row.departmentId && departmentPathMap.value.has(row.departmentId)) {
    return departmentPathMap.value.get(row.departmentId)
  }
  return row.departmentName || '未分配'
}

function visibleRoleNames(row: UserItem) {
  return (row.roleNames || []).slice(0, 2)
}

function hiddenRoleNames(row: UserItem) {
  return (row.roleNames || []).slice(2)
}

function fullTime(value?: string) {
  if (!value) return '从未登录'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function relativeTime(value?: string) {
  if (!value) return '从未登录'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const diff = Date.now() - date.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}小时前`
  if (diff < 30 * 86_400_000) return `${Math.floor(diff / 86_400_000)}天前`
  return fullTime(value)
}

function extractError(error: unknown, fallback: string) {
  if (
    typeof error === 'object' &&
    error !== null &&
    'response' in error &&
    typeof (error as { response?: { data?: { message?: string } } }).response?.data?.message === 'string'
  ) {
    return (error as { response: { data: { message: string } } }).response.data.message
  }
  return fallback
}

async function fetchUsers() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listUsers(query)
    users.value = result.data.records
    total.value = result.data.total
  } catch (error) {
    users.value = []
    total.value = 0
    errorMessage.value = extractError(error, '用户数据加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function fetchOptions() {
  optionLoading.value = true
  try {
    const [roleResult, departmentResult] = await Promise.all([listRoles(), listDepartments()])
    roles.value = roleResult.data
    departments.value = departmentResult.data
  } catch (error) {
    ElMessage.error(extractError(error, '筛选选项加载失败'))
  } finally {
    optionLoading.value = false
  }
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(user: UserItem) {
  editingId.value = user.id
  Object.assign(form, {
    username: user.username,
    password: '',
    realName: user.realName,
    email: user.email || '',
    phone: user.phone || '',
    departmentId: user.departmentId,
    roleIds: user.roleIds || [],
  })
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function saveUser() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const payload = { ...form }
    if (editingId.value) {
      delete payload.username
      delete payload.password
      await updateUser(editingId.value, payload)
      const index = users.value.findIndex((item) => item.id === editingId.value)
      if (index >= 0) {
        users.value[index] = {
          ...users.value[index],
          realName: form.realName,
          email: form.email,
          phone: form.phone,
          departmentId: form.departmentId,
          departmentName: form.departmentId ? departmentPathMap.value.get(form.departmentId)?.split('/').at(-1) : undefined,
          roleIds: [...form.roleIds],
          roleNames: roles.value.filter((role) => form.roleIds.includes(role.id)).map((role) => role.roleName),
        }
      }
      ElMessage.success('用户更新成功')
    } else {
      await createUser(payload)
      query.page = 1
      ElMessage.success('用户创建成功')
      await fetchUsers()
    }
    dialogVisible.value = false
  } catch (error) {
    ElMessage.error(extractError(error, editingId.value ? '用户更新失败' : '用户创建失败'))
  } finally {
    saving.value = false
  }
}

async function changeStatus(user: UserItem, status: number) {
  if (status === STATUS_DISABLED) {
    await ElMessageBox.confirm(`确定禁用用户「${user.username}」吗？禁用后该用户将无法登录。`, '禁用用户', {
      type: 'warning',
      confirmButtonText: '确认禁用',
      cancelButtonText: '取消',
    })
  }

  if (status === STATUS_NORMAL && user.status === STATUS_LOCKED) {
    await ElMessageBox.confirm(`确定解锁用户「${user.username}」吗？`, '解锁用户', {
      type: 'warning',
      confirmButtonText: '确认解锁',
      cancelButtonText: '取消',
    })
  }

  try {
    await updateUserStatus(user.id, status)
    user.status = status
    ElMessage.success(status === STATUS_NORMAL ? '用户已启用' : '用户已禁用')
  } catch (error) {
    ElMessage.error(extractError(error, '用户状态更新失败'))
  }
}

async function removeUser(user: UserItem) {
  await ElMessageBox.confirm(`确定删除用户「${user.username}」？此操作不可恢复。`, '删除用户', {
    type: 'error',
    confirmButtonText: '确认删除',
    cancelButtonText: '取消',
    confirmButtonClass: 'el-button--danger',
  })

  try {
    await deleteUser(user.id)
    ElMessage.success('用户已删除')
    if (users.value.length === 1 && (query.page || 1) > 1) {
      query.page = (query.page || 1) - 1
    }
    await fetchUsers()
  } catch (error) {
    ElMessage.error(extractError(error, '用户删除失败'))
  }
}

async function copyPassword(password: string) {
  try {
    await navigator.clipboard.writeText(password)
    ElMessage.success('临时密码已复制')
  } catch {
    ElMessage.warning('复制失败，请手动记录临时密码')
  }
}

async function resetPassword(user: UserItem) {
  await ElMessageBox.confirm(`确定重置用户「${user.username}」的密码吗？重置后该用户需使用临时密码登录并修改密码。`, '重置密码', {
    type: 'warning',
    confirmButtonText: '确认重置',
    cancelButtonText: '取消',
  })

  try {
    const result = await resetUserPassword(user.id)
    const password = result.data.tempPassword
    await ElMessageBox.alert(
      `<div class="temp-password-dialog">
        <p>临时密码</p>
        <strong>${password}</strong>
      </div>`,
      '密码重置成功',
      {
        dangerouslyUseHTMLString: true,
        confirmButtonText: '复制临时密码',
        callback: () => copyPassword(password),
      },
    )
  } catch (error) {
    ElMessage.error(extractError(error, '密码重置失败'))
  }
}

function search() {
  query.page = 1
  fetchUsers()
}

function resetFilters() {
  query.username = undefined
  query.realName = undefined
  query.departmentId = undefined
  query.status = undefined
  query.page = 1
  fetchUsers()
}

function canDelete(row: UserItem) {
  return row.id !== currentUserId.value
}

onMounted(async () => {
  await fetchOptions()
  await fetchUsers()
  filtersReady.value = true
})
</script>

<template>
  <main class="admin-page post-login-page">
    <header class="page-header">
      <div>
        <p>用户管理</p>
        <h1>账号、角色与状态</h1>
        <span class="header-subtitle">集中维护平台用户、部门归属、角色授权和账号状态。</span>
      </div>
      <el-button type="primary" @click="openCreate">新增用户</el-button>
    </header>

    <section class="toolbar">
      <el-input v-model="query.username" clearable placeholder="用户名" />
      <el-input v-model="query.realName" clearable placeholder="真实姓名" />
      <el-tree-select
        v-model="query.departmentId"
        clearable
        check-strictly
        :data="departments"
        :loading="optionLoading"
        node-key="id"
        placeholder="部门"
        :props="{ label: 'deptName', children: 'children' }"
      />
      <el-select v-model="query.status" clearable placeholder="全部状态">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-button type="primary" @click="search">
        <Search :size="16" />
        查询
      </el-button>
      <el-button @click="resetFilters">
        <RotateCcw :size="16" />
        重置
      </el-button>
    </section>

    <section class="table-shell">
      <el-skeleton v-if="loading && !users.length" :rows="6" animated class="table-skeleton" />

      <el-result v-else-if="errorMessage" icon="error" title="用户数据加载失败" :sub-title="errorMessage">
        <template #extra>
          <el-button type="primary" @click="fetchUsers">重试</el-button>
        </template>
      </el-result>

      <el-empty
        v-else-if="!users.length"
        :description="isFiltered ? '未找到匹配的用户，试试调整筛选条件' : '暂无用户数据'"
      >
        <el-button v-if="isFiltered" @click="resetFilters">重置筛选</el-button>
      </el-empty>

      <el-table v-else v-loading="loading" :data="users" border row-key="id" highlight-current-row>
        <el-table-column prop="username" label="用户名" width="120" fixed />
        <el-table-column prop="realName" label="姓名" width="110" />
        <el-table-column label="部门" min-width="150">
          <template #default="{ row }">
            <span>{{ departmentText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" min-width="170">
          <template #default="{ row }">
            <el-tag v-for="role in visibleRoleNames(row)" :key="role" class="role-tag">{{ role }}</el-tag>
            <el-tooltip v-if="hiddenRoleNames(row).length" :content="hiddenRoleNames(row).join('、')" placement="top">
              <el-tag class="role-tag" type="info">+{{ hiddenRoleNames(row).length }}</el-tag>
            </el-tooltip>
            <span v-if="!row.roleNames?.length" class="muted-text">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后登录" min-width="160">
          <template #default="{ row }">
            <el-tooltip :content="fullTime(row.lastLoginAt)" placement="top">
              <span>{{ relativeTime(row.lastLoginAt) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.status === STATUS_NORMAL" link type="warning" @click="changeStatus(row, STATUS_DISABLED)">
              禁用
            </el-button>
            <el-button v-if="row.status === STATUS_DISABLED" link type="success" @click="changeStatus(row, STATUS_NORMAL)">
              启用
            </el-button>
            <el-button v-if="row.status === STATUS_LOCKED" link type="warning" @click="changeStatus(row, STATUS_NORMAL)">
              解锁
            </el-button>
            <el-button link type="primary" @click="resetPassword(row)">重置密码</el-button>
            <el-button v-if="canDelete(row)" link type="danger" @click="removeUser(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-pagination
      v-if="!errorMessage && total > 0"
      v-model:current-page="query.page"
      v-model:page-size="query.pageSize"
      class="pager"
      layout="total, sizes, prev, pager, next"
      :page-sizes="[10, 20, 50]"
      :total="total"
      @change="fetchUsers"
    />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑用户' : '新增用户'" width="620px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" :disabled="saving">
        <el-form-item label="用户名" prop="username" required>
          <el-input v-model="form.username" :disabled="Boolean(editingId)" placeholder="字母、数字或下划线" />
        </el-form-item>
        <el-form-item v-if="!editingId" label="密码" prop="password" required>
          <el-input v-model="form.password" type="password" show-password placeholder="8-32位，含字母和数字" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName" required>
          <el-input v-model="form.realName" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="手机" prop="phone">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="部门" prop="departmentId" required>
          <el-tree-select
            v-model="form.departmentId"
            clearable
            check-strictly
            :data="departments"
            node-key="id"
            :props="{ label: 'deptName', children: 'children' }"
          />
        </el-form-item>
        <el-form-item label="角色" prop="roleIds" required>
          <el-select v-model="form.roleIds" multiple placeholder="请选择角色">
            <el-option v-for="role in roles" :key="role.id" :label="role.roleName" :value="role.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-header p {
  margin: 0 0 6px;
  color: var(--do-primary);
  font-weight: 800;
}

.page-header h1 {
  margin: 0;
  font-size: 24px;
  color: var(--do-ink);
}

.toolbar {
  grid-template-columns: repeat(4, minmax(120px, 1fr)) auto auto;
}

.table-shell {
  min-height: 360px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.table-skeleton {
  padding: 18px;
}

.role-tag {
  margin-right: 6px;
}

.muted-text {
  color: var(--do-muted);
}

.pager {
  justify-content: flex-end;
  margin-top: 16px;
}

:global(.temp-password-dialog p) {
  margin: 0 0 8px;
  color: var(--do-muted);
}

:global(.temp-password-dialog strong) {
  display: inline-flex;
  min-width: 180px;
  padding: 10px 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  color: var(--do-ink);
  background: var(--do-primary-soft);
  font-size: 18px;
  letter-spacing: 1px;
}
</style>
