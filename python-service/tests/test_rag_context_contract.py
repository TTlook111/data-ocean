from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from dataocean.rag.chunker import (
    CHUNK_OVERLAP_TOKENS,
    MAX_CHUNK_TOKENS,
    chunk_tables,
    chunk_skills_md,
    count_tokens,
    validate_skills_md_structure,
)
from dataocean.rag.fallback import _question_match_score, fallback_retrieve
from dataocean.rag.milvus_client import _validate_existing_collection
from dataocean.rag.retriever import _fetch_adjacent_chunks
from dataocean.rag.vectorizer import vectorize_chunks


def _valid_document(body: str = "orders 的订单说明") -> str:
    return f"""
## 1. 文档来源
基于当前元数据快照生成。

## 2. 核心表说明
### orders — 订单表
<!-- dataocean-fact: {{"snapshotId":1,"factType":"TABLE_STRUCTURE","sourceIds":[1],"dependencies":["table:orders"],"reviewStatus":"APPROVED","governanceStatus":"NORMAL"}} -->
{body}
<!-- dataocean-fact: {{"snapshotId":1,"factType":"COLUMN_STRUCTURE","sourceIds":[1,11],"dependencies":["table:orders","column:orders.amount"],"reviewStatus":"APPROVED","governanceStatus":"NORMAL"}} -->

## 3. Join Path（关联路径）
### orders ↔ customers
<!-- dataocean-fact: {{"snapshotId":1,"factType":"JOIN_PATH","sourceIds":[2],"dependencies":["table:orders","table:customers","column:orders.customer_id","column:customers.id"],"reviewStatus":"APPROVED","governanceStatus":"NORMAL"}} -->
- 关联条件: `orders.customer_id = customers.id`

## 4. 指标口径
### 订单数量
- SQL 表达式: `COUNT(*)`

## 5. 字段防坑指南
### orders.amount
- 金额单位为元。

## 6. 常见查询场景
### 按月统计订单数量
- 涉及表: orders
"""


def test_long_semantic_unit_uses_token_budget_and_same_group() -> None:
    long_body = "订单金额字段用于统计订单总额，必须按照订单状态过滤。" * 220
    chunks = chunk_skills_md(
        "## 核心表说明\n### orders — 订单表\n"
        '<!-- dataocean-fact: {"snapshotId":5,"factType":"TABLE_STRUCTURE","sourceIds":[1],"dependencies":["table:orders"],"reviewStatus":"APPROVED"} -->\n'
        + long_body
    )

    assert len(chunks) > 1
    assert all(count_tokens(chunk.chunk_text) <= MAX_CHUNK_TOKENS for chunk in chunks)
    assert len({chunk.chunk_group_id for chunk in chunks}) == 1
    assert [chunk.chunk_index for chunk in chunks] == list(range(len(chunks)))
    assert CHUNK_OVERLAP_TOKENS == 150


def test_short_semantic_unit_is_not_padded_or_discarded() -> None:
    chunks = chunk_skills_md(
        "## Join Path\n### orders ↔ users\n"
        '<!-- dataocean-fact: {"snapshotId":5,"factType":"JOIN_PATH","sourceIds":[1],"dependencies":["table:orders","table:users","column:orders.id","column:users.id"],"reviewStatus":"APPROVED"} -->\n'
        "orders.id = users.id"
    )

    assert len(chunks) == 1
    assert "orders.id = users.id" in chunks[0].chunk_text
    assert count_tokens(chunks[0].chunk_text) < 800


def test_join_chunk_preserves_qualified_related_columns() -> None:
    chunks = chunk_skills_md(
        "## Join Path\n### orders ↔ customers\n"
        '<!-- dataocean-fact: {"snapshotId":5,"factType":"JOIN_PATH","sourceIds":[2],"dependencies":["table:orders","table:customers","column:orders.customer_id","column:customers.id"],"reviewStatus":"APPROVED"} -->\n'
        "关联条件: `orders.customer_id = customers.id`"
    )

    assert len(chunks) == 1
    assert set(chunks[0].related_columns) == {"orders.customer_id", "customers.id"}
    assert chunks[0].related_column == "customer_id"


