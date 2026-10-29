<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Bell, ChevronDown, KeyRound, LogOut, MessageSquareText, PanelLeftClose, PanelLeftOpen, UserRound } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import type { DropdownInstance } from 'element-plus'
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
import {
  getUnreadNotificationCount,
  listNotifications,
  markNotificationAsRead,
  markNotificationsBatchAsRead,
  type NotificationItem,
} from '../../api/notification'
import { findWorkspace, type AdminContextMode } from '../../router/adminNavigation'
import { buildAdminBreadcrumbs } from '../../utils/adminNavigation'
import AdminDomainNav from './AdminDomainNav.vue'
import ScopeBar from './ScopeBar.vue'
import brandMark from '../../assets/brand/dataocean-mark.png'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const iamS1 = useIamS1Store()
const collapsed = ref(false)
const notifications = ref<NotificationItem[]>([])
const notificationLoading = ref(false)
const unreadCount = ref(0)
const userMenuVisible = ref(false)
const userDropdown = ref<DropdownInstance>()
let notificationTimer: number | undefined
let narrowScreen: MediaQueryList | undefined
function adaptSidebar(event: MediaQueryListEvent) { if (event.matches) collapsed.value = true }

const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const initial = computed(() => displayName.value.slice(0, 1).toUpperCase())
const roleText = computed(() => iamS1.systemAdmin ? '系统管理员' : '当前账号')
const routeTitle = computed(() => String(route.meta.title || 'DataOcean 后台'))
const workspace = computed(() => findWorkspace(String(route.meta.workspaceKey || '')))
const breadcrumbs = computed(() => buildAdminBreadcrumbs({
  title: routeTitle.value,
  workspaceKey: String(route.meta.workspaceKey || ''),
  breadcrumbParent: route.meta.breadcrumbParent ? String(route.meta.breadcrumbParent) : undefined,
  query: route.query,
}))
const contextMode = computed<AdminContextMode>(() => (route.meta.contextMode as AdminContextMode) || workspace.value?.contextMode || 'none')
const hasUnread = computed(() => notifications.value.some((item) => !item.isRead))

function closeUserMenu() {
  userDropdown.value?.handleClose()
  userMenuVisible.value = false
}

function toggleSidebar() {
  closeUserMenu()
  collapsed.value = !collapsed.value
}

async function handleUserCommand(command: string) {
  closeUserMenu()
  if (command === 'query' && iamS1.queryUse) router.push('/query')
  if (command === 'profile') router.push('/profile')
  if (command === 'password') router.push('/change-password')
  if (command === 'logout') {
    await auth.logout()
    await router.push('/login')
  }
}

async function fetchUnreadCount() {
  try {
    const result = await getUnreadNotificationCount()
    unreadCount.value = result.data?.count ?? 0
  } catch {
    unreadCount.value = 0
  }
}

async function fetchNotifications() {
  notificationLoading.value = true
  try {
    const result = await listNotifications({ page: 1, pageSize: 8 })
    notifications.value = result.data?.records ?? []
    await fetchUnreadCount()
  } finally {
    notificationLoading.value = false
  }
}

async function readNotification(item: NotificationItem) {
  if (item.isRead) return
  await markNotificationAsRead(item.id)
  item.isRead = true
  unreadCount.value = Math.max(0, unreadCount.value - 1)
}

async function readAllVisible() {
  const unread = notifications.value.filter((item) => !item.isRead)
  if (!unread.length) return
  await markNotificationsBatchAsRead(unread.map((item) => item.id))
  unread.forEach((item) => { item.isRead = true })
  unreadCount.value = 0
}

function notificationTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}

onMounted(() => {
  narrowScreen = window.matchMedia('(max-width: 640px)')
  if (narrowScreen.matches) collapsed.value = true
  narrowScreen.addEventListener('change', adaptSidebar)
  fetchUnreadCount()
  notificationTimer = window.setInterval(fetchUnreadCount, 60_000)
})

onBeforeUnmount(() => {
  narrowScreen?.removeEventListener('change', adaptSidebar)
  if (notificationTimer) window.clearInterval(notificationTimer)
})
</script>

