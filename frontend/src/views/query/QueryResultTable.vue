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
    <el-table :data="rows" border stripe max-height="360" aria-label="受保护的查询结果">
      <el-table-column
        v-for="column in (result.columns || [])"
        :key="column.name"
        :prop="column.name"
        :label="column.comment?.trim() || (/^s\d+_c\d+$/.test(column.name) ? `结果列 ${(result.columns || []).indexOf(column) + 1}（未提供业务名称）` : column.name)"
        min-width="120"
        show-overflow-tooltip
      ><template #header><span>{{ column.comment?.trim() || (/^s\d+_c\d+$/.test(column.name) ? `结果列 ${(result.columns || []).indexOf(column) + 1}（未提供业务名称）` : column.name) }}</span><small v-if="column.comment?.trim() || /^s\d+_c\d+$/.test(column.name)" class="result-original-key" translate="no">原始列名：{{ column.name }}</small></template></el-table-column>
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
.result-original-key { display:block; color:var(--do-muted); font-size:11px; font-weight:400; overflow-wrap:anywhere; }
.query-result-table { min-width: 0; display: grid; gap: 8px; }
.query-result-table :deep(.el-table) { width: 100%; }
.query-result-table :deep(.el-table .cell) { padding-block: 5px; font-size: 14px; line-height: 1.6; }
.query-result-table :deep(.el-table__header th) { background: var(--do-bg); color: var(--do-ink); }
.query-result-table :deep(.el-pagination) { justify-content: flex-end; }
</style>
