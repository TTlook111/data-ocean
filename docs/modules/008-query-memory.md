# 008 S1 问数与会话记忆

用户在一个数据源内用自然语言提问。Java 保存会话、消息、任务、执行尝试与受保护结果，Python 编排检索、SQL 生成、语义核对和只读执行。前端通过 Java 创建任务、读取进度和结果，也可恢复会话、查看 SQL、导出或提交反馈。[问数页面](../../frontend/src/views/query/QueryDatasourceView.vue)、[问数 API](../../backend/DataOcean/src/main/java/com/dataocean/module/query/controller/IamS1QueryController.java)

## 一次提问的执行路径

1. **建立任务。** `submit` 绑定用户、数据源和会话，选择当前授权及知识构建版本，保存任务后派发。后续步骤只能使用任务绑定的来源；检索、规划所见字段仍受当前权限约束。[任务服务](../../backend/DataOcean/src/main/java/com/dataocean/module/query/service/impl/IamS1QueryServiceImpl.java)
2. **检索与生成。** `run_query_graph` 从检索、资源规划进入 SQL 生成和语义核对。规划保留用户原始问题，跨表推理需有经确认的关系依据。问题缺少业务条件可返回澄清；模型、限流或网络故障有各自的失败处理。[查询图与节点](../../python-service/dataocean/iam_s1/graph.py)
3. **授权与只读执行。** Java 为每次 SQL 尝试授权并登记开始。Python 做 AST 与字段用途检查，注入参数化行条件，在选定的业务库执行。Java 复核来源并保护结果，Python 使用受保护数据核对答案和制作图表。[尝试授权与保护](../../backend/DataOcean/src/main/java/com/dataocean/module/query/service/impl/IamS1QueryAttemptServiceImpl.java)、[SQL 校验](../../python-service/dataocean/iam_s1/sql_security.py)
4. **保存与读取。** 进入 `COMPLETED` 需要非空 `attemptId`、与本任务同一条 `PROTECTED` 尝试匹配的精确 SQL 哈希。前端可通过 SSE 看进度，最终结果从 Java 的任务查询读取。历史重读、导出与 SQL 查看按当前权限处理。[完成与回读](../../backend/DataOcean/src/main/java/com/dataocean/module/query/service/impl/IamS1QueryServiceImpl.java)、[任务订阅与轮询](../../frontend/src/composables/useQuerySubmit.ts)

## 预算、取消与恢复

图和 Java 预算记录共同约束每问最多 3 次 SQL 尝试、8 次模型调用、2 次 Embedding 调用、90 秒及 0.10 元预估 AI 费用。重试、格式修正和恢复沿用本任务的余额与期限。Redis checkpoint 初始化或恢复失败会停止执行。恢复遇到已有受保护结果时核对尝试和 SQL 哈希，复用该结果以避免重复执行。[预算预留与结算](../../backend/DataOcean/src/main/java/com/dataocean/module/query/service/impl/IamS1QueryBudgetServiceImpl.java)、[图恢复与执行](../../python-service/dataocean/iam_s1/graph.py)

用户取消任务时，Java 保存终态及待投递的取消意图，提交后向 Python 发送。投递失败可由后台重新领取；远端接收回执记录的是请求接收情况，调用是否已实际停止需另行判断。[取消投递与重试](../../backend/DataOcean/src/main/java/com/dataocean/module/query/service/impl/IamS1QueryCancellationServiceImpl.java)

## 会话与长期摘要

每个会话固定数据源。Java 向 Python 传递稳定的 `threadId`、最近五个已结束轮次和可用的较早摘要，摘要复用需匹配权限修订与范围指纹。失败、超时或取消会保留真实终态，不能作为成功取数的依据。长期摘要由专用执行器异步更新，Redis checkpoint 仅用于恢复图执行，查询结果不缓存。[会话与消息](../../backend/DataOcean/src/main/java/com/dataocean/module/query/service/impl/ConversationServiceImpl.java)、[上下文与长期摘要](../../backend/DataOcean/src/main/java/com/dataocean/module/query/service/impl/ConversationContextSummaryServiceImpl.java)
