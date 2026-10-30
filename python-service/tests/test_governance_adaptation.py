"""治理事实交接的正反例；不访问模型、向量库或业务库。"""
import copy
import json
import time
from unittest.mock import AsyncMock

import pytest

from dataocean.iam_s1 import graph
from dataocean.iam_s1.firewall import filter_chunk
from dataocean.knowledge.service import _lineage_dependencies
from test_iam_s1_security import snapshot


@pytest.mark.asyncio
async def test_planner_uses_only_the_selected_relation_not_other_same_table_candidates(monkeypatch):
    selected = [{"tableName": "orders", "columns": ["amount", "buyer_id"]},
                {"tableName": "customers", "columns": ["id", "name"]}]
    linked = [{"tableName": "orders", "columns": [
        {"columnName": "amount", "allowedUsages": ["PROJECTION"]},
        {"columnName": "buyer_id", "allowedUsages": ["JOIN"]},
        {"columnName": "seller_id", "allowedUsages": ["FILTER"]}]},
        {"tableName": "customers", "columns": [
            {"columnName": "id", "allowedUsages": ["JOIN"]},
            {"columnName": "name", "allowedUsages": ["PROJECTION"]}]}]
    def relation(identity, key):
        return {"factType": "BUSINESS_RELATION", "factSourceIds": [identity],
                "reviewStatus": "APPROVED", "factReviewStatus": "APPROVED",
                "resourceDependencies": ["table:orders", "table:customers", f"column:orders.{key}", "column:customers.id"]}
    state = {"taskId": "selected-relation", "question": "按买方统计订单金额", "deadlineEpochSeconds": time.time() + 30,
             "safeRag": [relation("401", "buyer_id"), relation("402", "seller_id")], "glossaryTerms": []}
    monkeypatch.setattr(graph, "_progress", AsyncMock())
    monkeypatch.setattr(graph, "_link_candidates", lambda _: linked)
    monkeypatch.setattr(graph, "_unmapped_requested_object", lambda *_: False)
    monkeypatch.setattr(graph, "_budgeted_llm", AsyncMock(return_value=(json.dumps({
        "selectedResources": selected, "selectedRelationIds": ["401"], "clarificationNeeded": False}), state)))
    result = await graph._plan_node(state)
    assert result["status"] == "PROCESSING"
    assert result["linkedResources"] == selected
    assert "seller_id" not in result["linkedResources"][0]["columns"]
    assert result["selectedRelationIds"] == ["401"]
    assert [fact["factSourceIds"] for fact in graph._linked_rag(result)] == [["401"]]


@pytest.mark.parametrize("chosen", [["unknown"], "401", [True], ["401", "402"]])
def test_invalid_or_unreviewed_relation_choices_do_not_mutate_resources(chosen):
    selected, linked, fact = join_case()
    before = copy.deepcopy(selected)
    unreviewed = {**fact, "factSourceIds": ["402"], "factReviewStatus": "PENDING"}
    with pytest.raises(ValueError):
        graph._add_confirmed_join_columns(selected, linked, [fact, unreviewed], chosen)
    assert selected == before


def test_only_the_selected_relation_can_reach_sql_context_even_if_other_keys_are_visible():
    selected, _, buyer = join_case()
    buyer["resourceDependencies"] = ["table:orders", "table:customers", "column:orders.buyer_id", "column:customers.id"]
    seller = {**buyer, "factSourceIds": ["402"], "resourceDependencies": [
        "table:orders", "table:customers", "column:orders.seller_id", "column:customers.id"]}
    state = {"safeRag": [buyer, seller], "selectedRelationIds": ["401"], "linkedResources": [
        {"tableName": "orders", "columns": ["buyer_id", "seller_id"]}, {"tableName": "customers", "columns": ["id"]}]}
    assert graph._linked_rag(state) == [buyer]


