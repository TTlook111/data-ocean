<script setup lang="ts">
/**
 * 元数据目录搜索页面
 *
 * 支持全文搜索实体（表/列/术语/标签），按类型和数据源过滤。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from 'lucide-vue-next'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import { listMyDatasources, type UserDatasourceItem } from '../../../api/datasource'
import { searchCatalog, type MetadataEntityItem } from '../../../api/admin/catalog'

const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)

const query = ref('')
const entityType = ref('')
const datasourceId = ref<number | null>(null)
const results = ref<MetadataEntityItem[]>([])
const loading = ref(false)
const searched = ref(false)
const datasources = ref<UserDatasourceItem[]>([])

const ENTITY_TYPE_LABELS: Record<string, string> = {
  DATASOURCE: '数据源',
  TABLE: '表',
  COLUMN: '列',
  GLOSSARY_TERM: '术语',
  TAG: '标签',
}

async function loadDatasources() {
  try {
    const res = await listMyDatasources()
    datasources.value = res.data ?? []
  } catch {
    // ignore
  }
}

async function handleSearch() {
  if (!query.value.trim()) {
    ElMessage.warning('请输入搜索关键词')
    return
  }
  loading.value = true
  searched.value = true
  try {
    const res = await searchCatalog({
      q: query.value.trim(),
      type: entityType.value || undefined,
      datasourceId: datasourceId.value || undefined,
      page: 1,
      size: 50,
    })
    results.value = res.data ?? []
  } catch {
    ElMessage.error('搜索失败')
  } finally {
    loading.value = false
  }
}

function highlightMatch(text: string | undefined, q: string): string {
  if (!text || !q) return text || ''
  const regex = new RegExp(`(${q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi')
  return text.replace(regex, '<mark>$1</mark>')
}

loadDatasources()
withContext(() => {
  reveal('.search-bar, .results-panel', { y: 14, stagger: 0.06 })
})
</script>

<template>
  <main ref="pageRef" class="catalog-page post-login-page">

    <section class="search-bar">
      <el-input
        v-model="query"
        placeholder="输入关键词搜索..."
        clearable
        style="flex: 1"
        @keyup.enter="handleSearch"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-select v-model="entityType" placeholder="实体类型" clearable style="width: 140px">
        <el-option label="全部" value="" />
        <el-option label="表" value="TABLE" />
        <el-option label="列" value="COLUMN" />
        <el-option label="术语" value="GLOSSARY_TERM" />
        <el-option label="标签" value="TAG" />
      </el-select>
      <el-select
        v-model="datasourceId"
        placeholder="数据源"
        clearable
        filterable
        style="width: 200px"
      >
        <el-option
          v-for="ds in datasources"
          :key="ds.id"
          :label="ds.name"
          :value="ds.id"
        />
      </el-select>
      <el-button type="primary" :icon="Search" :loading="loading" @click="handleSearch">
        搜索
      </el-button>
    </section>

    <section class="results-panel">
      <div v-if="!searched" class="empty-hint">
        输入关键词开始搜索
      </div>
      <div v-else-if="results.length === 0 && !loading" class="empty-hint">
        未找到匹配结果
      </div>
      <template v-else>
        <div class="result-count">共 {{ results.length }} 条结果</div>
        <div v-for="item in results" :key="item.id" class="result-card">
          <div class="result-header">
            <el-tag size="small" :type="item.entityType === 'TABLE' ? 'primary' : item.entityType === 'COLUMN' ? 'success' : 'warning'">
              {{ ENTITY_TYPE_LABELS[item.entityType] || item.entityType }}
            </el-tag>
            <span class="result-name" v-html="highlightMatch(item.name, query)" />
            <span v-if="item.displayName && item.displayName !== item.name" class="result-display-name">
              ({{ item.displayName }})
            </span>
          </div>
          <div class="result-fqn">{{ item.fqn }}</div>
          <div v-if="item.description" class="result-desc" v-html="highlightMatch(item.description, query)" />
        </div>
      </template>
    </section>
  </main>
</template>

<style scoped>
.catalog-page {
  display: grid;
  gap: 16px;
  padding: 24px;
}




.search-bar {
  display: flex;
  gap: 12px;
  align-items: center;
}

.results-panel {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  padding: 16px;
  background: var(--do-surface);
  min-height: 300px;
}

.result-count {
  font-size: 13px;
  color: var(--do-muted);
  margin-bottom: 12px;
}

.result-card {
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 6px;
  margin-bottom: 8px;
}

.result-card:hover {
  border-color: var(--do-primary, #4d8fdc);
}

.result-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.result-name {
  font-weight: 500;
  color: var(--do-ink);
}

.result-name :deep(mark) {
  background: #fff3cd;
  padding: 0 2px;
  border-radius: 2px;
}

.result-display-name {
  font-size: 13px;
  color: var(--do-muted);
}

.result-fqn {
  font-size: 12px;
  color: var(--do-muted);
  font-family: monospace;
  margin-bottom: 4px;
}

.result-desc {
  font-size: 13px;
  color: var(--do-ink);
}

.result-desc :deep(mark) {
  background: #fff3cd;
  padding: 0 2px;
  border-radius: 2px;
}

.empty-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 200px;
  color: var(--do-muted);
  font-size: 14px;
}
</style>
