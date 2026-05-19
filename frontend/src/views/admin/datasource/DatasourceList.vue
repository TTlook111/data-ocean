<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  CircleCheck,
  CircleHelp,
  CircleX,
  KeyRound,
  PlugZap,
  RefreshCw,
  RotateCcw,
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

const STATUS_DISABLED = 0
const STATUS_ENABLED = 1
const HEALTH_HEALTHY = 'HEALTHY'
const HEALTH_UNHEALTHY = 'UNHEALTHY'
const HEALTH_UNKNOWN = 'UNKNOWN'

const formRef = ref<FormInstance>()
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const accessLoading = ref(false)
const usersLoading = ref(false)
const dialogVisible = ref(false)
const accessDialogVisible = ref(false)
const editingId = ref<number>()
const currentAccessDatasource = ref<DatasourceItem>()
const datasources = ref<DatasourceItem[]>([])
const users = ref<UserItem[]>([])
const accessList = ref<DatasourceAccessItem[]>([])
const selectedAccessUsers = ref<number[]>([])
const total = ref(0)
const activeStep = ref(0)
const errorMessage = ref('')
const testedOk = ref(false)
const connectionDirty = ref(false)
const accessCountMap = reactive<Record<number, number>>({})
const rowTesting = reactive<Record<number, boolean>>({})
const filtersReady = ref(false)
let filterTimer: ReturnType<typeof setTimeout> | undefined

