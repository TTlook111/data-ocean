<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import {
  BookOpenCheck,
  CheckCircle2,
  ClipboardCheck,
  Database,
  FileCheck2,
  KeyRound,
  Link2,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
} from 'lucide-vue-next'
import { getDatasourceReadiness, type DatasourceReadiness } from '../../../api/admin/datasource'

interface LifecycleStep {
  key: string
  title: string
  owner: string
  description: string
  actionText: string
  actionPath: string
  ready: boolean
  active: boolean
  icon: typeof Database
}

const route = useRoute()
const loading = ref(false)
const errorMessage = ref('')
const readiness = ref<DatasourceReadiness>()

const datasourceId = computed(() => Number(route.params.id))
const firstReason = computed(() => readiness.value?.blockReasons?.[0])

const steps = computed<LifecycleStep[]>(() => {
  const item = readiness.value
  if (!item) return []
  const firstBlockedKey = [
    ['connection', item.connectionReady],
    ['metadata', item.metadataReady],
    ['governance', item.governanceReady],
    ['knowledge', item.knowledgeReady],
    ['permission', item.permissionReady],
    ['askable', item.askable],
  ].find(([, ready]) => !ready)?.[0]

  return [
    {
      key: 'connection',
      title: '接入验证',
      owner: '数据管理员',
      description: '数据源已启用，并且最近一次连接健康检查通过。',
      actionText: '测试连接',
      actionPath: '/admin/datasources',
      ready: item.connectionReady,
      active: firstBlockedKey === 'connection',
      icon: Link2,
    },
    {
      key: 'metadata',
      title: '采集快照',
      owner: '治理负责人',
      description: item.snapshotVersion ? `已发布元数据快照 V${item.snapshotVersion}` : '需要完成元数据同步并发布可用快照。',
      actionText: '发布快照',
      actionPath: '/admin/metadata/lifecycle',
      ready: item.metadataReady,
      active: firstBlockedKey === 'metadata',
      icon: Database,
    },
    {
      key: 'governance',
      title: '治理处理',
      owner: '数据管理员',
      description: '高危治理问题已经处理，不会把阻塞或废弃字段带入查询链路。',
      actionText: '处理问题',
      actionPath: '/admin/governance/issues',
      ready: item.governanceReady,
      active: firstBlockedKey === 'governance',
      icon: ClipboardCheck,
    },
    {
      key: 'knowledge',
      title: '语义发布',
      owner: '数据分析师',
      description: item.knowledgeVersion ? `已发布 skills.md V${item.knowledgeVersion}` : '需要审核并发布 skills.md，完成向量化后才能进入召回。',
      actionText: '知识审核',
      actionPath: '/admin/knowledge/review',
      ready: item.knowledgeReady,
      active: firstBlockedKey === 'knowledge',
      icon: BookOpenCheck,
    },
    {
      key: 'permission',
      title: '权限配置',
      owner: '安全管理员',
      description: '已有有效查询授权，且没有被显式拒绝策略覆盖。',
      actionText: '配置权限',
      actionPath: '/admin/permission/access',
      ready: item.permissionReady,
      active: firstBlockedKey === 'permission',
      icon: KeyRound,
    },
    {
      key: 'askable',
      title: '可询问',
      owner: '业务用户',
      description: '该数据源已经可以在智能问答中使用。',
      actionText: '去查询',
      actionPath: '/query',
      ready: item.askable,
      active: firstBlockedKey === 'askable',
      icon: ShieldCheck,
    },
    {
      key: 'archive',
      title: '持续运营',
      owner: '平台管理员',
      description: '持续关注慢查询、反馈、权限变更和元数据版本演进。',
      actionText: '查看审计',
      actionPath: '/admin/audit/logs',
      ready: item.askable,
      active: false,
      icon: FileCheck2,
    },
  ]
})

