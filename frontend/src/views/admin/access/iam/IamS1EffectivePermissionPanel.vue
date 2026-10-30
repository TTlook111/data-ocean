<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import EmptyState from '../../../../components/common/EmptyState.vue'
import ErrorState from '../../../../components/common/ErrorState.vue'
import LoadingState from '../../../../components/common/LoadingState.vue'
import { useIamWorkspaceSession } from '../../../../composables/useIamWorkspaceSession'
import {
  listIamS1Columns,
  listIamS1Datasources,
  listIamS1QueryResourceColumns,
  listIamS1QueryResourceDatasources,
  listIamS1QueryResourceSnapshots,
  listIamS1QueryResourceTables,
  listIamS1Snapshots,
  listIamS1Subjects,
  listIamS1Tables,
  previewIamS1EffectivePermission,
  type IamS1AuthorizationSnapshot,
  type IamS1ColumnOption,
  type IamS1SubjectOption,
  type IamS1TableOption,
  type IamS1SnapshotOption,
} from '../../../../api/iamS1'
const props = defineProps<{ defaultDatasourceId?: number; invalidDefaultDatasource?: boolean }>()
const session = useIamWorkspaceSession(),
  iam = session.iam,
  auth = session.auth
const mode = ref<'self' | 'other'>('self'),
  datasources = ref<Array<{ id: number; name: string }>>([]),
  datasourceId = ref<number>(),
  snapshots = ref<IamS1SnapshotOption[]>([]),
  snapshotId = ref<number>(),
  tables = ref<IamS1TableOption[]>([]),
  tableName = ref<string>(),
  columns = ref<IamS1ColumnOption[]>([]),
  selectedColumns = ref<string[]>([]),
  users = ref<IamS1SubjectOption[]>([]),
  targetUserId = ref<number>(),
  snapshot = ref<IamS1AuthorizationSnapshot | null>(null)
const error = ref(''),
  userError = ref(''),
  previewError = ref(''),
  loading = ref(false),
  loadingUsers = ref(false),
  previewing = ref(false)
const selfUserId = computed(() => auth.currentUser?.id ?? auth.user?.userId)
const canSelf = computed(() => session.ready.value && (iam.queryUse || iam.systemAdmin))
const canOthers = computed(
  () =>
    session.ready.value &&
    (iam.hasGlobal('security:effective:view') ||
      iam.datasourceCapabilities.some((x) => session.can('security:effective:view', x.datasourceId))),
)
const previewingSelf = computed(() => mode.value === 'self')
const sourceKey = computed(() =>
  JSON.stringify([session.key.value, mode.value, datasourceId.value, props.invalidDefaultDatasource]),
)
const objectKey = computed(() => JSON.stringify([sourceKey.value, snapshotId.value, tableName.value]))
const resultKey = computed(() =>
  JSON.stringify([
    objectKey.value,
    previewingSelf.value ? selfUserId.value : targetUserId.value,
    selectedColumns.value,
  ]),
)
const current = (k: string) => session.alive() && session.ready.value && k === objectKey.value
const canUseSource = computed(
  () =>
    !props.invalidDefaultDatasource &&
    !!datasourceId.value &&
    datasources.value.some((x) => x.id === datasourceId.value) &&
    (previewingSelf.value ? canSelf.value : session.can('security:effective:view', datasourceId.value)),
)
const effectiveTarget = computed(() => (previewingSelf.value ? selfUserId.value : targetUserId.value))
let sourcesRequest = 0,
  snapshotsRequest = 0,
  tablesRequest = 0,
  columnsRequest = 0,
  usersRequest = 0,
  previewRequest = 0
