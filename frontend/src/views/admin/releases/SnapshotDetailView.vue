<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { GitCompareArrows, Table2 } from 'lucide-vue-next'
import { useRouter, useRoute } from 'vue-router'
import { getSnapshotDetail, type SnapshotDetail } from '../../../api/admin/metadata'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import ObjectContextSummary from '../../../components/admin/ObjectContextSummary.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import { governanceStatusLabel, snapshotStatusLabel } from '../../../utils/enumLabels'

const route = useRoute()
const router = useRouter()
const snapshotId = computed(() => Number(route.params.snapshotId))
const detail = ref<SnapshotDetail | null>(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await getSnapshotDetail(snapshotId.value)
    detail.value = result.data
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '快照详情加载失败'
  } finally {
    loading.value = false
  }
}

function openGovernance() {
  if (!detail.value) return
  router.push({
    path: '/admin/governance',
    query: {
      datasourceId: String(detail.value.snapshot.datasourceId),
      snapshotId: String(detail.value.snapshot.id),
    },
  })
}

onMounted(load)
</script>

<template>
  <div class="admin-page snapshot-detail-page">
    <ObjectContextSummary
      v-if="detail"
      :title="'快照 v' + detail.snapshot.snapshotVersion"
      :description="detail.datasourceName || ('数据源 #' + detail.snapshot.datasourceId)"
      back-to="/admin/releases"
      source-label="元数据采集快照"
    />
    <TaskPageHeader title="快照详情" description="检查采集结果和治理状态；审核通过与正式发布是两个独立动作。">
      <template #status><BusinessStatusBadge v-if="detail" :status="detail.snapshot.status" :label="snapshotStatusLabel(detail.snapshot.status)" /></template>
      <template #actions><el-button v-if="detail" type="primary" @click="openGovernance">进入治理</el-button></template>
    </TaskPageHeader>
    <LoadingState v-if="loading" variant="skeleton" :rows="6" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="detail">
      <section class="snapshot-detail__summary">
        <div><span>采集时间</span><strong>{{ detail.snapshot.createdAt }}</strong></div>
        <div><span>表数量</span><strong>{{ detail.snapshot.tableCount }}</strong></div>
        <div><span>字段数量</span><strong>{{ detail.snapshot.columnCount }}</strong></div>
        <div><span>质量分</span><strong>{{ detail.snapshot.qualityScore ?? '待检查' }}</strong></div>
      </section>
      <section class="snapshot-detail__card">
        <div class="snapshot-detail__heading"><h2><Table2 :size="17" />表清单</h2><span>{{ detail.tables.length }} 张表</span></div>
        <el-table v-if="detail.tables.length" :data="detail.tables" size="small">
          <el-table-column prop="tableName" label="表名" min-width="180" />
          <el-table-column prop="tableComment" label="说明" min-width="180" />
          <el-table-column prop="rowCountEstimate" label="估算行数" width="120" />
          <el-table-column label="治理状态" width="130">
            <template #default="{ row }"><BusinessStatusBadge :status="row.governanceStatus" :label="governanceStatusLabel(row.governanceStatus)" /></template>
          </el-table-column>
        </el-table>
        <EmptyState v-else message="该快照尚未返回表清单" />
      </section>
      <section class="snapshot-detail__card">
        <div class="snapshot-detail__heading"><h2><GitCompareArrows :size="17" />版本操作</h2></div>
        <p class="snapshot-detail__hint">要比较当前快照与其他版本，请从版本发布工作区进入差异页面。</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.snapshot-detail__summary {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.snapshot-detail__summary > div,
.snapshot-detail__card {
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
}

.snapshot-detail__summary span {
  display: block;
  color: var(--do-muted);
  font-size: 12px;
}

.snapshot-detail__summary strong {
  display: block;
  margin-top: 7px;
  color: var(--do-ink);
  font-size: 17px;
}

.snapshot-detail__card + .snapshot-detail__card {
  margin-top: 16px;
}

.snapshot-detail__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.snapshot-detail__heading h2 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  color: var(--do-ink);
  font-size: 15px;
}

.snapshot-detail__heading span,
.snapshot-detail__hint {
  color: var(--do-muted);
  font-size: 12px;
}

.snapshot-detail__hint {
  margin: 0;
}

@media (max-width: 760px) {
  .snapshot-detail__summary {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