def test_table_fallback_keeps_all_columns_and_applies_token_budget() -> None:
    columns = [
        {
            "column_name": f"column_{index}",
            "column_type": "VARCHAR(255)",
            "column_comment": "字段说明 " * 12,
        }
        for index in range(80)
    ]
    chunks = chunk_tables([
        {"table_name": "orders", "table_comment": "订单表", "columns": columns}
    ])

    assert len(chunks) > 1
    assert all(count_tokens(chunk.chunk_text) <= MAX_CHUNK_TOKENS for chunk in chunks)
    assert all(chunk.chunk_group_id == chunks[0].chunk_group_id for chunk in chunks)
    assert all(column in "\n".join(chunk.chunk_text for chunk in chunks) for column in [
        "column_0", "column_79"
    ])


def test_skills_document_structure_validator() -> None:
    assert validate_skills_md_structure(_valid_document()) == []
    errors = validate_skills_md_structure("## 核心表说明\n### orders\n订单表")
    assert any("缺少顶级章节" in error for error in errors)


def test_fallback_rejects_other_snapshot_and_preserves_metadata() -> None:
    response = fallback_retrieve(
        10,
        [
            {
                "docId": 1,
                "sourceId": 2,
                "snapshotId": 4,
                "chunkType": "TABLE_DESC",
                "tableName": "old_orders",
                "chunkText": "旧版本",
            },
            {
                "docId": 3,
                "sourceId": 4,
                "snapshotId": 5,
                "knowledgeVersionNo": 2,
                "chunkType": "TABLE_DESC",
                "tableName": "orders",
                "relatedColumns": '["order_id", "amount"]',
                "chunkIndex": 1,
                "chunkGroupId": "orders-group",
                "chunkText": "orders amount",
            },
            {
                "docId": 5,
                "sourceId": 6,
                "chunkType": "TABLE_DESC",
                "tableName": "unbound_orders",
                "chunkText": "没有快照绑定的信息",
            },
        ],
        active_snapshot_id=5,
        question="查询 orders amount",
        limit=10,
    )

    assert response.returned == 1
    assert response.results[0].table_name == "orders"
    assert response.results[0].snapshot_id == 5
    assert [column.name for column in response.results[0].columns] == ["order_id", "amount"]


def test_fallback_supports_chinese_keyword_matching() -> None:
    score = _question_match_score(
        "查询订单金额",
        "orders",
        ["orders"],
        ["orders.amount"],
        "订单金额字段，单位为元",
    )

    assert score > 0


def test_existing_milvus_collection_rejects_non_ip_metric() -> None:
    client = MagicMock()
    client.describe_collection.return_value = {
        "fields": [{
            "name": "embedding",
            "type": "FLOAT_VECTOR",
            "params": {"dim": 2},
        }]
    }
    client.list_indexes.return_value = ["embedding_index"]
    client.describe_index.return_value = {"metric_type": "COSINE"}

    with pytest.raises(RuntimeError, match="度量不兼容"):
        _validate_existing_collection(client, "schema_collection", 2)


def test_existing_milvus_collection_accepts_ip_metric() -> None:
    client = MagicMock()
    client.describe_collection.return_value = {
        "fields": [{
            "name": "embedding",
            "type": "FLOAT_VECTOR",
            "params": {"dim": 2},
        }]
    }
    client.list_indexes.return_value = ["embedding_index"]
    client.describe_index.return_value = {"params": {"metric_type": "IP"}}

    _validate_existing_collection(client, "schema_collection", 2)


