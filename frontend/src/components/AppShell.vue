<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Component } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import {
  Bell,
  BookOpen,
  ChevronLeft,
  Database,
  HeartPulse,
  LayoutDashboard,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  ShieldCheck,
  ShieldAlert,
  Settings,
  MessageSquare,
  UserCog,
  UserRound,
  Workflow,
} from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { useGsapMotion } from '../composables/useGsapMotion'
import { roleCodesLabel } from '../utils/enumLabels'
import {
  getUnreadNotificationCount,
  listNotifications,
  markNotificationAsRead,
  markNotificationsBatchAsRead,
  type NotificationItem,
} from '../api/notification'
import AdminContextBar from './AdminContextBar.vue'

interface MenuItem {
  key: string
  label: string
  to: string
  icon: Component
  permission?: string
  match?: string[]
}

interface WorkspaceLink {
  label: string
  to: string
  permission?: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const drawerVisible = ref(false)
const shellRef = ref<HTMLElement | null>(null)
const { gsap, reveal, withContext } = useGsapMotion(shellRef)
const notificationLoading = ref(false)
const unreadNotificationCount = ref(0)
const notifications = ref<NotificationItem[]>([])
let notificationTimer: ReturnType<typeof window.setInterval> | undefined
const hasUnreadInPanel = computed(() => notifications.value.some((item) => !item.isRead))

const menuGroups: Array<{ label: string; items: MenuItem[] }> = [
  {
    label: '总览',
    items: [
      { key: 'workbench', label: '工作台', to: '/admin', icon: LayoutDashboard },
    ],
  },
  {
    label: '数据源生命周期',
    items: [
      {
        key: 'datasource',
        label: '数据源中心',
        to: '/admin/datasources',
        icon: Database,
        permission: 'datasource:manage',
        match: [
          '/admin/datasources',
          '/admin/metadata/sync',
          '/admin/metadata/snapshots',
          '/admin/metadata/tables',
          '/admin/metadata/catalog',
          '/admin/metadata/diff',
          '/admin/metadata/version-history',
          '/admin/metadata/schedule',
        ],
      },
      {
        key: 'governance',
        label: '治理中心',
        to: '/admin/governance/quality',
        icon: Workflow,
        permission: 'metadata:manage',
        match: ['/admin/governance', '/admin/metadata/lifecycle', '/admin/field'],
      },
      {
        key: 'knowledge',
        label: '语义中心',
        to: '/admin/knowledge',
        icon: BookOpen,
        permission: 'knowledge:manage',
        match: ['/admin/knowledge', '/admin/glossary', '/admin/prompts'],
      },
    ],
  },
  {
    label: '开放与运营',
    items: [
      {
        key: 'permission',
        label: '权限与开放',
        to: '/admin/permission/access',
        icon: ShieldAlert,
        permission: 'security:manage',
        match: ['/admin/permission', '/admin/users', '/admin/roles', '/admin/departments'],
      },
      {
        key: 'operation',
        label: '运营与安全',
        to: '/admin/audit/logs',
        icon: HeartPulse,
        permission: 'audit:view',
        match: ['/admin/audit', '/admin/system/health'],
      },
      {
        key: 'settings',
        label: '系统设置',
        to: '/admin/system/ai-config',
        icon: Settings,
        permission: 'system:ai-config:view',
        match: ['/admin/system/ai-config', '/admin/system/operation-logs'],
      },
    ],
  },
]

const workspaceLinks: Record<string, WorkspaceLink[]> = {
  datasource: [
    { label: '数据源总览', to: '/admin/datasources', permission: 'datasource:manage' },
    { label: '同步任务', to: '/admin/metadata/sync', permission: 'metadata:manage' },
    { label: '快照列表', to: '/admin/metadata/snapshots', permission: 'metadata:manage' },
    { label: '表浏览器', to: '/admin/metadata/tables', permission: 'metadata:manage' },
    { label: '目录搜索', to: '/admin/metadata/catalog', permission: 'metadata:manage' },
    { label: '快照差异', to: '/admin/metadata/diff', permission: 'metadata:manage' },
    { label: '版本历史', to: '/admin/metadata/version-history', permission: 'metadata:manage' },
    { label: '同步调度', to: '/admin/metadata/schedule', permission: 'metadata:manage' },
  ],
  governance: [
    { label: '治理总览', to: '/admin/governance/quality', permission: 'metadata:manage' },
    { label: '问题处理', to: '/admin/governance/issues', permission: 'metadata:manage' },
    { label: '治理状态', to: '/admin/governance/status', permission: 'metadata:manage' },
    { label: '快照生命周期', to: '/admin/metadata/lifecycle', permission: 'metadata:manage' },
    { label: '字段标签', to: '/admin/field/tags', permission: 'field-tag:manage' },
    { label: '字段可信度', to: '/admin/field/confidence', permission: 'field-tag:manage' },
    { label: '反馈闭环', to: '/admin/field/feedback-review', permission: 'field-tag:manage' },
  ],
  knowledge: [
    { label: '知识文档', to: '/admin/knowledge', permission: 'knowledge:manage' },
    { label: '文档编辑', to: '/admin/knowledge/editor', permission: 'knowledge:manage' },
    { label: '知识审核', to: '/admin/knowledge/review', permission: 'knowledge:manage' },
    { label: '术语体系', to: '/admin/glossary/list', permission: 'metadata:manage' },
    { label: 'Prompt 策略', to: '/admin/prompts', permission: 'prompt:manage' },
  ],
  permission: [
    { label: '访问控制', to: '/admin/permission/access', permission: 'security:manage' },
    { label: '授权策略', to: '/admin/permission/policies', permission: 'security:manage' },
    { label: '用户', to: '/admin/users', permission: 'user:manage' },
    { label: '角色', to: '/admin/roles', permission: 'role:view' },
    { label: '部门', to: '/admin/departments', permission: 'department:manage' },
  ],
  operation: [
    { label: '审计日志', to: '/admin/audit/logs', permission: 'audit:view' },
    { label: '慢查询', to: '/admin/audit/slow-queries', permission: 'audit:view' },
    { label: '血缘查看', to: '/admin/audit/lineage', permission: 'audit:view' },
    { label: '血缘图谱', to: '/admin/audit/lineage-graph', permission: 'audit:view' },
    { label: '服务健康', to: '/admin/system/health', permission: '*' },
  ],
  settings: [
    { label: 'AI 配置', to: '/admin/system/ai-config', permission: 'system:ai-config:view' },
    { label: '操作日志', to: '/admin/system/operation-logs', permission: 'audit:view' },
  ],
}

const permissions = computed(() => auth.user?.permissions || auth.currentUser?.permissions || [])
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const roleText = computed(() => roleCodesLabel(auth.currentUser?.roles || auth.user?.roles, 'DataOcean'))
const currentTitle = computed(() => String(route.meta.title || matchedMenuItem.value?.label || 'DataOcean'))
const currentSection = computed(() => String(route.meta.section || '工作台'))
const contextRoutes = [
  '/admin/datasources',
  '/admin/metadata',
  '/admin/governance',
  '/admin/knowledge',
  '/admin/field',
  '/admin/audit',
  '/admin/glossary',
  '/admin/permission',
]
const showAdminContext = computed(() => contextRoutes.some((path) => route.path === path || route.path.startsWith(`${path}/`)))
const adminPermissionCodes = [
  'admin:view',
  'datasource:manage',
  'metadata:manage',
  'skills:manage',
  'prompt:manage',
  'field:manage',
  'field-tag:manage',
  'feedback:review',
  'audit:view',
  'user:manage',
  'role:manage',
  'role:view',
  'department:manage',
  'knowledge:manage',
  'security:manage',
  'system:ai-config:view',
  'system:ai-config:manage',
]
const canEnterAdmin = computed(() => permissions.value.includes('*') || adminPermissionCodes.some((code) => permissions.value.includes(code)))

const visibleGroups = computed(() =>
  menuGroups
    .map((group) => ({
      ...group,
      items: group.items.filter(canViewMenuItem),
    }))
    .filter((group) => group.items.length > 0),
)

function canViewMenuItem(item: MenuItem) {
  return canView(item.permission) || Boolean(workspaceLinks[item.key]?.some((link) => canView(link.permission)))
}

function navTarget(item: MenuItem) {
  return workspaceLinks[item.key]?.find((link) => canView(link.permission))?.to || item.to
}

function routeMatches(item: Pick<MenuItem, 'to' | 'match'>) {
  const paths = [item.to, ...(item.match || [])]
  return paths.some((path) => route.path === path || route.path.startsWith(`${path}/`))
}

const matchedMenuItem = computed(() =>
  menuGroups
    .flatMap((group) => group.items)
    .filter(routeMatches)
    .sort((a, b) => {
      const aLength = Math.max(a.to.length, ...(a.match || []).map((path) => path.length))
      const bLength = Math.max(b.to.length, ...(b.match || []).map((path) => path.length))
      return bLength - aLength
    })[0],
)

const activeWorkspaceLinks = computed(() =>
  (workspaceLinks[matchedMenuItem.value?.key || ''] || []).filter((item) => canView(item.permission)),
)

const activeWorkspaceLink = computed(() =>
  activeWorkspaceLinks.value
    .filter((item) => route.path === item.to || route.path.startsWith(`${item.to}/`))
    .sort((a, b) => b.to.length - a.to.length)[0],
)

function canView(permission?: string) {
  if (!permission) return true
  if (permission === 'system:ai-config:view' && permissions.value.includes('system:ai-config:manage')) return true
  return permissions.value.includes('*') || permissions.value.includes(permission)
}

function isActiveMenu(item: MenuItem) {
  return matchedMenuItem.value?.key === item.key
}

function isActiveWorkspaceLink(item: WorkspaceLink) {
  return activeWorkspaceLink.value?.to === item.to
}

function handleUserCommand(command: string) {
  drawerVisible.value = false
  if (command === 'query') {
    router.push('/query')
    return
  }
  if (command === 'admin') {
    router.push('/admin')
    return
  }
  if (command === 'profile') {
    router.push('/profile')
    return
  }
  if (command === 'password') {
    router.push('/change-password')
    return
  }
  if (command === 'logout') {
    auth.logout()
    router.push('/login')
  }
}

async function fetchUnreadCount() {
  try {
    const result = await getUnreadNotificationCount()
    unreadNotificationCount.value = result.data?.count ?? 0
  } catch {
    unreadNotificationCount.value = 0
  }
}

async function fetchNotifications() {
  notificationLoading.value = true
  try {
    const result = await listNotifications({ page: 1, pageSize: 8 })
    notifications.value = result.data?.records ?? []
    await fetchUnreadCount()
  } catch {
    notifications.value = []
  } finally {
    notificationLoading.value = false
  }
}

async function handleReadNotification(item: NotificationItem) {
  if (!item.isRead) {
    try {
      await markNotificationAsRead(item.id)
      item.isRead = true
      unreadNotificationCount.value = Math.max(0, unreadNotificationCount.value - 1)
    } catch {
      await fetchUnreadCount()
    }
  }
}

async function markVisibleNotificationsAsRead() {
  const unreadItems = notifications.value.filter((item) => !item.isRead)
  if (!unreadItems.length) return

  const ids = unreadItems.map((item) => item.id)

  try {
    // 使用批量接口
    await markNotificationsBatchAsRead(ids)

    // 更新本地状态
    unreadItems.forEach((item) => {
      item.isRead = true
    })
    await fetchUnreadCount()
  } catch (error) {
    console.error('批量标记已读失败:', error)
  }
}

function notificationTypeLabel(type?: string) {
  const labels: Record<string, string> = {
    FIELD_CONFIDENCE_ALERT: '字段治理',
    SNAPSHOT_PUBLISHED: '快照发布',
    SNAPSHOT_EXPIRED: '快照过期',
    SYSTEM_ALERT: '系统',
  }
  return labels[type || ''] || '通知'
}

function formatNotificationTime(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

onMounted(() => {
  withContext(() => {
    reveal('.sidebar-brand, .nav-section, .app-topbar', {
      y: 14,
      stagger: 0.045,
    })
    reveal('.nav-item', {
      y: 8,
      duration: 0.34,
      stagger: 0.022,
    })
  })
  fetchUnreadCount()
  notificationTimer = window.setInterval(fetchUnreadCount, 60_000)
})

onBeforeUnmount(() => {
  if (notificationTimer) {
    window.clearInterval(notificationTimer)
  }
})

// 路由切换时不再对内容区做弹跳动画，避免视觉上像「整个页面在刷新」的错觉

watch(collapsed, async () => {
  await nextTick()
  if (!shellRef.value) return
  gsap.from(shellRef.value.querySelectorAll('.nav-item'), {
    autoAlpha: 0,
    x: collapsed.value ? -8 : 8,
    duration: 0.2,
    ease: 'power2.out',
    stagger: 0.012,
    clearProps: 'transform,opacity,visibility',
  })
})
</script>

<template>
  <div ref="shellRef" class="app-shell" :class="{ 'is-collapsed': collapsed }">
    <aside class="app-sidebar">
      <div class="sidebar-brand">
        <RouterLink to="/admin" class="brand-link" aria-label="DataOcean 工作台">
          <span class="brand-mark">DO</span>
          <span class="brand-copy">
            <strong>DataOcean</strong>
            <small>智能查询治理平台</small>
          </span>
        </RouterLink>
        <button class="sidebar-toggle" type="button" @click="collapsed = !collapsed">
          <PanelLeftOpen v-if="collapsed" :size="18" />
          <PanelLeftClose v-else :size="18" />
        </button>
      </div>

      <nav class="side-nav" aria-label="主导航">
        <section v-for="group in visibleGroups" :key="group.label" class="nav-section">
          <p>{{ group.label }}</p>
          <RouterLink
            v-for="item in group.items"
            :key="item.to"
            class="nav-item"
            :class="{ active: isActiveMenu(item) }"
            :to="navTarget(item)"
            :title="collapsed ? item.label : undefined"
          >
            <component :is="item.icon" :size="18" />
            <span>{{ item.label }}</span>
          </RouterLink>
        </section>
      </nav>
    </aside>

    <main class="app-main">
      <header class="app-topbar">
        <div class="topbar-title">
          <span><ChevronLeft :size="16" /> {{ currentSection }}</span>
          <h1>{{ currentTitle }}</h1>
        </div>

        <div class="topbar-actions">
          <el-popover
            placement="bottom-end"
            width="360"
            trigger="click"
            popper-class="notification-popover"
            @show="fetchNotifications"
          >
            <template #reference>
              <button class="icon-button" type="button" aria-label="通知">
                <el-badge :value="unreadNotificationCount" :hidden="unreadNotificationCount === 0" :max="99">
                  <Bell :size="19" />
                </el-badge>
              </button>
            </template>

            <div class="notification-panel" v-loading="notificationLoading">
              <div class="notification-panel__header">
                <div>
                  <strong>通知中心</strong>
                  <span>{{ unreadNotificationCount }} 条未读</span>
                </div>
                <button
                  class="notification-mark-read"
                  type="button"
                  :disabled="!hasUnreadInPanel"
                  @click="markVisibleNotificationsAsRead"
                >
                  全部已读
                </button>
              </div>
              <div v-if="notifications.length" class="notification-list">
                <button
                  v-for="item in notifications"
                  :key="item.id"
                  class="notification-item"
                  :class="{ unread: !item.isRead }"
                  type="button"
                  @click="handleReadNotification(item)"
                >
                  <span class="notification-dot"></span>
                  <span class="notification-body">
                    <span class="notification-meta">
                      <b>{{ notificationTypeLabel(item.type) }}</b>
                      <em>{{ formatNotificationTime(item.createdAt) }}</em>
                    </span>
                    <strong>{{ item.title }}</strong>
                    <small>{{ item.content }}</small>
                  </span>
                </button>
              </div>
              <el-empty v-else description="暂无通知" :image-size="72" />
            </div>
          </el-popover>

          <button class="user-pill" type="button" @click="drawerVisible = true">
            <span class="user-avatar">{{ displayName.slice(0, 1) }}</span>
            <strong>{{ displayName }}</strong>
          </button>
        </div>
      </header>

      <AdminContextBar v-if="showAdminContext" />

      <nav v-if="activeWorkspaceLinks.length" class="workspace-nav" aria-label="工作区导航">
        <RouterLink
          v-for="item in activeWorkspaceLinks"
          :key="item.to"
          class="workspace-nav-item"
          :class="{ active: isActiveWorkspaceLink(item) }"
          :to="item.to"
        >
          {{ item.label }}
        </RouterLink>
      </nav>

      <section class="app-content">
        <RouterView />
      </section>
    </main>

    <el-drawer v-model="drawerVisible" direction="rtl" size="280px" :show-close="false">
      <template #header>
        <div class="drawer-profile">
          <div class="drawer-avatar">{{ displayName.slice(0, 1) }}</div>
          <div class="drawer-info">
            <strong>{{ displayName }}</strong>
            <small>{{ roleText }}</small>
          </div>
        </div>
      </template>
      <nav class="drawer-nav">
        <button class="drawer-item" @click="handleUserCommand('query')">
          <MessageSquare :size="18" />
          <span>返回问答端</span>
        </button>
        <button class="drawer-item" @click="handleUserCommand('profile')">
          <UserRound :size="18" />
          <span>个人资料</span>
        </button>
        <button class="drawer-item" @click="handleUserCommand('password')">
          <ShieldCheck :size="18" />
          <span>修改密码</span>
        </button>
        <button v-if="canEnterAdmin" class="drawer-item" @click="handleUserCommand('admin')">
          <UserCog :size="18" />
          <span>后台管理</span>
        </button>
        <div class="drawer-divider"></div>
        <button class="drawer-item drawer-item--danger" @click="handleUserCommand('logout')">
          <LogOut :size="18" />
          <span>退出登录</span>
        </button>
      </nav>
    </el-drawer>
  </div>
</template>

<style scoped>
/* 通知弹窗样式 */
.notification-popover {
  padding: 0 !important;
}

.notification-panel {
  min-height: 140px;
}

.notification-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px;
  border-bottom: 1px solid var(--do-line);
}

.notification-panel__header strong {
  display: block;
  color: var(--do-ink);
  font-size: 14px;
}

.notification-panel__header span {
  display: block;
  margin-top: 3px;
  color: var(--do-muted);
  font-size: 12px;
}

.notification-mark-read {
  flex: 0 0 auto;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  color: var(--do-primary);
  background: var(--do-surface);
  cursor: pointer;
}

.notification-mark-read:disabled {
  color: var(--do-muted);
  cursor: not-allowed;
  opacity: 0.65;
}

.notification-list {
  max-height: 360px;
  overflow: auto;
  padding: 8px;
}

.notification-item {
  width: 100%;
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr);
  gap: 10px;
  padding: 11px 10px;
  border: 0;
  border-radius: 8px;
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.notification-item.unread {
  background: rgba(77, 143, 220, 0.07);
}

.notification-item:hover {
  background: var(--do-bg);
}

.notification-dot {
  width: 7px;
  height: 7px;
  margin-top: 6px;
  border-radius: 999px;
  background: transparent;
}

.notification-item.unread .notification-dot {
  background: var(--do-primary);
}

.notification-body {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.notification-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.notification-meta b {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 7px;
  border-radius: 7px;
  color: var(--do-primary);
  background: rgba(77, 143, 220, 0.1);
  font-size: 11px;
}

.notification-body strong {
  color: var(--do-ink);
  font-size: 13px;
  line-height: 1.35;
}

.notification-body small {
  display: -webkit-box;
  overflow: hidden;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.notification-meta em {
  flex: 0 0 auto;
  color: var(--do-muted);
  font-size: 11px;
  font-style: normal;
}

/* 顶部操作栏样式 */
.topbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--do-muted);
  cursor: pointer;
  transition: all 0.2s;
}

.icon-button:hover {
  background: var(--do-bg);
  color: var(--do-ink);
}

.workspace-nav {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
  padding: 12px 16px;
  border-bottom: 1px solid var(--do-line);
  background: #fff;
}

.workspace-nav-item {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  color: var(--do-muted);
  font-size: 13px;
  font-weight: 800;
  text-decoration: none;
  background: var(--do-surface);
  transition: color 0.2s, border-color 0.2s, background 0.2s;
}

.workspace-nav-item:hover {
  color: var(--do-primary-strong);
  border-color: rgba(77, 143, 220, 0.35);
  background: rgba(77, 143, 220, 0.07);
}

.workspace-nav-item.active {
  color: var(--do-primary-strong);
  border-color: rgba(77, 143, 220, 0.45);
  background: rgba(77, 143, 220, 0.12);
}
</style>
