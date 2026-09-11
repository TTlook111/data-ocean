<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listPermissionsTree, type PermissionGroup } from '../../../api/admin/user'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import UserList from './UserList.vue'
import RoleList from './RoleList.vue'
import DepartmentTree from './DepartmentTree.vue'

const route = useRoute()
const router = useRouter()
const tab = ref(String(route.query.tab || 'users'))
const permissionGroups = ref<PermissionGroup[]>([])
const permissionLoading = ref(false)

const tabTitle = computed(() => ({
  users: '用户',
  roles: '角色',
  departments: '部门',
  permissions: '权限项',
}[tab.value] || '组织与角色'))

function selectTab(value: string) {
  tab.value = value
  router.replace({ query: { ...route.query, tab: value } })
}

async function loadPermissions() {
  if (permissionGroups.value.length) return
  permissionLoading.value = true
  try {
    const result = await listPermissionsTree()
    permissionGroups.value = result.data || []
  } finally {
    permissionLoading.value = false
  }
}

watch(() => route.query.tab, (value) => {
  tab.value = String(value || 'users')
  if (tab.value === 'permissions') loadPermissions()
})

onMounted(() => {
  if (tab.value === 'permissions') loadPermissions()
})
</script>

<template>
  <div class="admin-page organization-page">
    <TaskPageHeader title="组织与角色" description="在一个工作区中维护用户、角色、部门和权限项；完成主体管理后再进入授权管理配置访问范围。">
      <template #status><span class="organization-page__tab-label">当前：{{ tabTitle }}</span></template>
    </TaskPageHeader>

    <el-tabs :model-value="tab" class="organization-page__tabs" @update:model-value="selectTab">
      <el-tab-pane label="用户" name="users"><UserList /></el-tab-pane>
      <el-tab-pane label="角色" name="roles"><RoleList /></el-tab-pane>
      <el-tab-pane label="部门" name="departments"><DepartmentTree /></el-tab-pane>
      <el-tab-pane label="权限项" name="permissions">
        <el-skeleton v-if="permissionLoading" :rows="6" animated />
        <div v-else class="permission-groups">
          <el-empty v-if="!permissionGroups.length" description="暂无权限项" />
          <el-card v-for="group in permissionGroups" v-else :key="group.module" shadow="never">
            <template #header>{{ group.moduleName || group.module }}</template>
            <div class="permission-list">
              <el-tag v-for="permission in group.permissions" :key="permission.id" effect="plain">
                {{ permission.permissionName }} · {{ permission.permissionCode }}
              </el-tag>
            </div>
          </el-card>
        </div>
      </el-tab-pane>
    </el-tabs>
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

.permission-groups {
  display: grid;
  gap: 12px;
}

.permission-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