@pytest.mark.asyncio
async def test_adjacent_query_uses_document_group_and_index() -> None:
    client = MagicMock()
    client.query.return_value = [
        {
            "doc_id": 9,
            "source_id": 12,
            "snapshot_id": 5,
            "knowledge_version_no": 3,
            "chunk_index": 2,
            "chunk_group_id": "group-1",
            "chunk_type": "TABLE_DESC",
            "chunk_text": "相邻内容",
            "related_table": "orders",
            "related_column": "amount",
            "governance_status": "NORMAL",
            "review_status": "APPROVED",
        }
    ]

    with patch("dataocean.rag.retriever.get_client", return_value=client):
        results = await _fetch_adjacent_chunks(
            chunk_contexts=[
                {
                    "doc_id": 9,
                    "version_no": 3,
                    "chunk_group_id": "group-1",
                    "chunk_index": 1,
                }
            ],
            datasource_id=10,
            snapshot_id=5,
        )

    expression = client.query.call_args.kwargs["filter"]
    assert "doc_id == 9" in expression
    assert "knowledge_version_no == 3" in expression
    assert 'chunk_group_id == "group-1"' in expression
    assert "chunk_index >= 0" in expression
    assert "chunk_index <= 2" in expression
    assert results[0].document.metadata["context_expansion"] is True


@pytest.mark.asyncio
async def test_vectorizer_fails_when_cleanup_returns_false() -> None:
    from dataocean.rag.schema import ChunkItem

    chunks = [
        ChunkItem(
            source_id=1,
            chunk_type="TABLE_DESC",
            chunk_text="orders",
            related_table="orders",
        )
    ]
    delete_mock = AsyncMock(return_value=False)
    with patch("dataocean.rag.vectorizer.ensure_collection", return_value=MagicMock()), patch(
        "dataocean.rag.vectorizer.embed_texts", new=AsyncMock(return_value=[[0.1, 0.2]])
    ), patch(
        "dataocean.rag.vectorizer.add_chunk_embeddings", new=AsyncMock(return_value=["1"])
    ), patch("dataocean.rag.vectorizer.delete_by_expr", new=delete_mock):
        response = await vectorize_chunks(
            datasource_id=10,
            snapshot_id=5,
            version_no=3,
            chunks=chunks,
            doc_id=99,
            target_dimension=2,
            force=True,
        )

    assert response.status == "FAILED"
    assert any("write failed" in error for error in response.errors)


def test_build_candidate_filter_drops_mixed_or_stale_facts_before_reranking() -> None:
    from dataocean.rag.schema import RetrievedSchema, RetrieveRequest
    from dataocean.rag.service import _filter_build_candidates

    request = RetrieveRequest(
        datasourceId=10,
        activeSnapshotId=4,
        question="查询订单",
        topK=1,
        buildId="build-a",
        collectionName="dataocean_rag_ds10_bbuilda",
        authorizedResources=["table:orders", "column:orders.id"],
        authorizedChunkIds=[7, 8, 9],
    )
    safe = RetrievedSchema(
        tableName="orders", columns=[], score=0.99, snapshotId=4, buildId="build-a",
        chunkText="safe", resourceDependencies=["table:orders", "column:orders.id"],
        factSourceIds=["fact:7"], factType="COLUMN_STRUCTURE",
        factReviewStatus="APPROVED", reviewStatus="APPROVED", governanceStatus="NORMAL",
    )
    mixed = safe.model_copy(update={
        "source_id": 8,
        "resource_dependencies": ["table:orders", "column:orders.id", "column:orders.secret"],
    })
    stale_build = safe.model_copy(update={"source_id": 9, "build_id": "old-build"})

    assert _filter_build_candidates([mixed, stale_build, safe], request) == [safe]


