/**
 * QuerySidebar — 左侧边栏组件
 * 包含数据源列表、会话列表、搜索过滤、新建会话按钮
 */
<script setup lang="ts">
import { Database, History, MessageSquarePlus, RefreshCw, Search, Trash2 } from 'lucide-vue-next'
import type { DatasourceReadiness, UserDatasourceItem } from '../../api/datasource'
import type { LocalSession } from '../../composables/useQuerySession'

defineProps<{
  datasources: UserDatasourceItem[]
  readinessMap: Record<number, DatasourceReadiness>
  selectedId: number | undefined
  datasourceSessions: LocalSession[]
  activeSessionId: string | undefined
  keyword: string
  loading: boolean
  readinessLoading: boolean
}>()

const emit = defineEmits<{
  'select-datasource': [id: number]
  'select-session': [sessionId: string]
  'remove-session': [session: LocalSession]
  'new-session': []
  'refresh': []
  'update:keyword': [value: string]
}>()

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <aside class="query-sidebar">
    <RouterLink class="query-brand" to="/query" aria-label="DataOcean 智能问答">
      <span>DO</span>
      <div>
        <strong>DataOcean</strong>
        <small>智能问答</small>
      </div>
    </RouterLink>

    <section class="sidebar-block">
      <div class="block-title">
        <span>数据源</span>
        <button type="button" :disabled="loading || readinessLoading" aria-label="刷新数据源" @click="emit('refresh')">
          <RefreshCw :size="15" />
        </button>
      </div>

      <div v-if="loading && !datasources.length" class="sidebar-loading">正在加载数据源...</div>
      <div v-else-if="!datasources.length" class="sidebar-empty">暂无可用数据源，请联系管理员开通权限。</div>
      <div v-else class="datasource-list">
        <button
          v-for="datasource in datasources"
          :key="datasource.id"
          type="button"
          class="datasource-row"
          :class="{ active: datasource.id === selectedId, 'not-askable': readinessMap[datasource.id]?.askable === false }"
          @click="emit('select-datasource', datasource.id)"
        >
          <Database :size="16" />
          <span>
            <strong>{{ datasource.name }}</strong>
            <small>{{ datasource.databaseName }}</small>
            <small v-if="readinessMap[datasource.id]" class="readiness-chip">
              {{ readinessMap[datasource.id].askable ? '可询问' : readinessMap[datasource.id].stageLabel }}
            </small>
          </span>
        </button>
      </div>
    </section>

    <section class="sidebar-block history-block">
      <div class="block-title">
        <span>当前数据源历史</span>
        <button type="button" :disabled="!selectedId" aria-label="新建对话" @click="emit('new-session')">
          <MessageSquarePlus :size="15" />
        </button>
      </div>

      <label class="history-search">
        <Search :size="15" />
        <input :value="keyword" type="search" placeholder="搜索当前数据源会话" :disabled="!selectedId" @input="emit('update:keyword', ($event.target as HTMLInputElement).value)" />
      </label>

      <div v-if="!selectedId" class="sidebar-empty">选择数据源后显示对应历史。</div>
      <div v-else-if="!datasourceSessions.length" class="sidebar-empty">当前数据源暂无历史会话。</div>
      <div v-else class="history-list">
        <div
          v-for="session in datasourceSessions"
          :key="session.id"
          role="button"
          tabindex="0"
          class="history-row"
          :class="{ active: session.id === activeSessionId }"
          @click="emit('select-session', session.id)"
          @keydown.enter="emit('select-session', session.id)"
        >
          <History :size="15" />
          <span>
            <strong>{{ session.title }}</strong>
            <small>{{ session.messages.length }} 条消息 · {{ formatTime(session.updatedAt) }}</small>
          </span>
          <button type="button" class="history-delete" aria-label="删除会话" @click.stop="emit('remove-session', session)">
            <Trash2 :size="14" />
          </button>
        </div>
      </div>
    </section>
  </aside>
</template>
