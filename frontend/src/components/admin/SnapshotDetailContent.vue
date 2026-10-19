<script setup lang="ts">
import { computed } from 'vue'
import { Key, Table2 } from 'lucide-vue-next'
import type { SnapshotDetail, TableMetaItem, ColumnMetaItem } from '../../api/admin/metadata'
import BusinessStatusBadge from './BusinessStatusBadge.vue'
import EmptyState from '../common/EmptyState.vue'
import { governanceStatusLabel } from '../../utils/enumLabels'

const props = withDefaults(defineProps<{
  detail: SnapshotDetail
  showDatasource?: boolean
}>(), { showDatasource: true })

const datasourceLabel = computed(() =>
  props.detail.datasourceName?.trim()
    || props.detail.snapshot.datasourceName?.trim()
    || `数据源 ID ${props.detail.snapshot.datasourceId}`,
)

function columnsForTable(table: TableMetaItem): ColumnMetaItem[] {
  return props.detail.columns
    .filter((column) => column.tableMetaId === table.id)
    .sort((a, b) => a.ordinalPosition - b.ordinalPosition)
}

function formatDateTime(value?: string | null) {
  if (!value) return '未提供'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ')
  const parts = new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date)
  const part = (type: string) => parts.find((item) => item.type === type)?.value || ''
  return `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}`
}

function formatCount(value?: number | null) {
  return value == null ? '未采集统计' : value.toLocaleString('zh-CN')
}

function formatEstimatedRows(value?: number | null) {
  return value == null ? '未采集统计' : `约 ${formatCount(value)} 行`
}

function formatNullRate(value?: number | null) {
  return value == null ? '未采集统计' : `${(Number(value) * 100).toFixed(1)}%`
}

function nullableLabel(value: number | boolean) {
  return value ? '允许为空' : '不能为空'
}
</script>

<template>
  <div class="snapshot-detail-content">
    <div v-if="showDatasource" class="snapshot-detail-content__source" aria-label="来源数据源">
      <span>数据源</span>
      <strong :title="datasourceLabel" tabindex="0">{{ datasourceLabel }}</strong>
    </div>

    <section class="snapshot-detail-content__summary" aria-label="快照摘要">
      <div class="snapshot-detail-content__summary-item">
        <span>采集时间</span>
        <time
          :datetime="detail.snapshot.createdAt"
          :title="detail.snapshot.createdAt || undefined"
          :tabindex="detail.snapshot.createdAt ? 0 : undefined"
        >{{ formatDateTime(detail.snapshot.createdAt) }}</time>
      </div>
      <div class="snapshot-detail-content__summary-item">
        <span>表数</span>
        <strong>{{ formatCount(detail.snapshot.tableCount) }}</strong>
      </div>
      <div class="snapshot-detail-content__summary-item">
        <span>字段数</span>
        <strong>{{ formatCount(detail.snapshot.columnCount) }}</strong>
      </div>
      <div class="snapshot-detail-content__summary-item">
        <span>质量分</span>
        <strong>{{ detail.snapshot.qualityScore == null ? '未校验' : `${detail.snapshot.qualityScore} 分` }}</strong>
      </div>
    </section>

    <section class="snapshot-detail-content__tables" aria-label="快照表清单">
      <div class="snapshot-detail-content__heading">
        <h3><Table2 :size="17" aria-hidden="true" />表清单</h3>
      </div>
      <div class="snapshot-detail-content__table-region">
        <el-table v-if="detail.tables.length" :data="detail.tables" class="snapshot-detail-content__table" size="small" row-key="id">
          <el-table-column type="expand" label="字段" width="48">
            <template #default="{ row }">
              <div class="snapshot-detail-content__expanded-fields">
                <el-table v-if="columnsForTable(row).length" :data="columnsForTable(row)" class="snapshot-detail-content__field-table" size="small" row-key="id">
                  <el-table-column label="字段名" min-width="170">
                    <template #default="{ row: column }">
                      <span class="snapshot-detail-content__field-name">
                        <Key v-if="column.isPrimaryKey" :size="12" aria-label="主键" role="img" />
                        <code :title="column.columnName">{{ column.columnName }}</code>
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="dataType" label="类型" width="130">
                    <template #default="{ row: column }"><code class="snapshot-detail-content__field-type" :title="column.dataType">{{ column.dataType }}</code></template>
                  </el-table-column>
                  <el-table-column label="可空性" width="100">
                    <template #default="{ row: column }">{{ nullableLabel(column.isNullable) }}</template>
                  </el-table-column>
                  <el-table-column prop="columnComment" label="字段说明" min-width="210">
                    <template #default="{ row: column }"><span class="snapshot-detail-content__field-comment" :title="column.columnComment || undefined" :tabindex="column.columnComment ? 0 : undefined">{{ column.columnComment }}</span></template>
                  </el-table-column>
                  <el-table-column label="治理状态" width="116">
                    <template #default="{ row: column }"><BusinessStatusBadge :status="column.governanceStatus" :label="governanceStatusLabel(column.governanceStatus)" /></template>
                  </el-table-column>
                  <el-table-column label="空值率" width="110" align="right">
                    <template #default="{ row: column }"><span :class="{ 'snapshot-detail-content__uncollected': column.nullRate == null }">{{ formatNullRate(column.nullRate) }}</span></template>
                  </el-table-column>
                </el-table>
                <EmptyState v-else message="该快照未返回此表的字段结构" />
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="tableName" label="技术表名" min-width="210">
            <template #default="{ row }"><code class="snapshot-detail-content__table-name" :title="row.tableName">{{ row.tableName }}</code></template>
          </el-table-column>
          <el-table-column prop="tableComment" label="说明" min-width="220">
            <template #default="{ row }"><span class="snapshot-detail-content__table-comment" :title="row.tableComment || undefined" :tabindex="row.tableComment ? 0 : undefined">{{ row.tableComment }}</span></template>
          </el-table-column>
          <el-table-column label="估算行数" width="136" align="right">
            <template #default="{ row }"><span class="snapshot-detail-content__estimate">{{ formatEstimatedRows(row.rowCountEstimate) }}</span></template>
          </el-table-column>
          <el-table-column label="治理状态" width="126">
            <template #default="{ row }"><BusinessStatusBadge :status="row.governanceStatus" :label="governanceStatusLabel(row.governanceStatus)" /></template>
          </el-table-column>
        </el-table>
        <EmptyState v-else message="该快照尚未返回表清单" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.snapshot-detail-content { display: grid; gap: 12px; min-width: 0; }
