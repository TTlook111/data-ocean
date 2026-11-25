"""把实际提供方 HTTP 请求记入平台库，不记录输入、响应正文或凭据。"""

import logging
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from typing import TYPE_CHECKING
from uuid import uuid4

import httpx
import httpx2

from dataocean_ai.core.config import GovernanceSettings
from dataocean_ai.governance.security import worker_token

if TYPE_CHECKING:
    from dataocean_ai.adapters.chat import ModelConfiguration

logger = logging.getLogger(__name__)
_EXTENSION = "dataocean_call_receipt"
_COUNTER_MAX = 2**31 - 1


@dataclass(frozen=True)
class CallScope:
    """在当前异步业务流程内固定诊断归属。"""

    # 发起调用的模块。
    module: str
    # 模型目录中的实际业务节点。
    node: str
    # 原任务编号，仅用于归属和历史去重。
    origin: str


_scope: ContextVar[CallScope | None] = ContextVar("model_call_scope", default=None)


class ModelRecordingError(Exception):
    """尚未确认写入开始回执，提供方请求未发送。"""


@contextmanager
def model_call_scope(module: str, node: str, origin: str) -> Iterator[None]:
    """让单轮、多轮及并行批次的实际请求继承同一业务身份。"""
    token = _scope.set(CallScope(module, node, origin))
    try:
        yield
    finally:
        # 同一工作者下一条任务不能继承上一条的节点或任务编号。
        _scope.reset(token)


async def _write(call: str, action: str, payload: dict) -> bool:
    """在固定内部服务上有界重试同一幂等动作。"""
    base = GovernanceSettings.from_environment().java_url
    if not base:
        return False
    try:
        # 内部身份不随提供方客户端转发，关闭代理和重定向。
        bearer = {"Authorization": "Bearer " + worker_token()}
        async with httpx.AsyncClient(timeout=2, trust_env=False, follow_redirects=False) as client:
            for _ in range(2):
                # 传输中断后保留原调用标识，最多再核对一次写入。
                try:
                    response = await client.put(
                        f"{base.rstrip('/')}/internal/v1/model-calls/{call}/{action}",
                        headers=bearer,
                        json=payload,
                    )
                except httpx.RequestError:
                    continue
                if response.is_success:
                    return response.json().get("data") is True
                if response.status_code < 500:
                    # 身份、参数或原记录不匹配时不重复提交。
                    return False
    except (OSError, KeyError, ValueError, AttributeError):
        # 只将安全分类交回模型适配器，禁止披露密钥、地址或响应内容。
        return False
    return False


def _counter(value: object) -> int | None:
    """只采纳提供方报告的合法用量计数。"""
    return value if type(value) is int and 0 <= value <= _COUNTER_MAX else None


def _usage(payload: dict, protocol: str) -> tuple[int | None, int | None]:
    """按不同能力的实际回执字段读取已报告的 Token。"""
    if protocol == "COHERE_RERANK":
        # 计费搜索次数不能换算成 Token，仅采纳提供方实际报告的 Token 数。
        meta = payload.get("meta")
        if not isinstance(meta, dict):
            return None, None
        tokens = meta.get("tokens")
        billed = meta.get("billed_units")
        counts = tokens if isinstance(tokens, dict) else billed
        if isinstance(counts, dict):
            return _counter(counts.get("input_tokens")), _counter(counts.get("output_tokens"))
        return None, None
    usage = payload.get("usage")
    if not isinstance(usage, dict):
        return None, None
    if protocol == "ANTHROPIC_MESSAGES":
        # Messages 的缓存读取与写入属于实际输入，缺少输入主体数时仍展示未知。
        input_tokens = _counter(usage.get("input_tokens"))
        if input_tokens is not None:
            input_tokens = _counter(
                input_tokens
                + (_counter(usage.get("cache_creation_input_tokens")) or 0)
                + (_counter(usage.get("cache_read_input_tokens")) or 0)
            )
        return input_tokens, _counter(usage.get("output_tokens"))
    input_tokens = _counter(usage.get("prompt_tokens"))
    if protocol == "OPENAI_EMBEDDINGS":
        # 向量回执中的总 Token 等同于输入，没有生成输出 Token。
        return input_tokens if input_tokens is not None else _counter(usage.get("total_tokens")), 0
    return input_tokens, _counter(usage.get("completion_tokens"))


def model_call_hooks(config: "ModelConfiguration", validate: Callable[[dict], bool] | None = None):
    """在提供方客户端登记真实请求，同一 Agent 的多次外发分别计数。"""
    scope = _scope.get()
    if scope is None:
        # 合成连接测试以及独立的适配器探针没有业务节点归属。
        return None

    async def start(request) -> None:
        """开始回执确认后才允许模型请求进入网络。"""
        identity = str(uuid4())
        written = await _write(
            identity,
            "start",
            {
                "module": scope.module,
                "originId": scope.origin,
                "nodeCode": scope.node,
                "revisionId": config.revision_id,
            },
        )
        if not written:
            raise ModelRecordingError()
        request.extensions[_EXTENSION] = (identity, time.monotonic())

    async def finish(response) -> None:
        """已返回的提供方响应只提取状态、型号、用量和耗时。"""
        identity, started = response.request.extensions[_EXTENSION]
        codes = {400: "MODEL_FORMAT", 401: "MODEL_AUTH", 403: "MODEL_AUTH", 429: "MODEL_RATE_LIMIT"}
        failure = (
            None if response.is_success else codes.get(response.status_code, "MODEL_RESULT_UNKNOWN")
        )
        payload: dict = {}
        if response.is_success:
            # 读取并缓存已有响应供 SDK 继续解析，保持原模型业务返回与异常处理。
            try:
                await response.aread()
                body = response.json()
                if isinstance(body, dict) and body.get("error") is None:
                    payload = body
                else:
                    failure = "MODEL_FORMAT"
            except (httpx.HTTPError, httpx2.HTTPError):
                # 提供方已经收到请求，无法取得完整回包时保留待核查事实。
                raise
            except (UnicodeError, ValueError):
                failure = "MODEL_FORMAT"
        if failure is None and validate is not None and not validate(payload):
            # HTTP 成功但排序结果不完整时，也把可核实的格式故障记入概览。
            failure = "MODEL_FORMAT"
        input_tokens, output_tokens = _usage(payload, config.protocol)
        name = payload.get("model")
        if not await _write(
            identity,
            "finish",
            {
                "modelName": name[:120] if isinstance(name, str) else None,
                "failureCode": failure,
                "elapsedMs": max(0, int((time.monotonic() - started) * 1000)),
                "inputTokens": input_tokens,
                "outputTokens": output_tokens,
            },
        ):
            # 已经登记的请求会在截止后列入待核查。结果记录失败不覆盖真实业务回包。
            logger.warning("模型结果记录未确认，call=%s", identity)

    return {"request": [start], "response": [finish]}
