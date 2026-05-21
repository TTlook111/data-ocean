<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Database,
  GitBranch,
  Layers,
  MessageSquareText,
  Table2,
  Users,
} from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { getDashboardStats, type DashboardStats } from '../api/admin/dashboard'

const auth = useAuthStore()
const loading = ref(false)
const stats = ref<DashboardStats | null>(null)

const permissions = computed(() => auth.user?.permissions || auth.currentUser?.permissions || [])
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const isAdmin = computed(() => permissions.value.includes('*'))

const actionLabels: Record<string, string> = {
  PUBLISH: '发布', EXPIRE: '过期', REVOKE: '撤回', STATUS_TRANSITION: '状态变更'
}

async function fetchStats() {
  if (!isAdmin.value) return
  loading.value = true
  try {
    const res = await getDashboardStats()
    stats.value = res.data ?? null
  } catch { /* 静默处理 */ }
  finally { loading.value = false }
}

onMounted(fetchStats)
</script>

<template>
  <main class="admin-home post-login-page">
    <header class="page-header">
      <div>
        <p>工作台</p>
        <h1>{{ displayName }}，欢迎回来</h1>
        <span class="header-subtitle" v-if="isAdmin">平台数据概览，一目了然</span>
        <span class="header-subtitle" v-else>选择数据源，用自然语言探索你的业务数据</span>
      </div>
      <RouterLink class="hero-action" to="/query">
        <MessageSquareText :size="16" />
        开始查询
      </RouterLink>
    </header>

    <section v-if="isAdmin && stats" class="stats-grid" v-loading="loading">
      <article class="stat-card">
        <div class="stat-icon blue"><Users :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ stats.totalUsers }}</span>
          <span class="stat-label">用户总数</span>
        </div>
      </article>
      <article class="stat-card">
        <div class="stat-icon green"><Database :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ stats.activeDatasources }}<small>/{{ stats.totalDatasources }}</small></span>
          <span class="stat-label">活跃/总数据源</span>
        </div>
      </article>
      <article class="stat-card">
        <div class="stat-icon purple"><Table2 :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ stats.totalTables }}</span>
          <span class="stat-label">已发布表</span>
        </div>
      </article>
      <article class="stat-card">
        <div class="stat-icon sky"><Layers :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ stats.totalColumns }}</span>
          <span class="stat-label">已发布字段</span>
        </div>
      </article>
      <article class="stat-card">
        <div class="stat-icon orange"><AlertTriangle :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ stats.openIssues }}</span>
          <span class="stat-label">待处理问题</span>
        </div>
      </article>
      <article class="stat-card">
        <div class="stat-icon teal"><CheckCircle2 :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ stats.resolvedIssues }}</span>
          <span class="stat-label">已解决问题</span>
        </div>
      </article>
      <article class="stat-card">
        <div class="stat-icon blue"><GitBranch :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ stats.publishedSnapshots }}<small>/{{ stats.totalSnapshots }}</small></span>
          <span class="stat-label">已发布/总快照</span>
        </div>
      </article>
      <article class="stat-card">
        <div class="stat-icon green"><Activity :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value" :class="{ 'score-good': (stats.avgQualityScore ?? 0) >= 80, 'score-warn': (stats.avgQualityScore ?? 0) < 80 && (stats.avgQualityScore ?? 0) >= 60, 'score-bad': (stats.avgQualityScore ?? 0) < 60 }">
            {{ stats.avgQualityScore ?? '-' }}
          </span>
          <span class="stat-label">平均质量分</span>
        </div>
      </article>
    </section>

    <section v-if="isAdmin && stats?.recentActivities?.length" class="activity-section">
      <h3>最近操作</h3>
      <div class="activity-list">
        <div v-for="(a, i) in stats.recentActivities" :key="i" class="activity-item">
          <span class="activity-dot" :class="'dot-' + a.type.toLowerCase()"></span>
          <span class="activity-desc">
            <strong>{{ actionLabels[a.type] || a.type }}</strong>
            {{ a.description }}
          </span>
          <span class="activity-time">{{ a.time }}</span>
        </div>
      </div>
    </section>

    <section v-if="!isAdmin" class="user-guide">
      <div class="guide-card">
        <h3>如何开始查询</h3>
        <ol>
          <li>进入「智能查询」，选择一个已授权的数据源</li>
          <li>用自然语言描述你想查询的内容</li>
          <li>系统自动生成 SQL 并返回表格或图表结果</li>
        </ol>
      </div>
    </section>
  </main>
