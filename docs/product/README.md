# 产品方案与模块解说

[产品方案](产品方案.docx)定义业务目标、角色、流程、状态和边界。模块解说帮助定位和理解，不维护第二套详细规则，也不记录开发经过。整体阶段见[文档导航](../README.md#当前阶段)。

## 按模块读取

| 章 | 解说 |
| --- | --- |
| 1 | [项目定位与整体流程](modules/01-overview.md) |
| 2 | [人员角色与权限](modules/02-people-access.md) |
| 3 | [数据源接入与元数据采集](modules/03-datasources.md) |
| 4 | [数据治理与业务知识审核](modules/04-governance.md) |
| 5 | [知识库与发布管理](modules/05-knowledge-release.md) |
| 6 | [智能问数与查询流程](modules/06-query.md) |
| 7 | [用户反馈与持续改进](modules/07-feedback.md) |
| 8 | [治理与问数运营看板](modules/08-dashboard.md) |
| 9 | [模型管理与安全调用](modules/09-models.md) |

## 按需读取原文

在仓库根目录运行：

```powershell
.\tools\read-product-plan.ps1
.\tools\read-product-plan.ps1 -Chapter 2
.\tools\read-product-plan.ps1 -Chapter 2 -Section '管理能力与可授予上限'
```

默认先列章节，指定章节后列小节；`-Section` 输出对应正文和表格，`-Full` 才输出整章。图示查看方案原件，不把文本提取当作完整图示信息。

## 维护边界

业务结论变化时更新方案对应条款和受影响解说；只改界面或技术细节时维护各自设计。解说不复制全部原文，不把尚未确认的技术建议写成已选架构。
