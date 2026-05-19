<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CircleCheck,
  CircleHelp,
  CircleX,
  KeyRound,
  PlugZap,
  RefreshCw,
  Search,
  Trash2,
} from 'lucide-vue-next'
import {
  createDatasource,
  deleteDatasource,
  grantDatasourceAccess,
  listDatasourceAccess,
  listDatasources,
  revokeDatasourceAccess,
  testDatasourceConnection,
  testSavedDatasourceConnection,
  updateDatasource,
  updateDatasourceStatus,
  type DatasourceAccessItem,
  type DatasourceItem,
  type DatasourcePayload,
  type DatasourceQuery,
} from '../../../api/admin/datasource'
import { listUsers, type UserItem } from '../../../api/admin/user'

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const accessLoading = ref(false)
const dialogVisible = ref(false)
const accessDialogVisible = ref(false)
const editingId = ref<number>()
const currentAccessDatasource = ref<DatasourceItem>()
const datasources = ref<DatasourceItem[]>([])
const users = ref<UserItem[]>([])
const accessList = ref<DatasourceAccessItem[]>([])
const selectedAccessUsers = ref<number[]>([])
const total = ref(0)
const query = reactive<DatasourceQuery>({
  page: 1,
  pageSize: 10,
})
const form = reactive<DatasourcePayload>({
  name: '',
  description: '',
  host: '',
  port: 3306,
  databaseName: '',
  charset: 'utf8mb4',
  username: '',
  password: '',
})

const statusOptions = [
  { label: '启用', value: 1 },
  { label: '禁用', value: 0 },
]

const healthOptions = [
  { label: '未知', value: 'UNKNOWN' },
  { label: '健康', value: 'HEALTHY' },
  { label: '异常', value: 'UNHEALTHY' },
]

const grantableUsers = computed(() => {
  const grantedIds = new Set(accessList.value.map((item) => item.userId))
  return users.value.filter((user) => !grantedIds.has(user.id))
})

function resetForm() {
  editingId.value = undefined
  Object.assign(form, {
    name: '',
    description: '',
    host: '',
    port: 3306,
    databaseName: '',
    charset: 'utf8mb4',
    username: '',
    password: '',
  })
}

function healthType(status: string) {
  if (status === 'HEALTHY') return 'success'
  if (status === 'UNHEALTHY') return 'danger'
  return 'info'
}

function healthLabel(status: string) {
  return healthOptions.find((item) => item.value === status)?.label || '未知'
}

function healthIcon(status: string) {
  if (status === 'HEALTHY') return CircleCheck
  if (status === 'UNHEALTHY') return CircleX
  return CircleHelp
}

async function fetchDatasources() {
  loading.value = true
  try {
    const result = await listDatasources(query)
    datasources.value = result.data.records
    total.value = result.data.total
  } finally {
    loading.value = false
  }
}

async function fetchUsers() {
  const result = await listUsers({ page: 1, pageSize: 100, status: 1 })
  users.value = result.data.records
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: DatasourceItem) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    description: row.description || '',
    host: row.host,
    port: row.port,
    databaseName: row.databaseName,
    charset: row.charset || 'utf8mb4',
    username: row.username || '',
    password: '',
  })
  dialogVisible.value = true
}

async function saveDatasource() {
  saving.value = true
  try {
    const payload = { ...form }
    if (editingId.value && !payload.password) {
      delete payload.password
    }
    if (editingId.value) {
      await updateDatasource(editingId.value, payload)
      ElMessage.success('数据源已更新')
    } else {
      await createDatasource(payload)
      ElMessage.success('数据源已创建')
    }
    dialogVisible.value = false
    await fetchDatasources()
  } finally {
    saving.value = false
  }
}

async function testFormConnection() {
  if (!form.password) {
    ElMessage.warning(editingId.value ? '测试新连接需填写密码；保存时可留空沿用原密码' : '请先填写密码')
    return
  }
  testing.value = true
  try {
    const result = await testDatasourceConnection({
      host: form.host,
      port: form.port,
      databaseName: form.databaseName,
      charset: form.charset,
      username: form.username,
      password: form.password,
    })
    if (result.data.success) {
      ElMessage.success(`连接成功，耗时 ${result.data.responseTimeMs}ms`)
    } else {
      ElMessage.error(result.data.message)
    }
  } finally {
    testing.value = false
  }
}

async function testSaved(row: DatasourceItem) {
  const result = await testSavedDatasourceConnection(row.id)
  if (result.data.success) {
    ElMessage.success(`连接成功，耗时 ${result.data.responseTimeMs}ms`)
  } else {
    ElMessage.error(result.data.message)
  }
  await fetchDatasources()
}

async function changeStatus(row: DatasourceItem, status: number) {
  await updateDatasourceStatus(row.id, status)
  ElMessage.success(status === 1 ? '数据源已启用' : '数据源已禁用')
  await fetchDatasources()
}

async function removeDatasource(row: DatasourceItem) {
  await ElMessageBox.confirm(`确定删除数据源「${row.name}」吗？`, '删除数据源', { type: 'warning' })
  await deleteDatasource(row.id)
  ElMessage.success('数据源已删除')
  await fetchDatasources()
}

async function openAccess(row: DatasourceItem) {
  currentAccessDatasource.value = row
  selectedAccessUsers.value = []
  accessDialogVisible.value = true
  await fetchAccess(row.id)
  if (!users.value.length) {
    await fetchUsers()
  }
}

