<script setup lang="ts">
import { computed } from 'vue'
import { ArrowUpRight, CircleHelp } from 'lucide-vue-next'
import type { QualityIssueSummary, QualityCheckBatch, QualityIssueItem } from '../../api/admin/governance'
import { overviewSummary,groupOverviewTables,overviewCoverage,overviewStatuses,overviewStatusNames,type OverviewStatus } from '../../utils/governanceOverview'
const props=defineProps<{summary:QualityIssueSummary|null;rows:QualityIssueItem[];complete:boolean;aggregateLoading:boolean;message:string;issueLoading:boolean;issueError:string;canViewIssues:boolean;batch:QualityCheckBatch|null;batchLoading:boolean;batchError:string;batchState:string;batchDescription:string;batchScope:string;batchLabel?:string}>()
const emit=defineEmits<{plot:[value:{status?:OverviewStatus;tableName?:string;issueDomain?:'SOURCE'|'SEMANTIC'}];help:[];coverage:[];retry:[];cancel:[]}>()
const model=computed(()=>overviewSummary(props.summary))
const groups=computed(()=>props.complete?groupOverviewTables(props.rows):[]),bars=computed(()=>groups.value.slice(0,8))
const axisMax=computed(()=>Math.max(2,Math.ceil(Math.max(0,...groups.value.map(g=>g.count))/2)*2))
const remainder=computed(()=>groups.value.slice(8).reduce((n,g)=>n+g.count,0))
const coverage=computed(()=>overviewCoverage(props.batch))
const coverageNames={SUCCESS:'执行成功',FAILURE:'失败',NOT_APPLICABLE:'不适用',NOT_EXECUTED:'未执行'} as const
const issueAvailable=computed(()=>props.canViewIssues&&!props.issueError&&!props.issueLoading&&model.value.total!=null)
const domainFraction=computed(()=>model.value.total&&model.value.domains?model.value.domains.SOURCE/model.value.total:0)
</script>
<template>
 <div class="overview-analysis" data-overview-charts>
  <div class="analysis-row">
   <figure class="main-figure"><figcaption class="figure-head"><h2>{{ complete?'未解决问题集中度':'当前处理结构' }}</h2><span>{{ model.total==null?'当前数量未知':`当前快照 ${model.total} 条 · 单位：条` }}</span></figcaption>
    <p v-if="!canViewIssues" class="emptyplot">没有此数据源的「查看质量问题」权限；无权不表示问题为0。</p>
    <p v-else-if="issueLoading" class="emptyplot" role="status">正在读取问题统计…</p>
    <p v-else-if="issueError" class="emptyplot" role="alert">{{ issueError }}</p>
    <p v-else-if="model.total===0" class="emptyplot"><strong>暂无待处理问题</strong>已读取范围确认0条；不代表来源质量或业务口径全部通过。</p>
    <p v-else-if="model.total==null" class="emptyplot">问题总数未确认，不推断为空。</p>
    <template v-else>
     <div class="status-legend" role="group" aria-label="按单个状态查看问题"><button v-for="status in overviewStatuses" :key="status" :disabled="!model.statuses" :aria-label="`查看${overviewStatusNames[status]}问题`" @click="emit('plot',{status})"><i :class="status" aria-hidden="true" />{{ overviewStatusNames[status] }} <b>{{ model.statuses?.[status] ?? '—' }}</b><ArrowUpRight :size="12" /></button><button class="status-help" aria-label="查看处理状态说明" title="此前已结束处理，现重新进入待确认流程；可能由人工重新开启，也可能由语义复查触发。" @click="emit('help')">状态说明 <CircleHelp :size="12" /></button></div>
     <template v-if="complete">
      <div class="table-bars" role="group" aria-label="按表和处理状态查看问题"><div v-for="group in bars" :key="group.name??'__unlocated__'" class="stack-row"><span class="tname" :title="group.name??'未定位表名'">{{ group.name??'未定位表名' }}</span><div class="bar-track"><template v-for="status in overviewStatuses" :key="status"><button v-if="group.statuses[status] && group.name!==null" class="bar-segment" :class="status" :style="{width:group.statuses[status]/axisMax*100+'%'}" :aria-label="`${group.name} ${overviewStatusNames[status]} ${group.statuses[status]}条，前往问题中心`" @click="emit('plot',{tableName:group.name,status})">{{ group.statuses[status]/axisMax>=.04 ? group.statuses[status] : '' }}</button><span v-else-if="group.statuses[status]" class="bar-segment" :class="status" :style="{width:group.statuses[status]/axisMax*100+'%'}" :aria-label="`未定位表名，${overviewStatusNames[status]}${group.statuses[status]}条`">{{ group.statuses[status]/axisMax>=.04?group.statuses[status]:'' }}</span></template></div><button v-if="group.name!==null" class="total-jump" :aria-label="`${group.name}共${group.count}条，选择状态`" @click="emit('plot',{tableName:group.name})">{{ group.count }}</button><span v-else class="total-jump">{{ group.count }}</span></div></div>
      <div class="axis" aria-hidden="true"><span/><div><span>0</span><span>{{ axisMax/2 }}</span><span>{{ axisMax }} 条</span></div><span/></div>
      <p class="figure-note">按本轮完整分页核对，非事务快照；{{ groups.length>8?`仅示前8组，另有${remainder}条未绘。`:'读取期间可能变化。' }}未定位表名不伪造成表筛选。</p>
     </template>
     <template v-else><div v-if="model.statuses" class="status-fallback"><button v-for="status in overviewStatuses" :key="status" class="fallback-row" @click="emit('plot',{status})"><span>{{ overviewStatusNames[status] }}</span><span class="track" aria-hidden="true"><i :class="status" :style="{width:model.statuses[status]/Math.max(1,...Object.values(model.statuses))*100+'%'}"/></span><b>{{ model.statuses[status] }}</b></button></div><p class="figure-note" :role="message?'status':undefined">{{ aggregateLoading?'正在取得完整明细，先展示权威状态摘要。':message || '表聚合未确认，不从最近几条问题推算总体。' }}</p><button v-if="aggregateLoading" class="evidence-link" @click="emit('cancel')">停止表聚合</button></template>
    </template>
   </figure>
   <figure class="domain-figure"><figcaption class="figure-head"><h2>问题来源构成</h2><span>SOURCE / SEMANTIC</span></figcaption><p v-if="!issueAvailable" class="emptyplot">{{ !canViewIssues?'无权读取问题分域，不按0显示。':issueLoading?'正在读取分域…':'分域统计不可用，不沿用旧比例。' }}</p><p v-else-if="model.total===0" class="emptyplot">已读取问题为0，不绘制空饼冒充健康评分。</p><p v-else-if="!model.domains" class="emptyplot">分域计数未核实；缺字段或合计不符时不推算。</p><template v-else><div class="domain-body"><svg class="donut" viewBox="0 0 128 128" role="img" :aria-label="`未解决${model.total}条，来源问题${model.domains.SOURCE}条，语义问题${model.domains.SEMANTIC}条`"><circle cx="64" cy="64" r="45" fill="none" stroke="var(--do-chart-2)" stroke-width="14"/><circle cx="64" cy="64" r="45" fill="none" stroke="var(--do-chart-1)" stroke-width="14" :stroke-dasharray="`${domainFraction*Math.PI*90} ${Math.PI*90}`" transform="rotate(-90 64 64)"/><text class="total" x="64" y="65" text-anchor="middle">{{ model.total }}</text><text class="label" x="64" y="85" text-anchor="middle">未解决问题</text></svg><div class="domain-labels"><button v-for="domain in (['SOURCE','SEMANTIC'] as const)" :key="domain" class="domain-jump" :aria-label="`${domain==='SOURCE'?'来源问题':'语义问题'}${model.domains[domain]}条，选择状态`" @click="emit('plot',{issueDomain:domain})"><span><i :class="domain" aria-hidden="true"/>{{ domain==='SOURCE'?'来源问题':'语义问题' }}</span><b>{{ model.domains[domain] }}</b></button></div></div><p class="figure-note">同一未解决集合的互斥分域，不是质量分或完成率。先选状态，再进入问题中心。</p></template></figure>
  </div>
  <figure class="coverage-figure"><figcaption class="coverage-title"><h2>来源校验执行覆盖 <span>{{ batchState }}</span></h2><span v-if="batch">{{ batchLabel || '最近批次' }} #{{ batch.id }} · {{ batchScope }}{{ coverage?` · ${coverage.total}条规则`:'' }}</span></figcaption><p v-if="batchLoading" class="emptyplot" role="status">正在读取校验状态…</p><p v-else-if="batchError" class="emptyplot" role="alert">{{ batchError }}</p><template v-else><p v-if="!batch || !coverage" class="emptyplot">{{ batchDescription }}{{ batch && !coverage ? '规则覆盖明细或分母未完整核实，不推算比例。':'' }}</p><div v-else class="coverage-bar" role="img" :aria-label="`本批次${coverage.total}条规则，${Object.entries(coverage.counts).map(([k,n])=>coverageNames[k as keyof typeof coverageNames]+n).join('、')}；执行成功不等于质量通过`"><div v-for="[status,n] in Object.entries(coverage.counts).filter(([,n])=>n>0)" :key="status" class="coverage-part" :class="status" :style="{width:n/coverage.total*100+'%'}">{{ coverageNames[status as keyof typeof coverageNames] }} <b>{{ n }}</b></div></div><p v-if="batch?.errorSummary" role="alert" class="batch-error">{{ batch.errorSummary }}</p><div class="coverage-foot"><span>分母为该批次规则数，非字段数量；执行成功不等于质量无问题。</span><span>{{ batch ? batch.complete ? '本批次完整性仍按范围核对' : 'complete=false · 不展示本次完整评分' : '无批次不推断历史从未执行' }}</span></div></template><button class="evidence-link" @click="emit('coverage')">查看执行依据 <ArrowUpRight :size="13" /></button></figure>
 </div>
