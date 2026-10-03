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

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function runUserCommand(command: 'profile' | 'password' | 'admin' | 'logout') {
  userMenuOpen.value = false
  emit('user-command', command)
}
</script>

<template>
  <aside class="query-sidebar" :class="{ 'mobile-history-open': mobileHistoryOpen }" aria-label="会话导航">
    <RouterLink class="query-brand" to="/query" aria-label="DataOcean 智能问数">
      <span aria-hidden="true">DO</span>
      <div><strong>DataOcean</strong><small>智能问数</small></div>
    </RouterLink>

    <button class="new-session-button" type="button" @click="emit('new-session')">
      <MessageSquarePlus :size="17" aria-hidden="true" /><span>新建对话</span>
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
        <button type="button" :aria-pressed="searchOpen" aria-label="搜索历史会话" @click="searchOpen = !searchOpen"><Search :size="15" /></button>
      </div>
      <label v-if="searchOpen" class="history-search">
        <Search :size="14" aria-hidden="true" />
        <input :value="keyword" type="search" placeholder="搜索会话" aria-label="搜索历史会话" @input="emit('update:keyword', ($event.target as HTMLInputElement).value)" />
      </label>
      <div v-if="!datasourceSessions.length" class="history-empty">还没有对话</div>
      <div v-else class="history-list">
        <div v-for="session in datasourceSessions" :key="session.id" class="history-row" :class="{ active: session.id === activeSessionId }">
          <button
            type="button"
            class="history-select"
            :aria-current="session.id === activeSessionId ? 'true' : undefined"
            @click="emit('select-session', session.id)"
          >
            <History :size="15" aria-hidden="true" />
            <span><strong>{{ session.title }}</strong><small>{{ formatTime(session.updatedAt) }}</small></span>
          </button>
          <button type="button" class="history-delete" :aria-label="`删除会话 ${session.title}`" @click="emit('remove-session', session)"><Trash2 :size="14" /></button>
        </div>
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
.query-sidebar { position: sticky; top: 0; height: 100dvh; min-width: 0; display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; gap: 18px; padding: 18px 14px 12px; border-right: 1px solid var(--do-line); background: var(--do-sidebar); }
.query-brand { height: 46px; display: grid; grid-template-columns: 42px minmax(0, 1fr); align-items: center; gap: 10px; color: var(--do-ink); text-decoration: none; }
.query-brand > span { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 11px; color: #fff; background: var(--do-primary); font-size: 13px; font-weight: 900; box-shadow: 0 6px 14px color-mix(in srgb, var(--do-primary) 24%, transparent); }
.query-brand strong, .query-brand small, .history-row strong, .history-row small { display: block; }
.query-brand strong { font-size: 17px; line-height: 1.15; }
.query-brand small { margin-top: 3px; color: var(--do-muted); font-size: 11px; }
.new-session-button { min-height: 44px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; border: 0; border-radius: var(--do-radius-md); color: #fff; background: var(--do-primary); font: inherit; font-size: 13px; font-weight: 800; cursor: pointer; box-shadow: 0 6px 16px color-mix(in srgb, var(--do-primary) 22%, transparent); transition: background var(--do-transition-fast), transform var(--do-transition-fast); }
.new-session-button:hover { background: var(--do-primary-strong); }
.new-session-button:active { transform: translateY(1px); }
.mobile-history-toggle { display: none; }
.history-section { min-height: 0; display: grid; grid-template-rows: auto auto minmax(0, 1fr); align-content: start; gap: 8px; }
.history-heading { min-height: 32px; display: flex; align-items: center; justify-content: space-between; color: var(--do-muted); font-size: 12px; font-weight: 800; }
.history-heading button { width: 36px; height: 36px; display: grid; place-items: center; border: 0; border-radius: 8px; color: var(--do-muted); background: transparent; cursor: pointer; }
.history-heading button:hover { color: var(--do-primary-strong); background: var(--do-primary-soft); }
.history-search { min-height: 38px; display: grid; grid-template-columns: 20px minmax(0, 1fr); align-items: center; gap: 5px; padding: 0 9px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-muted); background: var(--do-surface); }
.history-search:focus-within { border-color: var(--do-primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--do-primary) 10%, transparent); }
.history-search input { min-width: 0; border: 0; outline: 0; color: var(--do-ink); background: transparent; font: inherit; font-size: 12px; }
.history-list { min-height: 0; display: grid; align-content: start; gap: 3px; overflow-y: auto; overscroll-behavior: contain; }
.history-row { min-height: 58px; display: grid; grid-template-columns: minmax(0, 1fr) 34px; align-items: center; gap: 2px; padding: 2px; border: 1px solid transparent; border-radius: 9px; color: var(--do-ink); }
.history-row:hover { background: color-mix(in srgb, var(--do-primary) 6%, transparent); }
.history-row.active { border-color: color-mix(in srgb, var(--do-primary) 20%, transparent); background: color-mix(in srgb, var(--do-primary) 11%, transparent); }
.history-select { min-width: 0; min-height: 48px; display: grid; grid-template-columns: 24px minmax(0, 1fr); align-items: center; gap: 7px; padding: 5px 6px; border: 0; border-radius: 7px; color: inherit; background: transparent; text-align: left; cursor: pointer; }
.history-select > svg { color: var(--do-primary-strong); }
.history-select span { min-width: 0; }
.history-row strong, .history-row small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-row strong { margin-bottom: 4px; font-size: 12px; }
.history-row small { color: var(--do-muted); font-size: 11px; }
.history-delete { width: 34px; height: 38px; display: grid; place-items: center; border: 0; border-radius: 7px; color: var(--do-muted); background: transparent; cursor: pointer; opacity: 0; }
.history-row:hover .history-delete, .history-row:focus-within .history-delete { opacity: 1; }
.history-delete:hover { color: var(--do-danger); background: var(--do-danger-soft); }
.history-empty { padding: 12px 8px; color: var(--do-muted); font-size: 12px; }
.sidebar-user-wrap { position: relative; }
.sidebar-user { width: 100%; min-height: 50px; display: grid; grid-template-columns: 34px minmax(0, 1fr); align-items: center; gap: 9px; padding: 7px 8px; border: 0; border-radius: 9px; color: var(--do-ink); background: transparent; text-align: left; cursor: pointer; transition: background var(--do-transition-fast); }
.sidebar-user:hover { background: color-mix(in srgb, var(--do-primary) 8%, transparent); }
.sidebar-avatar { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 9px; color: #fff; background: var(--do-primary); font-size: 12px; font-weight: 900; }
.sidebar-user-copy { min-width: 0; }
.sidebar-user-copy strong, .sidebar-user-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sidebar-user-copy strong { font-size: 12px; line-height: 1.2; }
.sidebar-user-copy small { margin-top: 3px; color: var(--do-muted); font-size: 10px; }
.sidebar-user-menu { position: absolute; left: 0; right: 0; bottom: calc(100% + 8px); z-index: 90; padding: 8px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); box-shadow: var(--do-shadow-lg); }
.menu-profile { min-height: 48px; display: grid; grid-template-columns: 34px minmax(0, 1fr); align-items: center; gap: 9px; padding: 5px 7px; }
.sidebar-user-menu > button { width: 100%; min-height: 40px; display: flex; align-items: center; gap: 10px; padding: 0 9px; border: 0; border-radius: var(--do-radius-sm); color: var(--do-ink); background: transparent; font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
.sidebar-user-menu > button:hover { background: var(--do-bg); }
.sidebar-user-menu > button.danger { color: var(--do-danger); }
.menu-divider { height: 1px; margin: 6px 4px; background: var(--do-line); }
.user-menu-enter-active, .user-menu-leave-active { transition: opacity 140ms ease, transform 140ms ease; transform-origin: bottom left; }
.user-menu-enter-from, .user-menu-leave-to { opacity: 0; transform: translateY(6px) scale(.98); }
.query-brand:focus-visible, .new-session-button:focus-visible, .mobile-history-toggle:focus-visible, .history-heading button:focus-visible, .history-select:focus-visible, .history-delete:focus-visible, .sidebar-user:focus-visible, .sidebar-user-menu > button:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 30%, transparent); outline-offset: 2px; }
@media (max-width: 1279px) and (min-width: 769px) { .query-sidebar { padding-inline: 12px; } }
@media (max-width: 768px) {
  .query-sidebar { position: relative; height: auto; grid-template-columns: minmax(0, 1fr) auto 44px; grid-template-rows: 44px auto auto; grid-template-areas: "brand new user" "toggle toggle toggle" "history history history"; gap: 8px; padding: 8px 12px; border-right: 0; border-bottom: 1px solid var(--do-line); }
  .query-brand { grid-area: brand; height: 44px; grid-template-columns: 36px minmax(0, 1fr); gap: 8px; }
  .query-brand > span { width: 34px; height: 34px; border-radius: 9px; }
  .query-brand strong { font-size: 15px; }
  .query-brand small { display: none; }
  .new-session-button { grid-area: new; min-height: 44px; padding: 0 12px; }
  .mobile-history-toggle { grid-area: toggle; min-height: 42px; display: flex; align-items: center; gap: 8px; padding: 0 10px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-ink); background: var(--do-surface); font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
  .mobile-history-toggle small { margin-left: auto; color: var(--do-muted); }
  .history-section { display: none; grid-area: history; max-height: 38dvh; grid-template-rows: auto auto minmax(0, 1fr); }
  .mobile-history-open .history-section { display: grid; }
  .history-list { max-height: 31dvh; }
  .history-row { min-height: 48px; }
  .history-select { min-height: 44px; }
  .history-delete { width: 40px; height: 40px; opacity: 1; }
  .sidebar-user-wrap { grid-area: user; }
  .sidebar-user { width: 44px; height: 44px; min-height: 44px; display: flex; justify-content: center; padding: 4px; }
  .sidebar-user-copy { display: none; }
  .sidebar-user-menu { top: calc(100% + 8px); right: 0; bottom: auto; width: min(300px, calc(100vw - 24px)); }
}
@media (prefers-reduced-motion: reduce) { .user-menu-enter-active, .user-menu-leave-active { transition-duration: 1ms; } }
</style>