watch(
  sourceKey,
  () => {
    snapshotsRequest++
    tablesRequest++
    columnsRequest++
    usersRequest++
    snapshots.value = []
    snapshotId.value = undefined
    tables.value = []
    tableName.value = undefined
    columns.value = []
    selectedColumns.value = []
    users.value = []
    targetUserId.value = undefined
    error.value = ''
    userError.value = ''
    loading.value = false
    loadingUsers.value = false
  },
  { flush: 'sync' },
)
watch(
  snapshotId,
  () => {
    tablesRequest++
    columnsRequest++
    tables.value = []
    tableName.value = undefined
    columns.value = []
    selectedColumns.value = []
  },
  { flush: 'sync' },
)
watch(
  tableName,
  () => {
    columnsRequest++
    columns.value = []
    selectedColumns.value = []
  },
  { flush: 'sync' },
)
watch(
  resultKey,
  () => {
    previewRequest++
    snapshot.value = null
    previewError.value = ''
    previewing.value = false
  },
  { flush: 'sync' },
)
async function initialize() {
  mode.value = canSelf.value ? 'self' : 'other'
  await loadDatasources()
}
async function loadDatasources() {
  const r = ++sourcesRequest,
    g = session.capture(),
    m = mode.value
  datasources.value = []
  datasourceId.value = undefined
  error.value = ''
  if (!session.ready.value || (m === 'self' ? !canSelf.value : !canOthers.value)) return
  loading.value = true
  try {
    const rows =
      m === 'self'
        ? (await listIamS1QueryResourceDatasources('QUERY')).data || []
        : iam.systemAdmin
          ? (await listIamS1Datasources()).data || []
          : iam.datasourceCapabilities
              .map((x) => ({ id: x.datasourceId, name: x.datasourceName || `源 #${x.datasourceId}` }))
              .filter((x) => session.can('security:effective:view', x.id))
    if (!session.current(g) || r !== sourcesRequest || m !== mode.value) return
    datasources.value = rows
    if (props.invalidDefaultDatasource) throw new Error('指定数据源标识无效，未选择其他范围。')
    if (props.defaultDatasourceId && !rows.some((x) => x.id === props.defaultDatasourceId))
      throw new Error('指定数据源不在当前用途的可用范围，未选择其他源。')
    datasourceId.value = props.defaultDatasourceId || rows[0]?.id
    await onDatasourceChange()
  } catch (e) {
    if (session.current(g) && r === sourcesRequest && m === mode.value)
      error.value = e instanceof Error ? e.message : '读取当前用途数据源失败'
  } finally {
    if (session.current(g) && r === sourcesRequest && m === mode.value) loading.value = false
  }
}
async function loadUsers() {
  const k = sourceKey.value,
    r = ++usersRequest,
    ds = datasourceId.value
  userError.value = ''
  if (previewingSelf.value || !ds || !canUseSource.value) return
  loadingUsers.value = true
  try {
    const result = await listIamS1Subjects('EFFECTIVE', ds, 'USER')
    if (!session.alive() || k !== sourceKey.value || r !== usersRequest) return
    users.value = (result.data || []).filter((x) => x.subjectType === 'USER' && x.id !== selfUserId.value)
  } catch (e) {
    if (session.alive() && k === sourceKey.value && r === usersRequest)
      userError.value = e instanceof Error ? e.message : '查看他人对象目录读取失败'
  } finally {
    if (session.alive() && k === sourceKey.value && r === usersRequest) loadingUsers.value = false
  }
}
async function loadSnapshots() {
  const ds = datasourceId.value,
    k = sourceKey.value,
    r = ++snapshotsRequest
  error.value = ''
  if (!ds || !canUseSource.value) return
  if (!previewingSelf.value && !session.can('security:permission:view', ds)) {
    error.value = '现有管理资源目录需要同源 security:permission:view；查看他人的功能仍独立，未自动补权。'
    return
  }
  loading.value = true
  try {
    const result = previewingSelf.value
      ? await listIamS1QueryResourceSnapshots(ds, 'QUERY')
      : await listIamS1Snapshots(ds)
    if (!session.alive() || k !== sourceKey.value || r !== snapshotsRequest) return
    snapshots.value = (result.data || []).filter((x) => x.datasourceId === ds && x.status === 'PUBLISHED')
    snapshotId.value = snapshots.value[0]?.id
    await loadTables()
  } catch (e) {
    if (session.alive() && k === sourceKey.value && r === snapshotsRequest)
      error.value = e instanceof Error ? e.message : '读取发布快照失败'
  } finally {
    if (session.alive() && k === sourceKey.value && r === snapshotsRequest) loading.value = false
  }
}
async function loadTables() {
  const ds = datasourceId.value,
    snap = snapshotId.value,
    k = objectKey.value,
    r = ++tablesRequest
  if (!ds || !snap || !canUseSource.value) return
  error.value = ''
  loading.value = true
  try {
    const result = previewingSelf.value
      ? await listIamS1QueryResourceTables(ds, snap, 'QUERY')
      : await listIamS1Tables(ds, snap)
    if (!current(k) || r !== tablesRequest) return
    tables.value = (result.data || []).filter((x) => x.datasourceId === ds && x.metadataSnapshotId === snap)
  } catch (e) {
    if (current(k) && r === tablesRequest) error.value = e instanceof Error ? e.message : '读取表失败'
  } finally {
    if (current(k) && r === tablesRequest) loading.value = false
  }
}
async function loadColumns() {
  const ds = datasourceId.value,
    snap = snapshotId.value,
    table = tableName.value,
    k = objectKey.value,
    r = ++columnsRequest
  if (!ds || !snap || !table || !canUseSource.value || !tables.value.some((x) => x.tableName === table))
    return
  error.value = ''
  loading.value = true
  try {
    const result = previewingSelf.value
      ? await listIamS1QueryResourceColumns(ds, snap, table, 'QUERY')
      : await listIamS1Columns(ds, snap, table)
    if (!current(k) || r !== columnsRequest) return
    columns.value = result.data || []
  } catch (e) {
    if (current(k) && r === columnsRequest) error.value = e instanceof Error ? e.message : '读取字段失败'
  } finally {
    if (current(k) && r === columnsRequest) loading.value = false
  }
}
async function onDatasourceChange() {
  await Promise.all([loadUsers(), loadSnapshots()])
}
async function preview() {
  if (previewing.value || !canUseSource.value) return
  const ds = datasourceId.value,
    snap = snapshotId.value,
    table = tableName.value,
    target = effectiveTarget.value
  previewError.value = ''
  if (
    !ds ||
    !snap ||
    !table ||
    !target ||
    !selectedColumns.value.length ||
    !snapshots.value.some((x) => x.id === snap) ||
    !tables.value.some((x) => x.tableName === table) ||
    selectedColumns.value.some((c) => !columns.value.some((x) => x.columnName === c)) ||
    (!previewingSelf.value && !users.value.some((x) => x.id === target))
  ) {
    previewError.value = '请核对精确用户、发布快照、表和字段；字段未选不表示全部。'
    return
  }
  const payload = {
      userId: target,
      datasourceId: ds,
      activeMetadataSnapshotId: snap,
      tables: [{ tableName: table, referencedColumns: [...selectedColumns.value] }],
    },
    k = resultKey.value,
    r = ++previewRequest
  previewing.value = true
  snapshot.value = null
  try {
    const result = await previewIamS1EffectivePermission(payload)
    if (!session.alive() || k !== resultKey.value || r !== previewRequest) return
    const data = result.data
    if (
      !data ||
      typeof data.allowed !== 'boolean' ||
      data.userId !== target ||
      data.datasourceId !== ds ||
      data.activeMetadataSnapshotId !== snap ||
      !Array.isArray(data.tables) ||
      data.tables.some(
        (x) =>
          x.tableName !== table ||
          typeof x.allowed !== 'boolean' ||
          !Array.isArray(x.allowedColumns) ||
          !x.allowedColumns.every((c) => typeof c === 'string') ||
          !Array.isArray(x.grantSources) ||
          !x.grantSources.every((g) => g && (!g.rowCondition || Array.isArray(g.rowCondition.predicates))) ||
          !Array.isArray(x.fieldProtections) ||
          !Array.isArray(x.reasons),
      ) ||
      (data.allowed &&
        (data.tables.length !== 1 ||
          !data.tables[0]?.allowed ||
          new Set(data.tables[0].allowedColumns).size !==
            new Set(payload.tables[0]!.referencedColumns).size ||
          payload.tables[0]!.referencedColumns.some((c) => !data.tables[0]!.allowedColumns.includes(c))))
    )
      throw new Error('实际权限回执对象不完整或与本次用户/源/快照不符，未展示旧结果')
    snapshot.value = data
  } catch (e) {
    if (session.alive() && k === resultKey.value && r === previewRequest)
      previewError.value = e instanceof Error ? e.message : '计算实际权限失败'
  } finally {
    if (session.alive() && k === resultKey.value && r === previewRequest) previewing.value = false
  }
}
const reasonText = (code?: string) => {
  if (!code) return '未提供原因'
  const map: Record<string, string> = {
    ALLOWED: '允许',
    NO_S1_BINDING: '当前账号尚未分配可用角色',
    FUNCTION_NOT_GRANTED: '角色不包含该功能',
    DATASOURCE_NOT_ASSIGNED: '角色不负责该数据源',
    SAME_BINDING_REQUIRED: '功能与负责源不在同一个角色绑定上',
    NO_ALLOW_GRANT: '没有任何允许的数据授权',
    DENIED_BY_RULE: '命中禁止规则',
    EMPTY_RESOURCE: '没有配置可查询的表和字段',
    SNAPSHOT_NOT_PUBLISHED: '元数据快照不存在或未发布',
    USER_NOT_ENABLED: '账号不存在或已禁用',
    PROTOCOL_MISMATCH: '权限校验信息不匹配，请刷新页面后重试',
  }
  return map[code] ?? `未识别的原因码：${code}`
}

