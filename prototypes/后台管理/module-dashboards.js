/* 四个模块共用图表控件，各自保留独立的业务口径、配色和编排。 */
(() => {
  'use strict';
  const blue = '#3e86dc', cyan = '#1598ad', teal = '#168f7c', green = '#65a58b';
  const indigo = '#6373ce', slate = '#7b8ca9', amber = '#bf8528', red = '#ce7464';
  window.ModuleDashboardProfiles = {
    'sources-dashboard': {
      title: '接入概览', layout: 'sources', accent: cyan,
      charts: [
        { id: 'source-main', title: '采集运行趋势', unit: '次', hero: true, href: '#collections', destination: '采集记录',
          series: [{ key: 'collectionSuccess', name: '采集成功', color: blue, fill: true }, { key: 'collectionFailure', name: '采集失败', color: red, dashed: true }],
          metrics: [{ key: 'collectionSuccess', label: '次采集成功' }, { key: 'collectionFailure', label: '次采集失败' }] },
        { id: 'source-outcomes', title: '采集结果分布', unit: '次', kind: 'ring', centerLabel: '次已结束采集',
          info: '统计所选期间已结束的任务。成功、失败、取消分别计数，排队与采集中不进入占比。',
          series: [{ key: 'collectionSuccess', name: '成功', color: blue }, { key: 'collectionFailure', name: '失败', color: red }, { key: 'collectionCancelled', name: '已取消', color: slate }], metrics: [] },
        { id: 'source-latency', title: '采集耗时', unit: '秒', decimals: 1,
          info: '仅统计成功采集，按任务完成次数加权；标题为所选期间平均值。',
          series: [{ key: 'collectionDuration', name: '平均采集耗时', color: cyan, fill: true }], metrics: [{ key: 'collectionDuration', label: '秒平均耗时' }] },
        { id: 'source-changes', title: '待核实变化', unit: '项', href: '#catalog', destination: '元数据目录',
          info: '展示日末仍待事实核实的结构变化，标题为所选期间期末值，不代表业务审核积压。',
          series: [{ key: 'pendingChanges', name: '待事实核实', color: slate, step: true }], metrics: [{ key: 'pendingChanges', label: '项期末待核实' }] }
      ]
    },
    'governance-dashboard': {
      title: '治理概览', layout: 'governance', accent: teal,
      charts: [
        { id: 'governance-main', title: '表字段确认趋势', unit: '项', hero: true, href: '#governance', destination: '治理工作台',
          info: '表说明与字段分别计数，只统计实际确认；AI 草稿不计入。',
          series: [{ key: 'fieldConfirmed', name: '字段确认', color: teal, fill: true }, { key: 'tableConfirmed', name: '表说明确认', color: green, dashed: true }],
          metrics: [{ key: 'fieldConfirmed', label: '项字段确认' }, { key: 'tableConfirmed', label: '项表说明确认' }] },
        { id: 'governance-status', title: '期末治理进度', unit: '个', kind: 'ring', centerLabel: '个表与视图',
          info: '按所选期末的对象快照统计。主进度只汇总表说明与字段，关联、指标及变更后待核对项分别表达。',
          series: [{ key: 'governanceDone', name: '已治理', color: teal }, { key: 'governanceDoing', name: '治理中', color: '#6a9d86' }, { key: 'governanceTodo', name: '待治理', color: slate }], metrics: [] },
        { id: 'governance-review', title: '变更后待核对', unit: '项',
          info: '日末待核对的业务条目，可与对象治理进度重叠，不作为第四种进度。',
          series: [{ key: 'governanceReview', name: '业务条目待核对', color: amber, step: true }], metrics: [{ key: 'governanceReview', label: '项期末待核对' }] },
        { id: 'governance-relations', title: '关联与指标审核', unit: '项',
          series: [{ key: 'relationConfirmed', name: '关联确认', color: teal }, { key: 'indicatorConfirmed', name: '指标确认', color: slate, dashed: true }],
          metrics: [{ key: 'relationConfirmed', label: '项关联确认' }, { key: 'indicatorConfirmed', label: '项指标确认' }] }
      ]
    },
    'knowledge-dashboard': {
      title: '知识概览', layout: 'knowledge', accent: indigo,
      charts: [
        { id: 'knowledge-main', title: '正式知识可用变化', unit: '篇', hero: true, href: '#knowledge', destination: '知识文档',
          info: '展示日末可用的正式文档存量，标题取所选期间期末值。发布、暂停和禁止分别管理，草稿不进入正式知识。',
          series: [{ key: 'availableDocuments', name: '可用正式文档', color: indigo, fill: true, step: true }], metrics: [{ key: 'availableDocuments', label: '篇期末可用' }] },
        { id: 'knowledge-releases', title: '发布动态', unit: '批', href: '#releases', destination: '发布任务',
          series: [{ key: 'publishBatches', name: '成功生效批次', color: indigo, fill: true }], metrics: [{ key: 'publishBatches', label: '批成功生效' }] },
        { id: 'knowledge-validation', title: '验证异常', unit: '题次', href: '#test-cases', destination: '标准题单',
          info: '未通过与执行错误按运行题次分别统计，不将异常数量当作问数正确率。',
          series: [{ key: 'validationFailed', name: '未通过', color: amber }, { key: 'validationErrors', name: '执行错误', color: red, dashed: true }],
          metrics: [{ key: 'validationFailed', label: '题次未通过' }, { key: 'validationErrors', label: '题次执行错误' }] },
        { id: 'knowledge-attention', title: '期末维护关注', unit: '篇', kind: 'dots',
          info: '待更新、暂停与禁止使用可以同时出现在同一份文档上，因此分别计数，不计算占比。',
          series: [{ key: 'knowledgeUpdates', name: '待更新', color: indigo }, { key: 'knowledgePaused', name: '已暂停', color: amber }, { key: 'knowledgeForbidden', name: '禁止使用', color: red }], metrics: [] }
      ]
    },
    'feedback-dashboard': {
      title: '反馈概览', layout: 'feedback', accent: amber,
      charts: [
        { id: 'feedback-main', title: '新增与解决趋势', unit: '个', hero: true, href: '#feedback', destination: '问题队列',
          info: '已解决仅计实际验收解决，其他结案和合并单独表达。',
          series: [{ key: 'feedbackNew', name: '新增问题', color: amber, fill: true }, { key: 'feedbackSolved', name: '已解决', color: teal }],
          metrics: [{ key: 'feedbackNew', label: '个新增问题' }, { key: 'feedbackSolved', label: '个已解决' }] },
        { id: 'feedback-backlog', title: '积压与等待验证', unit: '个',
          info: '按日末状态展示。待验证是未结问题的一部分，两个数量不能相加。',
          series: [{ key: 'feedbackOpen', name: '未结问题', color: slate, fill: true }, { key: 'feedbackAwaitingVerification', name: '等待验证', color: amber, dashed: true }],
          metrics: [{ key: 'feedbackOpen', label: '个期末未结' }, { key: 'feedbackAwaitingVerification', label: '个等待验证' }] },
        { id: 'feedback-closures', title: '结案类型', unit: '个', kind: 'ring', centerLabel: '个期间结案',
          info: '所选期间的结案按已解决、其他结案、已合并分别计数，不把说明结案或合并当成修复。',
          series: [{ key: 'feedbackSolved', name: '已解决', color: teal }, { key: 'feedbackOtherClosed', name: '其他结案', color: amber }, { key: 'feedbackMerged', name: '已合并', color: slate }], metrics: [] },
        { id: 'feedback-hours', title: '解决耗时', unit: '小时', decimals: 1,
          info: '只统计本期实际解决的问题，按解决数量加权平均。未结、其他结案与合并不混入解决耗时。',
          series: [{ key: 'feedbackHours', name: '平均解决耗时', color: teal }], metrics: [{ key: 'feedbackHours', label: '小时平均解决' }] }
      ]
    }
  };
})();
