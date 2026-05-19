<script setup lang="ts">
import {
  Building2,
  Database,
  KeyRound,
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
    desc: '维护账号、部门归属、角色授权和账号状态。',
    to: '/admin/users',
    icon: Users,
    tone: 'blue',
  },
  {
    title: '数据源管理',
    desc: '管理业务库连接、健康状态和用户授权范围。',
    to: '/admin/datasources',
    icon: Database,
    tone: 'green',
  },
  {
    title: '问答端数据源',
    desc: '选择已授权的数据源，进入自然语言查询工作区。',
    to: '/query',
    icon: MessageSquareText,
    tone: 'sky',
  },
  {
    title: '个人资料',
    desc: '维护姓名、邮箱、手机号和登录密码。',
    to: '/profile',
    icon: UserRound,
    tone: 'cream',
  },
]

const governanceStats = [
  { label: '用户与角色', value: '集中管控', icon: ShieldCheck },
  { label: '业务数据源', value: '统一接入', icon: Database },
  { label: '组织部门', value: '层级维护', icon: Building2 },
  { label: '访问授权', value: '范围可控', icon: KeyRound },
]

const platformNotes = [
  '数据源仅向被授权用户开放，避免跨业务范围查询。',
  '账号、角色、部门统一维护，便于治理职责落地。',
  '健康检测与启停状态集中展示，降低无效查询入口。',
]
</script>

<template>
  <main class="admin-home post-login-page">
    <section class="dashboard-hero">
      <div class="hero-copy">
        <p>DataOcean 工作台</p>
        <h1>欢迎回来，{{ auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '管理员' }}</h1>
        <span>这里汇总平台治理入口，帮助你快速进入账号权限、数据源接入和问答查询相关工作。</span>
      </div>
      <RouterLink class="hero-action" to="/query">
        <MessageSquareText :size="18" />
        进入问答端
      </RouterLink>
    </section>

    <section class="status-grid">
      <article v-for="item in governanceStats" :key="item.label" class="status-card">
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

      <article class="guidance-panel">
        <header>
          <p>治理提醒</p>
          <strong>日常使用建议</strong>
        </header>
        <ul>
          <li v-for="note in platformNotes" :key="note">{{ note }}</li>
        </ul>
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
  position: relative;
  min-height: 176px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 22px;
  overflow: hidden;
  padding: 24px;
  border: 1px solid rgba(77, 143, 220, 0.22);
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(189, 232, 248, 0.96) 0, rgba(255, 247, 227, 0.94) 54%, rgba(246, 251, 239, 0.96) 100%);
  box-shadow: var(--do-shadow);
}

.dashboard-hero::after {
  position: absolute;
  right: 26px;
  bottom: 18px;
  width: 180px;
  height: 74px;
  border: solid rgba(106, 168, 79, 0.32);
  border-width: 0 0 4px 4px;
  border-radius: 0 0 0 70px;
  content: "";
}

.hero-copy {
  position: relative;
  z-index: 1;
  max-width: 740px;
}

.dashboard-hero p {
  margin: 0 0 8px;
  color: var(--do-primary-strong);
  font-size: 13px;
  font-weight: 900;
}

.dashboard-hero h1 {
  margin: 0 0 12px;
  color: #1d3c34;
  font-size: 28px;
  line-height: 1.22;
}

.dashboard-hero span {
  color: #526653;
  line-height: 1.7;
}

.hero-action {
  position: relative;
  z-index: 1;
  height: 42px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  border: 1px solid rgba(77, 143, 220, 0.26);
  border-radius: 8px;
  color: #fff;
  background: var(--do-primary);
  font-weight: 900;
  white-space: nowrap;
  box-shadow: 0 12px 24px rgba(77, 143, 220, 0.18);
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.status-card,
.entry-card,
.guidance-panel {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.status-card {
  min-height: 86px;
  display: grid;
  grid-template-columns: 42px 1fr;
  align-items: center;
  gap: 12px;
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
.guidance-panel strong {
  display: block;
  color: var(--do-ink);
}

.status-card strong {
  margin-bottom: 4px;
  font-size: 21px;
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

.entry-card.green .entry-icon {
  color: var(--do-accent-strong);
  background: #eff9e9;
}

.entry-card.sky .entry-icon {
  color: #2f73bd;
  background: #eaf7ff;
}

.entry-card.cream .entry-icon {
  color: #b9811e;
  background: #fff4d6;
}

.guidance-panel {
  grid-column: span 2;
  padding: 18px;
}

.guidance-panel header {
  margin-bottom: 12px;
}

.guidance-panel p {
  margin: 0 0 6px;
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 900;
}

.guidance-panel ul {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  color: var(--do-muted);
  line-height: 1.7;
  list-style: none;
}

.guidance-panel li {
  position: relative;
  padding-left: 16px;
}

.guidance-panel li::before {
  position: absolute;
  top: 0.72em;
  left: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--do-accent);
  content: "";
}

@media (max-width: 1180px) {
  .status-grid,
  .workbench-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .guidance-panel {
    grid-column: span 2;
  }
}
</style>
