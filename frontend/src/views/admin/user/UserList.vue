<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createUser,
  deleteUser,
  listDepartments,
  listRoles,
  listUsers,
  updateUser,
  updateUserStatus,
  type DepartmentNode,
  type RoleItem,
  type UserItem,
  type UserPayload,
  type UserQuery,
} from '../../../api/admin/user'

const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number>()
const users = ref<UserItem[]>([])
const roles = ref<RoleItem[]>([])
const departments = ref<DepartmentNode[]>([])
const total = ref(0)
const query = reactive<UserQuery>({
  page: 1,
  pageSize: 10,
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
  { label: '正常', value: 1 },
  { label: '禁用', value: 2 },
  { label: '锁定', value: 3 },
]

function statusLabel(status: number) {
  return statusOptions.find((item) => item.value === status)?.label || '未知'
}

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
}

async function fetchUsers() {
  loading.value = true
  try {
    const result = await listUsers(query)
    users.value = result.data.records
    total.value = result.data.total
  } finally {
    loading.value = false
  }
}

async function fetchOptions() {
  const [roleResult, departmentResult] = await Promise.all([listRoles(), listDepartments()])
  roles.value = roleResult.data
  departments.value = departmentResult.data
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
}

async function saveUser() {
  saving.value = true
  try {
    const payload = { ...form }
    if (editingId.value) {
      delete payload.username
      delete payload.password
      await updateUser(editingId.value, payload)
      ElMessage.success('用户更新成功')
    } else {
      await createUser(payload)
      ElMessage.success('用户创建成功')
    }
    dialogVisible.value = false
    await fetchUsers()
  } finally {
    saving.value = false
  }
}

async function changeStatus(user: UserItem, status: number) {
  await updateUserStatus(user.id, status)
  ElMessage.success('用户状态已更新')
  await fetchUsers()
}

async function removeUser(user: UserItem) {
  await ElMessageBox.confirm(`确定删除用户「${user.username}」吗？`, '删除用户', {
    type: 'warning',
  })
  await deleteUser(user.id)
  ElMessage.success('用户已删除')
  await fetchUsers()
}

function search() {
  query.page = 1
  fetchUsers()
}

onMounted(async () => {
  await fetchOptions()
  await fetchUsers()
})
</script>

<template>
  <main class="admin-page">
    <header class="page-header">
      <div>
        <p>用户管理</p>
        <h1>账号、角色与状态</h1>
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
        node-key="id"
        placeholder="部门"
        :props="{ label: 'deptName', children: 'children' }"
      />
      <el-select v-model="query.status" clearable placeholder="状态">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
    </section>

    <el-table v-loading="loading" :data="users" border>
      <el-table-column prop="username" label="用户名" min-width="120" />
      <el-table-column prop="realName" label="真实姓名" min-width="120" />
      <el-table-column prop="departmentName" label="部门" min-width="130" />
      <el-table-column label="角色" min-width="180">
        <template #default="{ row }">
          <el-tag v-for="role in row.roleNames" :key="role" class="role-tag">{{ role }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'warning'">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastLoginAt" label="最后登录" min-width="180" />
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" @click="changeStatus(row, row.status === 1 ? 2 : 1)">
            {{ row.status === 1 ? '禁用' : '启用' }}
          </el-button>
          <el-button v-if="row.status === 3" link type="warning" @click="changeStatus(row, 1)">解锁</el-button>
          <el-button link type="danger" @click="removeUser(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.pageSize"
      class="pager"
      layout="total, sizes, prev, pager, next"
      :total="total"
      @change="fetchUsers"
    />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑用户' : '新增用户'" width="560px">
      <el-form label-width="90px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="Boolean(editingId)" />
        </el-form-item>
        <el-form-item v-if="!editingId" label="密码" required>
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="真实姓名" required>
          <el-input v-model="form.realName" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="部门">
          <el-tree-select
            v-model="form.departmentId"
            clearable
            check-strictly
            :data="departments"
            node-key="id"
            :props="{ label: 'deptName', children: 'children' }"
          />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.roleIds" multiple>
            <el-option v-for="role in roles" :key="role.id" :label="role.roleName" :value="role.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.admin-page {
  min-height: 100vh;
  padding: 28px;
  background: #f6f7fb;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.page-header p {
  margin: 0 0 6px;
  color: #6f35f2;
  font-weight: 800;
}

.page-header h1 {
  margin: 0;
  font-size: 28px;
}

.toolbar {
  display: grid;
  grid-template-columns: repeat(5, minmax(120px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.role-tag {
  margin-right: 6px;
}

.pager {
  justify-content: flex-end;
  margin-top: 16px;
}

@media (max-width: 900px) {
  .toolbar {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
