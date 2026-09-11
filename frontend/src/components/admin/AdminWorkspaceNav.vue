<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ADMIN_WORKSPACES } from '../../router/adminNavigation'

const route = useRoute()
const workspaces = computed(() => ADMIN_WORKSPACES.filter((item) => item.domainKey === route.meta.domainKey))

function isActive(path: string) {
  return route.path === path || route.path.startsWith('/' + path.replace(/^\//, '') + '/')
}
</script>

<template>
  <nav v-if="workspaces.length" class="admin-workspace-nav" aria-label="当前业务域工作区">
    <span class="admin-workspace-nav__label">当前工作区</span>
    <RouterLink
      v-for="workspace in workspaces"
      :key="workspace.key"
      :to="workspace.path"
      class="admin-workspace-nav__item"
      :class="{ active: isActive(workspace.path) }"
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