@pytest.mark.asyncio
async def test_neighbor_expansion_drops_unlisted_chunks_before_reranking() -> None:
    from langchain_core.documents import Document
    from dataocean.rag.retriever import retrieve_from_milvus
    from dataocean.rag.schema import RetrieveRequest
    from dataocean.rag.vector_store import SearchHit

    safe = SearchHit(
        Document("authorized", metadata={
            "source_id": 1, "snapshot_id": 4, "doc_id": 10,
            "source_version": 1, "chunk_group_id": "field-1", "chunk_index": 1,
            "related_table": "orders", "related_columns": '["orders.id"]',
            "related_tables": '["orders"]', "entity_ids": "[]", "trust_score": 90,
            "governance_status": "NORMAL", "review_status": "APPROVED",
            "resource_dependencies": '["table:orders","column:orders.id"]',
            "fact_source_ids": '["fact:1"]', "fact_type": "COLUMN_STRUCTURE",
            "fact_review_status": "APPROVED", "build_id": "build-a",
        }),
        0.9,
    )
    forbidden_neighbor = SearchHit(Document("unauthorized neighbor", metadata={"source_id": 2}), 0.3)
    request = RetrieveRequest(
        datasourceId=10, activeSnapshotId=4, question="订单",
        topK=1, buildId="build-a", collectionName="collection-a",
        authorizedChunkIds=[1], authorizedResources=["table:orders", "column:orders.id"],
    )
    with patch("dataocean.rag.retriever.search_by_vector", new=AsyncMock(return_value=[safe])), \
            patch("dataocean.rag.retriever._fetch_adjacent_chunks", new=AsyncMock(return_value=[forbidden_neighbor])):
        results = await retrieve_from_milvus([0.1], request)

    assert [item.chunk_text for item in results] == ["authorized"]


def test_mysql_fallback_applies_dependency_filter_before_top_k() -> None:
    allowed = {
        "datasourceId": 10, "snapshotId": 4, "ragBuildId": "build-a",
        "chunkType": "TABLE_DESC", "tableName": "orders", "tables": ["orders"],
        "columns": ["orders.id"], "resourceDependencies": ["table:orders", "column:orders.id"],
        "factSourceIds": ["fact:7"], "factType": "COLUMN_STRUCTURE",
        "factReviewStatus": "APPROVED", "reviewStatus": "APPROVED",
        "governanceStatus": "NORMAL", "chunkText": "订单说明",
    }
    mixed = {
        **allowed,
        "sourceId": 8,
        "resourceDependencies": ["table:orders", "column:orders.id", "column:orders.secret"],
        "chunkText": "订单说明 secret",
    }
    response = fallback_retrieve(
        10, [mixed, allowed], active_snapshot_id=4, question="订单", limit=1,
        build_id="build-a", authorized_resources=["table:orders", "column:orders.id"],
    )

    assert response.returned == 1
    assert response.results[0].chunk_text == "订单说明"


def test_query_embedding_cache_is_scoped_to_the_active_build_embedding_config() -> None:
    from dataocean.rag.schema import EmbeddingConfig
    from dataocean.rag.service import embedding_cache_key

    embedding_a = EmbeddingConfig(providerId="provider-a", baseUrl="https://a.test/v1", model="embed-a", dimension=1024)
    embedding_b = EmbeddingConfig(providerId="provider-b", baseUrl="https://b.test/v1", model="embed-b", dimension=768)
    key_a = embedding_cache_key("同一个问题", embedding_a)
    key_b = embedding_cache_key("同一个问题", embedding_b)
    assert key_a != key_b
    assert embedding_cache_key("同一个问题", embedding_a) == key_a


@pytest.mark.asyncio
async def test_milvus_search_batches_the_full_authorized_chunk_set_before_ann_top_k() -> None:
    from dataocean.rag.vector_store import search_by_vector

    class FakeMilvusClient:
        def __init__(self):
            self.filters = []

        def search(self, **kwargs):
            self.filters.append(kwargs["filter"])
            return [[{"distance": 0.9, "entity": {"source_id": len(self.filters), "chunk_text": "safe"}}]]

    client = FakeMilvusClient()
    allowed_ids = list(range(1, 502))
    with patch("dataocean.rag.vector_store.get_client", return_value=client), \
            patch("dataocean.rag.vector_store._get_nprobe", return_value=1):
        hits = await search_by_vector(
            embedding=[0.1, 0.2],
            datasource_id=10,
            snapshot_id=4,
            limit=2,
            collection_name="dataocean_rag_ds10_bbuilda",
            authorized_chunk_ids=allowed_ids,
            build_id="build-a",
        )

    assert len(client.filters) == 2
    assert all('build_id == "build-a"' in expression for expression in client.filters)
    assert "source_id in [1" in client.filters[0]
    assert "source_id in [501]" in client.filters[1]
    assert len(hits) == 2
