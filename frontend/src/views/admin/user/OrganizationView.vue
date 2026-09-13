<script setup lang="ts">
/**
 * 组织与角色工作区（开发指导 §7.15）
 *
 * 固定 Tab：`用户 | 角色 | 部门 | 权限项`，Tab 由 `?tab=` 恢复。
 *
 * 权限项是原实现最缺的一块：只有只读标签，没有创建/编辑/删除——而开发指导 §7.15
 * 明确要求「权限项保留列表、创建、编辑和删除能力」，后端 `PermissionController`
 * 也早已提供对应端点（写操作需 `role:manage`）。本次补齐 API 封装与页面操作。
 *
 * **Tab 切换使用 `push`**：开发指导 §15 要求「URL 刷新、前进和后退恢复」，
 * 原实现用 `replace`，刷新能恢复但浏览器后退不能切回上一个 Tab。
 *
 * 后端能力缺口（如实标注）：`DepartmentController` 只有 `/tree` 与增删改，
 * **没有部门成员接口**，因此 §7.15「部门…展示成员摘要」在前后端两侧都不成立。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Pencil, Plus, RefreshCw, Trash2 } from 'lucide-vue-next'
import {
  createPermission,
  deletePermission,
  listPermissions,
  updatePermission,
  type PermissionItem,
  type PermissionPayload,
} from '../../../api/admin/user'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import UserList from './UserList.vue'
import RoleList from './RoleList.vue'
import DepartmentTree from './DepartmentTree.vue'

const TABS = [
  { name: 'users', label: '用户', title: '用户' },
  { name: 'roles', label: '角色', title: '角色' },
  { name: 'departments', label: '部门', title: '部门' },
  { name: 'permissions', label: '权限项', title: '权限项' },
] as const

const route = useRoute()
const router = useRouter()

const activeTab = ref(TABS.some((t) => t.name === route.query.tab) ? String(route.query.tab) : 'users')
const tabTitle = computed(() => TABS.find((t) => t.name === activeTab.value)?.title || '组织与角色')

const permissions = ref<PermissionItem[]>([])
const permissionsLoading = ref(false)
const permissionsError = ref('')
const moduleFilter = ref('')
const dialogVisible = ref(false)
const editingId = ref<number>()
const saving = ref(false)

const form = reactive<PermissionPayload>({
  permissionCode: '',
  permissionName: '',
  module: '',
  description: '',
})

const moduleOptions = computed(() => {
  const modules = [...new Set(permissions.value.map((item) => item.module))].sort()
  return modules.map((value) => ({ value, label: value }))
})

const filteredPermissions = computed(() => {
  if (!moduleFilter.value) return permissions.value
  return permissions.value.filter((item) => item.module === moduleFilter.value)
})

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

function selectTab(name: string | number) {
  activeTab.value = String(name)
  router.push({ query: { ...route.query, tab: activeTab.value } })
  if (activeTab.value === 'permissions' && !permissions.value.length) loadPermissions()
}

watch(() => route.query.tab, (value) => {
  const next = TABS.some((tab) => tab.name === value) ? String(value) : 'users'
  if (next !== activeTab.value) activeTab.value = next
})

/**
 * 加载权限项平铺列表。
 *
 * 失败必须渲染错误态：原实现没有 catch，请求失败后 `permissionGroups` 保持空数组，
 * 页面走到「暂无权限项」——把失败说成了空数据（§11.3、§18）。
 */
async function loadPermissions() {
  permissionsLoading.value = true
  permissionsError.value = ''
  try {
    permissions.value = (await listPermissions()).data || []
  } catch (cause) {
    permissions.value = []
    permissionsError.value = apiError(cause, '权限项加载失败')
  } finally {
    permissionsLoading.value = false
  }
}

function openCreate() {
  editingId.value = undefined
  form.permissionCode = ''
  form.permissionName = ''
  form.module = ''
  form.description = ''
  dialogVisible.value = true
}

function openEdit(row: PermissionItem) {
  editingId.value = row.id
  form.permissionCode = row.permissionCode
  form.permissionName = row.permissionName
  form.module = row.module
  form.description = row.description || ''
  dialogVisible.value = true
}

async function savePermission() {
  if (!form.permissionCode.trim() || !form.permissionName.trim() || !form.module.trim()) {
    ElMessage.warning('权限编码、权限名称与所属模块都不能为空')
    return
  }
  saving.value = true
  try {
    const payload: PermissionPayload = {
      permissionCode: form.permissionCode.trim(),
      permissionName: form.permissionName.trim(),
      module: form.module.trim(),
      description: form.description?.trim() || undefined,
    }
    if (editingId.value) {
      await updatePermission(editingId.value, payload)
      ElMessage.success('权限项已更新')
    } else {
      await createPermission(payload)
      ElMessage.success('权限项已创建')
    }
    dialogVisible.value = false
    await loadPermissions()
  } catch (cause) {
    ElMessage.error(apiError(cause, editingId.value ? '更新权限项失败' : '创建权限项失败'))
  } finally {
    saving.value = false
  }
}

