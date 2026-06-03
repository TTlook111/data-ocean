<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Edit3, KeyRound, Plus, RefreshCw, ShieldCheck, Trash2, UserPlus, Users } from 'lucide-vue-next'
import { useAuthStore } from '../../../stores/auth'
import {
  assignRoleToUser,
  createRole,
  deleteRole,
  listPermissionsTree,
  listRolePermissionIds,
  listRoleUsers,
  listRoles,
  listUsers,
  removeRoleFromUser,
  updateRole,
  updateRolePermissions,
  type PermissionGroup,
  type RoleItem,
  type RolePayload,
  type UserItem,
} from '../../../api/admin/user'

type PermissionTreeNode = {
  id: string | number
  label: string
  disabled?: boolean
  children?: PermissionTreeNode[]
}

const loading = ref(false)
const memberLoading = ref(false)
const permissionLoading = ref(false)
const permissionSaving = ref(false)
const roleSaving = ref(false)
const addingMember = ref(false)
const removingUserId = ref<number>()
const errorMessage = ref('')
const roles = ref<RoleItem[]>([])
const activeRoleId = ref<number>()
const activeTab = ref('definition')
const roleMembers = ref<UserItem[]>([])
const userOptions = ref<UserItem[]>([])
const selectedUserId = ref<number>()
const permissionGroups = ref<PermissionGroup[]>([])
const checkedPermissionIds = ref<number[]>([])
const roleDialogVisible = ref(false)
const editingRoleId = ref<number>()
const roleFormRef = ref<FormInstance>()
const auth = useAuthStore()

const roleForm = reactive<RolePayload>({
  roleCode: '',
  roleName: '',
  description: '',
  status: 1,
  permissionIds: [],
})

const roleRules: FormRules = {
  roleCode: [
    { required: true, message: '请输入角色编码', trigger: 'blur' },
    { pattern: /^[A-Z0-9_:.-]+$/, message: '仅支持大写字母、数字、下划线、冒号、点和横线', trigger: 'blur' },
  ],
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
}

const activeRole = computed(() => roles.value.find((role) => role.id === activeRoleId.value))
const canManageRoles = computed(() => auth.hasAnyPermission(['role:manage', 'user:manage', '*']))
const assignedUserIds = computed(() => new Set(roleMembers.value.map((user) => user.id)))
const selectableUsers = computed(() => userOptions.value.filter((user) => !assignedUserIds.value.has(user.id) && user.status === 1))
const permissionTreeData = computed<PermissionTreeNode[]>(() =>
  permissionGroups.value.map((group) => ({
    id: `module:${group.module}`,
    label: group.moduleName || group.module,
    disabled: true,
    children: group.permissions.map((item) => ({
      id: item.id,
      label: `${item.permissionName} (${item.permissionCode})`,
    })),
  })),
)

function extractError(error: unknown, fallback: string) {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const msg = (error as { response?: { data?: { message?: string } } }).response?.data?.message
    if (typeof msg === 'string') return msg
  }
  return fallback
}

