<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Trash2 } from 'lucide-vue-next'
import {
  createAccessPolicy,
  deleteAccessPolicy,
  listAccessPolicies,
  type AccessPolicyItem,
  type AccessPolicyPayload,
} from '../../../api/admin/permission'
import { http } from '../../../api/http'
import { listDepartments, type ApiResult, type DepartmentNode } from '../../../api/admin/user'
import ResourceScopeSelector from '../../../components/ResourceScopeSelector.vue'
import { useAdminContextStore } from '../../../stores/adminContext'

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
const selectedDatasource = ref<number>()
const selectedSnapshot = ref<number>()
const policySnapshot = ref<number>()
const policies = ref<AccessPolicyItem[]>([])
const tableFilter = ref('')
const adminContext = useAdminContextStore()

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
  await adminContext.initialize()
  selectedDatasource.value = adminContext.datasourceId
  selectedSnapshot.value = adminContext.snapshotId
  await loadPolicies()
  await loadSubjects()
})

async function loadSubjects() {
  try {
    const [rolesRes, usersRes, deptTree] = await Promise.all([
      http.get<ApiResult<any[]>>('/api/admin/roles'),
      http.get<ApiResult<any>>('/api/admin/users', { params: { pageSize: 200 } }),
      listDepartments(),
    ])
    roles.value = (rolesRes.data.data || []).map((r: any) => ({ id: r.id, name: r.roleName }))
    const userList = usersRes.data.data?.records || usersRes.data.data || []
    users.value = userList.map((u: any) => ({ id: u.id, name: u.realName || u.username }))
    departments.value = flattenDepartments(deptTree.data || []).map(d => ({ id: d.id, name: d.name }))
  } catch {
    ElMessage.error('加载授权主体（用户/角色/部门）失败，请检查权限或稍后重试')
  }
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
  policySnapshot.value = selectedSnapshot.value
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
  form.datasourceId = selectedDatasource.value || 0
  if (!form.datasourceId || !form.subjectId || !form.tableName) {
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
    <section class="page-actions">
      <el-button type="primary" @click="openCreateDialog">
        <Plus :size="16" style="margin-right: 6px" />新增策略
      </el-button>
    </section>

    <section class="toolbar">
      <ResourceScopeSelector
        v-model:datasource-id="selectedDatasource"
        v-model:snapshot-id="selectedSnapshot"
        v-model:table-name="tableFilter"
        mode="table"
        include-all-table-option
        all-table-label="全部表"
        @change="loadPolicies"
      />
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
          <ResourceScopeSelector
            v-model:datasource-id="selectedDatasource"
            v-model:snapshot-id="policySnapshot"
            v-model:table-name="form.tableName"
            v-model:column-name="form.columnName"
            mode="column"
            :show-datasource="false"
          />
        </el-form-item>
        <p class="form-hint">字段留空时表示表级策略；选择字段后可配置字段级禁止或脱敏。</p>
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
.toolbar { margin-bottom: 16px; display: flex; align-items: center; }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; box-shadow: var(--do-shadow); }
.form-hint { margin: -8px 0 12px 90px; color: var(--do-muted); font-size: 12px; line-height: 1.5; }
</style>
