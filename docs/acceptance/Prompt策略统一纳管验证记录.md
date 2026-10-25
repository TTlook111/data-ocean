# Prompt 策略统一纳管验证记录

日期：2026-10-25。范围为当前工作树中的场景策略管理、消费者接线、版本工作流与实际调用来源；不是部署、模型业务效果或页面视觉验收。

## 实现范围

- 9 类真实场景目录与变量含义、固定输出合同；运营与平台入口及旧链接跳转。
- 角色/任务作为同一策略包版本化；兼容既有纯文本；简单变量合同校验及只使用合成数据的预览。
- 编辑版本与已批准线上版本分别读取；草稿、驳回、回滚草稿不替换线上；批准时校验并事务切换活跃版本；开关独立于草稿。
- Python 所有业务 LLM 消费者接入统一策略解析；固定安全/输出约束、Java 权限过滤、AST、预算、解析及人工审核保留。
- Java 持久化实际调用回执，记录后台/内置、实际版本、合同、单向消息摘要、回退原因、返回/失败与耗时，不记录正文或密钥。回执失败告警但不覆盖主调用结果。
- 退役意图模板只读；新六类模板由 V72 初始化为停用待审草稿，不伪造批准。

## 自动化与构建

Java 定向：`PromptPolicyPersistenceTest`、`PromptTemplateServiceImplTest`、`PromptTemplateControllerAuthorizationTest`。使用独立 H2、Flyway 禁用且测试配置明确提供必需密钥；真实 Spring 事务代理覆盖工作流与指针切换。H2 中执行 V72 验证草稿初始化及既有内容保留，不证明 MySQL 语法/迁移成功。授权测试通过真实 S1 切面验证目录、回执、校验与维护/审核的权限边界。

结果：15 项全部通过，Java 打包通过。前端 49 项定向测试及最终生产构建通过；`git diff --check` 通过，构建保留大 chunk 提示。

```powershell
cd backend/DataOcean
mvn -q '-Dtest=PromptPolicyPersistenceTest,PromptTemplateServiceImplTest,PromptTemplateControllerAuthorizationTest' package
```

Python 定向：`test_prompt_scenes.py`、`test_knowledge_semantic_candidates.py`、`test_business_knowledge_candidate.py`、`test_iam_s1_graph_planning.py`、`test_iam_s1_graph_budget.py`、`test_iam_s1_graph_chart.py`、`test_iam_s1_graph_recovery.py`、`test_iam_s1_security.py`。115 项通过、1 项按条件跳过；策略专项 23 项在最终代码再次通过。模型和新增回执 HTTP 用 mock/隔离 transport 验证；覆盖九类合同、非法/缺失/停用/不可用回退、动态输入不二次渲染、真实字段消费者、预算与回执对应、失败不上传敏感正文，以及跨语言变量合同漂移。

```powershell
cd python-service
.\.venv\Scripts\python.exe -m pytest tests/test_prompt_scenes.py tests/test_knowledge_semantic_candidates.py tests/test_business_knowledge_candidate.py tests/test_iam_s1_graph_planning.py tests/test_iam_s1_graph_budget.py tests/test_iam_s1_graph_chart.py tests/test_iam_s1_graph_recovery.py tests/test_iam_s1_security.py -q
```

前端定向：`SemanticsCapabilityControl.test.ts`、`adminNavigation.test.ts`、`adminNavigationCapabilities.test.ts`，49 项通过。覆盖场景目录、配置未初始化、角色/任务版本包、独立生效版本、确认期间切换场景取消提交、调用记录失败与维护/审核能力隔离。运行 `npm run build`；构建结果见项目现状，本轮仍保留既有大 chunk 提示。

## 验证限制

