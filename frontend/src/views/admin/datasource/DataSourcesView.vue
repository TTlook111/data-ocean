<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Database, ExternalLink, Pencil, PlugZap, RefreshCw, Trash2 } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import {
  createDatasource,
  deleteDatasource,
  getDatasourceReadiness,
  listDatasources,
  testDatasourceConnection,
  updateDatasource,
  updateDatasourceStatus,
  type DatasourceItem,
  type DatasourcePayload,
  type DatasourceQuery,
  type DatasourceReadiness,
} from '../../../api/admin/datasource'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const router = useRouter()
const route = useRoute()
const rows = ref<DatasourceItem[]>([])
const readiness = reactive<Record<number, DatasourceReadiness | undefined>>({})
const total = ref(0)
const loading = ref(true)
const error = ref('')
const dialogVisible = ref(false)
const saving = ref(false)
const testing = ref(false)
const editingId = ref<number>()
const testedOk = ref(false)
const connectionDirty = ref(false)
// 仅保留在当前编辑弹窗内，保存时使用最近一次测试成功的密码。
// 不写入列表、路由、Store 或持久化存储。
const lastTestedPassword = ref<string | null>(null)
const formRef = ref<FormInstance>()

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

const rules = computed<FormRules>(() => ({
  name: [
    { required: true, message: '请输入数据源名称', trigger: 'blur' },
    { min: 2, max: 100, message: '名称需为 2-100 位', trigger: 'blur' },
  ],
  host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }],
  port: [{ required: true, type: 'number', min: 1, max: 65535, message: '端口需在 1-65535 之间', trigger: 'blur' }],
  databaseName: [{ required: true, message: '请输入数据库名', trigger: 'blur' }],
  charset: [{ required: true, message: '请选择字符集', trigger: 'change' }],
  username: [{ required: true, message: '请输入只读账号', trigger: 'blur' }],
  password: editingId.value ? [] : [{ required: true, message: '请输入密码', trigger: 'blur' }],
}))

const canSave = computed(() => testedOk.value && !connectionDirty.value)
const saveHint = computed(() => {
  if (!testedOk.value) return '保存前必须先测试连接成功'
  if (connectionDirty.value) return '连接配置已变化，请重新测试连接'
  return ''
})

function apiError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (error instanceof Error ? error.message : fallback)
}

