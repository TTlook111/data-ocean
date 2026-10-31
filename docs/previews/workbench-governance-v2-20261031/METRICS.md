# V2 指标、权限与导航合同

核对基线 develop `097a505acdb84c25d48268d25bad19a9b306d332`。以下区分现有权威数据、前端可派生数据和目前不支持的内容；HTML 使用合成夹具，不执行这些读取。

## 工作台

页面 `/admin/workbench`，`contextMode: none`，入口为全局 `admin:workbench:view`。页面可见不代表拥有所有域的数据。各功能绑定和负责源分别检查，系统管理员身份不替代业务查询授权。

| 展示内容 | 权威来源与统计范围 | 权限及已支持入口 | 空/未知与取舍 |
| --- | --- | --- | --- |
| 未解决问题摘要 | `GET /api/admin/dashboard/stats` 的 `openIssues`；服务端按 `governance:issue:view` 的负责源统计 OPEN、CONFIRMED、REOPENED | 工作台入口权＋问题查看能力；`/admin/governance/issues` 是普通模块入口 | 不是按 assigneeId 过滤的个人待办；无能力时不把服务端占位 0 当业务 0，失败显示未知 |
| 待审批申请摘要 | `listIamS1AccessRequestQueue('PENDING',1,1)` 的分页总数，现有 `/api/iam-s1/access-requests/queue` | 全局 `security:approval:view`；`/admin/access/iam-approvals` | 不等于个人最近处理；可查看不等于可批准；不额外请求元数据 |
| 来源准备提示 | 数据源列表＋`GET /api/admin/datasources/readiness/batch` 的已知结果；统计明确的阻断来源，非推测全站阻断 | `datasource:view` 及对应负责源；`/admin/data-sources` 普通入口 | 未返回、失败或部分结果不能当就绪/0；连接、采集、治理、知识、授权各自独立 |
| 本机范围偏好 | 现有 AdminContext 持久化的 datasourceId/snapshotId，仅作为进入偏好；正式打开需重验存在、范围与能力 | 范围读取按 `metadata:view`；不具备能力的审批组合不展示此块 | 没有任务、草稿或最近访问合同，不能叫“继续上次” |
| 主工作入口 | 现有路由能力组合，非统计接口推断个人优先级 | 维护示例 `governance:field:view` → `/admin/governance/fields?datasourceId=2&snapshotId=31&tab=tasks`；审批示例 → 审批页；观察示例 `governance:view` → 当前范围总览 | 只选择当前确实可访问入口；正式集成还需对多能力组合定义确定优先级和范围复验。无入口给明确无权说明，不能暗增权限 |
| 次工作入口 | 最多两个现有高频任务 | `knowledge:view` → `/admin/semantics/knowledge?datasourceId=2&tab=review`；`governance:view` → 总览；`metadata:view` → `/admin/assets?datasourceId=2` | 审核 tab 可查看不代表允许审核。未通过能力核验的入口不展示 |

现有 `recentActivities` 是负责范围的系统审计活动，不是个人访问历史，本版不用它制作“最近工作”。未展示总用户、全系统资产卡片或虚构个人归属统计。

工作台 15 条跨有权范围摘要与问题页当前快照/全部状态默认列表不是同一集合。点击仅显示普通模块入口说明，**不承诺目标页精准列出这 15 条**。

## 治理总览

页面 `/admin/governance`，范围是单 datasourceId＋明确 snapshotId；导航入口为该来源的 `governance:view`。各指标再按自己的读取能力独立判断；来源选择及快照资料不能依靠额外补权获取。

| 分析问题／图形 | 口径与分母 | 现有数据来源 | 权限与支持程度 | 缺失／部分结果 |
| --- | --- | --- | --- | --- |
| 有多少未解决问题 | 问题记录条数；OPEN＋CONFIRMED＋REOPENED，按问题 ID 去重；不是受影响字段数 | `GET /api/admin/snapshots/{snapshotId}/quality-issues/summary` 的 totalUnresolved | `governance:issue:view`＋该快照负责源，直接支持 | 读取成功 0 才是 0；无权/失败为未知，不沿用旧数 |
| 属于哪个问题域 | 同一未解决集合中互斥的 SOURCE／SEMANTIC，分母 totalUnresolved | 同一 summary.domainCounts | 同上，直接支持 | 缺字段、未知枚举或分域合计无法对账时不得补 0 或宣称完整构成 |
| 真实处理结构 | OPEN／CONFIRMED／REOPENED 互斥状态，分母 totalUnresolved | 同一 summary.statusCounts | 同上，直接支持；不完整明细时用独立横条降级 | 摘要有权且有效可以保留；不从已加载一页推总量 |
| 集中在哪些表；涉及多少表 | 完整未解决记录按表×状态聚合；条形单位“条问题”；涉及表为不同表名数，非不同字段数 | 当前快照 quality-issues 分页，逐一查询三个单状态，完整去重、核对摘要后派生 | `governance:issue:view`；**无专门服务端排行或一致读取水位接口**，样稿演示派生能力 | 分页不全/范围变化/对账失败即撤下总体表排行及表数，保留权威摘要。无表名问题需单独标明未定位，不伪造成表或丢弃 |
| 校验是否完整执行 | 最近批次 rules 的 SUCCESS／FAILURE／NOT_APPLICABLE／NOT_EXECUTED；分母该批次规则数 | `GET /api/admin/snapshots/{snapshotId}/quality-check-batches/latest` | `governance:view`＋快照负责源；独立数据源 | 无批次≠历史未执行；无权≠0；SUCCESS 仅说明执行成功。PARTIAL/complete=false 不显示本次完整评分 |
| 当前统计范围 | 来源、快照、版本和状态 | 当前 AdminContext／元数据读取 | `metadata:view` 等原合同独立检查，不借辅助接口扩大能力 | 切来源/快照清空旧图与对话框；异步以身份＋范围世代隔离 |