<template>
  <div class="admin-shell" :class="{ 'is-collapsed': collapsed }">
    <aside class="admin-shell__sidebar">
      <div class="admin-shell__brand">
        <RouterLink to="/admin" class="admin-shell__brand-link" aria-label="DataOcean 后台">
          <img class="admin-shell__brand-mark" :src="brandMark" alt="" width="28" height="44" />
          <span class="admin-shell__brand-copy">
            <strong class="notranslate" translate="no">DataOcean</strong>
            <small>数据查询治理平台</small>
          </span>
        </RouterLink>
        <button class="admin-shell__collapse" type="button" :aria-label="collapsed ? '展开导航' : '收起导航'" :aria-expanded="!collapsed" @click="toggleSidebar">
          <PanelLeftOpen v-if="collapsed" :size="17" />
          <PanelLeftClose v-else :size="17" />
        </button>
      </div>

      <AdminDomainNav
        :collapsed="collapsed"
        @navigate="closeUserMenu"
        @expand-sidebar="collapsed = false"
      />

      <div class="admin-shell__sidebar-footer">
        <el-dropdown ref="userDropdown" class="admin-shell__account-menu" popper-class="admin-account-actions-popper" :placement="collapsed ? 'right-end' : 'top-start'" trigger="click" @command="handleUserCommand" @visible-change="userMenuVisible = $event">
          <button class="admin-shell__user" type="button" aria-label="打开用户菜单" aria-haspopup="menu" :aria-expanded="userMenuVisible" :title="collapsed ? displayName : undefined">
            <span class="admin-shell__avatar" aria-hidden="true">{{ initial }}</span>
            <span class="admin-shell__user-copy"><strong class="notranslate" translate="no">{{ displayName }}</strong><small>{{ roleText }}</small></span>
            <ChevronDown class="admin-shell__user-chevron" :size="15" aria-hidden="true" />
          </button>
          <template #dropdown>
            <el-dropdown-menu @keydown.esc.stop.prevent="closeUserMenu">
              <el-dropdown-item command="profile"><UserRound :size="16" aria-hidden="true" />个人资料</el-dropdown-item>
              <el-dropdown-item command="password"><KeyRound :size="16" aria-hidden="true" />修改密码</el-dropdown-item>
              <el-dropdown-item v-if="iamS1.queryUse" command="query"><MessageSquareText :size="16" aria-hidden="true" />智能问数</el-dropdown-item>
              <el-dropdown-item divided command="logout"><LogOut :size="16" aria-hidden="true" />退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </aside>

    <main class="admin-shell__main">
      <header class="admin-shell__topbar">
        <nav class="admin-shell__title" aria-label="当前位置">
          <template v-for="(item, index) in breadcrumbs" :key="item.key">
            <span v-if="index" aria-hidden="true">/</span>
            <RouterLink
              v-if="item.to && !item.current"
              :to="item.to"
              :class="{ notranslate: item.key === 'home' }"
              :translate="item.key === 'home' ? 'no' : undefined"
            >{{ item.label }}</RouterLink>
            <span v-else :aria-current="item.current ? 'page' : undefined">{{ item.label }}</span>
          </template>
        </nav>

        <div class="admin-shell__topbar-actions">
          <el-popover placement="bottom-end" width="360" trigger="click" @show="fetchNotifications">
            <template #reference>
              <button class="admin-shell__icon-button" type="button" aria-label="通知">
                <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99">
                  <Bell :size="18" />
                </el-badge>
              </button>
            </template>
            <div v-loading="notificationLoading" class="admin-shell__notification-panel">
              <div class="admin-shell__notification-header">
                <strong>通知中心</strong>
                <button type="button" :disabled="!hasUnread" @click="readAllVisible">全部已读</button>
              </div>
              <el-empty v-if="!notifications.length" description="暂无通知" :image-size="56" />
              <button v-for="item in notifications" :key="item.id" type="button" class="admin-shell__notification" :class="{ unread: !item.isRead }" @click="readNotification(item)">
                <span>{{ item.title }}</span>
                <small>{{ notificationTime(item.createdAt) }}</small>
                <p>{{ item.content }}</p>
              </button>
            </div>
          </el-popover>

        </div>
      </header>

      <ScopeBar
        v-if="(contextMode === 'datasource' || contextMode === 'datasource-snapshot') && route.name !== 'admin-collections' && route.name !== 'admin-assets' && route.name !== 'admin-releases'"
        :mode="contextMode"
        :show-control-labels="route.name === 'admin-governance' || route.name === 'admin-governance-issues-new'"
        :track-route-sync="route.name === 'admin-governance-issues-new'"
        :preserve-route-context-on-refresh="route.name === 'admin-governance-issues-new'"
      />
      <section class="admin-shell__content" :class="{ 'admin-shell__content--compact': route.name === 'admin-workbench' || route.name === 'admin-data-sources' || route.name === 'admin-data-source-detail' || route.name === 'admin-collections' || route.name === 'admin-assets' || route.name === 'admin-asset-detail' || route.name === 'admin-releases' || route.name === 'admin-governance' || route.name === 'admin-governance-issues-new' }">
        <RouterView />
      </section>
    </main>
  </div>
