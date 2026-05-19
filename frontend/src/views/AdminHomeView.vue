<script setup lang="ts">
import { computed } from 'vue'
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

const permissions = computed(() => auth.user?.permissions || auth.currentUser?.permissions || [])
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')

function hasPermission(perm: string) {
  return permissions.value.includes('*') || permissions.value.includes(perm)
}

const isAdmin = computed(() => permissions.value.includes('*'))

const quickEntries = computed(() => {
  const entries = []

  if (hasPermission('user:manage')) {
    entries.push({
      title: '用户管理',
      desc: '维护账号、部门归属、角色授权和账号状态。',
      to: '/admin/users',
      icon: Users,
      tone: 'blue' as const,
    })
  }

  if (hasPermission('datasource:manage')) {
    entries.push({
      title: '数据源管理',
      desc: '管理业务库连接、健康状态和用户授权范围。',
      to: '/admin/datasources',
      icon: Database,
      tone: 'green' as const,
    })
  }

  entries.push({
    title: '智能查询',
    desc: '选择已授权的数据源，用自然语言查询业务数据。',
    to: '/query',
    icon: MessageSquareText,
    tone: 'sky' as const,
  })

  entries.push({
    title: '个人资料',
    desc: '维护姓名、邮箱、手机号和登录密码。',
    to: '/profile',
    icon: UserRound,
    tone: 'cream' as const,
  })

  return entries
})

</script>

<template>
  <main class="admin-home post-login-page">
    <section class="dashboard-hero">
      <div class="hero-copy">
        <p v-if="isAdmin">管理工作台</p>
        <p v-else>数据查询工作台</p>
        <h1>{{ displayName }}，欢迎回来</h1>
        <span v-if="isAdmin">
          这里汇总平台治理入口，帮助你快速进入账号权限、数据源接入和问答查询相关工作。
        </span>
        <span v-else>
          选择已授权的数据源，用自然语言探索你的业务数据。
        </span>
      </div>
      <RouterLink class="hero-action" to="/query">
        <MessageSquareText :size="18" />
        开始查询
      </RouterLink>
    </section>

    <section v-if="isAdmin" class="status-grid">
      <article class="status-card">
        <span class="status-icon blue">
          <Users :size="20" />
        </span>
        <div>
          <strong>用户与角色</strong>
          <small>集中管控</small>
        </div>
      </article>
      <article class="status-card">
        <span class="status-icon green">
          <Database :size="20" />
        </span>
        <div>
          <strong>业务数据源</strong>
          <small>统一接入</small>
        </div>
      </article>
      <article class="status-card">
        <span class="status-icon orange">
          <Building2 :size="20" />
        </span>
        <div>
          <strong>组织部门</strong>
          <small>层级维护</small>
        </div>
      </article>
      <article class="status-card">
        <span class="status-icon purple">
          <KeyRound :size="20" />
        </span>
        <div>
          <strong>访问授权</strong>
          <small>范围可控</small>
        </div>
      </article>
    </section>

    <section v-else class="status-grid status-grid-2">
      <article class="status-card">
        <span class="status-icon sky">
          <MessageSquareText :size="20" />
        </span>
        <div>
          <strong>自然语言查询</strong>
          <small>用中文提问，AI 生成 SQL 并返回结果</small>
        </div>
      </article>
      <article class="status-card">
        <span class="status-icon green">
          <ShieldCheck :size="20" />
        </span>
        <div>
          <strong>安全可控</strong>
          <small>仅查询已授权数据源，敏感字段自动脱敏</small>
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

      <article v-if="isAdmin" class="guidance-panel">
        <header>
          <p>治理提醒</p>
          <strong>日常使用建议</strong>
        </header>
        <ul>
          <li>数据源仅向被授权用户开放，避免跨业务范围查询。</li>
          <li>账号、角色、部门统一维护，便于治理职责落地。</li>
          <li>健康检测与启停状态集中展示，降低无效查询入口。</li>
        </ul>
      </article>

      <article v-else class="guidance-panel">
        <header>
          <p>使用指南</p>
          <strong>如何开始查询</strong>
        </header>
        <ul>
          <li>进入「智能查询」，选择一个已授权的数据源。</li>
          <li>用自然语言描述你想查询的内容，例如「上月销售额前10的产品」。</li>
          <li>系统会自动生成 SQL、执行查询并返回表格或图表结果。</li>
          <li>如需更多数据源权限，请联系管理员开通。</li>
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
  min-height: 160px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 22px;
  overflow: hidden;
  padding: 28px 28px 28px 32px;
  border: 1px solid rgba(77, 143, 220, 0.22);
  border-radius: 12px;
  background:
    linear-gradient(135deg, rgba(189, 232, 248, 0.96) 0, rgba(255, 247, 227, 0.94) 54%, rgba(246, 251, 239, 0.96) 100%);
  box-shadow: var(--do-shadow);
}