.snapshot-detail-content__source { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px 12px; min-width: 0; padding: 2px 0; }
.snapshot-detail-content__source > span,
.snapshot-detail-content__summary-item > span { color: var(--do-muted); font-size: 12px; }
.snapshot-detail-content__source > strong { min-width: 0; color: var(--do-ink); font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.snapshot-detail-content__source > strong:focus-visible,
.snapshot-detail-content__summary-item time:focus-visible,
.snapshot-detail-content__table-comment:focus-visible,
.snapshot-detail-content__field-comment:focus-visible { border-radius: 3px; outline: 2px solid color-mix(in srgb, var(--do-primary) 42%, transparent); outline-offset: 3px; }
.snapshot-detail-content__summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 0; min-width: 0; padding: 10px 14px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); background: var(--do-surface); }
.snapshot-detail-content__summary-item { display: grid; gap: 3px; min-width: 0; flex: 1 1 175px; padding: 0 16px; border-right: 1px solid var(--do-line); }
.snapshot-detail-content__summary-item:first-child { padding-left: 0; }
.snapshot-detail-content__summary-item:last-child { padding-right: 0; border-right: 0; }
.snapshot-detail-content__summary-item > strong,
.snapshot-detail-content__summary-item > time { color: var(--do-ink); font-size: 14px; font-weight: 600; line-height: 1.4; }
.snapshot-detail-content__tables { min-width: 0; padding: 14px 16px 16px; overflow: hidden; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); }
.snapshot-detail-content__heading { display: flex; align-items: center; gap: 8px; margin-bottom: 9px; }
.snapshot-detail-content__heading h3 { display: flex; align-items: center; gap: 7px; margin: 0; color: var(--do-ink); font-size: 15px; font-weight: 700; }
.snapshot-detail-content__table-region { min-width: 0; max-width: 100%; overflow-x: auto; }
.snapshot-detail-content__table :deep(.el-table) { min-width: 860px; border: 0; border-radius: 0; color: var(--do-ink); background: var(--do-surface); }
.snapshot-detail-content__table :deep(.el-table::before),
.snapshot-detail-content__table :deep(.el-table__inner-wrapper::before) { display: none; }
.snapshot-detail-content__table :deep(.el-table th.el-table__cell) { height: 42px; padding: 0; color: var(--do-muted); background: #f4f6f9; font-size: 12px; font-weight: 600; }
.snapshot-detail-content__table :deep(.el-table th.el-table__cell .cell) { padding: 0 12px; line-height: 42px; }
.snapshot-detail-content__table :deep(.el-table td.el-table__cell) { padding: 12px 0; border-bottom-color: var(--do-line); font-size: 14px; vertical-align: middle; }
.snapshot-detail-content__table :deep(.el-table td.el-table__cell .cell) { padding: 0 12px; line-height: 1.45; }
.snapshot-detail-content__table :deep(.el-table tr:last-child td.el-table__cell) { border-bottom: 0; }
.snapshot-detail-content__table :deep(.el-table__expand-icon) { width: 24px; height: 24px; color: var(--do-primary-strong); }
.snapshot-detail-content__table :deep(.el-table__expanded-cell) { padding: 10px 14px 14px 42px; background: #f7f8fa; }
.snapshot-detail-content__expanded-fields { min-width: 0; overflow-x: auto; }
.snapshot-detail-content__field-table :deep(.el-table) { min-width: 850px; border: 0; color: var(--do-ink); background: var(--do-surface); }
.snapshot-detail-content__field-table :deep(.el-table::before),
.snapshot-detail-content__field-table :deep(.el-table__inner-wrapper::before) { display: none; }
.snapshot-detail-content__field-table :deep(.el-table th.el-table__cell) { height: 36px; padding: 0; color: var(--do-muted); background: #f4f6f9; font-size: 12px; }
.snapshot-detail-content__field-table :deep(.el-table th.el-table__cell .cell) { padding: 0 10px; line-height: 36px; }
.snapshot-detail-content__field-table :deep(.el-table td.el-table__cell) { padding: 10px 0; border-bottom-color: var(--do-line); font-size: 14px; }
.snapshot-detail-content__field-table :deep(.el-table td.el-table__cell .cell) { padding: 0 10px; line-height: 1.4; }
.snapshot-detail-content__field-table :deep(.business-status-badge--success) { color: var(--do-muted); background: var(--do-soft); }
.snapshot-detail-content__table-name,
.snapshot-detail-content__field-name code,
.snapshot-detail-content__field-type { color: var(--do-ink); font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace; font-size: 13px; font-weight: 500; }
.snapshot-detail-content__table-name { white-space: nowrap; }
.snapshot-detail-content__table-comment,
.snapshot-detail-content__field-comment { display: inline-block; max-width: 100%; overflow: hidden; color: var(--do-ink); font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.snapshot-detail-content__field-name { display: inline-flex; align-items: center; gap: 6px; min-width: 0; }
.snapshot-detail-content__field-name svg { flex: 0 0 auto; color: var(--do-warning); }
.snapshot-detail-content__estimate { color: var(--do-muted); font-size: 13px; white-space: nowrap; }
.snapshot-detail-content__uncollected { color: var(--do-muted); font-size: 12px; }

@media (max-width: 760px) {
  .snapshot-detail-content__summary { padding: 9px 11px; }
  .snapshot-detail-content__summary-item { flex-basis: 44%; padding: 0 12px; }
  .snapshot-detail-content__summary-item:nth-child(2) { border-right: 0; }
  .snapshot-detail-content__summary-item:nth-child(3) { padding-left: 0; }
  .snapshot-detail-content__summary-item:last-child { border-right: 0; }
  .snapshot-detail-content__tables { padding: 12px; }
}
@media (max-width: 480px) {
  .snapshot-detail-content__summary-item { flex-basis: 100%; padding: 6px 0; border-right: 0; border-bottom: 1px solid var(--do-line); }
  .snapshot-detail-content__summary-item:first-child { padding-top: 0; }
  .snapshot-detail-content__summary-item:last-child { padding-bottom: 0; border-bottom: 0; }
}
</style>
