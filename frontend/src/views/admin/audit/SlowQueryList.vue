<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listSlowQueries, type AuditLogVO } from '../../../api/admin/audit'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAuthStore } from '../../../stores/auth'
import { positiveId } from '../../../utils/lineageContext'
import { getHttpErrorMessage } from '../../../utils/httpError'
import AuditRecordDrawer from './AuditRecordDrawer.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
const iam = useIamS1Store(),
  auth = useAuthStore(),
  route = useRoute(),
  router = useRouter()
const canViewAudit = computed(
  () =>
    iam.loaded &&
    !iam.errorMessage &&
    (iam.systemAdmin || iam.datasourcesWithFunction('audit:view').length > 0),
)
const scope = computed(() => JSON.stringify([auth.token, iam.snapshot, iam.loaded, iam.errorMessage]))
const logs = ref<AuditLogVO[]>([]),
  total = ref(0),
  loading = ref(false),
  error = ref(''),
  page = ref(positiveId(route.query.slowPage) || 1),
  pageSize = 20
const detailTarget = ref<{ id: number; source: number } | null>(null)
let generation = 0,
  alive = true
async function fetchData() {
  const ticket = ++generation,
    key = scope.value
  detailTarget.value = null
  logs.value = []
  total.value = 0
  error.value = ''
  if (!canViewAudit.value) {
    loading.value = false
    return
  }
  loading.value = true
  const current = () => alive && ticket === generation && key === scope.value && canViewAudit.value
  try {
    const r = await listSlowQueries({ page: page.value, pageSize })
    if (current()) {
      logs.value = (r.data?.records || []).map((x) => ({ ...x, sqlText: undefined }))
      total.value = r.data?.total || 0
    }
  } catch (cause) {
    if (current()) error.value = getHttpErrorMessage(cause, '慢查询列表读取失败')
  } finally {
    if (current()) loading.value = false
  }
}
function changePage(value: number) {
  page.value = value
  void router.replace({ query: { ...route.query, slowPage: value > 1 ? String(value) : undefined } })
  void fetchData()
}
watch(
  () => route.query.slowPage,
  () => {
    const next = positiveId(route.query.slowPage) || 1
    if (next !== page.value) {
      page.value = next
      void fetchData()
    }
  },
)
watch(
  scope,
  () => {
    generation++
    logs.value = []
    total.value = 0
    void fetchData()
  },
  { flush: 'sync' },
)
void fetchData()
onBeforeUnmount(() => {
  alive = false
  generation++
  logs.value = []
})
</script>
<template>
  <section class="slow-query-page">
    <div class="performance-intro">
      <span>性能观察 / 当前有权源合集</span>
      <h2>定位耗时较长的查询记录</h2>
      <p>
        按服务端记录的任务耗时降序展示，慢标记使用服务端配置阈值。接口只支持合并分页，不支持再选某个数据源；保留其他
        Tab 的数据源参数也不会改变此处范围。
      </p>
      <el-button :loading="loading" :disabled="!canViewAudit" @click="fetchData">重新读取</el-button>
    </div>
    <section class="content-panel">
      <ErrorState v-if="error" :message="error" @retry="fetchData" />
      <LoadingState v-else-if="loading" variant="skeleton" :rows="5" />
      <EmptyState v-else-if="!canViewAudit" message="没有 audit:view 能力，或能力摘要未就绪。" />
      <EmptyState
        v-else-if="!logs.length"
        message="当前有权源没有已记录的慢查询；这不能证明没有耗时问题或未投影查询。"
      />
      <template v-else>
        <div class="panel-caption">
          <strong>已标记慢查询</strong>
          <span>服务端共 {{ total }} 条 · 按任务耗时降序</span>
        </div>
        <el-table :data="logs" row-key="id">
          <el-table-column prop="id" label="审计 ID" width="100" />
          <el-table-column prop="question" label="查询问题" min-width="280" show-overflow-tooltip />
          <el-table-column label="数据源" min-width="170">
            <template #default="{ row }">{{ row.datasourceName || `#${row.datasourceId}` }}</template>
          </el-table-column>
          <el-table-column label="任务耗时" width="140">
            <template #default="{ row }">
              <strong>{{ row.executionTimeMs ?? '—' }}</strong>
              ms
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="记录时间" min-width="180" />
          <el-table-column label="操作" fixed="right" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="detailTarget = { id: row.id, source: row.datasourceId }">
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <p class="boundary-note">
          此处不默认展示 SQL。每行详情按精确审计记录 ID 重新读取，可按需查看原审计 SQL；数字任务 ID
          不能代替公开 taskId。
        </p>
      </template>
      <el-pagination
        v-if="!loading && !error && total > pageSize"
        class="pager"
        layout="total,prev,pager,next"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        @current-change="changePage"
      />
    </section>
    <AuditRecordDrawer :target="detailTarget" @close="detailTarget = null" />
  </section>
</template>
<style scoped>
.slow-query-page {
  display: grid;
  gap: 20px;
  min-width: 0;
}
.performance-intro {
  padding: 24px;
  border-left: 3px solid var(--do-primary);
  background: var(--do-surface);
  border-radius: 0 12px 12px 0;
}
.performance-intro > span {
  font-size: 11px;
  color: var(--do-primary-strong);
  font-weight: 700;
}
.performance-intro h2 {
  font-size: 22px;
  margin: 10px 0;
}
.performance-intro p,
.boundary-note {
  font-size: 12px;
  color: var(--do-muted);
  line-height: 1.8;
  max-width: 850px;
}
.performance-intro .el-button {
  margin-top: 8px;
}
.content-panel {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: 12px;
  background: var(--do-surface);
  min-width: 0;
}
.panel-caption {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
  font-size: 13px;
}
.panel-caption span {
  color: var(--do-muted);
  font-size: 12px;
}
.pager {
  margin-top: 20px;
  justify-content: flex-end;
}
@media (max-width: 700px) {
  .panel-caption {
    flex-direction: column;
  }
  .performance-intro {
    padding: 18px;
  }
  .pager {
    overflow: auto;
    justify-content: flex-start;
  }
}
</style>