async function removePermission(row: PermissionItem) {
  try {
    await ElMessageBox.confirm(
      `删除权限项「${row.permissionName}」（${row.permissionCode}）？`
      + '已分配给角色的权限项会影响到角色权限树，请先确认没有角色正在使用它。',
      '确认删除权限项',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deletePermission(row.id)
    ElMessage.success('权限项已删除')
    await loadPermissions()
  } catch (cause) {
    ElMessage.error(apiError(cause, '删除权限项失败'))
  }
}

onMounted(() => {
  if (activeTab.value === 'permissions') loadPermissions()
})
</script>

<template>
  <div class="admin-page organization-page">
    <TaskPageHeader
      title="组织与角色"
      description="在一个工作区中维护用户、角色、部门和权限项；完成主体管理后再进入授权管理配置访问范围。"
    >
      <template #status><span class="organization-page__tab-label">当前：{{ tabTitle }}</span></template>
      <template #actions>
        <el-button
          v-if="activeTab === 'permissions'"
          :icon="Plus"
          type="primary"
          @click="openCreate"
        >新建权限项</el-button>
      </template>
    </TaskPageHeader>

    <el-tabs :model-value="activeTab" class="organization-page__tabs" @update:model-value="selectTab">
      <el-tab-pane label="用户" name="users" lazy><UserList /></el-tab-pane>
      <el-tab-pane label="角色" name="roles" lazy><RoleList /></el-tab-pane>
      <el-tab-pane label="部门" name="departments" lazy>
        <DepartmentTree />
      </el-tab-pane>
      <el-tab-pane label="权限项" name="permissions" lazy>
        <section class="organization-page__panel">
          <div class="organization-page__toolbar">
            <el-select v-model="moduleFilter" placeholder="全部模块" clearable style="width: 180px">
              <el-option v-for="opt in moduleOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
            <el-button :icon="RefreshCw" :loading="permissionsLoading" @click="loadPermissions">刷新</el-button>
          </div>
          <ErrorState v-if="permissionsError" :message="permissionsError" @retry="loadPermissions" />
          <LoadingState v-else-if="permissionsLoading" variant="skeleton" :rows="5" />
          <EmptyState
            v-else-if="!filteredPermissions.length"
            :message="permissions.length ? '当前模块筛选下没有权限项。' : '还没有权限项。创建后才能在角色权限树中分配。'"
            :action-text="permissions.length ? '' : '新建权限项'"
            @action="openCreate"
          />
          <el-table v-else :data="filteredPermissions" stripe size="small">
            <el-table-column prop="module" label="所属模块" width="150" />
            <el-table-column prop="permissionName" label="权限名称" min-width="160" />
            <el-table-column prop="permissionCode" label="权限编码" min-width="200">
              <template #default="{ row }"><code class="organization-page__code">{{ row.permissionCode }}</code></template>
            </el-table-column>
            <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ row.description || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="130" align="center">
              <template #default="{ row }">
                <el-button link type="primary" :icon="Pencil" aria-label="编辑权限项" @click="openEdit(row)">编辑</el-button>
                <el-button link type="danger" :icon="Trash2" aria-label="删除权限项" @click="removePermission(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <p class="organization-page__note">
            写操作由后端 `role:manage` 权限校验，前端按钮可见性不构成授权边界（§13、§18）。
          </p>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑权限项' : '新建权限项'" width="520px">
      <el-form label-width="92px">
        <el-form-item label="权限编码">
          <el-input v-model="form.permissionCode" placeholder="如 datasource:manage" />
        </el-form-item>
        <el-form-item label="权限名称">
          <el-input v-model="form.permissionName" placeholder="如 数据源管理" />
        </el-form-item>
        <el-form-item label="所属模块">
          <el-input v-model="form.module" placeholder="如 datasource" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePermission">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.organization-page__tab-label {
  color: var(--do-muted);
  font-size: 13px;
}

.organization-page__tabs :deep(.el-tab-pane) {
  padding-top: 8px;
}

.organization-page__panel {
  display: grid;
  gap: 12px;
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.organization-page__toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.organization-page__code {
  font-size: 12px;
}

.organization-page__note,
.organization-page__gap {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}

.organization-page__gap {
  margin-top: 12px;
}
</style>