const query = reactive<DatasourceQuery>({
  page: 1,
  pageSize: 20,
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

const originalConnection = reactive({
  host: '',
  port: 3306,
  databaseName: '',
  charset: 'utf8mb4',
  username: '',
})

const statusOptions = [
  { label: '启用', value: STATUS_ENABLED },
  { label: '禁用', value: STATUS_DISABLED },
]

const healthOptions = [
  { label: '未知', value: HEALTH_UNKNOWN },
  { label: '健康', value: HEALTH_HEALTHY },
  { label: '异常', value: HEALTH_UNHEALTHY },
]

const charsetOptions = ['utf8mb4', 'utf8', 'latin1', 'gbk']

const rules = computed<FormRules>(() => ({
  name: [
    { required: true, message: '请输入数据源名称', trigger: 'blur' },
    { min: 2, max: 100, message: '名称需为2-100位', trigger: 'blur' },
  ],
  description: [{ max: 500, message: '描述不能超过500字', trigger: 'blur' }],
  host: [
    { required: true, message: '请输入主机地址', trigger: 'blur' },
    {
      pattern:
        /^(localhost|((25[0-5]|2[0-4]\d|1?\d?\d)(\.|$)){4}|([A-Za-z0-9-]+\.)+[A-Za-z]{2,}|[A-Za-z0-9-]+)$/,
      message: '请输入合法 IP 或域名',
      trigger: 'blur',
    },
  ],
  port: [{ required: true, type: 'number', min: 1, max: 65535, message: '端口需在1-65535之间', trigger: 'blur' }],
  databaseName: [{ required: true, message: '请输入数据库名', trigger: 'blur' }],
  charset: [{ required: true, message: '请选择字符集', trigger: 'change' }],
  username: [{ required: true, message: '请输入只读账号', trigger: 'blur' }],
  password: editingId.value ? [] : [{ required: true, message: '请输入密码', trigger: 'blur' }],
}))

const grantableUsers = computed(() => {
  const grantedIds = new Set(accessList.value.map((item) => item.userId))
  return users.value.filter((user) => user.status === 1 && !grantedIds.has(user.id))
})

const isFiltered = computed(() => Boolean(query.name || query.status !== undefined || query.healthStatus))
const canSave = computed(() => testedOk.value && !connectionDirty.value)
const saveDisabledReason = computed(() => {
  if (!testedOk.value) return '请先测试连接成功后再保存'
  if (connectionDirty.value) return '连接配置已变更，请重新测试'
  return ''
})

watch(
  () => query.name,
  () => {
    if (!filtersReady.value) return
    window.clearTimeout(filterTimer)
    filterTimer = window.setTimeout(() => {
      query.page = 1
      fetchDatasources()
    }, 300)
  },
)

watch(
  () => [query.status, query.healthStatus],
  () => {
    if (!filtersReady.value) return
    query.page = 1
    fetchDatasources()
  },
)

watch(
  () => [form.host, form.port, form.databaseName, form.charset, form.username, form.password],
  () => {
    if (!dialogVisible.value) return
    const changed =
      form.host !== originalConnection.host ||
      form.port !== originalConnection.port ||
      form.databaseName !== originalConnection.databaseName ||
      form.charset !== originalConnection.charset ||
      form.username !== originalConnection.username ||
      Boolean(form.password)
    connectionDirty.value = changed
    if (changed) {
      testedOk.value = false
    }
  },
)

function resetForm() {
  editingId.value = undefined
  activeStep.value = 0
  testedOk.value = false
  connectionDirty.value = false
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
  Object.assign(originalConnection, {
    host: '',
    port: 3306,
    databaseName: '',
    charset: 'utf8mb4',
    username: '',
  })
  formRef.value?.clearValidate()
}

function healthType(status: string) {
  if (status === HEALTH_HEALTHY) return 'success'
  if (status === HEALTH_UNHEALTHY) return 'danger'
  return 'info'
}

function healthLabel(status: string) {
  return healthOptions.find((item) => item.value === status)?.label || '未知'
}

function healthIcon(status: string) {
  if (status === HEALTH_HEALTHY) return CircleCheck
  if (status === HEALTH_UNHEALTHY) return CircleX
  return CircleHelp
}

function statusLabel(status: number) {
  return status === STATUS_ENABLED ? '启用' : '禁用'
}

function fullTime(value?: string) {
  if (!value) return '未检测'
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
  if (!value) return '—'
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

async function fetchAccessCounts(rows: DatasourceItem[]) {
  await Promise.all(
    rows.map(async (row) => {
      try {
        const result = await listDatasourceAccess(row.id)
        accessCountMap[row.id] = result.data.length
      } catch {
        accessCountMap[row.id] = 0
      }
    }),
  )
}

async function fetchDatasources() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listDatasources(query)
    datasources.value = result.data.records
    total.value = result.data.total
    await fetchAccessCounts(result.data.records)
  } catch (error) {
    datasources.value = []
    total.value = 0
    errorMessage.value = extractError(error, '数据源加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function fetchUsers() {
  usersLoading.value = true
  try {
    const result = await listUsers({ page: 1, pageSize: 100 })
    users.value = result.data.records
  } catch (error) {
    ElMessage.error(extractError(error, '用户列表加载失败'))
  } finally {
    usersLoading.value = false
  }
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: DatasourceItem) {
  editingId.value = row.id
  activeStep.value = 0
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
  Object.assign(originalConnection, {
    host: row.host,
    port: row.port,
    databaseName: row.databaseName,
    charset: row.charset || 'utf8mb4',
    username: row.username || '',
  })
  testedOk.value = true
  connectionDirty.value = false
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function nextStep() {
  const valid = await formRef.value?.validateField(['name', 'description']).catch(() => false)
  if (valid === false) return
  activeStep.value = 1
}

function previousStep() {
  activeStep.value = 0
}

async function saveDatasource() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!canSave.value) {
    ElMessage.warning(saveDisabledReason.value)
    return
  }

  saving.value = true
  try {
    const payload = { ...form }
    if (editingId.value && !payload.password) {
      delete payload.password
    }
    if (editingId.value) {
      const result = await updateDatasource(editingId.value, payload)
      const index = datasources.value.findIndex((item) => item.id === editingId.value)
      if (index >= 0) {
        datasources.value[index] = result.data
      }
      ElMessage.success('数据源已更新')
    } else {
      await createDatasource(payload)
      query.page = 1
      ElMessage.success('数据源已创建')
      await fetchDatasources()
    }
    dialogVisible.value = false
  } catch (error) {
    ElMessage.error(extractError(error, editingId.value ? '数据源更新失败' : '数据源创建失败'))
  } finally {
    saving.value = false
  }
}

async function testFormConnection() {
  const valid = await formRef.value
    ?.validateField(['host', 'port', 'databaseName', 'charset', 'username', 'password'])
    .catch(() => false)
  if (valid === false) return

  if (editingId.value && !form.password) {
    ElMessage.warning('测试连接需填写密码；保存时可留空沿用原密码')
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
      password: form.password || '',
    })
    if (result.data.success) {
      testedOk.value = true
      connectionDirty.value = false
      Object.assign(originalConnection, {
        host: form.host,
        port: form.port,
        databaseName: form.databaseName,
        charset: form.charset,
        username: form.username,
      })
      if (editingId.value) {
        form.password = ''
      }
      ElMessage.success(`连接成功（耗时 ${result.data.responseTimeMs}ms）`)
    } else {
      testedOk.value = false
      ElMessage.error(`连接失败：${result.data.message}`)
    }
  } catch (error) {
    testedOk.value = false
    ElMessage.error(extractError(error, '连接测试失败，请检查配置'))
  } finally {
    testing.value = false
  }
}