async function fetchRoles() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listRoles()
    roles.value = result.data || []
    if (!activeRoleId.value && roles.value.length) {
      activeRoleId.value = roles.value[0].id
    }
    if (activeRoleId.value && !roles.value.some((role) => role.id === activeRoleId.value)) {
      activeRoleId.value = roles.value[0]?.id
    }
    if (activeRoleId.value) {
      await Promise.all([fetchMembers(activeRoleId.value), fetchRolePermissions(activeRoleId.value)])
    }
  } catch (error) {
    roles.value = []
    errorMessage.value = extractError(error, '角色数据加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function fetchMembers(roleId: number) {
  memberLoading.value = true
  selectedUserId.value = undefined
  try {
    const result = await listRoleUsers(roleId)
    roleMembers.value = result.data || []
  } catch (error) {
    roleMembers.value = []
    ElMessage.error(extractError(error, '角色成员加载失败'))
  } finally {
    memberLoading.value = false
  }
}

async function fetchPermissions() {
  try {
    const result = await listPermissionsTree()
    permissionGroups.value = result.data || []
  } catch (error) {
    permissionGroups.value = []
    ElMessage.error(extractError(error, '权限树加载失败'))
  }
}

async function fetchRolePermissions(roleId: number) {
  permissionLoading.value = true
  try {
    const result = await listRolePermissionIds(roleId)
    checkedPermissionIds.value = result.data || []
  } catch (error) {
    checkedPermissionIds.value = []
    ElMessage.error(extractError(error, '角色权限加载失败'))
  } finally {
    permissionLoading.value = false
  }
}

async function fetchUserOptions() {
  try {
    const result = await listUsers({ page: 1, pageSize: 100 })
    userOptions.value = result.data.records || []
  } catch (error) {
    ElMessage.error(extractError(error, '用户选项加载失败'))
  }
}

function statusType(status: number) {
  return status === 1 ? 'success' : 'info'
}

function statusLabel(status: number) {
  return status === 1 ? '启用' : '禁用'
}

function formatTime(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date)
}

async function selectRole(roleId: number) {
  activeRoleId.value = roleId
  await Promise.all([fetchMembers(roleId), fetchRolePermissions(roleId)])
}

function resetRoleForm() {
  editingRoleId.value = undefined
  Object.assign(roleForm, {
    roleCode: '',
    roleName: '',
    description: '',
    status: 1,
    permissionIds: [],
  })
  roleFormRef.value?.clearValidate()
}

function openCreateRole() {
  resetRoleForm()
  roleDialogVisible.value = true
}

async function openEditRole(role: RoleItem) {
  editingRoleId.value = role.id
  Object.assign(roleForm, {
    roleCode: role.roleCode,
    roleName: role.roleName,
    description: role.description || '',
    status: role.status,
    permissionIds: [],
  })
  roleDialogVisible.value = true
  await nextTick()
  roleFormRef.value?.clearValidate()
}

async function saveRole() {
  const valid = await roleFormRef.value?.validate().catch(() => false)
  if (!valid) return
  roleSaving.value = true
  try {
    if (editingRoleId.value) {
      await updateRole(editingRoleId.value, roleForm)
      ElMessage.success('角色已更新')
    } else {
      await createRole(roleForm)
      ElMessage.success('角色已创建')
    }
    roleDialogVisible.value = false
    await fetchRoles()
  } catch (error) {
    ElMessage.error(extractError(error, editingRoleId.value ? '角色更新失败' : '角色创建失败'))
  } finally {
    roleSaving.value = false
  }
}

async function removeRole(role: RoleItem) {
  await ElMessageBox.confirm(`确定删除角色「${role.roleName}」吗？`, '删除角色', {
    type: 'warning',
    confirmButtonText: '确定删除',
    cancelButtonText: '取消',
  })
  try {
    await deleteRole(role.id)
    if (activeRoleId.value === role.id) activeRoleId.value = undefined
    ElMessage.success('角色已删除')
    await fetchRoles()
  } catch (error) {
    ElMessage.error(extractError(error, '角色删除失败'))
  }
}

function onPermissionCheck(_node: PermissionTreeNode, state: { checkedKeys: Array<string | number> }) {
  checkedPermissionIds.value = state.checkedKeys.filter((key): key is number => typeof key === 'number')
}

async function savePermissions() {
  if (!activeRoleId.value || !canManageRoles.value) return
  permissionSaving.value = true
  try {
    await updateRolePermissions(activeRoleId.value, checkedPermissionIds.value)
    ElMessage.success('角色权限已保存')
  } catch (error) {
    ElMessage.error(extractError(error, '角色权限保存失败'))
  } finally {
    permissionSaving.value = false
  }
}

async function addMember() {
  if (!activeRoleId.value || !selectedUserId.value || !canManageRoles.value) return
  addingMember.value = true
  try {
    await assignRoleToUser(activeRoleId.value, selectedUserId.value)
    ElMessage.success('成员添加成功')
    await fetchMembers(activeRoleId.value)
  } catch (error) {
    ElMessage.error(extractError(error, '成员添加失败'))
  } finally {
    addingMember.value = false
  }
}