本版不把质量规则次数当字段覆盖率，不把同一问题的多维切片相加成问题总数。SOURCE 与 SEMANTIC 可相加，是因为服务端定义它们为同一集合的互斥域；表×状态完整聚合同样只按唯一问题 ID 统计。

默认合成数据：来源 #2、快照 #31 共 12 条，SOURCE8／SEMANTIC4，OPEN7／CONFIRMED3／REOPENED2；products5、orders4、customers2、inventory1。独立批次示例共 8 条规则：执行成功5、失败1、不适用1、未执行1，状态 PARTIAL、complete=false。没有把 5/8 标成质量通过率。

## 图形→目标页筛选

真实问题页初始化及监听支持 `issueDomain`、`tableName`、`dimension`、`severity`、**单个 status**、`assigneeId`、`page`；来源/快照由上下文读取。后端 status 使用单值匹配，未传 status 则包含全部状态，**没有“全部未解决”并集参数**。

| 点击对象 | 样稿的目的地行为 | 真实支持边界 |
| --- | --- | --- |
| products 的待处理色段 3 | 预览 `/admin/governance/issues?datasourceId=2&snapshotId=31&tableName=products&status=OPEN` | 表＋单状态精确对应；目标仍重验来源、快照、权限 |
| 已确认全局状态 3 | 预览相同范围＋`status=CONFIRMED` | 权威摘要可以直接对应一个状态 |
| SOURCE 共 8 或 products 共 5 | 先选 OPEN／CONFIRMED／REOPENED，再预览带域或表＋单状态的真实 URL | 不发送虚构 `status=UNRESOLVED`，不把默认全部状态当未解决并集 |
| 只取得摘要时点击 SOURCE | 域总数已知，但域×状态数量显示“—”；选状态后预览合法 URL | summary 的两个边际分布不能推出交叉计数；不补造 5／2／1 |
| 来源校验覆盖带 | 只读数值和批次说明 | 现有目标没有匹配本批次规则执行状态的筛选合同，本版不伪造精准钻取 |
| 工作台其他入口 | 目的地预览注明普通工作区入口 | 不能把跨范围摘要量与目标默认筛选量视为一致 |

预览弹层只展示目标范围、状态和路由，**不列问题明细**。正式问题处理仍属于专页。

## 排除项与正式实施前条件

- 无权威时间序列，本版不画趋势、SLA 或积压天数。
- 有效解释、候选草稿、审核、复查、文档发布和活动索引不合成一个完成率；本页摘要也没有这些概念的统一可靠分母，因此不凑图。
- 没有个人任务/最近访问/精确任务恢复接口，本版收缩为能力工作入口与范围偏好，不新增后端。
- 表排行正式接入需明确完整分页的规模上限、取消与身份/范围隔离、唯一 ID 去重、摘要对账及变更检测。现有接口没有事务水位；无法证实完整一致就按样稿降级，不以“所有页请求成功”宣称事务一致。
- 每个指标分别持有 loading、noaccess、unavailable、nodata、true0、partial 状态；真实撤权/网络错误仍需真实环境验证。样稿的“批次无权”用于演示独立读取拒绝分支，不宣称同一稳定身份既有相同范围能力又必然被拒；能力变更和实际响应以服务端为准。
- 未请求后端改造、政策变更或正式落地；如果今后要无条件提供全局表排行或“所有未解决”精准一跳，需另行确认聚合及筛选接口方案。本版没有实现这些接口。

## 源码核对入口

- [导航、入口能力与上下文模式](../../../frontend/src/router/adminNavigation.ts)
- [实际路由](../../../frontend/src/router/index.ts)
- [现工作台](../../../frontend/src/views/AdminHomeView.vue)、[摘要类型](../../../frontend/src/api/admin/dashboard.ts)、[摘要服务范围裁剪](../../../backend/DataOcean/src/main/java/com/dataocean/module/dashboard/service/impl/DashboardServiceImpl.java)
- [审批队列 API](../../../frontend/src/api/iamS1.ts)、[来源准备 API](../../../frontend/src/api/admin/datasource.ts)、[范围上下文](../../../frontend/src/stores/adminContext.ts)
- [问题页 query 读取与持久化](../../../frontend/src/views/admin/governance/IssueList.vue)
- [问题摘要、列表与批次类型](../../../frontend/src/api/admin/governance.ts)
- [读取权限注解](../../../backend/DataOcean/src/main/java/com/dataocean/module/governance/controller/MetadataGovernanceController.java)
- [问题摘要与单状态查询实现](../../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/impl/QualityIssueServiceImpl.java)

导航继续遵循：数据治理→治理总览／问题中心；数据血缘位于数据资产→关系与影响组→数据血缘二级，路由 `/admin/assets/lineage`，本版未移动正式菜单。
