<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Play } from 'lucide-vue-next'
import {
  triggerQualityCheck,
  listQualityRules,
  updateRuleEnabled,
  type QualityCheckResult,
  type QualityRule
} from '../../../api/admin/governance'
import { listSnapshots } from '../../../api/admin/metadata'

const loading = ref(false)
const checkLoading = ref(false)
const selectedSnapshotId = ref<number | undefined>()
const snapshots = ref<Array<{ id: number; datasourceName?: string; snapshotVersion: number; qualityScore?: number }>>([])
const checkResult = ref<QualityCheckResult | null>(null)
const rules = ref<QualityRule[]>([])

const dimensionLabels: Record<string, string> = {
  COMPLETENESS: '完整性',
  ACCURACY: '准确性',
  CONSISTENCY: '一致性',
  TIMELINESS: '时效性',
  TRACEABILITY: '可追溯性'
}

const severityLabels: Record<string, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' }

async function fetchSnapshots() {
  const res = await listSnapshots({ page: 1, size: 50 })
  snapshots.value = res.data?.records ?? []
}

async function fetchRules() {
  const res = await listQualityRules()
  rules.value = res.data ?? []
}

async function runCheck() {
  if (!selectedSnapshotId.value) {
    ElMessage.warning('请选择快照')
    return
  }
  checkLoading.value = true
  try {
    const res = await triggerQualityCheck(selectedSnapshotId.value)
    checkResult.value = res.data ?? null
    ElMessage.success(`质量校验完成，综合得分 ${res.data?.qualityScore}`)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '校验失败')
  } finally {
    checkLoading.value = false
  }
}

async function toggleRule(rule: QualityRule) {
  const newEnabled = rule.enabled === 1 ? false : true
  await updateRuleEnabled(rule.id, newEnabled)
  rule.enabled = newEnabled ? 1 : 0
}

function scoreColor(score: number): string {
  if (score >= 80) return '#67c23a'
  if (score >= 60) return '#e6a23c'
  return '#f56c6c'
}

onMounted(() => {
  fetchSnapshots()
  fetchRules()
})
</script>

<template>
  <main class="quality-page post-login-page" v-loading="loading">
    <header class="page-header">
      <div>
        <p>元数据治理</p>
        <h1>质量看板</h1>
        <span class="header-subtitle">执行五维质量校验，查看数据源元数据健康状况</span>
      </div>
    </header>

    <!-- 校验触发区 -->
    <section class="check-panel">
      <el-select v-model="selectedSnapshotId" placeholder="选择快照" style="width: 300px">
        <el-option v-for="s in snapshots" :key="s.id" :value="s.id"
                   :label="`#${s.id} v${s.snapshotVersion} ${s.qualityScore != null ? '(' + s.qualityScore + '分)' : ''}`" />
      </el-select>
      <el-button type="primary" :loading="checkLoading" @click="runCheck" style="margin-left: 12px">
        <Play :size="16" style="margin-right: 6px" />执行质量校验
      </el-button>
    </section>

    <!-- 校验结果 -->
    <section v-if="checkResult" class="result-panel">
      <div class="score-card">
        <div class="score-main" :style="{ color: scoreColor(checkResult.qualityScore) }">
          {{ checkResult.qualityScore }}
        </div>
        <div class="score-label">综合质量分</div>
      </div>

      <div class="dimension-grid">
        <div v-for="(score, dim) in checkResult.dimensionScores" :key="dim" class="dim-card">
          <div class="dim-score" :style="{ color: scoreColor(score) }">{{ score }}</div>
          <div class="dim-label">{{ dimensionLabels[dim] || dim }}</div>
        </div>
      </div>

      <div class="issue-summary">
        <span>共 {{ checkResult.totalIssues }} 个问题：</span>
        <el-tag type="danger" size="small">高 {{ checkResult.issueCount.HIGH || 0 }}</el-tag>
        <el-tag type="warning" size="small">中 {{ checkResult.issueCount.MEDIUM || 0 }}</el-tag>
        <el-tag size="small">低 {{ checkResult.issueCount.LOW || 0 }}</el-tag>
      </div>
    </section>

    <!-- 规则列表 -->
    <section class="rules-panel">
      <h3>质量规则</h3>
      <el-table :data="rules" stripe size="small">
        <el-table-column prop="ruleName" label="规则名称" width="200" />
        <el-table-column prop="dimension" label="维度" width="120">
          <template #default="{ row }">{{ dimensionLabels[row.dimension] || row.dimension }}</template>
        </el-table-column>
        <el-table-column prop="severity" label="级别" width="80">
          <template #default="{ row }">
            <el-tag :type="row.severity === 'HIGH' ? 'danger' : row.severity === 'MEDIUM' ? 'warning' : 'info'" size="small">
              {{ severityLabels[row.severity] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="deductionPoints" label="扣分" width="80" />
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled === 1" @change="toggleRule(row)" size="small" />
          </template>
        </el-table-column>
      </el-table>
    </section>
  </main>
</template>

<style scoped>
.quality-page { display: grid; gap: 16px; }
.page-header { }
.page-header p { font-size: 12px; color: var(--do-muted); margin: 0 0 4px; }
.page-header h1 { font-size: 22px; margin: 0; color: var(--do-ink); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }

.check-panel { display: flex; align-items: center; }

.result-panel {
  background: var(--do-surface); border: 1px solid var(--do-line);
  border-radius: 8px; padding: 24px;
}
.score-card { text-align: center; margin-bottom: 20px; }
.score-main { font-size: 48px; font-weight: 700; }
.score-label { font-size: 14px; color: var(--do-muted); }

.dimension-grid { display: flex; gap: 16px; justify-content: center; margin-bottom: 16px; }
.dim-card { text-align: center; padding: 12px 20px; background: var(--do-bg); border-radius: 6px; }
.dim-score { font-size: 24px; font-weight: 600; }
.dim-label { font-size: 12px; color: var(--do-muted); margin-top: 4px; }

.issue-summary { text-align: center; display: flex; align-items: center; justify-content: center; gap: 8px; }

.rules-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 20px; }
.rules-panel h3 { margin: 0 0 16px; font-size: 16px; color: var(--do-ink); }
</style>
