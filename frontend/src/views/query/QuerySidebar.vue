<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { DropdownInstance } from 'element-plus'
import { ChevronsUpDown, History, LogOut, MessageSquarePlus, Search, ShieldCheck, Trash2, UserCog, UserRound } from 'lucide-vue-next'
import BrandMark from '../../components/common/BrandMark.vue'
import type { LocalSession } from '../../composables/useQuerySession'
import { useIamS1Store } from '../../stores/iamS1'

const iamS1 = useIamS1Store()
onMounted(() => { void iamS1.load() })

const props = defineProps<{
  datasourceSessions: LocalSession[]
  activeSessionId: string | undefined
  keyword: string
  displayName: string
  roleText: string
  canEnterAdmin: boolean
  mobileHistoryOpen: boolean
  loading?: boolean
  errorMessage?: string
}>()

const emit = defineEmits<{
  'select-session': [sessionId: string]
  'remove-session': [session: LocalSession]
  'new-session': []
  'toggle-mobile-history': []
  'user-command': [command: 'profile' | 'password' | 'admin' | 'logout']
  'update:keyword': [value: string]
  'retry-history': []
}>()

const searchOpen = ref(Boolean(props.keyword))
const searchInput = ref<HTMLInputElement>()
const searchToggle = ref<HTMLButtonElement>()
const userMenuOpen = ref(false)
const userDropdown = ref<DropdownInstance>()
const userMenuTrigger = ref<HTMLButtonElement>()
const sessionCount = computed(() => props.datasourceSessions.length)

// 筛选条件必须始终可见；关闭搜索同时清空父级关键词，避免列表被隐藏条件过滤。
async function toggleHistorySearch() {
  searchOpen.value = !searchOpen.value
  if (!searchOpen.value) emit('update:keyword', '')
  await nextTick()
  if (searchOpen.value) searchInput.value?.focus()
  else searchToggle.value?.focus()
}

function closeHistorySearch() {
  if (searchOpen.value) void toggleHistorySearch()
}

watch(() => props.keyword, value => { if (value) searchOpen.value = true })

function closeUserMenu() {
  userDropdown.value?.handleClose()
  userMenuTrigger.value?.focus()
}

const groupedSessions = computed(() => {
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const yesterdayStart = todayStart - 24 * 60 * 60 * 1000
  const groups = [
    { key: 'today', label: '今天', sessions: [] as LocalSession[] },
    { key: 'yesterday', label: '昨天', sessions: [] as LocalSession[] },
    { key: 'earlier', label: '更早', sessions: [] as LocalSession[] },
  ]
  for (const session of props.datasourceSessions) {
    const timestamp = new Date(session.updatedAt).getTime()
    if (Number.isNaN(timestamp)) continue
    if (timestamp >= todayStart) groups[0].sessions.push(session)
    else if (timestamp >= yesterdayStart) groups[1].sessions.push(session)
    else groups[2].sessions.push(session)
  }
  return groups.filter((group) => group.sessions.length)
})

function pad(value: number) {
  return String(value).padStart(2, '0')
}