async function fetchReadiness() {
  if (!datasourceId.value) {
    errorMessage.value = '数据源 ID 无效'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await getDatasourceReadiness(datasourceId.value)
    readiness.value = result.data
  } catch (error) {
    errorMessage.value =
      typeof error === 'object' &&
      error !== null &&
      'response' in error &&
      typeof (error as { response?: { data?: { message?: string } } }).response?.data?.message === 'string'
        ? (error as { response: { data: { message: string } } }).response.data.message
        : '数据源流程状态加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(fetchReadiness)
</script>

<template>
  <main class="lifecycle-page post-login-page">
    <section class="lifecycle-hero">
      <div class="hero-icon">
        <Database :size="34" />
      </div>
      <div class="hero-main">
        <span>数据源上线流程</span>
        <h1>{{ readiness?.datasourceName || `数据源 #${datasourceId}` }}</h1>
        <p>用接入、采集、治理、语义、权限和可询问状态判断当前数据源走到了哪一步。</p>
      </div>
      <div v-if="readiness" class="hero-status" :class="{ success: readiness.askable }">
        <strong>{{ readiness.stageLabel }}</strong>
        <small>{{ readiness.progress }}%</small>
      </div>
    </section>

    <el-result v-if="errorMessage" icon="error" title="流程状态加载失败" :sub-title="errorMessage">
      <template #extra>
        <el-button type="primary" @click="fetchReadiness">重试</el-button>
      </template>
    </el-result>

    <template v-else>
      <section v-loading="loading" class="flow-panel">
        <div v-if="readiness" class="flow-track">
          <div
            v-for="(step, index) in steps"
            :key="step.key"
            class="flow-step"
            :class="{ done: step.ready, active: step.active }"
          >
            <div class="step-line" :class="{ filled: index > 0 && steps[index - 1].ready }"></div>
            <div class="step-node">
              <CheckCircle2 v-if="step.ready" :size="18" />
              <component :is="step.icon" v-else :size="18" />
            </div>
            <strong>{{ step.title }}</strong>
            <span>{{ step.owner }}</span>
            <small>{{ step.ready ? '已完成' : step.active ? '当前阻塞' : '待处理' }}</small>
          </div>
        </div>
      </section>

      <section v-if="readiness" class="detail-grid">
        <article class="summary-panel">
          <div class="panel-title">
            <ShieldAlert v-if="!readiness.askable" :size="18" />
            <ShieldCheck v-else :size="18" />
            <strong>{{ readiness.askable ? '已达到可询问状态' : '当前阻塞原因' }}</strong>
          </div>
          <p>{{ firstReason?.message || '全部关键环节已完成，可以进入智能问答。' }}</p>
          <RouterLink
            v-if="firstReason?.actionPath"
            class="primary-link"
            :to="firstReason.actionPath"
          >
            {{ firstReason.actionText || '去处理' }}
          </RouterLink>
          <RouterLink v-else class="primary-link" to="/query">去智能问答</RouterLink>
        </article>

        <article class="summary-panel">
          <div class="panel-title">
            <RefreshCw :size="18" />
            <strong>版本状态</strong>
          </div>
          <dl class="status-list">
            <div>
              <dt>发布快照</dt>
              <dd>{{ readiness.snapshotVersion ? `V${readiness.snapshotVersion}` : '未发布' }}</dd>
            </div>
            <div>
              <dt>语义知识</dt>
              <dd>{{ readiness.knowledgeVersion ? `V${readiness.knowledgeVersion}` : '未发布' }}</dd>
            </div>
            <div>
              <dt>查询授权</dt>
              <dd>{{ readiness.permissionReady ? '已配置' : '未完成' }}</dd>
            </div>
          </dl>
        </article>

        <article class="step-detail-panel">
          <div class="panel-title">
            <ClipboardCheck :size="18" />
            <strong>流程明细</strong>
          </div>
          <div class="step-detail-list">
            <div v-for="step in steps" :key="step.key" class="step-detail" :class="{ active: step.active }">
              <component :is="step.ready ? CheckCircle2 : step.icon" :size="18" />
              <div>
                <strong>{{ step.title }}</strong>
                <p>{{ step.description }}</p>
              </div>
              <RouterLink :to="step.actionPath">{{ step.actionText }}</RouterLink>
            </div>
          </div>
        </article>
      </section>
    </template>
  </main>
</template>

<style scoped>
.lifecycle-page {
  display: grid;
  gap: 18px;
}

.lifecycle-hero {
  min-height: 132px;
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) auto;
  align-items: center;
  gap: 18px;
  padding: 24px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: linear-gradient(105deg, #fff 0%, #fff 54%, #eef5ff 100%);
  box-shadow: var(--do-shadow);
}

.hero-icon {
  width: 64px;
  height: 64px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: var(--do-primary-strong);
  background: rgba(77, 143, 220, 0.12);
}

.hero-main {
  min-width: 0;
}

.hero-main span {
  color: var(--do-muted);
  font-size: 13px;
  font-weight: 900;
}

.hero-main h1 {
  margin: 6px 0;
  overflow: hidden;
  color: var(--do-ink);
  font-size: 24px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero-main p {
  margin: 0;
  color: var(--do-muted);
  font-size: 13px;
}

.hero-status {
  min-width: 108px;
  display: grid;
  gap: 4px;
  justify-items: center;
  padding: 12px;
  border: 1px solid rgba(180, 83, 9, 0.22);
  border-radius: 8px;
  color: #92400e;
  background: #fffbeb;
}

.hero-status.success {
  border-color: rgba(22, 163, 74, 0.24);
  color: #15803d;
  background: #f0fdf4;
}

.hero-status strong {
  font-size: 14px;
}

.hero-status small {
  color: inherit;
  font-weight: 900;
}

.flow-panel,
.summary-panel,
.step-detail-panel {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.flow-panel {
  min-height: 188px;
  padding: 28px 18px 22px;
}

.flow-track {
  display: grid;
  grid-template-columns: repeat(7, minmax(110px, 1fr));
}

.flow-step {
  position: relative;
  min-width: 0;
  display: grid;
  justify-items: center;
  gap: 7px;
  color: var(--do-muted);
  text-align: center;
}

.step-line {
  position: absolute;
  top: 17px;
  right: 50%;
  left: -50%;
  height: 2px;
  background: #cbd5e1;
}

.flow-step:first-child .step-line {
  display: none;
}

.step-line.filled {
  background: #22c55e;
}

.step-node {
  position: relative;
  z-index: 1;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 2px solid #cbd5e1;
  border-radius: 50%;
  color: #64748b;
  background: #fff;
}

.flow-step.done .step-node {
  border-color: #22c55e;
  color: #fff;
  background: #22c55e;
}

.flow-step.active .step-node {
  border-color: #b45309;
  color: #b45309;
  background: #fffbeb;
}

.flow-step strong {
  max-width: 100%;
  overflow: hidden;
  color: var(--do-ink);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.flow-step.done strong {
  color: #15803d;
}

.flow-step span,
.flow-step small {
  max-width: 100%;
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-grid {
  display: grid;
  grid-template-columns: minmax(260px, 0.85fr) minmax(260px, 0.85fr) minmax(380px, 1.3fr);
  gap: 16px;
}

.summary-panel,
.step-detail-panel {
  padding: 18px;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--do-primary-strong);
}

.panel-title strong {
  color: var(--do-ink);
}

.summary-panel p {
  min-height: 54px;
  margin: 14px 0;
  color: #475569;
  font-size: 14px;
  line-height: 1.7;
}

.primary-link,
.step-detail a {
  color: var(--do-primary-strong);
  font-size: 13px;
  font-weight: 900;
  text-decoration: none;
}

.status-list {
  display: grid;
  gap: 12px;
  margin: 14px 0 0;
}

.status-list div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 10px;
  border-bottom: 1px dashed var(--do-line);
}

.status-list dt,
.status-list dd {
  margin: 0;
  font-size: 13px;
}

.status-list dt {
  color: var(--do-muted);
}

.status-list dd {
  color: var(--do-ink);
  font-weight: 900;
}

.step-detail-list {
  display: grid;
  gap: 10px;
  margin-top: 14px;
}

.step-detail {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 11px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: #fff;
}

.step-detail.active {
  border-color: rgba(180, 83, 9, 0.24);
  background: #fffbeb;
}

.step-detail svg {
  color: var(--do-primary-strong);
}

.step-detail div {
  min-width: 0;
}

.step-detail strong,
.step-detail p {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-detail strong {
  display: block;
  color: var(--do-ink);
  font-size: 13px;
}

.step-detail p {
  margin: 3px 0 0;
  color: var(--do-muted);
  font-size: 12px;
}

@media (max-width: 1180px) {
  .flow-track {
    grid-template-columns: repeat(4, minmax(120px, 1fr));
    row-gap: 24px;
  }

  .step-line {
    display: none;
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .lifecycle-hero {
    grid-template-columns: 1fr;
  }

  .hero-status {
    justify-items: start;
  }

  .flow-track {
    grid-template-columns: 1fr;
  }

  .flow-step {
    grid-template-columns: 34px 1fr auto;
    justify-items: start;
    text-align: left;
  }

  .flow-step span,
  .flow-step small {
    display: none;
  }
}
</style>
