from __future__ import annotations

import json
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from dataocean.iam_s1 import graph as graph_module
from dataocean.iam_s1.sql_security import validate_sql


def test_failed_new_attempt_cannot_route_old_protected_result_to_verification():
    state = {
        "taskId": "task-attempt-binding",
        "attemptCount": 1,
        "currentAttemptId": "task-attempt-binding-2-bbbbbbbbbbbb",
        "currentSqlHash": "b" * 64,
        "protectedResult": {
            "status": "PROTECTED",
            "attemptId": "task-attempt-binding-1-aaaaaaaaaaaa",
            "sqlHash": "a" * 64,
            "data": [{"old": 1}],
        },
        "errorType": "REPAIRABLE_SQL",
        "error": "Unknown column: order_total",
    }

    assert graph_module._has_current_protected_result(state) is False
    assert graph_module._after_execution(state) == "generate_sql"


@pytest.mark.asyncio
async def test_result_verification_fails_closed_when_result_belongs_to_previous_attempt(monkeypatch):
    budget_call = AsyncMock(side_effect=AssertionError("stale data must not reach the verifier"))
    monkeypatch.setattr(graph_module, "_budgeted_llm", budget_call)
    state = {
        "taskId": "task-attempt-binding",
        "deadlineEpochSeconds": time.time() + 30,
        "attemptCount": 2,
        "currentAttemptId": "task-attempt-binding-2-bbbbbbbbbbbb",
        "currentSqlHash": "b" * 64,
        "protectedResult": {
            "status": "PROTECTED",
            "attemptId": "task-attempt-binding-1-aaaaaaaaaaaa",
            "sqlHash": "a" * 64,
            "data": [{"old": 1}],
        },
    }

    result = await graph_module._verify_result_node(state)

    assert result["status"] == "FAILED"
    assert "没有匹配的 Java 保护结果" in result["error"]
    budget_call.assert_not_awaited()


@pytest.mark.asyncio
async def test_starting_repair_attempt_clears_previous_protected_result(monkeypatch):
    observed = {}

    async def budgeted(state, *_args, **_kwargs):
        observed["protectedResult"] = state["protectedResult"]
        observed["recoveredProtectedResult"] = state["recoveredProtectedResult"]
        return "SELECT id FROM orders", dict(state)

    monkeypatch.setattr(graph_module, "_progress", AsyncMock())
    monkeypatch.setattr(graph_module, "_planning_snapshot", lambda *_args, **_kwargs: {})
    monkeypatch.setattr(graph_module, "_linked_rag", lambda *_args, **_kwargs: [])
    monkeypatch.setattr(graph_module, "build_model_context", lambda *_args, **_kwargs: {})
    monkeypatch.setattr(graph_module, "render_sql_prompt", AsyncMock(return_value="prompt"))
    monkeypatch.setattr(graph_module, "_budgeted_llm", budgeted)
    monkeypatch.setattr(graph_module, "validate_sql", lambda *_args, **_kwargs: SimpleNamespace(
        passed=True,
        source_trace=[],
        sql="SELECT id FROM orders",
        used_tables=["orders"],
        used_columns=["orders.id"],
        column_usages={"orders.id": ["PROJECTION"]},
        violations=[],
    ))
    monkeypatch.setattr(graph_module, "_unsupported_reviewed_output_aliases", lambda *_args, **_kwargs: [])
    state = {
        "taskId": "task-attempt-binding",
        "deadlineEpochSeconds": time.time() + 30,
        "attemptCount": 1,
        "candidateCatalog": {},
        "linkedResources": [],
        "linkedSchema": [],
        "safeRag": [],
        "glossaryTerms": [],
        "fewShotExamples": [],
        "conversationHistory": [],
        "conversationSummary": None,
        "question": "统计订单数",
        "questionIntent": {},
        "rewrittenQuestion": "",
        "ragBuildId": None,
        "ragSourceSnapshotId": None,
        "repairFeedback": "修正分组字段",
        "protectedResult": {"status": "PROTECTED", "attemptId": "old-attempt", "sqlHash": "a" * 64},
        "recoveredProtectedResult": False,
    }

    result = await graph_module._generate_sql_node(state)

    assert observed == {"protectedResult": {}, "recoveredProtectedResult": False}
    assert result["protectedResult"] == {}
    assert result["currentAttemptId"] != "old-attempt"
    assert result["currentSqlHash"] == graph_module.hashlib.sha256(result["currentSql"].encode()).hexdigest()


