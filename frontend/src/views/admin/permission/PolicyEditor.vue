<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Trash2 } from 'lucide-vue-next'
import {
  createAccessPolicy,
  deleteAccessPolicy,
  listAccessPolicies,
  updateAccessPolicy,
  type AccessPolicyItem,
  type AccessPolicyPayload,
} from '../../../api/admin/permission'
import { http } from '../../../api/http'
import type { ApiResult } from '../../../api/admin/user'

interface DatasourceOption { id: number; name: string }

const loading = ref(false)
const dialogVisible = ref(false)
const datasources = ref<DatasourceOption[]>([])
const selectedDatasource = ref<number>()
const policies = ref<AccessPolicyItem[]>([])
const tableFilter = ref('')

const accessTypes = [
  { value: 'ALLOW', label: '允许' },
  { value: 'DENY', label: '禁止' },
  { value: 'MASK', label: '脱敏' },
]

const maskStrategies = [
  { value: 'PHONE', label: '手机号' },
  { value: 'ID_CARD', label: '身份证' },
  { value: 'EMAIL', label: '邮箱' },
  { value: 'BANK_CARD', label: '银行卡' },
  { value: 'NAME', label: '姓名' },
]

const subjectTypes = [
  { value: 'USER', label: '用户' },
  { value: 'ROLE', label: '角色' },
  { value: 'DEPARTMENT', label: '部门' },
]

const form = reactive<AccessPolicyPayload>({
  datasourceId: 0,
  subjectType: 'ROLE',
  subjectId: 0,
  tableName: '',
  columnName: '',
  accessType: 'DENY',
  maskStrategy: '',
  rowFilterExpression: '',
})

const roles = ref<Array<{ id: number; name: string }>>([])
const users = ref<Array<{ id: number; name: string }>>([])
const departments = ref<Array<{ id: number; name: string }>>([])

const subjectOptions = computed(() => {
  if (form.subjectType === 'ROLE') return roles.value
  if (form.subjectType === 'USER') return users.value
  return departments.value
})

onMounted(async () => {
  await loadDatasources()
  await loadSubjects()
})

async function loadDatasources() {
  try {
    const { data } = await http.get<ApiResult<DatasourceOption[]>>('/api/admin/datasources/simple')
    datasources.value = data.data || []
    if (datasources.value.length > 0 && !selectedDatasource.value) {
      selectedDatasource.value = datasources.value[0].id
      await loadPolicies()
    }
  } catch { /* ignore */ }
}

async function loadSubjects() {
  try {
    const [rolesRes, usersRes, deptsRes] = await Promise.all([
      http.get<ApiResult<any[]>>('/api/admin/roles'),
      http.get<ApiResult<any>>('/api/admin/users', { params: { pageSize: 200 } }),
      http.get<ApiResult<any[]>>('/api/admin/departments'),
    ])
    roles.value = (rolesRes.data.data || []).map((r: any) => ({ id: r.id, name: r.roleName }))
    const userList = usersRes.data.data?.records || usersRes.data.data || []
    users.value = userList.map((u: any) => ({ id: u.id, name: u.realName || u.username }))
    departments.value = (deptsRes.data.data || []).map((d: any) => ({ id: d.id, name: d.deptName }))
  } catch { /* ignore */ }
}

async function loadPolicies() {
  if (!selectedDatasource.value) return
  loading.value = true
  try {
    const res = await listAccessPolicies(selectedDatasource.value, undefined, undefined, tableFilter.value || undefined)
    policies.value = res.data || []
  } catch { ElMessage.error('加载策略列表失败') }
  finally { loading.value = false }
}

function openCreateDialog() {
  form.datasourceId = selectedDatasource.value || 0
  form.subjectType = 'ROLE'
  form.subjectId = 0
  form.tableName = ''
  form.columnName = ''
  form.accessType = 'DENY'
  form.maskStrategy = ''
  form.rowFilterExpression = ''
  dialogVisible.value = true
}

async function handleCreate() {
  if (!form.subjectId || !form.tableName) {
    ElMessage.warning('请填写必要信息')
    return
  }
  try {
    await createAccessPolicy(form)
    ElMessage.success('策略创建成功')
    dialogVisible.value = false
    await loadPolicies()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '创建失败')
  }
}

