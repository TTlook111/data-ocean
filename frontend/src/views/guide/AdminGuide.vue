<script setup lang="ts">
import { computed, onMounted } from 'vue'
import GuidePage from '../../components/common/GuidePage.vue'
import { useIamS1Store } from '../../stores/iamS1'
import { findWorkspaceByPath } from '../../router/adminNavigation'
import { isWorkspaceVisible, buildWorkspaceTarget, resolveAdminEntry } from '../../utils/adminNavigation'
const iam = useIamS1Store()
onMounted(() => {
  void iam.load()
})
const home = computed(() => (iam.loaded && !iam.errorMessage ? resolveAdminEntry(iam) : undefined))
function target(path: string) {
  const workspace = findWorkspaceByPath(path)
  return iam.loaded && !iam.errorMessage && workspace && isWorkspaceVisible(workspace, iam)
    ? buildWorkspaceTarget(path, iam)
    : undefined
}
const steps = [
  {
    title: '接入数据源，确认连接事实',
    text: '录入数据源并测试只读连接。连接成功仅证明当次连接可用；启用、采集、治理、发布和可问数各有自己的状态。',
    path: '/admin/data-sources',
    action: '查看数据源',
  },
  {
    title: '采集元数据，锁定精确对象',
    text: '通过采集任务读取表、字段、类型、索引和源注释，查看任务结果与快照。后续操作始终核对负责数据源、快照和具体对象。',
    path: '/admin/collections',
    action: '查看采集任务',
  },
  {
    title: '先理解业务，再解释表与字段',
    text: '在数据对象目录核对结构和业务上下文。表间关联关系、加工血缘和一次查询的实际来源分别查看，不能从表同时出现就推断血缘。',
    path: '/admin/assets',
    action: '查看数据对象',
  },
  {
    title: '让 AI 提建议，由人审核事实',
    text: 'AI 字段解释是候选。审核人员核对依据、对象和草稿后再确认或驳回；人工修改不能被迟到建议覆盖。',
    path: '/admin/governance/fields',
    action: '进入字段治理',
  },
  {
    title: '处理质量与语义问题',
    text: '区分源数据质量问题与语义解释问题，维护规则和治理状态。问题关闭后仍需在原情景核验；质量分和使用分不等于业务正确性。',
    path: '/admin/governance/issues',
    action: '查看问题中心',
  },
  {
    title: '审核并发布元数据版本',
    text: '以精确快照版本完成审核与发布，保留复查、失效和恢复语义。发布不会替你完成所有业务数据授权，也不会等于 RAG 已构建。',
    path: '/admin/releases',
    action: '查看元数据版本',
  },
  {
    title: '维护并发布业务主题知识',
    text: '基于对象事实组织业务文档，复用可信字段含义与业务口径。文档的修订、审核和发布是独立动作，候选内容不能冒充正式知识。',
    path: '/admin/semantics/knowledge',
    action: '进入语义知识',
  },
  {
    title: '显式构建 RAG，核对当前索引',
    text: '确认构建所用快照、文档修订、摘要指纹与成员，再显式发起构建。发布和构建分开；切换前核验替代版本，并保护仍有引用的索引。合法旧构建可继续使用，来源失效与有更新待构建须分别处理。',
    path: '/admin/semantics/knowledge',
    action: '查看构建与当前索引',
  },
  {
    title: '受控问数，查看真实执行证据',
    text: '业务用户另需数据查询授权。现有 LangGraph 负责检索、计划与 SQL 生成，SQLGlot 校验后受控只读执行；可修正错误有限重试，无权或危险操作直接拒绝。结果完成还需核对同一任务的 attempt 与 SQL 摘要证据，投影状态另行显示。',
    path: '/admin/operations/queries',
    action: '查看查询分析',
  },
  {
    title: '把反馈送回人工修订闭环',
    text: '记录字段用途标签、问题归因和修订依据，在治理反馈工作面核对。使用记录、执行成功或正向反馈不会自动批准语义；修订后仍经过审核、发布及必要的显式构建。',
    path: '/admin/governance',
    action: '返回治理工作面',
  },
]
</script>
<template>
  <GuidePage
    label="治理流程指南 · 10 个业务环节"
    title="把数据整理成可核验的业务知识"
    description="按角色协作完成接入、理解、审核、发布与问数反馈。每个状态回答一个具体问题，页面入口只显示当前能力允许的范围。"
  >
    <template #navigation>
      <RouterLink to="/profile">个人资料</RouterLink>
      <RouterLink to="/guide/query">问数使用指南</RouterLink>
    </template>
    <div class="guide-note">
      功能权决定能做什么，后台负责源决定能维护哪些源，业务数据权决定能查哪些数据。三者独立；不同角色绑定不能拼成更大的权限组合。
    </div>
    <ol class="guide-steps">
      <li v-for="step in steps" :key="step.title" class="guide-step">
        <div>
          <h2>{{ step.title }}</h2>
          <p>{{ step.text }}</p>
          <RouterLink v-if="target(step.path)" :to="target(step.path)!" class="guide-link">
            {{ step.action }} →
          </RouterLink>
          <span v-else class="guide-unavailable">此入口当前未获授权或能力尚未确认</span>
        </div>
      </li>
    </ol>
    <div class="guide-actions">
      <RouterLink v-if="home" :to="home" class="guide-primary">进入有权工作区</RouterLink>
      <RouterLink v-if="iam.loaded && !iam.errorMessage && iam.queryUse" to="/query">进入问数</RouterLink>
      <RouterLink to="/guide/query">查看问数指南</RouterLink>
    </div>
  </GuidePage>
</template>
