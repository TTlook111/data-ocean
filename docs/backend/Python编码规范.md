# DataOcean Python 编码规范

本页补充 [三端共用总则](../engineering/代码风格与项目组织规范.md) 中与 Python AI 服务有关的实现规则。工程目录和依赖锁定见[工程组织与版本基线](../engineering/工程组织与版本基线.md#python-api-与执行器)，节点及配置行为以[产品方案第 9 章](../product/modules/09-models.md)和[模型配置原型说明](../frontend/pages/models.md)为准。

## 模型按业务节点分配

- 有权限的用户在系统「模型配置」中填写模型及凭据，在「节点列表」为各业务节点独立分配模型。同一模型可供多个节点使用。Python 调用传入服务端约定的业务节点标识，例如表说明生成、字段解释生成；准备资料、解析结果等图步骤沿用所属业务节点的调用契约，无需编入模型分配清单。
- Java 管理节点分配、配置修订以及凭据。Python 通过受认证的内部接口按当次任务获取固定的模型配置修订和受控秘密引用，调用前核验仍有执行资格，并按当次修订调用。未分配、停用或接口能力不匹配时提供明确状态。普通改绑用于后续新任务，恢复沿用当次已固定修订，当前停用及撤权仍约束后续外发。Embedding 按[向量配置与索引一致性规则](../architecture/知识版本与索引一致性.md#embedding-模型变更)使用匹配的生效配置。
- `.env` 和 `.env.example` 维护监听、日志、Redis、Java 内部地址、服务密钥文件路径等进程启动参数。运行参数增删时同步启动说明和示例，示例只放可公开的默认值或占位值。正式模型的服务地址、模型名、凭据和分配均来自系统配置，禁止以进程环境变量、模型厂商默认值或自动换模型兜底。模型秘密只在必要调用层解析，按[凭据保护规则](../architecture/安全执行与审计.md#凭据保护与轮换)处理。

## LangChain 模型适配

模型类型用于匹配节点所需能力，接口格式用于选择 `adapters` 中的调用实现。按系统保存的接口格式选用对应 LangChain 集成，显式传入当次服务地址、模型、凭据和调用限制。生成模型按以下两种协议接入。

| 接口格式 | LangChain 集成 | 调用约束 |
| --- | --- | --- |
| OpenAI Chat Completions | `langchain-openai` 的 `ChatOpenAI` | 显式指定 `use_responses_api=False`。通义等兼容服务的扩展参数仅在验证过的服务和模型上使用，例如通过 `extra_body` 传入 `enable_thinking`。 [ChatOpenAI 参数参考](https://reference.langchain.com/python/langchain-openai/ChatOpenAI) · [通义接口文档](https://www.alibabacloud.com/help/en/model-studio/qwen-api-via-openai-chat-completions) |
| Anthropic Messages | `langchain-anthropic` 的 `ChatAnthropic` | 由集成处理 Messages 请求、鉴权与内容块。结构化生成按服务及模型能力选用原生 JSON Schema 或受支持的工具调用方式。 [LangChain ChatAnthropic](https://docs.langchain.com/oss/python/integrations/chat/anthropic#structured-output) |

- 适配器负责各协议的请求、认证、响应和错误转换，工作流只使用类型化的生成结果和诊断字段。SDK 无法表达必要能力时先核对厂商专用集成，再考虑局部补充。Embedding 和 Rerank 按各自接口格式接入。通用 JSON Rerank 按完整 HTTPS URL 发送扁平的 `model`、`query`、`documents`、`top_n`，要求 `results` 为原候选的完整集合。每项须有唯一的原始 `index` 和有限数值 `relevance_score`。Cohere Rerank 沿用 `/rerank` 路径及归一分数约束。
- 结构化结果按服务和模型实测能力选用 `with_structured_output` 的具体方法或受支持的 JSON 模式，并使用 Pydantic 校验。使用严格 JSON Schema 时，开放键的字典可在模型传输层改为有类型的列表，再转换为业务字段。业务节点继续核对允许字段、引用和实际来源。解析失败和模型拒绝按实际原因处理。 [LangChain 结构化输出](https://docs.langchain.com/oss/python/langchain/models#structured-output) · [Claude 的动态键约束](https://platform.claude.com/docs/en/build-with-claude/structured-outputs#objects-with-arbitrary-keys)
- 连接测试使用合成输入并复用同一格式适配器。每种协议在部署环境中验证鉴权、响应和节点所需的结构化能力，系统配置只展示已实现并验证的格式。业务调用返回可审计的节点、实际模型、配置修订、结果状态、耗时、可取得的用量及失败原因。日志和追踪只保留获准的诊断字段，模型凭据、完整提示词、原始业务资料及完整响应不得写入诊断内容。
- 业务入口为实际模型外发绑定节点及原任务，生成与向量适配器按每个提供方请求登记。正式 Python API 和 Worker 都使用原有 `AI_JAVA_URL` 与 `AI_WORKER_PRIVATE_KEY` 向 Java 写回。登记未确认时不发送提供方请求，已发送但结果未确认的请求超期作为待核查记录。连接测试使用独立回执。

## LangGraph 工作流与故障恢复

- 固定流程使用 `StateGraph` 表达顺序和显式分支。只有业务确需模型自主选择工具或动态决定下一步时才使用 Agent。各工作流维护自身节点、提示词和类型化状态，节点返回必要的状态更新。进程依赖和当次运行上下文可通过 `Runtime` 传入，模型凭据及客户端不进入图状态、检查点或提示词。 [工作流与 Agent](https://docs.langchain.com/oss/python/langgraph/workflows-agents) · [Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api)
- 把模型请求等外部操作放在明确的节点边界，区分超时、限流、鉴权失败、返回格式错误和结果待核查。调用超时取配置上限和任务剩余预算中更严格的一项。重试也消耗任务预算，明确由哪一层重试，避免 SDK、图节点和 Worker 的重试次数叠加。仅在符合任务协议且确认允许重试时退避重试。已发送且结果未知的请求先核对原调用状态，检查点恢复和队列重投都不能作为直接重发的依据。 [LangGraph 故障处理](https://docs.langchain.com/oss/python/langgraph/fault-tolerance) · [调用与任务协议](../architecture/调用与任务协议.md#失败恢复)
- 需要暂停或跨进程续作的流程按[存储与会话状态](../architecture/存储与会话状态.md#redis-检查点的持久化与恢复边界)复用 Redis Checkpointer，图中保存续作所需的最少状态及引用。恢复先根据 Java 的任务状态、执行代次和回执核对资格与已完成操作。外部副作用用稳定业务标识做幂等或核查。建议和最终业务状态依然通过 Java 核验、保存及交付。
