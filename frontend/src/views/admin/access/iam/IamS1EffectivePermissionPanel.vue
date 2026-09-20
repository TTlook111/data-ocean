<script setup lang="ts">
/**
 * IAM-SIMPLE-1 实际权限预览面板
 *
 * 该面板调用与真实查询完全相同的统一 Resolver（POST /api/iam-s1/effective-permissions/preview），
 * 不在前端自行合并权限，也不返回业务记录或权限参数原值。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EmptyState from '../../../../components/common/EmptyState.vue'
import { useAuthStore } from '../../../../stores/auth'
import { useIamS1Store } from '../../../../stores/iamS1'
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
  type IamS1DatasourceRef,
  type IamS1SubjectOption,
  type IamS1TableOption,
} from '../../../../api/iamS1'

const auth = useAuthStore()
const iamS1 = useIamS1Store()

const datasources = ref<IamS1DatasourceRef[]>([])
const datasourceId = ref<number>()
const snapshots = ref<Array<{ id: number; snapshotVersion?: number }>>([])
const snapshotId = ref<number>()
const tables = ref<IamS1TableOption[]>([])
const tableName = ref<string>()
const columns = ref<IamS1ColumnOption[]>([])
const selectedColumns = ref<string[]>([])
const users = ref<IamS1SubjectOption[]>([])
const targetUserId = ref<number>()
const previewing = ref(false)
const snapshot = ref<IamS1AuthorizationSnapshot | null>(null)

const selfUserId = computed(() => auth.currentUser?.id ?? auth.user?.userId)

/** 预览目标是否为本人（未选择也按本人处理）；决定资源接口按用户侧还是管理端走。 */
const previewingSelf = computed(() => !targetUserId.value || targetUserId.value === selfUserId.value)

const reasonText = (code?: string) => {
  if (!code) return '未提供原因'
  const map: Record<string, string> = {
    ALLOWED: '允许',
    NO_S1_BINDING: '当前账号没有任何可用的 IAM-SIMPLE-1 角色绑定',
    FUNCTION_NOT_GRANTED: '角色不包含该功能',
    DATASOURCE_NOT_ASSIGNED: '角色不负责该数据源',
    SAME_BINDING_REQUIRED: '功能与负责源不在同一个角色绑定上',
    NO_ALLOW_GRANT: '没有任何允许的数据授权',
    DENIED_BY_RULE: '命中禁止规则',
    EMPTY_RESOURCE: '没有配置可查询的表和字段',
    SNAPSHOT_NOT_PUBLISHED: '元数据快照不存在或未发布',
    USER_NOT_ENABLED: '账号不存在或已禁用',
    PROTOCOL_MISMATCH: '请求不是 IAM-SIMPLE-1 协议',
  }
  return map[code] ?? `未识别的原因码：${code}`
}

async function loadDatasources() {
  // 合并两类来源：后台负责源（管理端接口）与本人有数据授权（用户侧接口，scope=QUERY）。
  // 只看本人权限时，不会因为“没有后台负责源”而无法选择数据源。
  const empty: IamS1DatasourceRef[] = []
  const [admin, mine] = await Promise.all([
    listIamS1Datasources().then((result) => result.data ?? empty).catch(() => empty),
    listIamS1QueryResourceDatasources('QUERY').then((result) => result.data ?? empty).catch(() => empty),
  ])
  const merged = new Map<number, IamS1DatasourceRef>()
  for (const item of [...admin, ...mine]) merged.set(item.id, item)
  datasources.value = [...merged.values()]
  datasourceId.value = datasources.value[0]?.id
}

async function loadUsers() {
  // 用户下拉只服务“查看他人”，EFFECTIVE 用途要求 security:effective:view
  // 与“请求中的 datasourceId 负责源”在同一角色绑定上，所以按当前数据源加载。
  // 普通问数用户没有该功能，这里失败必须降级为空列表，
  // 否则会中断 onMounted 后续的本人预览初始化（快照/表/字段永远为空）。
  if (!datasourceId.value) {
    users.value = []
    return
  }
  try {
    const result = await listIamS1Subjects('EFFECTIVE', datasourceId.value, 'USER')
    users.value = result.data ?? []
  } catch {
    users.value = []
  }
}

