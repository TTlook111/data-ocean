"""B3 IAM-SIMPLE-1 合同、Firewall、AST 和参数化记录条件测试。"""

from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from pydantic import ValidationError

from dataocean.iam_s1.firewall import build_model_context, filter_chunk, filter_schema
from dataocean.iam_s1.schema import (
    S1Capabilities,
    S1Column,
    S1ConnectionConfig,
    S1ExecutionBinding,
    S1GrantSource,
    S1PermissionSnapshot,
    S1Predicate,
    S1Resource,
    S1RagRetrieveRequest,
    S1RowCondition,
    S1SqlExecuteRequest,
)
from dataocean.iam_s1.sql_security import inject_row_conditions, validate_sql
from dataocean.iam_s1.service import execute_validated, retrieve


def snapshot(masked: bool = False, condition: bool = False) -> S1PermissionSnapshot:
    columns = [
        S1Column(name="id", columnId=1, usage=["PROJECTION"], protectionLevel="NORMAL", maskPolicy=None, dataType="INT", governanceStatus="NORMAL", sourceSnapshotId=88),
        S1Column(name="region", columnId=2, usage=["FILTER"], protectionLevel="NORMAL", maskPolicy=None, dataType="VARCHAR", governanceStatus="NORMAL", sourceSnapshotId=88),
    ]
    if masked:
        columns.append(S1Column(name="phone", columnId=3, usage=["PROJECTION"], protectionLevel="MASKED", maskPolicy="PHONE", dataType="VARCHAR", governanceStatus="NORMAL", sourceSnapshotId=88))
    row_condition = None
    if condition:
        row_condition = S1RowCondition(matchType="ALL", predicates=[S1Predicate(columnName="region", columnId=2, operatorCode="EQ", valueType="STRING", parameterReference=None, bindingReference="g1")])
    return S1PermissionSnapshot(
        protocolVersion="IAM-SIMPLE-1", taskId="task-1", userId=7, datasourceId=1,
        activeMetadataSnapshotId=88, permissionRevision=100, calculatedAt="2026-09-18T10:00:00+08:00",
        nextEffectiveAt=None,
        resources=[S1Resource(tableName="orders", columns=columns, grantSources=[S1GrantSource(grantId=1, sourceSummary="S1", grantSource="MANUAL", sourceReferenceId=None, explicitColumns=[c.name for c in columns], rowCondition=row_condition)], sourceSnapshotId=88)],
        capabilities=S1Capabilities(query=True, viewSql=False, export=False),
    )


def test_s1_schema_rejects_legacy_flat_permissions_and_missing_security_fields():
    with pytest.raises(ValidationError):
        S1PermissionSnapshot.model_validate({"protocolVersion": "IAM-SIMPLE-1", "allowedTables": ["orders"]})
    with pytest.raises(ValidationError):
        S1Column.model_validate({"name": "id", "columnId": 1, "protectionLevel": "NORMAL", "maskPolicy": None, "dataType": "INT", "governanceStatus": "NORMAL", "sourceSnapshotId": 88})


def test_firewall_removes_hidden_and_masked_sample_values():
    current = snapshot(masked=True)
    schema = [{"tableName": "orders", "columns": [{"name": "id"}, {"name": "phone", "sample_values": ["13800000000"]}, {"name": "secret"}]}]
    result = filter_schema(schema, current)
    assert [item["name"] for item in result[0]["columns"]] == ["id", "phone"]
    assert "sample_values" not in result[0]["columns"][1]


def test_firewall_rejects_unbound_or_wrong_snapshot_chunks_and_keeps_safe_chunks():
    current = snapshot()
    safe = {"datasourceId": 1, "activeMetadataSnapshotId": 88, "tables": ["orders"], "columns": ["orders.id"], "chunkText": "safe"}
    assert filter_chunk(safe, current) is not None
    assert filter_chunk({**safe, "activeMetadataSnapshotId": 89}, current) is None
    assert filter_chunk({**safe, "columns": ["orders.secret"]}, current) is None
    assert filter_chunk({**safe, "columns": []}, current) is None


