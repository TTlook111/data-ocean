<script setup lang="ts">
/**
 * IAM-SIMPLE-1 字段保护面板
 *
 * 允许查询不等于允许看原值：字段保护单独维护，不在每个用户授权里复制掩码。
 * 维护字段保护不授予字段查询权。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EmptyState from '../../../../components/common/EmptyState.vue'
import { useIamS1Store } from '../../../../stores/iamS1'
import {
  listIamS1Columns,
  listIamS1Datasources,
  listIamS1FieldProtections,
  listIamS1Snapshots,
  listIamS1Tables,
  revokeIamS1FieldProtection,
  saveIamS1FieldProtection,
  type IamS1ColumnOption,
  type IamS1DatasourceRef,
  type IamS1FieldProtectionItem,
  type IamS1TableOption,
} from '../../../../api/iamS1'

const iamS1 = useIamS1Store()

const datasources = ref<IamS1DatasourceRef[]>([])
const datasourceId = ref<number>()
const snapshots = ref<Array<{ id: number; snapshotVersion?: number }>>([])
const snapshotId = ref<number>()
const tables = ref<IamS1TableOption[]>([])
const tableName = ref<string>()
const columns = ref<IamS1ColumnOption[]>([])
const protections = ref<IamS1FieldProtectionItem[]>([])
const savingColumnId = ref<number>()

const maskPolicies = [
  { label: '手机号掩码', value: 'PHONE' },
  { label: '身份证掩码', value: 'ID_CARD' },
  { label: '邮箱掩码', value: 'EMAIL' },
  { label: '银行卡掩码', value: 'BANK_CARD' },
  { label: '姓名掩码', value: 'NAME' },
]

const protectionLabel = (level?: string) =>
  level === 'HIDDEN' ? '隐藏字段' : level === 'MASKED' ? '脱敏显示' : '正常显示'

const activeFor = (columnMetaId: number) =>
  protections.value.find((item) => item.columnMetaId === columnMetaId && item.status === 'ACTIVE')

const hiddenCount = computed(() => columns.value.filter((item) => item.protectionLevel === 'HIDDEN').length)
const maskedCount = computed(() => columns.value.filter((item) => item.protectionLevel === 'MASKED').length)

async function loadDatasources() {
  const result = await listIamS1Datasources()
  // 同数据授权面板：服务端只按“负责源”下发，必须再按“查看字段保护”过滤，
  // 否则下拉会出现该绑定没有此功能的源，选中后保存/读取一律被后端拒绝。
  const allowed = new Set(iamS1.datasourcesWithFunction('security:mask:view'))
  datasources.value = (result.data ?? []).filter((item) => allowed.has(item.id))
  datasourceId.value = datasources.value[0]?.id
}

async function loadSnapshots() {
  snapshotId.value = undefined
  tables.value = []
  columns.value = []
  protections.value = []
  tableName.value = undefined
  if (!datasourceId.value) return
  const result = await listIamS1Snapshots(datasourceId.value)
  snapshots.value = result.data ?? []
  snapshotId.value = snapshots.value[0]?.id
  await loadProtections()
}

async function loadTables() {
  tableName.value = undefined
  columns.value = []
  if (!datasourceId.value || !snapshotId.value) return
  const result = await listIamS1Tables(datasourceId.value, snapshotId.value)
  tables.value = result.data ?? []
}

async function loadColumns() {
  columns.value = []
  if (!datasourceId.value || !snapshotId.value || !tableName.value) return
  const result = await listIamS1Columns(datasourceId.value, snapshotId.value, tableName.value)
  columns.value = result.data ?? []
}

async function loadProtections() {
  if (!datasourceId.value || !snapshotId.value) return
  try {
    const result = await listIamS1FieldProtections({
      datasourceId: datasourceId.value,
      snapshotId: snapshotId.value,
    })
    protections.value = result.data ?? []
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取字段保护失败')
  }
}

async function save(column: IamS1ColumnOption, level: 'NORMAL' | 'MASKED' | 'HIDDEN', maskPolicy?: string) {
  if (!datasourceId.value || !snapshotId.value || !tableName.value) return
  savingColumnId.value = column.columnMetaId
  try {
    await saveIamS1FieldProtection({
      protocolVersion: 'IAM-SIMPLE-1',
      datasourceId: datasourceId.value,
      metadataSnapshotId: snapshotId.value,
      tableName: tableName.value,
      columnMetaId: column.columnMetaId,
      columnName: column.columnName,
      protectionLevel: level,
      maskPolicy: level === 'MASKED' ? maskPolicy ?? 'PHONE' : null,
      reason: '界面维护字段保护',
    })
    ElMessage.success(`已保存：${column.columnComment || column.columnName} → ${protectionLabel(level)}`)
    await Promise.all([loadProtections(), loadColumns()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存字段保护失败')
  } finally {
    savingColumnId.value = undefined
  }
}

async function revoke(item: IamS1FieldProtectionItem) {
  try {
    await revokeIamS1FieldProtection(item.id, '界面撤销字段保护')
    ElMessage.success('字段保护已撤销')
    await Promise.all([loadProtections(), loadColumns()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '撤销字段保护失败')
  }
}

onMounted(async () => {
  try {
    await loadDatasources()
    await loadSnapshots()
    await loadTables()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '初始化字段保护失败')
  }
})
</script>

<template>
  <div class="iam-s1-protections">
    <section class="panel">
      <h3 class="panel-title">选择要维护的字段</h3>
      <div class="inline">
        <el-select v-model="datasourceId" placeholder="数据源" class="w220" @change="loadSnapshots">
          <el-option v-for="item in datasources" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select v-model="snapshotId" placeholder="已发布快照" class="w220" @change="loadTables">
          <el-option
            v-for="item in snapshots"
            :key="item.id"
            :label="`版本 ${item.snapshotVersion ?? item.id}`"
            :value="item.id"
          />
        </el-select>
        <el-select v-model="tableName" placeholder="表" class="w260" @change="loadColumns">
          <el-option
            v-for="item in tables"
            :key="item.tableName"
            :label="`${item.tableName}${item.tableComment ? '（' + item.tableComment + '）' : ''}`"
            :value="item.tableName"
          />
        </el-select>
      </div>
      <p class="hint">
        当前表：隐藏 {{ hiddenCount }} 个字段、脱敏 {{ maskedCount }} 个字段。隐藏字段不会进入问数 Schema 与知识召回；
        脱敏字段只能直接展示，不能用于筛选、关联、排序或分组。
      </p>
    </section>

    <section class="panel">
      <EmptyState v-if="!columns.length" message="请选择数据源、快照和表后维护字段保护。" />
      <el-table v-else :data="columns" size="small">
        <el-table-column label="字段" min-width="200">
          <template #default="{ row }">
            {{ row.columnComment || row.columnName }}
            <small class="muted">{{ row.columnName }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="dataType" label="类型" width="140" />
        <el-table-column label="当前保护" width="140">
          <template #default="{ row }">
            {{ protectionLabel(row.protectionLevel) }}
            <small v-if="activeFor(row.columnMetaId)?.maskPolicyName" class="muted">
              {{ activeFor(row.columnMetaId)?.maskPolicyName }}
            </small>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="320">
          <template #default="{ row }">
            <el-button
              size="small"
              :loading="savingColumnId === row.columnMetaId"
              @click="save(row, 'NORMAL')"
            >正常显示</el-button>
            <el-select
              size="small"
              class="w160"
              placeholder="选择掩码"
              :disabled="savingColumnId === row.columnMetaId"
              @change="(value: string) => save(row, 'MASKED', value)"
            >
              <el-option v-for="item in maskPolicies" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-button
              size="small"
              type="warning"
              :loading="savingColumnId === row.columnMetaId"
              @click="save(row, 'HIDDEN')"
            >隐藏字段</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="panel">
      <h3 class="panel-title">该快照已生效的字段保护（{{ protections.length }}）</h3>
      <EmptyState v-if="!protections.length" message="该快照还没有生效的字段保护。" />
      <el-table v-else :data="protections" size="small">
        <el-table-column prop="tableName" label="表" width="160" />
        <el-table-column prop="columnName" label="字段" width="180" />
        <el-table-column label="保护" width="150">
          <template #default="{ row }">
            {{ row.protectionLevelName }}{{ row.maskPolicyName ? `（${row.maskPolicyName}）` : '' }}
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="danger" @click="revoke(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.iam-s1-protections {
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
  margin: 0 0 12px;
  font-size: 15px;
}
.inline {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.hint {
  margin: 12px 0 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
}
.muted {
  display: block;
  color: var(--do-muted);
  font-size: 12px;
}
.w160 {
  width: 160px;
}
.w220 {
  width: 220px;
}
.w260 {
  width: 260px;
}
</style>
