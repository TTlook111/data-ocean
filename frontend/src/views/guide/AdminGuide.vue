<script setup lang="ts">
import { useRouter } from 'vue-router'
import {
  Database, FolderSync, ShieldCheck, History, FileText, MessageSquare, ArrowRight, CheckCircle2
} from 'lucide-vue-next'

const router = useRouter()

const steps = [
  {
    icon: Database,
    color: 'blue',
    title: '接入数据源',
    desc: '在「数据源管理」中添加 MySQL 连接信息，点击测试确认连通后启用。支持多个数据源独立管理。',
    action: { label: '去添加数据源', to: '/admin/datasources' },
  },
  {
    icon: FolderSync,
    color: 'green',
    title: '采集元数据',
    desc: '在「同步任务」中触发元数据采集，系统自动扫描目标库的所有表结构、字段类型、索引和注释。',
    action: { label: '去同步', to: '/admin/metadata/sync' },
  },
  {
    icon: ShieldCheck,
    color: 'orange',
    title: '治理元数据',
    desc: '在「质量看板」查看自动校验结果，修复缺失注释、不一致命名等问题。质量分越高，AI 查询越准确。',
    action: { label: '去治理', to: '/admin/governance/quality' },
  },
  {
    icon: History,
    color: 'purple',
    title: '发布快照',
    desc: '治理完成后在「快照生命周期」中发布一个冻结版本。发布后的快照作为 AI 查询的可信数据依据。',
    action: { label: '去发布', to: '/admin/metadata/lifecycle' },
  },
  {
    icon: FileText,
    color: 'sky',
    title: '生成知识库',
    desc: '在「知识库总览」中选择快照，AI 自动生成 skills.md 业务文档。人工审核确认后发布，内容将向量化进入 RAG。',
    action: { label: '去生成', to: '/admin/knowledge' },
  },
  {
    icon: MessageSquare,
    color: 'blue',
    title: '开始查询',
    desc: '一切就绪！业务人员现在可以在问答端用自然语言查询该数据源的数据了。',
    action: { label: '去问答端', to: '/query' },
  },
]
</script>

<template>
  <main class="guide-page">
    <header class="guide-hero">
      <div class="hero-badge">管理员引导</div>
      <h1>数据治理全流程 — 6 步完成</h1>
      <p>从数据源接入到业务人员可查询，完整的治理闭环</p>
    </header>

    <section class="guide-timeline">
      <article v-for="(step, i) in steps" :key="i" class="timeline-card">
        <div class="timeline-left">
          <div class="step-number">{{ i + 1 }}</div>
          <div v-if="i < steps.length - 1" class="timeline-line"></div>
        </div>
        <div class="timeline-right">
          <div class="step-icon" :class="`tone-${step.color}`">
            <component :is="step.icon" :size="28" />
          </div>
          <div class="step-body">
            <h3>{{ step.title }}</h3>
            <p>{{ step.desc }}</p>
            <RouterLink v-if="step.action" :to="step.action.to" class="step-action">
              {{ step.action.label }} <ArrowRight :size="14" />
            </RouterLink>
          </div>
        </div>
      </article>
    </section>

    <footer class="guide-footer">
      <div class="done-badge"><CheckCircle2 :size="20" /> 完成以上步骤后，系统即可投入使用</div>
      <button class="guide-cta" @click="router.push('/admin')">
        返回工作台 <ArrowRight :size="18" />
      </button>
      <RouterLink to="/guide/query" class="guide-link">← 查看问答端引导</RouterLink>
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
  background: var(--do-tone-orange-bg);
  color: var(--do-tone-orange);
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

.guide-timeline {
  max-width: 680px;
  width: 100%;
}

.timeline-card {
  display: grid;
  grid-template-columns: 40px 1fr;
  gap: 16px;
}

.timeline-left {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.step-number {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--do-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  flex-shrink: 0;
}

.timeline-line {
  width: 2px;
  flex: 1;
  background: var(--do-line);
  margin: 8px 0;
}

.timeline-right {
  display: flex;
  gap: 16px;
  padding-bottom: 28px;
  align-items: flex-start;
}

.step-icon {
  width: 56px;
  height: 56px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  flex-shrink: 0;
}

.step-icon.tone-blue { background: var(--do-tone-blue-bg); color: var(--do-tone-blue); }
.step-icon.tone-green { background: var(--do-tone-green-bg); color: var(--do-tone-green); }
.step-icon.tone-orange { background: var(--do-tone-orange-bg); color: var(--do-tone-orange); }
.step-icon.tone-purple { background: var(--do-tone-purple-bg); color: var(--do-tone-purple); }
.step-icon.tone-sky { background: var(--do-tone-sky-bg); color: var(--do-tone-sky); }

.step-body h3 {
  margin: 0 0 6px;
  font-size: 16px;
  color: var(--do-ink);
}

.step-body p {
  margin: 0 0 10px;
  font-size: 14px;
  color: var(--do-muted);
  line-height: 1.6;
}

.step-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--do-primary);
  text-decoration: none;
  font-weight: 500;
}

.step-action:hover { text-decoration: underline; }

.guide-footer {
  margin-top: 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.done-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 18px;
  border-radius: 8px;
  background: var(--do-tone-green-bg);
  color: var(--do-tone-green);
  font-size: 14px;
  font-weight: 500;
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
