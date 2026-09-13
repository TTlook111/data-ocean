<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Database, LockKeyhole, RefreshCw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { useAdminContextStore } from '../../stores/adminContext'
import type { AdminContextMode } from '../../router/adminNavigation'

const props = defineProps<{ mode: AdminContextMode }>()
const route = useRoute()
const router = useRouter()
const context = useAdminContextStore()
const invalidContext = ref('')
let syncing = false

const showSnapshot = computed(() => props.mode === 'datasource-snapshot')
const queryDatasourceId = computed(() => Number(route.query.datasourceId) || undefined)
const querySnapshotId = computed(() => Number(route.query.snapshotId) || undefined)

async function syncFromUrl() {
  if (syncing) return
  syncing = true
  invalidContext.value = ''
  try {
    await context.initialize()
    if (queryDatasourceId.value) {
      const found = context.datasources.some((item) => item.id === queryDatasourceId.value)
      if (!found) {
        invalidContext.value = 'URL 中的数据源不存在或当前账号不可用，已保留安全范围。'
      } else if (context.datasourceId !== queryDatasourceId.value) {
        await context.selectDatasource(queryDatasourceId.value)
      }
    }

    if (showSnapshot.value && querySnapshotId.value) {
      if (!context.snapshots.some((item) => item.id === querySnapshotId.value)) {
        invalidContext.value = 'URL 中的快照不属于当前数据源，已清除该快照范围。'
        context.selectSnapshot(undefined)
      } else if (context.snapshotId !== querySnapshotId.value) {
        context.selectSnapshot(querySnapshotId.value)
      }
    }
  } catch (cause) {
    invalidContext.value = cause instanceof Error
      ? cause.message
      : '数据源范围加载失败，请检查后台服务后重试。'
  } finally {
    syncing = false
  }
}

function updateQuery(next: Record<string, number | undefined>) {
  const query = { ...route.query }
  Object.entries(next).forEach(([key, value]) => {
    if (value == null) delete query[key]
    else query[key] = String(value)
  })
  router.replace({ query })
}

async function handleDatasourceChange(id?: number) {
  await context.selectDatasource(id)
  updateQuery({ datasourceId: id, snapshotId: undefined })
}

function handleSnapshotChange(id?: number) {
  context.selectSnapshot(id)
  updateQuery({ snapshotId: id })
}

onMounted(syncFromUrl)
watch(() => [route.query.datasourceId, route.query.snapshotId, props.mode], syncFromUrl)
</script>

<template>
  <section class="scope-bar" aria-label="后台业务范围">
    <div class="scope-bar__intro">
      <LockKeyhole v-if="mode === 'locked-resource'" :size="16" />
      <Database v-else :size="16" />
      <div>
        <strong>{{ mode === 'datasource-snapshot' ? '数据源与快照范围' : '数据源范围' }}</strong>
        <span v-if="mode === 'datasource-snapshot'">只查看当前数据源的快照和治理对象</span>
        <span v-else>选择后，当前工作区会使用该数据源上下文</span>
      </div>
    </div>

    <div class="scope-bar__controls">
      <el-select
        :model-value="context.datasourceId"
        :loading="context.loading"
        filterable
        clearable
        placeholder="选择数据源"
        @update:model-value="handleDatasourceChange"
      >
        <el-option
          v-for="item in context.datasources"
          :key="item.id"
          :value="item.id"
          :label="item.name + ' / ' + item.databaseName"
        />
      </el-select>
      <el-select
        v-if="showSnapshot"
        :model-value="context.snapshotId"
        :loading="context.loading"
        clearable
        :disabled="!context.datasourceId"
        placeholder="选择快照"
        @update:model-value="handleSnapshotChange"
      >
        <el-option
          v-for="item in context.snapshots"
          :key="item.id"
          :value="item.id"
          :label="'v' + item.snapshotVersion + ' · ' + item.status"
        />
      </el-select>
      <el-button :icon="RefreshCw" :loading="context.loading" aria-label="刷新范围" @click="context.refresh" />
    </div>

    <p v-if="invalidContext" class="scope-bar__warning">{{ invalidContext }}</p>
  </section>
</template>

<style scoped>
.scope-bar {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) minmax(360px, 1.2fr);
  gap: 16px;
  align-items: center;
  margin: 18px 24px 0;
  padding: 14px 16px;
  border: 1px solid rgba(77, 143, 220, .22);
  border-radius: var(--do-radius-lg);
  background: linear-gradient(120deg, var(--do-primary-soft), var(--do-surface) 60%);
}

.scope-bar__intro,
.scope-bar__controls {
  display: flex;
  align-items: center;
  gap: 10px;
}

.scope-bar__intro {
  color: var(--do-primary-strong);
}

.scope-bar__intro div {
  display: grid;
  gap: 3px;
}

.scope-bar__intro strong {
  font-size: 13px;
}

.scope-bar__intro span {
  color: var(--do-muted);
  font-size: 12px;
}

.scope-bar__controls :deep(.el-select) {
  min-width: 0;
  flex: 1;
}

.scope-bar__warning {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--do-danger);
  font-size: 12px;
}

</style>
