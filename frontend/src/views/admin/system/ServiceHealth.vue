<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Activity, Cpu, Database, RefreshCw, RotateCcw, Server } from 'lucide-vue-next'
import { http } from '../../../api/http'
import { getPoolDashboard, resetDatasourcePool, type PoolDashboardInfo } from '../../../api/admin/system'

interface ServiceStatus {
  status: string
  description: string
  lastCheckTime: string | null
  consecutiveFailures?: number
  lastErrorMessage?: string | null
}

interface HealthData {
  overall: string
  checkTime: string
  pythonService: ServiceStatus
  mysql: ServiceStatus
  redis: ServiceStatus
}

const loading = ref(false)
const poolLoading = ref(false)
const resettingDatasourceId = ref<number>()
const healthData = ref<HealthData | null>(null)
const poolData = ref<PoolDashboardInfo | null>(null)

async function fetchHealth() {
  loading.value = true
  try {
    const { data } = await http.get<{ code: number; data: HealthData }>('/api/admin/system/health')
    healthData.value = data.data
  } catch {
    healthData.value = null
    ElMessage.error('服务健康状态加载失败')
  } finally {
    loading.value = false
  }
}

async function fetchPools() {
  poolLoading.value = true
  try {
    const result = await getPoolDashboard()
    poolData.value = result.data
  } catch {
    ElMessage.error('连接池状态加载失败')
  } finally {
    poolLoading.value = false
  }
}

async function refreshAll() {
  await Promise.allSettled([fetchHealth(), fetchPools()])
}

async function handleResetPool(datasourceId: number) {
  resettingDatasourceId.value = datasourceId
  try {
    await resetDatasourcePool(datasourceId)
    ElMessage.success('连接池已重置')
    await fetchPools()
  } catch {
    ElMessage.error('连接池重置失败')
  } finally {
    resettingDatasourceId.value = undefined
  }
}

function getStatusColor(status: string): string {
  switch (status) {
    case 'AVAILABLE': return 'var(--do-accent)'
    case 'DEGRADED': return '#e6a23c'
    case 'UNAVAILABLE': return '#f56c6c'
    default: return 'var(--do-muted)'
  }
}

function getStatusLabel(status: string): string {
  switch (status) {
    case 'AVAILABLE': return '正常'
    case 'DEGRADED': return '降级'
    case 'UNAVAILABLE': return '不可用'
    case 'HEALTHY': return '健康'
    default: return '未知'
  }
}

function formatPoolTime(value?: number) {
  if (!value) return '-'
  const date = new Date(value * 1000)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

onMounted(refreshAll)
</script>

<template>
  <main class="service-health-page post-login-page" v-loading="loading">
    <section class="page-actions">
      <el-button :icon="RefreshCw" @click="refreshAll">刷新</el-button>
    </section>

    <section v-if="healthData" class="health-grid">
      <div class="health-card overall-card">
        <div class="card-icon" :style="{ color: getStatusColor(healthData.overall === 'HEALTHY' ? 'AVAILABLE' : 'DEGRADED') }">
          <Activity :size="28" />
        </div>
        <div class="card-body">
          <h3>系统总体状态</h3>
          <span class="status-badge" :style="{ background: getStatusColor(healthData.overall === 'HEALTHY' ? 'AVAILABLE' : 'DEGRADED') }">
            {{ getStatusLabel(healthData.overall) }}
          </span>
        </div>
      </div>

      <div class="health-card">
        <div class="card-icon" :style="{ color: getStatusColor(healthData.pythonService.status) }">
          <Cpu :size="24" />
        </div>
        <div class="card-body">
          <h3>Python AI 服务</h3>
          <span class="status-badge" :style="{ background: getStatusColor(healthData.pythonService.status) }">
            {{ getStatusLabel(healthData.pythonService.status) }}
          </span>
          <p class="meta" v-if="healthData.pythonService.lastCheckTime">
            最后检查 {{ healthData.pythonService.lastCheckTime }}
          </p>
          <p class="meta" v-if="healthData.pythonService.consecutiveFailures">
            连续失败 {{ healthData.pythonService.consecutiveFailures }} 次
          </p>
          <p class="error-msg" v-if="healthData.pythonService.lastErrorMessage">
            {{ healthData.pythonService.lastErrorMessage }}
          </p>
        </div>
      </div>

      <div class="health-card">
        <div class="card-icon" :style="{ color: getStatusColor(healthData.mysql.status) }">
          <Database :size="24" />
        </div>
        <div class="card-body">
          <h3>MySQL 数据库</h3>
          <span class="status-badge" :style="{ background: getStatusColor(healthData.mysql.status) }">
            {{ getStatusLabel(healthData.mysql.status) }}
          </span>
        </div>
      </div>

      <div class="health-card">
        <div class="card-icon" :style="{ color: getStatusColor(healthData.redis.status) }">
          <Server :size="24" />
        </div>
        <div class="card-body">
          <h3>Redis 缓存</h3>
          <span class="status-badge" :style="{ background: getStatusColor(healthData.redis.status) }">
            {{ getStatusLabel(healthData.redis.status) }}
          </span>
        </div>
      </div>
    </section>

    <el-empty v-else-if="!loading" description="暂无健康状态数据" />

    <section class="pool-section">
      <div class="section-header">
        <div>
          <h2>SQL 连接池状态</h2>
          <p>当前活跃连接池 {{ poolData?.activePools ?? 0 }} 个</p>
        </div>
        <el-button :icon="RefreshCw" :loading="poolLoading" @click="fetchPools">刷新连接池</el-button>
      </div>

      <el-table
        v-loading="poolLoading"
        :data="poolData?.pools || []"
        border
        row-key="datasourceId"
        empty-text="暂无活跃连接池"
      >
        <el-table-column prop="datasourceId" label="数据源 ID" min-width="120" />
        <el-table-column prop="poolSize" label="连接数" min-width="100" />
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">
            {{ formatPoolTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="最后使用" min-width="160">
          <template #default="{ row }">
            {{ formatPoolTime(row.lastUsedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :icon="RotateCcw"
              :loading="resettingDatasourceId === row.datasourceId"
              @click="handleResetPool(row.datasourceId)"
            >
              重置
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </main>
</template>

<style scoped>
.service-health-page {
  display: grid;
  gap: 18px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.section-header p {
  font-size: 13px;
  color: var(--do-muted);
}

.health-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.health-card,
.pool-section {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.health-card {
  padding: 20px;
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.overall-card {
  grid-column: 1 / -1;
}

.card-icon {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  border-radius: 8px;
  background: var(--do-bg);
  display: flex;
  align-items: center;
  justify-content: center;
}

.card-body h3,
.section-header h2 {
  font-size: 14px;
  font-weight: 600;
  color: var(--do-ink);
  margin: 0 0 8px;
}

.status-badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 12px;
  font-size: 12px;
  color: #fff;
  font-weight: 500;
}

.meta {
  font-size: 12px;
  color: var(--do-muted);
  margin: 6px 0 0;
}

.error-msg {
  font-size: 12px;
  color: #f56c6c;
  margin: 4px 0 0;
  word-break: break-all;
}

.pool-section {
  padding: 16px;
}

.section-header {
  margin-bottom: 12px;
}

.section-header p {
  margin: 0;
}
</style>