async function loadSnapshots() {
  snapshotId.value = undefined
  tables.value = []
  columns.value = []
  selectedColumns.value = []
  tableName.value = undefined
  snapshot.value = null
  if (!datasourceId.value) return
  // 本人预览只需“使用问数”，走用户侧资源接口；
  // 查看他人需要后台负责源，走管理端接口。两者严格分离，不能混用。
  const result = previewingSelf.value
    ? await listIamS1QueryResourceSnapshots(datasourceId.value, 'QUERY')
    : await listIamS1Snapshots(datasourceId.value)
  snapshots.value = result.data ?? []
  snapshotId.value = snapshots.value[0]?.id
  await loadTables()
}

async function loadTables() {
  tableName.value = undefined
  columns.value = []
  selectedColumns.value = []
  if (!datasourceId.value || !snapshotId.value) return
  const result = previewingSelf.value
    ? await listIamS1QueryResourceTables(datasourceId.value, snapshotId.value, 'QUERY')
    : await listIamS1Tables(datasourceId.value, snapshotId.value)
  tables.value = result.data ?? []
}

async function loadColumns() {
  selectedColumns.value = []
  columns.value = []
  if (!datasourceId.value || !snapshotId.value || !tableName.value) return
  const result = previewingSelf.value
    ? await listIamS1QueryResourceColumns(datasourceId.value, snapshotId.value, tableName.value, 'QUERY')
    : await listIamS1Columns(datasourceId.value, snapshotId.value, tableName.value)
  columns.value = result.data ?? []
}

async function preview() {
  if (!datasourceId.value) {
    ElMessage.warning('请先选择数据源')
    return
  }
  if (!tableName.value || !selectedColumns.value.length) {
    ElMessage.warning('请先选择要核对的表和字段；空字段不表示全部字段')
    return
  }
  previewing.value = true
  try {
    const result = await previewIamS1EffectivePermission({
      userId: targetUserId.value,
      datasourceId: datasourceId.value,
      activeMetadataSnapshotId: snapshotId.value,
      tables: [{ tableName: tableName.value, referencedColumns: selectedColumns.value }],
    })
    snapshot.value = result.data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '计算实际权限失败')
  } finally {
    previewing.value = false
  }
}

onMounted(async () => {
  await iamS1.load()
  try {
    // loadUsers 依赖已选中的数据源，必须排在 loadDatasources 之后。
    await loadDatasources()
    targetUserId.value = selfUserId.value
    await loadUsers()
    await loadSnapshots()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '初始化实际权限预览失败')
  }
})

/** 切换数据源：可预览的用户列表也要按新数据源重新判定。 */
async function onDatasourceChange() {
  await loadUsers()
  await loadSnapshots()
}
</script>

