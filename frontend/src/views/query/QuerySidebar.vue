/**
 * QuerySidebar — Codex 风格工作区侧栏
 * 数据源通过单一入口展开选择，避免与主区域重复展示。
 */
<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check, ChevronDown, Database, History, LogOut, MessageSquarePlus, RefreshCw, Search, ShieldCheck, Trash2, UserCog, UserRound } from 'lucide-vue-next'
import type { DatasourceReadiness, UserDatasourceItem } from '../../api/datasource'
import type { LocalSession } from '../../composables/useQuerySession'

const props = defineProps<{
  datasources: UserDatasourceItem[]
  readinessMap: Record<number, DatasourceReadiness>
  selectedId: number | undefined
  datasourceSessions: LocalSession[]
  activeSessionId: string | undefined
  keyword: string
  loading: boolean
  readinessLoading: boolean
  errorMessage: string
  displayName: string
  roleText: string
  canEnterAdmin: boolean
}>()

const emit = defineEmits<{
  'select-datasource': [id: number]
  'select-session': [sessionId: string]
  'remove-session': [session: LocalSession]
  'new-session': []
  'refresh': []
  'user-command': [command: 'profile' | 'password' | 'admin' | 'logout']
  'update:keyword': [value: string]
}>()

const datasourceOpen = ref(false)
const searchOpen = ref(false)
const userMenuOpen = ref(false)
const selectedDatasource = computed(() => props.datasources.find((item) => item.id === props.selectedId))
const selectedReadiness = computed(() => props.selectedId ? props.readinessMap[props.selectedId] : undefined)

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function chooseDatasource(id: number) {
  emit('select-datasource', id)
  datasourceOpen.value = false
}

function runUserCommand(command: 'profile' | 'password' | 'admin' | 'logout') {
  userMenuOpen.value = false
  emit('user-command', command)
}
</script>

