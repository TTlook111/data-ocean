<script setup lang="ts">
import { computed, onMounted, onScopeDispose, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Ban, ChevronDown, CircleCheck, Database, Pencil, PlugZap, RefreshCw, Search, Trash2 } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import {
  createDatasource,
  deleteDatasource,
  getDatasourceReadiness,
  getDatasource,
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
import { useIamS1Store } from '../../../stores/iamS1'
import { useAuthStore } from '../../../stores/auth'
import { useAdminScrollLock } from '../../../composables/useAdminScrollLock'
import { getHttpErrorMessage } from '../../../utils/httpError'

const router = useRouter()
const route = useRoute()
const iamS1 = useIamS1Store()
const auth = useAuthStore()
const canView = computed(() => iamS1.hasGlobal('datasource:view'))
const canCreate = computed(() => iamS1.hasGlobal('datasource:manage'))
const VIEW_HINT = '没有查看数据源的权限。'
const MANAGE_HINT = '没有管理当前数据源的权限。'
const CREATE_HINT = '没有新建数据源的权限。'

function canManageDatasource(id: number) {
  return iamS1.canOnDatasource('datasource:manage', id)
}

const rows = ref<DatasourceItem[]>([])
const readiness = reactive<Record<number, DatasourceReadiness | undefined>>({})
const total = ref(0)
const loading = ref(true)
const error = ref('')
const dialogVisible = ref(false)
const saving = ref(false)
const testing = ref(false)
const rowActionBusy = ref(false)
const editingId = ref<number>()
const testedOk = ref(false)
const connectionDirty = ref(false)
const testError = ref('')
// 仅保留在当前编辑弹窗内，保存时使用最近一次测试成功的密码。
// 不写入列表、路由、Store 或持久化存储。
const lastTestedPassword = ref<string | null>(null)
const formRef = ref<FormInstance>()
let listRequest = 0, dialogGeneration = 0, testRequest = 0, connectionRevision = 0, formRevision = 0, alive = true
let navigationIntent = 0
let confirmationGeneration = 0, ownsConfirmation = false
const confirmationBusy = ref(false)
const confirmationCancelClass = `datasource-cancel-${Math.random().toString(36).slice(2)}`
function closeOwnedConfirmation() {
  confirmationGeneration++
  if (ownsConfirmation) document.querySelector<HTMLButtonElement>(`.${confirmationCancelClass}`)?.click()
  ownsConfirmation = false; confirmationBusy.value = false
}
async function confirmAction(message: string, title: string, type: 'warning' | 'error', confirmButtonText: string) {
  if (confirmationBusy.value) return false
  const ticket = ++confirmationGeneration
  ownsConfirmation = true; confirmationBusy.value = true
  try {
    await ElMessageBox.confirm(message, title, { type, confirmButtonText, cancelButtonText: '取消', cancelButtonClass: confirmationCancelClass })
    return alive && ticket === confirmationGeneration
  } catch { return false }
  finally { if (ticket === confirmationGeneration) { ownsConfirmation = false; confirmationBusy.value = false } }
}
useAdminScrollLock(() => dialogVisible.value)
onScopeDispose(() => { alive = false; navigationIntent++; closeOwnedConfirmation(); listRequest++; dialogGeneration++; testRequest++; lastTestedPassword.value = null; form.password = '' })
const preparationStages = [
  { title: '连接', text: '配置并测试只读连接' }, { title: '采集', text: '取得表与字段结构' },
  { title: '治理', text: '核对问题与业务含义' }, { title: '发布', text: '审核并发布版本' },
  { title: '索引', text: '显式构建知识索引' }, { title: '问数', text: '按当前授权执行' },
]

const query = reactive<DatasourceQuery>({
  name: typeof route.query.name === 'string' ? route.query.name : undefined,
  status: ['0', '1'].includes(String(route.query.status)) ? Number(route.query.status) : undefined,
  healthStatus: ['HEALTHY', 'UNHEALTHY', 'UNKNOWN'].includes(String(route.query.healthStatus)) ? String(route.query.healthStatus) : undefined,
  page: Math.max(1, Number(route.query.page) || 1),
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
const canWriteForm = computed(() => editingId.value === undefined ? canCreate.value : canManageDatasource(editingId.value))
const saveHint = computed(() => {
  if (!testedOk.value) return '保存前必须先测试连接成功'
  if (connectionDirty.value) return '连接配置已变化，请重新测试连接'
  return ''
})
const testStatusText = computed(() => {
  if (testing.value) return '正在测试连接…'
  if (testError.value) return '连接测试失败，请检查配置后重试。'
  if (connectionDirty.value) return '连接配置已修改，请重新测试连接。'
  if (testedOk.value) return '连接测试已通过，可以保存。'
  return '连接测试成功后即可保存。'
})
const testStatusTone = computed(() => {
  if (testing.value) return 'testing'
  if (testError.value) return 'danger'
  if (connectionDirty.value) return 'warning'
  if (testedOk.value) return 'success'
  return 'muted'
})

function apiError(error: unknown, fallback: string) {
  return getHttpErrorMessage(error, fallback)
}

function sourceLabel(row: DatasourceItem) {
  return `${row.name} · ${row.dbType} · ${row.databaseName}`
}

function connectionLabel(row: DatasourceItem) {
  return `${row.host}:${row.port}`
}

function healthLabel(status?: string) {
  if (status === 'HEALTHY') return '连接正常'
  if (status === 'UNHEALTHY') return '连接异常'
  return '尚未检测'
}

function resetForm() {
  dialogGeneration++; testRequest++; testing.value = false; saving.value = false
  editingId.value = undefined
  testedOk.value = false
  connectionDirty.value = false
  testError.value = ''
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

function loadReadiness(row: DatasourceItem, ticket = listRequest, token = auth.token) {
  return getDatasourceReadiness(row.id)
    .then((result) => {
      if (result.data.datasourceId !== row.id) throw new Error('准备状态的数据源范围不一致')
      if (alive && ticket === listRequest && token === auth.token) readiness[row.id] = result.data
    })
    .catch(() => { if (alive && ticket === listRequest && token === auth.token) readiness[row.id] = undefined })
}

async function load() {
  const ticket = ++listRequest, token = auth.token, params = { ...query }
  const current = () => alive && ticket === listRequest && token === auth.token
  loading.value = true
  error.value = ''
  await iamS1.load()
  if (!current()) return
  if (!canView.value) {
    rows.value = []
    total.value = 0
    error.value = iamS1.errorMessage || VIEW_HINT
    loading.value = false
    return
  }
  try {
    const filters = { ...route.query }
    for (const key of ['name', 'status', 'healthStatus', 'page'] as const) {
      const value = params[key]
      if (value === undefined || value === '' || (key === 'page' && value === 1)) delete filters[key]
      else filters[key] = String(value)
    }
    await router.replace({ query: filters })
    if (!current()) return
    const result = await listDatasources(params)
    if (!current()) return
    rows.value = result.data.records
    total.value = result.data.total
    for (const id of Object.keys(readiness)) delete readiness[Number(id)]
    await Promise.all(rows.value.map(row => loadReadiness(row, ticket, token)))
  } catch (cause) {
    if (!current()) return
    rows.value = []
    total.value = 0
    error.value = apiError(cause, '数据源加载失败，请稍后重试')
  } finally {
    if (current()) loading.value = false
  }
}

function openCreate() {
  if (!canCreate.value) { ElMessage.warning(CREATE_HINT); return }
  navigationIntent++
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: DatasourceItem) {
  if (!canManageDatasource(row.id)) { ElMessage.warning(MANAGE_HINT); return }
  navigationIntent++
  resetForm()
  editingId.value = row.id
  testedOk.value = true
  connectionDirty.value = false
  testError.value = ''
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
  testedOk.value = true
  connectionDirty.value = false
  dialogVisible.value = true
}

async function testConnection() {
  if (testing.value || saving.value) return
  const generation = dialogGeneration, revision = connectionRevision, ticket = ++testRequest, identity = auth.token
  const current = () => alive && dialogVisible.value && generation === dialogGeneration && ticket === testRequest && identity === auth.token
  if (!canWriteForm.value) { ElMessage.warning(MANAGE_HINT); return }
  const valid = await formRef.value?.validateField(['host', 'port', 'databaseName', 'charset', 'username', 'password']).catch(() => false)
  if (valid === false || !current() || revision !== connectionRevision) return
  if (!canWriteForm.value) { ElMessage.warning(MANAGE_HINT); return }
  if (editingId.value && !form.password) {
    ElMessage.warning('测试连接需要填写密码；若不修改连接配置，编辑保存可以留空密码')
    return
  }

  testing.value = true
  testError.value = ''
  try {
    const payload = {
      host: form.host,
      port: form.port,
      databaseName: form.databaseName,
      charset: form.charset,
      username: form.username,
      password: form.password || '',
    }
    const result = await testDatasourceConnection(payload)
    if (!current() || revision !== connectionRevision || !canWriteForm.value) return
    if (!result.data.success) {
      testedOk.value = false
      testError.value = '连接失败：' + result.data.message
      return
    }
    testedOk.value = true
    connectionDirty.value = false
    Object.assign(originalConnection, { host: payload.host, port: payload.port, databaseName: payload.databaseName, charset: payload.charset, username: payload.username })
    lastTestedPassword.value = payload.password
    ElMessage.success('连接测试成功')
  } catch (cause) {
    if (!current() || revision !== connectionRevision) return
    testedOk.value = false
    testError.value = apiError(cause, '连接测试失败，请检查配置')
  } finally {
    if (current()) testing.value = false
  }
}

async function save() {
  if (saving.value || testing.value) return
  const generation = dialogGeneration, revision = formRevision, identity = auth.token, id = editingId.value, intent = navigationIntent
  const current = () => alive && dialogVisible.value && generation === dialogGeneration && identity === auth.token
  if (!canWriteForm.value) { ElMessage.warning(MANAGE_HINT); return }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!current() || revision !== formRevision || saving.value || testing.value) return
  if (!valid || !canSave.value) {
    if (!canSave.value) ElMessage.warning(saveHint.value)
    return
  }
  if (!canWriteForm.value) { ElMessage.warning(MANAGE_HINT); return }

  saving.value = true
  try {
    const payload = { ...form }
    if (lastTestedPassword.value !== null) {
      payload.password = lastTestedPassword.value
    } else if (id !== undefined) {
      delete payload.password
    }
    if (id !== undefined) {
      await updateDatasource(id, payload)
      if (!current()) return
      ElMessage.success('数据源已更新')
      dialogVisible.value = false
      await load()
      return
    }

    const result = await createDatasource(payload)
    if (!current()) return
    const createdId = result.data?.id
    ElMessage.success('数据源已创建')
    dialogVisible.value = false
    await iamS1.load(true)
    if (!alive || identity !== auth.token || intent !== navigationIntent) return
    if (createdId && iamS1.canOnDatasource('datasource:view', createdId)) {
      await router.push('/admin/data-sources/' + createdId)
    } else {
      ElMessage.info('连接已保存；当前账号尚无新数据源查看权限，请联系管理员配置负责源。')
      await load()
    }
  } catch (cause) {
    if (!current()) return
    ElMessage.error(apiError(cause, editingId.value ? '数据源更新失败' : '数据源创建失败'))
  } finally {
    if (alive && generation === dialogGeneration && identity === auth.token) saving.value = false
  }
}

async function toggleStatus(row: DatasourceItem) {
  if (confirmationBusy.value || rowActionBusy.value) return
  const identity = auth.token
  if (!canManageDatasource(row.id)) { ElMessage.warning(MANAGE_HINT); return }
  const nextStatus = row.status === 1 ? 0 : 1
  if (nextStatus === 0) {
    if (!await confirmAction('禁用后该数据源无法继续采集和问数，请确认数据源名称：' + row.name, '禁用数据源', 'warning', '确认禁用')) return
  }
  rowActionBusy.value = true
  try {
    if (!alive || identity !== auth.token) return
    if (!canManageDatasource(row.id)) { ElMessage.warning(MANAGE_HINT); return }
    const result = await updateDatasourceStatus(row.id, nextStatus)
    if (!alive || identity !== auth.token) return
    Object.assign(row, result.data)
    await loadReadiness(row)
    if (!alive || identity !== auth.token) return
    ElMessage.success(nextStatus === 1 ? '数据源已启用' : '数据源已禁用')
  } catch (cause) {
    if (alive && identity === auth.token) ElMessage.error(apiError(cause, '数据源状态更新失败'))
  } finally {
    if (alive && identity === auth.token) rowActionBusy.value = false
  }
}

async function remove(row: DatasourceItem) {
  if (confirmationBusy.value || rowActionBusy.value) return
  const identity = auth.token
  if (!canManageDatasource(row.id)) { ElMessage.warning(MANAGE_HINT); return }
  if (row.status === 1) {
    ElMessage.warning('启用状态的数据源不可删除，请先禁用')
    return
  }
  try {
    if (!await confirmAction('删除后该数据源及其授权关系将被移除，请确认数据源名称：' + row.name, '删除数据源', 'error', '确认删除')) return
    if (!alive || identity !== auth.token) return
    if (!canManageDatasource(row.id)) { ElMessage.warning(MANAGE_HINT); return }
    rowActionBusy.value = true
    await deleteDatasource(row.id)
    if (!alive || identity !== auth.token) return
    ElMessage.success('数据源已删除')
    if (rows.value.length === 1 && (query.page || 1) > 1) query.page = (query.page || 1) - 1
    await load()
  } catch (cause) {
    if (alive && identity === auth.token && cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '数据源删除失败'))
  } finally {
    if (alive && identity === auth.token) rowActionBusy.value = false
  }
}

watch(
  () => [form.host, form.port, form.databaseName, form.charset, form.username, form.password],
  () => {
    connectionRevision++
    if (!dialogVisible.value) return
    const changed = form.host !== originalConnection.host
      || form.port !== originalConnection.port
      || form.databaseName !== originalConnection.databaseName
      || form.charset !== originalConnection.charset
      || form.username !== originalConnection.username
      || form.password !== (lastTestedPassword.value ?? '')
    connectionDirty.value = changed
    if (changed) {
      testedOk.value = false
      testError.value = ''
    }
  }, { flush: 'sync' },
)
watch(() => [form.name, form.description, form.host, form.port, form.databaseName, form.charset, form.username, form.password], () => { formRevision++ }, { flush: 'sync' })
watch(() => auth.token, () => { navigationIntent++; closeOwnedConfirmation(); rowActionBusy.value = false; dialogVisible.value = false; resetForm(); void load() })
watch(() => iamS1.snapshot, () => { void load() })
watch(() => [query.name, query.status, query.healthStatus, query.page, query.pageSize], () => { navigationIntent++; closeOwnedConfirmation() }, { flush: 'sync' })
watch(() => [route.query.name, route.query.status, route.query.healthStatus, route.query.page], () => {
  navigationIntent++
  closeOwnedConfirmation()
  const next = { name: typeof route.query.name === 'string' ? route.query.name : undefined,
    status: ['0','1'].includes(String(route.query.status)) ? Number(route.query.status) : undefined,
    healthStatus: ['HEALTHY','UNHEALTHY','UNKNOWN'].includes(String(route.query.healthStatus)) ? String(route.query.healthStatus) : undefined,
    page: Math.max(1, Number(route.query.page) || 1) }
  if (Object.entries(next).every(([key, value]) => query[key as keyof DatasourceQuery] === value)) return
  Object.assign(query, next); void load()
})

async function openEditFromRoute() {
  const editId = Number(route.query.edit)
  if (!Number.isSafeInteger(editId) || editId <= 0 || !canManageDatasource(editId) || !iamS1.canOnDatasource('datasource:view', editId)) return
  navigationIntent++
  const identity = auth.token, generation = dialogGeneration
  const current = () => alive && identity === auth.token && generation === dialogGeneration && editId === Number(route.query.edit)
  try {
    const editRow = rows.value.find(row => row.id === editId) || (await getDatasource(editId)).data
    if (current() && editRow.id === editId && canManageDatasource(editId) && iamS1.canOnDatasource('datasource:view', editId)) openEdit(editRow)
  } catch (cause) { if (current()) ElMessage.error(apiError(cause, '编辑目标读取失败')) }
}
watch(() => route.query.edit, () => { void openEditFromRoute() })
onMounted(async () => { await load(); if (alive) await openEditFromRoute() })
</script>

<template>
  <div class="admin-page data-sources-page">
    <TaskPageHeader
      title="数据源"
      description="管理连接，查看准备情况，继续数据治理。"
    >
      <template #actions>
        <el-button v-if="canCreate" type="primary" :icon="Database" @click="openCreate">新增数据源</el-button>
      </template>
    </TaskPageHeader>

    <section class="source-journey" aria-label="从连接到问数的操作分工">
      <ol><li v-for="(stage, index) in preparationStages" :key="stage.title"><span aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span><div><strong>{{ stage.title }}</strong><small>{{ stage.text }}</small></div></li></ol>
      <p>发布与索引构建是两个动作。准备状态以各数据源当前核验结果为准。</p>
    </section>

    <section class="data-sources-panel do-glass-panel">
      <div class="data-sources-panel__toolbar">
        <el-input
          v-model="query.name"
          class="data-sources-panel__search"
          clearable
          placeholder="搜索数据源名称，按 Enter 搜索"
          aria-label="搜索数据源名称"
          @clear="query.page = 1; load()"
          @keyup.enter="query.page = 1; load()"
        ><template #prefix><Search :size="16" aria-hidden="true" /></template></el-input>
        <el-select v-model="query.status" class="data-sources-panel__filter" clearable placeholder="全部状态" @change="query.page = 1; load()">
          <el-option label="启用" :value="1" />
          <el-option label="禁用" :value="0" />
        </el-select>
        <el-select v-model="query.healthStatus" class="data-sources-panel__filter" clearable placeholder="全部连接状态" @change="query.page = 1; load()">
          <el-option label="连接正常" value="HEALTHY" />
          <el-option label="连接异常" value="UNHEALTHY" />
          <el-option label="未检测" value="UNKNOWN" />
        </el-select>
        <span v-if="!loading && !error" class="data-sources-panel__count">共 {{ total }} 个匹配数据源<span v-if="total > rows.length"> · 本页 {{ rows.length }} 个</span></span>
        <el-button class="data-sources-panel__refresh" :icon="RefreshCw" @click="load">刷新</el-button>
      </div>

      <ErrorState v-if="error" :message="error" @retry="load" />
      <LoadingState v-else-if="loading" variant="skeleton" :rows="5" />
      <template v-else-if="rows.length">
        <div class="data-sources-panel__table">
          <el-table :data="rows">
            <el-table-column label="数据源" min-width="250">
              <template #default="{ row }">
                <el-tooltip :content="sourceLabel(row)" placement="top-start" effect="light" :show-after="300" :trigger="['hover', 'focus']" popper-class="data-source-cell-tooltip">
                  <div class="source-cell" tabindex="0" :aria-label="sourceLabel(row)">
                    <strong class="notranslate" translate="no">{{ row.name }}</strong>
                    <span class="source-cell__meta notranslate" translate="no">{{ row.dbType }} · {{ row.databaseName }}</span>
                    <small v-if="row.description" class="source-cell__description" :title="row.description">{{ row.description }}</small>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="连接" min-width="180">
              <template #default="{ row }">
                <div class="connection-cell">
                  <el-tooltip :content="connectionLabel(row)" placement="top-start" effect="light" :show-after="300" :trigger="['hover', 'focus']" popper-class="data-source-cell-tooltip">
                    <span class="connection-cell__address" tabindex="0" translate="no" :aria-label="connectionLabel(row)">{{ row.host }}:{{ row.port }}</span>
                  </el-tooltip>
                  <BusinessStatusBadge :status="row.healthStatus" :label="healthLabel(row.healthStatus)" />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="启用状态" width="96">
              <template #default="{ row }"><BusinessStatusBadge :status="row.status === 1 ? 'ENABLED' : 'DISABLED'" /></template>
            </el-table-column>
            <el-table-column label="当前阶段与下一步" min-width="180">
              <template #default="{ row }">
                <div v-if="readiness[row.id]" class="readiness-cell">
                  <BusinessStatusBadge :status="readiness[row.id]?.askable ? 'PUBLISHED' : readiness[row.id]?.stage" :label="readiness[row.id]?.askable ? '可问数' : readiness[row.id]?.stageLabel" />
                  <span v-if="readiness[row.id]?.blockReasons.length" class="readiness-cell__reason">{{ readiness[row.id]?.blockReasons[0]?.message }}</span>
                </div>
                <span v-else class="cell-muted">准备情况读取失败，请刷新重试</span>
              </template>
            </el-table-column>
            <el-table-column label="版本与索引" min-width="190">
              <template #default="{ row }">
                <div v-if="readiness[row.id]" class="version-cell"><span>采集 {{ readiness[row.id]?.latestCollectedSnapshotId ? 'v' + readiness[row.id]?.latestCollectedSnapshotVersion : '暂无' }} · 发布 {{ readiness[row.id]?.publishedSnapshotId ? 'v' + readiness[row.id]?.snapshotVersion : '暂无' }}</span><small :class="{ 'version-warning': readiness[row.id]?.ragStale }">索引：{{ readiness[row.id]?.knowledgeReady ? readiness[row.id]?.ragStale ? '活动版本来源落后' : '已有活动版本' : '尚无活动版本' }}</small></div>
                <span v-else class="cell-muted">版本暂未确认</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="184" fixed="right">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-button link type="primary" @click="router.push('/admin/data-sources/' + row.id)">查看详情</el-button>
                  <el-dropdown v-if="canManageDatasource(row.id)" trigger="click" popper-class="data-source-actions-popper">
                    <el-button link class="row-actions__more" :disabled="rowActionBusy" :aria-label="row.name + '的更多操作'">更多<ChevronDown :size="14" aria-hidden="true" /></el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item @click="openEdit(row)"><Pencil :size="17" aria-hidden="true" />编辑数据源</el-dropdown-item>
                        <el-dropdown-item @click="canManageDatasource(row.id) && router.push('/admin/data-sources/' + row.id + '?action=test')"><PlugZap :size="17" aria-hidden="true" />测试连接</el-dropdown-item>
                        <el-dropdown-item @click="toggleStatus(row)">
                          <Ban v-if="row.status === 1" :size="17" aria-hidden="true" />
                          <CircleCheck v-else :size="17" aria-hidden="true" />
                          {{ row.status === 1 ? '禁用数据源' : '启用数据源' }}
                        </el-dropdown-item>
                        <el-dropdown-item divided class="data-source-actions__danger" @click="remove(row)"><Trash2 :size="17" aria-hidden="true" />删除数据源</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div v-if="total > (query.pageSize || 20)" class="data-sources-panel__pagination">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.pageSize"
            layout="total, sizes, prev, pager, next"
            :page-sizes="[10, 20, 50, 100]"
            :total="total"
            @current-change="load"
            @size-change="() => { query.page = 1; load() }"
          />
        </div>
      </template>
      <EmptyState
        v-else
        :message="query.name || query.status !== undefined || query.healthStatus ? '当前筛选条件下没有匹配的数据源。' : canCreate ? '还没有数据源，请先完成连接测试并创建一个数据源。' : '当前负责范围内没有可查看的数据源。'"
        :action-text="query.name || query.status !== undefined || query.healthStatus || !canCreate ? '' : '新增数据源'"
        @action="openCreate"
      />
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑数据源' : '新增数据源'"
      class="data-source-dialog"
      width="760px"
      top="6vh"
      @closed="resetForm"
    >
      <p class="connection-contract">测试连接只验证当前配置。保存后在详情继续采集、治理与审核发布；知识索引需要单独构建。</p>
      <el-form ref="formRef" :model="form" :rules="rules" :disabled="saving || !canWriteForm" label-position="top" class="data-source-form">
        <section class="data-source-dialog__section">
          <h3 class="data-source-dialog__section-title">基本信息</h3>
          <div class="data-source-dialog__stack">
            <el-form-item label="名称" prop="name"><el-input v-model="form.name" placeholder="例如：生产订单库" /></el-form-item>
            <el-form-item label="说明" prop="description"><el-input v-model="form.description" type="textarea" :rows="2" placeholder="选填，简要说明这个数据源的用途" /></el-form-item>
          </div>
        </section>

        <section class="data-source-dialog__section">
          <h3 class="data-source-dialog__section-title">连接配置</h3>
          <div class="data-source-dialog__row data-source-dialog__row--main">
            <el-form-item label="主机" prop="host"><el-input v-model="form.host" placeholder="127.0.0.1 或数据库域名" /></el-form-item>
            <el-form-item label="端口" prop="port"><el-input-number v-model="form.port" class="data-source-dialog__port" :min="1" :max="65535" controls-position="right" /></el-form-item>
            <el-form-item label="数据库" prop="databaseName"><el-input v-model="form.databaseName" /></el-form-item>
            <el-form-item label="字符集" prop="charset"><el-select v-model="form.charset"><el-option v-for="item in ['utf8mb4', 'utf8', 'latin1', 'gbk']" :key="item" :label="item" :value="item" /></el-select></el-form-item>
          </div>
        </section>

        <section class="data-source-dialog__section">
          <h3 class="data-source-dialog__section-title">访问凭据</h3>
          <div class="data-source-dialog__row data-source-dialog__row--credentials">
            <el-form-item label="只读账号" prop="username"><el-input v-model="form.username" /></el-form-item>
            <el-form-item label="密码" prop="password"><el-input v-model="form.password" type="password" show-password :placeholder="editingId ? '留空则沿用原密码' : '请输入密码'" /></el-form-item>
          </div>
        </section>

        <p v-if="testError" class="data-source-dialog__test-error" role="alert">{{ testError }}</p>
      </el-form>

      <template #footer>
        <div class="data-source-dialog__footer">
          <div class="data-source-dialog__test">
            <el-button :loading="testing" :disabled="!canWriteForm || saving" :icon="PlugZap" @click="testConnection">测试连接</el-button>
            <span class="data-source-dialog__test-status" :class="`is-${testStatusTone}`" role="status" aria-live="polite">{{ testStatusText }}</span>
          </div>
          <div class="data-source-dialog__footer-actions">
            <el-button @click="dialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="saving" :disabled="!canSave || !canWriteForm || testing" @click="save">{{ editingId ? '保存' : '创建数据源' }}</el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.source-journey { margin: 6px 0 24px; padding: 20px 0 16px; border-top: 1px solid var(--do-line); border-bottom: 1px solid var(--do-line); }
.source-journey ol { display: grid; grid-template-columns: repeat(6, minmax(0,1fr)); gap: 16px; padding: 0; margin: 0; list-style: none; }
.source-journey li { display: flex; gap: 9px; min-width: 0; }
.source-journey li > span { margin-top: 2px; color: var(--do-primary-strong); font-size: 10px; font-variant-numeric: tabular-nums; }
.source-journey strong { display: block; color: var(--do-ink); font-size: 13px; font-weight: 600; }
.source-journey small { display: block; margin-top: 6px; color: var(--do-muted); font-size: 11px; line-height: 1.6; }
.source-journey p { margin: 16px 0 0; color: var(--do-muted); font-size: 11px; line-height: 1.5; }
.source-cell__description { max-width: 100%; overflow: hidden; color: var(--do-muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.version-cell { display: grid; gap: 5px; color: var(--do-ink); font-size: 12px; }
.version-cell small { color: var(--do-muted); font-size: 11px; }
.version-cell .version-warning { color: var(--do-warning); }
.connection-contract { margin: 0 0 22px; padding: 12px 14px; border: 1px solid var(--do-line); border-radius: 8px; color: var(--do-muted); background: var(--do-soft); font-size: 12px; line-height: 1.7; }
@media (max-width: 1100px) { .source-journey ol { grid-template-columns: repeat(3,minmax(0,1fr)); gap: 18px; } }
@media (max-width: 600px) { .source-journey ol { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 16px; } }
.data-sources-page :deep(.task-page-header) { align-items: center; margin-bottom: 14px; }
.data-sources-page :deep(.task-page-header h1) { font-size: clamp(28px, 2.4vw, 32px); line-height: 1.2; letter-spacing: -.025em; }

.data-sources-panel { min-width: 0; padding: 0; overflow: hidden; border-color: var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-glass-panel-bg); box-shadow: var(--do-shadow); }
.data-sources-panel__toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 12px 16px; border-bottom: 1px solid var(--do-line); }
.data-sources-panel__search { flex: 1 1 280px; min-width: 200px; max-width: 360px; }
.data-sources-panel__filter { flex: 0 1 160px; min-width: 140px; }
.data-sources-panel__toolbar :deep(.el-input__wrapper),
.data-sources-panel__toolbar :deep(.el-select__wrapper) { min-height: 36px; }
.data-sources-panel__toolbar :deep(.el-input__inner),
.data-sources-panel__toolbar :deep(.el-select__selected-item) { font-size: 13px; }
.data-sources-panel__count { margin-left: auto; color: var(--do-muted); font-size: 12px; white-space: nowrap; }
.data-sources-panel__refresh.el-button { flex: 0 0 auto; height: 36px; }

.data-sources-panel__table { min-width: 0; }
.data-sources-panel :deep(.el-table) {
  --el-table-bg-color: var(--do-surface);
  --el-table-tr-bg-color: var(--do-surface);
  --el-table-header-bg-color: var(--do-soft);
  --el-table-border-color: var(--do-line);
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--do-primary) 5%, var(--do-surface));
  color: var(--do-ink);
}
.data-sources-panel :deep(.el-table::before),
.data-sources-panel :deep(.el-table__inner-wrapper::before) { display: none; }
.data-sources-panel :deep(.el-table th.el-table__cell) { height: 42px; padding: 0; background: var(--do-soft); color: var(--do-muted); font-size: 12px; font-weight: 600; }
.data-sources-panel :deep(.el-table th.el-table__cell .cell) { padding: 0 12px; line-height: 42px; }
.data-sources-panel :deep(.el-table td.el-table__cell) { padding: 13px 0; vertical-align: middle; border-bottom-color: var(--do-line); }
.data-sources-panel :deep(.el-table td.el-table__cell .cell) { padding: 0 12px; line-height: 1.45; }
.data-sources-panel :deep(.el-table th.el-table__cell:first-child .cell),
.data-sources-panel :deep(.el-table td.el-table__cell:first-child .cell) { padding-left: 16px; }
.data-sources-panel :deep(.el-table th.el-table__cell:last-child .cell),
.data-sources-panel :deep(.el-table td.el-table__cell:last-child .cell) { padding-right: 16px; }
.data-sources-panel :deep(.el-table__body tr:last-child td.el-table__cell) { border-bottom: 0; }