.hero-copy {
  position: relative;
  z-index: 1;
  max-width: 740px;
}

.dashboard-hero p {
  margin: 0 0 6px;
  color: var(--do-primary-strong);
  font-size: 13px;
  font-weight: 900;
  letter-spacing: 0.5px;
}

.dashboard-hero h1 {
  margin: 0 0 10px;
  color: #1d3c34;
  font-size: 26px;
  line-height: 1.25;
}

.dashboard-hero span {
  color: #526653;
  font-size: 14px;
  line-height: 1.7;
}

.hero-action {
  position: relative;
  z-index: 1;
  height: 42px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 18px;
  border: 1px solid rgba(77, 143, 220, 0.26);
  border-radius: 8px;
  color: #fff;
  background: var(--do-primary);
  font-weight: 900;
  white-space: nowrap;
  box-shadow: 0 12px 24px rgba(77, 143, 220, 0.18);
  transition: background 160ms ease, transform 160ms ease;
}

.hero-action:hover {
  background: var(--do-primary-strong);
  transform: translateY(-1px);
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.status-grid-2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.status-card {
  min-height: 86px;
  display: grid;
  grid-template-columns: 44px 1fr;
  align-items: center;
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.status-icon {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: var(--do-radius);
}

.status-icon.blue {
  color: var(--do-tone-blue);
  background: var(--do-tone-blue-bg);
}

.status-icon.green {
  color: var(--do-tone-green);
  background: var(--do-tone-green-bg);
}

.status-icon.orange {
  color: var(--do-tone-orange);
  background: var(--do-tone-orange-bg);
}

.status-icon.purple {
  color: var(--do-tone-purple);
  background: var(--do-tone-purple-bg);
}

.status-icon.sky {
  color: var(--do-tone-sky);
  background: var(--do-tone-sky-bg);
}

.status-card strong {
  display: block;
  margin-bottom: 3px;
  color: var(--do-ink);
  font-size: 15px;
}

.status-card small {
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.5;
}

.workbench-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.entry-card {
  min-height: 120px;
  display: grid;
  grid-template-columns: 48px 1fr;
  align-items: start;
  gap: 14px;
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
  transition: transform 160ms ease, border-color 160ms ease, box-shadow 160ms ease;
}

.entry-card:hover {
  border-color: var(--do-primary);
  transform: translateY(-2px);
  box-shadow: var(--do-shadow-hover);
}

.entry-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: var(--do-radius);
  color: var(--do-tone-blue);
  background: var(--do-tone-blue-bg);
}

.entry-card.green .entry-icon {
  color: var(--do-tone-green);
  background: var(--do-tone-green-bg);
}

.entry-card.sky .entry-icon {
  color: var(--do-tone-sky);
  background: var(--do-tone-sky-bg);
}

.entry-card.cream .entry-icon {
  color: var(--do-tone-orange);
  background: var(--do-tone-orange-bg);
}

.entry-card strong {
  display: block;
  margin-bottom: 8px;
  color: var(--do-ink);
  font-size: 16px;
}

.entry-card small {
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.6;
}

.guidance-panel {
  grid-column: span 2;
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.guidance-panel header {
  margin-bottom: 14px;
}

.guidance-panel p {
  margin: 0 0 4px;
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 900;
}

.guidance-panel strong {
  display: block;
  color: var(--do-ink);
  font-size: 15px;
}

.guidance-panel ul {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  color: var(--do-muted);
  font-size: 14px;
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
  .status-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .workbench-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .guidance-panel {
    grid-column: span 2;
  }
}
</style>
