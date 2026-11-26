# DataOcean Python AI 工程

正式包为 `src/dataocean_ai`，使用同一份 `pyproject.toml` 和 `uv.lock` 构建。版本与工具要求见[后端基线](../docs/backend/README.md#版本基线与验证边界)。内部 API 与 AI Worker 共用此包，分进程运行。

当前公共基线包括集中配置、UTC 日志、明确类型的普通 JSON 返回、校验后的 `X-Trace-Id` 诊断关联及可运行的 `/health`。健康接口只报告本进程可响应，不代表 Redis、Milvus、模型或执行器已经就绪；浏览器业务仍统一由 Java 提供，Python 不管理人员、角色或平台 MySQL 事实。

## 源码导览

| 文件 | 职责 |
| --- | --- |
| [dataocean_ai/__init__.py](src/dataocean_ai/__init__.py) | 声明正式 Python 包 |
| [api/__init__.py](src/dataocean_ai/api/__init__.py) | 声明内部 HTTP API 子包 |
| [api/app.py](src/dataocean_ai/api/app.py) | 创建并启动 FastAPI 应用，提供健康接口、请求追踪和基本错误响应 |
| [core/__init__.py](src/dataocean_ai/core/__init__.py) | 声明共用运行能力子包 |
| [core/config.py](src/dataocean_ai/core/config.py) | 读取并校验正式应用的运行参数 |
| [core/logging.py](src/dataocean_ai/core/logging.py) | 配置共用的 UTC 进程日志 |
| [core/result.py](src/dataocean_ai/core/result.py) | 定义普通 JSON 接口的泛型返回结构 |
| [governance/queue.py](src/dataocean_ai/governance/queue.py)、[worker.py](src/dataocean_ai/governance/worker.py) | 幂等接收、独立消费和结果交付 |
| [governance/table_description.py](src/dataocean_ai/governance/table_description.py) | 组织固定来源和输入预算，执行结构化生成并验证建议引用 |
| [governance/field_agent.py](src/dataocean_ai/governance/field_agent.py) | 使用 LangChain `create_agent` 按需读取当次结构和资料，每轮调用前向 Java 核对权限 |
| [adapters/chat.py](src/dataocean_ai/adapters/chat.py)、[probe.py](src/dataocean_ai/adapters/probe.py) | 双协议模型调用及受认证的合成连接测试 |
| 向量存储连接 | [adapters/milvus.py](src/dataocean_ai/adapters/milvus.py) 统一读取部署配置并归还客户端，各业务入口自行确定操作超时与证据校验。 |
| [test_foundation.py](tests/unit/test_foundation.py) | 验证健康接口、追踪标识、错误响应和配置校验 |
| [pyproject.toml](pyproject.toml)、[uv.lock](uv.lock) | 声明正式包的构建、启动入口及工具配置，锁定实际依赖 |

## 表说明和字段解释

`dataocean_ai.governance` 通过 Redis Streams 接收表说明和单字段解释任务。LangGraph 编排固定目录、模型调用和建议校验，字段说明由 Agent 在当次获准证据内按需取证。API 和 Worker 分别由 `dataocean-ai-api`、`dataocean-ai-worker` 启动，模型回执经 Java 落库复核。

`dataocean_ai.knowledge` 复用上述队列、租约及内部身份，按 Java 固定的确认片段构建独立索引。Worker 从 `AI_MILVUS_URI` 与 `AI_MILVUS_TOKEN` 读取获准存储连接，按 Strong 回读正文和治理修订后交付结果。模型由候选固定，真实向量配置从 Java 当次领取。

在系统「模型配置」中新增模型，填写协议、服务 URL、型号和 API 凭据。保存后可在模型详情测试连接，或在「节点列表」给「表说明生成」分配模型。测试使用固定合成请求，结果绑定保存的配置修订。Java 接收任务时固定当次配置修订，并用 `dataocean.models.allowed-hosts` 核对部署允许的 HTTPS 服务。工作者外发前以当前任务和执行代次取得凭据，LangChain 适配器按当次协议调用。服务身份文件和模型凭据按[私有配置约定](../docs/architecture/认证与身份上下文.md#凭据注入与保存)维护。

## 本地开发

从仓库根目录执行。首次配置复制完整的 Python 启动模板，已有 `.env` 时按模板核对本机值。示例中的密钥路径指向已配对的 Java 公钥和 Python 服务私钥。

```powershell
# 首次配置本机启动参数。
if (-not (Test-Path python-service/.env)) {
    Copy-Item python-service/.env.example python-service/.env
}

# 初始化 Java 和 Python 的内部服务身份。
./tools/dev/init-ai-service-identity.ps1

# 使用已验证的 Python 3.13 安装锁定依赖。
uv --directory python-service sync --locked --python 3.13
uv --directory python-service build

# 检查正式源码和与其对应的单元测试。
uv --directory python-service run --locked ruff check src/dataocean_ai tests/unit
uv --directory python-service run --locked ruff format --check src/dataocean_ai tests/unit
uv --directory python-service run --locked pytest

# 本地内部 API 默认监听 127.0.0.1:18090。
./tools/dev/start-python.ps1

# 启用治理 AI 时在独立进程启动 Worker，读取同一份启动参数。
uv --directory python-service run --locked --python 3.13 --env-file .env dataocean-ai-worker
```

`--directory` 以 Python 工程作为工作目录，密钥相对路径也从这里计算。[Python 配置示例](.env.example)列出 API 与 Worker 的全部启动参数，并示例独立检查点 Redis 和 RAG 的 Milvus 连接。当前表说明使用治理队列，检查点和 RAG 示例随相应工作流启用。Java 侧参照 [application-local.example.yml](../backend/src/main/resources/application-local.example.yml) 配置内部地址、签名与验签文件，以及部署准入的模型主机。根目录模板供 Docker Compose 使用。

进程日志默认写标准输出，可用 `AI_LOG_OUTPUT=stderr` 改写。需要本地文件时将进程输出重定向至 `output/dev`。

Redis 检查点集成探针需将 `LANGGRAPH_TEST_REDIS_URL` 指向专用验收实例，并显式运行 [test_langgraph_checkpointer_integration.py](tests/test_langgraph_checkpointer_integration.py)。
