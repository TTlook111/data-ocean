<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshCw, ShieldCheck, UserPlus, Users } from 'lucide-vue-next'
import { useAuthStore } from '../../../stores/auth'
import {
  assignRoleToUser,
  listRoleUsers,
  listRoles,
  listUsers,
  removeRoleFromUser,
  type RoleItem,
  type UserItem,
} from '../../../api/admin/user'

const loading = ref(false)
const memberLoading = ref(false)
const addingMember = ref(false)
const removingUserId = ref<number>()
const errorMessage = ref('')
const roles = ref<RoleItem[]>([])
const activeRoleId = ref<number>()
const activeTab = ref('members')
const roleMembers = ref<UserItem[]>([])
const userOptions = ref<UserItem[]>([])
const selectedUserId = ref<number>()
const auth = useAuthStore()

const activeRole = computed(() => roles.value.find((role) => role.id === activeRoleId.value))
const canManageRoles = computed(() => auth.hasAnyPermission(['role:manage', 'user:manage']))
const assignedUserIds = computed(() => new Set(roleMembers.value.map((user) => user.id)))
const selectableUsers = computed(() => userOptions.value.filter((user) => !assignedUserIds.value.has(user.id) && user.status === 1))

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
    roles.value = result.data
    if (!activeRoleId.value && roles.value.length) {
      activeRoleId.value = roles.value[0].id
    }
    if (activeRoleId.value) {
      await fetchMembers(activeRoleId.value)
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
    roleMembers.value = result.data
  } catch (error) {
    roleMembers.value = []
    ElMessage.error(extractError(error, '角色成员加载失败'))
  } finally {
    memberLoading.value = false
  }
}

async function fetchUserOptions() {
  try {
    const result = await listUsers({ page: 1, pageSize: 100 })
    userOptions.value = result.data.records
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
  if (!value) return '—'
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
  await fetchMembers(roleId)
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
    confirmButtonText: '确认移除',
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
  await Promise.all([fetchRoles(), fetchUserOptions()])
})
</script>

<template>
  <main class="admin-page post-login-page">
    <header class="page-header">
      <div>
        <p>治理管理</p>
        <h1>角色管理</h1>
        <span class="header-subtitle">查看系统角色定义，并维护每个角色下的用户成员。</span>
      </div>
      <el-button :icon="RefreshCw" :loading="loading" @click="fetchRoles">刷新</el-button>
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
          </el-table>
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
                  :label="`${user.realName || user.username}（${user.username}）`"
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
  gap: 16px;
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

.header-subtitle {
  font-size: 13px;
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
.member-toolbar strong {
  display: block;
  font-size: 14px;
  color: var(--do-ink);
}

.role-chip small,
.member-toolbar small {
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
}

.role-tabs {
  padding: 0 16px 16px;
}

.member-toolbar {
  min-height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.member-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.muted-action {
  color: var(--do-muted);
}
</style>