@pytest.mark.asyncio
async def test_schema_linking_receives_java_filtered_reviewed_glossary_terms(monkeypatch):
    glossary = [{
        "name": "completed_sales_revenue",
        "displayName": "已完成订单销售额",
        "description": "已审核：SUM(sales_orders.quantity * sales_orders.unit_price)，仅含 COMPLETED。",
        "synonyms": '["销售额","revenue"]',
        "columns": ["sales_orders.quantity", "sales_orders.unit_price", "sales_orders.status"],
    }]
    state = {
        "taskId": "task-reviewed-glossary",
        "userId": 7,
        "datasourceId": 1,
        "activeMetadataSnapshotId": 88,
        "permissionRevision": 9,
        "capabilities": {"query": True, "viewSql": False, "export": False},
        "deadlineEpochSeconds": time.time() + 30,
        "question": "按地区统计已完成订单的销售额。",
        "conversationHistory": [],
        "conversationSummary": None,
        "candidateCatalog": {
            "activeMetadataSnapshotId": 88,
            "tables": [{
                "tableName": "sales_orders",
                "tableComment": "",
                "governanceStatus": "RECOMMENDED",
                "columns": [
                    {"columnMetaId": index, "columnName": name,
                     "dataType": "VARCHAR" if name in {"region", "status"} else "DECIMAL",
                     "protectionLevel": "NORMAL", "maskPolicy": None, "governanceStatus": "NORMAL",
                     "allowedUsages": ["PROJECTION", "FILTER", "GROUP", "FUNCTION"]}
                    for index, name in enumerate(["region", "quantity", "unit_price", "status"], start=1)
                ],
            }, {
                "tableName": "products",
                "tableComment": "",
                "governanceStatus": "RECOMMENDED",
                "columns": [
                    {"columnMetaId": index, "columnName": name, "dataType": "VARCHAR",
                     "protectionLevel": "NORMAL", "maskPolicy": None, "governanceStatus": "NORMAL",
                     "allowedUsages": ["PROJECTION", "FILTER", "JOIN", "GROUP"]}
                    for index, name in enumerate(["product_id", "category"], start=5)
                ],
            }],
        },
        "safeRag": [{
            "factType": "JOIN_PATH",
            "tables": ["sales_orders", "products"],
            "resourceDependencies": [
                "table:sales_orders", "table:products",
                "column:sales_orders.product_id", "column:products.product_id",
            ],
        }],
        "glossaryTerms": glossary,
        "ragRefreshCount": 1,
        "attemptCount": 0,
        "status": "PROCESSING",
    }
    response = json.dumps({
        "rewrittenQuestion": state["question"],
        "intent": {"measure": "completed_sales_revenue", "groupBy": "region"},
        "selectedResources": [{
            "tableName": "sales_orders",
            "columns": ["region"],
        }],
        "clarificationNeeded": False,
        "clarification": "",
    }, ensure_ascii=False)
    budgeted = dict(state)
    budgeted["llmCalls"] = 1

    budget_call = AsyncMock(return_value=(response, budgeted))
    monkeypatch.setattr(graph_module, "_budgeted_llm", budget_call)

    result = await graph_module._plan_node(state)

    assert result["status"] == "PROCESSING"
    assert result["linkedResources"] == [{
        "tableName": "sales_orders",
        "columns": ["region", "quantity", "unit_price", "status"],
    }]
    planning_snapshot = graph_module._planning_snapshot(result, result["linkedResources"])
    assert validate_sql(
        "SELECT region AS sales_region, SUM(quantity * unit_price) AS revenue "
        "FROM sales_orders WHERE status = 'COMPLETED' GROUP BY region",
        planning_snapshot,
    ).passed
    prompt_payload = json.loads(budget_call.await_args.args[3])
    assert prompt_payload["reviewedGlossaryTerms"] == glossary
    schema_by_table = {item["tableName"]: item for item in prompt_payload["authorizedSchemaCandidates"]}
    assert {item["columnName"] for item in schema_by_table["sales_orders"]["columns"]} == {
        "region", "quantity", "unit_price", "status",
    }
    assert {item["columnName"] for item in schema_by_table["products"]["columns"]} == {
        "product_id", "category",
    }
    assert "按当前权限过滤的已审核事实" in budget_call.await_args.args[2]