</template>
<style scoped>
/* 分域使用分类色，处理状态与执行覆盖各自采用状态色。 */
.overview-analysis {
  overflow: hidden;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
}

.analysis-row {
  display: grid;
  grid-template-columns: 1.42fr 1fr;
}

.main-figure,
.domain-figure {
  min-width: 0;
  margin: 0;
  padding: 22px 24px;
}

.domain-figure {
  border-left: 1px solid var(--do-line);
}

/* 标题与统计口径保持可读，较长的范围文字允许换行。 */
.figure-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px 16px;
  margin-bottom: 16px;
}

.figure-head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.5;
}

.figure-head > span {
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
}

button {
  font: inherit;
  cursor: pointer;
}

button:focus-visible {
  outline: 3px solid var(--do-primary);
  outline-offset: 3px;
}

button:disabled {
  color: var(--do-muted);
  cursor: not-allowed;
}

/* 三种未解决状态保持独立：待处理为警示、已确认为信息、重新处理为风险。 */
.OPEN {
  background: var(--do-warning);
}

.CONFIRMED {
  background: var(--do-info);
}

.REOPENED {
  background: var(--do-danger);
}

.status-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  margin-bottom: 16px;
}

.status-legend button {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 32px;
  padding: 3px 0;
  border: 0;
  color: var(--do-muted);
  background: none;
  font-size: 12px;
  line-height: 1.5;
}