function resetForm() {
  editingId.value = undefined
  testedOk.value = false
  connectionDirty.value = false
  lastTestedPassword.value = null
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

function loadReadiness(row: DatasourceItem) {
  return getDatasourceReadiness(row.id)
    .then((result) => { readiness[row.id] = result.data })
    .catch(() => { readiness[row.id] = undefined })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await listDatasources(query)
    rows.value = result.data.records
    total.value = result.data.total
    await Promise.all(rows.value.map(loadReadiness))
  } catch (cause) {
    rows.value = []
    total.value = 0
    error.value = apiError(cause, '数据源加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: DatasourceItem) {
  editingId.value = row.id
  testedOk.value = true
  connectionDirty.value = false
  lastTestedPassword.value = null
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
  dialogVisible.value = true
}

async function testConnection() {
  const valid = await formRef.value?.validateField(['host', 'port', 'databaseName', 'charset', 'username', 'password']).catch(() => false)
  if (valid === false) return
  if (editingId.value && !form.password) {
    ElMessage.warning('测试连接需要填写密码；若不修改连接配置，编辑保存可以留空密码')
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
    if (!result.data.success) {
      testedOk.value = false
      ElMessage.error('连接失败：' + result.data.message)
      return
    }
    testedOk.value = true
    connectionDirty.value = false
    Object.assign(originalConnection, {
      host: form.host,
      port: form.port,
      databaseName: form.databaseName,
      charset: form.charset,
      username: form.username,
    })
    lastTestedPassword.value = form.password ?? ''
    ElMessage.success('连接测试成功')
  } catch (cause) {
    testedOk.value = false
    ElMessage.error(apiError(cause, '连接测试失败，请检查配置'))
  } finally {
    testing.value = false
  }
}

async function save() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !canSave.value) {
    if (!canSave.value) ElMessage.warning(saveHint.value)
    return
  }

  saving.value = true
  try {
    const payload = { ...form }
    if (lastTestedPassword.value !== null) {
      payload.password = lastTestedPassword.value
    } else if (editingId.value) {
      delete payload.password
    }
    if (editingId.value) {
      await updateDatasource(editingId.value, payload)
      ElMessage.success('数据源已更新')
      dialogVisible.value = false
      await load()
      return
    }

    const result = await createDatasource(payload)
    const createdId = result.data?.id
    ElMessage.success('数据源已创建')
    dialogVisible.value = false
    if (createdId) {
      await router.push('/admin/data-sources/' + createdId)
    } else {
      await load()
    }
  } catch (cause) {
    ElMessage.error(apiError(cause, editingId.value ? '数据源更新失败' : '数据源创建失败'))
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: DatasourceItem) {
  const nextStatus = row.status === 1 ? 0 : 1
  if (nextStatus === 0) {
    try {
      await ElMessageBox.confirm(
        '禁用后该数据源无法继续采集和问数，请确认数据源名称：' + row.name,
        '禁用数据源',
        { type: 'warning', confirmButtonText: '确认禁用', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  try {
    const result = await updateDatasourceStatus(row.id, nextStatus)
    Object.assign(row, result.data)
    await loadReadiness(row)
    ElMessage.success(nextStatus === 1 ? '数据源已启用' : '数据源已禁用')
  } catch (cause) {
    ElMessage.error(apiError(cause, '数据源状态更新失败'))
  }
}

async function remove(row: DatasourceItem) {
  if (row.status === 1) {
    ElMessage.warning('启用状态的数据源不可删除，请先禁用')
    return
  }
  try {
    await ElMessageBox.confirm(
      '删除后该数据源及其授权关系将被移除，请确认数据源名称：' + row.name,
      '删除数据源',
      { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
    await deleteDatasource(row.id)
    ElMessage.success('数据源已删除')
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '数据源删除失败'))
  }
}

watch(
  () => [form.host, form.port, form.databaseName, form.charset, form.username, form.password],
  () => {
    if (!dialogVisible.value) return
    const changed = form.host !== originalConnection.host
      || form.port !== originalConnection.port
      || form.databaseName !== originalConnection.databaseName
      || form.charset !== originalConnection.charset
      || form.username !== originalConnection.username
      || form.password !== (lastTestedPassword.value ?? '')
    connectionDirty.value = changed
    if (changed) testedOk.value = false
  },
)

onMounted(async () => {
  await load()
  const editId = Number(route.query.edit)
  const editRow = rows.value.find((row) => row.id === editId)
  if (editRow) openEdit(editRow)
})
</script>

<template>
  <div class="admin-page data-sources-page">
    <TaskPageHeader
      eyebrow="数据接入"
      title="数据源"
      description="先完成连接测试，再保存并进入数据源驾驶舱。数据源列表只负责接入和基础状态，授权配置在权限与组织工作区完成。"
    >
      <template #actions>
        <el-button type="primary" :icon="Database" @click="openCreate">新增数据源</el-button>
      </template>
    </TaskPageHeader>

    <section class="data-sources-page__filters">
      <el-input v-model="query.name" clearable placeholder="搜索数据源名称" @keyup.enter="query.page = 1; load()" />
      <el-select v-model="query.status" clearable placeholder="全部状态" @change="query.page = 1; load()">
        <el-option label="启用" :value="1" />
        <el-option label="禁用" :value="0" />
      </el-select>
      <el-select v-model="query.healthStatus" clearable placeholder="全部连接状态" @change="query.page = 1; load()">
        <el-option label="连接正常" value="HEALTHY" />
        <el-option label="连接异常" value="UNHEALTHY" />
        <el-option label="未检测" value="UNKNOWN" />
      </el-select>
      <el-button :icon="RefreshCw" @click="load">刷新</el-button>
    </section>

    <ErrorState v-if="error" :message="error" @retry="load" />
    <LoadingState v-else-if="loading" variant="skeleton" :rows="6" />
    <section v-else-if="rows.length" class="data-sources-page__table">
      <el-table :data="rows" stripe>
        <el-table-column label="数据源" min-width="190">
          <template #default="{ row }">
            <div class="source-cell">
              <strong>{{ row.name }}</strong>
              <span>{{ row.dbType }} · {{ row.databaseName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="连接" min-width="180">
          <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }"><BusinessStatusBadge :status="row.status === 1 ? 'ENABLED' : 'DISABLED'" /></template>
        </el-table-column>
        <el-table-column label="就绪度" min-width="190">
          <template #default="{ row }">
            <div v-if="readiness[row.id]" class="readiness-cell">
              <BusinessStatusBadge :status="readiness[row.id]?.askable ? 'PUBLISHED' : readiness[row.id]?.stage" :label="readiness[row.id]?.askable ? '可以问数' : readiness[row.id]?.stageLabel" />
              <el-progress :percentage="readiness[row.id]?.progress || 0" :show-text="false" />
            </div>
            <span v-else class="muted">就绪度暂不可用</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="ExternalLink" @click="router.push('/admin/data-sources/' + row.id)">驾驶舱</el-button>
            <el-button link :icon="Pencil" @click="openEdit(row)">编辑</el-button>
            <el-button link :icon="PlugZap" @click="router.push('/admin/data-sources/' + row.id + '?action=test')">连接</el-button>
            <el-dropdown trigger="click">
              <el-button link>更多</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="toggleStatus(row)">{{ row.status === 1 ? '禁用数据源' : '启用数据源' }}</el-dropdown-item>
                  <el-dropdown-item divided @click="remove(row)"><Trash2 :size="14" />删除数据源</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="total > (query.pageSize || 20)"
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        class="data-sources-page__pagination"
        layout="total, sizes, prev, pager, next"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        @current-change="load"
        @size-change="() => { query.page = 1; load() }"
      />
    </section>
    <EmptyState
      v-else
      :message="query.name || query.status !== undefined || query.healthStatus ? '当前筛选条件下没有匹配的数据源。' : '还没有数据源，请先完成连接测试并创建一个数据源。'"
      :action-text="query.name || query.status !== undefined || query.healthStatus ? '' : '新增数据源'"
      @action="openCreate"
    />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑数据源' : '新增数据源'" width="720px" @closed="resetForm">
      <el-alert
        title="连接测试是保存门禁"
        description="新建数据源必须测试连接成功后才能保存；编辑时修改连接配置也需要重新测试。"
        type="info"
        :closable="false"
        show-icon
      />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" class="data-source-form">
        <el-form-item label="名称" prop="name"><el-input v-model="form.name" placeholder="例如：生产订单库" /></el-form-item>
        <el-form-item label="说明" prop="description"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
        <el-divider content-position="left">连接配置</el-divider>
        <el-form-item label="主机" prop="host"><el-input v-model="form.host" placeholder="127.0.0.1 或数据库域名" /></el-form-item>
        <el-form-item label="端口" prop="port"><el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" /></el-form-item>
        <el-form-item label="数据库" prop="databaseName"><el-input v-model="form.databaseName" /></el-form-item>
        <el-form-item label="字符集" prop="charset"><el-select v-model="form.charset"><el-option v-for="item in ['utf8mb4', 'utf8', 'latin1', 'gbk']" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        <el-form-item label="只读账号" prop="username"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="密码" prop="password"><el-input v-model="form.password" type="password" show-password :placeholder="editingId ? '留空则沿用原密码' : '请输入密码'" /></el-form-item>
        <el-form-item>
          <el-button :loading="testing" :icon="PlugZap" @click="testConnection">测试连接</el-button>
          <span v-if="canSave" class="form-success">连接已测试，可以保存</span>
          <span v-else class="form-hint">{{ saveHint }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canSave" @click="save">保存并进入下一步</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.data-sources-page__filters {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 160px 180px auto;
  gap: 10px;
  margin-bottom: 16px;
}

.data-sources-page__table {
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
}

.source-cell,
.readiness-cell {
  display: grid;
  gap: 4px;
}

.source-cell strong {
  color: var(--do-ink);
}

.source-cell span,
.muted,
.form-hint {
  color: var(--do-muted);
  font-size: 12px;
}

.readiness-cell :deep(.el-progress) {
  width: 150px;
}

.data-sources-page__pagination {
  justify-content: flex-end;
  margin-top: 16px;
}

.data-source-form {
  margin-top: 18px;
}

.data-source-form :deep(.el-input),
.data-source-form :deep(.el-select),
.data-source-form :deep(.el-input-number) {
  width: 100%;
}

.form-success {
  margin-left: 10px;
  color: var(--do-success);
  font-size: 12px;
}

.form-hint {
  margin-left: 10px;
}

@media (max-width: 760px) {
  .data-sources-page__filters {
    grid-template-columns: 1fr;
  }
}
</style>
