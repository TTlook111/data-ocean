"""异步边界回归测试。

验证同步 SQLAlchemy 调用不会直接穿透 FastAPI 事件循环，
以及文档生成、切片等同步计算的线程卸载。
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest

from dataocean.knowledge import service as knowledge_service
from dataocean.knowledge.schema import (
    GenerateDraftRequest,
)
from dataocean.rag import router as rag_router
from dataocean.rag.schema import ChunkDocumentRequest
from dataocean.sandbox import executor as sandbox_executor
from dataocean.sandbox import pool_manager


@pytest.mark.asyncio
async def test_sql_executor_offloads_engine_creation_and_query_execution(monkeypatch):
    """同步连接池获取和查询执行都应经由 asyncio.to_thread。"""
    engine = object()
    to_thread_calls: list[object] = []
    original_to_thread = asyncio.to_thread

    async def tracked_to_thread(func, /, *args, **kwargs):
        to_thread_calls.append(func)
        return await original_to_thread(func, *args, **kwargs)

    monkeypatch.setattr(sandbox_executor.asyncio, "to_thread", tracked_to_thread)
    monkeypatch.setattr(pool_manager, "get_engine", lambda *_args: engine)

    def fake_execute(_engine, _sql, connection_id_holder):
        connection_id_holder.append(123)
        raise RuntimeError("query failed")

    monkeypatch.setattr(sandbox_executor, "_execute_readonly", fake_execute)
    sandbox_config = SimpleNamespace(max_execution_time=1, max_result_rows=100)
    monkeypatch.setattr(sandbox_executor, "sandbox_config", sandbox_config)

    result = await sandbox_executor.execute(
        sql="SELECT 1",
        datasource_id=1,
        connection_config={"host": "localhost"},
    )

    assert result.success is False
    assert result.error_type == sandbox_executor.ErrorType.UNKNOWN
    assert pool_manager.get_engine in to_thread_calls
    assert sandbox_executor._execute_readonly in to_thread_calls


@pytest.mark.asyncio
async def test_sql_executor_offloads_timeout_kill(monkeypatch):
    """查询超时后的 KILL QUERY 不能同步阻塞事件循环。"""
    engine = object()
    to_thread_calls: list[object] = []
    original_to_thread = asyncio.to_thread

    async def tracked_to_thread(func, /, *args, **kwargs):
        to_thread_calls.append(func)
        return await original_to_thread(func, *args, **kwargs)

    monkeypatch.setattr(sandbox_executor.asyncio, "to_thread", tracked_to_thread)
    monkeypatch.setattr(pool_manager, "get_engine", lambda *_args: engine)

    def fake_execute(_engine, _sql, connection_id_holder):
        connection_id_holder.append(456)
        return sandbox_executor.ExecutionResult(success=True)

    monkeypatch.setattr(sandbox_executor, "_execute_readonly", fake_execute)
    monkeypatch.setattr(sandbox_executor, "_kill_query", lambda *_args: None)
    monkeypatch.setattr(
        sandbox_executor,
        "sandbox_config",
        SimpleNamespace(max_execution_time=1, max_result_rows=100),
    )

    # 用 wait_for 注入超时，避免让测试依赖真实数据库或人为 sleep。
    async def force_timeout(awaitable, timeout):
        awaitable.close()
        raise asyncio.TimeoutError

    monkeypatch.setattr(sandbox_executor.asyncio, "wait_for", force_timeout)
    result = await sandbox_executor.execute(
        sql="SELECT 1",
        datasource_id=1,
        connection_config={"host": "localhost"},
    )

    assert result.error_type == sandbox_executor.ErrorType.TIMEOUT
    assert sandbox_executor._kill_query in to_thread_calls
    assert sandbox_executor._execute_readonly not in to_thread_calls


@pytest.mark.asyncio
async def test_snapshot_rendering_does_not_run_on_event_loop_thread(monkeypatch):
    import threading
    caller = threading.get_ident()
    request = GenerateDraftRequest(snapshot_id=1, datasource_id=1, tables_metadata=[{
        "table_id":10,"source_snapshot_id":1,"table_name":"orders","columns":[],
    }])
    def render(_request):
        assert threading.get_ident() != caller
        return "snapshot document", {"tableCount":1,"columnCount":0}, []
    monkeypatch.setattr(knowledge_service,"_render_snapshot_document",render)
    result = await knowledge_service.generate_draft(request)
    assert result.content == "snapshot document"


@pytest.mark.asyncio
async def test_chunk_document_offloads_token_work(monkeypatch):
    """skills.md 校验和切分不应直接占用事件循环。"""
    to_thread_calls: list[object] = []
    original_to_thread = asyncio.to_thread

    async def tracked_to_thread(func, /, *args, **kwargs):
        to_thread_calls.append(func)
        return await original_to_thread(func, *args, **kwargs)

    monkeypatch.setattr(rag_router.asyncio, "to_thread", tracked_to_thread)

    def fake_chunk(_content, _snapshot_id=None):
        return []

    monkeypatch.setattr(rag_router, "chunk_skills_md", fake_chunk)
    result = await rag_router.chunk_document(
        ChunkDocumentRequest(content="skills.md", validate_structure=False)
    )

    assert result.chunk_count == 0
    assert fake_chunk in to_thread_calls