<template>
  <div class="iam-s1-effective">
    <section class="panel">
      <h3 class="panel-title">核对一个用户现在能查什么</h3>
      <p class="hint">
        结果来自与真实查询同一个统一计算服务，并展示授权来源、记录范围、字段保护和拒绝原因。
        查看其他人需要“查看用户实际权限”并负责目标数据源。
      </p>
      <div class="inline">
        <el-select v-model="targetUserId" placeholder="选择用户" filterable class="w240" @change="loadSnapshots">
          <el-option
            v-if="selfUserId"
            :label="'本人（' + (auth.currentUser?.realName || auth.user?.username || '当前账号') + '）'"
            :value="selfUserId"
          />
          <el-option v-for="item in users" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select v-model="datasourceId" placeholder="数据源" class="w200" @change="onDatasourceChange">
          <el-option v-for="item in datasources" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select v-model="snapshotId" placeholder="已发布快照" class="w200" @change="loadTables">
          <el-option
            v-for="item in snapshots"
            :key="item.id"
            :label="`版本 ${item.snapshotVersion ?? item.id}`"
            :value="item.id"
          />
        </el-select>
        <el-select v-model="tableName" placeholder="表" class="w220" @change="loadColumns">
          <el-option
            v-for="item in tables"
            :key="item.tableName"
            :label="item.tableName"
            :value="item.tableName"
          />
        </el-select>
        <el-select v-model="selectedColumns" multiple filterable placeholder="选择要核对的字段" class="w260">
          <el-option
            v-for="item in columns"
            :key="item.columnMetaId"
            :label="`${item.columnComment || item.columnName}${item.selectable ? '' : '（不可授权）'}`"
            :value="item.columnName"
          />
        </el-select>
        <el-button type="primary" :loading="previewing" @click="preview">计算实际权限</el-button>
      </div>
    </section>

    <EmptyState v-if="!snapshot" message="选择用户、数据源、表和字段后点击“计算实际权限”，查看真实生效范围。" />

    <section v-else class="panel">
      <h3 class="panel-title">
        结论：{{ snapshot.allowed ? '可以查询' : '不能查询' }}
        <small class="muted">{{ reasonText(snapshot.reasonCode) }}</small>
      </h3>
      <p class="hint">
        数据源：{{ snapshot.datasourceName || snapshot.datasourceId }}；快照：{{ snapshot.activeMetadataSnapshotId }}；
        权限修订：{{ snapshot.permissionRevision }}
      </p>

      <div class="role-block">
        <strong>后台职责（与业务查询范围分开）：</strong>
        <span v-if="iamS1.functionNamesOn(snapshot.datasourceId).length">
          {{ iamS1.functionNamesOn(snapshot.datasourceId).join('、') }}
        </span>
        <span v-else>该数据源上没有后台职责，或你没有负责该数据源。</span>
      </div>

      <EmptyState v-if="!snapshot.tables.length" message="没有可查询的表：可能没有任何允许的数据授权。" />

      <div v-for="table in snapshot.tables" :key="table.tableName" class="table-block">
        <h4>
          表 {{ table.tableName }}：{{ table.allowed ? '可查询' : '不可查询' }}
          <small class="muted">{{ reasonText(table.reasonCode) }}</small>
        </h4>
        <p class="hint">
          可查询字段：{{ table.allowedColumns.length ? table.allowedColumns.join('、') : '没有明确列，空字段不表示全部字段' }}
        </p>
        <ul v-if="table.reasons?.length" class="reason-list">
          <li v-for="(reason, index) in table.reasons" :key="index">{{ reason }}</li>
        </ul>
        <div v-if="table.grantSources.length" class="source-block">
          <strong>授权来源：</strong>
          <ul class="source-list">
            <li v-for="source in table.grantSources" :key="source.grantId">
              授权 {{ source.grantId }}：主体 {{ source.subjectType }}#{{ source.subjectId }}
              （{{ source.grantSource === 'APPROVAL' ? '访问审批通过' : '管理员配置' }}）；
              记录范围：{{ source.rowCondition
                ? `${source.rowCondition.matchType === 'ANY' ? '任一满足' : '全部满足'} ${source.rowCondition.predicates.map((item) => `${item.columnName} ${item.operatorCode}`).join('、')}`
                : '全部记录' }}；
              有效期：{{ source.validUntil || '长期有效' }}
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
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.panel {
  border: 1px solid var(--do-line);
  border-radius: 10px;
  padding: 18px;
  background: var(--do-surface);
}
.panel-title {
  margin: 0 0 8px;
  font-size: 15px;
}
.inline {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.hint {
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
  margin: 6px 0;
}
.muted {
  color: var(--do-muted);
  font-size: 12px;
  margin-left: 8px;
}
.role-block,
.table-block {
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
}
.table-block h4 {
  margin: 0 0 6px;
  font-size: 14px;
}
.reason-list,
.source-list {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--do-muted);
  line-height: 1.8;
}
.w200 {
  width: 200px;
}
.w220 {
  width: 220px;
}
.w240 {
  width: 240px;
}
.w260 {
  width: 260px;
}
</style>