<template>
  <aside class="query-sidebar">
    <RouterLink class="query-brand" to="/query" aria-label="DataOcean 智能问答">
      <span>DO</span>
      <div><strong>DataOcean</strong><small>智能问答</small></div>
    </RouterLink>

    <button class="new-session-button" type="button" :disabled="!selectedId" @click="emit('new-session')">
      <MessageSquarePlus :size="17" /><span>新建对话</span>
    </button>

    <section class="datasource-section">
      <div class="section-label">
        <span>数据源</span>
        <button type="button" :disabled="loading || readinessLoading" aria-label="刷新数据源" @click="emit('refresh')">
          <RefreshCw :size="14" :class="{ spinning: loading || readinessLoading }" />
        </button>
      </div>

      <button class="datasource-trigger" type="button" :class="{ open: datasourceOpen }" :aria-expanded="datasourceOpen" @click="datasourceOpen = !datasourceOpen">
        <span class="source-icon"><Database :size="17" /></span>
        <span class="source-copy">
          <strong>{{ selectedDatasource?.name || '选择数据源' }}</strong>
          <small v-if="selectedDatasource"><i :class="{ ready: selectedReadiness?.askable }"></i>{{ selectedReadiness?.askable ? '数据源已就绪' : (selectedReadiness?.stageLabel || selectedDatasource.databaseName) }}</small>
        </span>
        <ChevronDown :size="16" class="source-chevron" />
      </button>

      <div v-if="datasourceOpen" class="datasource-menu">
        <div v-if="loading && !datasources.length" class="menu-state">正在加载数据源...</div>
        <div v-else-if="errorMessage" class="menu-state error"><span>{{ errorMessage }}</span><button type="button" @click="emit('refresh')">重试</button></div>
        <div v-else-if="!datasources.length" class="menu-state">暂无可用数据源</div>
        <template v-else>
          <button v-for="datasource in datasources" :key="datasource.id" type="button" class="datasource-option" :class="{ active: datasource.id === selectedId }" @click="chooseDatasource(datasource.id)">
            <Database :size="15" />
            <span><strong>{{ datasource.name }}</strong><small>{{ readinessMap[datasource.id]?.askable ? '可询问' : (readinessMap[datasource.id]?.stageLabel || datasource.databaseName) }}</small></span>
            <Check v-if="datasource.id === selectedId" :size="15" />
          </button>
        </template>
      </div>
    </section>

    <section class="history-section">
      <div class="history-heading">
        <span>历史会话</span>
        <button type="button" :aria-pressed="searchOpen" aria-label="搜索历史会话" @click="searchOpen = !searchOpen"><Search :size="15" /></button>
      </div>
      <label v-if="searchOpen" class="history-search">
        <Search :size="14" />
        <input :value="keyword" type="search" placeholder="搜索会话" :disabled="!selectedId" @input="emit('update:keyword', ($event.target as HTMLInputElement).value)" />
      </label>
      <div v-if="!selectedId" class="history-empty">选择数据源后显示会话</div>
      <div v-else-if="!datasourceSessions.length" class="history-empty">还没有对话</div>
      <div v-else class="history-list">
        <div v-for="session in datasourceSessions" :key="session.id" role="button" tabindex="0" class="history-row" :class="{ active: session.id === activeSessionId }" @click="emit('select-session', session.id)" @keydown.enter="emit('select-session', session.id)">
          <History :size="14" />
          <span><strong>{{ session.title }}</strong><small>{{ formatTime(session.updatedAt) }}</small></span>
          <button type="button" class="history-delete" aria-label="删除会话" @click.stop="emit('remove-session', session)"><Trash2 :size="14" /></button>
        </div>
      </div>
    </section>

    <div class="sidebar-user-wrap">
      <Transition name="user-menu">
        <div v-if="userMenuOpen" class="sidebar-user-menu" role="menu">
          <div class="menu-profile">
            <span class="sidebar-avatar">{{ displayName.slice(0, 1) }}</span>
            <span class="sidebar-user-copy"><strong>{{ displayName }}</strong><small>{{ roleText }}</small></span>
          </div>
          <div class="menu-divider"></div>
          <button type="button" role="menuitem" @click="runUserCommand('profile')"><UserRound :size="16" /><span>个人资料</span></button>
          <button type="button" role="menuitem" @click="runUserCommand('password')"><ShieldCheck :size="16" /><span>修改密码</span></button>
          <button v-if="canEnterAdmin" type="button" role="menuitem" @click="runUserCommand('admin')"><UserCog :size="16" /><span>后台管理</span></button>
          <div class="menu-divider"></div>
          <button type="button" role="menuitem" class="danger" @click="runUserCommand('logout')"><LogOut :size="16" /><span>退出登录</span></button>
        </div>
      </Transition>
      <button class="sidebar-user" type="button" :aria-expanded="userMenuOpen" @click="userMenuOpen = !userMenuOpen">
        <span class="sidebar-avatar">{{ displayName.slice(0, 1) }}</span>
        <span class="sidebar-user-copy"><strong>{{ displayName }}</strong><small>{{ roleText }}</small></span>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.query-sidebar { position: sticky; top: 0; height: 100vh; display: grid; grid-template-rows: auto auto auto minmax(0, 1fr) auto; gap: 18px; padding: 18px 16px 12px; border-right: 1px solid var(--do-line); background: rgba(249, 251, 254, .96); }
