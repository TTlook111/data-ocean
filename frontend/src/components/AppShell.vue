<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import {
  Activity,
  Building2,
  Calendar,
  ChevronLeft,
  ChevronDown,
  ClipboardList,
  Database,
  FolderSync,
  History,
  LayoutDashboard,
  LogOut,
  MessageSquareText,
  PanelLeftClose,
  PanelLeftOpen,
  ShieldCheck,
  ShieldAlert,
  Table2,
  UserCog,
  UserRound,
  Users,
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

const menuGroups: Array<{ label: string; items: MenuItem[] }> = [
  {
    label: '工作台',
    items: [
      { label: '概览', to: '/admin', icon: LayoutDashboard },
      { label: '问答端', to: '/query', icon: MessageSquareText },
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
    label: '个人',
    items: [{ label: '个人资料', to: '/profile', icon: UserRound }],
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

async function logout() {
  await auth.logout()
  await router.replace('/login')
}

function handleUserCommand(command: string) {
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
    logout()
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

        <el-dropdown class="topbar-user" trigger="click" @command="handleUserCommand">
          <button class="user-pill" type="button">
            <span>{{ displayName.slice(0, 1) }}</span>
            <strong>{{ displayName }}</strong>
            <small>{{ roleText }}</small>
            <ChevronDown class="user-chevron" :size="15" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="canEnterAdmin" command="admin">
                <UserCog :size="15" />
                后台管理
              </el-dropdown-item>
              <el-dropdown-item command="profile">
                <UserRound :size="15" />
                个人资料
              </el-dropdown-item>
              <el-dropdown-item command="password">
                <ShieldCheck :size="15" />
                修改密码
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <LogOut :size="15" />
                退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <section class="app-content">
        <RouterView />
      </section>
    </main>
  </div>
</template>
