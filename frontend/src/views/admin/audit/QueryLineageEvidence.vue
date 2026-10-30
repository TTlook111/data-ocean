<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  analyzeImpact,
  queryColumnLineage,
  queryTableLineage,
  type ImpactAnalysisVO,
  type LineageColumnVO,
  type LineageTableVO,
} from '../../../api/admin/audit'
import { useAuthStore } from '../../../stores/auth'
import { useIamS1Store } from '../../../stores/iamS1'
import { positiveId, queryEvidenceKind } from '../../../utils/lineageContext'
import { getHttpErrorMessage } from '../../../utils/httpError'
import ErrorState from '../../../components/common/ErrorState.vue'
const props = defineProps<{ datasourceId?: number | null; table?: string; column?: string }>()
const auth = useAuthStore(),
  iam = useIamS1Store()
const tableInput = ref(props.table || ''),
  columnInput = ref(props.column || '')
const target = ref<{ source: number; table: string; column?: string } | null>(null)
const impact = ref<ImpactAnalysisVO | null>(null),
  rows = ref<Array<LineageTableVO | LineageColumnVO>>([])
const impactError = ref(''),
  rowsError = ref(''),
  loading = ref(false)
let generation = 0,
  alive = true
const allowed = computed(
  () =>
    iam.loaded &&
    !iam.errorMessage &&
    !!positiveId(props.datasourceId) &&
    iam.canOnDatasource('lineage:view', props.datasourceId!),
)
function clear() {
  generation++
  impact.value = null
  rows.value = []
  impactError.value = ''
  rowsError.value = ''
  loading.value = false
  target.value = null
}
async function load() {
  if (!allowed.value || loading.value || !tableInput.value.trim()) return
  const captured = {
    source: props.datasourceId!,
    table: tableInput.value.trim(),
    column: columnInput.value.trim() || undefined,
  }
  const token = auth.token,
    snapshot = iam.snapshot,
    ticket = ++generation
  target.value = captured
  loading.value = true
  impact.value = null
  rows.value = []
  impactError.value = ''
  rowsError.value = ''
  const current = () =>
    alive &&
    ticket === generation &&
    token === auth.token &&
    snapshot === iam.snapshot &&
    allowed.value &&
    captured.source === props.datasourceId
  // Independent read failures remain visible; a failed summary does not erase successful detail evidence.
  await Promise.all([
    analyzeImpact(captured.source, captured.table, captured.column)
      .then((r) => {
        if (current()) impact.value = r.data
      })
      .catch((e) => {
        if (current()) impactError.value = getHttpErrorMessage(e, '影响范围读取失败')
      }),
    (captured.column
      ? queryColumnLineage(captured.source, captured.table, captured.column)
      : queryTableLineage(captured.source, captured.table)
    )
      .then((r) => {
        if (current()) rows.value = r.data || []
      })
      .catch((e) => {
        if (current()) rowsError.value = getHttpErrorMessage(e, '查询来源明细读取失败')
      }),
  ])
  if (current()) loading.value = false
}
watch(
  [
    () => props.datasourceId,
    () => props.table,
    () => props.column,
    () => auth.token,
    () => iam.snapshot,
    allowed,
  ],
  () => {
    clear()
    tableInput.value = props.table || ''
    columnInput.value = props.column || ''
    if (props.table && allowed.value) void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  alive = false
  clear()
})
defineExpose({ load })
</script>
<template>
  <section class="query-lineage-evidence">
    <div class="evidence-intro">
      <span class="eyebrow">实际查询证据</span>
      <h2>谁使用了这个对象</h2>
      <p>
        这里只统计已完成追溯投影的成功受保护查询。表的查询使用与结果字段派生独立于关系图；不会从同时出现的表推断
        JOIN 或加工链路。
      </p>
    </div>
    <p v-if="!allowed" role="status">没有当前数据源的 lineage:view 能力，无法读取查询来源。</p>
    <div v-else class="evidence-body">
      <form class="evidence-form" @submit.prevent="load">
        <label>
          精确表名
          <el-input v-model="tableInput" placeholder="例如 orders" :disabled="loading" />
        </label>
        <label>
          字段名（可选）
          <el-input v-model="columnInput" placeholder="留空查看表级使用" :disabled="loading" />
        </label>
        <el-button native-type="submit" type="primary" :loading="loading" :disabled="!tableInput.trim()">
          读取查询证据
        </el-button>
      </form>
      <p class="evidence-note">数据源 #{{ datasourceId }} · 历史快照合并统计 · 接口不支持时间或快照筛选</p>
      <div v-if="loading" role="status" class="evidence-note">正在读取当前对象的影响范围与明细…</div>
      <section v-if="target" class="target-evidence">
        <p class="target-caption">
          当前证据：#{{ target.source }} / {{ target.table }}{{ target.column ? '.' + target.column : '' }}
        </p>
        <ErrorState v-if="impactError" :message="impactError" @retry="load" />
        <div v-if="impact" class="evidence-metrics">
          <div>
            <strong>{{ impact.dependentQueryCount }}</strong>
            <span>去重依赖查询数</span>
          </div>
          <div>
            <strong>{{ impact.pendingDatasourceQueryCount ?? '未提供' }}</strong>
            <span>整个数据源待投影查询</span>
          </div>
          <div class="coverage">
            <strong>{{ impact.coverageStartAt || '未提供' }}</strong>
            <span>整个数据源最早已投影记录</span>
          </div>
        </div>
        <p v-if="impact" class="coverage-note" role="status">
          {{ impact.coverageNote || '只覆盖已记录的受保护查询；未记录不代表没有影响。' }}
          执行成功不等于追溯投影完成；此处没有重跑 SQL 或修复投影的操作。
        </p>
        <div v-if="impact?.recentQueryTaskIds?.length" class="recent-tasks">
          <span>近期任务（最多 10 个，内部数字记录 ID）</span>
          <code v-for="id in impact.recentQueryTaskIds" :key="id">#{{ id }}</code>
        </div>
        <ErrorState v-if="rowsError" :message="rowsError" @retry="load" />
        <el-table v-else-if="rows.length" :data="rows">
          <el-table-column prop="queryTaskId" label="内部任务 ID" width="130" />
          <el-table-column prop="question" label="查询问题" min-width="220" show-overflow-tooltip />
          <el-table-column label="证据类型" width="150">
            <template #default="{ row }">{{ queryEvidenceKind(row) }}</template>
          </el-table-column>
          <el-table-column label="来源 → 输出" min-width="200">
            <template #default="{ row }">
              <code>{{ row.sourceTable }}{{ row.sourceColumn ? '.' + row.sourceColumn : '' }}</code>
              <span v-if="row.aliasName">→ {{ row.aliasName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="记录时间" min-width="180" />
        </el-table>
        <p v-else-if="!loading && !rowsError" class="empty-evidence">
          当前对象没有已记录的查询明细。0 条记录不能证明无人使用；列表上限 100 行，总数单独去重统计。
        </p>
        <details v-if="target.column && rows.length">
          <summary>查看字段派生表达式</summary>
          <p>表达式来自已记录的字段派生，不是受保护 SQL 读取入口。</p>
          <pre v-for="(row, i) in rows" :key="i">{{
            'expression' in row ? row.expression || '服务端未提供表达式' : ''
          }}</pre>
        </details>
      </section>
    </div>
  </section>
</template>
<style scoped>
.query-lineage-evidence {
  padding: 24px;
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: 14px;
  min-width: 0;
}
.eyebrow {
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
  color: var(--do-primary-strong);
}
h2 {
  margin: 8px 0;
  font-size: 22px;
}
.evidence-intro p,
.evidence-note,
.coverage-note,
.empty-evidence {
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.8;
}
.evidence-intro p {
  max-width: 850px;
}
.evidence-form {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 12px;
  margin-top: 24px;
}
.evidence-form label {
  display: grid;
  gap: 8px;
  min-width: 180px;
  font-size: 12px;
  font-weight: 700;
}
.target-caption {
  font-size: 13px;
  font-weight: 700;
  overflow-wrap: anywhere;
}
.evidence-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr 2fr;
  border-block: 1px solid var(--do-line);
  margin: 20px 0;
  padding: 20px 0;
  gap: 20px;
}
.evidence-metrics div {
  display: grid;
  gap: 8px;
}
.evidence-metrics strong {
  font-size: 26px;
}
.evidence-metrics .coverage strong {
  font-size: 15px;
}
.evidence-metrics span,
.recent-tasks {
  color: var(--do-muted);
  font-size: 12px;
}
.recent-tasks {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 18px 0;
}
code {
  color: var(--do-ink);
}
details {
  font-size: 13px;
  margin-top: 16px;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
@media (max-width: 700px) {
  .evidence-metrics {
    grid-template-columns: 1fr;
  }
  .query-lineage-evidence {
    padding: 16px;
  }
  .evidence-form label {
    width: 100%;
  }
}
</style>
