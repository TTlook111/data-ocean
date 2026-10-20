<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Database, LockKeyhole, RefreshCw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { useAdminContextStore } from '../../stores/adminContext'
import type { AdminContextMode } from '../../router/adminNavigation'
import { getHttpErrorMessage } from '../../utils/httpError'
import { snapshotStatusLabel } from '../../utils/enumLabels'
import TitleHelp from './TitleHelp.vue'

const props = withDefaults(defineProps<{
  mode: AdminContextMode
  refreshLabel?: string
  compact?: boolean
  showControlLabels?: boolean
}>(), {
  refreshLabel: '',
  compact: false,
  showControlLabels: false,
})
const route = useRoute()
const router = useRouter()
const context = useAdminContextStore()
const invalidContext = ref('')
let syncing = false

const showSnapshot = computed(() => props.mode === 'datasource-snapshot')
const queryDatasourceId = computed(() => Number(route.query.datasourceId) || undefined)
const querySnapshotId = computed(() => Number(route.query.snapshotId) || undefined)
const selectedDatasourceLabel = computed(() => {
  const item = context.datasources.find((datasource) => datasource.id === context.datasourceId)
  return item ? `${item.name} / ${item.databaseName}` : undefined
})

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
      let found = context.snapshots.some((item) => item.id === querySnapshotId.value)
      if (!found) {
        // 首批 50 条之外的快照按 ID 精确读取；失败时保留明确错误，不回退到最新快照。
        try {
          await context.ensureSnapshot(querySnapshotId.value)
          found = context.snapshots.some((item) => item.id === querySnapshotId.value)
        } catch (cause) {
          invalidContext.value = `指定的快照无法加载：${getHttpErrorMessage(
            cause,
            '快照不存在、无权访问或加载失败',
          )}。未切换到其他快照。`
        }
      }
      if (found) {
        if (context.snapshotId !== querySnapshotId.value) {
          context.selectSnapshot(querySnapshotId.value)
        }
      } else {
        context.selectSnapshot(undefined)
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
  <section class="scope-bar" :class="{ 'scope-bar--compact': compact, 'scope-bar--labeled-inline': showControlLabels && !compact }" aria-label="后台业务范围">
    <div class="scope-bar__intro">
      <LockKeyhole v-if="mode === 'locked-resource'" :size="16" />
      <Database v-else :size="16" />
      <div class="scope-bar__title">
        <TitleHelp
          :label="mode === 'datasource-snapshot' ? '数据源与快照范围' : '数据源范围'"
          :text="mode === 'datasource-snapshot' ? '只查看当前数据源的快照和治理对象' : '选择后，当前工作区会使用该数据源上下文'"
        />
        <strong>{{ mode === 'datasource-snapshot' ? '数据源与快照范围' : '数据源范围' }}</strong>
      </div>
    </div>

    <div class="scope-bar__controls">
      <div class="scope-bar__datasource-control">
        <span v-if="compact || showControlLabels" class="scope-bar__control-label">数据源</span>
        <el-select
          :model-value="context.datasourceId"
          :loading="context.loading"
          filterable
          clearable
          placeholder="选择数据源"
          :aria-label="selectedDatasourceLabel ? `数据源：${selectedDatasourceLabel}` : '选择数据源'"
          :title="selectedDatasourceLabel"
          @update:model-value="handleDatasourceChange"
        >
          <el-option
            v-for="item in context.datasources"
            :key="item.id"
            :value="item.id"
            :label="item.name + ' / ' + item.databaseName"
            :title="item.name + ' / ' + item.databaseName"
          />
        </el-select>
      </div>
      <div v-if="showSnapshot" class="scope-bar__snapshot-control">
        <span v-if="compact || showControlLabels" class="scope-bar__control-label">快照</span>
        <el-select
          :model-value="context.snapshotId"
          :loading="context.loading"
          clearable
          :disabled="!context.datasourceId"
          placeholder="选择快照"
          :aria-label="context.currentSnapshot ? `快照：v${context.currentSnapshot.snapshotVersion} · ${snapshotStatusLabel(context.currentSnapshot.status)}` : '选择快照'"
          :title="context.currentSnapshot ? `v${context.currentSnapshot.snapshotVersion} · ${snapshotStatusLabel(context.currentSnapshot.status)}` : undefined"
          @update:model-value="handleSnapshotChange"
        >
          <el-option
            v-for="item in context.snapshots"
            :key="item.id"
            :value="item.id"
            :label="'v' + item.snapshotVersion + ' · ' + snapshotStatusLabel(item.status)"
          />
        </el-select>
      </div>
      <el-tooltip
        v-if="compact || showControlLabels"
        class="scope-bar__refresh-tooltip"
        :content="refreshLabel || '刷新范围'"
        :trigger="['hover', 'focus']"
        placement="top"
      >
        <el-button
          class="scope-bar__refresh-icon"
          :icon="RefreshCw"
          :loading="context.loading"
          :aria-label="refreshLabel || '刷新范围'"
          @click="context.refresh"
        />
      </el-tooltip>
      <el-button v-else :icon="RefreshCw" :loading="context.loading" :aria-label="refreshLabel || '刷新范围'" @click="context.refresh">
        <span v-if="refreshLabel">{{ refreshLabel }}</span>
      </el-button>
    </div>

    <p v-if="invalidContext" class="scope-bar__warning">{{ invalidContext }}</p>
  </section>
</template>

<style scoped>
.scope-bar__title { position: relative; padding-right: 14px; }
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

.scope-bar--compact { display: contents; }
.scope-bar--compact .scope-bar__intro { display: none; }
.scope-bar--compact .scope-bar__controls { display: contents; }
.scope-bar--compact .scope-bar__datasource-control,
.scope-bar--compact .scope-bar__snapshot-control {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.scope-bar__datasource-control,
.scope-bar__snapshot-control {
  display: contents;
}
.scope-bar__control-label {
  flex: 0 0 auto;
  color: var(--do-muted);
  font-size: 12px;
  white-space: nowrap;
}
.scope-bar--compact .scope-bar__datasource-control {
  flex: 0 1 488px;
  min-width: 240px;
  max-width: 488px;
}
.scope-bar--compact .scope-bar__datasource-control :deep(.el-select) {
  flex: 1 1 400px;
  width: 400px;
  min-width: 180px;
  max-width: 440px;
}
.scope-bar--compact .scope-bar__snapshot-control { flex: 0 1 240px; }
.scope-bar--compact .scope-bar__snapshot-control :deep(.el-select) {
  flex: 1 1 180px;
  width: 180px;
  min-width: 160px;
  max-width: 200px;
}
.scope-bar--compact :deep(.el-select__wrapper) { min-height: 36px; }
.scope-bar--compact .scope-bar__refresh-tooltip { display: inline-flex; flex: 0 0 auto; }
.scope-bar--compact .scope-bar__refresh-icon {
  width: 36px;
  padding: 0;
  min-height: 36px;
  border-color: transparent;
  color: var(--do-muted);
  background: transparent;
  box-shadow: none;
}
.scope-bar--compact .scope-bar__refresh-icon:hover {
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
}
.scope-bar--compact .scope-bar__warning { flex: 1 1 100%; margin: 0; }

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
@media (max-width: 1150px) { .scope-bar { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .scope-bar { margin: 14px 18px 0; } .scope-bar__controls { flex-wrap: wrap; } .scope-bar__controls :deep(.el-select) { flex: 1 1 150px; } }
@media (max-width: 760px) {
  .scope-bar--compact .scope-bar__datasource-control { flex: 1 1 100%; max-width: none; }
  .scope-bar--compact .scope-bar__datasource-control :deep(.el-select) { width: auto; min-width: 0; }
}

/* Governance overview: keep the shared range controls in one local, labeled row. */
.scope-bar--labeled-inline {
  grid-template-columns: minmax(190px, .75fr) minmax(560px, 1.8fr);
  gap: 12px 20px;
  margin-top: 0;
  padding: 10px 14px;
}
.scope-bar--labeled-inline .scope-bar__controls {
  display: grid;
  grid-template-columns: minmax(340px, 420px) minmax(160px, 200px) auto;
  gap: 10px 12px;
  min-width: 0;
}
.scope-bar--labeled-inline .scope-bar__datasource-control,
.scope-bar--labeled-inline .scope-bar__snapshot-control {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.scope-bar--labeled-inline .scope-bar__datasource-control :deep(.el-select),
.scope-bar--labeled-inline .scope-bar__snapshot-control :deep(.el-select) {
  width: 100%;
  min-width: 0;
  flex: 1 1 auto;
}
.scope-bar--labeled-inline .scope-bar__datasource-control :deep(.el-select) { max-width: 420px; }
.scope-bar--labeled-inline .scope-bar__snapshot-control :deep(.el-select) { max-width: 200px; }
.scope-bar--labeled-inline :deep(.el-select__selected-item) { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.scope-bar--labeled-inline .scope-bar__refresh-tooltip { display: inline-flex; flex: 0 0 36px; align-self: center; }
.scope-bar--labeled-inline .scope-bar__refresh-icon {
  width: 36px;
  min-width: 36px;
  min-height: 36px;
  padding: 0;
  justify-content: center;
}
@media (max-width: 1150px) {
  .scope-bar--labeled-inline { grid-template-columns: minmax(0, 1fr); }
  .scope-bar--labeled-inline .scope-bar__controls {
    grid-template-columns: minmax(0, 1fr) minmax(160px, 200px) auto;
  }
}
@media (max-width: 760px) {
  .scope-bar--labeled-inline { margin: 0 0 14px; padding: 12px; }
  .scope-bar--labeled-inline .scope-bar__controls { grid-template-columns: minmax(0, 1fr) 36px; gap: 10px; }
  .scope-bar--labeled-inline .scope-bar__datasource-control { grid-column: 1 / -1; }
  .scope-bar--labeled-inline .scope-bar__datasource-control :deep(.el-select) { max-width: none; }
  .scope-bar--labeled-inline .scope-bar__snapshot-control { grid-column: 1; max-width: 240px; }
  .scope-bar--labeled-inline .scope-bar__refresh-tooltip { grid-column: 2; align-self: end; }
}

</style>
