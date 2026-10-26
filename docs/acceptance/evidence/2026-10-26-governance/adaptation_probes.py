"""离线诊断反例；失败断言代表仍未修复的断点，不是生产测试修改。

运行时把 python-service 及其 tests 加入 PYTHONPATH；不使用真实模型/向量库。
"""
import copy
import json
import os
import socket
import time

os.environ["INTERNAL_TOKEN"] = "test-internal-token-0123456789abcdef"
os.environ["LANGCHAIN_TRACING_V2"] = "false"

import pytest
from unittest.mock import AsyncMock
from dataocean.knowledge.service import generate_draft, _lineage_dependencies
from dataocean.rag.chunker import chunk_skills_md
from dataocean.iam_s1.firewall import filter_chunk
from dataocean.iam_s1.schema import S1PermissionSnapshot
from dataocean.iam_s1 import graph
from test_knowledge_snapshot_document import _request
from test_rag_rerank_fact_provenance import (
    test_reviewed_fact_survives_ann_rerank_and_s1_filter_without_full_fallback as rag_probe,
)


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    def deny(*args, **kwargs):
        raise AssertionError("本诊断禁止真实网络连接")
    monkeypatch.setattr(socket.socket, "connect", deny)
    monkeypatch.setattr(socket.socket, "connect_ex", deny)


def permission():
    return S1PermissionSnapshot(
        protocolVersion="IAM-SIMPLE-1", taskId="offline-probe", userId=7,
        datasourceId=701, activeMetadataSnapshotId=8801, permissionRevision=9,
        calculatedAt="2026-10-26T00:00:00", nextEffectiveAt=None,
        resources=[{
            "tableName": table, "grantSources": [], "sourceSnapshotId": 8801,
            "columns": [{"name": "product_id", "columnId": i,
                         "usage": ["PROJECTION", "JOIN"], "protectionLevel": "NORMAL",
                         "maskPolicy": None, "dataType": "INT", "governanceStatus": "NORMAL",
                         "sourceSnapshotId": 8801}],
        } for i, table in enumerate(["sales_orders", "products"], 1)],
        capabilities={"query": True, "viewSql": True, "export": False},
    )


def chunk_payload(chunk):
    return {
        "datasourceId": 701, "sourceSnapshotId": 8801, "ragBuildId": "offline-build",
        "sourceId": 401, "tables": chunk.related_tables, "columns": chunk.related_columns,
        "resourceDependencies": chunk.resource_dependencies, "factSourceIds": chunk.fact_source_ids,
        "factType": chunk.fact_type, "chunkType": chunk.chunk_type, "chunkText": chunk.chunk_text,
        "reviewStatus": chunk.review_status, "factReviewStatus": chunk.fact_review_status,
        "governanceStatus": chunk.governance_status,
    }


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["LINEAGE", "DERIVED_FROM"])
async def test_column_lineage_document_chunk_firewall_and_planner_input(monkeypatch, kind):
    req = _request()
    deps = ["table:sales_orders", "column:sales_orders.product_id",
            "table:products", "column:products.product_id"]
    req.lineage_facts = [{**req.lineage_facts[0], "relation_type": kind,
                         "source_fqn": "ds.db.sales_orders.product_id",
                         "target_fqn": "ds.db.products.product_id", "dependencies": deps}]
    doc = await generate_draft(req)
    chunks = [c for c in chunk_skills_md(doc.content, expected_snapshot_id=8801)
              if c.fact_type == kind]
    assert len(chunks) == 1
    chunk = chunks[0]
    assert chunk.chunk_type == "LINEAGE"
    assert chunk.fact_source_ids == ["401"]
    assert set(chunk.resource_dependencies) == set(deps)
    payload = chunk_payload(chunk)
    safe = filter_chunk(payload, permission(), rag_build_id="offline-build")
    assert safe is not None
    assert filter_chunk({**payload, "resourceDependencies": deps + ["column:products.hidden"]},
                        permission(), rag_build_id="offline-build") is None
    assert filter_chunk({**payload, "sourceSnapshotId": 8799}, permission()) is None
    captured = {}
    async def llm(state, scene, system, prompt, *args):
        captured.update(json.loads(prompt))
        return json.dumps({"selectedResources": [{"tableName": "products", "columns": ["product_id"]}]}), dict(state)
    monkeypatch.setattr(graph, "_progress", AsyncMock())
    monkeypatch.setattr(graph, "_budgeted_llm", llm)
    state = {"taskId": "offline-probe", "deadlineEpochSeconds": time.time() + 30,
             "question": "products", "safeRag": [safe], "glossaryTerms": [],
             "candidateCatalog": {"tables": [{"tableName": "products", "columns": [
                 {"columnName": "product_id", "allowedUsages": ["PROJECTION", "JOIN"],
                  "protectionLevel": "NORMAL", "governanceStatus": "NORMAL"}]}]}}
    result = await graph._plan_node(state)
    assert result["linkedResources"] == [{"tableName": "products", "columns": ["product_id"]}]
    assert captured["reviewedFacts"][0]["factType"] == kind
    assert set(captured["reviewedFacts"][0]["resourceDependencies"]) == set(deps)


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["LINEAGE", "DERIVED_FROM", "BUSINESS_RELATION"])
async def test_generic_rag_preserves_additional_fact_types(monkeypatch, kind):
    # 复用仓库实际 retrieve/rerank/firewall 测试，ANN、embedding、Redis 均模拟。
    await rag_probe(monkeypatch, kind)


@pytest.mark.parametrize("kind", ["TABLE_STRUCTURE", "TABLE_COMMENT", "BUSINESS_DATASOURCE",
                                 "BUSINESS_TABLE", "BUSINESS_METRIC", "LINEAGE", "DERIVED_FROM"])
def test_table_only_fact_admission(kind):
    payload = {"datasourceId": 701, "sourceSnapshotId": 8801, "factType": kind,
               "tables": ["products"], "columns": [], "resourceDependencies": ["table:products"],
               "factSourceIds": ["401"], "reviewStatus": "APPROVED",
               "factReviewStatus": "APPROVED", "governanceStatus": "NORMAL"}
    assert filter_chunk(payload, permission()) is not None


def test_business_relation_adds_only_authorized_join_keys():
    selected = [{"tableName": "orders", "columns": ["amount"]},
                {"tableName": "customers", "columns": ["name"]}]
    linked = {"orders": {"columns": [{"columnName": "customer_id", "allowedUsages": ["JOIN"]}]},
              "customers": {"columns": [{"columnName": "id", "allowedUsages": ["JOIN"]}]}}
    relation = {"factType": "BUSINESS_RELATION", "chunkType": "JOIN_PATH",
                "factReviewStatus": "APPROVED", "reviewStatus": "APPROVED",
                "resourceDependencies": ["table:orders", "table:customers", "column:orders.customer_id", "column:customers.id"]}
    old = copy.deepcopy(selected)
    assert graph._add_confirmed_join_columns(old, linked, [{**relation, "factType": "JOIN_PATH"}]) is None
    assert old[0]["columns"] == ["amount", "customer_id"]
    assert graph._add_confirmed_join_columns(selected, linked, [relation]) is None
    assert selected == old


def test_python_missing_dependency_fallback_understands_three_part_table_fqn():
    assert _lineage_dependencies({"source_fqn": "ds.db.orders", "target_fqn": "ds.db.rollups"}) == [
        "table:orders", "table:rollups"]
