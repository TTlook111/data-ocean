<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Bell, ChevronDown, LogOut, MessageSquareText, PanelLeftClose, PanelLeftOpen, UserRound } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import {
  getUnreadNotificationCount,
  listNotifications,
  markNotificationAsRead,
  markNotificationsBatchAsRead,
  type NotificationItem,
} from '../../api/notification'
import { findWorkspace, type AdminContextMode } from '../../router/adminNavigation'
import AdminDomainNav from './AdminDomainNav.vue'
import AdminWorkspaceNav from './AdminWorkspaceNav.vue'
import ScopeBar from './ScopeBar.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const notifications = ref<NotificationItem[]>([])
const notificationLoading = ref(false)
const unreadCount = ref(0)
const userMenuVisible = ref(false)
let notificationTimer: ReturnType<typeof window.setInterval> | undefined

const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const initial = computed(() => displayName.value.slice(0, 1).toUpperCase())
const routeTitle = computed(() => String(route.meta.title || 'DataOcean 后台'))
const workspace = computed(() => findWorkspace(String(route.meta.workspaceKey || '')))
const contextMode = computed<AdminContextMode>(() => (route.meta.contextMode as AdminContextMode) || workspace.value?.contextMode || 'none')
const hasWorkspace = computed(() => Boolean(workspace.value))
const hasUnread = computed(() => notifications.value.some((item) => !item.isRead))

function handleUserCommand(command: string) {
  userMenuVisible.value = false
  if (command === 'query') router.push('/query')
  if (command === 'profile') router.push('/profile')
  if (command === 'password') router.push('/change-password')
  if (command === 'logout') {
    auth.logout()
    router.push('/login')
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
  fetchUnreadCount()
  notificationTimer = window.setInterval(fetchUnreadCount, 60_000)
})

onBeforeUnmount(() => {
  if (notificationTimer) window.clearInterval(notificationTimer)
})
</script>

<template>
  <div class="admin-shell" :class="{ 'is-collapsed': collapsed }">
    <aside class="admin-shell__sidebar">
      <div class="admin-shell__brand">
        <RouterLink to="/admin/workbench" class="admin-shell__brand-link" aria-label="DataOcean 工作台">
          <span class="admin-shell__brand-mark">DO</span>
          <span class="admin-shell__brand-copy">
            <strong>DataOcean</strong>
            <small>数据查询治理平台</small>
          </span>
        </RouterLink>
        <button class="admin-shell__collapse" type="button" :aria-label="collapsed ? '展开导航' : '收起导航'" @click="collapsed = !collapsed">
          <PanelLeftOpen v-if="collapsed" :size="17" />
          <PanelLeftClose v-else :size="17" />
        </button>
      </div>

      <AdminDomainNav @navigate="userMenuVisible = false" />

      <div class="admin-shell__sidebar-footer">
        <RouterLink class="admin-shell__query-link" to="/query" :title="collapsed ? '进入智能问数' : undefined">
          <MessageSquareText :size="17" />
          <span>进入智能问数</span>
        </RouterLink>
      </div>
    </aside>

    <main class="admin-shell__main">
      <header class="admin-shell__topbar">
        <div class="admin-shell__title">
          <span v-if="hasWorkspace">{{ workspace?.label }}</span>
          <h1>{{ routeTitle }}</h1>
        </div>

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

          <el-dropdown trigger="click" @command="handleUserCommand">
            <button class="admin-shell__user" type="button" aria-label="用户菜单">
              <span class="admin-shell__avatar">{{ initial }}</span>
              <span class="admin-shell__user-name">{{ displayName }}</span>
              <ChevronDown :size="15" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile"><UserRound :size="15" />个人资料</el-dropdown-item>
                <el-dropdown-item command="password">修改密码</el-dropdown-item>
                <el-dropdown-item command="query"><MessageSquareText :size="15" />进入智能问数</el-dropdown-item>
                <el-dropdown-item divided command="logout"><LogOut :size="15" />退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <AdminWorkspaceNav />
      <ScopeBar v-if="contextMode === 'datasource' || contextMode === 'datasource-snapshot'" :mode="contextMode" />
      <section class="admin-shell__content">
        <RouterView />
      </section>
    </main>
  </div>
</template>

<style scoped>
.admin-shell {
  display: grid;
  grid-template-columns: 252px minmax(0, 1fr);
  min-height: 100vh;
  background: var(--do-bg);
}

.admin-shell.is-collapsed {
  grid-template-columns: 76px minmax(0, 1fr);
}

.admin-shell__sidebar {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  height: 100vh;
  border-right: 1px solid var(--do-line);
  background: linear-gradient(180deg, #f2f8ff 0%, var(--do-surface) 42%);
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
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.admin-shell__brand-mark,
.admin-shell__avatar {
  display: grid;
  place-items: center;
  border-radius: var(--do-radius-md);
  color: #fff;
  background: linear-gradient(135deg, var(--do-primary), #6aa84f);
  font-weight: 900;
}

.admin-shell__brand-mark {
  width: 38px;
  height: 38px;
  font-size: 13px;
}

.admin-shell__brand-copy {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.admin-shell__brand-copy strong {
  color: var(--do-ink);
  font-size: 16px;
}

.admin-shell__brand-copy small {
  overflow: hidden;
  color: var(--do-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-shell__collapse {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  color: var(--do-primary-strong);
  background: var(--do-surface);
  cursor: pointer;
}

.admin-shell__sidebar-footer {
  padding: 12px;
  border-top: 1px solid var(--do-line);
}

.admin-shell__query-link {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  padding: 0 12px;
  border-radius: var(--do-radius-md);
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
  font-size: 13px;
  font-weight: 800;
}

.admin-shell__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.admin-shell__topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  min-height: 72px;
  padding: 0 24px;
  border-bottom: 1px solid var(--do-line);
  background: rgba(255, 255, 255, .92);
  backdrop-filter: blur(14px);
}

.admin-shell__title {
  min-width: 0;
}

.admin-shell__title span {
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 800;
}

.admin-shell__title h1 {
  margin: 4px 0 0;
  overflow: hidden;
  color: var(--do-ink);
  font-size: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-shell__topbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.admin-shell__icon-button,
.admin-shell__user {
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
  display: flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 3px 10px 3px 4px;
}

.admin-shell__avatar {
  width: 30px;
  height: 30px;
  font-size: 13px;
}

.admin-shell__user-name {
  max-width: 130px;
  overflow: hidden;
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-shell__content {
  width: 100%;
  max-width: 1600px;
  margin: 0 auto;
  padding: 24px;
}

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
  justify-content: center;
  padding: 0 10px;
}

.admin-shell.is-collapsed .admin-shell__brand-copy,
.admin-shell.is-collapsed .admin-shell__collapse,
.admin-shell.is-collapsed .admin-domain-nav__item span,
.admin-shell.is-collapsed .admin-shell__query-link span,
.admin-shell.is-collapsed .admin-shell__user-name {
  display: none;
}

.admin-shell.is-collapsed .admin-domain-nav__item,
.admin-shell.is-collapsed .admin-shell__query-link {
  justify-content: center;
  padding: 0;
}

</style>
