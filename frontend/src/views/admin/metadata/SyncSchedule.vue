<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Clock, RefreshCw } from 'lucide-vue-next'
import { getSyncSchedule, updateSyncSchedule, type SyncScheduleInfo } from '../../../api/admin/system'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAuthStore } from '../../../stores/auth'
import ErrorState from '../../../components/common/ErrorState.vue'

const iam = useIamS1Store()
const auth = useAuthStore()
const canRead = computed(() => iam.loaded && !iam.errorMessage && (iam.hasGlobal('metadata:collect:view') || iam.datasourceCapabilities.some(ds => ds.functionCodes.includes('metadata:collect:view'))))
const canWrite = computed(() => canRead.value && iam.systemAdmin)
let disposed = false
let generation = 0
let editRevision = 0
const loading = ref(false)
const saving = ref(false)
const loaded = ref(false)
const loadError = ref('')
const savedCron = ref('')
const savedEnabled = ref(false)

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

const invalidTime = computed(() => presetMode.value !== 'custom' && !buildCron())
const isDirty = computed(() => loaded.value && (buildCron() !== savedCron.value || schedule.enabled !== savedEnabled.value))
const runningText = computed(() => schedule.running ? '调度已注册' : '调度未注册')

const cronDescription = computed(() => {
  if (presetMode.value === 'daily') {
    return `每天 ${dailyTime.value} 执行`
  } else if (presetMode.value === 'weekly') {
    const day = weekDays.find(d => d.value === weeklyDay.value)
    return `每${day?.label} ${weeklyTime.value} 执行`
  }
  return `自定义表达式: ${customCron.value}`
})

const previewState = computed(() => {
  if (isDirty.value) return schedule.enabled ? '当前设置 · 保存后生效' : '当前设置 · 保存后关闭'
  if (!schedule.enabled) return '已保存配置 · 当前未启用'
  return '已保存配置'
})
const previewText = computed(() => {
  const plan = cronDescription.value
  if (!schedule.enabled) return `自动同步当前关闭；启用并保存后，才会按「${plan}」生效。`
  if (isDirty.value) return `当前设置：${plan}。保存后生效。`
  return `已保存配置：${plan}。`
})

function buildCron(): string {
  if (presetMode.value === 'daily') {
    const [h, m] = (dailyTime.value || '').split(':').map(Number)
    if (!Number.isInteger(h) || !Number.isInteger(m) || h < 0 || h > 23 || m < 0 || m > 59) return ''
    return `0 ${m} ${h} * * ?`
  } else if (presetMode.value === 'weekly') {
    const [h, m] = (weeklyTime.value || '').split(':').map(Number)
    if (!Number.isInteger(h) || !Number.isInteger(m) || h < 0 || h > 23 || m < 0 || m > 59) return ''
    return `0 ${m} ${h} ? * ${weeklyDay.value}`
  }
  return customCron.value
}

function parseCronToUI(cron: string) {
  const parts = cron.trim().split(/\s+/)
  if (parts.length < 6) {
    presetMode.value = 'custom'
    customCron.value = cron
    return
  }
  const [sec, min, hour, dom, month, dow] = parts
  const simpleTime = parts.length === 6 && sec === '0' && month === '*' && /^(?:[0-9]|[1-5][0-9])$/.test(min) && /^(?:[0-9]|1[0-9]|2[0-3])$/.test(hour)

  if (simpleTime && dom === '*' && dow === '?') {
    presetMode.value = 'daily'
    dailyTime.value = `${hour.padStart(2, '0')}:${min.padStart(2, '0')}`
  } else if (simpleTime && dom === '?' && /^[0-6]$/.test(dow)) {
    presetMode.value = 'weekly'
    weeklyDay.value = parseInt(dow)
    weeklyTime.value = `${hour.padStart(2, '0')}:${min.padStart(2, '0')}`
  } else {
    presetMode.value = 'custom'
    customCron.value = cron
  }
}

