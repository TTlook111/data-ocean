<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { iamS1GetDatasourceReadiness, listIamS1QueryResourceTables, listIamS1QueryResourceColumns, type IamS1ColumnOption, type IamS1TableOption } from '../../api/iamS1'
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
const props = defineProps<{ datasourceId?: number; datasourceName?: string }>()
const auth = useAuthStore(), iam = useIamS1Store()
const visible = ref(false), loading = ref(false), error = ref(''), notice = ref('')
const snapshotId = ref<number>(), tables = ref<Array<IamS1TableOption & { columns: IamS1ColumnOption[] }>>([])
let generation = 0, alive = true
function clear() { generation++; tables.value = []; snapshotId.value = undefined; error.value = ''; notice.value = ''; loading.value = false }
function close() { visible.value = false; clear() }
async function readScope() {
  clear()
  const source = props.datasourceId, identity = auth.token, ticket = generation
  if (!source || !identity || !iam.loaded || !iam.queryUse) { error.value = '当前账号无权读取可问范围'; return }
  const current = () => alive && visible.value && ticket === generation && props.datasourceId === source && auth.token === identity && iam.loaded && iam.queryUse
  loading.value = true
  try {
    // Always re-read the user's current readiness. Never use APPLY or admin schema as fallback.
    const readiness = (await iamS1GetDatasourceReadiness(source)).data
    if (!current()) return
    if (readiness.datasourceId !== source) throw new Error('来源不一致')
    const snapshot = readiness.publishedSnapshotId
    if (!snapshot) { notice.value = '当前来源尚无可核对的已发布快照，暂不能展示可问范围。'; return }
    notice.value = readiness.askable ? '' : '当前来源尚未就绪；下方仅为授权目录，不代表现在可以发送查询。'
    const rows = (await listIamS1QueryResourceTables(source, snapshot, 'QUERY')).data
    if (!current()) return
    if (!Array.isArray(rows) || rows.some(row => row.datasourceId !== source || row.metadataSnapshotId !== snapshot)) throw new Error('目录来源不一致')
    const result = await Promise.all(rows.filter(row => row.selectable === true).map(async row => {
      const response = await listIamS1QueryResourceColumns(source, snapshot, row.tableName, 'QUERY')
      if (!Array.isArray(response.data)) throw new Error('字段目录不可用')
      return { ...row, columns: response.data.filter(column => column.selectable === true) }
    }))
    if (!current()) return
    snapshotId.value = snapshot
    tables.value = result.filter(row => row.columns.length > 0)
  } catch (cause) {
    if (!current()) return
    tables.value = []; snapshotId.value = undefined
    error.value = (cause as { response?: { status?: number } })?.response?.status === 403
      ? '当前账号无权读取可问范围，请由组织指定管理员核对账号能力与授权。'
      : '范围读取失败，请重新读取；未将未知状态视为没有数据或已就绪。'
  } finally { if (current()) loading.value = false }
}
function open() { visible.value = true; void readScope() }
watch(() => [props.datasourceId, auth.token, iam.snapshot, iam.loaded, iam.queryUse], close, { flush: 'sync' })
watch(visible, value => { if (!value) clear() }, { flush: 'sync' })
onBeforeUnmount(() => { alive = false; clear() })
</script>
<template>
  <span class="query-scope-entry"><button type="button" class="query-scope-trigger" :disabled="!datasourceId" @click="open">可问范围</button></span>
  <el-dialog v-model="visible" title="当前账号的可问范围" width="min(720px,94vw)" class="query-scope-dialog" append-to-body destroy-on-close @close="close">
    <p class="scope-intro"><strong>{{ datasourceName || '当前来源' }}</strong> · 仅展示当前账号在已发布快照中的授权字段。行范围、字段用途与脱敏以实际执行校验为准；可见字段不代表可任意聚合。</p>
    <p v-if="loading" role="status">正在读取当前授权与发布来源…</p>
    <div v-else-if="error" role="alert"><p>{{ error }}</p><button type="button" class="scope-retry" @click="readScope">重新读取范围</button></div>
    <template v-else>
      <p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="snapshotId" class="scope-boundary">当前已发布来源 #{{ snapshotId }}。业务含义、单位和统计口径仍需以已审核知识及本次问题为准；原始注释仅供核对。</p>
      <p v-if="snapshotId && !tables.length" role="status">当前授权范围内暂无可问字段。可向组织指定管理员提供账号、期望业务主题与所需范围，请其核对。</p>
      <article v-for="table in tables" :key="table.tableName" class="scope-table">
        <h3 translate="no">{{ table.tableName }} <small>{{ table.columns.length }} 个授权字段</small></h3>
        <ul><li v-for="column in table.columns" :key="column.columnMetaId"><strong translate="no">{{ column.columnName }}</strong><span>{{ column.dataType || '类型未提供' }}</span><span v-if="column.protectionLevel === 'MASKED'">结果受脱敏保护</span><details v-if="column.columnComment"><summary>原始字段说明</summary><p>{{ column.columnComment }}</p></details></li></ul>
        <details v-if="table.tableComment"><summary>原始表说明</summary><p>{{ table.tableComment }}</p></details>
      </article>
      <p class="scope-boundary">提问时说明要了解的指标、时间范围与分组方式；口径缺失时先补充信息。历史结果隐藏后，可从这里核对当前范围，再重新提问。</p>
      <button type="button" class="scope-retry" :disabled="loading" @click="readScope">重新读取范围</button>
    </template>
  </el-dialog>
</template>
<style scoped>
.query-scope-entry { display:inline-flex; vertical-align:middle; }
.query-scope-trigger,.scope-retry { min-height:36px; border:1px solid var(--do-line); border-radius:7px; background:var(--do-surface); color:var(--do-primary-strong); padding:4px 10px; font:inherit; font-size:12px; cursor:pointer; }
.query-scope-trigger { margin-left:6px; }
button:disabled { opacity:.6; cursor:not-allowed; }
button:focus-visible,summary:focus-visible { outline:2px solid var(--do-primary); outline-offset:2px; }
.scope-intro,.scope-boundary { color:var(--do-muted); line-height:1.7; font-size:13px; }
.scope-table { padding:14px 0; border-top:1px solid var(--do-line); overflow-wrap:anywhere; }
.scope-table h3 { margin:0 0 12px; font-size:15px; }
.scope-table small { margin-left:8px; color:var(--do-muted); font-size:12px; font-weight:400; }
.scope-table ul { list-style:none; padding:0; margin:0; display:grid; gap:10px; }
.scope-table li { display:flex; flex-wrap:wrap; gap:8px; font-size:13px; }
.scope-table li > span { color:var(--do-muted); }
.scope-table details { flex-basis:100%; color:var(--do-muted); font-size:12px; }
.scope-table > details { margin-top:12px; }
.scope-table summary { cursor:pointer; min-height:28px; }
.scope-table details p { white-space:pre-wrap; line-height:1.7; }
</style>
