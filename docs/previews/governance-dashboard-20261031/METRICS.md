# 治理总览样稿指标合同

基线：develop `64b2664f2d09939511eacfa1d69553f7a6e6625b`。页面定位 `/admin/governance`，现为QualityDashboard，范围是单数据源＋明确快照，不是首页工作台。

| 指标 | 含义、分母与范围 | 现有来源与权限 | 空/未知及支持程度 |
| --- | --- | --- | --- |
| 未解决问题总数 | 当前快照OPEN+CONFIRMED+REOPENED；单位条，不是唯一对象数 | quality-issues/summary.totalUnresolved；governance:issue:view＋该快照负责源 | 读取成功0才是0；无权/失败为不可用；直接支持 |
| 来源/语义问题分布 | 同一未解决问题集合的SOURCE/SEMANTIC数量；分母totalUnresolved | summary.domainCounts；同上 | 缺字段/两域总和不能核对时未知，不能补零；直接支持 |
| 待处理状态分布 | OPEN/CONFIRMED/REOPENED互斥计数；分母totalUnresolved | summary.statusCounts；同上 | 查询成功0可显示，无权/失败不显示空饼；直接支持 |
| 涉及表数量与排行 | 完整未解决问题记录按tableName聚合；计数是问题条数，distinct表数另标 | 当前snapshot quality-issues分页，逐一查询三种未解决状态，governance:issue:view | 仅前端派生可支持；不能用recentIssues或一页排行。须全量去重、核对总数与范围。分页过程中对象变化或读取不完整则不声称总体排行；当前没有独立服务端聚合/一致读取水位接口 |
| 来源校验覆盖 | 当前latest batch的rules按SUCCESS/FAILURE/NOT_APPLICABLE/NOT_EXECUTED分组；分母该批次规则总数，不是字段总数 | quality-check-batches/latest；governance:view | SUCCESS表示检查器执行成功，不等于质量无问题；PARTIAL/complete=false不展示本次完整评分；无批次≠历史从未执行 |
| 范围与快照 | 明确datasourceId、snapshotId、状态 | 现有ScopeBar/metadata快照读取需metadata:view及范围；各指标另按自身能力重验 | 不借辅助接口补权；换源/快照清空选择、页码与详情，不沿用旧对象 |

不放入本版：表/字段治理状态分布需额外metadata读取且仅是对象准入，不是解释有效率；待审核积压需完整任务/修订范围与相应能力，当前总览摘要没有统一权威计数；来源评分不等于语义正确率。审核、复查、文档发布、显式RAG、索引成员资格各自独立，不合成为完成率。现有总览无权威时间序列，不绘折线。

样稿的12条问题全为合成且完整载入：SOURCE8、SEMANTIC4；OPEN7、CONFIRMED3、REOPENED2；products5、orders4、customers2、inventory1。所有图形选择只过滤同范围的问题明细，图表仍保留快照整体分母。真实读取不完整场景保留summary的权威总数，隐藏总体排行，只标已载入明细。

生产代码/后端不改。此为桌面产品样稿，无模型/API调用或业务写入；完整聚合在正式采用前仍需设计加载上限、取消/变更检测或后端聚合方案，不能把样稿当已实现新接口。

## 可核对的源码

- [治理总览组件](../../../frontend/src/views/admin/governance/QualityDashboard.vue)
- [问题摘要、明细与批次类型](../../../frontend/src/api/admin/governance.ts)
- [问题摘要实现](../../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/impl/QualityIssueServiceImpl.java)
- [各读取接口权限](../../../backend/DataOcean/src/main/java/com/dataocean/module/governance/controller/MetadataGovernanceController.java)

若问题没有可定位的表名，应独立标明未定位记录，不删除也不伪造为一张表；当前12条演示记录均有明确表名。聚合不能从最近几条记录推断完整排名，也不能把某页或变化中的分页集当事务快照。