async function testSaved(row: DatasourceItem) {
  rowTesting[row.id] = true
  try {
    const result = await testSavedDatasourceConnection(row.id)
    if (result.data.success) {
      row.healthStatus = HEALTH_HEALTHY
      row.lastCheckSuccess = true
      row.lastCheckTime = new Date().toISOString()
      ElMessage.success(`连接成功（耗时 ${result.data.responseTimeMs}ms）`)
    } else {
      row.lastCheckSuccess = false
      ElMessage.error(`连接失败：${result.data.message}`)
    }
  } catch (error) {
    ElMessage.error(extractError(error, '连接测试失败'))
  } finally {
    rowTesting[row.id] = false
  }
}

async function changeStatus(row: DatasourceItem, status: number) {
  if (status === STATUS_DISABLED) {
    const count = accessCountMap[row.id] || 0
    await ElMessageBox.confirm(`确定禁用数据源「${row.name}」？当前有 ${count} 个用户被授权，禁用后将无法查询。`, '禁用数据源', {
      type: 'warning',
      confirmButtonText: '确认禁用',
      cancelButtonText: '取消',
    })
  }

  try {
    const result = await updateDatasourceStatus(row.id, status)
    Object.assign(row, result.data)
    ElMessage.success(status === STATUS_ENABLED ? '数据源已启用' : '数据源已禁用')
  } catch (error) {
    ElMessage.error(extractError(error, '数据源状态更新失败'))
  }
}

async function removeDatasource(row: DatasourceItem) {
  if (row.status === STATUS_ENABLED) {
    ElMessage.warning('启用状态的数据源不可删除，请先禁用')
    return
  }

  await ElMessageBox.confirm(`确定删除数据源「${row.name}」？此操作不可恢复，所有授权关系将被清除。`, '删除数据源', {
    type: 'error',
    confirmButtonText: '确认删除',
    cancelButtonText: '取消',
    confirmButtonClass: 'el-button--danger',
  })

  try {
    await deleteDatasource(row.id)
    ElMessage.success('数据源已删除')
    if (datasources.value.length === 1 && (query.page || 1) > 1) {
      query.page = (query.page || 1) - 1
    }
    await fetchDatasources()
  } catch (error) {
    ElMessage.error(extractError(error, '数据源删除失败'))
  }
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
    accessCountMap[datasourceId] = result.data.length
  } catch (error) {
    ElMessage.error(extractError(error, '授权列表加载失败'))
  } finally {
    accessLoading.value = false
  }
}

async function grantAccess() {
  if (!currentAccessDatasource.value || !selectedAccessUsers.value.length) {
    ElMessage.warning('请选择授权用户')
    return
  }
  try {
    const datasourceId = currentAccessDatasource.value.id
    await grantDatasourceAccess(datasourceId, selectedAccessUsers.value)
    ElMessage.success('授权成功')
    selectedAccessUsers.value = []
    await fetchAccess(datasourceId)
  } catch (error) {
    ElMessage.error(extractError(error, '授权失败'))
  }
}

