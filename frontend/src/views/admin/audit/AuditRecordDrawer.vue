<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getAuditLogDetail, type AuditLogVO } from '../../../api/admin/audit'
import { useAuthStore } from '../../../stores/auth'
import { useIamS1Store } from '../../../stores/iamS1'
import { getHttpErrorMessage } from '../../../utils/httpError'
import ErrorState from '../../../components/common/ErrorState.vue'
const props = defineProps<{ target: { id: number; source: number } | null }>(),
  emit = defineEmits<{ close: [] }>()
const auth = useAuthStore(),
  iam = useIamS1Store(),
  record = ref<AuditLogVO | null>(null),
  error = ref(''),
  loading = ref(false),
  sql = ref<string | null>(null),
  sqlLoading = ref(false),
  sqlError = ref('')
let ticket = 0,
  sqlTicket = 0,
  alive = true
const scope = computed(() => JSON.stringify([auth.token, iam.snapshot, iam.loaded, iam.errorMessage]))
const allowed = computed(
  () =>
    !!props.target &&
    iam.loaded &&
    !iam.errorMessage &&
    iam.canOnDatasource('audit:view', props.target.source),
)
async function load() {
  const target = props.target
  if (!target || !allowed.value) return
  const id = ++ticket,
    key = scope.value
  record.value = null
  sql.value = null
  error.value = ''
  loading.value = true
  const current = () =>
    alive &&
    id === ticket &&
    key === scope.value &&
    props.target?.id === target.id &&
    props.target?.source === target.source &&
    allowed.value
  try {
    const r = await getAuditLogDetail(target.id)
    if (!current()) return
    if (r.data.id !== target.id || r.data.datasourceId !== target.source)
      throw new Error('详情与所选记录不一致')
    record.value = { ...r.data, sqlText: undefined }
  } catch (e) {
    if (current()) error.value = getHttpErrorMessage(e, '审计记录读取失败')
  } finally {
    if (current()) loading.value = false
  }
}
async function readSql() {
  const target = props.target
  if (!target || !record.value || !allowed.value || sqlLoading.value) return
  const id = ++sqlTicket,
    key = scope.value
  sql.value = null
  sqlError.value = ''
  sqlLoading.value = true
  const current = () =>
    alive &&
    id === sqlTicket &&
    key === scope.value &&
    props.target?.id === target.id &&
    props.target?.source === target.source &&
    allowed.value
  try {
    const r = await getAuditLogDetail(target.id)
    if (!current()) return
    if (r.data.id !== target.id || r.data.datasourceId !== target.source)
      throw new Error('SQL与所选审计记录不一致')
    sql.value = r.data.sqlText || ''
  } catch (e) {
    if (current()) sqlError.value = getHttpErrorMessage(e, '当前审计SQL读取失败')
  } finally {
    if (current()) sqlLoading.value = false
  }
}
function clear() {
  ticket++
  sqlTicket++
  record.value = null
  sql.value = null
  error.value = ''
  sqlError.value = ''
  loading.value = false
  sqlLoading.value = false
}
watch(
  () => props.target,
  () => {
    clear()
    void load()
  },
  { immediate: true },
)
watch(
  scope,
  () => {
    clear()
    emit('close')
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  alive = false
  clear()
})
</script>
<template>
  <el-drawer
    :model-value="!!target"
    title="慢查询对应的审计记录"
    size="min(600px, 100vw)"
    @update:model-value="
      (value: boolean) => {
        if (!value) emit('close')
      }
    "
  >
    <p v-if="!allowed">没有此记录实际源的 audit:view 能力。</p>
    <p v-else-if="loading" role="status">按当前权限读取审计记录…</p>
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <section v-else-if="record" class="record-evidence">
      <h2>{{ record.question }}</h2>
      <p>
        审计 #{{ record.id }} · 内部数字任务 #{{ record.queryTaskId }} · 数据源 #{{ record.datasourceId }}
      </p>
      <dl>
        <dt>任务耗时</dt>
        <dd>{{ record.executionTimeMs ?? '—' }} ms</dd>
        <dt>使用表</dt>
        <dd>{{ record.usedTables || '未提供' }}</dd>
        <dt>使用字段</dt>
        <dd>{{ record.usedFields || '未提供' }}</dd>
        <dt>时间</dt>
        <dd>{{ record.createdAt }}</dd>
        <dt>反馈</dt>
        <dd>{{ record.userFeedback || '未记录' }}</dd>
      </dl>
      <p v-if="record.errorMessage">{{ record.errorMessage }}</p>
      <h3>审计保存的 SQL</h3>
      <p>
        点击后再次读取审计详情，由服务端重验 audit:view 与该记录实际源；不使用内部数字任务 ID 调用问数接口。
      </p>
      <el-button :loading="sqlLoading" @click="readSql">按当前权限读取 SQL</el-button>
      <ErrorState v-if="sqlError" :message="sqlError" @retry="readSql" />
      <pre v-if="sql">{{ sql }}</pre>
      <p v-else-if="sql !== null">该记录没有保存 SQL。</p>
    </section>
  </el-drawer>
</template>
<style scoped>
.record-evidence {
  display: grid;
  gap: 12px;
}
h2 {
  font-size: 21px;
  line-height: 1.7;
}
p,
dt {
  font-size: 12px;
  color: var(--do-muted);
  line-height: 1.8;
}
dl {
  display: grid;
  grid-template-columns: 80px minmax(0, 1fr);
  gap: 14px;
  font-size: 13px;
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
pre {
  padding: 14px;
  background: var(--do-bg);
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
