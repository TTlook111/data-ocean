"""检索诊断保留阶段和安全降级结果，不输出用户内容及供应商异常原文。"""

import logging
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from dataocean.rag.schema import RetrieveRequest
from dataocean.rag.service import retrieve_schemas
from dataocean.rag.retriever import _fetch_adjacent_chunks


def request_with_fallback(has_fallback: bool) -> RetrieveRequest:
    return RetrieveRequest(
        datasourceId=10,
        activeSnapshotId=4,
        question="private-question-content",
        buildId="build-a",
        collectionName="dataocean_rag_ds10_bbuilda",
        authorizedResources=["table:orders"],
        authorizedChunkIds=[7],
        fallbackChunks=[{
            "sourceId": 7, "snapshotId": 4, "ragBuildId": "build-a",
            "chunkType": "TABLE_DESC", "tableName": "orders",
            "chunkText": "private-knowledge-content", "resourceDependencies": ["table:orders"],
            "reviewStatus": "APPROVED", "factReviewStatus": "APPROVED", "governanceStatus": "NORMAL",
        }] if has_fallback else [],
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("has_fallback", [True, False])
async def test_retrieval_failure_logs_stage_without_content_and_preserves_fail_closed(has_fallback, caplog):
    request = request_with_fallback(has_fallback)
    cache = AsyncMock()
    cache.get.return_value = "[0.1]"
    failure = RuntimeError("private-vendor-detail token=secret-value")

    with patch("dataocean.rag.service._get_redis", new=AsyncMock(return_value=cache)), \
            patch("dataocean.rag.service.retrieve_from_milvus", new=AsyncMock(side_effect=failure)):
        if has_fallback:
            response = await retrieve_schemas(request)
            assert response.degraded and response.returned == 1
            assert response.results[0].chunk_text == "private-knowledge-content"
        else:
            with pytest.raises(RuntimeError) as caught:
                await retrieve_schemas(request)
            assert caught.value is failure

    assert "stage=vector_search" in caplog.text
    assert "error_type=RuntimeError" in caplog.text and "elapsed_ms=" in caplog.text
    for value in [request.question, "private-knowledge-content", "private-vendor-detail", "secret-value"]:
        assert value not in caplog.text
    assert all(record.exc_info is None for record in caplog.records)


@pytest.mark.asyncio
async def test_empty_recall_metrics_do_not_log_question(caplog):
    request = request_with_fallback(False)
    cache = AsyncMock()
    cache.get.return_value = "[0.1]"

    with caplog.at_level(logging.INFO, logger="dataocean.rag.service"), \
            patch("dataocean.rag.service._get_redis", new=AsyncMock(return_value=cache)), \
            patch("dataocean.rag.service.retrieve_from_milvus", new=AsyncMock(return_value=[])):
        response = await retrieve_schemas(request)

    assert response.returned == 0 and not response.degraded
    assert "RAG_RECALL datasource_id=10 snapshot_id=4 raw=0" in caplog.text
    assert request.question not in caplog.text


@pytest.mark.asyncio
async def test_adjacent_expansion_failure_does_not_log_vendor_details(caplog):
    client = MagicMock()
    client.query.side_effect = RuntimeError("private-query-expression token=secret-value")
    with patch("dataocean.rag.retriever.get_client", return_value=client):
        results = await _fetch_adjacent_chunks(
            [{"doc_id": 9, "version_no": 3, "chunk_group_id": "group-1", "chunk_index": 1}],
            datasource_id=10, snapshot_id=4,
        )

    assert results == []
    assert "datasource_id=10 snapshot_id=4 error_type=RuntimeError" in caplog.text
    assert "private-query-expression" not in caplog.text and "secret-value" not in caplog.text
    assert all(record.exc_info is None for record in caplog.records)


@pytest.mark.asyncio
@pytest.mark.parametrize("batch,explicit_config", [(False, False), (False, True), (True, False), (True, True)])
async def test_embedding_provider_failure_logs_only_type_and_preserves_exception(batch, explicit_config, monkeypatch, caplog):
    from dataocean.core.exceptions import LLMException
    from dataocean.infra import embeddings
    from dataocean.rag.schema import EmbeddingConfig

    # 进入真实 Embedding 包装层，只替换供应商客户端，覆盖内部 catch 的日志。
    failure = RuntimeError("private-input-content https://private-provider.invalid token=secret-value")
    provider = SimpleNamespace(
        aembed_query=AsyncMock(side_effect=failure),
        aembed_documents=AsyncMock(side_effect=failure),
    )
    factory = MagicMock(return_value=provider)
    monkeypatch.setattr(embeddings, "OpenAIEmbeddings", factory)
    monkeypatch.setattr(embeddings, "_embeddings", None)
    monkeypatch.setattr(embeddings, "_embedding_cache", {})
    monkeypatch.setattr(embeddings, "get_settings", lambda: SimpleNamespace(
        qwen_embedding_model="test-embedding-model", embedding_api_key="test-secret-key",
        dashscope_api_key="", embedding_base_url="https://private-provider.invalid",
        dashscope_base_url="https://private-provider.invalid", embedding_batch_size=10,
    ))
    config = EmbeddingConfig(
        providerId="test-provider", model="test-embedding-model",
        baseUrl="https://private-provider.invalid", apiKey="test-secret-key",
    )
    with pytest.raises(LLMException) as caught:
        if batch:
            if explicit_config:
                await embeddings.embed_texts_with_config(["private-input-content"], config)
            else:
                await embeddings.embed_texts(["private-input-content"])
        else:
            await embeddings.embed_single("private-input-content", config if explicit_config else None)

    factory.assert_called_once()
    (provider.aembed_documents if batch else provider.aembed_query).assert_awaited_once()
    assert caught.value.__cause__ is failure
    assert str(failure) in str(caught.value)
    assert "error_type=RuntimeError" in caplog.text
    for value in ["private-input-content", "private-provider.invalid", "secret-value", "test-secret-key"]:
        assert value not in caplog.text
    assert all(record.exc_info is None for record in caplog.records)