watch(
  [session.generation, () => props.defaultDatasourceId, () => props.invalidDefaultDatasource],
  initialize,
  { immediate: true },
)
</script>
<template>
  <div class="iam-s1-effective">
    <section class="panel">
      <div class="heading">
        <div>
          <small>只核对权限，不执行SQL</small>
          <h3>先选核对用途，再选精确范围</h3>
          <p>
            本人仅使用QUERY资源；查看他人使用同绑定的管理范围。预览采用与查询相同的解析器，默认字段用途包括展示、筛选、关联，不能替代某条SQL的执行前校验。
          </p>
        </div>
        <el-radio-group v-model="mode" @change="loadDatasources">
          <el-radio-button v-if="canSelf" value="self">本人业务权限</el-radio-button>
          <el-radio-button v-if="canOthers" value="other">查看他人</el-radio-button>
        </el-radio-group>
      </div>
      <div class="scope-grid">
        <label>
          数据源
          <el-select v-model="datasourceId" placeholder="当前用途可用源" @change="onDatasourceChange">
            <el-option v-for="s in datasources" :key="s.id" :value="s.id" :label="s.name" />
          </el-select>
        </label>
        <label v-if="!previewingSelf">
          目标用户
          <el-select v-model="targetUserId" filterable placeholder="选择具体用户" :loading="loadingUsers">
            <el-option v-for="u in users" :key="u.id" :value="u.id" :label="`${u.name} · #${u.id}`" />
          </el-select>
        </label>
        <label v-else>
          目标用户
          <span class="self-target">本人 · #{{ selfUserId ?? '未确认' }}</span>
        </label>
        <label>
          发布快照
          <el-select v-model="snapshotId" placeholder="选择发布快照" @change="loadTables">
            <el-option
              v-for="s in snapshots"
              :key="s.id"
              :value="s.id"
              :label="`版本 ${s.snapshotVersion ?? s.id} · #${s.id}`"
            />
          </el-select>
        </label>
        <label>
          表
          <el-select v-model="tableName" placeholder="精确表" @change="loadColumns">
            <el-option v-for="t in tables" :key="t.tableName" :value="t.tableName" :label="t.tableName" />
          </el-select>
        </label>
        <label class="columns">
          核对字段
          <el-select v-model="selectedColumns" multiple filterable placeholder="明确字段，空不表示全部">
            <el-option
              v-for="c in columns"
              :key="c.columnMetaId"
              :value="c.columnName"
              :label="`${c.columnComment || c.columnName}${c.selectable ? '' : '（不可授权）'}`"
            />
          </el-select>
        </label>
      </div>
      <div class="actions">
        <el-button type="primary" :loading="previewing" :disabled="!canUseSource || loading" @click="preview">
          计算实际权限
        </el-button>
        <el-button @click="loadDatasources">重新核对范围</el-button>
      </div>
      <p v-if="!datasources.length && !loading && !error">
        本次未返回当前用途的可用源。系统管理员不会自动获得业务数据权。
      </p>
      <ErrorState v-if="userError" :message="userError" @retry="loadUsers" />
    </section>
    <ErrorState v-if="error" :message="error" @retry="loadDatasources" />
    <LoadingState v-if="loading" text="正在读取精确权限资源…" />
    <ErrorState v-if="previewError" :message="previewError" @retry="preview" />
    <LoadingState v-else-if="previewing" text="正在计算本次精确范围的实际权限…" />
    <EmptyState
      v-else-if="!snapshot"
      message="明确用户、发布快照、表和字段后计算，结果只对应本次范围与权限修订。"
    />
    <section v-else class="panel">
      <h3 class="panel-title">
        结论：{{ snapshot.allowed ? '可以查询' : '不能查询' }}
        <small class="muted">{{ reasonText(snapshot.reasonCode) }}</small>
      </h3>
      <p class="hint">
        数据源：{{ snapshot.datasourceName || snapshot.datasourceId }}；快照：{{
          snapshot.activeMetadataSnapshotId
        }}； 权限修订：{{ snapshot.permissionRevision }}
      </p>

      <EmptyState v-if="!snapshot.tables.length" message="没有可查询的表：可能没有任何允许的数据授权。" />

      <div v-for="table in snapshot.tables" :key="table.tableName" class="table-block">
        <h4>
          表 {{ table.tableName }}：{{ table.allowed ? '可查询' : '不可查询' }}
          <small class="muted">{{ reasonText(table.reasonCode) }}</small>
        </h4>
        <p class="hint">
          可查询字段：{{
            table.allowedColumns.length ? table.allowedColumns.join('、') : '没有明确列，空字段不表示全部字段'
          }}
        </p>
        <ul v-if="table.reasons?.length" class="reason-list">
          <li v-for="(reason, index) in table.reasons" :key="index">{{ reason }}</li>
        </ul>
        <div v-if="table.grantSources.length" class="source-block">
          <strong>授权来源：</strong>
          <ul class="source-list">
            <li v-for="source in table.grantSources" :key="source.grantId">
              授权 {{ source.grantId }}：主体 {{ source.subjectType }}#{{ source.subjectId }} （{{
                source.grantSource === 'APPROVAL' ? '访问审批通过' : '管理员配置'
              }}）； 记录范围：{{
                source.rowCondition
                  ? `${source.rowCondition.matchType === 'ANY' ? '任一满足' : '全部满足'} ${source.rowCondition.predicates.map((item) => `${item.columnName} ${item.operatorCode}`).join('、')}`
                  : '全部记录'
              }}； 有效期：{{ source.validUntil || '长期有效' }}
            </li>
          </ul>
        </div>
        <div v-if="table.fieldProtections.length" class="source-block">
          <strong>字段保护：</strong>
          <ul class="source-list">
            <li v-for="protection in table.fieldProtections" :key="protection.columnName">
              {{ protection.columnName }} → {{ protection.protectionLevel
              }}{{ protection.maskPolicy ? `（${protection.maskPolicy}）` : '' }}
            </li>
          </ul>
        </div>
      </div>
    </section>
  </div>