@pytest.mark.asyncio
async def test_frozen_lineage_fingerprint_survives_document_chunk_and_wire_response():
    from dataocean.knowledge.service import generate_draft
    from dataocean.rag.chunker import chunk_skills_md
    from test_knowledge_snapshot_document import _request
    request = _request()
    request.lineage_facts[0]["source_fingerprint"] = "f" * 64
    result = await generate_draft(request)
    chunks = [chunk for chunk in chunk_skills_md(result.content, expected_snapshot_id=8801) if chunk.fact_type == "LINEAGE"]
    assert len(chunks) == 1
    assert chunks[0].source_fingerprint == "f" * 64
    assert chunks[0].model_dump(by_alias=True)["sourceFingerprint"] == "f" * 64


def test_table_lineage_dependencies_use_table_not_database():
    fact = {"relation_type": "LINEAGE", "source_fqn": "ds.db.orders", "target_fqn": "ds.db.customers"}
    assert set(_lineage_dependencies(fact)) == {"table:orders", "table:customers"}


@pytest.mark.parametrize("fact", [
    {"relation_type": "DERIVED_FROM", "source_fqn": "ds.db.orders", "target_fqn": "ds.db.customers"},
    {"relation_type": "LINEAGE", "source_fqn": "ds.db.orders", "target_fqn": "other.db.customers"},
    {"relation_type": "LINEAGE", "source_fqn": "ds.db.orders", "target_fqn": "ds.db.customers", "dependencies": ["table:db"]},
])
def test_lineage_rejects_wrong_shape_external_scope_or_forged_dependencies(fact):
    assert _lineage_dependencies(fact) == []


def table_chunk(kind="LINEAGE"):
    return {"datasourceId": 1, "sourceSnapshotId": 88, "tables": ["orders", "customers"],
            "columns": [], "resourceDependencies": ["table:orders", "table:customers"],
            "factSourceIds": ["401"], "factType": kind, "factReviewStatus": "APPROVED",
            "reviewStatus": "APPROVED", "governanceStatus": "NORMAL"}


def test_table_lineage_is_admitted_but_empty_field_derivation_is_rejected():
    current = snapshot()
    current = current.model_copy(update={"resources": [*current.resources, current.resources[0].model_copy(update={"tableName": "customers"})]})
    assert filter_chunk(table_chunk(), current) is not None
    assert filter_chunk(table_chunk("DERIVED_FROM"), current) is None
    assert filter_chunk({**table_chunk(), "resourceDependencies": ["table:orders"]}, current) is None
    assert filter_chunk({**table_chunk(), "resourceDependencies": [{"table": "orders"}]}, current) is None


def join_case():
    selected = [{"tableName": "orders", "columns": ["amount"]}, {"tableName": "customers", "columns": ["name"]}]
    linked = {"orders": {"columns": [{"columnName": "customer_id", "allowedUsages": ["JOIN"]}]},
              "customers": {"columns": [{"columnName": "id", "allowedUsages": ["JOIN"]}]}}
    fact = {"factType": "BUSINESS_RELATION", "factSourceIds": ["401"], "reviewStatus": "APPROVED", "factReviewStatus": "APPROVED",
            "resourceDependencies": ["table:orders", "table:customers", "column:orders.customer_id", "column:customers.id"]}
    return selected, linked, fact


def test_new_reviewed_relation_adds_authorized_keys_without_legacy_bypass():
    selected, linked, fact = join_case()
    assert graph._add_confirmed_join_columns(selected, linked, [fact], ["401"]) is None
    assert selected[0]["columns"] == ["amount", "customer_id"]
    assert selected[1]["columns"] == ["name", "id"]
    selected, linked, fact = join_case()
    assert graph._add_confirmed_join_columns(selected, linked, [{**fact, "factType": "JOIN_PATH"}]) is None
    assert selected[0]["columns"] == ["amount"]


def test_join_key_denial_does_not_partially_mutate_selected_resources():
    selected, linked, fact = join_case()
    linked["customers"]["columns"][0]["allowedUsages"] = ["PROJECTION"]
    before = copy.deepcopy(selected)
    assert graph._add_confirmed_join_columns(selected, linked, [fact], ["401"]) is not None
    assert selected == before
