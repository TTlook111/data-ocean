<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CheckCircle, XCircle } from 'lucide-vue-next'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import {
  listPendingReviews,
  approveFeedback,
  rejectFeedback,
  type FeedbackVO,
} from '../../../api/admin/field'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAuthStore } from '../../../stores/auth'
import { trackGovernanceWrite } from '../../../composables/governanceWriteTracker'
import GovernanceWriteNotice from '../../../components/admin/GovernanceWriteNotice.vue'

const iamS1 = useIamS1Store()
const auth = useAuthStore()
const identityKey = computed(() =>
  JSON.stringify([auth.token, iamS1.snapshot, iamS1.loaded, iamS1.errorMessage]),
)
const error = ref(''),
  actionBusy = ref(false)
const ownCancelClass = `legacy-feedback-${Math.random().toString(36).slice(2)}`
function cancelOwn() {
  document.querySelector<HTMLButtonElement>(`.${ownCancelClass}`)?.click()
}
let disposed = false,
  requestId = 0,
  operation = 0
function current(key: string) {
  return !disposed && key === identityKey.value && canViewFields.value
}
const FIELD_VIEW = 'governance:field:view'
const FIELD_MANAGE = 'governance:field:manage'
const MANAGE_HINT = '需要字段维护功能，服务端还会逐条核验该字段的实际数据源；当前公共范围不能证明本行有权。'
const canViewFields = computed(() => iamS1.loaded && !iamS1.errorMessage && iamS1.hasGlobal(FIELD_VIEW))
const canManageFields = computed(
  () =>
    iamS1.loaded &&
    !iamS1.errorMessage &&
    (iamS1.systemAdmin || iamS1.datasourcesWithFunction(FIELD_MANAGE).length > 0),
)

const loading = ref(false)
const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)

const reviews = ref<FeedbackVO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

async function fetchReviews() {
  const request = ++requestId,
    key = identityKey.value
  reviews.value = []
  total.value = 0
  loading.value = false
  error.value = ''
  if (!canViewFields.value) return
  loading.value = true
  try {
    const res = await listPendingReviews({ page: page.value, pageSize: pageSize.value })
    if (current(key) && request === requestId) {
      reviews.value = res.data?.records ?? []
      total.value = res.data?.total ?? 0
    }
  } catch {
    if (current(key) && request === requestId) error.value = '既有反馈读取失败，不能视作没有待审记录。'
  } finally {
    if (current(key) && request === requestId) loading.value = false
  }
}
async function mutateLegacy(feedbackId: number, approved: boolean) {
  if (actionBusy.value || !canManageFields.value || !reviews.value.some((r) => r.id === feedbackId)) return
  const key = identityKey.value,
    op = ++operation,
    owned = () => current(key) && op === operation && canManageFields.value
  actionBusy.value = true
  error.value = ''
  try {
    await ElMessageBox.confirm(
      approved
        ? '服务端将核验此字段的实际数据源和待审状态；确认通过后按旧来源政策调整分数，具体变化以服务端记录为准，不等同当前修订使用分或原问题修复。'
        : '确认驳回此旧反馈？不调整旧来源分，原问题是否修复需独立核验。',
      '旧反馈审核确认',
      {
        confirmButtonText: approved ? '确认通过' : '确认驳回',
        cancelButtonText: '取消',
        type: approved ? 'warning' : 'info',
        cancelButtonClass: ownCancelClass,
      },
    )
    if (!owned()) return
    await trackGovernanceWrite(
      auth.token,
      [`legacy-feedback:${feedbackId}`],
      `旧反馈 #${feedbackId} · ${approved ? '通过' : '驳回'}`,
      () => (approved ? approveFeedback(feedbackId) : rejectFeedback(feedbackId)),
    )
    if (owned()) {
      ElMessage.success(approved ? '服务端已接受旧反馈审核' : '已驳回旧反馈')
      await fetchReviews()
    }
  } catch (cause) {
    if (owned() && cause !== 'cancel' && cause !== 'close')
      error.value =
        (cause as any)?.response?.data?.message ||
        (cause instanceof Error ? cause.message : '旧反馈审核未确认完成，请核对原记录')
  } finally {
    if (op === operation) actionBusy.value = false
  }
}
function handleApprove(id: number) {
  return mutateLegacy(id, true)
}
function handleReject(id: number) {
  return mutateLegacy(id, false)
}
function handlePageChange(p: number) {
  page.value = p
  fetchReviews()
}

watch(
  identityKey,
  () => {
    operation++
    actionBusy.value = false
    cancelOwn()
    page.value = 1
    void fetchReviews()
  },
  { immediate: true, flush: 'sync' },
)
onMounted(() => {
  withContext(() => reveal('.content-panel', { y: 14, stagger: 0.06 }))
})
onBeforeUnmount(() => {
  disposed = true
  requestId++
  operation++
  cancelOwn()
})
</script>

<template>
  <main ref="pageRef" class="feedback-review-page post-login-page">
    <GovernanceWriteNotice />
    <div class="legacy-feedback-contract">
      <h3>既有字段反馈记录</h3>
      <p>
        此接口合并当前账号有权查看的所有数据源记录，不按顶部公共源筛选。回执未提供逐行数据源，按钮只说明有维护能力；最终由服务端逐条核验，不能从当前源推断本行权限。
      </p>
      <p>旧来源分审核与上方当前版本反馈归因、正式知识修订、原情景核验分别处理。</p>
    </div>
    <p v-if="!canViewFields" role="status">没有字段查看能力，未读取既有反馈。</p>
    <p v-if="error" role="alert">
      {{ error }}
      <el-button @click="fetchReviews">重新读取</el-button>
    </p>

    <section class="content-panel">
      <el-table
        v-if="canViewFields && !error"
        empty-text="暂无待审核反馈"
        :data="reviews"
        v-loading="loading"
        stripe
      >
        <el-table-column prop="id" label="反馈ID" width="80" />
        <el-table-column prop="columnMetaId" label="字段ID" width="80" />
        <el-table-column prop="columnName" label="字段名" width="150" />
        <el-table-column prop="tableName" label="表名" width="150" />
        <el-table-column prop="userId" label="用户ID" width="80" />
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column prop="reasonCode" label="原因" width="140" />
        <el-table-column prop="comment" label="说明" />
        <el-table-column prop="createdAt" label="反馈时间" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="success"
              size="small"
              :loading="actionBusy"
              :disabled="!canManageFields || actionBusy"
              :title="MANAGE_HINT"
              @click="handleApprove(row.id)"
            >
              <CheckCircle :size="14" style="margin-right: 2px" />
              通过
            </el-button>
            <el-button
              link
              type="danger"
              size="small"
              :loading="actionBusy"
              :disabled="!canManageFields || actionBusy"
              :title="MANAGE_HINT"
              @click="handleReject(row.id)"
            >
              <XCircle :size="14" style="margin-right: 2px" />
              驳回
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-pagination
      v-if="total > pageSize"
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="page"
      @current-change="handlePageChange"
    />
  </main>
</template>

<style scoped>
.legacy-feedback-contract {
  padding: 16px;
  background: var(--do-bg);
  border: 1px solid var(--do-line);
  border-radius: 8px;
}
.legacy-feedback-contract h3 {
  font-size: 16px;
  margin: 0;
}
.legacy-feedback-contract p {
  font-size: 13px;
  line-height: 1.7;
  color: var(--do-muted);
}
.feedback-review-page {
  min-width: 0;
  width: 100%;
  padding: 0;
}
.content-panel {
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  padding: 16px;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