def test_ast_checks_all_resources_and_expands_star():
    current = snapshot()
    result = validate_sql("SELECT * FROM orders", current)
    assert result.passed
    assert "*" not in result.sql
    assert result.used_columns == ["orders.id", "orders.region"]
    assert not validate_sql("SELECT 1", current).passed
    assert "LIMIT 10000" in validate_sql("SELECT id FROM orders", current).sql.upper()
    assert not validate_sql("SELECT id FROM orders LIMIT 10001", current).passed
    assert not validate_sql("SELECT SLEEP(5) FROM orders", current).passed
    assert not validate_sql("SELECT id FROM customers", current).passed
    assert not validate_sql("SELECT id FROM orders WHERE secret = 1", current).passed


def test_masked_field_is_only_allowed_as_direct_projection():
    current = snapshot(masked=True)
    assert validate_sql("SELECT phone FROM orders", current).passed
    assert validate_sql("SELECT phone AS contact FROM orders", current).passed
    for sql in (
        "SELECT id FROM orders WHERE phone = 'x'",
        "SELECT phone FROM orders ORDER BY phone",
        "SELECT COUNT(phone) FROM orders",
        "SELECT CASE WHEN phone = 'x' THEN 1 END FROM orders",
    ):
        assert not validate_sql(sql, current).passed, sql


def test_ast_checks_join_group_order_having_and_nested_sources():
    current = snapshot(masked=True)
    assert validate_sql("SELECT o.id FROM orders o JOIN orders x ON o.id = x.id GROUP BY o.id ORDER BY o.id", current).passed
    assert not validate_sql("SELECT o.id FROM orders o JOIN customers c ON o.id = c.id", current).passed
    assert not validate_sql("SELECT id FROM orders WHERE phone = 'x' GROUP BY phone", current).passed
    assert not validate_sql("SELECT id FROM orders WHERE id IN (SELECT secret FROM orders)", current).passed
    assert not validate_sql("WITH q AS (SELECT id FROM orders) SELECT secret FROM q", current).passed


def test_firewall_applies_same_filter_to_rag_fallback_fewshot_and_history():
    current = snapshot()
    safe = {"datasourceId": 1, "activeMetadataSnapshotId": 88, "tables": ["orders"], "columns": ["orders.id"], "content": "safe"}
    unsafe = {"datasourceId": 1, "activeMetadataSnapshotId": 88, "tables": ["orders"], "columns": ["orders.secret"], "content": "unsafe"}
    context = build_model_context(current, [{"tableName": "orders", "columns": [{"name": "id"}]}], [safe, unsafe], [], [safe, unsafe], [{"role": "user", "content": "查询订单"}], {"sample_values": ["raw"]})
    assert context["rag"] == [safe]
    assert context["fewShot"] == [safe]
    assert context["conversationHistory"]
    assert "sample_values" not in context["conversationSummary"]


def test_row_condition_requires_binding_and_does_not_fallback_to_literal_sql():
    current = snapshot(condition=True)
    with pytest.raises(ValueError):
        inject_row_conditions("SELECT id FROM orders", current, [])


def test_structured_row_condition_is_ast_parameterized_and_value_never_enters_sql():
    current = snapshot(condition=True)
    current.resources[0].grantSources[0].rowCondition.predicates[0].bindingReference = "grant-9-condition-91"
    rewritten, params = inject_row_conditions(
        "SELECT id FROM orders",
        current,
        [S1ExecutionBinding(reference="grant-9-condition-91", valueType="STRING", value="华东")],
    )
    assert "华东" not in rewritten
    assert ":iam_s1_grant_9_condition_91" in rewritten
    assert params == {"iam_s1_grant_9_condition_91": "华东"}