def test_confirmed_join_dependencies_are_added_only_with_current_join_usage():
    selected = [
        {"tableName": "sales_orders", "columns": ["quantity", "unit_price", "status"]},
        {"tableName": "products", "columns": ["category"]},
    ]
    relation = [{
        "factType": "JOIN_PATH",
        "factReviewStatus": "APPROVED",
        "reviewStatus": "APPROVED",
        "resourceDependencies": [
            "table:sales_orders", "table:products",
            "column:sales_orders.product_id", "column:products.product_id",
        ],
    }]
    candidates = {
        "sales_orders": {"columns": [{"columnName": "product_id", "allowedUsages": ["JOIN"]}]},
        "products": {"columns": [{"columnName": "product_id", "allowedUsages": ["JOIN"]}]},
    }

    assert graph_module._add_confirmed_join_columns(selected, candidates, relation) is None
    assert selected[0]["columns"] == ["quantity", "unit_price", "status", "product_id"]
    assert selected[1]["columns"] == ["category", "product_id"]

    denied_candidates = {
        **candidates,
        "products": {"columns": [{"columnName": "product_id", "allowedUsages": ["PROJECTION"]}]},
    }
    denied = graph_module._add_confirmed_join_columns(selected[:1] + [{"tableName": "products", "columns": ["category"]}],
                                                      denied_candidates, relation)
    assert denied == "已确认关系需要的 Join 字段当前不允许 JOIN，请补充该字段的使用权限。"

    unreviewed = [{**relation[0], "factReviewStatus": "PENDING"}]
    no_join = [{"tableName": "sales_orders", "columns": ["quantity"]},
               {"tableName": "products", "columns": ["category"]}]
    assert graph_module._add_confirmed_join_columns(no_join, candidates, unreviewed) is None
    assert no_join[0]["columns"] == ["quantity"]


def test_planner_clarification_does_not_repeat_invented_field_examples():
    state = {
        "candidateCatalog": {"tables": [{
            "tableName": "sales_orders",
            "columns": [{"columnName": "status"}],
        }]},
        "glossaryTerms": [],
    }

    safe = graph_module._safe_planner_clarification(
        state,
        "当前模型没有明确campaign_id或ad_group字段，请确认 sales_orders 中的来源。",
    )

    assert "campaign_id" not in safe
    assert "ad_group" not in safe
    assert "足够依据" in safe
    bare_table_reference = graph_module._safe_planner_clarification(
        state,
        "请确认目标表是否为客户信息表（如 customers），并提供其表结构。",
    )
    assert "customers" not in bare_table_reference
    assert "customers" not in graph_module._safe_planner_clarification(
        state, "当前数据未包含客户字段；示例表为 `customers`，请提供结构。",
    )
    assert graph_module._after_generate({"status": "CLARIFICATION_REQUIRED"}) == "clarify"


@pytest.mark.asyncio
async def test_planner_clarifies_unknown_requested_dimension_before_sql_generation(monkeypatch):
    metric = {
        "name": "completed_sales_revenue",
        "displayName": "已完成订单销售额",
        "description": "SUM(sales_orders.quantity * sales_orders.unit_price)",
        "synonyms": '["销售额", "revenue"]',
        "columns": ["sales_orders.quantity", "sales_orders.unit_price"],
    }
    state = {
        "taskId": "task-missing-campaign-dimension",
        "deadlineEpochSeconds": time.time() + 30,
        "question": "哪个广告活动带来的销售额最高？",
        "candidateCatalog": {"tables": [{
            "tableName": "sales_orders",
            "tableComment": "",
            "columns": [
                {"columnName": "region", "columnComment": "地区"},
                {"columnName": "order_date", "columnComment": "订单日期"},
            ],
        }]},
        "glossaryTerms": [metric],
        "safeRag": [],
        "attemptCount": 0,
        "ragRefreshCount": 0,
    }
    monkeypatch.setattr(graph_module, "_progress", AsyncMock())
    monkeypatch.setattr(graph_module, "_link_candidates", lambda _: [{
        "tableName": "sales_orders", "columns": [{"columnName": "region"}],
    }])
    planner = AsyncMock()
    monkeypatch.setattr(graph_module, "_budgeted_llm", planner)

    result = await graph_module._plan_node(state)

    assert result["status"] == "CLARIFICATION_REQUIRED"
    assert "没有找到该问题所需的维度依据" in result["clarification"]
    planner.assert_not_awaited()


