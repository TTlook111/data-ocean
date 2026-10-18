<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { History, LogOut, MessageSquarePlus, Search, ShieldCheck, Trash2, UserCog, UserRound } from 'lucide-vue-next'
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
}>()

const emit = defineEmits<{
  'select-session': [sessionId: string]
  'remove-session': [session: LocalSession]
  'new-session': []
  'toggle-mobile-history': []
  'user-command': [command: 'profile' | 'password' | 'admin' | 'logout']
  'update:keyword': [value: string]
}>()

const searchOpen = ref(false)
const userMenuOpen = ref(false)
const sessionCount = computed(() => props.datasourceSessions.length)

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
  userMenuOpen.value = false
  emit('user-command', command)
}
</script>

<template>
  <aside class="query-sidebar do-glass-shell" :class="{ 'mobile-history-open': mobileHistoryOpen }" aria-label="会话导航">
    <RouterLink class="query-brand" to="/query" aria-label="DataOcean 智能问数">
      <span aria-hidden="true">DO</span>
      <div><strong>DataOcean</strong><small>智能问数</small></div>
    </RouterLink>

    <button class="new-session-button" type="button" @click="emit('new-session')">
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

    <section class="history-section" aria-label="历史会话">
      <div class="history-heading">
        <span>历史会话</span>
        <small class="history-count">{{ sessionCount }}</small>
        <button type="button" :aria-pressed="searchOpen" aria-label="搜索历史会话" @click="searchOpen = !searchOpen"><Search :size="15" /></button>
      </div>
      <label v-if="searchOpen" class="history-search">
        <Search :size="14" aria-hidden="true" />
        <input :value="keyword" type="search" placeholder="搜索会话" aria-label="搜索历史会话" @input="emit('update:keyword', ($event.target as HTMLInputElement).value)" />
      </label>
      <div v-if="!datasourceSessions.length" class="history-empty">{{ keyword.trim() ? '没有匹配的会话' : '还没有对话' }}</div>
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
      <Transition name="user-menu">
        <div v-if="userMenuOpen" class="sidebar-user-menu" role="menu" aria-label="用户菜单">
          <div class="menu-profile">
            <span class="sidebar-avatar" aria-hidden="true">{{ displayName.slice(0, 1) }}</span>
            <span class="sidebar-user-copy"><strong>{{ displayName }}</strong><small>{{ roleText }}</small></span>
          </div>
          <div class="menu-divider"></div>
          <button type="button" role="menuitem" @click="runUserCommand('profile')"><UserRound :size="16" aria-hidden="true" /><span>个人资料</span></button>
          <button type="button" role="menuitem" @click="runUserCommand('password')"><ShieldCheck :size="16" aria-hidden="true" /><span>修改密码</span></button>
          <button v-if="canEnterAdmin" type="button" role="menuitem" @click="runUserCommand('admin')"><UserCog :size="16" aria-hidden="true" /><span>后台管理</span></button>
          <div class="menu-divider"></div>
          <button type="button" role="menuitem" class="danger" @click="runUserCommand('logout')"><LogOut :size="16" aria-hidden="true" /><span>退出登录</span></button>
        </div>
      </Transition>
      <button class="sidebar-user" type="button" :aria-expanded="userMenuOpen" aria-label="打开用户菜单" @click="userMenuOpen = !userMenuOpen">
        <span class="sidebar-avatar" aria-hidden="true">{{ displayName.slice(0, 1) }}</span>
        <span class="sidebar-user-copy"><strong>{{ displayName }}</strong><small>{{ roleText }}</small></span>
      </button>
    </div>
  </aside>
</template>