def test_left_join_condition_is_attached_to_join_not_where():
    current = snapshot(condition=True)
    current.resources.append(S1Resource(
        tableName="users",
        columns=[S1Column(name="id", columnId=10, usage=["JOIN", "PROJECTION"], protectionLevel="NORMAL", maskPolicy=None, dataType="INT", governanceStatus="NORMAL", sourceSnapshotId=88), S1Column(name="region", columnId=11, usage=["FILTER"], protectionLevel="NORMAL", maskPolicy=None, dataType="VARCHAR", governanceStatus="NORMAL", sourceSnapshotId=88)],
        grantSources=[S1GrantSource(
            grantId=2, sourceSummary="S1", grantSource="MANUAL", sourceReferenceId=None,
            explicitColumns=["id", "region"],
            rowCondition=S1RowCondition(
                matchType="ALL",
                predicates=[S1Predicate(columnName="region", columnId=11, operatorCode="EQ", valueType="STRING", parameterReference=None, bindingReference="g2")],
            ),
        )],
        sourceSnapshotId=88,
    ))
    rewritten, _ = inject_row_conditions(
        "SELECT o.id, u.id FROM orders o LEFT JOIN users u ON o.id = u.id",
        current,
        [S1ExecutionBinding(reference="g1", valueType="STRING", value="华东"), S1ExecutionBinding(reference="g2", valueType="STRING", value="华东")],
    )
    assert "u.region" in rewritten.lower().split(" where ", 1)[0]
    assert "iam_s1_g1" in rewritten and "iam_s1_g2" in rewritten


def test_right_join_preserved_side_policy_is_in_where():
    current = snapshot(condition=True)
    current.resources.append(S1Resource(
        tableName="users",
        columns=[S1Column(name="id", columnId=10, usage=["JOIN", "PROJECTION"], protectionLevel="NORMAL", maskPolicy=None, dataType="INT", governanceStatus="NORMAL", sourceSnapshotId=88), S1Column(name="region", columnId=11, usage=["FILTER"], protectionLevel="NORMAL", maskPolicy=None, dataType="VARCHAR", governanceStatus="NORMAL", sourceSnapshotId=88)],
        grantSources=[S1GrantSource(
            grantId=2, sourceSummary="S1", grantSource="MANUAL", sourceReferenceId=None,
            explicitColumns=["id", "region"],
            rowCondition=S1RowCondition(
                matchType="ALL",
                predicates=[S1Predicate(columnName="region", columnId=11, operatorCode="EQ", valueType="STRING", parameterReference=None, bindingReference="g2")],
            ),
        )],
        sourceSnapshotId=88,
    ))
    rewritten, _ = inject_row_conditions(
        "SELECT o.id, u.id FROM orders o RIGHT JOIN users u ON o.id = u.id",
        current,
        [S1ExecutionBinding(reference="g1", valueType="STRING", value="华东"), S1ExecutionBinding(reference="g2", valueType="STRING", value="华东")],
    )
    assert "u.region" in rewritten.lower().split(" where ", 1)[1]


@pytest.mark.asyncio
async def test_execute_entry_rejects_sql_without_reinjected_row_policy():
    current = snapshot(condition=True)
    request = S1SqlExecuteRequest(
        protocolVersion="IAM-SIMPLE-1", taskId="task-1", userId=7, datasourceId=1,
        activeMetadataSnapshotId=88, permissionRevision=100, permissionSnapshot=current,
        executionBindings=[S1ExecutionBinding(reference="g1", valueType="STRING", value="华东")],
        originalSql="SELECT id FROM orders", validatedSql="SELECT id FROM orders LIMIT 10000",
        connectionConfig=S1ConnectionConfig(host="localhost", port=3306, database="db", username="u", password="p"),
    )
    with patch("dataocean.iam_s1.service.execute_sql", new_callable=AsyncMock) as execute:
        with pytest.raises(ValueError, match="不一致"):
            await execute_validated(request)
        execute.assert_not_awaited()


@pytest.mark.asyncio
async def test_s1_rag_calls_milvus_pipeline_then_filters_sources():
    current = snapshot()
    response = SimpleNamespace(results=[SimpleNamespace(
        related_tables=["orders"], related_columns=["orders.id"], chunk_text="safe", chunk_type="TABLE_DESC",
        score=0.9, doc_id=1, source_version=1,
    )])
    request = S1RagRetrieveRequest(
        protocolVersion="IAM-SIMPLE-1", taskId="task-1", userId=7, datasourceId=1,
        activeMetadataSnapshotId=88, permissionRevision=100, permissionSnapshot=current,
        question="查询订单", chunks=[],
    )
    with patch("dataocean.rag.service.retrieve_schemas", new_callable=AsyncMock, return_value=response) as retrieve_mock:
        result = await retrieve(request)
    retrieve_mock.assert_awaited_once()
    assert result[0]["columns"] == ["orders.id"]
