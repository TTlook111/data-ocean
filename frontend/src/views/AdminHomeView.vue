<script setup lang="ts">
import { computed, nextTick, ref, onMounted, watch } from 'vue'
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  ArrowRightLeft,
  BookOpen,
  Bot,
  CheckCircle2,
  Clock,
  Cpu,
  Database,
  FileText,
  GitBranch,
  Layers,
  MessageSquareText,
  RefreshCw,
  Shield,
  ShieldAlert,
  Table2,
  Users,
  Wand2,
  XCircle,
  Zap,
} from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { getDashboardStats, type DashboardStats } from '../api/admin/dashboard'
import {
  getBatchDatasourceReadiness,
  listSimpleDatasources,
  type DatasourceReadiness,
} from '../api/admin/datasource'
import { gsap } from 'gsap'
import { useGsapMotion } from '../composables/useGsapMotion'
import { useChart } from '../composables/useChart'

const auth = useAuthStore()
const loading = ref(false)
const readinessLoading = ref(false)
const stats = ref<DashboardStats | null>(null)
const datasourceReadiness = ref<DatasourceReadiness[]>([])
const homeRef = ref<HTMLElement | null>(null)
const { lift, reveal, revealAfterTick, withContext } = useGsapMotion(homeRef)

// 环形进度图 ref
const snapshotRingRef = ref<HTMLDivElement | null>(null)
const issueRingRef = ref<HTMLDivElement | null>(null)
const snapshotRingChart = useChart(snapshotRingRef)
const issueRingChart = useChart(issueRingRef)

// gsap CountUp 动画用的响应式数值
const animatedUsers = ref(0)
const animatedTables = ref(0)
const animatedColumns = ref(0)
const animatedOpenIssues = ref(0)
const animatedResolvedIssues = ref(0)

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
const askableDatasourceCount = computed(() => datasourceReadiness.value.filter((item) => item.askable).length)

const actionLabels: Record<string, string> = {
  PUBLISH: '发布', EXPIRE: '过期', REVOKE: '撤回', STATUS_TRANSITION: '状态变更'
}

/** 快捷操作配置 */
const quickActions = [
  { icon: MessageSquareText, label: '智能查询', desc: '用自然语言查询数据', path: '/query', color: 'blue' },
  { icon: Database, label: '数据源管理', desc: '管理数据源连接', path: '/admin/datasources', color: 'green' },
  { icon: Wand2, label: 'Prompt 管理', desc: '优化 AI 提示词', path: '/admin/prompts', color: 'purple' },
  { icon: Bot, label: 'AI 配置', desc: '配置 AI 供应商', path: '/admin/system/ai-config', color: 'sky' },
  { icon: Shield, label: '权限管理', desc: '管理访问策略', path: '/admin/permission/access', color: 'orange' },
  { icon: FileText, label: '知识库', desc: '管理 skills.md', path: '/admin/knowledge', color: 'teal' },
]

async function fetchStats() {
  if (!isAdmin.value) return
  loading.value = true
  try {
    const res = await getDashboardStats()
    stats.value = res.data ?? null
  } catch { /* 静默处理 */ }
  finally { loading.value = false }
}

async function fetchReadiness() {
  if (!isAdmin.value) return
  readinessLoading.value = true
  try {
    const datasourceRes = await listSimpleDatasources()
    const datasources = datasourceRes.data ?? []

    if (datasources.length === 0) {
      datasourceReadiness.value = []
      return
    }

    // 使用批量接口，一次请求获取所有数据源状态
    const ids = datasources.slice(0, 20).map(item => item.id)
    const result = await getBatchDatasourceReadiness(ids)
    datasourceReadiness.value = result.data ?? []
  } catch {
    datasourceReadiness.value = []
  } finally {
    readinessLoading.value = false
  }
}

/** 快捷操作卡片悬停动画 */
function animateQuickActions() {
  nextTick(() => {
    const cards = document.querySelectorAll('.quick-action-card')
    cards.forEach((card) => {
      card.addEventListener('mouseenter', () => {
        gsap.to(card, {
          y: -6,
          scale: 1.02,
          duration: 0.3,
          ease: 'power2.out',
          boxShadow: '0 8px 24px rgba(0, 0, 0, 0.15)',
        })
      })
      card.addEventListener('mouseleave', () => {
        gsap.to(card, {
          y: 0,
          scale: 1,
          duration: 0.3,
          ease: 'power2.out',
          boxShadow: '0 2px 8px rgba(0, 0, 0, 0.08)',
        })
      })
    })
  })
}

