<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Component } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import {
  Activity,
  Bell,
  BookOpen,
  Building2,
  Calendar,
  ChevronLeft,
  ClipboardList,
  Cpu,
  Database,
  FileText,
  FolderSync,
  GitBranch,
  HeartPulse,
  History,
  LayoutDashboard,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  SlidersHorizontal,
  ShieldCheck,
  ShieldAlert,
  Table2,
  Tag,
  TrendingUp,
  MessageSquare,
  UserCog,
  UserRound,
  Users,
  Workflow,
} from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { useGsapMotion } from '../composables/useGsapMotion'
import { roleCodesLabel } from '../utils/enumLabels'
import {
  getUnreadNotificationCount,
  listNotifications,
  markNotificationAsRead,
  type NotificationItem,
} from '../api/notification'
import AdminContextBar from './AdminContextBar.vue'

interface MenuItem {
  label: string
  to: string
  icon: Component
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
    label: '工作台',
    items: [
      { label: '概览', to: '/admin', icon: LayoutDashboard },
    ],
  },
  {
    label: '数据资产',
    items: [
      { label: '数据源管理', to: '/admin/datasources', icon: Database, permission: 'datasource:manage' },
      { label: '同步任务', to: '/admin/metadata/sync', icon: FolderSync, permission: 'metadata:manage' },
      { label: '表浏览器', to: '/admin/metadata/tables', icon: Table2, permission: 'metadata:manage' },
      { label: '目录搜索', to: '/admin/metadata/catalog', icon: Search, permission: 'metadata:manage' },
      { label: '快照列表', to: '/admin/metadata/snapshots', icon: History, permission: 'metadata:manage' },
      { label: '版本历史', to: '/admin/metadata/version-history', icon: GitBranch, permission: 'metadata:manage' },
      { label: '同步调度', to: '/admin/metadata/schedule', icon: Calendar, permission: 'metadata:manage' },
    ],
  },
  {
    label: '治理工作台',
    items: [
      { label: '质量看板', to: '/admin/governance/quality', icon: Activity, permission: 'metadata:manage' },
      { label: '问题清单', to: '/admin/governance/issues', icon: ClipboardList, permission: 'metadata:manage' },
      { label: '治理状态', to: '/admin/governance/status', icon: ShieldAlert, permission: 'metadata:manage' },
      { label: '快照生命周期', to: '/admin/metadata/lifecycle', icon: Workflow, permission: 'metadata:manage' },
      { label: '字段标签', to: '/admin/field/tags', icon: Tag, permission: 'field-tag:manage' },
      { label: '可信度看板', to: '/admin/field/confidence', icon: TrendingUp, permission: 'field-tag:manage' },
      { label: '反馈审核', to: '/admin/field/feedback-review', icon: MessageSquare, permission: 'field-tag:manage' },
    ],
  },
  {
    label: '语义资产',
    items: [
      { label: '知识库总览', to: '/admin/knowledge', icon: FileText, permission: 'knowledge:manage' },
      { label: '文档编辑器', to: '/admin/knowledge/editor', icon: ClipboardList, permission: 'knowledge:manage' },
      { label: '知识审核', to: '/admin/knowledge/review', icon: ShieldCheck, permission: 'knowledge:manage' },
      { label: '术语管理', to: '/admin/glossary/list', icon: BookOpen, permission: 'metadata:manage' },
      { label: 'Prompt 管理', to: '/admin/prompts', icon: SlidersHorizontal, permission: 'prompt:manage' },
    ],
  },
  {
    label: '权限与合规',
    items: [
      { label: '用户管理', to: '/admin/users', icon: Users, permission: 'user:manage' },
      { label: '角色管理', to: '/admin/roles', icon: ShieldCheck, permission: 'role:view' },
      { label: '部门管理', to: '/admin/departments', icon: Building2, permission: 'department:manage' },
      { label: '访问控制', to: '/admin/permission/access', icon: ShieldAlert, permission: 'security:manage' },
      { label: '策略编辑器', to: '/admin/permission/policies', icon: ShieldCheck, permission: 'security:manage' },
      { label: '审计日志', to: '/admin/audit/logs', icon: ClipboardList, permission: 'audit:view' },
      { label: '血缘查看', to: '/admin/audit/lineage', icon: GitBranch, permission: 'audit:view' },
      { label: '血缘图谱', to: '/admin/audit/lineage-graph', icon: GitBranch, permission: 'audit:view' },
    ],
  },
  {
    label: '系统运维',
    items: [
      { label: '服务健康', to: '/admin/system/health', icon: HeartPulse, permission: '*' },
      { label: '慢查询', to: '/admin/audit/slow-queries', icon: Activity, permission: 'audit:view' },
      { label: '操作日志', to: '/admin/system/operation-logs', icon: ClipboardList, permission: 'audit:view' },
      { label: 'AI 配置', to: '/admin/system/ai-config', icon: Cpu, permission: 'system:ai-config:view' },
    ],
  },
]

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
      items: group.items.filter((item) => canView(item.permission)),
    }))
    .filter((group) => group.items.length > 0),
)

const matchedMenuItem = computed(() =>
  menuGroups
    .flatMap((group) => group.items)
    .filter((item) => route.path === item.to || route.path.startsWith(`${item.to}/`))
    .sort((a, b) => b.to.length - a.to.length)[0],
)

function canView(permission?: string) {
  if (!permission) return true
  if (permission === 'system:ai-config:view' && permissions.value.includes('system:ai-config:manage')) return true
  return permissions.value.includes('*') || permissions.value.includes(permission)
}

function isActiveMenu(item: MenuItem) {
  return matchedMenuItem.value?.to === item.to
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
  await Promise.allSettled(unreadItems.map((item) => markNotificationAsRead(item.id)))
  unreadItems.forEach((item) => {
    item.isRead = true
  })
  await fetchUnreadCount()
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
            :to="item.to"
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
