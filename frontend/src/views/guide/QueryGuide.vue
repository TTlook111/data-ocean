<script setup lang="ts">
import { onMounted } from 'vue'
import GuidePage from '../../components/common/GuidePage.vue'
import { useIamS1Store } from '../../stores/iamS1'
const iam = useIamS1Store()
onMounted(() => {
  void iam.load()
})
const steps = [
  {
    title: '选择本次问题的数据范围',
    text: '进入问数后，在提问区确认数据源。每次问题只使用一个 MySQL 数据库；能看到后台数据源，不代表拥有业务查询权。',
    detail:
      '没有可选数据源时，刷新后仍不可用，请联系管理员核对业务数据授权和数据准备情况。不要通过更换问题绕过无权或受保护对象。',
  },
  {
    title: '把业务口径写进问题',
    text: '在底部输入框说明指标、时间范围、维度和筛选条件。例如：“按自然月统计本季度各产品的销售额，退款按净额扣除”。具体可问内容以所选数据源的可信知识与授权范围为准。',
    detail:
      '左侧用于新建和查找历史会话；中间保留连续问答。问题不明确时先补充说明；多轮上下文不能替代真实业务口径。',
  },
  {
    title: '在回答里看过程与结果',
    text: '提交后，执行阶段随进度展开。图表和表格属于本次回答，可按需放大；SQL、来源及血缘入口按权限与实际执行证据展示，不常驻占据一列。',
    detail:
      '结果成功、投影完成和业务结论正确是不同事实。只有经过受保护执行的结果才可作为本次执行证据；历史 SQL 和导出仍需服务端重验。图表是否可用取决于结果形态与返回内容。',
  },
  {
    title: '继续追问，核验并反馈',
    text: '沿同一会话补充维度或筛选条件。反馈应说明字段用途、口径问题和正确依据；运行证据与反馈不会自动成为正式语义，仍需进入人工修订与发布流程。',
    detail:
      '运行中可停止；中断、恢复和重试以当前任务提示为准。服务会在可修正错误上有限重试；遇到无权、危险 SQL、恢复失败或需要澄清时会停止，不会无限尝试。',
  },
]
</script>
<template>
  <GuidePage
    label="问数使用指南"
    title="从一个清楚的问题开始"
    description="选定数据范围，在连续对话里提问、看证据、核验结果。数据可信度和业务判断需要人参与。"
  >
    <template #navigation>
      <RouterLink to="/profile">个人资料</RouterLink>
      <RouterLink to="/guide/admin">治理流程指南</RouterLink>
    </template>
    <ol class="guide-steps">
      <li v-for="step in steps" :key="step.title" class="guide-step">
        <div>
          <h2>{{ step.title }}</h2>
          <p>{{ step.text }}</p>
          <details>
            <summary>操作边界与恢复方式</summary>
            <p>{{ step.detail }}</p>
          </details>
        </div>
      </li>
    </ol>
    <div class="guide-note">
      查询权限由服务端校验。系统管理员身份、后台功能权和负责数据源范围，都不会自动变成业务数据查询权。
    </div>
    <div class="guide-actions">
      <RouterLink v-if="iam.loaded && !iam.errorMessage && iam.queryUse" to="/query" class="guide-primary">
        进入问数
      </RouterLink>
      <span v-else class="guide-unavailable">
        当前查询能力尚未确认或未获授权，请在个人资料或管理员处核对。
      </span>
      <RouterLink to="/guide/admin">了解数据如何成为可信知识</RouterLink>
    </div>
  </GuidePage>
</template>
