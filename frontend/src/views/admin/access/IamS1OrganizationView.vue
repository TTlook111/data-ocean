<script setup lang="ts">
/**
 * IAM-SIMPLE-1 角色与负责源工作区
 *
 * 三个 Tab：角色 / 用户角色与负责源 / 功能目录。
 * 角色首屏使用中文名称、作用和能力摘要；技术码只在排查详情折叠展示；
 * 功能目录只读，不提供任意字符串功能码的新增入口。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '../../../components/common/EmptyState.vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import { useIamS1Store } from '../../../stores/iamS1'
import {
  assignIamS1UserRole,
  bindIamS1Datasources,
  createIamS1Role,
  deleteIamS1Role,
  disableIamS1UserRole,
  listIamS1BindingDatasources,
  listIamS1Datasources,
  listIamS1Functions,
  listIamS1RoleTemplates,
  listIamS1Roles,
  listIamS1Subjects,
  listIamS1UserRoles,
  removeIamS1UserRole,
  updateIamS1Role,
  type IamS1DatasourceRef,
  type IamS1FunctionCatalogItem,
  type IamS1Role,
  type IamS1RoleTemplate,
  type IamS1SubjectOption,
  type IamS1UserRoleBinding,
} from '../../../api/iamS1'

const iamS1 = useIamS1Store()
const activeTab = ref<'roles' | 'bindings' | 'catalog'>('roles')

const roles = ref<IamS1Role[]>([])
const templates = ref<IamS1RoleTemplate[]>([])
const functions = ref<IamS1FunctionCatalogItem[]>([])
const datasources = ref<IamS1DatasourceRef[]>([])
const users = ref<IamS1SubjectOption[]>([])
const bindings = ref<IamS1UserRoleBinding[]>([])
const selectedUserId = ref<number>()
const assignRoleId = ref<number>()
const savingRole = ref(false)
const editingRoleId = ref<number>()
const bindingDatasourceDraft = reactive<Record<number, number[]>>({})

const roleForm = reactive({
  roleCode: '',
  roleName: '',
  description: '',
  functionCodes: [] as string[],
  reason: '',
})

const roleFormVisible = ref(false)
const canViewRoles = computed(() => iamS1.hasGlobal('organization:role:view'))
const canManageRoles = computed(() => iamS1.hasGlobal('organization:role:manage'))
const canViewCatalog = computed(() => iamS1.hasGlobal('organization:permission:view'))
const canManageBindings = computed(() => iamS1.systemAdmin)

const catalogDomains = computed(() => {
  const groups = new Map<string, IamS1FunctionCatalogItem[]>()
  for (const item of functions.value) {
    const list = groups.get(item.domain) ?? []
    list.push(item)
    groups.set(item.domain, list)
  }
  return Array.from(groups.entries()).map(([domain, items]) => ({ domain, items }))
})

function isIncludedByDependency(code: string): boolean {
  return roleForm.functionCodes.some(
    (selected) => selected !== code && functions.value.find((item) => item.code === selected)?.dependencies.includes(code),
  )
}

function applyTemplate(template: IamS1RoleTemplate) {
  roleForm.roleCode = (roleForm.roleCode || template.code).toUpperCase()
  roleForm.roleName = template.name
  roleForm.description = template.description
  roleForm.functionCodes = [...template.functionCodes]
  roleFormVisible.value = true
  ElMessage.info(`已套用模板：${template.name}（${template.capabilitySummary}）`)
}

function startEdit(role: IamS1Role) {
  editingRoleId.value = role.id
  roleForm.roleCode = role.roleCode
  roleForm.roleName = role.roleName
  roleForm.description = role.description ?? ''
  roleForm.functionCodes = [...role.functionCodes]
  roleForm.reason = ''
  roleFormVisible.value = true
}

function resetRoleForm() {
  editingRoleId.value = undefined
  roleForm.roleCode = ''
  roleForm.roleName = ''
  roleForm.description = ''
  roleForm.functionCodes = []
  roleForm.reason = ''
}

async function loadRoles() {
  if (!canViewRoles.value) return
  const result = await listIamS1Roles()
  roles.value = result.data ?? []
}

async function loadTemplates() {
  const result = await listIamS1RoleTemplates()
  templates.value = result.data ?? []
}

async function loadFunctions() {
  if (!canViewCatalog.value) return
  const result = await listIamS1Functions()
  functions.value = result.data ?? []
}

async function loadDatasources() {
  const result = await listIamS1Datasources()
  datasources.value = result.data ?? []
}

async function loadUsers() {
  const result = await listIamS1Subjects('USER')
  users.value = result.data ?? []
}

async function loadBindings() {
  if (!selectedUserId.value) {
    bindings.value = []
    return
  }
  try {
    const result = await listIamS1UserRoles(selectedUserId.value)
    bindings.value = result.data ?? []
    for (const binding of bindings.value) {
      const detail = await listIamS1BindingDatasources(binding.userRoleId)
      bindingDatasourceDraft[binding.userRoleId] = (detail.data ?? []).map((item) => item.id)
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取用户角色失败')
  }
}

async function saveRole() {
  if (!roleForm.roleCode.trim() || !roleForm.roleName.trim()) {
    ElMessage.warning('请填写角色编码和中文名称')
    return
  }
  if (!roleForm.functionCodes.length) {
    ElMessage.warning('请至少选择一个功能，否则该角色无法进入任何业务工作区')
    return
  }
  savingRole.value = true
  try {
    const payload = {
      roleCode: roleForm.roleCode.trim().toUpperCase(),
      roleName: roleForm.roleName.trim(),
      description: roleForm.description,
      status: 1,
      functionCodes: roleForm.functionCodes,
      reason: roleForm.reason || undefined,
    }
    if (editingRoleId.value) {
      await updateIamS1Role(editingRoleId.value, payload)
      ElMessage.success('角色已更新')
    } else {
      await createIamS1Role(payload)
      ElMessage.success('角色已创建')
    }
    resetRoleForm()
    roleFormVisible.value = false
    await loadRoles()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存角色失败')
  } finally {
    savingRole.value = false
  }
}

async function removeRole(role: IamS1Role) {
  try {
    await ElMessageBox.confirm(`删除角色「${role.roleName}」后，其成员将失去该角色的能力，确认删除吗？`, '删除角色', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteIamS1Role(role.id, '界面删除角色')
    ElMessage.success('角色已删除')
    await loadRoles()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除角色失败')
  }
}

async function assignRole(roleId?: number) {
  if (!selectedUserId.value || !roleId) {
    ElMessage.warning('请选择用户和角色')
    return
  }
  try {
    await assignIamS1UserRole(selectedUserId.value, roleId, '界面分配角色')
    ElMessage.success('角色已分配')
    await loadBindings()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '分配角色失败')
  }
}

async function removeBinding(binding: IamS1UserRoleBinding) {
  if (!selectedUserId.value) return
  try {
    await removeIamS1UserRole(selectedUserId.value, binding.roleId, '界面解除角色')
    ElMessage.success('角色已解除')
    await loadBindings()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '解除角色失败')
  }
}

async function disableBinding(binding: IamS1UserRoleBinding) {
  if (!selectedUserId.value) return
  try {
    await disableIamS1UserRole(selectedUserId.value, binding.roleId, '界面停用角色绑定')
    ElMessage.success('角色绑定已停用')
    await loadBindings()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '停用角色绑定失败')
  }
}

async function saveBindingDatasources(binding: IamS1UserRoleBinding) {
  const ids = bindingDatasourceDraft[binding.userRoleId] ?? []
  if (!ids.length) {
    ElMessage.warning('至少需要一个后台负责数据源')
    return
  }
  try {
    await bindIamS1Datasources(binding.userRoleId, ids, '界面维护后台负责源')
    ElMessage.success('后台负责数据源已保存')
    await loadBindings()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存负责数据源失败')
  }
}

onMounted(async () => {
  await iamS1.load()
  try {
    await Promise.all([loadRoles(), loadTemplates(), loadFunctions(), loadDatasources(), loadUsers()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '初始化角色工作区失败')
  }
})
</script>

<template>
  <div class="iam-s1-org">
    <TaskPageHeader
      title="角色与负责源"
      description="角色管功能，负责源只限制后台工作范围，不授予业务数据查询权。功能组合使用固定中文目录，不能创建任意功能码。"
    />

    <el-tabs v-model="activeTab">
      <el-tab-pane label="角色" name="roles">
        <EmptyState
          v-if="!canViewRoles"
          message="没有“查看角色”权限：需要 IAM-SIMPLE-1 角色包含“查看角色”。"
        />
        <template v-else>
          <section class="panel">
            <h3 class="panel-title">常用角色模板</h3>
            <p class="hint">模板只帮助勾选功能，创建后是普通角色，没有按名字识别的特权，也不附送数据授权。</p>
            <div class="template-grid">
              <button
                v-for="template in templates"
                :key="template.code"
                class="template-card"
                :disabled="!canManageRoles"
                @click="applyTemplate(template)"
              >
                <strong>{{ template.name }}</strong>
                <span>{{ template.description }}</span>
                <small>已获得：{{ template.capabilitySummary }}</small>
                <small>数据：{{ template.dataHint }}</small>
              </button>
            </div>
          </section>

          <section v-if="canManageRoles" class="panel">
            <h3 class="panel-title">{{ editingRoleId ? '编辑角色' : '新建角色' }}</h3>
            <div class="inline">
              <el-input v-model="roleForm.roleCode" placeholder="角色编码（英文/数字）" class="w200" />
              <el-input v-model="roleForm.roleName" placeholder="中文名称，例如 业务分析人员" class="w240" />
              <el-input v-model="roleForm.description" placeholder="用途说明" class="w280" />
              <el-button type="primary" :loading="savingRole" @click="saveRole">保存角色</el-button>
              <el-button v-if="editingRoleId" @click="resetRoleForm(); roleFormVisible = false">取消编辑</el-button>
            </div>
            <div v-if="roleFormVisible" class="function-matrix">
              <div v-for="group in catalogDomains" :key="group.domain" class="matrix-group">
                <h4>{{ group.domain }}</h4>
                <label v-for="item in group.items" :key="item.code" class="matrix-row">
                  <el-checkbox v-model="roleForm.functionCodes" :value="item.code">
                    <span class="check-name">{{ item.name }}</span>
                    <small class="check-desc">{{ item.description }}</small>
                    <small v-if="item.systemAdminOnly" class="tag-lock">仅系统管理员可配置</small>
                    <small v-else-if="isIncludedByDependency(item.code)" class="tag-included">已随维护权限包含</small>
                  </el-checkbox>
                </label>
              </div>
            </div>
            <p v-else class="hint">选择上方模板或点击“新建角色”展开中文功能矩阵。</p>
          </section>

          <section class="panel">
            <h3 class="panel-title">角色列表（{{ roles.length }}）</h3>
            <EmptyState v-if="!roles.length" message="暂无角色数据。可以先套用上方模板创建第一个普通角色。" />
            <el-table v-else :data="roles" size="small">
              <el-table-column prop="roleName" label="中文名称" width="180" />
              <el-table-column prop="capabilitySummary" label="能力摘要" min-width="320" />
              <el-table-column prop="memberCount" label="成员" width="80" />
              <el-table-column label="类型" width="120">
                <template #default="{ row }">
                  {{ row.protectedRole ? '系统管理员（受保护）' : '普通角色' }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="160">
                <template #default="{ row }">
                  <template v-if="canManageRoles && !row.protectedRole">
                    <el-button link @click="startEdit(row)">编辑</el-button>
                    <el-button link type="danger" @click="removeRole(row)">删除</el-button>
                  </template>
                  <span v-else class="muted">系统管理员由服务端固定维护</span>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </template>
      </el-tab-pane>

      <el-tab-pane label="用户角色与负责源" name="bindings">
        <section class="panel">
          <EmptyState
            v-if="!canManageBindings"
            message="后台角色与负责数据源只能由 IAM-SIMPLE-1 系统管理员分配；如需调整请联系系统管理员。"
          />
          <template v-else>
            <div class="inline">
              <el-select v-model="selectedUserId" filterable placeholder="选择用户" class="w240" @change="loadBindings">
                <el-option v-for="item in users" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
              <el-select
                v-model="assignRoleId"
                placeholder="分配角色"
                class="w320"
                @change="(value: number) => assignRole(value)"
              >
                <el-option
                  v-for="role in roles.filter((item) => !item.protectedRole)"
                  :key="role.id"
                  :label="`${role.roleName}（${role.capabilitySummary}）`"
                  :value="role.id"
                />
              </el-select>
            </div>

            <EmptyState v-if="!bindings.length" message="该用户还没有 IAM-SIMPLE-1 角色绑定。请分配角色并选择它负责的数据源。" />
            <div v-for="binding in bindings" :key="binding.userRoleId" class="binding-block">
              <h4>
                {{ binding.roleName }}
                <small class="muted">{{ binding.capabilitySummary }}</small>
              </h4>
              <p class="hint">
                {{ binding.responsibleDatasourceSummary }}（负责源只限制后台范围，<strong>不代表能查询这些数据</strong>）
              </p>
              <div class="inline">
                <el-select
                  v-model="bindingDatasourceDraft[binding.userRoleId]"
                  multiple
                  filterable
                  placeholder="负责数据源"
                  class="w320"
                >
                  <el-option v-for="item in datasources" :key="item.id" :label="item.name" :value="item.id" />
                </el-select>
                <el-button size="small" @click="saveBindingDatasources(binding)">保存负责源</el-button>
                <el-button size="small" type="warning" @click="disableBinding(binding)">停用绑定</el-button>
                <el-button size="small" type="danger" @click="removeBinding(binding)">解除角色</el-button>
              </div>
            </div>
          </template>
        </section>
      </el-tab-pane>

      <el-tab-pane label="功能目录" name="catalog">
        <section class="panel">
          <EmptyState
            v-if="!canViewCatalog"
            message="没有“查看功能说明”权限：需要 IAM-SIMPLE-1 角色包含“查看功能说明”。"
          />
          <template v-else>
            <p class="hint">
              固定 {{ functions.length }} 项功能，只能由前向升级脚本初始化；本页只读，不提供新增或修改功能点的入口。
            </p>
            <div v-for="group in catalogDomains" :key="group.domain" class="matrix-group">
              <h4>{{ group.domain }}</h4>
              <el-table :data="group.items" size="small">
                <el-table-column prop="name" label="中文名称" width="200" />
                <el-table-column prop="description" label="勾选后可以做什么" min-width="320" />
                <el-table-column prop="workspace" label="工作区" width="140" />
                <el-table-column label="依赖" width="200">
                  <template #default="{ row }">
                    {{ row.dependencies.length ? row.dependencies.join('、') : '无' }}
                  </template>
                </el-table-column>
                <el-table-column label="配置限制" width="180">
                  <template #default="{ row }">
                    <span v-if="row.systemAdminOnly">仅系统管理员可配置</span>
                    <span v-else>普通角色可配置</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </section>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.iam-s1-org {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.panel {
  border: 1px solid var(--do-line);
  border-radius: 10px;
  padding: 18px;
  background: var(--do-surface);
  margin-top: 8px;
}
.panel-title {
  margin: 0 0 10px;
  font-size: 15px;
}
.inline {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}
.template-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  background: var(--do-surface);
  text-align: left;
  cursor: pointer;
}
.template-card:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.template-card span,
.template-card small {
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
}
.function-matrix {
  margin-top: 14px;
  max-height: 420px;
  overflow: auto;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  padding: 12px;
}
.matrix-group h4 {
  margin: 12px 0 6px;
  font-size: 13px;
}
.matrix-row {
  display: block;
}
.check-name {
  font-size: 13px;
}
.check-desc {
  display: block;
  color: var(--do-muted);
  font-size: 12px;
}
.tag-lock {
  color: #d4380d;
  font-size: 12px;
}
.tag-included {
  color: var(--do-muted);
  font-size: 12px;
}
.binding-block {
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
}
.binding-block h4 {
  margin: 0 0 6px;
  font-size: 14px;
}
.hint {
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
  margin: 6px 0;
}
.muted {
  color: var(--do-muted);
  font-size: 12px;
  margin-left: 6px;
}
.w200 {
  width: 200px;
}
.w240 {
  width: 240px;
}
.w280 {
  width: 280px;
}
.w320 {
  width: 320px;
}
</style>
