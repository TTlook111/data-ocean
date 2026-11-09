# 005 元数据版本与审核

版本模块管理每个数据源采集到的元数据快照。它记录审核状态、选择当前正式快照，并提供同源版本差异和操作记录。版本列表展示各次采集的快照，审核、发布、过期与撤回的经过由审计日志记录。

## 业务路径

| 操作 | 现有实现 |
| --- | --- |
| 查看与比较 | 按数据源读取版本历史与当前正式快照。比较时校验两侧快照的可见范围和数据源，按选定顺序给出表、字段的结构差异。[版本入口](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/controller/SnapshotVersionController.java)、[列表与比较](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/impl/SnapshotLifecycleServiceImpl.java) |
| 审核 | `changeStatus` 校验原状态和目标状态，记录操作者与原因。从草稿进入检查，存在问题时可进入 `ISSUE_FOUND`。转入 `APPROVED` 前重新计算审核条件。[状态迁移](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/impl/SnapshotLifecycleServiceImpl.java)、[状态定义](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/enums/SnapshotStatus.java) |
| 发布与撤回 | `publishSnapshot` 在同一事务内将旧正式版转为 `EXPIRED`、候选转为 `PUBLISHED`，并记录审计。撤回需填写原因，仅能将当前正式版转回 `APPROVED`，撤回后该源暂无正式快照。[发布服务](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/impl/SnapshotPublishServiceImpl.java)、[发布页面](../../frontend/src/views/admin/releases/ReleasesView.vue) |
| 发布后处理 | 事务提交后，监听器异步处理通知并尝试发起来源质量检查。实际结果在质量检查模块中记录。[发布事件监听](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/event/SnapshotPublishedEventListener.java) |

## 发布条件与并发

未解决的来源高风险问题阻断审核通过和发布。发布还要求快照中至少有一张处于 `NORMAL` 或 `RECOMMENDED` 状态的表。未解决的语义高风险问题产生警告。预览、审核与发布共用 `evaluate` 的判定。[发布门禁](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/impl/SnapshotReleaseGateServiceImpl.java)

发布请求携带候选的预期状态和预期正式快照 ID，首次发布时明确传入空 ID。服务在同源锁内重新读取两者并校验门禁，再更新状态。并发替换、门禁变化或撤回目标已变都会导致请求失败。正式快照可以供知识文档选择，文档发布和 RAG 构建另有独立入口。[发布与撤回事务](../../backend/DataOcean/src/main/java/com/dataocean/module/versioning/service/impl/SnapshotPublishServiceImpl.java)