async function removeMember(user: UserItem) {
  if (!activeRoleId.value || !canManageRoles.value) return
  await ElMessageBox.confirm(`确定将 ${user.realName || user.username} 从该角色移除吗？`, '移除成员', {
    type: 'warning',
    confirmButtonText: '确定移除',
    cancelButtonText: '取消',
  })
  removingUserId.value = user.id
  try {
    await removeRoleFromUser(activeRoleId.value, user.id)
    ElMessage.success('成员已移除')
    await fetchMembers(activeRoleId.value)
  } catch (error) {
    ElMessage.error(extractError(error, '成员移除失败'))
  } finally {
    removingUserId.value = undefined
  }
}

onMounted(async () => {
  await Promise.all([fetchRoles(), fetchUserOptions(), fetchPermissions()])
})
</script>

<template>
  <main class="admin-page post-login-page">
    <header class="page-header">
      <div>
        <p>治理管理</p>
        <h1>角色管理</h1>
        <span class="header-subtitle">维护角色定义、功能权限和角色下的用户成员。</span>
      </div>
      <div class="header-actions">
        <el-button :icon="RefreshCw" :loading="loading" @click="fetchRoles">刷新</el-button>
        <el-button v-if="canManageRoles" type="primary" :icon="Plus" @click="openCreateRole">新增角色</el-button>
      </div>
    </header>

    <section class="role-summary">
      <button
        v-for="role in roles"
        :key="role.id"
        type="button"
        class="role-chip"
        :class="{ active: role.id === activeRoleId }"
        @click="selectRole(role.id)"
      >
        <span class="role-chip-icon">
          <ShieldCheck :size="16" />
        </span>
        <div>
          <strong>{{ role.roleName }}</strong>
          <small>{{ role.description || role.roleCode }}</small>
        </div>
      </button>
    </section>

    <section class="table-shell">
      <el-skeleton v-if="loading && !roles.length" :rows="4" animated style="padding:18px" />

      <el-result v-else-if="errorMessage" icon="error" title="角色数据加载失败" :sub-title="errorMessage">
        <template #extra>
          <el-button type="primary" @click="fetchRoles">重试</el-button>
        </template>
      </el-result>

      <el-empty v-else-if="!roles.length" description="暂无角色数据" />

      <el-tabs v-else v-model="activeTab" class="role-tabs">
        <el-tab-pane label="角色定义" name="definition">
          <el-table :data="roles" border row-key="id" highlight-current-row>
            <el-table-column prop="roleName" label="角色名称" min-width="140" fixed>
              <template #default="{ row }">
                <strong>{{ row.roleName }}</strong>
              </template>
            </el-table-column>
            <el-table-column prop="roleCode" label="角色编码" min-width="160" />
            <el-table-column prop="description" label="描述" min-width="240" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" min-width="130">
              <template #default="{ row }">
                {{ formatTime(row.createdAt) }}
              </template>
            </el-table-column>
            <el-table-column v-if="canManageRoles" label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEditRole(row)">
                  <Edit3 :size="14" />
                  编辑
                </el-button>
                <el-button link type="danger" @click="removeRole(row)">
                  <Trash2 :size="14" />
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="权限配置" name="permissions">
          <div class="permission-toolbar">
            <div>
              <strong>{{ activeRole?.roleName || '请选择角色' }}</strong>
              <small><KeyRound :size="14" /> 已选择 {{ checkedPermissionIds.length }} 项权限</small>
            </div>
            <el-button
              v-if="canManageRoles"
              type="primary"
              :disabled="!activeRoleId"
              :loading="permissionSaving"
              @click="savePermissions"
            >
              保存权限
            </el-button>
          </div>
          <el-tree
            v-loading="permissionLoading"
            :data="permissionTreeData"
            node-key="id"
            show-checkbox
            default-expand-all
            :check-strictly="false"
            :default-checked-keys="checkedPermissionIds"
            :props="{ label: 'label', children: 'children', disabled: 'disabled' }"
            @check="onPermissionCheck"
          />
        </el-tab-pane>

        <el-tab-pane label="成员管理" name="members">
          <div class="member-toolbar">
            <div>
              <strong>{{ activeRole?.roleName || '请选择角色' }}</strong>
              <small><Users :size="14" /> {{ roleMembers.length }} 名成员</small>
            </div>
            <div v-if="canManageRoles" class="member-actions">
              <el-select v-model="selectedUserId" filterable clearable placeholder="选择要加入的用户" style="width: 240px">
                <el-option
                  v-for="user in selectableUsers"
                  :key="user.id"
                  :label="`${user.realName || user.username} (${user.username})`"
                  :value="user.id"
                />
              </el-select>
              <el-button type="primary" :icon="UserPlus" :disabled="!selectedUserId" :loading="addingMember" @click="addMember">
                添加成员
              </el-button>
            </div>
            <small v-else>仅可查看成员，无角色管理权限</small>
          </div>

          <el-table v-loading="memberLoading" :data="roleMembers" border row-key="id" empty-text="该角色暂无成员">
            <el-table-column prop="username" label="用户名" min-width="130" />
            <el-table-column prop="realName" label="姓名" min-width="120" />
            <el-table-column prop="email" label="邮箱" min-width="180" />
            <el-table-column label="账号状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '正常' : '非正常' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canManageRoles" link type="danger" :loading="removingUserId === row.id" @click="removeMember(row)">移除</el-button>
                <span v-else class="muted-action">-</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog v-model="roleDialogVisible" :title="editingRoleId ? '编辑角色' : '新增角色'" width="560px" @closed="resetRoleForm">
      <el-form ref="roleFormRef" :model="roleForm" :rules="roleRules" label-width="90px" :disabled="roleSaving">
        <el-form-item label="角色编码" prop="roleCode" required>
          <el-input v-model="roleForm.roleCode" :disabled="Boolean(editingRoleId)" placeholder="例如 DATA_ANALYST" />
        </el-form-item>
        <el-form-item label="角色名称" prop="roleName" required>
          <el-input v-model="roleForm.roleName" placeholder="例如 数据分析师" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="roleForm.status" :active-value="1" :inactive-value="2" active-text="启用" inactive-text="禁用" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="roleForm.description" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="roleSaving" @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="roleSaving" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}

