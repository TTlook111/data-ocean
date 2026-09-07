"""异步边界回归测试。

验证同步 SQLAlchemy 调用不会直接穿透 FastAPI 事件循环，
以及独立业务域的 LLM 文档生成使用受控并发。
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest

from dataocean.knowledge import service as knowledge_service
from dataocean.knowledge.schema import (
    DomainDoc,
    DomainGroup,
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
async def test_domain_documents_generate_with_bounded_concurrency(monkeypatch):
    """独立业务域文档应并发生成，并保持域顺序。"""
    request = GenerateDraftRequest(
        snapshot_id=1,
        datasource_id=1,
        tables_metadata=[],
    )
    domains = [
        DomainGroup(domain_name=f"域{i}", table_names=[], reason="test")
        for i in range(4)
    ]
    started = 0
    all_started = asyncio.Event()

    async def fake_analyze(_request):
        return domains

    async def fake_generate(_request, domain):
        nonlocal started
        started += 1
        if started == len(domains):
            all_started.set()
        await asyncio.wait_for(all_started.wait(), timeout=1)
        return DomainDoc(
            title=f"{domain.domain_name} skills.md",
            content="content",
            table_names=[],
        )

    monkeypatch.setattr(knowledge_service, "_analyze_domains", fake_analyze)
    monkeypatch.setattr(knowledge_service, "_generate_domain_doc", fake_generate)

    result = await knowledge_service.analyze_and_generate(request)

    assert [doc.title for doc in result.docs] == [f"域{i} skills.md" for i in range(4)]
    assert started == 4


@pytest.mark.asyncio
async def test_chunk_document_offloads_token_work(monkeypatch):
    """skills.md 校验和切分不应直接占用事件循环。"""
    to_thread_calls: list[object] = []
    original_to_thread = asyncio.to_thread

    async def tracked_to_thread(func, /, *args, **kwargs):
        to_thread_calls.append(func)
        return await original_to_thread(func, *args, **kwargs)

    monkeypatch.setattr(rag_router.asyncio, "to_thread", tracked_to_thread)

    def fake_chunk(_content):
        return []

    monkeypatch.setattr(rag_router, "chunk_skills_md", fake_chunk)
    result = await rag_router.chunk_document(
        ChunkDocumentRequest(content="skills.md", validate_structure=False)
    )

    assert result.chunk_count == 0
    assert fake_chunk in to_thread_calls
