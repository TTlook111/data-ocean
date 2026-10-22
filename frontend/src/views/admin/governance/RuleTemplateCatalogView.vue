<script setup lang="ts">
/**
 * 检查类型（规则模板）只读目录。
 *
 * 模板由后端 QualityRuleTemplateRegistry 注册，每个都绑定真实 QualityChecker；
 * 页面只展示执行器能力和参数 schema，不提供新建、编辑或删除。
 */
import { computed, onMounted, ref } from 'vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import { listQualityRuleTemplates, type QualityRuleParameterDefinition, type QualityRuleTemplate } from '../../../api/admin/governance'
import { qualityDimensionLabel } from '../../../utils/enumLabels'
import { ruleScopeLabel, ruleTargetTypeLabel, sortRuleScopes } from '../../../utils/qualityRulePresentation'

const templates = ref<QualityRuleTemplate[]>([])
const loading = ref(false)
const error = ref('')
const dimensionFilter = ref('')
const checkTypeFilter = ref('')

const dimensions = ['COMPLETENESS', 'ACCURACY', 'CONSISTENCY', 'TIMELINESS', 'TRACEABILITY']
const filteredTemplates = computed(() => templates.value.filter((template) => (
  (!dimensionFilter.value || template.dimension === dimensionFilter.value)
  && (!checkTypeFilter.value || template.checkType === checkTypeFilter.value)
)))

function checkTypeLabel(checkType: string) {
  return checkType === 'DATA' ? '业务数据（只读抽样）' : '元数据检查'
}

function parameterText(parameter: QualityRuleParameterDefinition) {
  if (parameter.type === 'BOOLEAN') {
    return `${parameter.label}（是/否，默认${parameter.defaultValue ? '是' : '否'}）`
  }
  const range = parameter.minimum !== undefined || parameter.maximum !== undefined
    ? `${parameter.minimum ?? '不限'}–${parameter.maximum ?? '不限'}`
    : '数值'
  return `${parameter.label}（${parameter.type === 'INTEGER' ? '整数' : '数值'} ${range}，默认${parameter.defaultValue}）`
}

function parametersText(template: QualityRuleTemplate) {
  return template.parameters.length ? template.parameters.map(parameterText).join('；') : '无参数'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await listQualityRuleTemplates()
    templates.value = response.data ?? []
  } catch (cause) {
    templates.value = []
    error.value = cause instanceof Error ? cause.message : '检查类型读取失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <main class="rule-template-page post-login-page">
    <TaskPageHeader
      title="检查类型"
      description="查看已注册的检查模板及其维度、目标、范围和参数。执行逻辑由后端提供，页面只读。"
    />

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="检查类型不是可自由新建的字典项"
      description="每个模板都绑定真实的后端检查执行器。新增或修改检查逻辑需要后端实现、测试和部署；规则实例通过“检查类型 + 适用范围 + 参数”组合。"
    />

    <section class="rule-template-page__workspace do-glass-panel" aria-labelledby="rule-template-list-title">
      <div class="rule-template-page__toolbar">
        <div class="rule-template-page__filters">
          <el-select v-model="dimensionFilter" placeholder="全部治理维度" clearable class="rule-template-page__select">
            <el-option v-for="dimension in dimensions" :key="dimension" :label="qualityDimensionLabel(dimension)" :value="dimension" />
          </el-select>
          <el-select v-model="checkTypeFilter" placeholder="全部检查方式" clearable class="rule-template-page__select">
            <el-option label="元数据检查" value="SCHEMA" />
            <el-option label="业务数据（只读抽样）" value="DATA" />
          </el-select>
        </div>
        <span class="rule-template-page__count">共 {{ filteredTemplates.length }} / {{ templates.length }} 个检查类型</span>
      </div>

      <ErrorState v-if="error" :message="error" @retry="load" />
      <LoadingState v-else-if="loading" text="正在读取检查类型…" />
      <EmptyState
        v-else-if="!filteredTemplates.length"
        :message="templates.length ? '没有符合筛选条件的检查类型。' : '当前没有已注册的检查类型。'"
      />
      <div v-else class="rule-template-page__table-wrap">
        <el-table :data="filteredTemplates" row-key="code" class="rule-template-page__table">
          <el-table-column label="检查类型" min-width="220">
            <template #default="{ row }">
              <strong>{{ row.name }}</strong>
              <small class="rule-template-page__code">{{ row.code }}</small>
            </template>
          </el-table-column>
          <el-table-column label="治理维度" width="120">
            <template #default="{ row }">{{ qualityDimensionLabel(row.dimension) }}</template>
          </el-table-column>
          <el-table-column label="检查方式" width="170">
            <template #default="{ row }">{{ checkTypeLabel(row.checkType) }}</template>
          </el-table-column>
          <el-table-column label="检查目标" width="100">
            <template #default="{ row }">{{ ruleTargetTypeLabel(row.targetType) }}</template>
          </el-table-column>
          <el-table-column label="允许范围" min-width="190">
            <template #default="{ row }">
              <span class="rule-template-page__chips">
                <el-tag v-for="scope in sortRuleScopes(row.allowedScopes)" :key="scope" size="small" type="info">{{ ruleScopeLabel(scope) }}</el-tag>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="参数" min-width="260">
            <template #default="{ row }"><span class="rule-template-page__params">{{ parametersText(row) }}</span></template>
          </el-table-column>
          <el-table-column label="说明" min-width="280" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description }}</template>
          </el-table-column>
        </el-table>
      </div>
    </section>
  </main>
</template>

<style scoped>
.rule-template-page { display: grid; gap: 16px; min-width: 0; }
.rule-template-page__workspace { display: grid; gap: 14px; min-width: 0; padding: 18px; border-radius: 16px; }
.rule-template-page__toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px 16px; }
.rule-template-page__filters { display: flex; flex-wrap: wrap; gap: 10px; }
.rule-template-page__select { width: 200px; }
.rule-template-page__count { color: var(--do-muted); font-size: 12px; }
.rule-template-page__table-wrap { min-width: 0; overflow-x: auto; }
.rule-template-page__code { display: block; margin-top: 3px; color: var(--do-muted); font-size: 11px; word-break: break-all; }
.rule-template-page__chips { display: flex; flex-wrap: wrap; gap: 4px; }
.rule-template-page__params { color: var(--do-muted); font-size: 12px; line-height: 1.5; }
</style>
