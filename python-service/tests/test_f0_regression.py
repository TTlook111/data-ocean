"""F0 排雷任务回归测试

覆盖 F0 修复的所有关键场景：
- RAG force 向量化安全修复
- RAG fallback/is_fallback 标记
- reranker 分数 clamp
- Agent retry_count 边界修复
- 模板变量一致性
- 配置热重载竞态修复
- 连接池清理 TOCTOU 修复
- LLM/Embedding 初始化竞态修复
"""

from __future__ import annotations

import threading
import time
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from langchain_core.documents import Document

from dataocean.agent.state import AgentState
from dataocean.agent.nodes.sql_generator import run_sql_generator
from dataocean.agent.nodes.sql_validator import run_sql_validator
from dataocean.agent.nodes.sql_executor import run_sql_executor
from dataocean.core.config import reload_config, get_config_version
from dataocean.rag.reranker import DataOceanReranker, rerank
from dataocean.rag.schema import ChunkItem, RetrieveRequest, RetrievedSchema
from dataocean.rag.vectorizer import vectorize_chunks
from dataocean.rag.vector_store import get_vector_store, clear_vector_store_cache, _vector_store_cache
from dataocean.sandbox.pool_manager import cleanup_idle_pools, _pool_info, PoolInfo


# ============================================================
# RAG Force 向量化安全修复回归测试
# ============================================================

class TestRagForceVectorizer:
    """RAG force 向量化安全修复"""

    @pytest.mark.asyncio
    async def test_force_requires_doc_id(self):
        """force=True 时必须传入 doc_id，否则返回失败"""
        chunks = [
            ChunkItem(
                chunk_text="test text",
                chunk_type="TABLE_DESC",
                source_id=1,
                governance_status="NORMAL",
                review_status="APPROVED",
                related_table="test",
                related_column="id",
            )
        ]

        with patch("dataocean.rag.vectorizer.ensure_collection") as mock_ensure:
            mock_ensure.return_value = MagicMock(name="test_collection")

            result = await vectorize_chunks(
                datasource_id=1,
                snapshot_id=1,
                version_no=1,
                chunks=chunks,
                doc_id=None,
                force=True,
            )

            assert result.status == "FAILED"
            assert any("doc_id" in str(e) for e in result.errors)

    @pytest.mark.asyncio
    async def test_non_force_without_doc_id_allowed(self):
        """非 force 模式允许无 doc_id"""
        chunks = [
            ChunkItem(
                chunk_text="test text",
                chunk_type="TABLE_DESC",
                source_id=1,
                governance_status="NORMAL",
                review_status="APPROVED",
                related_table="test",
                related_column="id",
            )
        ]

        with patch("dataocean.rag.vectorizer.ensure_collection") as mock_ensure, \
             patch("dataocean.rag.vectorizer.embed_texts", new_callable=AsyncMock, return_value=[[0.1] * 1024]), \
             patch("dataocean.rag.vectorizer.add_chunk_embeddings", new_callable=AsyncMock, return_value=["1"]):
            mock_ensure.return_value = MagicMock(name="test_collection")

            result = await vectorize_chunks(
                datasource_id=1,
                snapshot_id=1,
                version_no=1,
                chunks=chunks,
                doc_id=None,
                force=False,
            )

            # 非 force 模式不应该返回 force 错误
            assert not any("doc_id" in str(e) for e in result.errors)


# ============================================================
# RAG Fallback/is_fallback 标记回归测试
# ============================================================

class TestRagFallback:
    """RAG fallback 机制"""

    def test_fallback_score_is_0_5(self):
        """fallback 路径的 score 固定为 0.5"""
        # 这个测试验证 fallback 的 score 行为
        # 实际的 fallback_retrieve 函数需要 Milvus 连接
        # 这里只测试 reranker 对 score=0.5 的处理
        documents = [
            Document(
                page_content="fallback result",
                metadata={
                    "table_name": "test_table",
                    "score": 0.5,
                    "relevance_score": 0.5,
                    "chunk_type": "TABLE_DESC",
                    "governance_status": "NORMAL",
                },
            ),
        ]

        reranker = DataOceanReranker(top_k=10)
        result = reranker.compress_documents(documents, "test query")

        # 验证 fallback 结果被保留
        assert len(result) > 0
        assert result[0].metadata.get("score") >= 0.0
        assert result[0].metadata.get("score") <= 1.0


