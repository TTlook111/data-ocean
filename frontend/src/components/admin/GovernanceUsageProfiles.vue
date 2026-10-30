<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useGovernanceScope } from '../../composables/useGovernanceScope'
import { useAdminScrollLock } from '../../composables/useAdminScrollLock'
import { listSnapshotTables, listSnapshotTableColumns, type TableMetaItem } from '../../api/admin/governance'
import {
  currentUsageProfile,
  usageEvidence,
  type UsageProfile,
  type UsageEvidence,
} from '../../api/admin/governanceWorkflow'
const scope = useGovernanceScope(),
  context = scope.context
const ready = scope.ready,
  canView = computed(() => scope.can('governance:field:view')),
  canReadCatalog = computed(() => scope.can('metadata:view'))
const tables = ref<TableMetaItem[]>([]),
  selectedTable = ref(''),
  rows = ref<UsageProfile[]>([]),
  loading = ref(false),
  error = ref('')
const evidenceTarget = ref<UsageProfile>(),
  evidenceRows = ref<UsageEvidence[]>([]),
  evidenceTotal = ref(0),
  evidencePage = ref(1),
  evidenceLoading = ref(false),
  evidenceError = ref('')
let epoch = 0,
  evidenceRequest = 0,
  disposed = false
useAdminScrollLock(() => !!evidenceTarget.value)
function resetEvidence() {
  evidenceRequest++
  evidenceTarget.value = undefined
  evidenceRows.value = []
  evidenceTotal.value = 0
  evidenceLoading.value = false
  evidenceError.value = ''
}
async function loadTables() {
  const ticket = ++epoch,
    g = scope.capture(),
    snapshot = context.snapshotId
  tables.value = []
  rows.value = []
  selectedTable.value = ''
  error.value = ''
  loading.value = false
  resetEvidence()
  if (!canView.value || !snapshot) return
  if (!canReadCatalog.value) {
    error.value = '没有metadata:view，不能读取辅助表字段目录；字段查看权不自动授予此能力。'
    return
  }
  loading.value = true
  try {
    const response = await listSnapshotTables(snapshot)
    if (!disposed && ticket === epoch && scope.isCurrent(g)) tables.value = response.data
  } catch {
    if (!disposed && ticket === epoch && scope.isCurrent(g)) error.value = '表目录读取失败，请重试。'
  } finally {
    if (!disposed && ticket === epoch && scope.isCurrent(g)) loading.value = false
  }
}
async function load() {
  const ticket = ++epoch,
    g = scope.capture(),
    snapshot = context.snapshotId,
    name = selectedTable.value
  rows.value = []
  error.value = ''
  loading.value = false
  resetEvidence()
  if (!canView.value || !canReadCatalog.value || !snapshot || !tables.value.some((t) => t.tableName === name))
    return
  loading.value = true
  const current = () => !disposed && ticket === epoch && scope.isCurrent(g) && name === selectedTable.value
  try {
    const columns = await listSnapshotTableColumns(snapshot, name)
    if (!current()) return
    const profiles: UsageProfile[] = []
    let failures = 0
    for (let start = 0; start < columns.data.length; start += 8) {
      const slice = columns.data.slice(start, start + 8)
      const results = await Promise.allSettled(
        slice.map((column) => currentUsageProfile(snapshot, column.id)),
      )
      if (!current()) return
      results.forEach((result, index) => {
        if (result.status === 'fulfilled' && result.value.data?.columnMetaId === slice[index]?.id)
          profiles.push(result.value.data)
        else failures++
      })
    }
    rows.value = profiles
    if (failures)
      error.value = `有 ${failures} 个字段档案读取失败，已读取 ${profiles.length} 项；未知不显示为0分，请重试。`
  } catch {
    if (current()) error.value = '字段档案读取失败，请重试。'
  } finally {
    if (current()) loading.value = false
  }
}
function openEvidence(row: UsageProfile) {
  if (
    !canView.value ||
    !row.revisionId ||
    !rows.value.some((x) => x.columnMetaId === row.columnMetaId && x.revisionId === row.revisionId)
  )
    return
  evidenceTarget.value = { ...row }
  evidencePage.value = 1
  void loadEvidence()
}
function closeEvidence(visible: boolean) {
  if (!visible) resetEvidence()
}
async function loadEvidence() {
  const target = evidenceTarget.value,
    snapshot = context.snapshotId,
    g = scope.capture(),
    ticket = ++evidenceRequest
  evidenceRows.value = []
  evidenceTotal.value = 0
  evidenceError.value = ''
  evidenceLoading.value = false
  if (!target?.revisionId || !snapshot || !canView.value) return
  const current = () =>
    !disposed && scope.isCurrent(g) && ticket === evidenceRequest && target === evidenceTarget.value
  evidenceLoading.value = true
  try {
    const result = await usageEvidence(snapshot, target.columnMetaId, evidencePage.value)
    if (!current()) return
    if (result.data?.revisionId !== target.revisionId)
      throw new Error('字段修订已变化，不能把其他版本的使用证据归于本档案；请关闭并刷新档案。')
    if (!Array.isArray(result.data.records) || !Number.isFinite(result.data.total))
      throw new Error('证据回执不完整，不能判断为空记录。')
    evidenceRows.value = result.data.records
    evidenceTotal.value = result.data.total
  } catch (cause) {
    if (current()) evidenceError.value = cause instanceof Error ? cause.message : '使用证据读取失败，请重试。'
  } finally {
    if (current()) evidenceLoading.value = false
  }
}
watch(scope.generation, loadTables, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => {
  disposed = true
  epoch++
  evidenceRequest++
})
</script>
<template>
  <section class="usage-profiles">
    <div class="usage-contract">
      <strong>标签、知识资格、使用参考分分别核对</strong>
      <p>执行成功和重复使用不能证明业务正确；只计当前修订已确认且去重的SQL样例，不自动奖励或审核。</p>
    </div>
    <div class="usage-heading">
      <div>
        <h3>字段使用置信度</h3>
        <p>
          业务标签独立维护；参考分仅供试用，来自当前版本已审核的 SQL 样例；相同 SQL
          合并计算，不代表业务正确概率。
        </p>
      </div>
      <el-button :disabled="!canView || loading" @click="selectedTable ? load() : loadTables()">
        刷新
      </el-button>
    </div>
    <p v-if="!ready" role="status">{{ scope.scopeIssue.value || '请先选择数据源与快照。' }}</p>
    <p v-else-if="!canView">没有当前范围的字段查看权限。</p>
    <template v-else>
      <el-select
        v-model="selectedTable"
        placeholder="选择要查看的表"
        filterable
        :disabled="loading"
        @change="load"
      >
        <el-option
          v-for="table in tables"
          :key="table.id"
          :label="table.tableComment ? `${table.tableName} · ${table.tableComment}` : table.tableName"
          :value="table.tableName"
        />
      </el-select>
      <p v-if="error" role="alert">{{ error }}</p>
      <el-table
        :data="rows"
        v-loading="loading"
        :empty-text="
          selectedTable ? '当前表暂无可读取的字段档案。' : '选择表后查看字段标签、审核情况和使用证据。'
        "
      >
        <el-table-column prop="fieldName" label="字段" min-width="220" />
        <el-table-column label="业务标签" min-width="140">
          <template #default="{ row }">{{ row.tags.join('、') || '尚未分类' }}</template>
        </el-table-column>
        <el-table-column label="问数知识资格" width="160">
          <template #default="{ row }">
            {{ row.reviewStatus === 'APPROVED' ? '当前修订可用' : '来源或修订待确认' }}
          </template>
        </el-table-column>
        <el-table-column label="使用参考分" min-width="150">
          <template #default="{ row }">
            <strong>
              {{
                row.reviewStatus !== 'APPROVED' ? '资格待确认' : row.score == null ? '证据不足' : row.score
              }}
            </strong>
          </template>
        </el-table-column>
        <el-table-column label="有效使用证据" width="160">
          <template #default="{ row }">
            {{ row.evidenceCount ?? '未知' }} 条
            <span v-if="row.evidenceCount">
              · 正向 {{ row.positiveCount ?? '未知' }} / 负向 {{ row.negativeCount ?? '未知' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="!row.revisionId" @click="openEvidence(row)">
              证据明细
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <el-dialog
      :model-value="!!evidenceTarget"
      :title="evidenceTarget?.fieldName || '使用证据'"
      width="min(820px,94vw)"
      @update:model-value="closeEvidence"
    >
      <p>
        历史包含已撤回或替换的意见，当前分数只计入有效且去重的贡献。修订 #{{ evidenceTarget?.revisionId }} ·
        字段 #{{ evidenceTarget?.columnMetaId }}。
      </p>
      <p v-if="evidenceError" role="alert">
        {{ evidenceError }}
        <el-button @click="loadEvidence">重试</el-button>
      </p>
      <el-table
        v-else
        :data="evidenceRows"
        v-loading="evidenceLoading"
        empty-text="当前修订尚无已确认使用证据。"
      >
        <el-table-column label="时间" min-width="170">
          <template #default="{ row }">{{ row.createdAt?.replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column label="评价" width="90">
          <template #default="{ row }">{{ row.direction === 'LIKE' ? '认可' : '有问题' }}</template>
        </el-table-column>
        <el-table-column label="当前贡献" min-width="170">
          <template #default="{ row }">
            {{
              row.contributionStatus === 'LEGACY_POLICY'
                ? '旧政策，仅供追溯'
                : row.contributionStatus === 'NOT_QUALIFIED'
                  ? '当前知识资格待确认'
                  : row.contributes
                    ? '参与当前分数'
                    : row.active
                      ? '同源较新意见已替代'
                      : '已撤回或替换'
            }}
          </template>
        </el-table-column>
        <el-table-column prop="note" label="确认依据" min-width="230" />
      </el-table>
      <el-pagination
        v-if="evidenceTotal > 20"
        v-model:current-page="evidencePage"
        :total="evidenceTotal"
        :page-size="20"
        layout="prev,pager,next,total"
        @current-change="loadEvidence"
      />
      <template #footer><el-button @click="closeEvidence(false)">关闭</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>
.usage-contract {
  padding: 16px;
  background: var(--do-primary-soft);
  border-radius: 8px;
  font-size: 13px;
}
.usage-contract p {
  margin: 6px 0 0;
  line-height: 1.7;
  color: var(--do-muted);
}
.usage-profiles {
  padding: 20px;
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: 14px;
  display: grid;
  gap: 16px;
  min-width: 0;
}
.usage-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.usage-heading h3 {
  margin: 0;
}
.usage-heading p {
  font-size: 13px;
  color: var(--do-ink-muted);
}
.usage-profiles > .el-select {
  max-width: 420px;
}
</style>
