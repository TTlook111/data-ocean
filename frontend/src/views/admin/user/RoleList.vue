<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listRoles, type RoleItem } from '../../../api/admin/user'

const loading = ref(false)
const roles = ref<RoleItem[]>([])

async function fetchRoles() {
  loading.value = true
  try {
    const result = await listRoles()
    roles.value = result.data
  } finally {
    loading.value = false
  }
}

onMounted(fetchRoles)
</script>

<template>
  <main class="admin-page post-login-page">
    <header class="page-header">
      <p>角色管理</p>
      <h1>角色列表与权限概览</h1>
    </header>

    <el-table v-loading="loading" :data="roles" border>
      <el-table-column prop="roleCode" label="角色编码" min-width="150" />
      <el-table-column prop="roleName" label="角色名称" min-width="150" />
      <el-table-column prop="description" label="描述" min-width="220" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" min-width="180" />
    </el-table>
  </main>
</template>

<style scoped>
.admin-page {
  min-height: 100vh;
  padding: 28px;
}

.page-header {
  margin-bottom: 18px;
}

.page-header p {
  margin: 0 0 6px;
  color: var(--do-leaf-deep);
  font-weight: 800;
}

.page-header h1 {
  margin: 0;
  font-size: 28px;
  color: var(--do-ink);
}
</style>
