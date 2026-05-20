<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Clock, RefreshCw } from 'lucide-vue-next'
import { getSyncSchedule, updateSyncSchedule, type SyncScheduleInfo } from '../../../api/admin/system'

const loading = ref(false)
const saving = ref(false)

const schedule = reactive<SyncScheduleInfo>({
  cron: '0 0 2 * * ?',
  enabled: false,
  running: false
})

const presetMode = ref<'daily' | 'weekly' | 'custom'>('daily')

const presetOptions = [
  { label: '每天', value: 'daily' },
  { label: '每周', value: 'weekly' },
  { label: '自定义 Cron', value: 'custom' }
]

const dailyTime = ref('02:00')
const weeklyDay = ref(1)
const weeklyTime = ref('02:00')
const customCron = ref('0 0 2 * * ?')

const weekDays = [
  { label: '周一', value: 1 },
  { label: '周二', value: 2 },
  { label: '周三', value: 3 },
  { label: '周四', value: 4 },
  { label: '周五', value: 5 },
  { label: '周六', value: 6 },
  { label: '周日', value: 0 }
]

const cronDescription = computed(() => {
  if (presetMode.value === 'daily') {
    return `每天 ${dailyTime.value} 执行`
  } else if (presetMode.value === 'weekly') {
    const day = weekDays.find(d => d.value === weeklyDay.value)
    return `每${day?.label} ${weeklyTime.value} 执行`
  }
  return `自定义表达式: ${customCron.value}`
})

function buildCron(): string {
  if (presetMode.value === 'daily') {
    const [h, m] = dailyTime.value.split(':').map(Number)
    return `0 ${m} ${h} * * ?`
  } else if (presetMode.value === 'weekly') {
    const [h, m] = weeklyTime.value.split(':').map(Number)
    return `0 ${m} ${h} ? * ${weeklyDay.value}`
  }
  return customCron.value
}

function parseCronToUI(cron: string) {
  const parts = cron.split(/\s+/)
  if (parts.length < 6) {
    presetMode.value = 'custom'
    customCron.value = cron
    return
  }
  const [, min, hour, dom, , dow] = parts

  if (dom === '*' && dow === '?') {
    presetMode.value = 'daily'
    dailyTime.value = `${hour.padStart(2, '0')}:${min.padStart(2, '0')}`
  } else if (dom === '?' && /^\d$/.test(dow)) {
    presetMode.value = 'weekly'
    weeklyDay.value = parseInt(dow)
    weeklyTime.value = `${hour.padStart(2, '0')}:${min.padStart(2, '0')}`
  } else {
    presetMode.value = 'custom'
    customCron.value = cron
  }
}

async function fetchSchedule() {
  loading.value = true
  try {
    const res = await getSyncSchedule()
    if (res.data) {
      schedule.cron = res.data.cron
      schedule.enabled = res.data.enabled
      schedule.running = res.data.running
      parseCronToUI(res.data.cron)
    }
  } catch (e: any) {
    ElMessage.error('获取调度配置失败')
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  const cron = buildCron()
  saving.value = true
  try {
    const res = await updateSyncSchedule({ cron, enabled: schedule.enabled })
    if (res.code === 200 && res.data) {
      schedule.cron = res.data.cron
      schedule.enabled = res.data.enabled
      schedule.running = res.data.running
      ElMessage.success('调度配置已更新')
    } else {
      ElMessage.error(res.message || '保存失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(fetchSchedule)
</script>

<template>
  <main class="schedule-page post-login-page" v-loading="loading">
    <header class="page-header">
      <div>
        <p>元数据管理</p>
        <h1>同步调度</h1>
        <span class="header-subtitle">配置元数据自动同步的执行频率和时间</span>
      </div>
      <el-button :icon="RefreshCw" @click="fetchSchedule">刷新</el-button>
    </header>

    <section class="config-card">
      <div class="config-row">
        <label>启用自动同步</label>
        <div class="config-control">
          <el-switch v-model="schedule.enabled" />
          <el-tag v-if="schedule.running" type="success" size="small" style="margin-left: 12px">运行中</el-tag>
          <el-tag v-else type="info" size="small" style="margin-left: 12px">未运行</el-tag>
        </div>
      </div>

      <div class="config-row">
        <label>同步频率</label>
        <div class="config-control">
          <el-radio-group v-model="presetMode">
            <el-radio-button v-for="opt in presetOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <div class="config-row" v-if="presetMode === 'daily'">
        <label>执行时间</label>
        <div class="config-control">
          <el-time-select v-model="dailyTime" start="00:00" step="00:15" end="23:45"
                          placeholder="选择时间" style="width: 140px" />
        </div>
      </div>

      <div class="config-row" v-if="presetMode === 'weekly'">
        <label>执行日期</label>
        <div class="config-control">
          <el-select v-model="weeklyDay" style="width: 100px">
            <el-option v-for="d in weekDays" :key="d.value" :label="d.label" :value="d.value" />
          </el-select>
          <el-time-select v-model="weeklyTime" start="00:00" step="00:15" end="23:45"
                          placeholder="选择时间" style="width: 140px; margin-left: 12px" />
        </div>
      </div>

      <div class="config-row" v-if="presetMode === 'custom'">
        <label>Cron 表达式</label>
        <div class="config-control">
          <el-input v-model="customCron" placeholder="0 0 2 * * ?" style="width: 240px">
            <template #prefix><Clock :size="14" /></template>
          </el-input>
          <span class="cron-hint">秒 分 时 日 月 周（Spring Cron 6位格式）</span>
        </div>
      </div>

      <div class="config-row summary-row">
        <label>执行说明</label>
        <div class="config-control">
          <span class="cron-desc">{{ cronDescription }}</span>
        </div>
      </div>

      <div class="config-actions">
        <el-button type="primary" :loading="saving" @click="handleSave">保存配置</el-button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.schedule-page { padding: 24px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 24px; }
.page-header p { font-size: 12px; color: var(--do-muted); margin: 0 0 4px; }
.page-header h1 { font-size: 22px; margin: 0; color: var(--do-ink); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }

.config-card {
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: 8px;
  padding: 24px;
  max-width: 640px;
}

.config-row {
  display: flex;
  align-items: center;
  padding: 16px 0;
  border-bottom: 1px solid var(--do-line);
}
.config-row:last-of-type { border-bottom: none; }
.config-row label {
  width: 120px;
  flex-shrink: 0;
  font-size: 14px;
  color: var(--do-ink);
  font-weight: 500;
}
.config-control { flex: 1; display: flex; align-items: center; }

.cron-hint { margin-left: 12px; font-size: 12px; color: var(--do-muted); }
.cron-desc { font-size: 13px; color: var(--do-muted); }
.summary-row { border-bottom: none; }

.config-actions {
  padding-top: 20px;
  border-top: 1px solid var(--do-line);
  margin-top: 8px;
}
</style>
