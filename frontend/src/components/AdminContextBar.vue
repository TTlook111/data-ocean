<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { BookOpen, Database, GitBranch, RefreshCw } from 'lucide-vue-next'
import { useAdminContextStore } from '../stores/adminContext'

const context = useAdminContextStore()

const snapshotOptions = computed(() =>
  context.snapshots.map((item) => ({
    value: item.id,
    label: `#${item.id} / v${item.snapshotVersion}`,
    status: item.status,
    tableCount: item.tableCount,
    columnCount: item.columnCount,
  })),
)

const knowledgeOptions = computed(() =>
  context.knowledgeDocs.map((item) => ({
    value: item.id,
    label: `${item.title} v${item.currentVersion}`,
    status: item.status,
  })),
)

function snapshotMeta(id?: number) {
  const option = snapshotOptions.value.find((item) => item.value === id)
  if (!option) return '未选择'
  return `${option.status} / ${option.tableCount} 表 / ${option.columnCount} 字段`
}

function knowledgeMeta(id?: number) {
  return knowledgeOptions.value.find((item) => item.value === id)?.status || '未选择'
}

onMounted(() => {
  context.initialize().catch(() => undefined)
})
</script>

<template>
  <section class="admin-context-bar" aria-label="后台上下文">
    <div class="context-control context-control--wide">
      <span class="context-label">
        <Database :size="15" />
        数据源
      </span>
      <el-select
        :model-value="context.datasourceId"
        :loading="context.loading"
        placeholder="选择数据源"
        size="small"
        filterable
        @update:model-value="context.selectDatasource"
      >
        <el-option
          v-for="item in context.datasources"
          :key="item.id"
          :label="`${item.name} / ${item.databaseName}`"
          :value="item.id"
        />
      </el-select>
    </div>

    <div class="context-control">
      <span class="context-label">
        <GitBranch :size="15" />
        快照
      </span>
      <el-select
        :model-value="context.snapshotId"
        :disabled="!context.datasourceId"
        :loading="context.loading"
        placeholder="选择快照"
        size="small"
        @update:model-value="context.selectSnapshot"
      >
        <el-option v-for="item in snapshotOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <small>{{ snapshotMeta(context.snapshotId) }}</small>
    </div>

    <div class="context-control context-control--wide">
      <span class="context-label">
        <BookOpen :size="15" />
        知识库
      </span>
      <el-select
        :model-value="context.knowledgeDocId"
        :disabled="!context.datasourceId"
        :loading="context.loading"
        placeholder="选择知识文档"
        size="small"
        filterable
        @update:model-value="context.selectKnowledgeDoc"
      >
        <el-option v-for="item in knowledgeOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <small>{{ knowledgeMeta(context.knowledgeDocId) }}</small>
    </div>

    <el-button class="context-refresh" :icon="RefreshCw" :loading="context.loading" size="small" @click="context.refresh" />
  </section>
</template>