</template>

<style scoped>
.admin-home {
  display: grid;
  gap: 20px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24px 28px;
  border: 1px solid var(--do-line);
  border-radius: 12px;
  background: linear-gradient(135deg, rgba(189, 232, 248, 0.9) 0%, rgba(246, 251, 239, 0.95) 100%);
  box-shadow: var(--do-shadow);
}

.page-header p { margin: 0 0 4px; color: var(--do-primary-strong); font-size: 13px; font-weight: 700; }
.page-header h1 { margin: 0 0 6px; font-size: 22px; color: var(--do-ink); }

.hero-action {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 10px 18px; border-radius: 8px;
  background: var(--do-primary); color: #fff; font-weight: 600; font-size: 14px;
  white-space: nowrap; transition: background 150ms;
}
.hero-action:hover { background: var(--do-primary-strong); }

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}

.stat-card {
  display: flex; align-items: center; gap: 14px;
  padding: 18px 16px;
  border: 1px solid var(--do-line); border-radius: 10px;
  background: var(--do-surface); box-shadow: var(--do-shadow);
  transition: transform 150ms, box-shadow 150ms;
}
.stat-card:hover { transform: translateY(-2px); box-shadow: 0 6px 16px rgba(0,0,0,0.06); }

.stat-icon {
  width: 42px; height: 42px; display: grid; place-items: center;
  border-radius: 10px; flex-shrink: 0;
}
.stat-icon.blue { color: #4d8fdc; background: #eef5ff; }
.stat-icon.green { color: #6aa84f; background: #edf7e8; }
.stat-icon.purple { color: #8b5cf6; background: #f3eeff; }
.stat-icon.sky { color: #0ea5e9; background: #e8f7fd; }
.stat-icon.orange { color: #f59e0b; background: #fef3cd; }
.stat-icon.teal { color: #14b8a6; background: #e6faf8; }

.stat-body { display: flex; flex-direction: column; }
.stat-value { font-size: 22px; font-weight: 700; color: var(--do-ink); line-height: 1.2; }
.stat-value small { font-size: 14px; font-weight: 400; color: var(--do-muted); }
.stat-label { font-size: 12px; color: var(--do-muted); margin-top: 2px; }

.score-good { color: #67c23a; }
.score-warn { color: #e6a23c; }
.score-bad { color: #f56c6c; }

.activity-section {
  padding: 20px; border: 1px solid var(--do-line); border-radius: 10px;
  background: var(--do-surface); box-shadow: var(--do-shadow);
}
.activity-section h3 { margin: 0 0 14px; font-size: 15px; color: var(--do-ink); }
.activity-list { display: flex; flex-direction: column; gap: 10px; }
.activity-item {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 12px; border-radius: 6px; background: var(--do-bg);
}
.activity-dot {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
}
.dot-publish { background: #67c23a; }
.dot-expire { background: #c0c4cc; }
.dot-revoke { background: #f56c6c; }
.dot-status_transition { background: #409eff; }
.activity-desc { flex: 1; font-size: 13px; color: var(--do-ink); }
.activity-desc strong { margin-right: 6px; }
.activity-time { font-size: 12px; color: var(--do-muted); white-space: nowrap; }

.user-guide { max-width: 600px; }
.guide-card {
  padding: 24px; border: 1px solid var(--do-line); border-radius: 10px;
  background: var(--do-surface); box-shadow: var(--do-shadow);
}
.guide-card h3 { margin: 0 0 14px; font-size: 16px; color: var(--do-ink); }
.guide-card ol { margin: 0; padding-left: 20px; color: var(--do-muted); font-size: 14px; line-height: 2; }

@media (max-width: 1100px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 600px) {
  .stats-grid { grid-template-columns: 1fr; }
}
</style>