async function revokeAccess(row: DatasourceAccessItem) {
  if (!currentAccessDatasource.value) return
  await ElMessageBox.confirm(`确定取消用户「${row.realName || row.username}」的数据源授权吗？`, '取消授权', {
    type: 'warning',
    confirmButtonText: '取消授权',
    cancelButtonText: '返回',
  })

  try {
    await revokeDatasourceAccess(currentAccessDatasource.value.id, row.userId)
    ElMessage.success('授权已取消')
    await fetchAccess(currentAccessDatasource.value.id)
  } catch (error) {
    ElMessage.error(extractError(error, '取消授权失败'))
  }
}

function accessDepartmentText(row: DatasourceAccessItem) {
  return users.value.find((user) => user.id === row.userId)?.departmentName || '—'
}

function search() {
  query.page = 1
  fetchDatasources()
}

function resetFilters() {
  query.name = undefined
  query.status = undefined
  query.healthStatus = undefined
  query.page = 1
  fetchDatasources()
}

onMounted(async () => {
  await fetchDatasources()
  filtersReady.value = true
})
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
      <el-select v-model="query.status" clearable placeholder="全部状态">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="query.healthStatus" clearable placeholder="全部健康状态">
        <el-option v-for="item in healthOptions" :key="item.value" :label="item.label" :value="item.value" />
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
      <el-skeleton v-if="loading && !datasources.length" :rows="6" animated class="table-skeleton" />

      <el-result v-else-if="errorMessage" icon="error" title="数据源加载失败" :sub-title="errorMessage">
        <template #extra>
          <el-button type="primary" @click="fetchDatasources">重试</el-button>
        </template>
      </el-result>

      <el-empty
        v-else-if="!datasources.length"
        :description="isFiltered ? '未找到匹配的数据源' : '暂无数据源，点击右上角按钮添加'"
      >
        <el-button v-if="isFiltered" @click="resetFilters">重置筛选</el-button>
        <el-button v-else type="primary" @click="openCreate">新增数据源</el-button>
      </el-empty>

      <el-table v-else v-loading="loading" :data="datasources" border row-key="id" highlight-current-row>
        <el-table-column label="名称" min-width="170" fixed>
          <template #default="{ row }">
            <div class="name-cell">{{ row.name }}</div>
            <small class="ellipsis">{{ row.description || '暂无描述' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="连接信息" min-width="220">
          <template #default="{ row }">
            <div class="connection-text">{{ row.host }}:{{ row.port }} / {{ row.databaseName }}</div>
            <small>{{ row.username || '未设置账号' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag type="info">{{ row.dbType || 'MySQL' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === STATUS_ENABLED ? 'success' : 'info'">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="健康" width="110">
          <template #default="{ row }">
            <el-tag :type="healthType(row.healthStatus)">
              <component :is="healthIcon(row.healthStatus)" :size="14" />
              {{ healthLabel(row.healthStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上次检测" min-width="140">
          <template #default="{ row }">
            <el-tooltip :content="fullTime(row.lastCheckTime)" placement="top">
              <span>{{ relativeTime(row.lastCheckTime) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="授权用户数" width="110">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAccess(row)">{{ accessCountMap[row.id] ?? 0 }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="creatorName" label="创建者" min-width="100" />
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="success" :loading="rowTesting[row.id]" @click="testSaved(row)">
              <RefreshCw :size="14" />
              测试
            </el-button>
            <el-button v-if="row.status === STATUS_ENABLED" link type="warning" @click="changeStatus(row, STATUS_DISABLED)">
              禁用
            </el-button>
            <el-button v-if="row.status === STATUS_DISABLED" link type="success" @click="changeStatus(row, STATUS_ENABLED)">
              启用
            </el-button>
            <el-button link type="primary" @click="openAccess(row)">
              <KeyRound :size="14" />
              授权
            </el-button>
            <el-tooltip v-if="row.status === STATUS_ENABLED" content="启用状态的数据源不可删除，请先禁用" placement="top">
              <span>
                <el-button link type="danger" disabled>
                  <Trash2 :size="14" />
                  删除
                </el-button>
              </span>
            </el-tooltip>
            <el-button v-else link type="danger" @click="removeDatasource(row)">
              <Trash2 :size="14" />
              删除
            </el-button>
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
      @change="fetchDatasources"
    />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑数据源' : '新增数据源'" width="720px" @closed="resetForm">
      <el-steps :active="activeStep" finish-status="success" simple class="form-steps">
        <el-step title="基本信息" />
        <el-step title="连接配置" />
      </el-steps>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" :disabled="saving">
        <section v-show="activeStep === 0" class="step-panel">
          <el-form-item label="名称" prop="name" required>
            <el-input v-model="form.name" maxlength="100" show-word-limit />
          </el-form-item>
          <el-form-item label="描述" prop="description">
            <el-input v-model="form.description" type="textarea" :rows="4" maxlength="500" show-word-limit />
          </el-form-item>
        </section>

        <section v-show="activeStep === 1" class="step-panel">
          <el-form-item label="主机地址" prop="host" required>
            <el-input v-model="form.host" placeholder="例如 127.0.0.1 或 mysql.example.com" />
          </el-form-item>
          <el-form-item label="端口" prop="port" required>
            <el-input-number v-model="form.port" :min="1" :max="65535" />
          </el-form-item>
          <el-form-item label="数据库名" prop="databaseName" required>
            <el-input v-model="form.databaseName" />
          </el-form-item>
          <el-form-item label="字符集" prop="charset" required>
            <el-select v-model="form.charset">
              <el-option v-for="item in charsetOptions" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
          <el-form-item label="只读账号" prop="username" required>
            <el-input v-model="form.username" />
          </el-form-item>
          <el-form-item :label="editingId ? '新密码' : '密码'" prop="password" :required="!editingId">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              :placeholder="editingId ? '留空则不修改；修改连接配置需填写后测试' : '请输入密码'"
            />
          </el-form-item>
        </section>
      </el-form>

      <p v-if="editingId && connectionDirty" class="test-tip">连接配置已变更，建议重新测试连接后保存。</p>

      <template #footer>
        <el-button class="test-button" :loading="testing" @click="testFormConnection">测试连接</el-button>
        <el-button v-if="activeStep === 1" @click="previousStep">上一步</el-button>
        <el-button v-if="activeStep === 0" type="primary" @click="nextStep">下一步</el-button>
        <template v-else>
          <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
          <el-tooltip :disabled="canSave" :content="saveDisabledReason" placement="top">
            <span>
              <el-button type="primary" :loading="saving" :disabled="!canSave" @click="saveDatasource">保存</el-button>
            </span>
          </el-tooltip>
        </template>
      </template>
    </el-dialog>

    <el-dialog v-model="accessDialogVisible" :title="`数据源授权${currentAccessDatasource ? ` - ${currentAccessDatasource.name}` : ''}`" width="680px">
      <section class="grant-bar">
        <el-select v-model="selectedAccessUsers" multiple filterable :loading="usersLoading" placeholder="按用户名或姓名搜索用户">
          <el-option
            v-for="user in grantableUsers"
            :key="user.id"
            :label="`${user.realName || user.username}（${user.username}）`"
            :value="user.id"
          />
        </el-select>
        <el-button type="primary" @click="grantAccess">添加</el-button>
      </section>

      <el-table v-loading="accessLoading" :data="accessList" border>
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column prop="realName" label="姓名" min-width="120" />
        <el-table-column label="部门" min-width="120">
          <template #default="{ row }">{{ accessDepartmentText(row) }}</template>
        </el-table-column>
        <el-table-column label="授权时间" min-width="160">
          <template #default="{ row }">{{ fullTime(row.grantedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button link type="danger" @click="revokeAccess(row)">取消授权</el-button>
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
  grid-template-columns: minmax(180px, 1.5fr) minmax(130px, 1fr) minmax(150px, 1fr) auto auto;
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

.name-cell,
.connection-text {
  font-weight: 800;
}

.ellipsis {
  max-width: 150px;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.form-steps {
  margin-bottom: 18px;
}

.step-panel {
  min-height: 300px;
}

.test-tip {
  margin: 0 0 12px 100px;
  color: var(--do-warning);
  font-size: 13px;
}

.test-button {
  float: left;
}

.grant-bar {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  margin-bottom: 14px;
}
</style>
