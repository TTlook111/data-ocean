# SQL 生成与修正

> 状态：现行代码导航；核对日期：2026-10-26。这里只核对源码定位，不代表本轮运行或业务验收通过。
>
> 上一级：[模块说明](../008-query-memory.md) · [功能导航](../README.md)

## 功能说明

定位资源规划、生成、语义检查、修正分支与结果核对。涉及授权和 AST 时继续读 IAM 专题。

## 修改位置速查

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 流程与条件分支 | [graph.py](../../../python-service/dataocean/iam_s1/graph.py)：`_graph`、`_after_semantic`、`_after_execution`、`_after_result` | 节点连线决定修正、澄清和失败去向。 |
| 规划、生成、语义核对 | [graph.py](../../../python-service/dataocean/iam_s1/graph.py)：`_plan_node`、`_generate_sql_node`、`_semantic_check_node`、`_verify_result_node` | 不要把服务错误转成业务澄清。 |
| 提示词变量与渲染 | [service.py](../../../python-service/dataocean/iam_s1/service.py)：`sql_prompt_variables`、`render_sql_prompt` | 同时查看 iam_s1/prompts/sql_generation.j2 与受管 Prompt。 |
| 受管策略的实际解析 | [graph.py](../../../python-service/dataocean/iam_s1/graph.py)：`_budgeted_llm`；[scenes.py](../../../python-service/dataocean/prompt/scenes.py)：`resolve_scene` | SQL 节点先用 managed=False 渲染基础输入，再由统一预算调用解析场景策略。 |
| 授权、执行与结果保护 | [graph.py](../../../python-service/dataocean/iam_s1/graph.py)：`_authorize_execute_protect_node` | 保持逐次授权和受保护结果合同。 |

## 实现链路

_graph 装配 retrieve → plan → generate_sql → semantic_check → authorize_execute_protect → verify_result；条件边可返回检索或生成，不能将其当作无条件线性流水线。

修改最终模型提示词时，跟随 `_generate_sql_node → render_sql_prompt(managed=False) → _budgeted_llm → resolve_scene`，同时核对固定合同与受管策略；只修改本地模板不能保证最终提示词按预期变化。

## 关键约束与关联影响

改提示词不放宽 AST。SQL 修正仍受[执行预算](执行预算与失败处理.md)限制；权限规则见[查询授权与结果保护](../iam/查询授权与结果保护.md)。

规划的 selectedRelationIds 只填写本题采用的已审核 BUSINESS_RELATION 身份；无关系则为空。全部所选关系的完整依赖、所选表和当前 JOIN usage 校验成功后才原子补键，失败不留下半个规划；未知、未审或越界 ID 拒绝。SQL 上下文排除未采用的候选，旧 JOIN_PATH 不直通。

候选被冻结、被召回、进入提示词与 SQL 实际采用是不同证据。字段重合、模型自报或引用列表不能证明某条知识已被采用；还需核对受保护 SQL、实际使用/输出来源和独立业务预期。已有合规知识却未进入上下文应查适配缺陷，不能假装缺资料；供应商、网络及格式故障归系统失败。

## 测试与验证入口

- [test_iam_s1_graph_planning.py](../../../python-service/tests/test_iam_s1_graph_planning.py)：规划与生成相关场景。
- [test_iam_s1_security.py](../../../python-service/tests/test_iam_s1_security.py)：SQL 安全边界。

真实业务问题的准确率与生成延迟需另做模型验收，不能从单测推断。