</template>

<style scoped>
.admin-shell {
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  align-items: stretch;
  height: 100vh;
  height: 100dvh;
  min-height: 0;
  gap: 0;
  padding: 0;
  overflow: hidden;
  color: var(--do-ink);
  background: var(--do-bg);
}

.admin-shell.is-collapsed {
  grid-template-columns: 76px minmax(0, 1fr);
}
.admin-shell :deep(.el-button--primary) { --el-button-bg-color: var(--do-primary-strong); --el-button-border-color: var(--do-primary-strong); --el-button-hover-bg-color: var(--do-primary-strong); --el-button-hover-border-color: var(--do-primary-strong); }

.admin-shell__sidebar {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--do-line);
  border-radius: 0;
  background: var(--do-sidebar);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  isolation: isolate;
}

/* Bound the glass layer while leaving menus, shadows and content unclipped. */
.admin-shell__sidebar::before,
.admin-shell__topbar::before {
  display: none;
  position: absolute;
  inset: 0;
  z-index: -1;
  border-radius: inherit;
  pointer-events: none;
  content: '';
}

.admin-shell__sidebar::before {
  background: var(--do-glass-shell-bg);
  backdrop-filter: blur(var(--do-glass-blur)) saturate(128%);
  -webkit-backdrop-filter: blur(var(--do-glass-blur)) saturate(128%);
  clip-path: inset(0 round 16px);
}

.admin-shell__topbar::before {
  background: var(--do-glass-panel-bg);
  backdrop-filter: blur(var(--do-glass-panel-blur)) saturate(118%);
  -webkit-backdrop-filter: blur(var(--do-glass-panel-blur)) saturate(118%);
  clip-path: inset(0 round 15px);
}

@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  .admin-shell__sidebar::before { background: rgba(239, 247, 255, .96); }
  .admin-shell__topbar::before { background: rgba(250, 253, 255, .98); }
}

@media (prefers-reduced-transparency: reduce) {
  .admin-shell__sidebar::before { background: rgba(239, 247, 255, .97); backdrop-filter: none; -webkit-backdrop-filter: none; }
  .admin-shell__topbar::before { background: rgba(250, 253, 255, .98); backdrop-filter: none; -webkit-backdrop-filter: none; }
}

.admin-shell__brand {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  height: 72px;
  padding: 0 16px;
  border-bottom: 1px solid var(--do-line);
}

