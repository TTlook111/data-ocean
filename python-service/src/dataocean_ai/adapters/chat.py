"""按任务配置的协议调用 LangChain，规范化模型结果与诊断信息。"""

import asyncio
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from functools import cached_property
from typing import Literal
from urllib.parse import urlsplit

import httpx
import httpx2
from anthropic import AsyncAnthropic
from langchain_anthropic import ChatAnthropic
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from langsmith import tracing_context
from pydantic import BaseModel, ConfigDict, Field, PrivateAttr, SecretStr


class ChatConfiguration(BaseModel):
    """Java 在外发前签发的单次模型配置。"""

    # 内部接口字段使用 Java 端同名别名。
    model_config = ConfigDict(hide_input_in_errors=True)

    # 当次固定的配置修订，不随改绑变化。
    revision_id: str = Field(alias="revisionId")
    # 明确声明的生成协议。
    protocol: Literal["OPENAI_CHAT_COMPLETIONS", "ANTHROPIC_MESSAGES"]
    # 已校验的服务端 SDK 基址。
    endpoint: str
    # 发送给服务的模型标识。
    model_name: str = Field(alias="modelName")
    # 当次请求可用秒数。
    timeout: int = Field(alias="timeoutSeconds", ge=1, le=120)
    # 仅在适配器持有的访问凭据。
    api_key: SecretStr = Field(alias="apiKey")


class CallReceipt(BaseModel):
    """只记录可审计诊断值，输入与原始响应不进入回执。"""

    # 实际使用的不可变配置修订。
    revision_id: str = Field(serialization_alias="revisionId")
    # 提供方报告的模型型号，未知时为空。
    model_name: str | None = Field(serialization_alias="modelName")
    # 提供方调用 ID，框架内部 ID 不冒充提供方回执。
    provider_call_id: str | None = Field(serialization_alias="providerCallId")
    # 模型调用耗时，毫秒。
    elapsed_ms: int = Field(serialization_alias="elapsedMs")
    # 提供方输入 token 数，未知时为空。
    input_tokens: int | None = Field(serialization_alias="inputTokens")
    # 提供方输出 token 数，未知时为空。
    output_tokens: int | None = Field(serialization_alias="outputTokens")


class ModelFailure(Exception):
    """跨 LangChain 和两个 SDK 的稳定诊断状态。"""


class _ControlledAnthropic(ChatAnthropic):
    """使当前锁定的 Anthropic 集成使用受控 HTTP 连接。"""

    # 与 OpenAI 适配器共享同一出站安全约束和连接生命周期。
    _transport: httpx2.AsyncClient = PrivateAttr()

    @cached_property
    def _async_client(self) -> AsyncAnthropic:
        """沿用 LangChain 的消息及结构化处理，固定 SDK 出站传输。"""
        return AsyncAnthropic(
            api_key=self.anthropic_api_key.get_secret_value(),
            base_url=self.anthropic_api_url,
            timeout=self.default_request_timeout,
            max_retries=0,
            http_client=self._transport,
        )


@asynccontextmanager
async def model_session(config: ChatConfiguration) -> AsyncIterator[BaseChatModel]:
    """按同一配置打开受控模型，模型与工具循环共享总时限。"""
    settings = {
        "model": config.model_name,
        "base_url": config.endpoint,
        "api_key": config.api_key.get_secret_value(),
        "timeout": config.timeout,
        "max_tokens": 1400,
        "max_retries": 0,
    }
    # 各 SDK 使用各自版本的客户端，共用禁止代理和重定向的传输策略。
    openai = config.protocol == "OPENAI_CHAT_COMPLETIONS"
    client_type = httpx.AsyncClient if openai else httpx2.AsyncClient
    try:
        # 私有业务原文只送达当次准入服务，禁止外部追踪和 HTTP 重定向。
        async with (
            asyncio.timeout(config.timeout),
            client_type(follow_redirects=False, trust_env=False) as transport,
        ):
            if openai:
                # 千问已验证的模型使用非思考 JSON 模式。
                host = urlsplit(config.endpoint).hostname or ""
                hosts = {"dashscope.aliyuncs.com", "maas.qianwenaiapi.com"}
                qwen = host in hosts and config.model_name in {
                    "qwen-plus",
                    "qwen-plus-latest",
                    "qwen-flash",
                }
                model = ChatOpenAI(
                    **settings,
                    http_async_client=transport,
                    use_responses_api=False,
                    extra_body={"enable_thinking": False} if qwen else None,
                )
            else:
                # Messages 使用原生 Schema，传输模型只包含已声明的字段。
                model = _ControlledAnthropic(**settings)
                model._transport = transport
            with tracing_context(enabled=False):
                # 工作流只在受控传输存活时向提供方发送消息。
                yield model
    except TimeoutError:
        # 读超时后服务端仍可能已收到请求，保留待核实事实。
        raise ModelFailure("MODEL_RESULT_UNKNOWN") from None
    except ModelFailure:
        # 业务层已分类的失败沿用原原因。
        raise
    except Exception as error:
        # 提供方异常可能带原始响应，只转换稳定分类。
        status = getattr(error, "status_code", None)
        if status is None:
            status = getattr(getattr(error, "response", None), "status_code", None)
        code = {400: "MODEL_FORMAT", 401: "MODEL_AUTH", 403: "MODEL_AUTH", 429: "MODEL_RATE_LIMIT"}
        raise ModelFailure(code.get(status, "MODEL_RESULT_UNKNOWN")) from None


async def generate[Answer: BaseModel](
    config: ChatConfiguration, system: str, prompt: str, schema: type[Answer]
) -> tuple[Answer, CallReceipt]:
    """进行一次结构化非 Agent 调用。"""
    started = time.monotonic()
    async with model_session(config) as model:
        # 两种已验收协议各用自己的结构化模式。
        method = "json_mode" if config.protocol == "OPENAI_CHAT_COMPLETIONS" else "json_schema"
        runner = model.with_structured_output(schema, method=method, include_raw=True)
        result = await runner.ainvoke([SystemMessage(system), HumanMessage(prompt)])
    answer = result["parsed"]
    if not isinstance(answer, schema):
        # 拒绝、截断或解析失败不产生可写入的候选。
        raise ModelFailure("MODEL_FORMAT")
    return answer, call_receipt(config, [result["raw"]], started)


def call_receipt(
    config: ChatConfiguration, replies: list[AIMessage], started: float
) -> CallReceipt:
    """一轮或多轮响应均以当次固定修订生成诊断，累计已知用量。"""
    metadata = replies[-1].response_metadata if replies else {}
    model_name = metadata.get("model_name") or metadata.get("model")
    provider_id = metadata.get("id")

    def total(key: str) -> int | None:
        """只在各轮都提供计数时返回完整用量。"""
        values = [(message.usage_metadata or {}).get(key) for message in replies]
        if not values or any(value is None for value in values):
            # 避免把缺失轮次当作零用量。
            return None
        return sum(value for value in values if value is not None)

    return CallReceipt(
        revision_id=config.revision_id,
        model_name=str(model_name)[:120] if model_name else None,
        provider_call_id=str(provider_id)[:160] if provider_id else None,
        elapsed_ms=int((time.monotonic() - started) * 1000),
        input_tokens=total("input_tokens"),
        output_tokens=total("output_tokens"),
    )