watch([presetMode, dailyTime, weeklyDay, weeklyTime, customCron, () => schedule.enabled], () => { editRevision++ }, { flush: 'sync' })
async function fetchSchedule() {
  if (saving.value || !canRead.value) return
  const request = ++generation, token = auth.token, revision = editRevision
  const current = () => !disposed && request === generation && token === auth.token && canRead.value
  loading.value = true; loadError.value = ''
  try {
    const res = await getSyncSchedule()
    if (!current()) return
    if (revision !== editRevision) { loadError.value = '刷新期间设置已修改，未覆盖当前输入。请核对后再刷新或保存。'; return }
    if (!res.data) throw new Error('未读取到同步计划配置，请刷新重试。')
    Object.assign(schedule, res.data)
    savedCron.value = res.data.cron; savedEnabled.value = res.data.enabled
    parseCronToUI(res.data.cron); loaded.value = true
  } catch (error) { if (current()) loadError.value = (error as Error)?.message || '获取调度配置失败，请刷新重试。' }
  finally { if (current()) loading.value = false }
}
async function handleSave() {
  if (saving.value || loading.value || !canWrite.value || !loaded.value || loadError.value || invalidTime.value) return
  const request = ++generation, token = auth.token
  const payload = { cron: buildCron(), enabled: schedule.enabled }
  const current = () => !disposed && request === generation && token === auth.token && canWrite.value
  saving.value = true
  try {
    const res = await updateSyncSchedule(payload)
    if (!current()) return
    if (res.code !== 200 || !res.data) throw new Error(res.message || '保存失败')
    Object.assign(schedule, res.data)
    savedCron.value = res.data.cron; savedEnabled.value = res.data.enabled
    parseCronToUI(res.data.cron); ElMessage.success('调度配置已更新')
  } catch (error) { if (current()) ElMessage.error((error as { response?: { data?: { message?: string } } })?.response?.data?.message || (error as Error).message || '保存失败') }
  finally { if (current()) saving.value = false }
}
async function resetAndLoad() {
  generation++; loaded.value = false; loading.value = false; saving.value = false; loadError.value = ''
  await fetchSchedule()
}
watch(() => auth.token, resetAndLoad, { flush: 'sync' })
watch(() => iam.snapshot, resetAndLoad, { flush: 'sync' })
onMounted(async () => { await iam.load(); if (!disposed) await fetchSchedule() })
onBeforeUnmount(() => { disposed = true; generation++ })
</script>

<template>
  <main class="schedule-page post-login-page" v-loading="loading">
    <section class="config-card">
      <div class="config-header">
        <div class="config-scope">
          <el-tag type="info" effect="plain" round>全局配置</el-tag>
          <span>作用于全部启用的数据源</span>
        </div>
        <el-button :icon="RefreshCw" :disabled="saving || loading || !canRead" @click="fetchSchedule">刷新配置</el-button>
      </div>

      <ErrorState v-if="!canRead" message="没有同步计划查看权限。" @retry="iam.load(true)" />
      <ErrorState v-else-if="!loaded && loadError" :message="loadError" @retry="fetchSchedule" />
      <p v-else-if="!loaded" class="schedule-loading">正在读取同步计划…</p>
      <template v-else>
        <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="schedule-load-error" />

        <p class="schedule-access">{{ canWrite ? '修改一次会影响全部启用的数据源；由后台校验并更新全局调度。' : '只读计划。全局自动采集计划仅由受保护的系统管理员维护。' }}</p>
        <fieldset class="schedule-form" :disabled="!canWrite || saving || loading">
          <div class="config-row">
            <label>启用自动同步</label>
            <div class="config-control">
              <el-switch aria-label="启用自动同步" :disabled="!canWrite || saving || loading" v-model="schedule.enabled" />
              <span class="run-state" :class="{ 'is-running': schedule.running }"><i aria-hidden="true"></i>{{ runningText }}</span>
            </div>
          </div>

          <div class="config-row">
            <label>同步频率</label>
            <div class="config-control">
              <el-radio-group v-model="presetMode" :disabled="!canWrite || saving || loading" aria-label="同步频率" class="schedule-frequency">
                <el-radio-button v-for="opt in presetOptions" :key="opt.value" :value="opt.value">
                  {{ opt.label }}
                </el-radio-button>
              </el-radio-group>
            </div>
          </div>

          <div class="config-row" v-if="presetMode === 'daily'">
            <label>执行时间</label>
            <div class="config-control">
              <el-time-select aria-label="每日执行时间" :disabled="!canWrite || saving || loading" v-model="dailyTime" start="00:00" step="00:15" end="23:45"
                              placeholder="选择时间" style="width: 140px" />
            </div>
          </div>

          <div class="config-row" v-if="presetMode === 'weekly'">
            <label>执行日期</label>
            <div class="config-control">
              <el-select aria-label="每周执行日期" :disabled="!canWrite || saving || loading" v-model="weeklyDay" style="width: 100px">
                <el-option v-for="d in weekDays" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
              <el-time-select aria-label="每周执行时间" :disabled="!canWrite || saving || loading" v-model="weeklyTime" start="00:00" step="00:15" end="23:45"
                              placeholder="选择时间" style="width: 140px" />
            </div>
          </div>

          <div class="config-row" v-if="presetMode === 'custom'">
            <label>Cron 表达式</label>
            <div class="config-control">
              <el-input aria-label="Cron 表达式" :disabled="!canWrite || saving || loading" v-model="customCron" placeholder="0 0 2 * * ?" style="width: 240px">
                <template #prefix><Clock :size="14" /></template>
              </el-input>
              <span class="cron-hint">秒 分 时 日 月 周（Spring Cron 6位格式）</span>
            </div>
          </div>

          <div class="config-row config-row--preview">
            <label>计划预览</label>
            <div class="config-control">
              <div class="schedule-preview" :class="{ 'is-off': !schedule.enabled, 'is-dirty': isDirty }">
                <span class="schedule-preview__state">{{ previewState }}</span>
                <p>{{ previewText }}</p>
              </div>
            </div>
          </div>

          <p v-if="invalidTime" role="alert" class="schedule-time-error">请选择有效的执行时间后保存。</p>
          <div v-if="canWrite" class="config-actions">
            <div class="save-panel">
              <el-button type="primary" :loading="saving" :disabled="!loaded || !!loadError || loading || !isDirty || invalidTime" @click="handleSave">保存配置</el-button>
              <span class="save-note">修改保存后生效。</span>
              <span v-if="isDirty" class="save-dirty">当前设置尚未保存</span>
            </div>
          </div>
        </fieldset>
      </template>
    </section>
  </main>