- 未对本机或其他应用 MySQL 执行 V72，未改应用数据、账号、权限或模型配置，未重启运行中的 Java/Python 服务。新接口与新表需要在协调部署后真实核验。
- 未调用真实付费模型验证所有场景，未以合成预览证明模型遵循策略。模型返回也不等于输出解析、执行、人工采纳或业务正确。
- 未做本轮浏览器视觉、键盘、窄屏验收，没有生成宣称视觉通过的截图。
- 回执是尽力保存的可追溯记录，不是完整调用计费账本；网络或持久化失败可留下缺口。
- 旧查询任务效果统计继续明确标为历史关联口径；知识采纳率、摘要质量和业务题集需后续真实证据，不显示成已完成的自动质量评分。

## 对抗式审查后的修复

本轮先复现再修复四条边界：

- Java 独立 H2 中，查看 v2 后驳回并提交 v3，旧接口确实批准了未查看的 v3；角色模板也允许把 input 放进 system。两条失败用例在修复后通过。
- Python 中，动态变量被提升为系统角色的检查未拒绝；模拟慢回执网络会让已成功模型结果超时。两条失败用例转绿；额外验证了 Graph 先结算再入队，慢网络不阻断主结果。
- 前端中，保存触发的列表刷新未完成时，通过响应式路由切到另一场景，旧响应会把页面切回原场景；该失败用例修复后通过并保留新场景草稿。

修复：提交、批准、驳回必须提供实际查看的 expectedVersionNo；服务端同时匹配版本与预期状态，主表和活跃指针在 Spring 事务中更新。前端发送所见版本，冲突不自动重试最新版本。Java/Python 都拒绝角色中的动态变量，任务指令仍只做一次变量替换。

回执改为 lifespan 管理的单工作任务和有界队列（128 个等待项），请求只立即入队，预算结算先于回执入队；队列满、网络失败及关闭排空超时都可告警丢弃。测试覆盖慢网络、失败不覆盖主异常、饱和不阻塞、关闭取消/清空，以及消息正文不进入队列回执。没有增加外部服务或新迁移。

最终定向：Java 3 套件 19 项，前端 3 文件 51 项，Python 9 文件 141 项通过、1 项条件跳过（在上述 Python 命令中增加 tests/test_internal_auth.py）。Java 新增旧审核/驳回/提交版本拒绝、角色变量拒绝、真实事务回滚，前端新增旧响应隔离和批准版本参数，Python 策略专项现为 28 项。前后端构建与差异检查以本轮最终结果为准；部署及真实模型/页面验收限制保持不变。

## 第二轮复查修复

复查发现的两项 P2 已先补失败用例：同一场景刷新成功会把新输入替换为旧内容，刷新失败会移除整个编辑区；Java 策略校验接受 JSON 尾随值和整数截断版本，导致 Python 运行时拒绝并回退。

前端使用同步递增的编辑修订保护角色、任务及摘要。刷新成功和失败都核验请求、选择与编辑修订，存在后续编辑时不更新列表或重置编辑区，明确提示编辑已保留。保存请求在途发生的新输入也保留，不清空新摘要、不再次刷新覆盖。

Java 仅对策略读取启用 FAIL_ON_TRAILING_TOKENS，以 BigInteger 精确比较格式版本为 1，不改变全局 ObjectMapper。独立 H2 用例覆盖预览、保存拒绝，以及篡改待审内容后的批准拒绝与线上版本保留；Python 对应覆盖尾随标量、尾随对象、4294967297 三组输入。

本轮验证范围：Java 三套件、前端三个文件与 Python 策略专项；非全量重跑，不把上一轮 141 项误记为本轮成绩。数据库迁移、真实模型和页面视觉仍未执行，没有新增迁移或重启服务。

本轮最终结果：Java 20 项（3 套件）、前端 54 项（3 文件）、Python 策略专项 31 项通过；Java 打包、前端生产构建和 git diff --check 通过。前端保留原大 chunk 提示。原审查的 Java 独立复现程序重跑后，尾随值及 4294967297 两例均从 ACCEPTED 转为 REJECTED。
