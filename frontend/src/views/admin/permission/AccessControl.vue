<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Trash2 } from 'lucide-vue-next'
import {
  grantDatasourcePermission,
  listDatasourcePermissions,
  revokeDatasourcePermission,
  updateDatasourcePermission,
  type DatasourcePermissionItem,
  type DatasourcePermissionPayload,
} from '../../../api/admin/permission'
import { http } from '../../../api/http'
import { listDepartments, type ApiResult, type DepartmentNode } from '../../../api/admin/user'

interface DatasourceOption { id: number; name: string }
interface SubjectOption { id: number; name: string; type: string }

/** 将部门树拍平为一维列表，供主体下拉选择 */
function flattenDepartments(nodes: DepartmentNode[]): Array<{ id: number; name: string }> {
  const result: Array<{ id: number; name: string }> = []
  const walk = (list: DepartmentNode[]) => {
    for (const node of list) {
      result.push({ id: node.id, name: node.deptName })
      if (node.children && node.children.length > 0) {
        walk(node.children)
      }
    }
  }
  walk(nodes)
  return result
}

const loading = ref(false)
const dialogVisible = ref(false)
const datasources = ref<DatasourceOption[]>([])
const selectedDatasource = ref<number>()
const permissions = ref<DatasourcePermissionItem[]>([])
const subjectFilter = ref('')

const form = reactive<DatasourcePermissionPayload>({
  datasourceId: 0,
  subjectType: 'ROLE',
  subjectId: 0,
  canQuery: true,
  canExport: false,
  canViewSql: true,
})

const subjectTypes = [
  { value: 'USER', label: '用户' },
  { value: 'ROLE', label: '角色' },
  { value: 'DEPARTMENT', label: '部门' },
]

const roles = ref<SubjectOption[]>([])
const users = ref<SubjectOption[]>([])
const departments = ref<SubjectOption[]>([])

const subjectOptions = computed(() => {
  if (form.subjectType === 'ROLE') return roles.value
  if (form.subjectType === 'USER') return users.value
  return departments.value
})

const filteredPermissions = computed(() => {
  if (!subjectFilter.value) return permissions.value
  return permissions.value.filter(p => p.subjectType === subjectFilter.value)
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
      await loadPermissions()
    }
  } catch { /* ignore */ }
}

async function loadSubjects() {
  try {
    const [rolesRes, usersRes, deptTree] = await Promise.all([
      http.get<ApiResult<any[]>>('/api/admin/roles'),
      http.get<ApiResult<any>>('/api/admin/users', { params: { pageSize: 200 } }),
      listDepartments(),
    ])
    roles.value = (rolesRes.data.data || []).map((r: any) => ({ id: r.id, name: r.roleName, type: 'ROLE' }))
    const userList = usersRes.data.data?.records || usersRes.data.data || []
    users.value = userList.map((u: any) => ({ id: u.id, name: u.realName || u.username, type: 'USER' }))
    departments.value = flattenDepartments(deptTree.data || []).map(d => ({ id: d.id, name: d.name, type: 'DEPARTMENT' }))
  } catch {
    ElMessage.error('加载授权主体（用户/角色/部门）失败，请检查权限或稍后重试')
  }
}

async function loadPermissions() {
  if (!selectedDatasource.value) return
  loading.value = true
  try {
    const res = await listDatasourcePermissions(selectedDatasource.value, subjectFilter.value || undefined)
    permissions.value = res.data || []
  } catch { ElMessage.error('加载授权列表失败') }
  finally { loading.value = false }
}

function openGrantDialog() {
  form.datasourceId = selectedDatasource.value || 0
  form.subjectType = 'ROLE'
  form.subjectId = 0
  form.canQuery = true
  form.canExport = false
  form.canViewSql = true
  dialogVisible.value = true
}

async function handleGrant() {
  if (!form.subjectId) { ElMessage.warning('请选择授权主体'); return }
  try {
    await grantDatasourcePermission(form)
    ElMessage.success('授权成功')
    dialogVisible.value = false
    await loadPermissions()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '授权失败')
  }
}

async function handleToggle(row: DatasourcePermissionItem, field: 'canQuery' | 'canExport' | 'canViewSql') {
  try {
    await updateDatasourcePermission(row.id, { [field]: !row[field] })
    row[field] = !row[field]
  } catch { ElMessage.error('更新失败') }
}

async function handleRevoke(row: DatasourcePermissionItem) {
  await ElMessageBox.confirm(`确定撤销 ${row.subjectName} 的授权？`, '确认撤销')
  try {
    await revokeDatasourcePermission(row.id)
    ElMessage.success('已撤销')
    await loadPermissions()
  } catch { ElMessage.error('撤销失败') }
}

function subjectTypeLabel(type: string) {
  return subjectTypes.find(t => t.value === type)?.label || type
}
</script>

<template>
  <main class="access-control-page post-login-page">
    <header class="page-header">
      <div>
        <p>安全管理</p>
        <h1>访问控制</h1>
        <span class="header-subtitle">管理数据源级别的访问授权</span>
      </div>
      <el-button type="primary" @click="openGrantDialog">
        <Plus :size="16" style="margin-right: 6px" />新增授权
      </el-button>
    </header>

    <section class="toolbar">
      <el-select v-model="selectedDatasource" placeholder="选择数据源" style="width: 240px" @change="loadPermissions">
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
      <el-select v-model="subjectFilter" placeholder="全部类型" clearable style="width: 140px; margin-left: 12px" @change="loadPermissions">
        <el-option v-for="t in subjectTypes" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
    </section>

    <section class="content-panel">
      <el-table :data="filteredPermissions" v-loading="loading" stripe>
        <el-table-column label="主体类型" width="100">
          <template #default="{ row }">{{ subjectTypeLabel(row.subjectType) }}</template>
        </el-table-column>
        <el-table-column prop="subjectName" label="主体名称" min-width="140" />
        <el-table-column label="查询" width="80" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.canQuery" size="small" @change="handleToggle(row, 'canQuery')" />
          </template>
        </el-table-column>
        <el-table-column label="导出" width="80" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.canExport" size="small" @change="handleToggle(row, 'canExport')" />
          </template>
        </el-table-column>
        <el-table-column label="查看SQL" width="90" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.canViewSql" size="small" @change="handleToggle(row, 'canViewSql')" />
          </template>
        </el-table-column>
        <el-table-column prop="grantedAt" label="授权时间" width="170" />
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ row }">
            <el-button type="danger" link size="small" @click="handleRevoke(row)">
              <Trash2 :size="14" />
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && filteredPermissions.length === 0" description="暂无授权记录" />
    </section>

    <el-dialog v-model="dialogVisible" title="新增数据源授权" width="480px">
      <el-form label-width="80px">
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
        <el-form-item label="权限">
          <el-checkbox v-model="form.canQuery">查询</el-checkbox>
          <el-checkbox v-model="form.canExport">导出</el-checkbox>
          <el-checkbox v-model="form.canViewSql">查看SQL</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleGrant">确定</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.access-control-page { padding: 0; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; }
.page-header p { font-size: 12px; color: var(--do-muted); margin: 0 0 4px; }
.page-header h1 { font-size: 22px; margin: 0; color: var(--do-ink); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }
.toolbar { margin-bottom: 16px; display: flex; align-items: center; }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; box-shadow: var(--do-shadow); }
</style>
