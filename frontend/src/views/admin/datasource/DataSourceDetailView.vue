<script setup lang="ts">
import { computed, onScopeDispose, ref, watch, type Component } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ArrowRight, CheckCircle2, CircleAlert, Database, PlugZap, RefreshCw, Workflow } from 'lucide-vue-next'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { getDatasource, getDatasourceReadiness, testSavedDatasourceConnection, updateDatasourceStatus, type DatasourceItem, type DatasourceReadiness } from '../../../api/admin/datasource'
import { buildContextQuery, resolveReadinessAction } from '../../../utils/adminNavigation'
import { canOpenDatasourceWorkspace } from '../../../utils/datasourcePreparation'
import { getHttpErrorMessage } from '../../../utils/httpError'
import { knowledgeStatusLabel, severityLabel, snapshotStatusLabel } from '../../../utils/enumLabels'
import { listSnapshots, listSyncTasks, triggerSync, type SnapshotItem, type SyncTaskItem } from '../../../api/admin/metadata'
import { listQualityIssues, type QualityIssueItem } from '../../../api/admin/governance'
import { listKnowledgeDocs, type KnowledgeDocItem } from '../../../api/admin/knowledge'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import DatasourcePreparation from '../../../components/admin/DatasourcePreparation.vue'
import ActivityTimeline from '../../../components/admin/ActivityTimeline.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAuthStore } from '../../../stores/auth'

