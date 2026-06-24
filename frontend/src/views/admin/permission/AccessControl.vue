<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Trash2 } from 'lucide-vue-next'
import {
  getDatasourcePermissionDecision,
  grantDatasourcePermission,
  listDatasourcePermissions,
  revokeDatasourcePermission,
  updateDatasourcePermission,
  type DatasourcePermissionDecision,
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
const decisionLoading = ref(false)
const decisionUserId = ref<number>()
const decision = ref<DatasourcePermissionDecision>()

const form = reactive<DatasourcePermissionPayload>({
  datasourceId: 0,
  subjectType: 'ROLE',
  subjectId: 0,
  accessEffect: 'ALLOW',
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

const decisionSourceText = computed(() => {
  if (!decision.value) return '未计算'
  const labels: Record<string, string> = {
    USER: '用户直接授权',
    ROLE: '角色授权',
    DEPARTMENT: '部门授权',
    NONE: '无有效授权',
    '*': '超级管理员权限',
  }
  return labels[decision.value.decisionSource] || decision.value.decisionSource
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
  decision.value = undefined
  try {
    const res = await listDatasourcePermissions(selectedDatasource.value, subjectFilter.value || undefined)
    permissions.value = res.data || []
  } catch { ElMessage.error('加载授权列表失败') }
  finally { loading.value = false }
}

async function previewDecision() {
  if (!selectedDatasource.value || !decisionUserId.value) {
    ElMessage.warning('请选择数据源和用户')
    return
  }
  decisionLoading.value = true
  try {
    const res = await getDatasourcePermissionDecision(selectedDatasource.value, decisionUserId.value)
    decision.value = res.data
  } catch (error: any) {
    decision.value = undefined
    ElMessage.error(error.response?.data?.message || '权限决策计算失败')
  } finally {
    decisionLoading.value = false
  }
}

function openGrantDialog() {
  form.datasourceId = selectedDatasource.value || 0
  form.subjectType = 'ROLE'
  form.subjectId = 0
  form.accessEffect = 'ALLOW'
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

async function handleEffectChange(row: DatasourcePermissionItem, effect: 'ALLOW' | 'DENY') {
  try {
    await updateDatasourcePermission(row.id, { accessEffect: effect })
    row.accessEffect = effect
  } catch { ElMessage.error('更新授权效果失败') }
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
    <section class="page-actions">
      <el-button type="primary" @click="openGrantDialog">
        <Plus :size="16" style="margin-right: 6px" />新增授权
      </el-button>
    </section>

    <section class="toolbar">
      <el-select v-model="selectedDatasource" placeholder="选择数据源" style="width: 240px" @change="loadPermissions">
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
      <el-select v-model="subjectFilter" placeholder="全部类型" clearable style="width: 140px; margin-left: 12px" @change="loadPermissions">
        <el-option v-for="t in subjectTypes" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
    </section>

    <section class="decision-panel">
      <div class="decision-controls">
        <strong>最终权限预览</strong>
        <el-select v-model="decisionUserId" filterable placeholder="选择用户" style="width: 220px">
          <el-option v-for="user in users" :key="user.id" :label="user.name" :value="user.id" />
        </el-select>
        <el-button type="primary" :loading="decisionLoading" @click="previewDecision">计算权限</el-button>
      </div>
      <div v-if="decision" class="decision-result">
        <el-tag :type="decision.accessEffect === 'DENY' ? 'danger' : decision.canQuery ? 'success' : 'info'">
          {{ decision.accessEffect }}
        </el-tag>
        <span>来源：{{ decisionSourceText }}</span>
        <span>查询：{{ decision.canQuery ? '允许' : '禁止' }}</span>
        <span>导出：{{ decision.canExport ? '允许' : '禁止' }}</span>
        <span>SQL：{{ decision.canViewSql ? '可见' : '隐藏' }}</span>
      </div>
      <div v-else class="decision-empty">选择一个用户后可查看部门、角色、用户直接授权合并后的最终结果。</div>
    </section>

    <section class="role-matrix">
      <article>
        <strong>业务查询用户</strong>
        <span>只使用已授权数据源，必要时申请访问。</span>
      </article>
      <article>
        <strong>数据管理员</strong>
        <span>维护数据源连接、采集、快照和表字段质量。</span>
      </article>
      <article>
        <strong>数据安全管理员</strong>
        <span>配置数据源授权、行列策略、脱敏和审批。</span>
      </article>
      <article>
        <strong>治理负责人</strong>
        <span>关注上线流程、阻塞事项、发布和跨角色协同。</span>
      </article>
    </section>

    <section class="content-panel">
      <el-table :data="filteredPermissions" v-loading="loading" stripe>
        <el-table-column label="主体类型" width="100">
          <template #default="{ row }">{{ subjectTypeLabel(row.subjectType) }}</template>
        </el-table-column>
        <el-table-column prop="subjectName" label="主体名称" min-width="140" />
        <el-table-column label="查询" width="80" align="center">
          <template #default="{ row }">
            <el-select :model-value="row.accessEffect || 'ALLOW'" size="small" style="display: block; width: 72px; margin: 0 auto 6px" @change="(value: 'ALLOW' | 'DENY') => handleEffectChange(row, value)">
              <el-option label="ALLOW" value="ALLOW" />
              <el-option label="DENY" value="DENY" />
            </el-select>
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
        <el-form-item label="Effect">
          <el-radio-group v-model="form.accessEffect">
            <el-radio-button value="ALLOW">ALLOW</el-radio-button>
            <el-radio-button value="DENY">DENY</el-radio-button>
          </el-radio-group>
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
.toolbar { margin-bottom: 16px; display: flex; align-items: center; }
.decision-panel {
  display: grid;
  gap: 12px;
  margin-bottom: 16px;
  padding: 14px 16px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}
.decision-controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.decision-controls strong {
  color: var(--do-ink);
  font-size: 15px;
}
.decision-result {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  color: #475569;
  font-size: 13px;
}
.decision-empty {
  color: var(--do-muted);
  font-size: 13px;
}
.role-matrix {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}
.role-matrix article {
  min-width: 0;
  display: grid;
  gap: 5px;
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: #fff;
}
.role-matrix strong,
.role-matrix span {
  overflow: hidden;
  text-overflow: ellipsis;
}
.role-matrix strong {
  color: var(--do-ink);
  font-size: 13px;
  white-space: nowrap;
}
.role-matrix span {
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.5;
}
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; box-shadow: var(--do-shadow); }
@media (max-width: 1100px) {
  .role-matrix { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 640px) {
  .role-matrix { grid-template-columns: 1fr; }
}
</style>