onMounted(() => {
  withContext(() => {
    // 欢迎区域动画
    reveal('.home-header', { y: -20, duration: 0.6, ease: 'back.out(1.7)' })

    // 快捷操作卡片交错动画
    reveal('.quick-action-card', {
      y: 30,
      opacity: 0,
      stagger: 0.08,
      duration: 0.5,
      ease: 'power3.out',
      delay: 0.2,
    })

    // 运维指标卡片动画
    reveal('.ops-card', {
      scale: 0.9,
      opacity: 0,
      stagger: 0.1,
      duration: 0.5,
      ease: 'back.out(1.7)',
      delay: 0.4,
    })

    // 统计卡片动画
    reveal('.stat-card', {
      y: 20,
      opacity: 0,
      stagger: 0.06,
      duration: 0.4,
      ease: 'power2.out',
      delay: 0.6,
    })

    // 优先事项卡片动画
    reveal('.priority-card', {
      x: -30,
      opacity: 0,
      stagger: 0.1,
      duration: 0.5,
      ease: 'power3.out',
      delay: 0.8,
    })

    // 活动列表动画
    reveal('.activity-item', {
      x: 20,
      opacity: 0,
      stagger: 0.05,
      duration: 0.3,
      ease: 'power2.out',
      delay: 1,
    })

    // 用户指南动画
    reveal('.user-guide', {
      y: 20,
      opacity: 0,
      duration: 0.5,
      ease: 'power2.out',
      delay: 0.3,
    })
  })

  fetchStats()
  fetchReadiness()
  animateQuickActions()
})

watch(stats, async (value) => {
  if (!value) return
  await nextTick()
  revealAfterTick('.ops-card, .stat-card, .priority-card, .activity-item', {
    y: 14,
    stagger: 0.035,
  })

  // 初始化环形进度图
  initRingCharts()

  // gsap CountUp 动画
  gsap.to(animatedUsers, { value: value.totalUsers, duration: 1, ease: 'power2.out', snap: { value: 1 } })
  gsap.to(animatedTables, { value: value.totalTables, duration: 1, ease: 'power2.out', snap: { value: 1 } })
  gsap.to(animatedColumns, { value: value.totalColumns, duration: 1, ease: 'power2.out', snap: { value: 1 } })
  gsap.to(animatedOpenIssues, { value: value.openIssues, duration: 1, ease: 'power2.out', snap: { value: 1 } })
  gsap.to(animatedResolvedIssues, { value: value.resolvedIssues, duration: 1, ease: 'power2.out', snap: { value: 1 } })
})