.page-header,
.header-actions,
.member-toolbar,
.permission-toolbar,
.member-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-header,
.member-toolbar,
.permission-toolbar {
  justify-content: space-between;
}

.page-header {
  margin-bottom: 4px;
}

.page-header p {
  margin: 0 0 6px;
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 900;
}

.page-header h1 {
  margin: 0;
  font-size: 24px;
  color: var(--do-ink);
}

.header-subtitle,
.muted-action {
  color: var(--do-muted);
}

.role-summary {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}

.role-chip {
  display: grid;
  grid-template-columns: 36px 1fr;
  align-items: center;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
  text-align: left;
  cursor: pointer;
}

.role-chip.active {
  border-color: var(--do-primary);
  box-shadow: 0 0 0 3px rgba(77, 143, 220, 0.12);
}

.role-chip-icon {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: var(--do-tone-purple);
  background: var(--do-tone-purple-bg);
}

.role-chip strong,
.member-toolbar strong,
.permission-toolbar strong {
  display: block;
  font-size: 14px;
  color: var(--do-ink);
}

.role-chip small,
.member-toolbar small,
.permission-toolbar small {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--do-muted);
  font-size: 12px;
}

.table-shell {
  min-height: 280px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
  overflow: hidden;
  width: 100%;
}

.role-tabs {
  padding: 0 16px 16px;
}

.member-toolbar,
.permission-toolbar {
  min-height: 56px;
}
</style>
