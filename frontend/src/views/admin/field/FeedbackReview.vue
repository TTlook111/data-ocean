<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CheckCircle, XCircle } from 'lucide-vue-next'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import {
  listPendingReviews,
  approveFeedback,
  rejectFeedback,
  type FeedbackVO
} from '../../../api/admin/field'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAdminContextStore } from '../../../stores/adminContext'

const iamS1 = useIamS1Store()
const adminContext = useAdminContextStore()
const FIELD_VIEW = 'governance:field:view'
const FIELD_MANAGE = 'governance:field:manage'
const MANAGE_HINT = '需要 governance:field:manage 与当前数据源同一绑定'
const canViewFields = computed(() => iamS1.canOnDatasource(FIELD_VIEW, adminContext.datasourceId || undefined))
const canManageFields = computed(() => iamS1.canOnDatasource(FIELD_MANAGE, adminContext.datasourceId || undefined))

const loading = ref(false)
const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)

const reviews = ref<FeedbackVO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

async function fetchReviews() {
  if (!canViewFields.value) {
    reviews.value = []
    total.value = 0
    loading.value = false
    return
  }
  loading.value = true
  try {
    const res = await listPendingReviews({ page: page.value, pageSize: pageSize.value })
    reviews.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } finally {
    loading.value = false
  }
}

async function handleApprove(feedbackId: number) {
  if (!canManageFields.value) {
    ElMessage.warning(MANAGE_HINT)
    return
  }
  try {
    await ElMessageBox.confirm('确认通过此反馈？通过后将扣减字段可信度 15 分。', '审核确认', {
      confirmButtonText: '确认通过',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await approveFeedback(feedbackId)
    ElMessage.success('审核通过')
    await fetchReviews()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e.response?.data?.message || '操作失败')
    }
  }
}

async function handleReject(feedbackId: number) {
  if (!canManageFields.value) {
    ElMessage.warning(MANAGE_HINT)
    return
  }
  try {
    await ElMessageBox.confirm('确认驳回此反馈？驳回后不会调整可信度。', '审核确认', {
      confirmButtonText: '确认驳回',
      cancelButtonText: '取消',
      type: 'info'
    })
    await rejectFeedback(feedbackId)
    ElMessage.success('已驳回')
    await fetchReviews()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e.response?.data?.message || '操作失败')
    }
  }
}

function handlePageChange(p: number) {
  page.value = p
  fetchReviews()
}

onMounted(() => {
  withContext(() => { reveal('.content-panel, .stats-row, .toolbar', { y: 14, stagger: 0.06 }) })
  fetchReviews()
})
</script>

<template>
  <main ref="pageRef" class="feedback-review-page post-login-page">

    <section class="content-panel">
      <el-table :data="reviews" v-loading="loading" stripe>
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
            <el-button link type="success" size="small" :disabled="!canManageFields" @click="handleApprove(row.id)">
              <CheckCircle :size="14" style="margin-right: 2px" />通过
            </el-button>
            <el-button link type="danger" size="small" :disabled="!canManageFields" @click="handleReject(row.id)">
              <XCircle :size="14" style="margin-right: 2px" />驳回
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!reviews.length && !loading" description="暂无待审核反馈" />
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
.feedback-review-page { padding: 24px; }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; }
.pager { margin-top: 16px; justify-content: flex-end; }
</style>
