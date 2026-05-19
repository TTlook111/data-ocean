<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ShieldCheck } from 'lucide-vue-next'
import { listRoles, type RoleItem } from '../../../api/admin/user'

const loading = ref(false)
const errorMessage = ref('')
const roles = ref<RoleItem[]>([])

function extractError(error: unknown, fallback: string) {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const msg = (error as { response?: { data?: { message?: string } } }).response?.data?.message
    if (typeof msg === 'string') return msg
  }
  return fallback
}

async function fetchRoles() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listRoles()
    roles.value = result.data
  } catch (error) {
    roles.value = []
    errorMessage.value = extractError(error, '角色数据加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function statusType(status: number) {
  return status === 1 ? 'success' : 'info'
}

function statusLabel(status: number) {
  return status === 1 ? '启用' : '禁用'
}

function formatTime(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date)
}

onMounted(fetchRoles)
</script>

<template>
  <main class="admin-page post-login-page">
    <header class="page-header">
      <div>
        <p>治理管理</p>
        <h1>角色管理</h1>
        <span class="header-subtitle">查看系统角色定义、启用状态和权限说明。角色分配给用户后生效。</span>
      </div>
    </header>

    <section class="role-summary">
      <article v-for="role in roles.slice(0, 4)" :key="role.id" class="role-chip">
        <span class="role-chip-icon">
          <ShieldCheck :size="16" />
        </span>
        <div>
          <strong>{{ role.roleName }}</strong>
          <small>{{ role.description || role.roleCode }}</small>
        </div>
      </article>
    </section>

    <section class="table-shell">
      <el-skeleton v-if="loading && !roles.length" :rows="4" animated style="padding:18px" />

      <el-result v-else-if="errorMessage" icon="error" title="角色数据加载失败" :sub-title="errorMessage">
        <template #extra>
          <el-button type="primary" @click="fetchRoles">重试</el-button>
        </template>
      </el-result>

      <el-empty v-else-if="!roles.length" description="暂无角色数据" />

      <el-table v-else v-loading="loading" :data="roles" border row-key="id" highlight-current-row>
        <el-table-column prop="roleName" label="角色名称" min-width="140" fixed>
          <template #default="{ row }">
            <strong>{{ row.roleName }}</strong>
          </template>
        </el-table-column>
        <el-table-column prop="roleCode" label="角色编码" min-width="160" />
        <el-table-column prop="description" label="描述" min-width="240" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="130">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
      </el-table>
    </section>
  </main>
</template>

<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.page-header p {
  margin: 0 0 6px;
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 900;
}

.page-header h1 {
  margin: 0;
  font-size: 24px;
  color: var(--do-ink);
}

.role-summary {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}

.role-chip {
  display: grid;
  grid-template-columns: 36px 1fr;
  align-items: center;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.role-chip-icon {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: var(--do-radius);
  color: var(--do-tone-purple);
  background: var(--do-tone-purple-bg);
}

.role-chip strong {
  display: block;
  font-size: 14px;
  color: var(--do-ink);
}

.role-chip small {
  color: var(--do-muted);
  font-size: 12px;
}

.table-shell {
  min-height: 280px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}
</style>
