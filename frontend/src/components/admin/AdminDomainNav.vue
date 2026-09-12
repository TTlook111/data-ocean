<script setup lang="ts">
import {
  BarChart3,
  BookOpen,
  Database,
  FolderKanban,
  LayoutDashboard,
  ShieldCheck,
  SlidersHorizontal,
} from 'lucide-vue-next'
import { useRoute, type RouteLocationRaw } from 'vue-router'
import { computed } from 'vue'
import { ADMIN_DOMAIN_KEYS } from '../../router/adminNavigation'
import { useAdminContextStore } from '../../stores/adminContext'
import { buildContextQuery, findWorkspaceContextMode, type AdminContextSource } from '../../utils/adminNavigation'

const route = useRoute()
const context = useAdminContextStore()
const emit = defineEmits<{ navigate: [] }>()

const domains = [
  { key: ADMIN_DOMAIN_KEYS.workbench, label: '工作台', path: '/admin/workbench', icon: LayoutDashboard },
  { key: ADMIN_DOMAIN_KEYS.dataEntry, label: '数据接入', path: '/admin/data-sources', icon: Database },
  { key: ADMIN_DOMAIN_KEYS.dataAssets, label: '数据资产', path: '/admin/assets', icon: FolderKanban },
  { key: ADMIN_DOMAIN_KEYS.governance, label: '数据治理', path: '/admin/governance', icon: SlidersHorizontal },
  { key: ADMIN_DOMAIN_KEYS.semantics, label: '语义中心', path: '/admin/semantics/glossaries', icon: BookOpen },
  { key: ADMIN_DOMAIN_KEYS.access, label: '权限与组织', path: '/admin/access', icon: ShieldCheck },
  { key: ADMIN_DOMAIN_KEYS.operations, label: '运营与平台', path: '/admin/operations/queries', icon: BarChart3 },
]

const activeKey = computed(() => String(route.meta.domainKey || (route.path === '/admin/workbench' ? ADMIN_DOMAIN_KEYS.workbench : '')))

function isActive(key: string) {
  return activeKey.value === key
}

// 上下文来源：合法 URL 参数优先于本地持久化状态（开发指导 §6.2 规则 4）。
// 回落到 store 是必要的——页面 URL 通常不带这两个参数，只读 URL 会让继承失效。
const contextSource = computed<AdminContextSource>(() => ({
  datasourceId: Number(route.query.datasourceId) || context.datasourceId,
  snapshotId: Number(route.query.snapshotId) || context.snapshotId,
}))

// 一级导航的落地页是一个具体工作区（如 数据资产 → /admin/assets），
// 按该工作区声明的 contextMode 决定继承哪些参数，与二级导航使用同一套规则。
function targetFor(domain: { path: string }): RouteLocationRaw {
  return { path: domain.path, query: buildContextQuery(findWorkspaceContextMode(domain.path), contextSource.value) }
}
</script>

<template>
  <nav class="admin-domain-nav" aria-label="一级业务域">
    <RouterLink
      v-for="domain in domains"
      :key="domain.key"
      :to="targetFor(domain)"
      class="admin-domain-nav__item"
      :class="{ active: isActive(domain.key) }"
      :title="domain.label"
      @click="emit('navigate')"
    >
      <component :is="domain.icon" :size="18" aria-hidden="true" />
      <span>{{ domain.label }}</span>
    </RouterLink>
  </nav>
</template>

<style scoped>
.admin-domain-nav {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  overflow: auto;
  padding: 18px 12px;
}

.admin-domain-nav__item {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
  padding: 0 12px;
  border-radius: var(--do-radius-md);
  color: var(--do-muted);
  font-size: 14px;
  font-weight: 700;
  transition: background var(--do-transition-fast), color var(--do-transition-fast);
}

.admin-domain-nav__item:hover,
.admin-domain-nav__item.active {
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
}
</style>