function initRingCharts() {
  const ringBase = {
    type: 'gauge' as const,
    startAngle: 90,
    endAngle: -270,
    pointer: { show: false },
    progress: { show: true, overlap: false, roundCap: true, clip: false, itemStyle: { color: '#4d8fdc' } },
    axisLine: { lineStyle: { width: 8, color: [[1, '#e5e7eb']] } },
    axisTick: { show: false },
    splitLine: { show: false },
    axisLabel: { show: false },
    detail: { fontSize: 16, fontWeight: 700, color: '#172033', offsetCenter: [0, 0], formatter: '{value}%' },
    data: [{ value: 0 }],
  }

  snapshotRingChart.init()
  snapshotRingChart.setOption({
    series: [{ ...ringBase, data: [{ value: snapshotPublishRate.value }] }],
  })

  issueRingChart.init()
  issueRingChart.setOption({
    series: [{ ...ringBase, data: [{ value: issueResolutionRate.value }] }],
  })
}

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

    <!-- 快捷操作区域 -->
    <section class="quick-actions-section">
      <h3 class="section-title">
        <Zap :size="18" />
        快捷操作
      </h3>
      <div class="quick-actions-grid">
        <RouterLink
          v-for="action in quickActions"
          :key="action.path"
          :to="action.path"
          class="quick-action-card"
          :class="`action-${action.color}`"
        >
          <div class="action-icon">
            <component :is="action.icon" :size="24" />
          </div>
          <div class="action-content">
            <strong>{{ action.label }}</strong>
            <small>{{ action.desc }}</small>
          </div>
          <ArrowRight :size="16" class="action-arrow" />
        </RouterLink>
      </div>
    </section>

    <section v-if="isAdmin" class="readiness-section" v-loading="readinessLoading">
      <div class="section-heading-row">
        <h3 class="section-title">
          <CheckCircle2 :size="18" />
          数据源上线状态
        </h3>
        <span v-if="datasourceReadiness.length" class="section-summary">
          {{ askableDatasourceCount }} / {{ datasourceReadiness.length }} 个可询问
        </span>
      </div>
      <div v-if="datasourceReadiness.length" class="readiness-list">
        <article
          v-for="item in datasourceReadiness"
          :key="item.datasourceId"
          class="readiness-item"
          :class="{ 'readiness-item--askable': item.askable }"
        >
          <div class="readiness-main">
            <strong>{{ item.datasourceName }}</strong>
            <small>{{ item.stageLabel }}</small>
          </div>
          <div class="readiness-progress">
            <el-progress :percentage="item.progress" :status="item.askable ? 'success' : undefined" />
            <span v-if="item.snapshotVersion">快照 v{{ item.snapshotVersion }}</span>
            <span v-if="item.knowledgeVersion">知识 v{{ item.knowledgeVersion }}</span>
          </div>
          <div class="readiness-reason">
            <template v-if="item.askable">
              <span class="reason-ok">已满足自然语言查询条件</span>
            </template>
            <template v-else>
              <span>{{ item.blockReasons[0]?.message || '等待补齐上线条件' }}</span>
              <small>{{ item.blockReasons[0]?.ownerRole || '治理负责人' }}</small>
            </template>
          </div>
          <RouterLink
            v-if="!item.askable && item.blockReasons[0]?.actionPath"
            class="readiness-action"
            :to="item.blockReasons[0].actionPath"
          >
            {{ item.blockReasons[0].actionText }}
            <ArrowRight :size="14" />
          </RouterLink>
          <RouterLink v-else class="readiness-action" :to="item.askable ? '/query' : '/admin/datasources'">
            {{ item.askable ? '开始查询' : '查看数据源' }}
            <ArrowRight :size="14" />
          </RouterLink>
        </article>
      </div>
      <el-empty v-else description="暂无可展示的数据源上线状态" :image-size="64" />
    </section>

    <section v-if="isAdmin && stats" class="ops-strip">
      <article class="ops-card" :class="`tone-${qualityTone}`">
        <span>治理健康</span>
        <strong>{{ stats.avgQualityScore ?? '-' }}</strong>
        <small>{{ qualityLabel }}</small>
        <div class="quality-bar"><div class="quality-fill" :style="{ width: (stats.avgQualityScore || 0) + '%' }"></div></div>
      </article>
      <article class="ops-card">
        <span>快照发布率</span>
        <div ref="snapshotRingRef" class="ring-chart"></div>
        <small>{{ stats.publishedSnapshots }} / {{ stats.totalSnapshots }} 个快照</small>
      </article>
      <article class="ops-card">
        <span>问题解决率</span>
        <div ref="issueRingRef" class="ring-chart"></div>
        <small>{{ stats.resolvedIssues }} / {{ issueTotal }} 条问题</small>
      </article>
    </section>

    <section v-if="isAdmin && stats" class="stats-grid" v-loading="loading">
      <article class="stat-card">
        <div class="stat-icon blue"><Users :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ animatedUsers }}</span>
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
          <span class="stat-value">{{ animatedTables }}</span>
          <span class="stat-label">已发布表</span>
        </div>
      </article>
      <article class="stat-card">
        <div class="stat-icon sky"><Layers :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ animatedColumns }}</span>
          <span class="stat-label">已发布字段</span>
        </div>
      </article>
      <article class="stat-card">
        <div class="stat-icon orange"><AlertTriangle :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ animatedOpenIssues }}</span>
          <span class="stat-label">待处理问题</span>
        </div>
      </article>
      <article class="stat-card">
        <div class="stat-icon teal"><CheckCircle2 :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ animatedResolvedIssues }}</span>
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

    <!-- 系统状态概览 -->
    <section v-if="isAdmin && stats" class="system-status-section">
      <h3 class="section-title">
        <Cpu :size="18" />
        系统运维
      </h3>
      <div class="status-grid">
        <RouterLink class="status-item" to="/admin/system/health">
          <div class="status-icon">
            <Cpu :size="16" />
          </div>
          <div class="status-info">
            <span class="status-label">服务健康监控</span>
            <span class="status-value">查看 Web、数据库、Redis、Python 服务实时状态</span>
          </div>
          <span class="status-badge">查看</span>
        </RouterLink>
        <RouterLink class="status-item" to="/admin/system/ai-config">
          <div class="status-icon">
            <Bot :size="16" />
          </div>
          <div class="status-info">
            <span class="status-label">AI 配置</span>
            <span class="status-value">管理供应商、模型、Embedding 与连接测试</span>
          </div>
          <span class="status-badge">配置</span>
        </RouterLink>
      </div>
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
          <span class="activity-icon" :class="'icon-' + a.type.toLowerCase()">
            <CheckCircle2 v-if="a.type === 'PUBLISH'" :size="14" />
            <Clock v-else-if="a.type === 'EXPIRE'" :size="14" />
            <XCircle v-else-if="a.type === 'REVOKE'" :size="14" />
            <ArrowRightLeft v-else :size="14" />
          </span>
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

/* 章节标题 */
.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 16px;
  font-size: 16px;
  font-weight: 600;
  color: var(--do-ink);
}

.section-title svg {
  color: var(--do-primary);
}

