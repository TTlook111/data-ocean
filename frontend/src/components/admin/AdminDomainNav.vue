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
import { computed } from 'vue'
import { useRoute, type RouteLocationRaw } from 'vue-router'
import { ADMIN_DOMAIN_KEYS, ADMIN_WORKSPACES, type AdminWorkspace } from '../../router/adminNavigation'
import { useAdminContextStore } from '../../stores/adminContext'
import { buildContextQuery, findWorkspaceContextMode, type AdminContextSource } from '../../utils/adminNavigation'

const props = defineProps<{ collapsed: boolean }>()
const route = useRoute()
const context = useAdminContextStore()
const emit = defineEmits<{ navigate: []; 'expand-sidebar': [] }>()

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
const activeWorkspaceKey = computed(() => String(route.meta.workspaceKey || ''))

function isActive(key: string) {
  return activeKey.value === key
}

function domainWorkspaces(domainKey: string) {
  return ADMIN_WORKSPACES.filter((item) => item.domainKey === domainKey)
}

function isWorkspaceActive(workspace: AdminWorkspace) {
  return activeWorkspaceKey.value === workspace.key
}

function expandCollapsedNavigation() {
  if (props.collapsed) emit('expand-sidebar')
}

// 上下文来源：合法 URL 参数优先于本地持久化状态，缺失时回落到 adminContext。
const contextSource = computed<AdminContextSource>(() => ({
  datasourceId: Number(route.query.datasourceId) || context.datasourceId,
  snapshotId: Number(route.query.snapshotId) || context.snapshotId,
}))

// 一级导航进入该域第一个工作区，并按目标工作区的 contextMode 继承上下文。
function targetFor(domain: { path: string }): RouteLocationRaw {
  return { path: domain.path, query: buildContextQuery(findWorkspaceContextMode(domain.path), contextSource.value) }
}
</script>

<template>
  <nav class="admin-domain-nav" aria-label="后台业务导航">
    <section v-for="domain in domains" :key="domain.key" class="admin-domain-nav__group">
      <RouterLink
        :to="targetFor(domain)"
        class="admin-domain-nav__item"
        :class="{ active: isActive(domain.key) }"
        :title="domain.label"
        :aria-current="isActive(domain.key) ? 'page' : undefined"
        :aria-expanded="domainWorkspaces(domain.key).length ? (!props.collapsed && isActive(domain.key)) : undefined"
        :aria-controls="domainWorkspaces(domain.key).length && !props.collapsed ? `admin-workspaces-${domain.key}` : undefined"
        @click="emit('navigate'); expandCollapsedNavigation()"
      >
        <component :is="domain.icon" :size="18" aria-hidden="true" />
        <span v-if="!props.collapsed">{{ domain.label }}</span>
      </RouterLink>

      <div
        v-if="!props.collapsed && isActive(domain.key) && domainWorkspaces(domain.key).length"
        :id="`admin-workspaces-${domain.key}`"
        class="admin-domain-nav__workspace-list"
        :aria-label="`${domain.label}工作区`"
      >
        <RouterLink
          v-for="workspace in domainWorkspaces(domain.key)"
          :key="workspace.key"
          :to="{ path: workspace.path, query: buildContextQuery(workspace.contextMode, contextSource) }"
          class="admin-domain-nav__workspace-item"
          :class="{ active: isWorkspaceActive(workspace) }"
          :aria-current="isWorkspaceActive(workspace) ? 'page' : undefined"
        >
          <span class="admin-domain-nav__workspace-dot" aria-hidden="true"></span>
          <span>{{ workspace.label }}</span>
        </RouterLink>
      </div>
    </section>
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

.admin-domain-nav__group {
  display: grid;
  gap: 4px;
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

.admin-domain-nav__item:focus-visible,
.admin-domain-nav__workspace-item:focus-visible {
  outline: 3px solid rgba(77, 143, 220, .24);
  outline-offset: 2px;
}

.admin-domain-nav__workspace-list {
  display: grid;
  gap: 3px;
  margin: 0 0 6px 16px;
  padding: 4px 0 4px 15px;
  border-left: 1px solid rgba(77, 143, 220, .26);
}

.admin-domain-nav__workspace-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  padding: 0 10px;
  border-radius: var(--do-radius-sm);
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 700;
  transition: background var(--do-transition-fast), color var(--do-transition-fast);
}

.admin-domain-nav__workspace-item:hover,
.admin-domain-nav__workspace-item.active {
  color: var(--do-primary-strong);
  background: rgba(77, 143, 220, .1);
}

.admin-domain-nav__workspace-dot {
  width: 5px;
  height: 5px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: currentColor;
  opacity: .58;
}

.admin-domain-nav__workspace-item.active .admin-domain-nav__workspace-dot {
  width: 6px;
  height: 6px;
  opacity: 1;
}
</style>