.source-cell { min-width: 0; min-height: 46px; display: grid; align-content: center; justify-items: start; gap: 4px; border-radius: 6px; outline: none; }
.source-cell:focus-visible { outline: 2px solid var(--do-primary); outline-offset: 2px; }
.source-cell strong, .source-cell__meta { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source-cell strong { color: var(--do-ink); font-size: 14px; font-weight: 650; }
.source-cell__meta { color: var(--do-muted); font-size: 12px; }

.connection-cell { min-width: 0; min-height: 46px; display: grid; align-content: center; justify-items: start; gap: 4px; }
.connection-cell__address { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; border-radius: 6px; outline: none; color: var(--do-ink); font-size: 12.5px; }
.connection-cell__address:focus-visible { outline: 2px solid var(--do-primary); outline-offset: 2px; }
.connection-cell :deep(.business-status-badge) { min-height: auto; padding: 0; gap: 5px; background: transparent; font-size: 12px; font-weight: 500; }

.readiness-cell { min-width: 0; display: grid; align-content: center; justify-items: start; gap: 4px; }
.readiness-cell :deep(.business-status-badge) { justify-self: start; width: fit-content; max-width: 100%; }
.readiness-cell__reason { color: var(--do-muted); font-size: 11.5px; line-height: 1.45; overflow-wrap: anywhere; }

.snapshot-cell { display: inline-block; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--do-ink); font-size: 12px; }
.cell-muted { color: var(--do-muted); font-size: 12px; line-height: 1.45; overflow-wrap: anywhere; }

