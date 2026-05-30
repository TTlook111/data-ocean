<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Database, FolderSync, ShieldCheck, History, FileText, MessageSquare,
  ArrowRight, CheckCircle2, ChevronDown
} from 'lucide-vue-next'

const router = useRouter()

// 跟踪哪些步骤的详情面板被展开
const expandedSteps = ref<Set<number>>(new Set())

function toggleDetail(index: number) {
  if (expandedSteps.value.has(index)) {
    expandedSteps.value.delete(index)
  } else {
    expandedSteps.value.add(index)
  }
}

interface DetailItem {
  label: string
  desc: string
  tag?: 'success' | 'warning' | 'danger' | 'info'
}

interface StepDef {
  icon: any
  color: string
  title: string
  desc: string
  action: { label: string; to: string }
  detailTitle?: string
  details?: DetailItem[]
  detailNote?: string
}

const steps: StepDef[] = [
  {
    icon: Database,
    color: 'blue',
    title: '接入数据源',
    desc: '在「数据源管理」中添加 MySQL 连接信息，点击测试确认连通后启用。支持多个数据源独立管理。',
    action: { label: '去添加数据源', to: '/admin/datasources' },
    detailTitle: '数据源状态说明',
    details: [
      { label: '启用', desc: '用户可在问答端选择该数据源进行查询', tag: 'success' },
      { label: '禁用', desc: '数据源暂停服务，用户不可见，已有配置保留', tag: 'warning' },
      { label: '连接测试', desc: '验证主机/端口/账号是否可达，测试失败不影响保存但无法启用', tag: 'info' },
    ],
    detailNote: '建议：生产库使用只读账号接入，避免误操作风险。',
  },
  {
    icon: FolderSync,
    color: 'green',
    title: '采集元数据',
    desc: '在「同步任务」中触发元数据采集，系统自动扫描目标库的所有表结构、字段类型、索引和注释。',
    action: { label: '去同步', to: '/admin/metadata/sync' },
    detailTitle: '采集方式说明',
    details: [
      { label: '手动触发', desc: '立即执行一次全量采集，适合首次接入或结构变更后', tag: 'info' },
      { label: '定时调度', desc: '在「同步调度」中配置 cron 表达式，系统按计划自动采集', tag: 'info' },
    ],
    detailNote: '采集只读取表结构信息（DDL），不会读取业务数据内容。',
  },
  {
    icon: ShieldCheck,
    color: 'orange',
    title: '治理元数据',
    desc: '在「质量看板」查看自动校验结果，修复缺失注释、不一致命名等问题。质量分越高，AI 查询越准确。',
    action: { label: '去治理', to: '/admin/governance/quality' },
    detailTitle: '治理状态与 RAG 准入规则',
    details: [
      { label: 'NORMAL — 正常', desc: '可被 RAG 召回，AI 可基于该字段生成 SQL', tag: 'success' },
      { label: 'RECOMMENDED — 推荐', desc: '召回优先级更高，AI 优先使用该字段', tag: 'success' },
      { label: 'SENSITIVE — 敏感', desc: '可召回但查询结果会自动脱敏（如手机号显示为 138****1234）', tag: 'warning' },
      { label: 'DEPRECATED — 已废弃', desc: '禁止召回，AI 不会使用该字段生成 SQL', tag: 'danger' },
      { label: 'BLOCKED — 已屏蔽', desc: '完全不可见，不进入向量库，不出现在任何查询中', tag: 'danger' },
    ],
    detailNote: '影响：治理状态直接决定字段能否被 AI 召回和出现在查询结果中。只有 NORMAL/RECOMMENDED/SENSITIVE 且审核通过的字段才会进入 RAG 知识库。',
  },
  {
    icon: History,
    color: 'purple',
    title: '发布快照',
    desc: '治理完成后在「快照生命周期」中发布一个冻结版本。发布后的快照作为 AI 查询的可信数据依据。',
    action: { label: '去发布', to: '/admin/metadata/lifecycle' },
    detailTitle: '快照生命周期说明',
    details: [
      { label: 'DRAFT — 草稿', desc: '刚采集的快照，可继续治理修改，尚未生效', tag: 'info' },
      { label: 'PUBLISHED — 已发布', desc: '冻结生效，AI 查询基于此版本的表结构。同一数据源只有一个生效快照', tag: 'success' },
      { label: 'ARCHIVED — 已归档', desc: '历史版本，被新快照取代后自动归档，可用于版本对比', tag: 'warning' },
    ],
    detailNote: '影响：发布快照后，AI 的 Schema RAG 会切换到新版本。旧快照的向量数据会被清理，确保查询始终基于最新治理结果。',
  },
  {
    icon: FileText,
    color: 'sky',
    title: '生成知识库',
    desc: '在「知识库总览」中选择快照，AI 自动生成 skills.md 业务文档。人工审核确认后发布，内容将向量化进入 RAG。',
    action: { label: '去生成', to: '/admin/knowledge' },
    detailTitle: '知识库文档状态流转',
    details: [
      { label: 'DRAFT — 草稿', desc: 'AI 生成或手动编辑中，可反复修改', tag: 'info' },
      { label: 'PENDING_REVIEW — 待审核', desc: '已提交审核，等待管理员确认内容准确性', tag: 'warning' },
      { label: 'APPROVED — 已通过', desc: '审核通过，可以发布', tag: 'success' },
      { label: 'PUBLISHED — 已发布', desc: '内容已向量化进入 RAG，AI 查询时会召回这些知识', tag: 'success' },
    ],
    detailNote: '影响：只有 PUBLISHED 状态的文档才会被向量化。文档质量直接影响 AI 理解业务语义的能力——注释越完整、描述越准确，查询结果越好。',
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

            <!-- 折叠面板：了解更多 -->
            <button v-if="step.details" class="detail-toggle" @click="toggleDetail(i)">
              <ChevronDown :size="14" :class="{ rotated: expandedSteps.has(i) }" />
              {{ expandedSteps.has(i) ? '收起' : '了解更多' }}：{{ step.detailTitle }}
            </button>
            <div v-if="step.details && expandedSteps.has(i)" class="detail-panel">
              <div v-for="(item, j) in step.details" :key="j" class="detail-item">
                <span class="detail-tag" :class="`tag-${item.tag}`">{{ item.label }}</span>
                <span class="detail-desc">{{ item.desc }}</span>
              </div>
              <p v-if="step.detailNote" class="detail-note">💡 {{ step.detailNote }}</p>
            </div>

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

.guide-hero { text-align: center; margin-bottom: 48px; }

.hero-badge {
  display: inline-block; padding: 4px 14px; border-radius: 20px;
  background: var(--do-tone-orange-bg); color: var(--do-tone-orange);
  font-size: 13px; font-weight: 600; margin-bottom: 16px;
}

.guide-hero h1 { font-size: 28px; color: var(--do-ink); margin: 0 0 8px; }
.guide-hero p { font-size: 15px; color: var(--do-muted); margin: 0; }

.guide-timeline { max-width: 680px; width: 100%; }

.timeline-card { display: grid; grid-template-columns: 40px 1fr; gap: 16px; }

.timeline-left { display: flex; flex-direction: column; align-items: center; }

.step-number {
  width: 32px; height: 32px; display: grid; place-items: center;
  border-radius: 50%; background: var(--do-primary); color: #fff;
  font-size: 13px; font-weight: 700; flex-shrink: 0;
}

.timeline-line { width: 2px; flex: 1; background: var(--do-line); margin: 8px 0; }

.timeline-right { display: flex; gap: 16px; padding-bottom: 28px; align-items: flex-start; }

.step-icon {
  width: 56px; height: 56px; display: grid; place-items: center;
  border-radius: 14px; flex-shrink: 0;
}

.step-icon.tone-blue { background: var(--do-tone-blue-bg); color: var(--do-tone-blue); }
.step-icon.tone-green { background: var(--do-tone-green-bg); color: var(--do-tone-green); }
.step-icon.tone-orange { background: var(--do-tone-orange-bg); color: var(--do-tone-orange); }
.step-icon.tone-purple { background: var(--do-tone-purple-bg); color: var(--do-tone-purple); }
.step-icon.tone-sky { background: var(--do-tone-sky-bg); color: var(--do-tone-sky); }

.step-body h3 { margin: 0 0 6px; font-size: 16px; color: var(--do-ink); }
.step-body p { margin: 0 0 10px; font-size: 14px; color: var(--do-muted); line-height: 1.6; }

/* 折叠面板 */
.detail-toggle {
  display: inline-flex; align-items: center; gap: 4px;
  background: none; border: none; cursor: pointer;
  font-size: 13px; color: var(--do-primary); font-weight: 500;
  padding: 4px 0; margin-bottom: 8px;
}

.detail-toggle:hover { text-decoration: underline; }

.detail-toggle svg { transition: transform 0.2s; }
.detail-toggle svg.rotated { transform: rotate(180deg); }

.detail-panel {
  margin: 8px 0 14px;
  padding: 14px 16px;
  background: var(--do-soft);
  border: 1px solid var(--do-line);
  border-radius: 8px;
}

.detail-item {
  display: flex; align-items: baseline; gap: 10px;
  padding: 6px 0;
  border-bottom: 1px solid var(--do-line);
}

.detail-item:last-of-type { border-bottom: none; }

.detail-tag {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.detail-tag.tag-success { background: var(--do-tone-green-bg); color: var(--do-tone-green); }
.detail-tag.tag-warning { background: var(--do-tone-orange-bg); color: var(--do-tone-orange); }
.detail-tag.tag-danger { background: #fde8e8; color: var(--do-danger); }
.detail-tag.tag-info { background: var(--do-tone-blue-bg); color: var(--do-tone-blue); }

.detail-desc { font-size: 13px; color: var(--do-ink); line-height: 1.5; }

.detail-note {
  margin: 12px 0 0;
  padding-top: 10px;
  border-top: 1px dashed var(--do-line);
  font-size: 13px;
  color: var(--do-muted);
  line-height: 1.5;
}

.step-action {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 13px; color: var(--do-primary); text-decoration: none; font-weight: 500;
}

.step-action:hover { text-decoration: underline; }

.guide-footer { margin-top: 32px; display: flex; flex-direction: column; align-items: center; gap: 16px; }

.done-badge {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 8px 18px; border-radius: 8px;
  background: var(--do-tone-green-bg); color: var(--do-tone-green);
  font-size: 14px; font-weight: 500;
}

.guide-cta {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 12px 32px; border: none; border-radius: 8px;
  background: var(--do-primary); color: #fff;
  font-size: 15px; font-weight: 600; cursor: pointer; transition: background 0.2s;
}

.guide-cta:hover { background: var(--do-primary-strong); }

.guide-link { font-size: 13px; color: var(--do-muted); text-decoration: none; }
.guide-link:hover { color: var(--do-primary); }
</style>
