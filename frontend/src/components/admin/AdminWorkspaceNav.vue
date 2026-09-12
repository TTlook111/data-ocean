<script setup lang="ts">
import { computed } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import { useRoute } from 'vue-router'
import { ADMIN_WORKSPACES, type AdminWorkspace } from '../../router/adminNavigation'
import { useAdminContextStore } from '../../stores/adminContext'
import { buildContextQuery, type AdminContextSource } from '../../utils/adminNavigation'

const route = useRoute()
const context = useAdminContextStore()
const workspaces = computed(() => ADMIN_WORKSPACES.filter((item) => item.domainKey === route.meta.domainKey))

// 高亮以路由显式声明的 workspaceKey 为准，与 AdminDomainNav 用 domainKey 判定的做法一致。
// 不用路径前缀匹配：`/admin/governance` 是 `/admin/governance/issues`、`/rules`、`/fields` 的前缀，
// `/admin/access` 是 `/admin/access/approvals`、`/organization` 的前缀，会同时高亮两项；
// 而 `/admin/metadata/tables`、`/admin/permission/policies` 的路径与所属工作区不同前缀，则完全不高亮。
const activeKey = computed(() => String(route.meta.workspaceKey || ''))

function isActive(key: string) {
  return activeKey.value === key
}

// 上下文来源：合法 URL 参数优先于本地持久化状态（开发指导 §6.2 规则 4）。
// 回落到 store 是必要的——页面 URL 通常不带这两个参数，只读 URL 会让继承失效。
const contextSource = computed<AdminContextSource>(() => ({
  datasourceId: Number(route.query.datasourceId) || context.datasourceId,
  snapshotId: Number(route.query.snapshotId) || context.snapshotId,
}))

// 按目标工作区自己声明的 contextMode 决定继承哪些参数，使目标 URL 自描述
// （可分享、可在刷新和前进后退时恢复）。目标页的 ScopeBar 会把它们同步进全局上下文。
function targetFor(workspace: AdminWorkspace): RouteLocationRaw {
  return { path: workspace.path, query: buildContextQuery(workspace.contextMode, contextSource.value) }
}
</script>

<template>
  <nav v-if="workspaces.length" class="admin-workspace-nav" aria-label="当前业务域工作区">
    <span class="admin-workspace-nav__label">当前工作区</span>
    <RouterLink
      v-for="workspace in workspaces"
      :key="workspace.key"
      :to="targetFor(workspace)"
      class="admin-workspace-nav__item"
      :class="{ active: isActive(workspace.key) }"
    >
      {{ workspace.label }}
    </RouterLink>
  </nav>
</template>

<style scoped>
.admin-workspace-nav {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow-x: auto;
  padding: 12px 24px;
  border-bottom: 1px solid var(--do-line);
  background: var(--do-surface);
}

.admin-workspace-nav__label {
  flex: 0 0 auto;
  margin-right: 4px;
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 700;
}

.admin-workspace-nav__item {
  flex: 0 0 auto;
  padding: 7px 12px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  color: var(--do-muted);
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

.admin-workspace-nav__item:hover,
.admin-workspace-nav__item.active {
  border-color: var(--do-primary);
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
}
</style>