</template>

<style scoped>
.schedule-page { display: block; }
.schedule-time-error { color:var(--do-danger); font-size:13px; }
.schedule-access { font-size:13px; color:var(--do-muted); margin:18px 0; line-height:1.7; }

.config-card {
  background: var(--do-glass-data-bg);
  border: 1px solid var(--do-glass-line);
  border-radius: var(--do-radius-lg);
  box-shadow: var(--do-shadow);
  padding: 18px 20px;
}

.config-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--do-line);
}
.config-scope { display: flex; align-items: center; gap: 8px; color: var(--do-muted); font-size: 12.5px; }
.config-header :deep(.el-button) { min-height: 36px; }

.schedule-loading { margin: 14px 0 0; color: var(--do-muted); font-size: 13px; }
.schedule-load-error { margin-top: 12px; }

.schedule-form { border:0; padding:0; margin-inline:0; width: 100%; max-width: 720px; min-width: 0; margin-top: 18px; }
.config-row {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  align-items: center;
  gap: 10px 16px;
}
.config-row + .config-row { margin-top: 22px; }
.config-row--preview { align-items: start; }
.config-row label {
  width: auto;
  color: var(--do-ink);
  font-size: 13.5px;
  font-weight: 500;
  line-height: 1.5;
}
.config-control { min-width: 0; display: flex; align-items: center; flex-wrap: wrap; gap: 10px 12px; }

.schedule-frequency {
  display: inline-flex;
  padding: 3px;
  border: 1px solid var(--do-line);
  border-radius: 9px;
  background: var(--do-bg);
}
.schedule-frequency :deep(.el-radio-button) { margin: 0; }
.schedule-frequency :deep(.el-radio-button__inner) {
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 14px;
  border: 0;
  border-radius: 7px;
  outline: 0;
  box-shadow: none;
  color: var(--do-muted);
  background: transparent;
  font-size: 12.5px;
}
.schedule-frequency :deep(.el-radio-button__inner:hover) {
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
}
.schedule-frequency :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  color: var(--do-primary-strong);
  background: var(--do-surface);
  box-shadow: 0 1px 4px rgba(34, 72, 126, .1);
}
.schedule-frequency :deep(.el-radio-button:first-child .el-radio-button__inner),
.schedule-frequency :deep(.el-radio-button:last-child .el-radio-button__inner) { border-radius: 7px; }
.schedule-frequency :deep(.el-radio-button__original-radio:focus-visible + .el-radio-button__inner) {
  border: 0;
  outline: 2px solid color-mix(in srgb, var(--do-primary) 32%, transparent);
  outline-offset: 1px;
}

.run-state { display: inline-flex; align-items: center; gap: 6px; color: var(--do-muted); font-size: 12px; line-height: 1.5; }
.run-state i { width: 6px; height: 6px; flex: 0 0 auto; border-radius: 50%; background: currentColor; }
.run-state.is-running { color: color-mix(in srgb, var(--do-success) 72%, var(--do-ink)); }

.cron-hint { margin: 0; color: var(--do-muted); font-size: 11.5px; line-height: 1.5; overflow-wrap: anywhere; }

.schedule-preview {
  width: 100%;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-sm);
  background: var(--do-bg);
}
.schedule-preview__state { display: inline-block; margin-bottom: 4px; color: var(--do-primary-strong); font-size: 11.5px; font-weight: 700; }
.schedule-preview.is-dirty .schedule-preview__state { color: color-mix(in srgb, var(--do-warning) 68%, var(--do-ink)); }
.schedule-preview.is-off .schedule-preview__state { color: var(--do-muted); }
.schedule-preview p { margin: 0; color: var(--do-muted); font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }

.config-actions {
  margin-top: 24px;
  padding-top: 16px;
  padding-left: 126px;
  border-top: 1px solid var(--do-line);
}
.save-panel { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 12px; }
.save-note { color: var(--do-muted); font-size: 12px; line-height: 1.5; }
.save-dirty { color: color-mix(in srgb, var(--do-warning) 68%, var(--do-ink)); font-size: 11.5px; }

@media (max-width: 760px) {
  .schedule-form { margin-top: 14px; }
  .config-row { grid-template-columns: minmax(0, 1fr); gap: 8px; }
  .config-row + .config-row { margin-top: 20px; }
  .config-actions { padding-left: 0; }
  .schedule-frequency { width: 100%; }
  .schedule-frequency :deep(.el-radio-button) { flex: 1 1 0; }
  .schedule-frequency :deep(.el-radio-button__inner) { width: 100%; padding-inline: 8px; }
}
</style>
