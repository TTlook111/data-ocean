<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import {
  Activity,
  Building2,
  Calendar,
  ChevronLeft,
  ClipboardList,
  Database,
  FolderSync,
  GitBranch,
  History,
  LayoutDashboard,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  ShieldCheck,
  ShieldAlert,
  Table2,
  UserCog,
  UserRound,
  Users,
  Workflow,
} from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'

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

const menuGroups: Array<{ label: string; items: MenuItem[] }> = [
  {
    label: '工作台',
    items: [
      { label: '概览', to: '/admin', icon: LayoutDashboard },
    ],
  },
  {
    label: '治理管理',
    items: [
      { label: '用户管理', to: '/admin/users', icon: Users, permission: 'user:manage' },
      { label: '角色管理', to: '/admin/roles', icon: ShieldCheck, permission: 'role:view' },
      { label: '部门管理', to: '/admin/departments', icon: Building2, permission: 'department:manage' },
      { label: '数据源管理', to: '/admin/datasources', icon: Database, permission: 'datasource:manage' },
    ],
  },
  {
    label: '元数据管理',
    items: [
      { label: '同步任务', to: '/admin/metadata/sync', icon: FolderSync, permission: 'metadata:manage' },
      { label: '快照列表', to: '/admin/metadata/snapshots', icon: History, permission: 'metadata:manage' },
      { label: '表浏览器', to: '/admin/metadata/tables', icon: Table2, permission: 'metadata:manage' },
      { label: '同步调度', to: '/admin/metadata/schedule', icon: Calendar, permission: 'metadata:manage' },
    ],
  },
  {
    label: '元数据治理',
    items: [
      { label: '质量看板', to: '/admin/governance/quality', icon: Activity, permission: 'metadata:manage' },
      { label: '问题清单', to: '/admin/governance/issues', icon: ClipboardList, permission: 'metadata:manage' },
      { label: '治理状态', to: '/admin/governance/status', icon: ShieldAlert, permission: 'metadata:manage' },
    ],
  },
  {
    label: '版本管理',
    items: [
      { label: '快照生命周期', to: '/admin/metadata/lifecycle', icon: Workflow, permission: 'metadata:manage' },
      { label: '版本历史', to: '/admin/metadata/version-history', icon: GitBranch, permission: 'metadata:manage' },
    ],
  },
]

const permissions = computed(() => auth.user?.permissions || auth.currentUser?.permissions || [])
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const roleText = computed(() => (auth.user?.roles?.length ? auth.user.roles.join(' / ') : 'DataOcean'))
const currentTitle = computed(() => String(route.meta.title || matchedMenuItem.value?.label || 'DataOcean'))
const currentSection = computed(() => String(route.meta.section || '工作台'))
const adminPermissionCodes = [
  'admin:view',
  'datasource:manage',
  'metadata:manage',
  'skills:manage',
  'prompt:manage',
  'field:manage',
  'feedback:review',
  'audit:view',
  'user:manage',
  'role:manage',
  'role:view',
  'department:manage',
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
  menuGroups.flatMap((group) => group.items).find((item) => route.path === item.to || route.path.startsWith(`${item.to}/`)),
)

function canView(permission?: string) {
  if (!permission) return true
  return permissions.value.includes('*') || permissions.value.includes(permission)
}

function handleUserCommand(command: string) {
  drawerVisible.value = false
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
</script>

<template>
  <div class="app-shell" :class="{ 'is-collapsed': collapsed }">
    <aside class="app-sidebar">
      <div class="sidebar-brand">
        <RouterLink to="/admin" class="brand-link" aria-label="DataOcean 工作台">
          <span class="brand-mark">DO</span>
          <span class="brand-copy">
            <strong>DataOcean</strong>
            <small>NL2SQL Governance</small>
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
            :class="{ active: route.path === item.to }"
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

        <button class="user-pill" type="button" @click="drawerVisible = true">
          <span class="user-avatar">{{ displayName.slice(0, 1) }}</span>
          <strong>{{ displayName }}</strong>
        </button>
      </header>

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
        <button class="drawer-item" @click="handleUserCommand('profile')">
          <UserRound :size="18" />
          <span>个人资料</span>
        </button>
        <button v-if="canEnterAdmin" class="drawer-item" @click="handleUserCommand('admin')">
          <UserCog :size="18" />
          <span>后台管理</span>
        </button>
        <button class="drawer-item" @click="handleUserCommand('password')">
          <ShieldCheck :size="18" />
          <span>修改密码</span>
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