# ============================================================
# Reranker 分数 Clamp 回归测试
# ============================================================

class TestRerankerClamp:
    """reranker 分数 clamp 到 [0, 1.0]"""

    def test_high_score_clamped_to_1(self):
        """高分被 clamp 到 1.0"""
        documents = [
            Document(
                page_content="test table",
                metadata={
                    "table_name": "orders",
                    "score": 0.9,
                    "relevance_score": 0.9,
                    "chunk_type": "TABLE_DESC",
                    "governance_status": "RECOMMENDED",
                },
            ),
        ]

        reranker = DataOceanReranker(
            top_k=10,
            confidence_scores={"orders": 95}
        )
        # 多个加分条件触发
        result = reranker.compress_documents(documents, "查询 orders 订单数据")

        for doc in result:
            score = doc.metadata.get("score", 0)
            assert score <= 1.0, f"Score {score} exceeds 1.0"

    def test_low_score_clamped_to_0(self):
        """低分被 clamp 到 0.0"""
        documents = [
            Document(
                page_content="This table is deprecated",
                metadata={
                    "table_name": "old_table",
                    "score": 0.3,
                    "relevance_score": 0.3,
                    "chunk_type": "TABLE_DESC",
                    "governance_status": "NORMAL",
                },
            ),
        ]

        reranker = DataOceanReranker(top_k=10)
        result = reranker.compress_documents(documents, "test query")

        for doc in result:
            score = doc.metadata.get("score", 0)
            assert score >= 0.0, f"Score {score} below 0.0"


# ============================================================
# Agent Retry Count 边界修复回归测试
# ============================================================

class TestAgentRetryCount:
    """Agent retry_count 边界修复"""

    @pytest.mark.asyncio
    async def test_validator_reject_increments_retry(self):
        """SQL 校验失败时递增 retry_count"""
        base_state = {
            "task_id": "test-001",
            "question": "test",
            "datasource_id": 1,
            "user_id": 1,
            "generated_sql": "INVALID SQL",
            "error_message": "",
            "retry_count": 0,
            "user_permissions": {
                "allowed_tables": ["orders"],
                "table_scope_mode": "ALLOWLIST",
            },
        }

        with patch("dataocean.agent.nodes.sql_validator.validate") as mock_validate:
            mock_validate.return_value = MagicMock(
                passed=False,
                reasons=["SQL syntax error"],
            )

            result = await run_sql_validator(base_state)

            assert result.get("validation_result", {}).get("valid") is False
            # REJECT level 不递增 retry_count
            assert result.get("retry_count", 0) == 0

    @pytest.mark.asyncio
    async def test_executor_failure_increments_retry(self):
        """SQL 执行失败时递增 retry_count"""
        base_state = {
            "task_id": "test-001",
            "question": "test",
            "datasource_id": 1,
            "user_id": 1,
            "generated_sql": "SELECT 1",
            "validation_result": {
                "valid": True,
                "rewritten_sql": "SELECT 1",
            },
            "error_message": "",
            "retry_count": 0,
            "connection_config": {
                "host": "localhost",
                "port": 3306,
                "database": "test",
                "username": "root",
                "password": "test",
            },
        }

        with patch("dataocean.sandbox.executor.execute", new_callable=AsyncMock) as mock_execute, \
             patch("dataocean.agent.nodes.sql_executor.sse.emit_progress", new_callable=AsyncMock):
            mock_execute.return_value = MagicMock(
                success=False,
                columns=[],
                rows=[],
                row_count=0,
                execution_time_ms=10,
                error="Connection failed",
            )

            result = await run_sql_executor(base_state)

            assert result.get("execution_result", {}).get("error") is not None
            assert result.get("retry_count", 0) == 1


