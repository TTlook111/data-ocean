<script setup lang="ts">
import { useRouter } from 'vue-router'
import { Database, MessageSquare, BarChart3, History, ArrowRight } from 'lucide-vue-next'

const router = useRouter()

const steps = [
  {
    icon: Database,
    color: 'blue',
    title: '选择数据源',
    desc: '从左侧列表中选择一个你有权限访问的数据库，系统会自动加载该库的表结构信息。',
  },
  {
    icon: MessageSquare,
    color: 'green',
    title: '用自然语言提问',
    desc: '在输入框中直接描述你想查的数据，例如「上月销售额最高的 10 个产品」「本周新增用户数趋势」。',
  },
  {
    icon: BarChart3,
    color: 'purple',
    title: '查看结果与图表',
    desc: 'AI 会自动生成 SQL 并执行，结果以表格展示。点击「图表」标签可切换为柱状图、折线图或饼图。',
  },
  {
    icon: History,
    color: 'orange',
    title: '多轮追问',
    desc: '在同一会话中继续提问，AI 会理解上下文。比如先问「上月销售额」，再追问「按产品分类呢？」。',
  },
]
</script>

<template>
  <main class="guide-page">
    <header class="guide-hero">
      <div class="hero-badge">快速入门</div>
      <h1>智能数据查询 — 4 步上手</h1>
      <p>无需 SQL 知识，用自然语言即可查询企业数据库</p>
    </header>

    <section class="guide-steps">
      <article v-for="(step, i) in steps" :key="i" class="step-card">
        <div class="step-number">{{ i + 1 }}</div>
        <div class="step-icon" :class="`tone-${step.color}`">
          <component :is="step.icon" :size="32" />
        </div>
        <div class="step-body">
          <h3>{{ step.title }}</h3>
          <p>{{ step.desc }}</p>
        </div>
      </article>
    </section>

    <footer class="guide-footer">
      <button class="guide-cta" @click="router.push('/query')">
        开始查询 <ArrowRight :size="18" />
      </button>
      <RouterLink to="/guide/admin" class="guide-link">我是管理员，查看后台引导 →</RouterLink>
    </footer>
  </main>
</template>

<style scoped>
.guide-page {
  min-height: 100vh;
  padding: 60px 24px;
  background: var(--do-bg);
  display: flex;
  flex-direction: column;
  align-items: center;
}

.guide-hero {
  text-align: center;
  margin-bottom: 48px;
}

.hero-badge {
  display: inline-block;
  padding: 4px 14px;
  border-radius: 20px;
  background: var(--do-primary-soft);
  color: var(--do-primary);
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 16px;
}

.guide-hero h1 {
  font-size: 28px;
  color: var(--do-ink);
  margin: 0 0 8px;
}

.guide-hero p {
  font-size: 15px;
  color: var(--do-muted);
  margin: 0;
}

.guide-steps {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-width: 640px;
  width: 100%;
}

.step-card {
  display: grid;
  grid-template-columns: 32px 72px 1fr;
  align-items: center;
  gap: 16px;
  padding: 24px;
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  box-shadow: var(--do-shadow);
}

.step-number {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--do-primary);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
}

.step-icon {
  width: 72px;
  height: 72px;
  display: grid;
  place-items: center;
  border-radius: 16px;
}

.step-icon.tone-blue { background: var(--do-tone-blue-bg); color: var(--do-tone-blue); }
.step-icon.tone-green { background: var(--do-tone-green-bg); color: var(--do-tone-green); }
.step-icon.tone-purple { background: var(--do-tone-purple-bg); color: var(--do-tone-purple); }
.step-icon.tone-orange { background: var(--do-tone-orange-bg); color: var(--do-tone-orange); }

.step-body h3 {
  margin: 0 0 6px;
  font-size: 16px;
  color: var(--do-ink);
}

.step-body p {
  margin: 0;
  font-size: 14px;
  color: var(--do-muted);
  line-height: 1.6;
}

.guide-footer {
  margin-top: 48px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.guide-cta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 12px 32px;
  border: none;
  border-radius: 8px;
  background: var(--do-primary);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
}

.guide-cta:hover { background: var(--do-primary-strong); }

.guide-link {
  font-size: 13px;
  color: var(--do-muted);
  text-decoration: none;
}

.guide-link:hover { color: var(--do-primary); }
</style>