<style scoped>
/* Keep the compact history control exclusive to narrow viewports. */
.query-sidebar { height: 100%; min-width: 0; min-height: 0; display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; gap: 14px; padding: 16px 12px 10px; border-radius: 16px; }
.query-sidebar.do-glass-shell { background: rgba(247, 252, 255, .66); }
.query-brand { height: 44px; display: grid; grid-template-columns: 38px minmax(0, 1fr); align-items: center; gap: 10px; color: var(--do-ink); text-decoration: none; }
.query-brand > span { width: 38px; height: 38px; display: grid; place-items: center; border-radius: 10px; color: #fff; background: var(--do-primary); font-size: 12px; font-weight: 900; box-shadow: 0 4px 10px color-mix(in srgb, var(--do-primary) 20%, transparent); }
.query-brand strong, .query-brand small, .history-row strong, .history-row small { display: block; }
.query-brand strong { font-size: 18px; line-height: 1.15; }
.query-brand small { margin-top: 2px; color: var(--do-muted); font-size: 11.5px; }
.new-session-button { min-height: 40px; display: inline-flex; align-items: center; justify-content: center; gap: 7px; border: 0; border-radius: var(--do-radius-md); color: #fff; background: var(--do-primary); font: inherit; font-size: 12.5px; font-weight: 700; cursor: pointer; box-shadow: 0 4px 12px color-mix(in srgb, var(--do-primary) 18%, transparent); transition: background var(--do-transition-fast), transform var(--do-transition-fast); }
.new-session-button:hover { background: var(--do-primary-strong); }
.new-session-button:active { transform: translateY(1px); }
.mobile-history-toggle { display: none; }
.history-section { min-height: 0; display: flex; flex-direction: column; gap: 6px; }
.history-heading, .history-search { flex-shrink: 0; }
.history-heading { min-height: 28px; display: flex; align-items: center; gap: 6px; color: var(--do-muted); font-size: 11.5px; font-weight: 700; }
.history-count { color: var(--do-muted); font-size: 10.5px; font-weight: 600; font-variant-numeric: tabular-nums; }
.history-heading button { width: 30px; height: 30px; margin-left: auto; display: grid; place-items: center; border: 0; border-radius: 7px; color: var(--do-muted); background: transparent; cursor: pointer; }
.history-heading button:hover { color: var(--do-primary-strong); background: var(--do-primary-soft); }
.history-search { min-height: 36px; display: grid; grid-template-columns: 18px minmax(0, 1fr); align-items: center; gap: 5px; padding: 0 8px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-muted); background: rgba(255, 255, 255, .88); }
.history-search:focus-within { border-color: var(--do-primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--do-primary) 10%, transparent); }
.history-search input { min-width: 0; border: 0; outline: 0; color: var(--do-ink); background: transparent; font: inherit; font-size: 12px; }
.history-list { flex: 1; min-height: 0; display: grid; align-content: start; gap: 8px; overflow-y: auto; overscroll-behavior: contain; padding-right: 2px; scrollbar-width: thin; scrollbar-color: color-mix(in srgb, var(--do-primary) 24%, transparent) transparent; }
.history-list::-webkit-scrollbar { width: 5px; }
.history-list::-webkit-scrollbar-track { background: transparent; }
.history-list::-webkit-scrollbar-thumb { border-radius: var(--do-radius-full); background: color-mix(in srgb, var(--do-primary) 22%, transparent); }
.history-group { min-width: 0; display: grid; gap: 2px; }
.history-group-label { margin: 9px 0 3px; padding: 0 7px; color: color-mix(in srgb, var(--do-primary-strong) 18%, var(--do-muted)); font-size: 10.5px; font-weight: 650; letter-spacing: .03em; }
.history-group:first-child .history-group-label { margin-top: 0; }
.history-row { min-height: 52px; display: grid; grid-template-columns: minmax(0, 1fr) 28px; align-items: center; border: 1px solid transparent; border-radius: 9px; color: var(--do-ink); }
.history-row:hover { background: color-mix(in srgb, var(--do-primary) 5%, white); }
.history-row.active { border-color: color-mix(in srgb, var(--do-primary) 22%, transparent); background: color-mix(in srgb, var(--do-primary) 8%, white); }
.history-select { min-width: 0; min-height: 52px; display: flex; align-items: center; padding: 6px 2px 6px 9px; border: 0; border-radius: 8px; color: inherit; background: transparent; text-align: left; cursor: pointer; }
.history-copy { flex: 1 1 auto; min-width: 0; display: block; }
.history-row strong { display: block; overflow: hidden; font-size: 12.5px; font-weight: 500; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.history-row.active strong { font-weight: 650; }
.history-row small { margin-top: 2px; overflow: hidden; color: var(--do-muted); font-size: 10.5px; line-height: 1.3; text-overflow: ellipsis; white-space: nowrap; font-variant-numeric: tabular-nums; }
.history-delete { width: 28px; height: 30px; display: grid; place-items: center; border: 0; border-radius: 7px; color: var(--do-muted); background: transparent; cursor: pointer; opacity: 0; transition: opacity var(--do-transition-fast); }
.history-row:hover .history-delete, .history-row:focus-within .history-delete, .history-row.active .history-delete { opacity: .75; }
.history-delete:hover { color: var(--do-danger); background: var(--do-danger-soft); opacity: 1; }
.history-empty { padding: 12px 8px; color: var(--do-muted); font-size: 12px; }
.sidebar-user-wrap { position: relative; padding-top: 8px; border-top: 1px solid var(--do-glass-line); }
.sidebar-user { width: 100%; min-height: 48px; display: grid; grid-template-columns: 32px minmax(0, 1fr); align-items: center; gap: 9px; padding: 6px 7px; border: 0; border-radius: 9px; color: var(--do-ink); background: transparent; text-align: left; cursor: pointer; transition: background var(--do-transition-fast); }
.sidebar-user:hover { background: color-mix(in srgb, var(--do-primary) 8%, transparent); }
.sidebar-avatar { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 9px; color: #fff; background: var(--do-primary); font-size: 11px; font-weight: 900; }
.sidebar-user-copy { min-width: 0; }
.sidebar-user-copy strong, .sidebar-user-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sidebar-user-copy strong { font-size: 13px; line-height: 1.2; }
.sidebar-user-copy small { margin-top: 3px; color: var(--do-muted); font-size: 11px; }
.sidebar-user-menu { position: absolute; left: 0; right: 0; bottom: calc(100% + 8px); z-index: 90; padding: 8px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); box-shadow: var(--do-shadow-lg); }
.menu-profile { min-height: 48px; display: grid; grid-template-columns: 34px minmax(0, 1fr); align-items: center; gap: 9px; padding: 5px 7px; }
.sidebar-user-menu > button { width: 100%; min-height: 40px; display: flex; align-items: center; gap: 10px; padding: 0 9px; border: 0; border-radius: var(--do-radius-sm); color: var(--do-ink); background: transparent; font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
.sidebar-user-menu > button:hover { background: var(--do-bg); }
.sidebar-user-menu > button.danger { color: var(--do-danger); }
.menu-divider { height: 1px; margin: 6px 4px; background: var(--do-line); }
.user-menu-enter-active, .user-menu-leave-active { transition: opacity 140ms ease, transform 140ms ease; transform-origin: bottom left; }
.user-menu-enter-from, .user-menu-leave-to { opacity: 0; transform: translateY(6px) scale(.98); }
.query-brand:focus-visible, .new-session-button:focus-visible, .mobile-history-toggle:focus-visible, .history-heading button:focus-visible, .history-select:focus-visible, .history-delete:focus-visible, .sidebar-user:focus-visible, .sidebar-user-menu > button:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 30%, transparent); outline-offset: 2px; }
@media (max-width: 1279px) and (min-width: 769px) { .query-sidebar { padding-inline: 10px; } }
@media (max-width: 768px) {
  .query-sidebar { position: relative; top: 0; height: auto; grid-template-columns: minmax(0, 1fr) auto 44px; grid-template-rows: 44px auto auto; grid-template-areas: "brand new user" "toggle toggle toggle" "history history history"; gap: 8px; padding: 8px 12px; border-radius: 14px; border-bottom: 1px solid var(--do-glass-line); }
  .query-brand { grid-area: brand; height: 44px; grid-template-columns: 34px minmax(0, 1fr); gap: 8px; }
  .query-brand > span { width: 32px; height: 32px; border-radius: 9px; }
  .query-brand strong { font-size: 15px; }
  .query-brand small { display: none; }
  .new-session-button { grid-area: new; min-height: 42px; padding: 0 12px; }
  .mobile-history-toggle { grid-area: toggle; min-height: 40px; display: flex; align-items: center; gap: 8px; padding: 0 10px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-ink); background: rgba(255, 255, 255, .88); font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
  .mobile-history-toggle small { margin-left: auto; color: var(--do-muted); }
  .history-section { display: none; grid-area: history; max-height: 40dvh; }
  .mobile-history-open .history-section { display: flex; }
  .history-list { max-height: 33dvh; }
  .history-row { grid-template-columns: minmax(0, 1fr) 36px; }
  .history-row strong { font-size: 13px; }
  .history-delete { width: 36px; height: 40px; opacity: .75; }
  .sidebar-user-wrap { grid-area: user; padding-top: 0; border-top: 0; }
  .sidebar-user { width: 44px; height: 44px; min-height: 44px; display: flex; justify-content: center; padding: 4px; }
  .sidebar-user-copy { display: none; }
  .sidebar-user-menu { top: calc(100% + 8px); right: 0; bottom: auto; width: min(300px, calc(100vw - 24px)); }
}
@media (prefers-reduced-motion: reduce) { .user-menu-enter-active, .user-menu-leave-active { transition-duration: 1ms; } }
</style>

<style>
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
