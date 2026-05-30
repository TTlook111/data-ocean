<script setup lang="ts">
import { computed, nextTick, ref, onMounted, watch } from 'vue'
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  BookOpen,
  CheckCircle2,
  Database,
  GitBranch,
  Layers,
  MessageSquareText,
  RefreshCw,
  ShieldAlert,
  Table2,
  Users,
} from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { getDashboardStats, type DashboardStats } from '../api/admin/dashboard'
import { useGsapMotion } from '../composables/useGsapMotion'

const auth = useAuthStore()
const loading = ref(false)
const stats = ref<DashboardStats | null>(null)
const homeRef = ref<HTMLElement | null>(null)
const { lift, reveal, revealAfterTick, withContext } = useGsapMotion(homeRef)

const permissions = computed(() => auth.user?.permissions || auth.currentUser?.permissions || [])
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const isAdmin = computed(() => permissions.value.includes('*'))
const issueTotal = computed(() => stats.value ? stats.value.openIssues + stats.value.resolvedIssues : 0)
const issueResolutionRate = computed(() => issueTotal.value ? Math.round((stats.value!.resolvedIssues / issueTotal.value) * 100) : 0)
const snapshotPublishRate = computed(() => stats.value?.totalSnapshots ? Math.round((stats.value.publishedSnapshots / stats.value.totalSnapshots) * 100) : 0)
const qualityLabel = computed(() => {
  const score = stats.value?.avgQualityScore
  if (score == null) return '暂无评分'
  if (score >= 80) return '质量稳定'
  if (score >= 60) return '需要关注'
  return '优先治理'
})
const qualityTone = computed(() => {
  const score = stats.value?.avgQualityScore ?? 0
  if (score >= 80) return 'good'
  if (score >= 60) return 'warn'
  return 'bad'
})

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

onMounted(() => {
  withContext(() => {
    reveal('.home-header, .user-guide', {
      y: 14,
      stagger: 0.05,
    })
  })
  fetchStats()
})

watch(stats, async (value) => {
  if (!value) return
  await nextTick()
  revealAfterTick('.ops-card, .stat-card, .priority-card, .activity-item', {
    y: 14,
    stagger: 0.035,
  })
})

watch(loading, (value, oldValue) => {
  if (!oldValue || value) return
  const grid = homeRef.value?.querySelector('.stats-grid')
  if (grid) {
    lift(grid, { y: 8, duration: 0.22, scale: 1 })
  }
})
</script>

<template>
  <main ref="homeRef" class="admin-home post-login-page">
    <header class="home-header">
      <div class="home-title">
        <span>工作台</span>
        <h1>{{ displayName }}，欢迎回来</h1>
      </div>
      <div class="header-actions">
        <RouterLink class="guide-action" to="/guide/admin">
          <BookOpen :size="16" />
          新手引导
        </RouterLink>
        <el-button v-if="isAdmin" :icon="RefreshCw" :loading="loading" @click="fetchStats">刷新</el-button>
        <RouterLink class="hero-action" to="/query">
          <MessageSquareText :size="16" />
          开始查询
        </RouterLink>
      </div>
    </header>

    <section v-if="isAdmin && stats" class="ops-strip">
      <article class="ops-card" :class="`tone-${qualityTone}`">
        <span>治理健康</span>
        <strong>{{ stats.avgQualityScore ?? '-' }}</strong>
        <small>{{ qualityLabel }}</small>
      </article>
      <article class="ops-card">
        <span>快照发布率</span>
        <strong>{{ snapshotPublishRate }}%</strong>
        <small>{{ stats.publishedSnapshots }} / {{ stats.totalSnapshots }} 个快照</small>
      </article>
      <article class="ops-card">
        <span>问题解决率</span>
        <strong>{{ issueResolutionRate }}%</strong>
        <small>{{ stats.resolvedIssues }} / {{ issueTotal }} 条问题</small>
      </article>
    </section>

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

    <section v-if="isAdmin && stats" class="priority-grid">
      <RouterLink class="priority-card urgent" to="/admin/governance/issues">
        <span class="priority-icon"><ShieldAlert :size="18" /></span>
        <div>
          <strong>{{ stats.openIssues }} 条待处理问题</strong>
          <small>按严重级别推进治理闭环</small>
        </div>
        <ArrowRight :size="16" />
      </RouterLink>
      <RouterLink class="priority-card" to="/admin/metadata/lifecycle">
        <span class="priority-icon"><GitBranch :size="18" /></span>
        <div>
          <strong>{{ stats.publishedSnapshots }} 个已发布快照</strong>
          <small>管理快照发布、过期和撤回</small>
        </div>
        <ArrowRight :size="16" />
      </RouterLink>
      <RouterLink class="priority-card" to="/admin/metadata/tables">
        <span class="priority-icon"><Table2 :size="18" /></span>
        <div>
          <strong>{{ stats.totalTables }} 张可查询表</strong>
          <small>查看字段、空值率和主键状态</small>
        </div>
        <ArrowRight :size="16" />
      </RouterLink>
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

