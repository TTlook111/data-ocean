# 工作台 V3 与治理状态文案合同

基线 develop `da667aed4e007e6612d87e9ac9507b15601d936a`。本文件记录源码事实及样稿取舍，不是后端变更方案。

## REOPENED：不是统一的复查失败

| 来源 | 当前实现允许的转换 | 准确含义 |
| --- | --- | --- |
| 人工处理 | RESOLVED→REOPENED；REJECTED→REOPENED | 有权操作者将已结束处理的问题重新开启，恢复待确认流程 |
| 语义复查 | 同一语义问题复查结果为 ISSUE，原状态为 RESOLVED、REJECTED 或 AUTO_CLOSED→REOPENED | 原问题在语义复查中仍有异常，被重新纳入未解决范围 |
| 后续人工处理 | REOPENED→CONFIRMED／REJECTED；不能直接 REOPENED→RESOLVED | 需重新确认；并不代表原问数情景已通过核验 |

当前未解决集合仍是 OPEN＋CONFIRMED＋REOPENED。仅看到 REOPENED 不能断定触发者或原因，也不能说全部是自动重开、来源质量检查失败或新发现的问题。

样稿显示名：**需重新处理**。

简短说明：**此前已结束处理，现重新进入待确认流程；可能由人工重新开启，也可能由语义复查触发。**

“状态说明”使用可点击原生按钮与对话框，键盘可完整阅读，不只依赖 hover；详情保留原枚举说明。没有修改正式文案、状态机或 API。图形筛选仍使用单个 `status=REOPENED`，既有单状态 URL 限制和不完整聚合降级保持不变。

源码依据：

- [状态常量与未解决集合](../../../backend/DataOcean/src/main/java/com/dataocean/module/governance/entity/po/MetadataQualityIssue.java)
- [人工状态机及并发保护](../../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/impl/QualityIssueServiceImpl.java)
- [语义复查重新开启逻辑](../../../backend/DataOcean/src/main/java/com/dataocean/module/fieldsemantic/service/impl/SemanticCheckExecutionService.java)
- [当前正式“重新打开”确认说明](../../../frontend/src/views/admin/governance/IssueList.vue)

## 工作台的数据来源与权限

入口 `/admin/workbench` 仍为全局 `admin:workbench:view`。各区域的功能能力、负责源和个人业务授权分开，不能由角色名称或管理员身份推导。

| 区域 | 现有权威数据 | 样稿行为／边界 |
| --- | --- | --- |
| 有权来源名称与数量 | `GET /api/admin/datasources/simple` | 按 datasource:view 和负责源裁剪；名称、ID 是合成夹具，不展示连接地址或凭据 |
| 来源准备条件 | `GET /api/admin/datasources/readiness/batch` 的各来源 DTO | connectionReady／metadataReady／governanceReady／knowledgeReady／permissionReady、stage、blockReasons 分别解释；未知回执不补0、不纳入已满足 |
| 版本依据 | 同 DTO 的 publishedSnapshotId／snapshotVersion、latestCollectedSnapshotId／latestCollectedSnapshotVersion | 只显示该来源真实回执字段，不从“最新采集”推断已发布，不借来源查看权额外读元数据详情 |
| 准备关注提示 | 已取得完整准备回执后，按已知 askable 及 blockReasons 归类 | 源未知时总体分类数为“—”，明确未知数量；不计算五步完成率。正式大范围读取若只取预览回执，不能据此给总体分类计数 |
| 治理问题摘要 | `GET /api/admin/dashboard/stats` 的 openIssues | 统计 governance:issue:view 对应负责源中的未解决记录，不是个人任务或当前选中来源的问题数；失败未知，只有接口成功且明确0才显示0 |
| 审批有限预览 | `listIamS1AccessRequestQueue('PENDING',1,2)` 的 total／records | 现接口支持目的、来源、快照、表、申请字段与状态；仅展示两条合成记录，不产生个人归属或新的详情URL |
| 字段核对范围 | 已按 metadata:view 等合同核验的范围偏好 | 不是最近工作；范围未验证或身份变化时不携带旧 datasourceId/snapshotId |