const route = useRoute(), router = useRouter(), iamS1 = useIamS1Store(), auth = useAuthStore()
const datasourceId = computed(() => Number(route.params.id))
const canView = computed(() => iamS1.canOnDatasource('datasource:view', datasourceId.value))
const canManage = computed(() => iamS1.canOnDatasource('datasource:manage', datasourceId.value))
const canCollect = computed(() => iamS1.canOnDatasource('metadata:collect:run', datasourceId.value))
const canReadSnapshots = computed(() => iamS1.canOnDatasource('metadata:view', datasourceId.value))
const canReadCollections = computed(() => iamS1.canOnDatasource('metadata:collect:view', datasourceId.value))
const canReadKnowledge = computed(() => iamS1.canOnDatasource('knowledge:view', datasourceId.value))
const canReadIssues = computed(() => iamS1.canOnDatasource('governance:issue:view', datasourceId.value))
const MANAGE_HINT = '没有管理当前数据源的权限。'
const datasource = ref<DatasourceItem | null>(null), readiness = ref<DatasourceReadiness | null>(null)
const snapshots = ref<SnapshotItem[]>([]), syncTasks = ref<SyncTaskItem[]>([]), qualityIssues = ref<QualityIssueItem[]>([]), knowledgeDocs = ref<KnowledgeDocItem[]>([])
const loading = ref(true), actionLoading = ref(false), error = ref('')
const snapshotRequestOk = ref(false), knowledgeRequestOk = ref(false), syncRequestOk = ref(false), issuesError = ref('')
let readRequest = 0, scopeGeneration = 0, alive = true, autoTestKey = ''
onScopeDispose(() => { alive = false; readRequest++; scopeGeneration++ })
const publishedSnapshot = computed(() => snapshots.value.find(item => item.id === readiness.value?.publishedSnapshotId))
const primaryReasonMessage = computed(() => readiness.value?.blockReasons?.[0]?.message || '')
const knowledgePreview = computed(() => knowledgeDocs.value.slice(0, 3)), qualityIssuePreview = computed(() => qualityIssues.value.slice(0, 3))
const knowledgeWorkspaceLink = computed<RouteLocationRaw>(() => ({ path: '/admin/semantics/knowledge', query: buildContextQuery('datasource', { datasourceId: datasourceId.value }) }))
const datasourceAddress = computed(() => datasource.value ? `${datasource.value.host}:${datasource.value.port}` : '')
function canNavigate(to?: RouteLocationRaw) {
  const path = typeof to === 'string' ? to.split('?')[0] : to && 'path' in to ? to.path : undefined
  return Boolean(path && canOpenDatasourceWorkspace(path, iamS1, datasourceId.value))
}
const remainingActions = computed(() => readiness.value?.blockReasons.slice(1).map(reason => {
  const to = resolveReadinessAction(reason.code, { datasourceId: datasourceId.value, snapshotId: readiness.value?.publishedSnapshotId })
  return { reason, to, allowed: canNavigate(to) }
}) || [])
function formatCompactTime(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}
interface PrimaryAction { key: string; label: string; icon?: Component; to?: RouteLocationRaw }
const primaryAction = computed<PrimaryAction>(() => {
  if (!readiness.value) return { key: 'reload', label: '刷新状态' }
  const reason = readiness.value.blockReasons?.[0]
  if (!reason) return readiness.value.askable
    ? { key: 'navigate', label: '查看数据资产', icon: Database, to: { path: '/admin/assets', query: { datasourceId: String(datasourceId.value) } } }
    : { key: 'reload', label: '刷新状态', icon: RefreshCw }
  if (reason.code === 'DATASOURCE_DISABLED') return { key: 'enable', label: reason.actionText || '启用数据源', icon: Database }
  if (reason.code === 'CONNECTION_NOT_HEALTHY') return { key: 'test', label: reason.actionText || '测试连接', icon: PlugZap }
  if (reason.code === 'SNAPSHOT_NOT_PUBLISHED' && !readiness.value.latestCollectedSnapshotId) return { key: 'collect', label: '开始采集', icon: Workflow }
  const to = resolveReadinessAction(reason.code, { datasourceId: datasourceId.value, snapshotId: readiness.value.publishedSnapshotId })
  return to ? { key: 'navigate', label: reason.actionText || '去处理', icon: ArrowRight, to } : { key: 'reload', label: '刷新状态', icon: RefreshCw }
})
const primaryActionAllowed = computed(() => {
  if (['test','enable'].includes(primaryAction.value.key)) return canManage.value
  if (primaryAction.value.key === 'collect') return canCollect.value
  if (primaryAction.value.key === 'navigate') return canNavigate(primaryAction.value.to)
  return true
})
const activityItems = computed(() => [
  ...syncTasks.value.slice(0, 3).map(task => ({ time: task.finishedAt || task.startedAt, action: '元数据采集 · ' + task.status, result: task.errorMessage || task.datasourceName })),
  ...snapshots.value.slice(0, 2).map(snapshot => ({ time: snapshot.createdAt, action: '快照 v' + snapshot.snapshotVersion, result: snapshot.status })),
])
async function load() {
  const ticket = ++readRequest, id = datasourceId.value, token = auth.token
  const current = () => alive && ticket === readRequest && id === datasourceId.value && token === auth.token
  loading.value = true; error.value = ''; datasource.value = null; readiness.value = null
  snapshotRequestOk.value = false; knowledgeRequestOk.value = false; syncRequestOk.value = false; issuesError.value = ''
  snapshots.value = []; syncTasks.value = []; knowledgeDocs.value = []; qualityIssues.value = []
  if (!Number.isSafeInteger(id) || id <= 0) { error.value = '数据源编号无效'; loading.value = false; return }
  await iamS1.load()
  if (!current()) return
  if (!canView.value) { error.value = iamS1.errorMessage || '没有查看数据源的权限。'; loading.value = false; return }
  try {
    const [sourceResult, readinessResult] = await Promise.all([getDatasource(id), getDatasourceReadiness(id)])
    if (!current()) return
    if (sourceResult.data.id !== id || readinessResult.data.datasourceId !== id) throw new Error('数据源返回范围不一致，请重新读取')
    datasource.value = sourceResult.data; readiness.value = readinessResult.data
    const optional = await Promise.allSettled([
      canReadSnapshots.value ? listSnapshots({ datasourceId: id, page: 1, size: 5 }) : Promise.resolve(null),
      canReadCollections.value ? listSyncTasks({ datasourceId: id, page: 1, size: 5 }) : Promise.resolve(null),
      canReadKnowledge.value ? listKnowledgeDocs({ datasourceId: id, page: 1, pageSize: 3 }) : Promise.resolve(null),
    ])
    if (!current()) return
    const [snapshotResult, taskResult, knowledgeResult] = optional
    if (snapshotResult.status === 'fulfilled' && snapshotResult.value) { snapshotRequestOk.value = true; snapshots.value = snapshotResult.value.data.records || [] }
    if (taskResult.status === 'fulfilled' && taskResult.value) { syncRequestOk.value = true; syncTasks.value = taskResult.value.data.records || [] }
    if (knowledgeResult.status === 'fulfilled' && knowledgeResult.value) { knowledgeRequestOk.value = true; knowledgeDocs.value = knowledgeResult.value.data.records || [] }
    if (readiness.value.publishedSnapshotId && canReadIssues.value) {
      try {
        const result = await listQualityIssues(readiness.value.publishedSnapshotId, { page: 1, size: 5, status: 'OPEN' })
        if (current()) qualityIssues.value = result.data.records || []
      } catch (cause) { if (current()) issuesError.value = getHttpErrorMessage(cause, '治理问题读取失败，请重试') }
    }
  } catch (cause) { if (current()) error.value = getHttpErrorMessage(cause, '数据源详情加载失败，请稍后重试') }
  finally { if (current()) loading.value = false }
}
async function performAction(kind: 'test' | 'enable' | 'collect') {
  if (actionLoading.value || loading.value || error.value || !canView.value) return
  if (kind === 'collect' ? !canCollect.value : !canManage.value) { ElMessage.warning(kind === 'collect' ? '没有执行当前数据源采集的权限。' : MANAGE_HINT); return }
  const id = datasourceId.value, generation = scopeGeneration
  const current = () => alive && generation === scopeGeneration && id === datasourceId.value
  actionLoading.value = true
  try {
    if (kind === 'test') {
      const result = await testSavedDatasourceConnection(id)
      if (!current()) return
      ElMessage[result.data.success ? 'success' : 'error'](result.data.success ? '连接测试成功' : '连接失败：' + result.data.message)
    } else if (kind === 'enable') {
      await updateDatasourceStatus(id, 1); if (!current()) return; ElMessage.success('数据源已启用')
    } else {
      await triggerSync({ datasourceId: id, includeStatistics: false }); if (!current()) return
      ElMessage.success('采集任务已发起')
      if (canReadCollections.value) { await router.push({ path: '/admin/collections', query: { datasourceId: String(id) } }); return }
    }
    if (current()) await load()
  } catch (cause) { if (current()) ElMessage.error(getHttpErrorMessage(cause, '操作失败，请稍后重试')) }
  finally { if (current()) actionLoading.value = false }
}
function runPrimaryAction() {
  if (!primaryActionAllowed.value || actionLoading.value || loading.value) return
  const action = primaryAction.value
  if (action.key === 'test' || action.key === 'enable' || action.key === 'collect') return performAction(action.key)
  if (action.key === 'navigate' && action.to) return router.push(action.to)
  return load()
}
function openSnapshot(id: number) { if (canReadSnapshots.value) void router.push('/admin/releases/snapshots/' + id) }
watch([datasourceId, () => auth.token, () => iamS1.snapshot, () => route.query.action], async () => {
  const generation = ++scopeGeneration; actionLoading.value = false
  await load()
  if (!alive || generation !== scopeGeneration || error.value || route.query.action !== 'test') return
  const key = `${auth.token}:${datasourceId.value}`
  if (autoTestKey === key) return
  autoTestKey = key
  if (canManage.value) await performAction('test')
}, { immediate: true })
</script>

