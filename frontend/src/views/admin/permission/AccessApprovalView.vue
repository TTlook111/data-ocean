<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAccessApprovalRequests, reviewAccessApprovalRequest, type AccessApprovalRequestItem } from '../../../api/admin/permission'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const rows = ref<AccessApprovalRequestItem[]>([])
const status = ref('PENDING')
const page = ref(1)
const total = ref(0)
const loading = ref(true)
const error = ref('')
const reviewing = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await listAccessApprovalRequests({ status: status.value || undefined, page: page.value, size: 20 })
    rows.value = result.data.records
    total.value = result.data.total
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '访问审批列表加载失败'
  } finally {
    loading.value = false
  }
}

async function review(row: AccessApprovalRequestItem, approved: boolean) {
  const reason = approved
    ? ''
    : await ElMessageBox.prompt('请输入拒绝理由，便于申请人回到正确的治理或授权流程。', '拒绝访问申请', { inputPlaceholder: '拒绝理由' }).then((result) => result.value).catch(() => null)
  if (reason === null) return
  reviewing.value = true
  try {
    await reviewAccessApprovalRequest(row.id, { approved, reason: reason || undefined })
    ElMessage.success(approved ? '申请已通过' : '申请已拒绝')
    await load()
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '审批操作失败')
  } finally {
    reviewing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-page approval-page">
    <TaskPageHeader title="访问审批" description="这里只提供管理员审批队列。审批通过后，后端会生成有有效期的临时允许策略；前端不绕过治理状态。">
      <template #actions>
        <el-select v-model="status" placeholder="状态" clearable style="width: 140px" @change="page = 1; load()">
          <el-option label="待审批" value="PENDING" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已拒绝" value="REJECTED" />
          <el-option label="已过期" value="EXPIRED" />
        </el-select>
      </template>
    </TaskPageHeader>

    <el-alert title="安全边界" type="warning" :closable="false">
      当前接口的列表范围由后端服务决定，本页面仅面向拥有 security:manage 的管理员开放。普通用户“我的申请”暂不在本轮开放。
    </el-alert>

    <LoadingState v-if="loading" variant="skeleton" :rows="5" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <section v-else class="approval-page__card">
      <el-table v-if="rows.length" v-loading="reviewing" :data="rows" stripe>
        <el-table-column prop="id" label="申请号" width="90" />
        <el-table-column prop="requesterId" label="申请人 ID" width="100" />
        <el-table-column prop="datasourceId" label="数据源 ID" width="100" />
        <el-table-column label="申请对象" min-width="180">
          <template #default="{ row }">{{ row.tableName }}{{ row.columnName ? '.' + row.columnName : '' }}</template>
        </el-table-column>
        <el-table-column prop="requestReason" label="申请理由" min-width="220" show-overflow-tooltip />
        <el-table-column prop="requestedDuration" label="时长/小时" width="100" />
        <el-table-column label="状态" width="120"><template #default="{ row }"><BusinessStatusBadge :status="row.status" /></template></el-table-column>
        <el-table-column label="创建时间" prop="createdAt" width="170" />
        <el-table-column v-if="status === 'PENDING'" label="操作" fixed="right" width="160">
          <template #default="{ row }">
            <el-button link type="success" @click="review(row, true)">通过</el-button>
            <el-button link type="danger" @click="review(row, false)">拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else message="当前筛选下没有访问审批记录" />
      <el-pagination
        v-if="total > 20"
        v-model:current-page="page"
        class="approval-page__pagination"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="20"
        @current-change="load"
      />
    </section>
  </div>
</template>

<style scoped>
.approval-page__card {
  margin-top: 18px;
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
}

.approval-page__pagination {
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
