<script setup lang="ts">
import {
  Building2,
  CheckCircle2,
  Database,
  MessageSquareText,
  ShieldCheck,
  UserRound,
  Users,
} from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

const quickEntries = [
  {
    title: '用户管理',
    desc: '账号、角色、部门与状态维护',
    to: '/admin/users',
    icon: Users,
    tone: 'blue',
  },
  {
    title: '数据源管理',
    desc: '业务库接入、连接检测与授权',
    to: '/admin/datasources',
    icon: Database,
    tone: 'teal',
  },
  {
    title: '问答端数据源',
    desc: '查看当前用户可查询的数据源',
    to: '/query',
    icon: MessageSquareText,
    tone: 'indigo',
  },
  {
    title: '个人资料',
    desc: '维护姓名、邮箱、手机号和密码',
    to: '/profile',
    icon: UserRound,
    tone: 'slate',
  },
]

const moduleStatus = [
  { label: '001 用户模块', value: '已联调', icon: ShieldCheck },
  { label: '002 数据源管理', value: '已联调', icon: Database },
  { label: '截图证据', value: '17 张', icon: CheckCircle2 },
  { label: '组织结构', value: '可维护', icon: Building2 },
]
</script>

<template>
  <main class="admin-home post-login-page">
    <section class="dashboard-hero">
      <div>
        <p>DataOcean 工作台</p>
        <h1>欢迎回来，{{ auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '管理员' }}</h1>
        <span>当前已完成用户模块与数据源管理的前后端联调，后续功能会继续按截图证据推进。</span>
      </div>
      <RouterLink class="hero-action" to="/query">进入问答端</RouterLink>
    </section>

    <section class="status-grid">
      <article v-for="item in moduleStatus" :key="item.label" class="status-card">
        <span>
          <component :is="item.icon" :size="20" />
        </span>
        <div>
          <strong>{{ item.value }}</strong>
          <small>{{ item.label }}</small>
        </div>
      </article>
    </section>

    <section class="workbench-grid">
      <RouterLink
        v-for="entry in quickEntries"
        :key="entry.to"
        class="entry-card"
        :class="entry.tone"
        :to="entry.to"
      >
        <span class="entry-icon">
          <component :is="entry.icon" :size="22" />
        </span>
        <div>
          <strong>{{ entry.title }}</strong>
          <small>{{ entry.desc }}</small>
        </div>
      </RouterLink>

      <article class="progress-panel">
        <header>
          <p>开发节奏</p>
          <strong>下一阶段建议</strong>
        </header>
        <ol>
          <li>统一模块三开始的页面布局与表格工具栏</li>
          <li>继续每个联调功能保存截图</li>
          <li>逐步补齐查询工作台的问答输入与结果视图</li>
        </ol>
      </article>
    </section>
  </main>
</template>

<style scoped>
.admin-home {
  display: grid;
  gap: 18px;
}

.dashboard-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  min-height: 176px;
  padding: 24px;
  border-radius: 8px;
  color: #fff;
  background:
    linear-gradient(135deg, rgba(15, 23, 42, 0.96), rgba(30, 64, 175, 0.92)),
    linear-gradient(90deg, rgba(20, 184, 166, 0.3), transparent);
  box-shadow: var(--do-shadow);
}

.dashboard-hero p {
  margin: 0 0 8px;
  color: #bfdbfe;
  font-size: 13px;
  font-weight: 900;
}

.dashboard-hero h1 {
  margin: 0 0 12px;
  font-size: 28px;
}

.dashboard-hero span {
  color: #dbeafe;
  line-height: 1.7;
}

.hero-action {
  height: 40px;
  display: inline-flex;
  align-items: center;
  padding: 0 16px;
  border-radius: 8px;
  color: #0f172a;
  background: #fff;
  font-weight: 900;
  white-space: nowrap;
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.status-card,
.entry-card,
.progress-panel {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: #fff;
  box-shadow: var(--do-shadow);
}

.status-card {
  display: grid;
  grid-template-columns: 42px 1fr;
  align-items: center;
  gap: 12px;
  min-height: 86px;
  padding: 16px;
}

.status-card span,
.entry-icon {
  display: grid;
  place-items: center;
  border-radius: 6px;
  color: var(--do-primary);
  background: var(--do-primary-soft);
}

.status-card span {
  width: 42px;
  height: 42px;
}

.status-card strong,
.entry-card strong,
.progress-panel strong {
  display: block;
  color: var(--do-ink);
}

.status-card strong {
  margin-bottom: 4px;
  font-size: 22px;
}

.status-card small,
.entry-card small {
  color: var(--do-muted);
  line-height: 1.5;
}

.workbench-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.entry-card {
  min-height: 124px;
  display: grid;
  grid-template-columns: 48px 1fr;
  align-items: start;
  gap: 14px;
  padding: 18px;
}

.entry-card:hover {
  border-color: var(--do-primary);
  transform: translateY(-1px);
  transition: transform 160ms ease, border-color 160ms ease;
}

.entry-icon {
  width: 48px;
  height: 48px;
}

.entry-card strong {
  margin-bottom: 8px;
  font-size: 17px;
}

.entry-card.teal .entry-icon {
  color: #0f766e;
  background: #ecfdf5;
}

.entry-card.indigo .entry-icon {
  color: #4338ca;
  background: #eef2ff;
}

.entry-card.slate .entry-icon {
  color: #475569;
  background: #f1f5f9;
}

.progress-panel {
  grid-column: span 2;
  padding: 18px;
}

.progress-panel header {
  margin-bottom: 12px;
}

.progress-panel p {
  margin: 0 0 6px;
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 900;
}

.progress-panel ol {
  margin: 0;
  padding-left: 20px;
  color: var(--do-muted);
  line-height: 1.9;
}

@media (max-width: 1100px) {
  .status-grid,
  .workbench-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .progress-panel {
    grid-column: span 2;
  }
}

@media (max-width: 720px) {
  .dashboard-hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .status-grid,
  .workbench-grid {
    grid-template-columns: 1fr;
  }

  .progress-panel {
    grid-column: auto;
  }
}
</style>
