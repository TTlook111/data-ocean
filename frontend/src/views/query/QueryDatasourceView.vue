<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Database, RefreshCw } from 'lucide-vue-next'
import { listMyDatasources, type UserDatasourceItem } from '../../api/datasource'

const loading = ref(false)
const datasources = ref<UserDatasourceItem[]>([])
const selectedId = ref<number>()

async function fetchDatasources() {
  loading.value = true
  try {
    const result = await listMyDatasources()
    datasources.value = result.data
    selectedId.value = result.data[0]?.id
  } finally {
    loading.value = false
  }
}

onMounted(fetchDatasources)
</script>

<template>
  <main class="query-page post-login-page">
    <section class="query-shell">
      <header class="page-header">
        <div>
          <p>问答端</p>
          <h1>选择本次查询的数据源</h1>
          <span class="header-subtitle">每次自然语言查询限定在一个已授权、已启用的数据源内执行。</span>
        </div>
        <el-button :loading="loading" @click="fetchDatasources">
          <RefreshCw :size="16" />
          刷新
        </el-button>
      </header>

      <el-empty
        v-if="!loading && datasources.length === 0"
        description="暂无可用数据源，请联系管理员授权"
      />

      <section v-else class="datasource-grid" v-loading="loading">
        <button
          v-for="datasource in datasources"
          :key="datasource.id"
          type="button"
          class="datasource-option"
          :class="{ selected: datasource.id === selectedId }"
          @click="selectedId = datasource.id"
        >
          <span class="icon-box">
            <Database :size="20" />
          </span>
          <span>
            <strong>{{ datasource.name }}</strong>
            <small>{{ datasource.databaseName }}</small>
            <em>{{ datasource.description || '已授权的业务数据源' }}</em>
          </span>
        </button>
      </section>

      <footer class="query-footer">
        <el-input
          placeholder="请输入自然语言问题"
          disabled
        />
        <el-button type="primary" disabled>开始查询</el-button>
      </footer>
    </section>
  </main>
</template>

<style scoped>
.query-page {
  display: grid;
}

.query-shell {
  display: grid;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-header p {
  margin: 0 0 6px;
  color: var(--do-primary);
  font-weight: 800;
}

.page-header h1 {
  margin: 0;
  font-size: 24px;
  color: var(--do-ink);
}

.datasource-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 14px;
  min-height: 190px;
}

.datasource-option {
  display: grid;
  grid-template-columns: 44px 1fr;
  gap: 14px;
  min-height: 132px;
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  color: var(--do-ink);
  text-align: left;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
  cursor: pointer;
}

.datasource-option.selected {
  border-color: var(--do-primary);
  box-shadow: 0 0 0 3px rgba(77, 143, 220, 0.16), var(--do-shadow);
}

.icon-box {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: var(--do-primary);
  background: var(--do-primary-soft);
}

.datasource-option strong,
.datasource-option small,
.datasource-option em {
  display: block;
}

.datasource-option strong {
  margin-bottom: 5px;
  font-size: 17px;
}

.datasource-option small {
  color: var(--do-muted);
  font-size: 13px;
}

.datasource-option em {
  margin-top: 12px;
  color: var(--do-muted);
  font-style: normal;
  line-height: 1.5;
}

.query-footer {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

</style>
