<script setup lang="ts">
import type { IamS1QueryTaskResult } from '../../api/iamS1'

defineProps<{
  result: IamS1QueryTaskResult
  rows: Record<string, unknown>[]
  page: number
  pageSize: number
}>()

const emit = defineEmits<{ 'update:page': [page: number] }>()
</script>

<template>
  <div class="query-result-table">
    <el-table :data="rows" border stripe max-height="360" size="small" aria-label="受保护的查询结果">
      <el-table-column
        v-for="column in (result.columns || [])"
        :key="column.name"
        :prop="column.name"
        :label="column.comment || column.name"
        min-width="120"
        show-overflow-tooltip
      />
    </el-table>
    <el-pagination
      v-if="(result.data?.length || 0) > pageSize"
      :current-page="page"
      :page-size="pageSize"
      :total="result.data?.length || 0"
      layout="total, prev, pager, next"
      size="small"
      @update:current-page="emit('update:page', $event)"
    />
  </div>
</template>

<style scoped>
.query-result-table { min-width: 0; display: grid; gap: 8px; }
.query-result-table :deep(.el-table) { width: 100%; }
.query-result-table :deep(.el-table__header th) { background: var(--do-bg); color: var(--do-ink); }
.query-result-table :deep(.el-pagination) { justify-content: flex-end; }
</style>