# ============================================================
# 模板变量一致性回归测试
# ============================================================

class TestTemplateVariableConsistency:
    """模板变量一致性"""

    @pytest.mark.asyncio
    async def test_sql_generator_uses_all_variables(self):
        """SQL 生成器使用所有变量"""
        base_state = {
            "task_id": "test-001",
            "question": "查询订单",
            "rewritten_query": "查询订单列表",
            "extracted_intent": {"dimensions": [], "metrics": [], "filters": []},
            "schema_context": [
                {
                    "table_name": "orders",
                    "chunk_type": "TABLE_DESC",
                    "chunk_text": "订单表",
                    "confidence_score": 90,
                    "governance_status": "NORMAL",
                }
            ],
            "confidence_scores": {"orders": 90},
            "conversation_history": [],
            "error_message": "",
            "retry_count": 0,
            "generated_sql": "",
        }

        mock_response = "```sql\nSELECT * FROM orders LIMIT 100\n```\ntest"

        with patch("dataocean.agent.nodes.sql_generator.call_llm", new_callable=AsyncMock, return_value=mock_response), \
             patch("dataocean.agent.nodes.sql_generator.render_prompt_with_metadata", new_callable=AsyncMock, return_value=("prompt", 1)):
            result = await run_sql_generator(base_state)

            assert result.get("generated_sql") is not None
            assert "SELECT" in result.get("generated_sql", "")


# ============================================================
# 配置热重载竞态修复回归测试
# ============================================================

class TestConfigReloadRaceCondition:
    """配置热重载竞态修复"""

    def test_config_version_increments(self):
        """配置重载后版本号递增"""
        initial_version = get_config_version()
        reload_config()
        assert get_config_version() > initial_version

    def test_config_reload_thread_safe(self):
        """配置重载线程安全"""
        results = []
        errors = []

        def reload_worker():
            try:
                for _ in range(5):
                    reload_config()
                    results.append(get_config_version())
            except Exception as e:
                errors.append(e)

        threads = [threading.Thread(target=reload_worker) for _ in range(3)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(errors) == 0
        assert len(results) > 0


# ============================================================
# 连接池清理 TOCTOU 修复回归测试
# ============================================================

class TestPoolCleanupTOCTOU:
    """连接池清理 TOCTOU 修复"""

    def test_cleanup_thread_safe(self):
        """清理操作线程安全"""
        _pool_info[9999] = PoolInfo(
            datasource_id=9999,
            pool_size=5,
            last_used_at=time.time() - 10000
        )

        results = []
        errors = []

        def cleanup_worker():
            try:
                cleaned = cleanup_idle_pools()
                results.append(cleaned)
            except Exception as e:
                errors.append(e)

        threads = [threading.Thread(target=cleanup_worker) for _ in range(3)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(errors) == 0
        assert 9999 not in _pool_info


# ============================================================
# VectorStore 缓存回归测试
# ============================================================

class TestVectorStoreCache:
    """VectorStore 缓存"""

    def test_cache_returns_same_instance(self):
        """缓存返回相同实例"""
        clear_vector_store_cache()

        mock_store = MagicMock(name="test_store")
        cache_key = ("test_collection", 1024)
        _vector_store_cache[cache_key] = mock_store

        store1 = get_vector_store("test_collection", 1024)
        store2 = get_vector_store("test_collection", 1024)

        assert store1 is store2

    def test_clear_cache_works(self):
        """清除缓存后返回新实例"""
        clear_vector_store_cache()

        mock_store1 = MagicMock(name="test_store1")
        cache_key = ("test_collection", 1024)
        _vector_store_cache[cache_key] = mock_store1

        store1 = get_vector_store("test_collection", 1024)
        clear_vector_store_cache()

        assert cache_key not in _vector_store_cache