.query-brand { height: 46px; display: grid; grid-template-columns: 38px minmax(0, 1fr); align-items: center; gap: 11px; color: var(--do-ink); text-decoration: none; }
.query-brand > span { width: 38px; height: 38px; display: grid; place-items: center; border-radius: 10px; color: #fff; background: var(--do-primary); font-size: 12px; font-weight: 900; box-shadow: 0 7px 16px rgba(77, 143, 220, .22); }
.query-brand strong, .query-brand small, .source-copy strong, .source-copy small, .datasource-option strong, .datasource-option small, .history-row strong, .history-row small { display: block; }
.query-brand strong { font-size: 17px; line-height: 1.15; }
.query-brand small { margin-top: 3px; color: var(--do-muted); font-size: 11px; }
.new-session-button { height: 42px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; border: 0; border-radius: 9px; color: #fff; background: var(--do-primary); font-size: 13px; font-weight: 800; cursor: pointer; box-shadow: 0 8px 18px rgba(77, 143, 220, .2); transition: background 150ms ease, transform 150ms ease; }
.new-session-button:hover:not(:disabled) { background: var(--do-primary-strong); }
.new-session-button:active:not(:disabled) { transform: translateY(1px); }
.new-session-button:disabled { cursor: not-allowed; opacity: .48; }
.datasource-section { position: relative; display: grid; gap: 8px; }
.section-label, .history-heading { min-height: 28px; display: flex; align-items: center; justify-content: space-between; color: var(--do-muted); font-size: 12px; font-weight: 800; }
.section-label button, .history-heading button { width: 28px; height: 28px; display: grid; place-items: center; border: 0; border-radius: 7px; color: var(--do-muted); background: transparent; cursor: pointer; }
.section-label button:hover:not(:disabled), .history-heading button:hover { color: var(--do-primary-strong); background: var(--do-primary-soft); }
.spinning { animation: spin 800ms linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.datasource-trigger { min-height: 58px; display: grid; grid-template-columns: 32px minmax(0, 1fr) 20px; align-items: center; gap: 8px; padding: 9px 10px; border: 1px solid var(--do-line); border-radius: 10px; color: var(--do-ink); background: var(--do-surface); text-align: left; cursor: pointer; transition: border-color 150ms ease, box-shadow 150ms ease; }
.datasource-trigger:hover, .datasource-trigger.open { border-color: rgba(77, 143, 220, .46); box-shadow: 0 0 0 3px rgba(77, 143, 220, .08); }
.source-icon { color: var(--do-primary-strong); }
.source-copy { min-width: 0; }
.source-copy strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.source-copy small { margin-top: 4px; overflow: hidden; color: var(--do-muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.source-copy i { width: 6px; height: 6px; display: inline-block; margin-right: 4px; border-radius: 50%; background: #f59e0b; vertical-align: 1px; }
.source-copy i.ready { background: #22c55e; }
.source-chevron { color: var(--do-muted); transition: transform 150ms ease; }
.datasource-trigger.open .source-chevron { transform: rotate(180deg); }
.datasource-menu { position: absolute; top: calc(100% + 6px); left: 0; right: 0; z-index: 30; display: grid; gap: 3px; max-height: 280px; overflow-y: auto; padding: 6px; border: 1px solid var(--do-line); border-radius: 10px; background: var(--do-surface); box-shadow: 0 18px 42px rgba(15, 23, 42, .14); }
.datasource-option { min-height: 48px; display: grid; grid-template-columns: 24px minmax(0, 1fr) 18px; align-items: center; gap: 7px; padding: 7px 8px; border: 0; border-radius: 8px; color: var(--do-ink); background: transparent; text-align: left; cursor: pointer; }
.datasource-option:hover, .datasource-option.active { background: var(--do-primary-soft); }
.datasource-option > svg { color: var(--do-primary-strong); }
.datasource-option span { min-width: 0; }
.datasource-option strong, .datasource-option small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.datasource-option strong { font-size: 12px; }
.datasource-option small { margin-top: 3px; color: var(--do-muted); font-size: 11px; }
.menu-state { padding: 10px; color: var(--do-muted); font-size: 12px; }
.menu-state.error { display: flex; justify-content: space-between; gap: 8px; color: var(--do-danger); }
.menu-state button { border: 0; color: var(--do-primary-strong); background: transparent; cursor: pointer; }
.history-section { min-height: 0; display: grid; grid-template-rows: auto auto minmax(0, 1fr); align-content: start; gap: 8px; }
.history-search { height: 34px; display: grid; grid-template-columns: 20px minmax(0, 1fr); align-items: center; gap: 5px; padding: 0 9px; border: 1px solid var(--do-line); border-radius: 8px; color: var(--do-muted); background: var(--do-surface); }
.history-search:focus-within { border-color: var(--do-primary); box-shadow: 0 0 0 3px rgba(77, 143, 220, .08); }
.history-search input { min-width: 0; border: 0; outline: 0; color: var(--do-ink); background: transparent; font-size: 12px; }
.history-list { min-height: 0; display: grid; align-content: start; gap: 3px; overflow-y: auto; }
.history-row { min-height: 54px; display: grid; grid-template-columns: 22px minmax(0, 1fr) 28px; align-items: center; gap: 6px; padding: 7px 6px 7px 9px; border: 1px solid transparent; border-radius: 9px; color: var(--do-ink); cursor: pointer; }
.history-row:hover { background: rgba(77, 143, 220, .06); }
.history-row.active { border-color: rgba(77, 143, 220, .18); background: rgba(77, 143, 220, .1); }
.history-row > svg { color: var(--do-primary-strong); }
.history-row span { min-width: 0; }
.history-row strong, .history-row small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-row strong { margin-bottom: 4px; font-size: 12px; }
.history-row small { color: var(--do-muted); font-size: 11px; }
.history-delete { width: 26px; height: 26px; display: grid; place-items: center; border: 0; border-radius: 6px; color: var(--do-muted); background: transparent; cursor: pointer; opacity: 0; }
.history-row:hover .history-delete, .history-row:focus-within .history-delete { opacity: 1; }
.history-delete:hover { color: var(--do-danger); background: #fef2f2; }
.history-empty { padding: 12px 8px; color: #94a3b8; font-size: 12px; }
.sidebar-user-wrap { position: relative; }
.sidebar-user { width: 100%; min-height: 48px; display: grid; grid-template-columns: 32px minmax(0, 1fr); align-items: center; gap: 9px; padding: 7px 8px; border: 0; border-radius: 9px; color: var(--do-ink); background: transparent; text-align: left; cursor: pointer; transition: background 150ms ease; }
.sidebar-user:hover { background: rgba(77, 143, 220, .08); }
.sidebar-avatar { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 8px; color: #fff; background: var(--do-primary); font-size: 12px; font-weight: 900; }
.sidebar-user-copy { min-width: 0; }
.sidebar-user-copy strong, .sidebar-user-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sidebar-user-copy strong { font-size: 12px; line-height: 1.2; }
.sidebar-user-copy small { margin-top: 3px; color: var(--do-muted); font-size: 10px; }
.sidebar-user-menu { position: absolute; left: 0; right: 0; bottom: calc(100% + 8px); z-index: 60; padding: 8px; border: 1px solid var(--do-line); border-radius: 12px; background: rgba(255, 255, 255, .98); box-shadow: 0 16px 42px rgba(15, 23, 42, .16); backdrop-filter: blur(14px); }
.menu-profile { min-height: 48px; display: grid; grid-template-columns: 32px minmax(0, 1fr); align-items: center; gap: 9px; padding: 5px 7px; }
.sidebar-user-menu > button { width: 100%; min-height: 38px; display: flex; align-items: center; gap: 10px; padding: 0 9px; border: 0; border-radius: 8px; color: var(--do-ink); background: transparent; font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
.sidebar-user-menu > button:hover { background: var(--do-bg); }
.sidebar-user-menu > button.danger { color: #b42318; }
.sidebar-user-menu > button.danger:hover { background: #fef2f2; }
.menu-divider { height: 1px; margin: 6px 4px; background: var(--do-line); }
.user-menu-enter-active, .user-menu-leave-active { transition: opacity 140ms ease, transform 140ms ease; transform-origin: bottom left; }
.user-menu-enter-from, .user-menu-leave-to { opacity: 0; transform: translateY(6px) scale(.98); }
.query-brand:focus-visible, .new-session-button:focus-visible, .datasource-trigger:focus-visible, .datasource-option:focus-visible, .history-heading button:focus-visible, .section-label button:focus-visible, .history-row:focus-visible, .history-delete:focus-visible, .sidebar-user:focus-visible { outline: 3px solid rgba(77, 143, 220, .2); outline-offset: 2px; }
</style>
