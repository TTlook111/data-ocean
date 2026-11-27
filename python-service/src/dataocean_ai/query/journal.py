"""用 Redis 持久化问数节点的外发动作与实际回执。"""

import asyncio
import hashlib
import json
import logging
import operator
import os
import re
from collections.abc import Awaitable, Callable
from typing import Annotated, Any, TypedDict
from uuid import UUID

from fastapi import HTTPException, Request
from fastapi.encoders import jsonable_encoder
from langgraph.checkpoint.redis.aio import AsyncRedisSaver
from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime
from redis.asyncio import Redis
from redis.exceptions import RedisError

from dataocean_ai.adapters.chat import ModelFailure

logger = logging.getLogger(__name__)


class Saved(TypedDict):
    """结果或失败分类关联同一份授权输入摘要。"""

    digest: str
    value: Any
    error: str | None


class JournalState(TypedDict, total=False):
    """每个请求的 thread 保留已收到的有限节点回执。"""

    step: str
    digest: str
    receipts: Annotated[dict[str, Saved], operator.or_]


class Invocation(TypedDict):
    """模型响应在提交前只保留于当前进程。"""

    saved: Saved


async def complete(state: JournalState, runtime: Runtime[Invocation]) -> JournalState:
    """将本次实际回包合并到当前线程上一份检查点。"""
    return {"receipts": {state["step"]: runtime.context["saved"]}}


async def invoke_step(invoke: Callable[[], Awaitable[Any]], digest: str) -> Saved:
    """已持久登记的外部调用独立完成，等待上游期间不占用写锁。"""
    try:
        async with asyncio.timeout(45):
            # 节点内部仍使用自己较短的提供方预算。
            value = jsonable_encoder(await invoke())
        return Saved(digest=digest, value=value, error=None)
    except ModelFailure as failure:
        # 不记录提供方正文，不让恢复把失败当成尚未外发。
        return Saved(digest=digest, value=None, error=str(failure))
    except TimeoutError:
        # 超出整个节点的处理上限，记录已结束的超时回执。
        return Saved(digest=digest, value=None, error="TIMEOUT")


def build(saver: AsyncRedisSaver):
    """用固定节点封装外发回执的持久化边界。"""
    graph = StateGraph(JournalState, context_schema=Invocation)
    graph.add_node("complete", complete)
    graph.add_edge(START, "complete")
    graph.add_edge("complete", END)
    return graph.compile(checkpointer=saver)


def stable(value: Any) -> Any:
    """指纹排除运行时超时与秘密，其余语义输入必须完全一致。"""
    if isinstance(value, dict):
        return {
            key: stable(item)
            for key, item in value.items()
            if key not in {"api_key", "apiKey", "timeout", "timeoutSeconds", "remainingSeconds"}
        }
    if isinstance(value, list):
        return [stable(item) for item in value]
    return value


def receipt(saved: Saved, digest: str) -> Any:
    """恢复只读取同一输入的回执，外发未知不能模拟成功。"""
    if digest != saved["digest"]:
        raise HTTPException(status_code=409, detail="QUERY_STEP_CONFLICT")
    if saved["error"]:
        raise HTTPException(status_code=424, detail=saved["error"])
    return saved["value"]


async def journal(request: Request, payload: Any, invoke: Callable[[], Awaitable[Any]]) -> Any:
    """先查同一节点回执，只有 Java 首次提交可登记并启动外发。"""
    try:
        raw_id = request.headers.get("x-query-id", "")
        query_id = str(UUID(raw_id))
        if query_id != raw_id:
            raise ValueError
    except ValueError:
        raise HTTPException(status_code=400, detail="QUERY_REQUEST_REQUIRED") from None
    step = request.headers.get("x-query-step", "")
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,35}", step):
        raise HTTPException(status_code=400, detail="QUERY_STEP_REQUIRED")
    lookup = request.headers.get("x-query-lookup") == "true"
    url = os.getenv("AI_CHECKPOINT_URL")
    if not url:
        raise HTTPException(status_code=503, detail="QUERY_CHECKPOINT_UNAVAILABLE")
    digest = hashlib.sha256(
        json.dumps(stable(jsonable_encoder(payload)), sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()
    prefix = os.getenv("AI_QUERY_CHECKPOINT_PREFIX", "dataocean:query:v1")
    options = {"configurable": {"thread_id": query_id}}
    claim = f"{prefix}:issued:{query_id}:{step}"
    active = f"{claim}:active"
    try:
        async with (
            Redis.from_url(url, socket_timeout=3, socket_connect_timeout=2) as client,
            AsyncRedisSaver.from_conn_string(
                redis_client=client,
                checkpoint_prefix=prefix + ":checkpoint",
                checkpoint_write_prefix=prefix + ":write",
                ttl={"default_ttl": 1440, "refresh_on_read": False},
            ) as saver,
        ):
            await saver.asetup()
            graph = build(saver)
            snapshot = await graph.aget_state(options)
            saved = snapshot.values.get("receipts", {}).get(step)
            if saved is not None:
                logger.info("问数恢复已确认节点，request=%s，step=%s", query_id, step)
                return receipt(saved, digest)

            if lookup or not await client.set(claim, digest, nx=True, ex=86400):
                # 外发记录或旧进程可能仍在结束，只等已登记回执。
                for _ in range(12):
                    fingerprint = await client.get(claim)
                    if fingerprint is None:
                        break
                    if fingerprint.decode() != digest:
                        raise HTTPException(status_code=409, detail="QUERY_STEP_CONFLICT")
                    await asyncio.sleep(0.35)
                    snapshot = await graph.aget_state(options)
                    saved = snapshot.values.get("receipts", {}).get(step)
                    if saved is not None:
                        return receipt(saved, digest)
                if await client.exists(active):
                    # 活动标记尚在时，继续等待同一节点提交回执。
                    raise HTTPException(status_code=425, detail="QUERY_STEP_RUNNING")
                raise HTTPException(status_code=409, detail="QUERY_STEP_OUTCOME_UNKNOWN")

            # 已登记动作独立执行，短期活动标记用于区分断连和真实失联。
            await client.set(active, "1", ex=60)
            try:
                response = await invoke_step(invoke, digest)
                # 多个解释修订可能前后交叉返回。读取最新线程状态和写入在同一短锁内。
                async with (
                    client.lock(f"{prefix}:writer:{query_id}", timeout=20, blocking_timeout=4),
                    asyncio.timeout(8),
                ):
                    result = await graph.ainvoke(
                        {"step": step, "digest": digest},
                        options,
                        context={"saved": response},
                        durability="sync",
                    )
                logger.info("问数节点回执已落盘，request=%s，step=%s", query_id, step)
                return receipt(result["receipts"][step], digest)
            finally:
                # 回执先落盘，完成或退出后清除活动事实。
                await client.delete(active)
    except (RedisError, OSError, TimeoutError):
        # 存储不可用时不在内存中代替已有的持久检查点。
        raise HTTPException(status_code=503, detail="QUERY_CHECKPOINT_UNAVAILABLE") from None