.status-legend i,
.domain-jump i {
  display: inline-block;
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 2px;
}

.status-legend b {
  color: var(--do-ink);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.status-help {
  margin-left: auto;
}

/* 表名、条段与坐标共用列宽，放大文字后仍保持对应关系。 */
.table-bars {
  display: grid;
  gap: 12px;
}

.stack-row,
.axis {
  display: grid;
  grid-template-columns: minmax(104px, .28fr) minmax(0, 1fr) 32px;
  gap: 12px;
}

.stack-row {
  align-items: center;
  min-height: 32px;
}

.tname {
  overflow: hidden;
  font: 12px/1.5 ui-monospace, Consolas, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar-track {
  display: flex;
  gap: 1px;
  height: 24px;
  overflow: hidden;
  border-radius: 4px;
  background: var(--do-soft);
}

.bar-segment {
  min-width: 0;
  padding: 0;
  border: 0;
  color: var(--do-on-primary);
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 24px;
  text-align: center;
}

.bar-segment:focus-visible {
  outline: 2px solid var(--do-surface);
  outline-offset: -3px;
}

.total-jump {
  min-height: 32px;
  padding: 3px 0;
  border: 0;
  color: var(--do-ink);
  background: none;
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.axis {
  margin-top: 8px;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.5;
}

.axis div {
  display: flex;
  justify-content: space-between;
}

.figure-note {
  margin: 14px 0 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.75;
}

/* 两个数据域使用序列前两种不同色相，环图和图例同时保留名称与数值。 */
.domain-body {
  display: flex;
  align-items: center;
  gap: 20px;
  min-width: 0;
  margin: 8px 0 14px;
}

.donut {
  width: 136px;
  height: 136px;
  flex-shrink: 0;
}

.donut text {
  font-family: inherit;
  fill: var(--do-ink);
}

.donut .total {
  font-size: 29px;
  font-weight: 600;
}

.donut .label {
  font-size: 12px;
  fill: var(--do-muted);
}

.domain-labels {
  display: grid;
  flex: 1;
  gap: 10px;
  min-width: 0;
}

.domain-jump {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-height: 36px;
  padding: 5px 0;
  border: 0;
  color: var(--do-ink);
  background: none;
  font-size: 13px;
  text-align: left;
}

.domain-jump span {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.domain-jump b {
  font-size: 22px;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
}

.domain-jump .SOURCE {
  background: var(--do-chart-1);
}

.domain-jump .SEMANTIC {
  background: var(--do-chart-2);
}

/* 不完整明细和无权状态保留原文，状态摘要仍使用相同语义色。 */
.emptyplot {
  padding: 22px 0;
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.8;
}

.emptyplot strong {
  display: block;
  margin-bottom: 6px;
  color: var(--do-ink);
  font-size: 15px;
  font-weight: 550;
}

.status-fallback {
  display: grid;
  gap: 14px;
}

.fallback-row {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr) 32px;
  align-items: center;
  gap: 12px;
  min-height: 32px;
  padding: 3px 0;
  border: 0;
  color: var(--do-ink);
  background: none;
  font-size: 12px;
  text-align: left;
}

.track {
  height: 16px;
  overflow: hidden;
  border-radius: 3px;
  background: var(--do-soft);
}

.track i {
  display: block;
  height: 100%;
}

/* 执行覆盖按四种回执状态着色，完成文字保持中性，不将完整执行等同质量通过。 */
.coverage-figure {
  margin: 0;
  padding: 20px 24px;
  border-top: 1px solid var(--do-line);
  background: var(--do-soft);
}

.coverage-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 20px;
  margin-bottom: 14px;
}

.coverage-title h2 {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.6;
}

.coverage-title h2 span {
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 400;
}

.coverage-title > span {
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
}

.coverage-bar {
  display: flex;
  gap: 2px;
  height: 36px;
  overflow: hidden;
  border-radius: 5px;
}

.coverage-part {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-width: 0;
  font-size: 12px;
}

.coverage-part b {
  font-size: 14px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.coverage-part.SUCCESS {
  color: var(--do-success);
  background: var(--do-success-soft);
}

.coverage-part.FAILURE {
  color: var(--do-danger);
  background: var(--do-danger-soft);
}

.coverage-part.NOT_APPLICABLE {
  color: var(--do-muted);
  background: var(--do-surface);
}

.coverage-part.NOT_EXECUTED {
  color: var(--do-warning);
  background: var(--do-warning-soft);
}

/* 分母说明与执行依据保持完整换行，链接使用全站动作色并保留焦点。 */
.coverage-foot {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px 20px;
  margin-top: 12px;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.75;
}

.evidence-link {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 32px;
  margin-top: 7px;
  padding: 5px 0;
  border: 0;
  color: var(--do-primary-strong);
  background: none;
  font-size: 12px;
}

.batch-error {
  color: var(--do-danger);
  font-size: 13px;
  line-height: 1.75;
}

/* 桌面缩放压缩可用宽度时纵向排列，保留完整坐标与操作区域。 */
@media (max-width: 1100px) {
  .analysis-row {
    grid-template-columns: minmax(0, 1fr);
  }

  .domain-figure {
    border-top: 1px solid var(--do-line);
    border-left: 0;
  }
}
</style>