<template>
  <div class="admin-page datasource-cockpit">
    <RouterLink class="datasource-back" to="/admin/data-sources"><ArrowLeft :size="15" aria-hidden="true" />返回数据源</RouterLink>
    <TaskPageHeader :title="datasource?.name || '数据源详情'" description-mode="inline" :description="datasource ? datasource.dbType + ' · ' + datasource.databaseName : '查看准备条件和待处理事项。'">
      <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新状态</el-button><el-button v-if="primaryAction.icon && primaryAction.key !== 'reload' && primaryActionAllowed" type="primary" :icon="primaryAction.icon" :loading="actionLoading" :disabled="loading || !!error" @click="runPrimaryAction">{{ primaryAction.label }}</el-button></template>
    </TaskPageHeader>
    <LoadingState v-if="loading" variant="skeleton" :rows="7" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="readiness">
      <section class="source-outcome" :class="{ ready: readiness.askable }">
        <component :is="readiness.askable ? CheckCircle2 : CircleAlert" :size="22" aria-hidden="true" />
        <div>
          <small class="outcome-kicker">当前准备状态</small>
          <strong>{{ readiness.askable ? '后台判定可问数' : readiness.stageLabel }}</strong>
          <p>{{ readiness.askable ? '当前准备条件已满足；实际使用者仍需独立授权。知识与索引状态见下方。' : (primaryReasonMessage || '查看下方准备条件，处理后再刷新状态。') }}</p>
          <small v-if="readiness.blockReasons[0]?.ownerRole">下一步协作角色：{{ readiness.blockReasons[0].ownerRole }}</small>
          <small v-if="primaryAction.key !== 'reload' && !primaryActionAllowed">当前账号没有此处理入口的权限，请由相应负责人处理。</small>
        </div>
      </section>

      <div class="datasource-cockpit__grid">
        <div class="datasource-cockpit__main">
          <section v-if="remainingActions.length" class="remaining-actions datasource-cockpit__card" aria-label="其他待处理事项">
            <h2>还需处理 {{ remainingActions.length }} 项</h2>
            <article v-for="item in remainingActions" :key="item.reason.code"><div><strong>{{ item.reason.message }}</strong><small>协作角色：{{ item.reason.ownerRole || '未指定' }}</small></div><RouterLink v-if="item.allowed && item.to" :to="item.to">{{ item.reason.actionText || '查看处理入口' }}<ArrowRight :size="14" aria-hidden="true" /></RouterLink><small v-else>请联系相应负责人</small></article>
          </section>
          <DatasourcePreparation :readiness="readiness" />
          <section class="datasource-cockpit__card">
            <div class="section-heading">
              <h2>治理问题</h2>
              <RouterLink v-if="canReadIssues" class="inline-link" :to="{ path: '/admin/governance/issues', query: { datasourceId: String(datasourceId), snapshotId: readiness.publishedSnapshotId ? String(readiness.publishedSnapshotId) : undefined } }">查看问题中心<ArrowRight :size="14" aria-hidden="true" /></RouterLink>
            </div>
            <p v-if="!canReadIssues" class="muted">当前账号没有本数据源的治理问题查看权限。</p>
            <ErrorState v-else-if="issuesError" :message="issuesError" @retry="load" />
            <p v-else-if="!readiness.publishedSnapshotId" class="muted">发布快照后，可在这里查看对应的治理问题。</p>
            <ul v-else-if="qualityIssuePreview.length" class="issue-preview">
              <li v-for="issue in qualityIssuePreview" :key="issue.id">
                <strong>{{ issue.issueDescription }}</strong>
                <small>{{ issue.tableName }}{{ issue.columnName ? '.' + issue.columnName : '' }} · {{ severityLabel(issue.severity) }}</small>
              </li>
            </ul>
            <p v-else class="muted">当前预览没有 OPEN 问题；不代表没有其他状态的阻断项。</p>
          </section>
        </div>

        <aside class="datasource-cockpit__side">
          <section class="datasource-cockpit__card">
            <h2>数据源信息</h2>
            <dl class="source-facts">
              <div><dt>数据源类型</dt><dd>{{ datasource?.dbType }}</dd></div>
              <div><dt>数据库</dt><dd class="source-facts__long" :title="datasource?.databaseName">{{ datasource?.databaseName }}</dd></div>
              <div><dt>连接地址</dt><dd class="source-facts__long" :title="datasourceAddress">{{ datasourceAddress }}</dd></div>
              <div><dt>使用状态</dt><dd><BusinessStatusBadge :status="datasource?.status === 1 ? 'ENABLED' : 'DISABLED'" /></dd></div>
            </dl>
            <div v-if="canManage" class="source-tools"><el-button size="small" :disabled="actionLoading" @click="router.push({ path: '/admin/data-sources', query: { edit: String(datasourceId) } })">编辑连接</el-button><el-button size="small" :loading="actionLoading" @click="performAction('test')">测试已保存连接</el-button></div>
          </section>

          <section class="datasource-cockpit__card">
            <h2>当前发布快照</h2>
            <p v-if="!canReadSnapshots" class="muted">当前账号没有快照详情查看权限。准备路径中的版本信息来自本页准备状态。</p>
            <ErrorState v-else-if="!snapshotRequestOk" message="快照读取失败，无法展示快照详情。" @retry="load" />
            <template v-else-if="readiness.publishedSnapshotId">
              <button type="button" class="compact-list__item compact-list__item--current" @click="openSnapshot(readiness.publishedSnapshotId)">
                <span class="compact-list__copy">
                  <strong>{{ readiness.snapshotVersion ? 'v' + readiness.snapshotVersion : '已发布快照' }}</strong>
                  <small v-if="publishedSnapshot">{{ publishedSnapshot.tableCount }} 张表 · {{ publishedSnapshot.columnCount }} 个字段</small>
                  <small v-else>快照详情暂不可用</small>
                </span>
                <BusinessStatusBadge status="PUBLISHED" label="已发布" />
              </button>
            </template>
            <p v-else class="muted">还没有已发布快照。采集后需审核并发布。</p>
            <details v-if="snapshots.length" class="source-details">
              <summary>查看最近快照</summary>
              <div class="compact-list">
                <button v-for="snapshot in snapshots" :key="snapshot.id" class="compact-list__item" type="button" @click="openSnapshot(snapshot.id)">
                  <span class="compact-list__copy">
                    <strong>v{{ snapshot.snapshotVersion }}</strong>
                    <small>{{ snapshotStatusLabel(snapshot.status) }} · {{ formatCompactTime(snapshot.createdAt) }}</small>
                  </span>
                  <BusinessStatusBadge :status="snapshot.status" :label="snapshotStatusLabel(snapshot.status)" />
                </button>
              </div>
            </details>
          </section>

          <section class="datasource-cockpit__card">
            <h2>语义知识</h2>
            <p v-if="!canReadKnowledge" class="muted">当前账号没有本数据源的知识文档查看权限。</p>
            <ErrorState v-else-if="!knowledgeRequestOk" message="知识文档读取失败，请重试。" @retry="load" />
            <template v-else-if="knowledgePreview.length">
              <div class="compact-list">
                <RouterLink v-for="doc in knowledgePreview" :key="doc.id" class="compact-list__item compact-list__item--knowledge" :to="'/admin/semantics/knowledge/' + doc.id">
                  <span class="compact-list__copy">
                    <strong :title="doc.title">{{ doc.title }}</strong>
                    <small>v{{ doc.currentVersion }} · {{ formatCompactTime(doc.updatedAt) }}</small>
                  </span>
                  <BusinessStatusBadge :status="doc.status" :label="knowledgeStatusLabel(doc.status)" />
                  <ArrowRight :size="14" aria-hidden="true" />
                </RouterLink>
              </div>
              <RouterLink class="compact-list__more" :to="knowledgeWorkspaceLink">查看全部<ArrowRight :size="14" aria-hidden="true" /></RouterLink>
            </template>
            <p v-else class="muted">还没有知识文档。通过准备清单进入语义知识工作区。</p>
          </section>

          <details class="datasource-cockpit__card source-details activity-details">
            <summary>最近采集与快照活动</summary>
            <p v-if="!canReadCollections" class="muted">当前账号没有采集记录查看权限。</p>
            <ErrorState v-else-if="!syncRequestOk" message="采集记录读取失败，请重试。" @retry="load" />
            <ActivityTimeline v-if="activityItems.length" :items="activityItems" />
            <p v-else-if="syncRequestOk" class="muted">当前没有活动记录。</p>
          </details>
        </aside>
      </div>
    </template>
  </div>