.home-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.home-title {
  min-width: 0;
}

.home-title span {
  display: block;
  margin-bottom: 5px;
  color: var(--do-primary-strong);
  font-size: 13px;
  font-weight: 900;
}

.home-title h1 {
  margin: 0;
  color: var(--do-ink);
  font-size: 24px;
  line-height: 1.25;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.ops-strip {
  display: grid;
  grid-template-columns: 1.1fr 1fr 1fr;
  gap: 14px;
}

.ops-card {
  min-height: 92px;
  display: grid;
  align-content: center;
  gap: 4px;
  padding: 16px 18px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.ops-card span {
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 800;
}

.ops-card strong {
  color: var(--do-ink);
  font-size: 26px;
  line-height: 1.15;
}

.ops-card small {
  color: var(--do-muted);
  font-size: 12px;
}

.ops-card.tone-good {
  border-color: #bbf7d0;
  background: linear-gradient(135deg, #f0fdf4 0%, #ffffff 90%);
}

.ops-card.tone-warn {
  border-color: #fde68a;
  background: linear-gradient(135deg, #fffbeb 0%, #ffffff 90%);
}

.ops-card.tone-bad {
  border-color: #fecaca;
  background: linear-gradient(135deg, #fef2f2 0%, #ffffff 90%);
}

.hero-action {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 10px 18px; border-radius: 8px;
  background: var(--do-primary); color: #fff; font-weight: 600; font-size: 14px;
  white-space: nowrap; transition: background 150ms;
}
.hero-action:hover { background: var(--do-primary-strong); }

.guide-action {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 10px 18px; border-radius: 8px;
  background: var(--do-tone-orange-bg); color: var(--do-tone-orange); font-weight: 600; font-size: 14px;
  white-space: nowrap; transition: background 150ms; text-decoration: none;
}
.guide-action:hover { background: #ffe8b8; }

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
  transition: border-color 150ms, box-shadow 150ms;
}
.stat-card:hover { border-color: var(--do-line-strong); box-shadow: var(--do-shadow-hover); }

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

.priority-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.priority-card {
  min-height: 86px;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) 18px;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
  transition: border-color 150ms, box-shadow 150ms;
}

.priority-card:hover {
  border-color: var(--do-primary);
  box-shadow: var(--do-shadow-hover);
}

.priority-icon {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  color: var(--do-primary-strong);
  background: #eaf4ff;
}

.priority-card.urgent .priority-icon {
  color: #b45309;
  background: #fff7ed;
}

.priority-card strong,
.priority-card small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.priority-card strong {
  color: var(--do-ink);
  font-size: 14px;
}

.priority-card small {
  margin-top: 4px;
  color: var(--do-muted);
  font-size: 12px;
}

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
  .ops-strip,
  .priority-grid { grid-template-columns: 1fr; }
}
@media (max-width: 600px) {
  .stats-grid { grid-template-columns: 1fr; }
  .home-header { align-items: flex-start; flex-direction: column; }
}
</style>