来源预览最多三个，审批预览最多两条，完整浏览/处理回到现有专页。全量数量和预览条数分开。样稿没有读取个人最近访问或任务分派数据，也不新增这类接口。

默认合成来源：#2 已采集快照31/v4但未发布；#9连接未通过检查且未采集；#5已发布快照41/v1、基础条件满足但无活动索引。三份回执完整时两个来源需关注。全范围15条未解决问题与选中来源的准备状态分母不同，不与来源数或申请数相加。空场景定义为对应读取成功且相关结果确为空，不允许仅因来源列表空就推导其他域统计为0。

## 管理员 readiness 的两个关键事实

源码 [DatasourceReadinessServiceImpl](../../../backend/DataOcean/src/main/java/com/dataocean/module/datasource/service/impl/DatasourceReadinessServiceImpl.java) 的 `buildReadiness(datasourceId,false)`：

- permissionReady 使用 `hasAnyQueryGrant(datasourceId)`，不是当前用户 `currentUserCanQuery`；后台“授权已配置”不能解释为“你已获查询权”。
- askable 当前由 connectionReady、metadataReady、governanceReady、permissionReady 共同决定，未把 knowledgeReady 加入硬门禁；知识索引单独展示，样稿不改变规则。
- governanceReady 对应已发布快照的高危未解决问题阻断检查，不是全量语义正确率或质量全通过；没有发布版时不展示“质量通过”。

因此本版不用“你可以问数”按钮，不用管理员角色推导业务数据权，不把活动 RAG 缺失改成新的服务端禁止条件。没有发布快照时，治理列明确“无发布版”；准备回执未知时各列“待确认”。

## 精确入口合同

| 下一步 | 需要的现有能力 | 实际支持的路由 |
| --- | --- | --- |
| 核对元数据版本 | 对应来源 metadata:release:view | `/admin/releases?datasourceId=2`，不伪造从工作台直接发布 |
| 核对连接详情 | 对应来源 datasource:view | `/admin/data-sources/9`，只预览详情，不自动测试连接或修改配置 |
| 查看知识准备 | 对应来源 knowledge:view | `/admin/semantics/knowledge?datasourceId=5&tab=index`，发布与显式构建分开 |
| 字段治理 | 同来源 governance:field:view，目标页元数据辅助读取仍有自己的能力合同 | `/admin/governance/fields?datasourceId=2&snapshotId=31&tab=tasks`；未核验范围时仅进入工作区，不传旧定位 |
| 知识文档 | 同来源 knowledge:view | `/admin/semantics/knowledge?datasourceId=2&tab=documents`，不是猜测字段文档映射 |
| 审批 | security:approval:view | `/admin/access/iam-approvals`，普通队列入口；不添加未支持的 requestId 深链，不在工作台批准/拒绝 |
| 问题中心 | governance:issue:view 及目标范围 | `/admin/governance/issues` 普通入口，不承诺默认列表精准对应全范围15条摘要 |

样稿在显示按钮和触发处理器两处核对合成能力、当前来源和读取状态；真实服务仍是安全边界。无版本查看权时按钮禁用并解释，不以其他来源能力补权，不用回执 actionPath 任意绕过目标页能力或白名单。没有真实请求或写操作。

来源/筛选切换、身份切换清理旧选择与弹层；重读演示回调使用世代标记，迟到结果不切回旧身份。真实并发、撤权、空库、后端错误和大数据量需正式集成后实测，本轮不作真实验收结论。

导航仍遵循 [adminNavigation.ts](../../../frontend/src/router/adminNavigation.ts)：数据血缘在数据资产→关系与影响组→数据血缘二级，本轮未移动正式菜单。其他参考：[准备 API 类型](../../../frontend/src/api/admin/datasource.ts)、[现工作台](../../../frontend/src/views/AdminHomeView.vue)、[审批队列类型](../../../frontend/src/api/iamS1.ts)。