</template>
<style scoped>
.iam-s1-effective {
  display: grid;
  gap: 20px;
}
.panel {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  min-width: 0;
}
.panel h3 {
  margin: 6px 0 10px;
  font-size: 17px;
}
.panel p,
.hint,
.muted {
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.8;
}
.heading {
  display: flex;
  justify-content: space-between;
  align-items: start;
  gap: 20px;
  flex-wrap: wrap;
}
.heading > div:first-child {
  max-width: 740px;
}
.heading small {
  color: var(--do-primary-strong);
}
.scope-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin: 20px 0;
}
.scope-grid label {
  display: grid;
  gap: 6px;
  font-size: 12px;
  color: var(--do-muted);
}
.columns {
  grid-column: span 2;
}
.self-target {
  display: block;
  padding: 8px;
  border: 1px solid var(--do-line);
  border-radius: 6px;
  color: var(--do-ink);
}
.actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.table-block {
  border-top: 1px solid var(--do-line);
  padding-top: 14px;
  margin-top: 16px;
}
.table-block h4 {
  font-size: 15px;
}
.muted {
  margin-left: 8px;
}
.source-list,
.reason-list {
  font-size: 12px;
  line-height: 1.8;
  color: var(--do-muted);
}
.source-block {
  margin-top: 12px;
}
@media (max-width: 850px) {
  .scope-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .panel {
    padding: 14px;
  }
}
@media (max-width: 550px) {
  .scope-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .columns {
    grid-column: auto;
  }
}
</style>
