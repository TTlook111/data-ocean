<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CheckCircle, XCircle } from 'lucide-vue-next'
import {
  listPendingReviews,
  approveFeedback,
  rejectFeedback,
  type FeedbackVO
} from '../../../api/admin/field'

const loading = ref(false)
const reviews = ref<FeedbackVO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

async function fetchReviews() {
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
  fetchReviews()
})
</script>

<template>
  <main class="feedback-review-page post-login-page">
    <header class="page-header">
      <div>
        <p>字段治理</p>
        <h1>反馈审核队列</h1>
        <span class="header-subtitle">审核用户对查询结果的负向反馈，通过后扣减字段可信度</span>
      </div>
    </header>

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
            <el-button link type="success" size="small" @click="handleApprove(row.id)">
              <CheckCircle :size="14" style="margin-right: 2px" />通过
            </el-button>
            <el-button link type="danger" size="small" @click="handleReject(row.id)">
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
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; }
.page-header h1 { margin: 4px 0; font-size: 22px; color: var(--do-ink); }
.page-header p { margin: 0; font-size: 12px; color: var(--do-muted); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; }
.pager { margin-top: 16px; justify-content: flex-end; }
</style>