.admin-shell__brand-link {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.admin-shell__avatar {
  display: grid;
  place-items: center;
  border-radius: var(--do-radius-md);
  color: #fff;
  background: linear-gradient(145deg, #1766f4, #2d78f2);
  font-weight: 900;
}

.admin-shell__brand-mark {
  flex: 0 0 auto;
  width: 28px;
  height: 44px;
  object-fit: contain;
  overflow: hidden;
  font-size: 13px;
  line-height: 1;
  white-space: nowrap;
}

.admin-shell__brand-copy {
  display: grid;
  gap: 3px;
  min-width: 0;
  overflow: hidden;
}

.admin-shell__brand-copy strong {
  overflow: hidden;
  color: var(--do-ink);
  font-size: 18px;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-shell__brand-copy small {
  overflow: hidden;
  color: var(--do-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-shell__collapse {
  flex: 0 0 auto;
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  color: var(--do-muted);
  background: var(--do-surface);
  cursor: pointer;
}

.admin-shell__sidebar-footer {
  flex: 0 0 auto;
  margin-top: auto;
  padding: 12px;
  border-top: 1px solid var(--do-line);
}

.admin-shell__account-menu { width: 100%; }

.admin-shell__main {
  /* Keep page content above the adjacent sidebar's glass layer. */
  position: relative;
  z-index: 0;
  align-self: stretch;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.admin-shell__main > .scope-bar { flex: 0 0 auto; }

.admin-shell__topbar {
  position: relative;
  z-index: 10;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  min-height: 64px;
  padding: 0 24px;
  border: 0;
  border-bottom: 1px solid var(--do-line);
  border-radius: 0;
  background: var(--do-bg);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  isolation: isolate;
}

.admin-shell__title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  color: var(--do-muted);
  font-size: 14px;
  flex-wrap: wrap;
}

.admin-shell__title [aria-current] { color: var(--do-ink); font-weight: 600; }
.admin-shell__title a:hover { color: var(--do-primary-strong); }

.admin-shell__topbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.admin-shell__icon-button {
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  color: var(--do-ink);
  background: var(--do-surface);
  cursor: pointer;
}

.admin-shell__icon-button {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
}

.admin-shell__user {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) 15px;
  width: 100%;
  align-items: center;
  gap: 10px;
  min-height: 56px;
  padding: 8px;
  border: 0;
  border-radius: var(--do-radius-md);
  color: var(--do-ink);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.admin-shell__avatar {
  width: 36px;
  height: 36px;
  color: var(--do-ink);
  background: #e1e5dc;
  font-size: 12px;
}

.admin-shell__user-copy { min-width: 0; display: grid; gap: 4px; }
.admin-shell__user-copy strong, .admin-shell__user-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.admin-shell__user-copy strong { font-size: 13px; font-weight: 700; }
.admin-shell__user-copy small { color: var(--do-muted); font-size: 12px; }
.admin-shell__user-chevron { color: var(--do-muted); }
.admin-shell__user:hover { background: color-mix(in srgb, var(--do-primary) 8%, transparent); }
.admin-shell__user:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 28%, transparent); outline-offset: 2px; }

.admin-shell__content {
  width: 100%;
  max-width: 1480px;
  min-height: 0;
  flex: 1 1 0;
  margin: 0 auto;
  padding: 32px 32px 28px;
  overflow-y: auto;
  overflow-x: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--do-primary) 22%, transparent) transparent;
}
.admin-shell__content::-webkit-scrollbar { width: 6px; }
.admin-shell__content::-webkit-scrollbar-track { background: transparent; }
.admin-shell__content::-webkit-scrollbar-thumb { border-radius: var(--do-radius-full); background: color-mix(in srgb, var(--do-primary) 20%, transparent); }
.admin-shell__content--compact { padding-top: 28px; }

.admin-shell__notification-panel {
  min-height: 80px;
}

.admin-shell__notification-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--do-line);
}

.admin-shell__notification-header button {
  border: 0;
  color: var(--do-primary-strong);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
}

