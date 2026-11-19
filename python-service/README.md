# DataOcean Python AI 工程

正式包为 `src/dataocean_ai`，使用同一份 `pyproject.toml` 和 `uv.lock` 构建。版本与工具要求见[后端基线](../docs/backend/README.md#版本基线与验证边界)。内部 API 与表说明生成 Worker 共用此包，分进程运行。

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
| [governance/queue.py](src/dataocean_ai/governance/queue.py)、[worker.py](src/dataocean_ai/governance/worker.py) | 幂等接收表说明生成任务，独立消费、获取固定依据和执行实际模型调用 |
| [test_foundation.py](tests/unit/test_foundation.py) | 验证健康接口、追踪标识、错误响应和配置校验 |
| [pyproject.toml](pyproject.toml)、[uv.lock](uv.lock) | 声明正式包的构建、启动入口及工具配置，锁定实际依赖 |

保留代码的业务定位见[源码地图](../docs/source-map/README.md#模块定位专题)。

## 表说明生成

`dataocean_ai.governance` 提供内部任务接收、Redis Streams 消费和 LangGraph 表说明生成。API 进程通过 `dataocean-ai-api` 启动，工作者通过 `dataocean-ai-worker` 启动。工作者按授权从 Java 取用固定来源，模型回执经 Java 落库复核。

Python 的 `.env` 维护监听、日志、队列及 Java 内部地址和服务签名密钥文件路径。模型在后台「模型配置」中新增，选用 OpenAI Chat Completions 或 Anthropic Messages，再分配给「表说明生成」节点。Java 接收任务时固定配置修订，并用 `dataocean.models.allowed-hosts` 核对部署允许的 HTTPS 服务。工作者外发前以当前任务和执行代次取得必要凭据，LangChain 适配器按当次协议调用。密钥文件和模型凭据按[私有配置约定](../docs/architecture/认证与身份上下文.md#凭据注入与保存)维护。

## 本地开发

从仓库根目录执行：

```powershell
# 安装锁定依赖，构建的正式包只包含 src/dataocean_ai。
uv --directory python-service sync --locked
uv --directory python-service build

# 检查正式源码和与其对应的单元测试。
uv --directory python-service run --locked ruff check src/dataocean_ai tests/unit
uv --directory python-service run --locked ruff format --check src/dataocean_ai tests/unit
uv --directory python-service run --locked pytest

# 本地内部 API 默认监听 127.0.0.1:18090。
./tools/dev/start-python.ps1

# 启用治理 AI 时在独立进程启动 Worker，读取同一份启动参数。
uv --directory python-service run --locked --env-file .env dataocean-ai-worker
```

`--directory` 明确以 Python 工程作为工作目录。运行配置只读取 `DATAOCEAN_AI_` 前缀；启动脚本会显式加载本目录的 `.env`（若存在），模板见[配置示例](.env.example)，不隐式读取根目录的 Compose 环境。

旧 `dataocean` 与原有测试仍保留供后续模块评估，不被正式包导入或打入产物，也不作为新版业务验收。正式测试当前定位在 `tests/unit`，后续业务测试继续按实际模块组织。首批整体覆盖及剩余边界见[人员与权限验收](../docs/backend/iam/验收.md)。