.section-heading-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.section-summary {
  color: var(--do-muted);
  font-size: 13px;
  font-weight: 700;
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

/* 快捷操作区域 */
.quick-actions-section {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: 12px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.quick-actions-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

.quick-action-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  background: #fff;
  text-decoration: none;
  color: var(--do-ink);
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.quick-action-card:hover {
  border-color: var(--do-primary);
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
}

.action-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  flex-shrink: 0;
  transition: transform 0.3s ease;
}

.quick-action-card:hover .action-icon {
  transform: scale(1.1) rotate(5deg);
}

.action-content {
  flex: 1;
  min-width: 0;
}

.action-content strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: var(--do-ink);
}

.action-content small {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--do-muted);
}

.action-arrow {
  color: var(--do-muted);
  transition: transform 0.3s ease, color 0.3s ease;
}

.quick-action-card:hover .action-arrow {
  transform: translateX(4px);
  color: var(--do-primary);
}

/* 快捷操作颜色变体 */
.action-blue .action-icon { color: #4d8fdc; background: #eef5ff; }
.action-green .action-icon { color: #6aa84f; background: #edf7e8; }
.action-purple .action-icon { color: #8b5cf6; background: #f3eeff; }
.action-sky .action-icon { color: #0ea5e9; background: #e8f7fd; }
.action-orange .action-icon { color: #f59e0b; background: #fef3cd; }
.action-teal .action-icon { color: #14b8a6; background: #e6faf8; }

.readiness-section {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: 12px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.readiness-list {
  display: grid;
  gap: 10px;
}

.readiness-item {
  display: grid;
  grid-template-columns: minmax(150px, 1.1fr) minmax(220px, 1.2fr) minmax(220px, 1.4fr) 118px;
  align-items: center;
  gap: 14px;
  padding: 12px 14px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: #fff;
}

.readiness-item--askable {
  border-color: #bbf7d0;
  background: #f8fffb;
}

.readiness-main,
.readiness-reason {
  min-width: 0;
}

.readiness-main strong,
.readiness-main small,
.readiness-reason span,
.readiness-reason small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.readiness-main strong {
  color: var(--do-ink);
  font-size: 14px;
}

.readiness-main small,
.readiness-reason small,
.readiness-progress span {
  color: var(--do-muted);
  font-size: 12px;
}

.readiness-progress {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.readiness-progress span {
  margin-right: 8px;
}

.readiness-reason span {
  color: var(--do-ink);
  font-size: 13px;
}

.readiness-reason .reason-ok {
  color: #15803d;
  font-weight: 700;
}

.readiness-action {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

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
.activity-icon {
  width: 24px; height: 24px; display: grid; place-items: center;
  border-radius: 50%; flex-shrink: 0;
}
.icon-publish { color: #67c23a; background: #edf7e8; }
.icon-expire { color: #909399; background: #f4f4f5; }
.icon-revoke { color: #f56c6c; background: #fef0f0; }
.icon-status_transition { color: #409eff; background: #ecf5ff; }
.activity-desc { flex: 1; font-size: 13px; color: var(--do-ink); }
.activity-desc strong { margin-right: 6px; }
.activity-time { font-size: 12px; color: var(--do-muted); white-space: nowrap; }

.ring-chart {
  width: 80px;
  height: 80px;
  margin: 2px auto;
}

.quality-bar {
  height: 4px;
  border-radius: 2px;
  background: #e5e7eb;
  margin-top: 8px;
}

.quality-fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.6s ease;
}

.tone-good .quality-fill { background: #67c23a; }
.tone-warn .quality-fill { background: #e6a23c; }
.tone-bad .quality-fill { background: #f56c6c; }

/* 系统状态区域 */
.system-status-section {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: 12px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  background: #fff;
  transition: all 0.3s ease;
}

.status-item:hover {
  border-color: var(--do-primary);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.status-icon {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  flex-shrink: 0;
}

.status-icon.online {
  color: #67c23a;
  background: #edf7e8;
}

.status-icon.offline {
  color: #f56c6c;
  background: #fef0f0;
}

.status-info {
  flex: 1;
  min-width: 0;
}

.status-label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--do-ink);
}

.status-value {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: var(--do-muted);
}

.status-badge {
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
}

.status-badge.online {
  color: #67c23a;
  background: #edf7e8;
}

.status-badge.offline {
  color: #f56c6c;
  background: #fef0f0;
}

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
  .readiness-item { grid-template-columns: 1fr; align-items: start; }
  .readiness-action { justify-content: flex-start; }
  .quick-actions-grid { grid-template-columns: repeat(2, 1fr); }
  .status-grid { grid-template-columns: 1fr; }
}
@media (max-width: 600px) {
  .stats-grid { grid-template-columns: 1fr; }
  .home-header { align-items: flex-start; flex-direction: column; }
  .section-heading-row { align-items: flex-start; flex-direction: column; }
  .quick-actions-grid { grid-template-columns: 1fr; }
}
</style>
