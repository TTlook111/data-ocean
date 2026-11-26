"""
独立验收实例的 Redis 检查点持久化探针。
"""

from __future__ import annotations

import asyncio
import os
import uuid
from typing import TypedDict

import pytest

REDIS_URL = os.getenv("LANGGRAPH_TEST_REDIS_URL")
pytestmark = pytest.mark.skipif(
    not REDIS_URL,
    reason="Set LANGGRAPH_TEST_REDIS_URL only for the dedicated Redis 8 acceptance container.",
)


class CheckpointState(TypedDict, total=False):
    """
    用于校验写入和读取结果一致的探针状态。
    """

    # 固定的合成标记。
    safe_marker: str


def test_async_redis_saver_reads_state_after_reopening_connection():
    """
    使用项目现有的 pytest 环境复验检查点持久化。
    """
    asyncio.run(verify_checkpoint_reopen())


async def verify_checkpoint_reopen():
    """
    写入检查点，断开后使用新连接读取。
    """
    from langgraph.checkpoint.redis.aio import AsyncRedisSaver
    from langgraph.graph import END, START, StateGraph

    def persist_marker(_state: CheckpointState) -> CheckpointState:
        """
        生成要写入检查点的标记。
        """
        return {"safe_marker": "redis-checkpoint-survived-reconnect"}

    builder = StateGraph(CheckpointState)
    builder.add_node("persist_marker", persist_marker)
    builder.add_edge(START, "persist_marker")
    builder.add_edge("persist_marker", END)
    thread_id = f"langgraph-acceptance-{uuid.uuid4().hex}"
    config = {"configurable": {"thread_id": thread_id}}

    # 通过第一次连接写入独立的探针检查点。
    async with AsyncRedisSaver.from_conn_string(REDIS_URL) as saver:
        await saver.asetup()
        graph = builder.compile(checkpointer=saver)
        result = await graph.ainvoke({}, config)
        assert result["safe_marker"] == "redis-checkpoint-survived-reconnect"

    # 使用新连接从 Redis 读取已持久化的状态。
    async with AsyncRedisSaver.from_conn_string(REDIS_URL) as saver:
        await saver.asetup()
        graph = builder.compile(checkpointer=saver)
        state = await graph.aget_state(config)
        assert state.values["safe_marker"] == "redis-checkpoint-survived-reconnect"
