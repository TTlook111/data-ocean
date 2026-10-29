<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useGovernanceScope } from '../../composables/useGovernanceScope'
import { trackGovernanceWrite } from '../../composables/governanceWriteTracker'
import { useAdminScrollLock } from '../../composables/useAdminScrollLock'
import GovernanceWriteNotice from './GovernanceWriteNotice.vue'
import { governanceError } from '../../api/admin/governanceWorkflow'
import {
  listGovernanceFeedback,
  reviewGovernanceFeedback,
  verifyGovernanceFeedback,
  type GovernanceFeedbackRow,
} from '../../api/admin/governanceWorkflow'
import GovernanceHistoryDialog from './GovernanceHistoryDialog.vue'
const scope = useGovernanceScope(),
  context = scope.context
const dialogError = ref('')
let dialogSession = 0
const rows = ref<GovernanceFeedbackRow[]>([]),
  total = ref(0),
  page = ref(1),
  loading = ref(false),
  error = ref(''),
  busy = ref(false)
const current = ref<GovernanceFeedbackRow>(),
  reason = ref('UNKNOWN'),
  column = ref<number>(),
  note = ref('')
const historyId = ref<number>(),
  scenario = ref(''),
  expectedResult = ref(''),
  actualResult = ref(''),
  evidenceReference = ref(''),
  verificationConfirmed = ref(false)
const verifying = computed(() => current.value?.followupStatus === 'AWAITING_VERIFICATION')
const needsAcceptance = computed(
  () => current.value?.feedbackType === 'DISLIKE' && reason.value.startsWith('FIELD_'),
)
const canView = computed(() => scope.canSource('governance:field:view'))
const canManage = computed(() => scope.canSource('governance:field:manage'))
const reasons: Record<string, string> = {
  UNKNOWN: '原因待分析',
  FIELD_MEANING: '字段含义',
  FIELD_USAGE: '字段使用条件',
  RELATION: '表关系',
  METRIC: '指标口径',
  SQL: 'SQL 实现',
  PRESENTATION: '结果呈现',
}
const states: Record<string, string> = {
  PENDING_ATTRIBUTION: '待归因',
  PENDING_REVIEW: '待审核',
  APPLIED: '已计入字段置信度',
  REVIEWED: '已确认其他原因',
  REJECTED: '不计入置信度',
}
let epoch = 0,
  operation = 0,
  disposed = false