def test_requested_dimension_matches_current_visible_and_reviewed_labels():
    state = {
        "candidateCatalog": {"tables": [{
            "tableName": "sales_orders",
            "columns": [{"columnName": "region", "columnComment": "地区"}],
        }]},
        "glossaryTerms": [{
            "name": "completed_sales_revenue", "displayName": "已完成订单销售额",
            "description": "SUM(quantity * unit_price)", "synonyms": '["销售额"]',
        }],
    }

    assert graph_module._unmapped_requested_object(state, "哪个地区销售额最高？") is None


def test_sql_output_label_cannot_repurpose_an_unrelated_authorized_dimension():
    terms = [{
        "name": "sales_region",
        "displayName": "地区维度",
        "synonyms": '["地区"]',
        "description": "sales_orders.region 表示地区。",
        "columns": ["sales_orders.region"],
    }]

    assert graph_module._unsupported_reviewed_output_aliases(
        [{"outputColumn": "广告活动", "sources": ["sales_orders.region"]}], terms,
    ) == ["广告活动"]
    assert graph_module._unsupported_reviewed_output_aliases(
        [{"outputColumn": "地区", "sources": ["sales_orders.region"]}], terms,
    ) == []

    derived_terms = [{
        "name": "sales_order_month", "displayName": "月份", "synonyms": '["月份","每个月"]',
        "description": "月份 = DATE_FORMAT(sales_orders.order_date, '%Y-%m')。",
        "columns": ["sales_orders.order_date"],
    }]
    assert graph_module._unsupported_reviewed_output_aliases(
        [{"outputColumn": "月份", "sources": ["sales_orders.order_date"]}], derived_terms,
    ) == []

    count_term = [{
        "name": "completed_order_count", "displayName": "已完成订单总笔数",
        "synonyms": '["多少笔"]',
        "description": "COUNT(DISTINCT sales_orders.order_id) WHERE status = 'COMPLETED'.",
        "columns": ["sales_orders.order_id", "sales_orders.status"],
    }]
    assert graph_module._unsupported_reviewed_output_aliases(
        [{"outputColumn": "已完成订单总笔数", "sources": [], "sourceKind": "NO_COLUMN_SOURCE"}], count_term,
    ) == []


def test_only_full_group_by_execution_error_uses_bounded_retry():
    assert graph_module._is_repairable_execution_error(
        "(1140, \"In aggregated query without GROUP BY, expression contains nonaggregated column; sql_mode=only_full_group_by\")"
    )
    hint = graph_module._execution_repair_feedback(
        "(1140, \"SELECT private_value FROM customer WHERE name='test' only_full_group_by\")"
    )
    assert hint is not None
    assert "GROUP BY" in hint
    assert "private_value" not in hint
    assert "customer" not in hint
    assert "test" not in hint