.admin-shell__notification {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px 8px;
  width: 100%;
  margin-top: 8px;
  padding: 10px;
  border: 1px solid transparent;
  border-radius: var(--do-radius-md);
  color: var(--do-ink);
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.admin-shell__notification.unread {
  border-color: rgba(77, 143, 220, .2);
  background: var(--do-primary-soft);
}

.admin-shell__notification small,
.admin-shell__notification p {
  color: var(--do-muted);
  font-size: 11px;
}

.admin-shell__notification p {
  grid-column: 1 / -1;
  margin: 0;
}

.admin-shell.is-collapsed .admin-shell__brand {
  gap: 2px;
  padding: 0 4px;
}

.admin-shell.is-collapsed .admin-shell__brand-copy,
.admin-shell.is-collapsed .admin-domain-nav__item span,
.admin-shell.is-collapsed .admin-shell__user-copy,
.admin-shell.is-collapsed .admin-shell__user-chevron {
  display: none;
}

.admin-shell.is-collapsed .admin-shell__brand-mark {
  width: 25px;
  height: 38px;
}

.admin-shell.is-collapsed .admin-domain-nav__item {
  justify-content: center;
  padding: 0;
}

.admin-shell.is-collapsed .admin-shell__sidebar-footer { padding: 8px; }
.admin-shell.is-collapsed .admin-shell__user { grid-template-columns: 36px; justify-content: center; padding: 0; }

@media (max-width: 760px) {
  .admin-shell { grid-template-columns: 208px minmax(0, 1fr); }
  .admin-shell__brand { padding: 0 12px; }
  .admin-shell__brand-copy small { display: none; }
  .admin-shell__content { padding: 18px 12px; }
  .admin-shell__topbar { padding: 0 18px; }
}

</style>

<style>
/* 元数据版本沿用后台公共海蓝画布；此处仅保留本路由的布局尺寸规则。 */
.admin-shell .admin-shell__content:has(.releases-page) {
  max-width: none;
  margin: 0;
  padding: 20px 24px 24px;
  overflow-x: hidden;
}
@media (max-width: 760px) {
  .admin-shell .admin-shell__content:has(.releases-page) { padding: 18px 12px; }
}

/* 目录页桌面固定表格模式下，底部留白由后台父容器统一收取，避免面板与侧栏底边错位。 */
.admin-shell .admin-shell__content:has(.assets-page--fixed-catalog) { padding-bottom: 0; }

/* 数据对象目录的快照表结构仅在宽屏和足够窗口高度时使用正文内滚动。 */
.admin-shell .admin-shell__content:has(.assets-page--fixed-tables) {
  display: flex;
  flex-direction: column;
  padding-bottom: 0;
  overflow: hidden;
}
.admin-shell .admin-shell__content:has(.assets-page--fixed-tables) > .assets-page--fixed-tables {
  flex: 1 1 auto;
  min-height: 0;
}

/*
 * 对象详情桌面固定模式：外层主内容区停止滚动，页面用父容器实际剩余高度约束正文。
 * 选择器只在详情页应用固定模式时生效，不影响目录和其他后台页面。
 */
.admin-shell .admin-shell__content:has(.entity-detail-page--fixed) {
  display: flex;
  flex-direction: column;
  padding-bottom: 0;
  overflow: hidden;
}
.admin-shell .admin-shell__content:has(.entity-detail-page--fixed) > .entity-detail-page--fixed {
  flex: 1 1 auto;
  min-height: 0;
}

/* 后台账号菜单沿用数据源列表“更多”菜单的排版规范。 */
.admin-account-actions-popper.el-popper {
  width: 172px;
  min-width: 172px;
  max-width: calc(100vw - 24px);
  padding: 0;
  border-color: var(--do-line);
  border-radius: var(--do-radius-md);
  background: rgba(255, 255, 255, .99);
  box-shadow: var(--do-shadow-lg);
}
.admin-account-actions-popper .el-popper__arrow::before { border-color: var(--do-line); background: #fff; }
.admin-account-actions-popper .el-dropdown-menu { padding: 6px; background: transparent; }
.admin-account-actions-popper .el-dropdown-menu__item {
  min-height: 38px;
  padding: 0 12px;
  gap: 8px;
  border-radius: 7px;
  color: var(--do-ink);
  font-size: 13px;
  font-weight: 500;
  line-height: 1.4;
}
.admin-account-actions-popper .el-dropdown-menu__item > svg { flex: 0 0 auto; width: 16px; height: 16px; }
.admin-account-actions-popper .el-dropdown-menu__item:not(.is-disabled):hover,
.admin-account-actions-popper .el-dropdown-menu__item:not(.is-disabled):focus {
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
}
.admin-account-actions-popper .el-dropdown-menu__item--divided { margin: 6px 0 0; border-top-color: var(--do-line); }
</style>