.row-actions { display: flex; align-items: center; gap: 10px; white-space: nowrap; }
.row-actions__more.el-button { padding: 3px 6px; border-radius: 6px; color: var(--do-ink); font-weight: 600; }
.row-actions__more.el-button:hover:not(.is-disabled) { color: var(--do-primary-strong); background: var(--do-primary-soft); }
.row-actions__more.el-button.is-disabled,
.row-actions__more.el-button:disabled { color: var(--do-muted); background: transparent; opacity: .6; cursor: not-allowed; }

.data-sources-panel__pagination { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; padding: 10px 16px 12px; border-top: 1px solid var(--do-line); background: var(--do-surface); }
.data-sources-panel__pagination :deep(.el-pagination) { flex-wrap: wrap; justify-content: flex-end; row-gap: 8px; }


@media (max-width: 900px) {
  .data-sources-panel__toolbar { padding: 10px 12px; gap: 8px; }
  .data-sources-panel__search { flex: 1 1 100%; max-width: none; }
  .data-sources-panel__filter { flex: 1 1 140px; }
  .data-sources-panel__count { margin-left: 0; }
  .data-sources-panel__refresh { margin-left: auto; }
}
</style>

<style>
.data-source-cell-tooltip.el-popper.el-tooltip__popper {
  max-width: min(360px, calc(100vw - 24px));
  border-color: var(--do-line);
  color: var(--do-ink);
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.data-source-actions-popper.el-popper {
  width: 172px;
  min-width: 172px;
  max-width: calc(100vw - 24px);
  padding: 0;
  border-color: var(--do-line);
  border-radius: var(--do-radius-md);
  background: rgba(255, 255, 255, .99);
  box-shadow: var(--do-shadow-lg);
}
.data-source-actions-popper .el-popper__arrow::before { border-color: var(--do-line); background: #fff; }
.data-source-actions-popper .el-dropdown-menu { padding: 6px; background: transparent; }
.data-source-actions-popper .el-dropdown-menu__item {
  min-height: 38px;
  padding: 0 12px;
  gap: 8px;
  border-radius: 7px;
  color: var(--do-ink);
  font-size: 13px;
  font-weight: 500;
  line-height: 1.4;
}
.data-source-actions-popper .el-dropdown-menu__item > svg { flex: 0 0 auto; width: 17px; height: 17px; }
.data-source-actions-popper .el-dropdown-menu__item:not(.is-disabled):hover,
.data-source-actions-popper .el-dropdown-menu__item:not(.is-disabled):focus {
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
}
.data-source-actions-popper .el-dropdown-menu__item--divided { margin: 6px 0 0; border-top-color: var(--do-line); }
.data-source-actions-popper .el-dropdown-menu__item.data-source-actions__danger { color: var(--do-danger); }
.data-source-actions-popper .el-dropdown-menu__item.data-source-actions__danger:not(.is-disabled):hover,
.data-source-actions-popper .el-dropdown-menu__item.data-source-actions__danger:not(.is-disabled):focus {
  color: var(--do-danger);
  background: var(--do-danger-soft);
}
.data-source-actions-popper .el-dropdown-menu__item.is-disabled {
  color: var(--do-muted);
  background: transparent;
  opacity: .6;
  cursor: not-allowed;
}

.el-dialog.data-source-dialog,
.data-source-dialog .el-dialog { max-width: calc(100vw - 32px); overflow: hidden; border-radius: var(--do-radius-lg); }
.data-source-dialog .el-dialog__header { padding: 16px 20px 12px; margin-right: 0; }
.data-source-dialog .el-dialog__body { max-height: calc(100vh - 250px); max-height: calc(100dvh - 250px); padding: 4px 20px 12px; overflow-y: auto; }
.data-source-dialog .el-dialog__footer { padding: 12px 20px 14px; border-top: 1px solid var(--do-line); background: rgba(255, 255, 255, .6); }
.data-source-dialog .el-button:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 30%, transparent); outline-offset: 2px; }

