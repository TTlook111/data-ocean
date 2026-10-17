from unittest.mock import AsyncMock
import pytest

from dataocean.iam_s1 import service as s1_service
from dataocean.iam_s1.schema import S1PermissionSnapshot, S1RagRetrieveRequest
from dataocean.rag import service as rag_service
from dataocean.rag.schema import RetrievedSchema


@pytest.mark.asyncio
@pytest.mark.parametrize("fact_type", ["COLUMN_STRUCTURE", "GLOSSARY_TERM", "SEMANTIC_NOTE"])
async def test_reviewed_fact_survives_ann_rerank_and_s1_filter_without_full_fallback(monkeypatch, fact_type):
    build_id = "abcdef12-3456-7890-abcd-ef1234567890"
    deps = ["table:products", "column:products.category"]
    fact = RetrievedSchema(table_name="products", related_tables=["products"],
        related_columns=["products.category"], columns=[{"name": "products.category"}],
        source_id=91, doc_id=4, source_version=2, snapshot_id=88,
        chunk_type="FIELD_NOTE", chunk_text="商品类别对应 products.category，用于按类别汇总。",
        score=0.95, governance_status="NORMAL", review_status="APPROVED",
        resource_dependencies=deps, fact_source_ids=["3"], fact_type=fact_type,
        fact_review_status="APPROVED", build_id=build_id)
    snapshot = S1PermissionSnapshot(protocolVersion="IAM-SIMPLE-1", taskId="provenance-test",
        userId=7, datasourceId=1, activeMetadataSnapshotId=88, permissionRevision=9,
        calculatedAt="2026-10-18T00:00:00", nextEffectiveAt=None,
        resources=[{"tableName": "products", "grantSources": [], "sourceSnapshotId": 88,
                    "columns": [{"name": "category", "columnId": 20, "usage": ["PROJECTION", "GROUP"],
                                 "protectionLevel": "NORMAL", "maskPolicy": None, "dataType": "VARCHAR",
                                 "governanceStatus": "NORMAL", "sourceSnapshotId": 88}]}],
        capabilities={"query": True, "viewSql": True, "export": False})
    fallback = {"sourceId": 91, "datasourceId": 1, "sourceSnapshotId": 88, "ragBuildId": build_id,
        "tables": ["products"], "columns": ["products.category"], "resourceDependencies": deps,
        "factSourceIds": ["3"], "factType": fact_type, "factReviewStatus": "APPROVED",
        "reviewStatus": "APPROVED", "governanceStatus": "NORMAL", "chunkText": "备用来源"}
    monkeypatch.setattr(rag_service, "retrieve_from_milvus", AsyncMock(return_value=[fact]))
    monkeypatch.setattr(rag_service, "embed_single", AsyncMock(return_value=[0.1]))
    monkeypatch.setattr(rag_service, "_get_redis", AsyncMock(side_effect=RuntimeError("cache unavailable")))
    result = await s1_service.retrieve(S1RagRetrieveRequest(protocolVersion="IAM-SIMPLE-1",
        taskId="provenance-test", userId=7, datasourceId=1, activeMetadataSnapshotId=88,
        permissionRevision=9, permissionSnapshot=snapshot, question="按商品类别汇总",
        chunks=[fallback], ragBuildId=build_id, ragSourceSnapshotId=88,
        ragCollectionName="dataocean_rag_ds1_babcdef1234567890abcdef1234567890",
        ragEmbeddingConfig={"model": "test-embedding", "dimension": 1}))
    assert len(result) == 1
    assert result[0]["chunkText"] == fact.chunk_text
    assert result[0]["sourceId"] == 91
    assert result[0]["factType"] == fact_type
    assert result[0]["factSourceIds"] == ["3"]
    assert result[0]["resourceDependencies"] == deps
    assert result[0]["factReviewStatus"] == "APPROVED"