@pytest.mark.asyncio
async def test_semantic_check_retries_sql_missing_a_reviewed_filter_before_java_authorization(monkeypatch):
    state = {
        "taskId": "task-required-filter",
        "userId": 7,
        "datasourceId": 1,
        "activeMetadataSnapshotId": 88,
        "permissionRevision": 9,
        "capabilities": {"query": True, "viewSql": False, "export": False},
        "deadlineEpochSeconds": time.time() + 30,
        "question": "已完成订单一共有多少笔？",
        "rewrittenQuestion": "已完成订单一共有多少笔？",
        "candidateCatalog": {
            "activeMetadataSnapshotId": 88,
            "tables": [{
                "tableName": "sales_orders",
                "columns": [{
                    "columnMetaId": 10, "columnName": "status", "dataType": "VARCHAR",
                    "protectionLevel": "NORMAL", "maskPolicy": None, "governanceStatus": "NORMAL",
                    "allowedUsages": ["PROJECTION", "FILTER"],
                }],
            }],
        },
        "linkedResources": [{"tableName": "sales_orders", "columns": ["status"]}],
        "glossaryTerms": [{
            "name": "sales_order_status_enum", "displayName": "销售订单状态值",
            "synonyms": '["已完成"]',
            "description": "sales_orders.status = 'COMPLETED'",
            "columns": ["sales_orders.status"],
        }],
        "questionIntent": {},
        "currentSql": "SELECT COUNT(*) AS order_count FROM sales_orders WHERE status = COMPLETED",
        "usedTables": ["sales_orders"],
        "usedColumns": [],
        "columnUsages": {},
        "sqlPrecheckError": "无法唯一追踪未限定字段: completed",
        "safeRag": [],
        "attemptCount": 1,
    }
    model = AsyncMock()
    monkeypatch.setattr(graph_module, "_budgeted_llm", model)

    result = await graph_module._semantic_check_node(state)

    assert result["semanticDecision"] == "RETRY"
    assert "sales_orders.status = 'COMPLETED'" in result["repairFeedback"]
    model.assert_not_awaited()


@pytest.mark.asyncio
async def test_planner_uses_narrow_reviewed_metric_schema_when_model_over_clarifies(monkeypatch):
    term = {
        "name": "completed_sales_revenue",
        "displayName": "已完成订单销售额",
        "description": "已审核公式 SUM(sales_orders.quantity * sales_orders.unit_price)，只筛选 sales_orders.status = 'COMPLETED'；日期用 order_date。",
        "synonyms": '["销售额"]',
        "columns": [
            "sales_orders.quantity", "sales_orders.unit_price", "sales_orders.status",
            "sales_orders.order_date", "sales_orders.region",
        ],
    }
    names = ["order_date", "region", "quantity", "unit_price", "status"]
    state = {
        "taskId": "task-reviewed-metric-fallback",
        "userId": 7,
        "datasourceId": 1,
        "activeMetadataSnapshotId": 88,
        "permissionRevision": 9,
        "capabilities": {"query": True, "viewSql": False, "export": False},
        "deadlineEpochSeconds": time.time() + 30,
        "question": "2026 年 2 月已完成订单销售额是多少？",
        "conversationHistory": [],
        "conversationSummary": None,
        "candidateCatalog": {
            "activeMetadataSnapshotId": 88,
            "tables": [{
                "tableName": "sales_orders", "tableComment": "", "governanceStatus": "RECOMMENDED",
                "columns": [{
                    "columnMetaId": index, "columnName": name, "dataType": "VARCHAR",
                    "protectionLevel": "NORMAL", "maskPolicy": None, "governanceStatus": "NORMAL",
                    "allowedUsages": ["PROJECTION", "FILTER", "GROUP", "FUNCTION"],
                } for index, name in enumerate(names, start=1)],
            }],
        },
        "safeRag": [],
        "glossaryTerms": [term],
        "ragRefreshCount": 1,
        "attemptCount": 0,
        "status": "PROCESSING",
    }
    clarification = json.dumps({
        "rewrittenQuestion": state["question"], "intent": {}, "selectedResources": [],
        "clarificationNeeded": True, "clarification": "请补充指标和日期口径。",
    }, ensure_ascii=False)
    budgeted = dict(state)
    budgeted["llmCalls"] = 1
    monkeypatch.setattr(graph_module, "_budgeted_llm", AsyncMock(return_value=(clarification, budgeted)))

    result = await graph_module._plan_node(state)

    assert result["status"] == "PROCESSING"
    assert result["linkedResources"] == [{
        "tableName": "sales_orders",
        "columns": [fqn.rsplit(".", 1)[1] for fqn in term["columns"]],
    }]
