<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Activity, Server, Database, Cpu, RefreshCw } from 'lucide-vue-next'
import { http } from '../../../api/http'

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
const healthData = ref<HealthData | null>(null)

async function fetchHealth() {
  loading.value = true
  try {
    const { data } = await http.get<{ code: number; data: HealthData }>('/api/admin/system/health')
    healthData.value = data.data
  } finally {
    loading.value = false
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

onMounted(fetchHealth)
</script>

<template>
  <main class="service-health-page post-login-page" v-loading="loading">
    <header class="page-header">
      <div>
        <p>系统管理</p>
        <h1>服务健康状态</h1>
        <span class="header-subtitle">监控各服务组件的运行状态</span>
      </div>
      <el-button :icon="RefreshCw" @click="fetchHealth">刷新</el-button>
    </header>

    <section v-if="healthData" class="health-grid">
      <!-- 总体状态 -->
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

      <!-- Python AI 服务 -->
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
            最后检查: {{ healthData.pythonService.lastCheckTime }}
          </p>
          <p class="meta" v-if="healthData.pythonService.consecutiveFailures">
            连续失败: {{ healthData.pythonService.consecutiveFailures }} 次
          </p>
          <p class="error-msg" v-if="healthData.pythonService.lastErrorMessage">
            {{ healthData.pythonService.lastErrorMessage }}
          </p>
        </div>
      </div>

      <!-- MySQL -->
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

      <!-- Redis -->
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
  </main>
</template>

<style scoped>
.service-health-page {
  padding: 24px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
}

.page-header p {
  font-size: 12px;
  color: var(--do-muted);
  margin: 0 0 4px;
}

.page-header h1 {
  font-size: 22px;
  font-weight: 600;
  color: var(--do-ink);
  margin: 0 0 4px;
}

.header-subtitle {
  font-size: 13px;
  color: var(--do-muted);
}

.health-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.health-card {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
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
  border-radius: 10px;
  background: var(--do-bg);
  display: flex;
  align-items: center;
  justify-content: center;
}

.card-body h3 {
  font-size: 14px;
  font-weight: 500;
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
</style>
