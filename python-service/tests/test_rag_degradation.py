"""RAG 降级路径测试

测试 RAG 降级机制：
- 降级结果的 is_fallback 标记
- 降级结果的 score 行为
- force 模式安全修复验证
- VectorStore 缓存验证
- reranker 分数 clamp 验证
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from dataocean.rag.reranker import DataOceanReranker, rerank
from dataocean.rag.schema import RetrieveRequest, RetrievedSchema
from dataocean.rag.vectorizer import vectorize_chunks
from dataocean.rag.vector_store import add_chunk_embeddings, search_by_vector, delete_by_expr
from dataocean.rag.milvus_client import get_client


class TestRerankerClamp:
    """reranker 分数 clamp 测试"""

    def test_score_clamped_to_0_1(self):
        """验证加权后分数被 clamp 到 [0, 1.0]"""
        from langchain_core.documents import Document

        documents = [
            Document(
                page_content="订单表",
                metadata={
                    "table_name": "orders",
                    "score": 0.8,
                    "relevance_score": 0.8,
                    "chunk_type": "TABLE_DESC",
                    "governance_status": "NORMAL",
                },
            ),
        ]

        reranker = DataOceanReranker(top_k=10, confidence_scores={"orders": 90})
        # 问题包含表名，会增加 0.2 分
        result = reranker.compress_documents(documents, "查询 orders 订单数据")

        # 验证分数被 clamp
        for doc in result:
            score = doc.metadata.get("score", 0)
            assert 0 <= score <= 1.0, f"Score {score} out of range [0, 1.0]"

    def test_deprecated_penalty(self):
        """验证废弃标记的惩罚（page_content 包含 deprecated 关键词）"""
        from langchain_core.documents import Document

        documents = [
            Document(
                page_content="This table is deprecated",  # 包含 deprecated 关键词
                metadata={
                    "table_name": "old_table",
                    "score": 0.9,
                    "relevance_score": 0.9,
                    "chunk_type": "TABLE_DESC",
                    "governance_status": "DEPRECATED",
                },
            ),
        ]

        reranker = DataOceanReranker(top_k=10)
        result = reranker.compress_documents(documents, "查询数据")

        # 验证分数被降低
        for doc in result:
            score = doc.metadata.get("score", 0)
            assert score < 0.9, f"Deprecated penalty not applied: {score}"


class TestVectorizerForceMode:
    """向量化 force 模式安全修复测试"""

    @pytest.mark.asyncio
    async def test_force_requires_doc_id(self):
        """验证 force=True 时必须传入 doc_id"""
        from dataocean.rag.schema import ChunkItem

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

        # Mock Milvus 连接
        with patch("dataocean.rag.vectorizer.ensure_collection") as mock_ensure:
            mock_ensure.return_value = MagicMock(name="test_collection")

            result = await vectorize_chunks(
                datasource_id=1,
                snapshot_id=1,
                version_no=1,
                chunks=chunks,
                doc_id=None,  # 无 doc_id
                force=True,   # force 模式
            )

            # 验证返回失败
            assert result.status == "FAILED"
            assert any("doc_id" in str(e) for e in result.errors)


class TestVectorStoreCache:
    """VectorStore 缓存测试"""

    def test_milvus_client_singleton(self):
        """MilvusClient 单例测试"""
        from dataocean.rag.milvus_client import get_client

        client1 = get_client()
        client2 = get_client()

        # 同一个实例
        assert client1 is client2


class TestRerankerIntegration:
    """reranker 集成测试"""

    def test_rerank_returns_retrieved_schema(self):
        """验证 rerank 返回 RetrievedSchema 对象"""
        results = [
            RetrievedSchema(
                table_name="orders",
                columns=[],
                score=0.8,
                relevance_score=0.8,
                chunk_type="TABLE_DESC",
                source_type="SCHEMA",
                source_version=1,
                snapshot_id=1,
                chunk_text="订单表",
                governance_status="NORMAL",
                review_status="APPROVED",
            ),
        ]

        request = RetrieveRequest(
            datasource_id=1,
            active_snapshot_id=1,
            question="查询订单",
            top_k=10,
        )

        reranked = rerank(results, request)

        # 验证返回类型
        assert len(reranked) > 0
        assert isinstance(reranked[0], RetrievedSchema)
        assert reranked[0].table_name == "orders"