useAdminScrollLock(() => !!current.value || !!historyId.value)
function sourceCurrent(g: number) {
  return !disposed && g === scope.generation.value && scope.sourceReady.value
}
async function load() {
  const ticket = ++epoch,
    ds = context.datasourceId,
    g = scope.capture()
  rows.value = []
  total.value = 0
  error.value = ''
  current.value = undefined
  historyId.value = undefined
  dialogSession++
  loading.value = false
  if (!canView.value || !ds) return
  loading.value = true
  try {
    const result = await listGovernanceFeedback(ds, page.value)
    if (sourceCurrent(g) && ticket === epoch) {
      rows.value = result.data.records
      total.value = result.data.total
    }
  } catch {
    if (sourceCurrent(g) && ticket === epoch) error.value = '反馈记录读取失败，请重试。'
  } finally {
    if (sourceCurrent(g) && ticket === epoch) loading.value = false
  }
}
function open(row: GovernanceFeedbackRow) {
  if (
    busy.value ||
    !canManage.value ||
    !rows.value.some((x) => x.id === row.id && x.rowVersion === row.rowVersion)
  )
    return
  dialogSession++
  current.value = JSON.parse(JSON.stringify(row))
  reason.value = row.reasonCode
  column.value = row.columnMetaId
  note.value = ''
  scenario.value = ''
  expectedResult.value = ''
  actualResult.value = ''
  evidenceReference.value = ''
  verificationConfirmed.value = false
  dialogError.value = ''
}
function closeDialog(visible: boolean) {
  if (!visible && !busy.value) {
    current.value = undefined
    dialogSession++
  }
}
async function writeFeedback(approved?: boolean) {
  const row = current.value,
    ds = context.datasourceId,
    g = scope.capture(),
    session = dialogSession
  if (busy.value || !row || !ds || !canManage.value) return
  const verify = approved === undefined
  if (
    verify &&
    (!verifying.value ||
      !row.currentRevisionId ||
      !verificationConfirmed.value ||
      !actualResult.value.trim() ||
      !evidenceReference.value.trim())
  )
    return
  if (!verify && (verifying.value || !note.value.trim())) return
  if (
    approved &&
    reason.value.startsWith('FIELD_') &&
    !row.targets.some((t) => t.columnMetaId === column.value)
  ) {
    dialogError.value = '请选择本次实际执行使用的字段，不能归因到其他对象。'
    return
  }
  if (approved && needsAcceptance.value && (!scenario.value.trim() || !expectedResult.value.trim())) {
    dialogError.value = '请保留原问题情景与固定验收标准。'
    return
  }
  const reviewBody = {
    expectedVersion: row.rowVersion,
    approved: !!approved,
    columnMetaId: column.value,
    reasonCode: reason.value,
    note: note.value.trim(),
    scenario: scenario.value.trim(),
    expectedResult: expectedResult.value.trim(),
  }
  const verificationBody = {
    expectedVersion: row.rowVersion,
    targetRevisionId: row.currentRevisionId!,
    actualResult: actualResult.value.trim(),
    evidenceReference: evidenceReference.value.trim(),
    confirmed: verificationConfirmed.value,
  }
  const action = ++operation,
    owned = () =>
      sourceCurrent(g) &&
      session === dialogSession &&
      current.value === row &&
      action === operation &&
      canManage.value
  busy.value = true
  dialogError.value = ''
  try {
    await trackGovernanceWrite(
      scope.auth.token,
      [`governance-feedback:${row.id}`],
      `${verify ? '人工核验原问题' : '反馈归因'} · 记录 #${row.id} / 版本 ${row.rowVersion}`,
      () =>
        verify
          ? verifyGovernanceFeedback(row.id, verificationBody)
          : reviewGovernanceFeedback(row.id, reviewBody),
    )
    if (owned()) {
      ElMessage.success(verify ? '已记录按原情景进行的人工核验' : '归因结果已保存；正式知识修订仍需独立处理')
      await load()
    }
  } catch (cause) {
    if (owned())
      dialogError.value = governanceError(
        cause,
        verify
          ? '原问题仍待验证，实际结果或修复版本需核对；原标准不变'
          : '处理未确认完成，请核对实际使用证据、知识版本和反馈；原依据保留',
      )
  } finally {
    if (!disposed && action === operation) busy.value = false
  }
}
function review(approved: boolean) {
  return writeFeedback(approved)
}
function verifyFix() {
  return writeFeedback()
}
watch(
  scope.generation,
  () => {
    operation++
    busy.value = false
    page.value = 1
    void load()
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  disposed = true
  epoch++
  operation++
  dialogSession++
})
</script>
<template>
  <section class="feedback-queue">
    <GovernanceWriteNotice />
    <div class="feedback-contract">
      <strong>反馈归因 → 正式知识修订 → 按原情景人工核验</strong>
      <p>
        归因到本次实际使用字段和语义修订，才能进入使用证据。问题关闭、版本变化或SQL执行成功，都不能替代原情景核验；审核不会自动修订知识。
      </p>
    </div>
    <div class="feedback-heading">
      <h3>问数反馈归因</h3>
      <el-button :disabled="!canView || busy" @click="load">刷新</el-button>
    </div>
    <p v-if="!canView">请先选择有字段查看权限的数据源。</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <template v-else>
      <el-table :data="rows" v-loading="loading" empty-text="暂无问数反馈。没有评价不代表知识已验证正确。">
        <el-table-column label="评价" width="100">
          <template #default="{ row }">{{ row.feedbackType === 'LIKE' ? '认可' : '有问题' }}</template>
        </el-table-column>
        <el-table-column label="问题方向" width="140">
          <template #default="{ row }">{{ reasons[row.reasonCode] || row.reasonCode }}</template>
        </el-table-column>
        <el-table-column prop="comment" label="用户补充" min-width="220" />
        <el-table-column label="处理结果" min-width="180">
          <template #default="{ row }">{{ states[row.status] || row.status }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="190">
          <template #default="{ row }">
            <el-button
              v-if="row.status.startsWith('PENDING')"
              link
              type="primary"
              :disabled="!canManage || busy"
              @click="open(row)"
            >
              分析与审核
            </el-button>
            <el-button
              v-else-if="row.followupStatus === 'AWAITING_VERIFICATION'"
              link
              type="primary"
              :disabled="!canManage || busy"
              @click="open(row)"
            >
              验证原问题
            </el-button>
            <span v-else>{{ row.followupStatus === 'VERIFIED' ? '人工核验通过' : row.reviewNote }}</span>
            <el-button link :disabled="busy" @click="historyId = row.id">操作记录</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="total > 20"
        v-model:current-page="page"
        :total="total"
        :page-size="20"
        layout="prev,pager,next,total"
        @current-change="load"
      />
    </template>
    <el-dialog
      :model-value="!!current"
      :title="verifying ? '验证原问题修复' : '分析反馈原因'"
      width="min(640px,94vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      @update:model-value="closeDialog"
    >
      <p v-if="dialogError" role="alert">{{ dialogError }}</p>
      <p>执行任务 {{ current?.taskId || '未提供' }} · 原执行语义修订 {{ current?.revisionId ?? '未提供' }}</p>
      <p>
        反馈 #{{ current?.id }} / 版本 {{ current?.rowVersion }} · 当前修复修订
        {{ current?.currentRevisionId ?? '尚未提供' }}
      </p>
      <p>{{ current?.comment || '用户未补充原因，请依据实际查询与业务资料核对。' }}</p>
      <template v-if="verifying">
        <p>原复现情景：{{ current?.resolution?.scenario }}</p>
        <p>原预期结果：{{ current?.resolution?.expectedResult }}</p>
        <el-form :disabled="busy" label-position="top">
          <el-form-item label="按原情景核对后的实际结果">
            <el-input v-model="actualResult" type="textarea" maxlength="4000" />
          </el-form-item>
          <el-form-item label="验收证据记录位置">
            <el-input v-model="evidenceReference" maxlength="2000" />
          </el-form-item>
          <el-checkbox v-model="verificationConfirmed">
            已在平台内核对原问题及实际结果，确认修复；不以版本变化替代验收
          </el-checkbox>
        </el-form>
      </template>
      <el-form v-else :disabled="busy" label-position="top">
        <el-form-item label="确认的问题方向">
          <el-select v-model="reason">
            <el-option v-for="(label, key) in reasons" :key="key" :value="key" :label="label" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="reason.startsWith('FIELD_')" label="实际使用字段">
          <el-select v-model="column">
            <el-option
              v-for="target in current?.targets || []"
              :key="target.columnMetaId"
              :value="target.columnMetaId"
              :label="target.fieldName"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="归因依据与审核意见">
          <el-input v-model="note" type="textarea" :rows="4" maxlength="8000" />
        </el-form-item>
        <template v-if="needsAcceptance">
          <el-form-item label="原问题的复现情景">
            <el-input v-model="scenario" type="textarea" maxlength="2000" />
          </el-form-item>
          <el-form-item label="修复后的预期结果（作为固定验收标准）">
            <el-input v-model="expectedResult" type="textarea" maxlength="4000" />
          </el-form-item>
        </template>
      </el-form>
      <p class="feedback-hint">
        只有能够定位到实际使用字段及其语义版本的已确认反馈才计分。表关系、指标和 SQL 问题分别保留原因。
      </p>
      <template #footer>
        <el-button :disabled="busy" @click="closeDialog(false)">关闭</el-button>
        <el-button
          v-if="verifying"
          type="primary"
          :loading="busy"
          :disabled="!verificationConfirmed || !actualResult.trim() || !evidenceReference.trim()"
          @click="verifyFix"
        >
          确认人工核验
        </el-button>
        <el-button v-if="!verifying" :disabled="busy || !note.trim()" @click="review(false)">
          不计入置信度
        </el-button>
        <el-button
          v-if="!verifying"
          type="primary"
          :loading="busy"
          :disabled="
            !note.trim() ||
            (reason.startsWith('FIELD_') && !column) ||
            (needsAcceptance && (!scenario.trim() || !expectedResult.trim()))
          "
          @click="review(true)"
        >
          确认归因
        </el-button>
      </template>
    </el-dialog>
    <GovernanceHistoryDialog
      v-if="historyId"
      :id="historyId"
      kind="FEEDBACK"
      @close="historyId = undefined"
    />
  </section>
</template>
<style scoped>
.feedback-contract {
  padding: 16px;
  background: var(--do-primary-soft);
  border-radius: 8px;
  font-size: 13px;
}
.feedback-contract p {
  color: var(--do-muted);
  line-height: 1.7;
  margin: 6px 0 0;
}
.feedback-queue {
  padding: 20px;
  border: 1px solid var(--do-line);
  background: var(--do-surface);
  border-radius: 14px;
  display: grid;
  gap: 16px;
  min-width: 0;
}
.feedback-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.feedback-hint {
  font-size: 13px;
  color: var(--do-ink-muted);
}
.feedback-queue :deep(.el-select) {
  width: 100%;
}
</style>
