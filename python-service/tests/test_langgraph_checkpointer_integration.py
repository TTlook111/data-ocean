"""Opt-in persistence smoke test for the isolated Redis 8 acceptance service."""

from __future__ import annotations

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
    safe_marker: str


@pytest.mark.asyncio
async def test_async_redis_saver_reads_state_after_reopening_connection():
    from langgraph.checkpoint.redis.aio import AsyncRedisSaver
    from langgraph.graph import END, START, StateGraph

    def persist_marker(_state: CheckpointState) -> CheckpointState:
        return {"safe_marker": "redis-checkpoint-survived-reconnect"}

    builder = StateGraph(CheckpointState)
    builder.add_node("persist_marker", persist_marker)
    builder.add_edge(START, "persist_marker")
    builder.add_edge("persist_marker", END)
    thread_id = f"langgraph-acceptance-{uuid.uuid4().hex}"
    config = {"configurable": {"thread_id": thread_id}}

    async with AsyncRedisSaver.from_conn_string(REDIS_URL) as saver:
        await saver.asetup()
        graph = builder.compile(checkpointer=saver)
        result = await graph.ainvoke({}, config)
        assert result["safe_marker"] == "redis-checkpoint-survived-reconnect"

    async with AsyncRedisSaver.from_conn_string(REDIS_URL) as saver:
        await saver.asetup()
        graph = builder.compile(checkpointer=saver)
        state = await graph.aget_state(config)
        assert state.values["safe_marker"] == "redis-checkpoint-survived-reconnect"
