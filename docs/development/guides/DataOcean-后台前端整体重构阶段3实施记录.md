# DataOcean 后台前端整体重构：阶段 3 实施记录

## 本次范围

阶段 3：数据资产。

本次只修改前端，不修改 Java、Python、数据库、权限码和后端 API URL。

## 已落地

- 新建资产目录 AssetsView.vue：
  - 使用数据源内实体接口获取资产。
  - 只展示当前数据源正式发布快照同步后的资产。
  - 前端完成关键词和实体类型过滤。
  - 支持跳转可分享资产详情 URL。
- 保留并接入资产详情页面 AssetEntityDetailView.vue：
  - 通过 URL 锁定实体。
  - 展示实体关系、血缘和下游影响。
- 新建版本发布工作区 ReleasesView.vue：
  - 候选快照列表。
  - 当前正式发布版本。
  - DRAFT、CHECKING、ISSUE_FOUND、APPROVED、PUBLISHED、EXPIRED 状态展示。
  - 开始检查、批准、发布、撤回和审计记录入口。
  - 版本历史和差异入口。
- 新建可分享快照差异页 SnapshotDiffView.vue：
  - URL：/admin/releases/snapshots/:snapshotId/diff/:compareId。
  - 展示新增/删除表和字段变化。
  - 差异页面不自动改变快照状态。
- 版本发布路由已切换到新工作区；旧元数据路径继续通过重定向兼容。

## 验证

- frontend/npm run build 通过。
- 浏览器已验证：
  - /admin/assets 可加载正式资产范围页面。
  - /admin/releases 可加载版本发布工作区。
  - /admin/releases/snapshots/:snapshotId 可打开快照详情。
  - /admin/releases/snapshots/:snapshotId/diff/:compareId 可打开差异页面。
  - Java 服务不可用时，页面显示区域错误，不展示伪造资产或版本数据。

## 尚待真实环境补验

本次验证期间 Java 后端 127.0.0.1:8080 未启动，因此以下场景尚未声称完成：

- 真实正式资产目录加载和实体详情。
- 快照检查、批准、发布和撤回。
- 快照审计记录和版本差异真实数据。
- 采集任务成功后进入快照详情，再进入治理问题列表。

阶段 3 退出条件需要在真实 * 超级管理员和后端数据可用后继续验收。