/** 组内只保留必要精度：今天/昨天显示时分，更早显示月日，跨年显示完整日期。 */
function formatSessionTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const now = new Date()
  const sameDay = date.toDateString() === now.toDateString()
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  if (sameDay || date.toDateString() === yesterday.toDateString()) {
    return `${pad(date.getHours())}:${pad(date.getMinutes())}`
  }
  if (date.getFullYear() === now.getFullYear()) return `${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function runUserCommand(command: 'profile' | 'password' | 'admin' | 'logout') {
  emit('user-command', command)
}
</script>

<template>
  <aside class="query-sidebar" :class="{ 'mobile-history-open': mobileHistoryOpen }" aria-label="会话导航">
    <!-- 品牌与新建动作保持固定，历史列表滚动不会带走主要入口。 -->
    <RouterLink class="query-brand" to="/query" aria-label="DataOcean 智能问数">
      <BrandMark class="query-brand-mark" aria-hidden="true" />
      <div><strong>DataOcean</strong><small>智能问数</small></div>
    </RouterLink>

    <button class="new-session-button" type="button" :disabled="loading" @click="emit('new-session')">
      <MessageSquarePlus :size="16" aria-hidden="true" /><span>新建对话</span>
    </button>

    <button
      class="mobile-history-toggle"
      type="button"
      :aria-expanded="mobileHistoryOpen"
      @click="emit('toggle-mobile-history')"
    >
      <History :size="16" aria-hidden="true" /><span>历史会话</span><small>{{ sessionCount }}</small>
    </button>

    <!-- 历史仍按真实时间分组，搜索、选中和删除各自保留独立键盘入口。 -->
    <section class="history-section" aria-label="历史会话">
      <div class="history-heading">
        <span>历史会话</span>
        <small class="history-count">{{ sessionCount }}</small>
        <button ref="searchToggle" type="button" :aria-expanded="searchOpen" :aria-label="searchOpen ? '关闭会话搜索' : '搜索历史会话'" @click="toggleHistorySearch"><Search :size="15" aria-hidden="true" /></button>
      </div>
      <label v-if="searchOpen" class="history-search">
        <Search :size="14" aria-hidden="true" />
        <input ref="searchInput" :value="keyword" type="search" placeholder="搜索会话" aria-label="搜索历史会话" @input="emit('update:keyword', ($event.target as HTMLInputElement).value)" @keydown.esc.stop.prevent="closeHistorySearch" />
      </label>
      <div v-if="loading" class="history-empty" role="status">正在读取会话…</div>
      <div v-else-if="errorMessage" class="history-empty history-error" role="alert">{{ errorMessage }}<button class="retry-history" type="button" @click="emit('retry-history')">重试历史会话</button></div>
      <div v-else-if="!datasourceSessions.length" class="history-empty">{{ keyword.trim() ? '没有匹配的会话' : '还没有对话' }}</div>
      <div v-else class="history-list">
        <section v-for="group in groupedSessions" :key="group.key" class="history-group" :aria-label="group.label">
          <h3 class="history-group-label">{{ group.label }}</h3>
          <div v-for="session in group.sessions" :key="session.id" class="history-row" :class="{ active: session.id === activeSessionId }">
            <el-tooltip
              :content="session.title"
              placement="right"
              effect="light"
              :show-after="400"
              :trigger="['hover', 'focus']"
              popper-class="history-title-tooltip"
            >
              <button
                type="button"
                class="history-select"
                :aria-current="session.id === activeSessionId ? 'true' : undefined"
                @click="emit('select-session', session.id)"
              >
                <span class="history-copy">
                  <strong>{{ session.title }}</strong>
                  <small>{{ formatSessionTime(session.updatedAt) }}</small>
                </span>
              </button>
            </el-tooltip>
            <button type="button" class="history-delete" :aria-label="`删除会话 ${session.title}`" @click="emit('remove-session', session)"><Trash2 :size="14" /></button>
          </div>
        </section>
      </div>
    </section>

    <div class="sidebar-user-wrap">
      <!-- 复用后台相同的下拉组件，统一点击外部、方向键导航和关闭行为。 -->
      <el-dropdown ref="userDropdown" class="sidebar-user-dropdown" popper-class="query-user-menu-popper" trigger="click" placement="top-start" @command="runUserCommand" @visible-change="userMenuOpen = $event">
        <button ref="userMenuTrigger" class="sidebar-user" type="button" :aria-expanded="userMenuOpen" aria-haspopup="menu" aria-label="打开用户菜单" @keydown.esc.stop.prevent="closeUserMenu">
          <span class="sidebar-avatar" aria-hidden="true">{{ displayName.slice(0, 1) }}</span>
          <span class="sidebar-user-copy"><strong>{{ displayName }}</strong><small>{{ roleText }}</small></span>
          <ChevronsUpDown class="sidebar-user-expand" :size="15" aria-hidden="true" />
        </button>
        <template #dropdown>
          <el-dropdown-menu aria-label="用户菜单" @keydown.esc.stop.prevent="closeUserMenu">
            <li class="query-user-menu-profile" role="presentation"><strong>{{ displayName }}</strong><small>{{ roleText }}</small></li>
            <el-dropdown-item command="profile"><UserRound :size="16" aria-hidden="true" />个人资料</el-dropdown-item>
            <el-dropdown-item command="password"><ShieldCheck :size="16" aria-hidden="true" />修改密码</el-dropdown-item>
            <el-dropdown-item v-if="canEnterAdmin" command="admin"><UserCog :size="16" aria-hidden="true" />后台管理</el-dropdown-item>
            <el-dropdown-item divided command="logout" class="query-user-menu-logout"><LogOut :size="16" aria-hidden="true" />退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </aside>
</template>

<style scoped>
/* 问数历史与后台导航共用表面和选中对比色。 */
.query-sidebar { height: 100%; min-width: 0; min-height: 0; display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; gap: 22px; padding: 24px 16px 14px; color: var(--do-nav-text); background: var(--do-nav-bg); }
.query-brand { min-width: 0; height: 48px; display: grid; grid-template-columns: 32px minmax(0, 1fr); align-items: center; gap: 12px; padding-inline: 4px; color: var(--do-nav-text); text-decoration: none; }
.query-brand-mark { width: 32px; height: 44px; }
.query-brand strong, .query-brand small { display: block; }
.query-brand strong { font-size: 19px; font-weight: 650; letter-spacing: -.45px; line-height: 1.25; }
.query-brand small { margin-top: 3px; color: var(--do-nav-muted); font-size: 11px; }

/* 新建是导航中的主动作，加载期间沿用原禁用状态。 */
.new-session-button { min-height: 44px; display: inline-flex; align-items: center; justify-content: flex-start; gap: 10px; padding-inline: 14px; border: 1px solid var(--do-nav-line); border-radius: var(--do-radius-sm); color: var(--do-nav-text); background: var(--do-nav-hover); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: background var(--do-transition-fast), border-color var(--do-transition-fast); }
.new-session-button:hover:not(:disabled) { border-color: var(--do-nav-active); color: var(--do-nav-active-text); background: var(--do-nav-active); }
.new-session-button:disabled { opacity: .5; cursor: wait; }
.mobile-history-toggle { display: none; }

/* 搜索与分组标题固定在列表上方，当前会话用整行底色保持可辨认。 */
.history-section { min-height: 0; display: flex; flex-direction: column; gap: 10px; }
.history-heading, .history-search { flex-shrink: 0; }
.history-heading { min-height: 28px; display: flex; align-items: center; gap: 7px; padding-left: 8px; color: var(--do-nav-muted); font-size: 12px; }
.history-count { color: var(--do-nav-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.history-heading button { width: 30px; height: 30px; margin-left: auto; display: grid; place-items: center; border: 0; border-radius: var(--do-radius-sm); color: var(--do-nav-muted); background: transparent; cursor: pointer; }
.history-heading button:hover { color: var(--do-nav-text); background: var(--do-nav-hover); }
.history-search { min-height: 38px; display: grid; grid-template-columns: 18px minmax(0, 1fr); align-items: center; gap: 6px; padding: 0 10px; border: 1px solid var(--do-nav-line); border-radius: var(--do-radius-sm); color: var(--do-nav-muted); background: var(--do-nav-hover); }
.history-search:focus-within { border-color: var(--do-nav-accent); }
.history-search input { min-width: 0; width: 100%; border: 0; outline: 0; color: var(--do-nav-text); background: transparent; font: inherit; font-size: 13px; }
.history-search input::placeholder { color: var(--do-nav-muted); opacity: 1; }
.history-list { flex: 1; min-height: 0; display: grid; align-content: start; gap: 16px; overflow-y: auto; overscroll-behavior: contain; padding: 2px; scrollbar-width: thin; scrollbar-color: var(--do-nav-line) transparent; }
.history-group { min-width: 0; display: grid; gap: 3px; }
.history-group-label { margin: 0 0 5px; padding-inline: 8px; color: var(--do-nav-muted); font-size: 11px; font-weight: 500; }
.history-row { min-width: 0; min-height: 56px; display: grid; grid-template-columns: minmax(0, 1fr) 30px; align-items: center; border-radius: var(--do-radius-sm); color: var(--do-nav-text); }
.history-row:hover { background: var(--do-nav-hover); }
/* 深色选中行使用配套前景，标题、时间和操作图标保持可读。 */
.history-row.active { color: var(--do-nav-active-text); background: var(--do-nav-active); }
.history-select { min-width: 0; min-height: 56px; display: flex; align-items: center; padding: 8px 2px 8px 10px; border: 0; border-radius: var(--do-radius-sm); color: inherit; background: transparent; text-align: left; cursor: pointer; }
.history-copy { min-width: 0; flex: 1; display: block; }
.history-copy strong, .history-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-copy strong { font-size: 13px; font-weight: 400; line-height: 1.5; }
.history-row.active strong { font-weight: 600; }
.history-row.active .history-copy small, .history-row.active .history-delete:not(:hover) { color: var(--do-nav-active-text); }
.history-row.active .history-select:focus-visible, .history-row.active .history-delete:focus-visible { outline: 2px solid var(--do-nav-active-text); outline-offset: -3px; }
.history-copy small { margin-top: 3px; color: var(--do-nav-muted); font-size: 11px; line-height: 1.3; font-variant-numeric: tabular-nums; }
.history-delete { width: 28px; height: 32px; display: grid; place-items: center; border: 0; border-radius: var(--do-radius-sm); color: var(--do-nav-muted); background: transparent; cursor: pointer; opacity: 0; transition: opacity var(--do-transition-fast); }
.history-row:hover .history-delete, .history-row:focus-within .history-delete, .history-row.active .history-delete { opacity: 1; }
.history-delete:hover { color: var(--do-danger); background: var(--do-danger-soft); }
.history-empty { padding: 14px 8px; color: var(--do-nav-muted); font-size: 12px; line-height: 1.7; }
.history-error { color: var(--do-danger); }
.retry-history { display: block; min-height: 36px; margin-top: 10px; border: 1px solid var(--do-nav-line); border-radius: var(--do-radius-sm); padding-inline: 10px; color: var(--do-nav-text); background: var(--do-nav-hover); font: inherit; cursor: pointer; }

/* 当前身份始终位于导航底部，显式箭头表明这里还有账户操作。 */
.sidebar-user-wrap { position: relative; padding-top: 12px; border-top: 1px solid var(--do-nav-line); }
.sidebar-user-dropdown { width: 100%; }
.sidebar-user { width: 100%; min-height: 48px; display: grid; grid-template-columns: 34px minmax(0, 1fr) 15px; align-items: center; gap: 10px; padding: 7px 6px; border: 0; border-radius: var(--do-radius-sm); color: var(--do-nav-text); background: transparent; text-align: left; cursor: pointer; transition: background var(--do-transition-fast); }
.sidebar-user:hover { background: var(--do-nav-hover); }
.sidebar-avatar { width: 34px; height: 34px; display: grid; place-items: center; border-radius: var(--do-radius-sm); color: var(--do-nav-active-text); background: var(--do-nav-active); font-size: 13px; font-weight: 600; }
.sidebar-user-copy { min-width: 0; }
.sidebar-user-copy strong, .sidebar-user-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sidebar-user-copy strong { font-size: 13px; font-weight: 500; line-height: 1.3; }
.sidebar-user-copy small { margin-top: 3px; color: var(--do-nav-muted); font-size: 11px; }
.sidebar-user-expand { color: var(--do-nav-muted); }
.query-brand:focus-visible, .new-session-button:focus-visible, .mobile-history-toggle:focus-visible, .history-heading button:focus-visible, .history-select:focus-visible, .history-delete:focus-visible, .sidebar-user:focus-visible, .retry-history:focus-visible { outline: 2px solid var(--do-nav-accent); outline-offset: 2px; }

/* 沿用窄窗口历史展开能力，折叠只改变呈现而不另建一套会话状态。 */
@media (max-width: 1100px) and (min-width: 769px) { .query-sidebar { padding-inline: 12px; } }
@media (max-width: 768px) {
  .query-sidebar { height: auto; grid-template-columns: minmax(0, 1fr) auto 44px; grid-template-rows: 44px auto auto; grid-template-areas: "brand new user" "toggle toggle toggle" "history history history"; gap: 8px; padding: 10px 12px; }
  .query-brand { grid-area: brand; height: 44px; grid-template-columns: 28px minmax(0, 1fr); gap: 9px; padding: 0; }
  .query-brand-mark { width: 28px; height: 38px; }
  .query-brand strong { font-size: 16px; }
  .query-brand small { display: none; }
  .new-session-button { grid-area: new; min-height: 40px; padding-inline: 10px; }
  .mobile-history-toggle { grid-area: toggle; min-height: 38px; display: flex; align-items: center; gap: 8px; padding: 0 10px; border: 1px solid var(--do-nav-line); border-radius: var(--do-radius-sm); color: var(--do-nav-text); background: var(--do-nav-hover); font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
  .mobile-history-toggle small { margin-left: auto; color: var(--do-nav-muted); }
  .history-section { display: none; grid-area: history; max-height: 40dvh; }
  .mobile-history-open .history-section { display: flex; }
  .history-list { max-height: 33dvh; }
  .history-row { grid-template-columns: minmax(0, 1fr) 36px; }
  .history-delete { width: 36px; height: 40px; opacity: 1; }
  .sidebar-user-wrap { grid-area: user; padding-top: 0; border-top: 0; }
  .sidebar-user { width: 44px; height: 44px; min-height: 44px; display: flex; justify-content: center; padding: 4px; }
  .sidebar-user-copy, .sidebar-user-expand { display: none; }
}
</style>

<style>
.query-user-menu-popper.el-popper { max-width: calc(100vw - 24px); border-color: var(--do-line); border-radius: var(--do-radius-md); box-shadow: var(--do-shadow-lg); }
.query-user-menu-popper .el-dropdown-menu { min-width: 200px; padding: 6px; background: var(--do-surface); }
.query-user-menu-popper .query-user-menu-profile { display: none; }
.query-user-menu-popper .el-dropdown-menu__item { min-height: 40px; gap: 10px; border-radius: var(--do-radius-sm); color: var(--do-ink); font-size: 13px; }
.query-user-menu-popper .el-dropdown-menu__item:not(.is-disabled):hover,
.query-user-menu-popper .el-dropdown-menu__item:not(.is-disabled):focus { color: var(--do-primary-strong); background: var(--do-primary-soft); }
.query-user-menu-popper .query-user-menu-logout { color: var(--do-danger); }
.query-user-menu-popper .query-user-menu-logout:not(.is-disabled):hover,
.query-user-menu-popper .query-user-menu-logout:not(.is-disabled):focus { color: var(--do-danger); background: var(--do-danger-soft); }
@media (max-width: 768px) {
  /* 窄屏触发器只显示头像，菜单内补回当前身份，方便操作前核对账号。 */
  .query-user-menu-popper .query-user-menu-profile { display: grid; gap: 4px; padding: 8px 12px 12px; margin-bottom: 4px; border-bottom: 1px solid var(--do-line); color: var(--do-ink); overflow-wrap: anywhere; }
  .query-user-menu-profile strong { font-size: 13px; }
  .query-user-menu-profile small { font-size: 12px; color: var(--do-muted); }
}
/* 历史标题单行截断后，用浮层补齐悬停与键盘聚焦时的完整标题。 */
.history-title-tooltip.el-popper.el-tooltip__popper {
  max-width: min(280px, calc(100vw - 24px));
  border-color: var(--do-line);
  color: var(--do-ink);
  font-size: 11.5px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
</style>
