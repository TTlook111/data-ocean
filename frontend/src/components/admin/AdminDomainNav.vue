<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, type RouteLocationRaw } from 'vue-router'
import { ADMIN_DOMAIN_KEYS, ADMIN_WORKSPACES, type AdminWorkspace } from '../../router/adminNavigation'
import { ADMIN_DOMAINS } from '../../router/adminDomains'
import { useAdminContextStore } from '../../stores/adminContext'
import { useIamS1Store } from '../../stores/iamS1'
import {
  buildWorkspaceTarget,
  findDomainHome,
  groupAdminWorkspaces,
  visibleAdminWorkspaces,
  type AdminContextSource,
} from '../../utils/adminNavigation'

const props = defineProps<{ collapsed: boolean }>()
const route = useRoute()
const context = useAdminContextStore()
const iamS1 = useIamS1Store()
const emit = defineEmits<{ navigate: []; 'expand-sidebar': [] }>()

const activeKey = computed(() => String(route.meta.domainKey || (route.path === '/admin/workbench' ? ADMIN_DOMAIN_KEYS.workbench : '')))
const activeWorkspaceKey = computed(() => String(route.meta.workspaceKey || ''))

function isActive(key: string) {
  return activeKey.value === key
}

function isWorkspaceActive(workspace: AdminWorkspace) {
  return activeWorkspaceKey.value === workspace.key
}

function expandCollapsedNavigation() {
  if (props.collapsed) emit('expand-sidebar')
}

// 当前范围只作目标偏好；每个工作区使用自身实际有权源，跨源不携带旧快照。
const contextSource = computed<AdminContextSource>(() => ({
  datasourceId: Number(route.query.datasourceId) || context.datasourceId,
  snapshotId: Number(route.query.snapshotId) || context.currentSnapshot?.id,
}))

const visibleWorkspaces = computed(() => visibleAdminWorkspaces(
  ADMIN_WORKSPACES,
  iamS1,
))

function domainWorkspaces(domainKey: string) {
  return visibleWorkspaces.value.filter((item) => item.domainKey === domainKey)
}

function workspaceGroups(domainKey: string) {
  return groupAdminWorkspaces(domainWorkspaces(domainKey))
}

// 工作台使用独立全局查看权；其他域在全部二级项不可见时隐藏。
const visibleDomains = computed(() => ADMIN_DOMAINS.filter((domain) => {
  if (domain.key === ADMIN_DOMAIN_KEYS.workbench) {
    return !iamS1.loaded || Boolean(iamS1.errorMessage) || iamS1.hasGlobal('admin:workbench:view')
  }
  const declared = ADMIN_WORKSPACES.some((item) => item.domainKey === domain.key)
  return !declared || visibleWorkspaces.value.some((item) => item.domainKey === domain.key)
}))

// 一级导航进入该域第一个可见工作区，并按目标工作区的 contextMode 继承上下文。
// 目标路径由 ADMIN_WORKSPACES 推导而不是各自硬编码，保证一级入口永远指向真实路由。
function targetFor(domain: { key: string }): RouteLocationRaw {
  const { path } = findDomainHome(domain.key, visibleWorkspaces.value)
  return buildWorkspaceTarget(path, iamS1, contextSource.value)
}
</script>

<template>
  <nav class="admin-domain-nav" aria-label="后台业务导航">
    <section v-for="domain in visibleDomains" :key="domain.key" class="admin-domain-nav__group">
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
        <component :is="domain.icon" :size="19" aria-hidden="true" />
        <span v-if="!props.collapsed">{{ domain.label }}</span>
      </RouterLink>

      <div
        v-if="!props.collapsed && isActive(domain.key) && domainWorkspaces(domain.key).length"
        :id="`admin-workspaces-${domain.key}`"
        class="admin-domain-nav__workspace-list"
        :aria-label="`${domain.label}工作区`"
      >
        <div
          v-for="group in workspaceGroups(domain.key)"
          :key="group.key"
          class="admin-domain-nav__workspace-group"
          :role="group.label ? 'group' : undefined"
          :aria-label="group.label"
        >
          <span v-if="group.label" class="admin-domain-nav__workspace-group-label">{{ group.label }}</span>
          <RouterLink
            v-for="workspace in group.items"
            :key="workspace.key"
            :to="buildWorkspaceTarget(workspace.path, iamS1, contextSource)"
            class="admin-domain-nav__workspace-item"
            :class="{ active: isWorkspaceActive(workspace) }"
            :aria-current="isWorkspaceActive(workspace) ? 'page' : undefined"
          >
            <span class="admin-domain-nav__workspace-dot" aria-hidden="true"></span>
            <span>{{ workspace.label }}</span>
          </RouterLink>
        </div>
      </div>
    </section>
  </nav>
</template>

<style scoped>
.admin-domain-nav {
  /* 导航保持独立滚动，业务区分组使用间距而非多层卡片。 */
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 5px;
  min-height: 0;
  overflow: auto;
  padding: 16px 12px;
}

.admin-domain-nav__group {
  display: grid;
  gap: 3px;
}

.admin-domain-nav__item {
  /* 域入口继承深色导航语义；权限与目标路由仍由现有能力映射决定。 */
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  padding: 0 12px;
  border-radius: var(--do-radius-md);
  color: var(--do-nav-muted);
  font-size: 13px;
  font-weight: 550;
  transition: background var(--do-transition-fast), color var(--do-transition-fast);
}

.admin-domain-nav__item > svg { flex: 0 0 auto; }

.admin-domain-nav__item:hover,
.admin-domain-nav__item.active {
  /* 悬停与当前域使用可读的明亮前景，保留清楚的选择反馈。 */
  color: var(--do-nav-text);
  background: var(--do-nav-hover);
}

.admin-domain-nav__item.active {
  /* 当前域强调标题与背景，不再叠加旧主题的装饰边线。 */
  font-weight: 650;
  color: var(--do-nav-accent);
}

.admin-domain-nav__item:focus-visible,
.admin-domain-nav__workspace-item:focus-visible {
  /* 深色表面使用专用高对比焦点色。 */
  outline: 2px solid var(--do-nav-accent);
  outline-offset: 2px;
}

.admin-domain-nav__workspace-list {
  /* 二级任务缩进建立层级，细线只表示父子关系。 */
  display: grid;
  gap: 2px;
  margin: 0 0 4px 14px;
  padding: 3px 0 3px 13px;
  border-left: 1px solid var(--do-nav-line);
}

.admin-domain-nav__workspace-group {
  display: grid;
  gap: 2px;
}

.admin-domain-nav__workspace-group + .admin-domain-nav__workspace-group {
  margin-top: 6px;
}

.admin-domain-nav__workspace-group-label {
  /* 组名保持次级但可读，避免用透明度降低中文辨识度。 */
  padding: 2px 10px 1px;
  color: var(--do-nav-muted);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: .08em;
}

.admin-domain-nav__workspace-item {
  /* 二级入口保留紧凑密度；实际当前页面仍以 aria-current 指明。 */
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 34px;
  padding: 0 10px;
  border-radius: var(--do-radius-sm);
  color: var(--do-nav-muted);
  font-size: 13px;
  font-weight: 600;
  transition: background var(--do-transition-fast), color var(--do-transition-fast);
}

.admin-domain-nav__workspace-item:hover,
.admin-domain-nav__workspace-item.active {
  /* 当前工作区与一级域区分，使用深色主题内的实色选中面。 */
  color: var(--do-nav-text);
  background: var(--do-nav-active);
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