</template>
<style scoped>
.datasource-cockpit :deep(.task-page-header) { align-items: center; margin-bottom: 14px; }
.datasource-cockpit :deep(.task-page-header__copy) { flex: 1 1 auto; min-width: 0; }
.datasource-cockpit :deep(.task-page-header h1) { font-size: clamp(28px, 2.4vw, 32px); line-height: 1.2; letter-spacing: -.025em; }
.datasource-cockpit :deep(.task-page-header p) { margin-top: 4px; font-size: 13px; line-height: 1.5; }
.datasource-cockpit :deep(.task-page-header__actions) { flex: 0 1 auto; max-width: 48%; justify-content: flex-end; }
.datasource-cockpit :deep(.task-page-header__actions .el-button) { height: auto; min-height: 36px; padding-block: 6px; line-height: 1.35; white-space: normal; text-align: center; }

.datasource-back { display: inline-flex; align-items: center; gap: 6px; margin-bottom: 10px; color: var(--do-primary-strong); font-size: 12.5px; }
.source-outcome { display: flex; align-items: flex-start; gap: 14px; margin-bottom: 24px; padding: 20px 22px; border: 1px solid var(--do-line); border-left: 3px solid var(--do-warning); border-radius: 10px; color: var(--do-warning); background: var(--do-surface); }
.source-outcome.ready { border-left-color: var(--do-success); color: var(--do-success); background: var(--do-surface); }
.source-outcome small { display: block; margin-top: 6px; color: var(--do-muted); font-size: 11px; line-height: 1.6; }
.source-outcome .outcome-kicker { margin: 0 0 6px; font-size: 11px; }
.source-outcome > svg { flex: 0 0 auto; }
.source-outcome strong { color: var(--do-ink); font-size: 15px; line-height: 1.4; }
.source-outcome p { margin: 3px 0 0; color: var(--do-muted); font-size: 12.5px; line-height: 1.55; overflow-wrap: anywhere; }