async function fetchAccess(datasourceId: number) {
  accessLoading.value = true
  try {
    const result = await listDatasourceAccess(datasourceId)
    accessList.value = result.data
  } finally {
    accessLoading.value = false
  }
}

async function grantAccess() {
  if (!currentAccessDatasource.value || !selectedAccessUsers.value.length) {
    ElMessage.warning('请选择授权用户')
    return
  }
  await grantDatasourceAccess(currentAccessDatasource.value.id, selectedAccessUsers.value)
  ElMessage.success('授权成功')
  selectedAccessUsers.value = []
  await fetchAccess(currentAccessDatasource.value.id)
}

async function revokeAccess(row: DatasourceAccessItem) {
  if (!currentAccessDatasource.value) return
  await revokeDatasourceAccess(currentAccessDatasource.value.id, row.userId)
  ElMessage.success('授权已撤销')
  await fetchAccess(currentAccessDatasource.value.id)
}

function search() {
  query.page = 1
  fetchDatasources()
}

onMounted(fetchDatasources)
</script>

<template>
  <main class="admin-page post-login-page">
    <header class="page-header">
      <div>
        <p>数据源管理</p>
        <h1>业务库接入与授权</h1>
        <span class="header-subtitle">管理可查询业务库的连接配置、健康状态和用户授权范围。</span>
      </div>
      <el-button type="primary" @click="openCreate">
        <PlugZap :size="16" />
        新增数据源
      </el-button>
    </header>

    <section class="toolbar">
      <el-input v-model="query.name" clearable placeholder="数据源名称" />
      <el-select v-model="query.status" clearable placeholder="启用状态">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="query.healthStatus" clearable placeholder="健康状态">
        <el-option v-for="item in healthOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-button type="primary" @click="search">
        <Search :size="16" />
        查询
      </el-button>
    </section>

    <el-table v-loading="loading" :data="datasources" border row-key="id">
      <el-table-column prop="name" label="名称" min-width="150" />
      <el-table-column label="连接信息" min-width="230">
        <template #default="{ row }">
          <div class="connection-text">{{ row.host }}:{{ row.port }}/{{ row.databaseName }}</div>
          <small>{{ row.username || '未设置账号' }}</small>
        </template>
      </el-table-column>
      <el-table-column prop="dbType" label="类型" width="90" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="健康" width="120">
        <template #default="{ row }">
          <el-tag :type="healthType(row.healthStatus)">
            <component :is="healthIcon(row.healthStatus)" :size="14" />
            {{ healthLabel(row.healthStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastCheckTime" label="最后检测" min-width="180" />
      <el-table-column prop="creatorName" label="创建人" min-width="110" />
      <el-table-column label="操作" width="330">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="success" @click="testSaved(row)">
            <RefreshCw :size="14" />
            测试
          </el-button>
          <el-button link type="warning" @click="changeStatus(row, row.status === 1 ? 0 : 1)">
            {{ row.status === 1 ? '禁用' : '启用' }}
          </el-button>
          <el-button link type="primary" @click="openAccess(row)">
            <KeyRound :size="14" />
            授权
          </el-button>
          <el-button link type="danger" @click="removeDatasource(row)">
            <Trash2 :size="14" />
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.pageSize"
      class="pager"
      layout="total, sizes, prev, pager, next"
      :total="total"
      @change="fetchDatasources"
    />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑数据源' : '新增数据源'" width="640px">
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="主机" required>
          <el-input v-model="form.host" />
        </el-form-item>
        <el-form-item label="端口" required>
          <el-input-number v-model="form.port" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item label="数据库" required>
          <el-input v-model="form.databaseName" />
        </el-form-item>
        <el-form-item label="字符集" required>
          <el-input v-model="form.charset" />
        </el-form-item>
        <el-form-item label="只读账号" required>
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item :label="editingId ? '新密码' : '密码'" :required="!editingId">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :loading="testing" @click="testFormConnection">测试连接</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveDatasource">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="accessDialogVisible" title="数据源授权" width="620px">
      <section class="grant-bar">
        <el-select v-model="selectedAccessUsers" multiple filterable placeholder="选择用户">
          <el-option
            v-for="user in grantableUsers"
            :key="user.id"
            :label="`${user.realName || user.username}（${user.username}）`"
            :value="user.id"
          />
        </el-select>
        <el-button type="primary" @click="grantAccess">授权</el-button>
      </section>

      <el-table v-loading="accessLoading" :data="accessList" border>
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column prop="realName" label="姓名" min-width="120" />
        <el-table-column prop="grantedAt" label="授权时间" min-width="170" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" @click="revokeAccess(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
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
  margin-bottom: 18px;
}

.page-header p {
  margin: 0 0 6px;
  color: var(--do-primary);
  font-weight: 800;
}

.page-header h1 {
  margin: 0;
  color: var(--do-ink);
  font-size: 24px;
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(180px, 1.5fr) minmax(120px, 1fr) minmax(120px, 1fr) auto;
  gap: 12px;
  padding: 14px;
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.connection-text {
  font-weight: 700;
}

small {
  color: var(--do-muted);
}

.el-tag {
  display: inline-flex;
  gap: 5px;
  align-items: center;
}

.pager {
  justify-content: flex-end;
  margin-top: 16px;
}

.grant-bar {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  margin-bottom: 14px;
}

</style>