.data-source-form { display: grid; gap: 14px; }
.data-source-dialog__section { display: grid; gap: 8px; }
.data-source-dialog__section-title { margin: 0; padding-bottom: 6px; border-bottom: 1px solid var(--do-line); color: var(--do-ink); font-size: 13px; font-weight: 700; }
.data-source-dialog__stack { display: grid; gap: 12px; }
.data-source-dialog__row { display: grid; gap: 12px 16px; }
.data-source-dialog__row--main { grid-template-columns: minmax(0, 7fr) minmax(0, 3fr); }
.data-source-dialog__row--credentials { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.data-source-form .el-form-item { margin-bottom: 0; }
.data-source-form .el-form-item__label { padding-bottom: 6px; color: var(--do-muted); font-size: 13px; line-height: 1.4; }
.data-source-form .el-form-item__error { padding-top: 3px; font-size: 11.5px; line-height: 1.4; }
.data-source-form .el-input__wrapper,
.data-source-form .el-select__wrapper { min-height: 36px; }
.data-source-form .el-input__inner,
.data-source-form .el-select__selected-item,
.data-source-form .el-textarea__inner { font-size: 13.5px; }
.data-source-form .el-textarea__inner { line-height: 1.6; }
.data-source-form .el-input,
.data-source-form .el-select,
.data-source-form .el-input-number { width: 100%; }
.data-source-dialog__port .el-input__inner { text-align: left; }

.data-source-dialog__test-error {
  margin: 0;
  padding: 8px 10px;
  border: 1px solid color-mix(in srgb, var(--do-danger) 24%, var(--do-line));
  border-radius: var(--do-radius-sm);
  color: var(--do-danger);
  background: var(--do-danger-soft);
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.data-source-dialog__footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px 16px; }
.data-source-dialog__test { min-width: 0; display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.data-source-dialog__test-status { font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
.data-source-dialog__test-status.is-muted { color: var(--do-muted); }
.data-source-dialog__test-status.is-testing { color: var(--do-primary-strong); }
.data-source-dialog__test-status.is-success { color: var(--do-success); }
.data-source-dialog__test-status.is-warning { color: color-mix(in srgb, var(--do-warning) 68%, var(--do-ink)); }
.data-source-dialog__test-status.is-danger { color: var(--do-danger); }
.data-source-dialog__footer-actions { display: flex; align-items: center; gap: 10px; margin-left: auto; }
.data-source-dialog__footer .el-button { min-height: 36px; }

@media (max-width: 720px) {
  .data-source-dialog__row--main,
  .data-source-dialog__row--credentials { grid-template-columns: minmax(0, 1fr); }
  .data-source-dialog .el-dialog__body { max-height: calc(100vh - 280px); max-height: calc(100dvh - 280px); }
  .data-source-dialog__footer { align-items: stretch; }
  .data-source-dialog__test,
  .data-source-dialog__footer-actions { width: 100%; }
  .data-source-dialog__footer-actions { margin-left: 0; justify-content: flex-end; }
}
</style>