.datasource-cockpit__grid { display: grid; grid-template-columns: minmax(0, 1fr) 300px; align-items: start; gap: 24px; }
.datasource-cockpit__main { display: grid; align-content: start; gap: 16px; min-width: 0; }
.datasource-cockpit__side { display: grid; align-content: start; gap: 14px; min-width: 0; }
.datasource-cockpit__card { min-width: 0; padding: 20px; border: 1px solid var(--do-line); border-radius: 12px; background: var(--do-surface); box-shadow: none; }
.source-tools { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 18px; }
.source-tools .el-button + .el-button { margin-left: 0; }
.remaining-actions article { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; padding-block: 12px; border-top: 1px solid var(--do-line); }
.remaining-actions article > div { min-width: 0; display: grid; gap: 5px; }
.remaining-actions strong { color: var(--do-ink); font-size: 13px; font-weight: 500; }
.remaining-actions small { color: var(--do-muted); font-size: 11px; }
.remaining-actions a { display: inline-flex; align-items: center; gap: 5px; min-height: 36px; color: var(--do-primary-strong); font-size: 12px; }
.datasource-cockpit__card h2 { margin: 0 0 12px; color: var(--do-ink); font-size: 15px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.section-heading h2 { margin: 0; }

.source-facts { display: grid; gap: 10px; margin: 0; }
.source-facts div { display: grid; grid-template-columns: 84px minmax(0, 1fr); align-items: start; gap: 10px; font-size: 12.5px; }
dt { color: var(--do-muted); line-height: 1.5; }
dd { margin: 0; color: var(--do-ink); line-height: 1.5; overflow-wrap: anywhere; }
.source-facts__long { word-break: break-word; }
.muted { margin: 6px 0 0; color: var(--do-muted); font-size: 12.5px; line-height: 1.6; overflow-wrap: anywhere; }
.section-heading + .muted { margin-top: 0; }

.compact-list { display: grid; }
.compact-list__item { min-width: 0; display: flex; align-items: center; justify-content: space-between; gap: 10px; width: 100%; padding: 10px 0; border: 0; border-bottom: 1px solid var(--do-line); color: var(--do-ink); background: transparent; font: inherit; text-align: left; text-decoration: none; cursor: pointer; }
.compact-list__item:last-child { border-bottom: 0; }
.compact-list__item:hover { color: var(--do-primary-strong); }
.compact-list__copy { min-width: 0; display: grid; gap: 3px; }
.compact-list__copy strong { color: inherit; font-size: 13px; line-height: 1.45; overflow-wrap: anywhere; }
.compact-list__copy small { color: var(--do-muted); font-size: 11.5px; line-height: 1.45; }
.compact-list__item--current .compact-list__copy strong { font-size: 16px; }
.compact-list__item--knowledge { display: grid; grid-template-columns: minmax(0, 1fr) auto 14px; }
.compact-list__item--knowledge > svg { flex: 0 0 auto; color: var(--do-muted); }
.compact-list__more { display: inline-flex; align-items: center; gap: 4px; margin-top: 8px; color: var(--do-primary-strong); font-size: 12.5px; }

.issue-preview { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; }
.issue-preview li { min-width: 0; display: grid; gap: 2px; padding: 8px 0; border-bottom: 1px solid var(--do-line); }
.issue-preview li:last-child { border-bottom: 0; }
.issue-preview strong { color: var(--do-ink); font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.issue-preview small { color: var(--do-muted); font-size: 11.5px; line-height: 1.45; overflow-wrap: anywhere; }

.source-details summary { padding: 0; color: var(--do-muted); font-size: 12.5px; cursor: pointer; }
.source-details[open] summary { margin-bottom: 10px; color: var(--do-ink); }
.activity-details summary { color: var(--do-muted); font-size: 13px; font-weight: 600; }
.activity-details[open] summary { margin-bottom: 12px; }
.inline-link { display: inline-flex; align-items: center; gap: 5px; color: var(--do-primary-strong); font-size: 12.5px; white-space: nowrap; }

@media (max-width: 1150px) {
  .datasource-cockpit__grid { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 900px) {
  .datasource-cockpit :deep(.task-page-header__actions) { max-width: none; justify-content: flex-start; }
}
</style>