async function handleDelete(row: AccessPolicyItem) {
  await ElMessageBox.confirm('确定删除该策略？', '确认删除')
  try {
    await deleteAccessPolicy(row.id)
    ElMessage.success('已删除')
    await loadPolicies()
  } catch { ElMessage.error('删除失败') }
}

function accessTypeTag(type: string) {
  if (type === 'DENY') return 'danger'
  if (type === 'MASK') return 'warning'
  return 'success'
}

function accessTypeLabel(type: string) {
  return accessTypes.find(t => t.value === type)?.label || type
}
</script>

<template>
  <main class="policy-editor-page post-login-page">
    <header class="page-header">
      <div>
        <p>安全管理</p>
        <h1>策略编辑器</h1>
        <span class="header-subtitle">配置行列级访问控制和脱敏策略</span>
      </div>
      <el-button type="primary" @click="openCreateDialog">
        <Plus :size="16" style="margin-right: 6px" />新增策略
      </el-button>
    </header>

    <section class="toolbar">
      <el-select v-model="selectedDatasource" placeholder="选择数据源" style="width: 240px" @change="loadPolicies">
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
      <el-input v-model="tableFilter" placeholder="按表名筛选" clearable style="width: 180px; margin-left: 12px"
                @clear="loadPolicies" @keyup.enter="loadPolicies" />
    </section>

    <section class="content-panel">
      <el-table :data="policies" v-loading="loading" stripe>
        <el-table-column prop="subjectName" label="主体" min-width="120">
          <template #default="{ row }">
            <el-tag size="small" type="info" style="margin-right: 6px">{{ row.subjectType }}</el-tag>
            {{ row.subjectName }}
          </template>
        </el-table-column>
        <el-table-column prop="tableName" label="表名" width="140" />
        <el-table-column prop="columnName" label="列名" width="130">
          <template #default="{ row }">{{ row.columnName || '(表级)' }}</template>
        </el-table-column>
        <el-table-column label="访问类型" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="accessTypeTag(row.accessType)" size="small">{{ accessTypeLabel(row.accessType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="maskStrategy" label="脱敏策略" width="100">
          <template #default="{ row }">{{ row.maskStrategy || '-' }}</template>
        </el-table-column>
        <el-table-column prop="rowFilterExpression" label="行级过滤" min-width="180">
          <template #default="{ row }">
            <code v-if="row.rowFilterExpression" style="font-size: 12px">{{ row.rowFilterExpression }}</code>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ row }">
            <el-button type="danger" link size="small" @click="handleDelete(row)">
              <Trash2 :size="14" />
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && policies.length === 0" description="暂无策略配置" />
    </section>

    <el-dialog v-model="dialogVisible" title="新增访问策略" width="540px">
      <el-form label-width="90px">
        <el-form-item label="主体类型">
          <el-radio-group v-model="form.subjectType">
            <el-radio-button v-for="t in subjectTypes" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="选择主体">
          <el-select v-model="form.subjectId" filterable placeholder="请选择" style="width: 100%">
            <el-option v-for="opt in subjectOptions" :key="opt.id" :label="opt.name" :value="opt.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="表名">
          <el-input v-model="form.tableName" placeholder="如 orders, customers" />
        </el-form-item>
        <el-form-item label="列名">
          <el-input v-model="form.columnName" placeholder="留空表示表级策略" />
        </el-form-item>
        <el-form-item label="访问类型">
          <el-radio-group v-model="form.accessType">
            <el-radio-button v-for="t in accessTypes" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.accessType === 'MASK'" label="脱敏策略">
          <el-select v-model="form.maskStrategy" placeholder="选择脱敏方式" style="width: 100%">
            <el-option v-for="s in maskStrategies" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="行级过滤">
          <el-input v-model="form.rowFilterExpression" placeholder="如 region = '华东'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreate">确定</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.policy-editor-page { padding: 0; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; }
.page-header p { font-size: 12px; color: var(--do-muted); margin: 0 0 4px; }
.page-header h1 { font-size: 22px; margin: 0; color: var(--do-ink); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }
.toolbar { margin-bottom: 16px; display: flex; align-items: center; }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; box-shadow: var(--do-shadow); }
</style>
