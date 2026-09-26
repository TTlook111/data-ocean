r"""Run the fixed G0 question set through the pre-LangGraph IAM-SIMPLE-1 path.

Run from python-service after starting the labeled test-only MySQL fixture:
  $env:LANGGRAPH_ACCEPTANCE_MYSQL_PASSWORD = 'langgraph-fixture-reader-20260926'
  $env:LANGGRAPH_ACCEPTANCE_LLM_ENABLED = '1'
  ..\.venv313\Scripts\python.exe ..\scripts\langgraph-acceptance\baseline.py

This script rejects non-loopback/non-fixture datasource settings and never reads
or writes the repository's shared DataOcean database or Milvus collections.
It uses the reviewed fixture chunks through the current S1 fallback path.
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import time
import uuid
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
PYTHON_SERVICE = REPO_ROOT / "python-service"
FIXTURES = Path(__file__).resolve().parent / "fixtures"
sys.path.insert(0, str(PYTHON_SERVICE))


def _fixture_connection() -> dict[str, Any]:
    host = os.getenv("LANGGRAPH_ACCEPTANCE_MYSQL_HOST", "127.0.0.1")
    port = int(os.getenv("LANGGRAPH_ACCEPTANCE_MYSQL_PORT", "13316"))
    database = os.getenv("LANGGRAPH_ACCEPTANCE_MYSQL_DATABASE", "langgraph_fixture")
    username = os.getenv("LANGGRAPH_ACCEPTANCE_MYSQL_USERNAME", "langgraph_fixture_reader")
    password = os.getenv("LANGGRAPH_ACCEPTANCE_MYSQL_PASSWORD", "")
    if host not in {"127.0.0.1", "localhost"} or port != 13316 or database != "langgraph_fixture":
        raise SystemExit("Refusing to run outside the loopback langgraph_fixture datasource.")
    if username != "langgraph_fixture_reader" or not password:
        raise SystemExit("The test-only read-only fixture credentials are missing.")
    return {
        "host": host,
        "port": port,
        "database": database,
        "username": username,
        "password": password,
    }


def _snapshot(task_id: str):
    from dataocean.iam_s1.schema import (
        S1Capabilities,
        S1Column,
        S1GrantSource,
        S1PermissionSnapshot,
        S1Resource,
    )

    authorization = json.loads((FIXTURES / "g0_authorization.json").read_text(encoding="utf-8"))
    resources = []
    next_column_id = 1
    for grant in authorization["grants"]:
        columns = []
        for name, data_type in _columns_for(grant["table"]):
            columns.append(S1Column(
                name=name,
                columnId=next_column_id,
                usage=grant["usage"],
                protectionLevel="NORMAL",
                maskPolicy=None,
                dataType=data_type,
                governanceStatus="NORMAL",
                sourceSnapshotId=authorization["activeMetadataSnapshotId"],
            ))
            next_column_id += 1
        resources.append(S1Resource(
            tableName=grant["table"],
            columns=columns,
            grantSources=[S1GrantSource(
                grantId=grant["grantId"],
                sourceSummary="Dedicated G0 fixture grant",
                grantSource="MANUAL",
                sourceReferenceId=None,
                explicitColumns=grant["columns"],
                rowCondition=None,
            )],
            sourceSnapshotId=authorization["activeMetadataSnapshotId"],
        ))
    return S1PermissionSnapshot(
        protocolVersion="IAM-SIMPLE-1",
        taskId=task_id,
        userId=authorization["userId"],
        datasourceId=authorization["datasourceId"],
        activeMetadataSnapshotId=authorization["activeMetadataSnapshotId"],
        permissionRevision=authorization["permissionRevision"],
        calculatedAt="2026-09-26T09:00:00+08:00",
        nextEffectiveAt=None,
        resources=resources,
        capabilities=S1Capabilities(query=True, viewSql=False, export=False),
    )


def _columns_for(table: str) -> list[tuple[str, str]]:
    if table == "sales_orders":
        return [
            ("order_id", "INT"), ("order_date", "DATE"), ("region", "VARCHAR"),
            ("product_id", "INT"), ("quantity", "INT"),
            ("unit_price", "DECIMAL"), ("status", "VARCHAR"),
        ]
    if table == "products":
        return [("product_id", "INT"), ("product_name", "VARCHAR"), ("category", "VARCHAR")]
    raise ValueError(f"Unexpected fixture grant table: {table}")


def _chunks() -> list[dict[str, Any]]:
    return [
        {
            "datasourceId": 701,
            "activeMetadataSnapshotId": 8801,
            "sourceSnapshotId": 8801,
            "ragBuildId": "g0-fixture-build",
            "sourceId": 1001,
            "tables": ["sales_orders"],
            "columns": [
                "sales_orders.order_date", "sales_orders.region", "sales_orders.product_id",
                "sales_orders.quantity", "sales_orders.unit_price", "sales_orders.status",
            ],
            "resourceDependencies": [
                "table:sales_orders", "column:sales_orders.order_date", "column:sales_orders.region",
                "column:sales_orders.product_id", "column:sales_orders.quantity",
                "column:sales_orders.unit_price", "column:sales_orders.status",
            ],
            "factSourceIds": ["g0:metric:revenue", "g0:metric:completed-orders"],
            "factType": "METRIC",
            "factReviewStatus": "APPROVED",
            "reviewStatus": "APPROVED",
            "governanceStatus": "NORMAL",
            "chunkText": (
                "APPROVED 事实：已完成订单为 status='COMPLETED'；" 
                "销售额定义为 SUM(quantity * unit_price)；" 
                "时间范围使用左闭右开区间。来源：G0 fixture review 2026-09-26。"
            ),
            "chunkType": "METRIC",
            "reviewStatus": "APPROVED",
            "docId": 8801,
            "versionNo": 1,
        },
        {
            "datasourceId": 701,
            "activeMetadataSnapshotId": 8801,
            "sourceSnapshotId": 8801,
            "ragBuildId": "g0-fixture-build",
            "sourceId": 1002,
            "tables": ["sales_orders", "products"],
            "columns": ["sales_orders.product_id", "products.product_id", "products.category"],
            "resourceDependencies": [
                "table:sales_orders", "table:products", "column:sales_orders.product_id",
                "column:products.product_id", "column:products.category",
            ],
            "factSourceIds": ["g0:fk:product-id"],
            "factType": "JOIN_PATH",
            "factReviewStatus": "APPROVED",
            "reviewStatus": "APPROVED",
            "governanceStatus": "NORMAL",
            "chunkText": (
                "APPROVED Join Path：sales_orders.product_id = products.product_id。"
                "这是 snapshot 8801 中确认的外键关系。"
            ),
            "chunkType": "JOIN_PATH",
            "reviewStatus": "APPROVED",
            "docId": 8801,
            "versionNo": 1,
        },
    ]


class _RecordingChat:
    def __init__(self, delegate, ledger: dict[str, Any]):
        self._delegate = delegate
        self._ledger = ledger

    async def ainvoke(self, messages, **kwargs):
        self._ledger["calls"] += 1
        response = await self._delegate.ainvoke(messages, **kwargs)
        usage = getattr(response, "usage_metadata", None) or {}
        response_metadata = getattr(response, "response_metadata", None) or {}
        usage = usage or response_metadata.get("token_usage", {})
        input_tokens = int(usage.get("input_tokens", usage.get("prompt_tokens", 0)) or 0)
        output_tokens = int(usage.get("output_tokens", usage.get("completion_tokens", 0)) or 0)
        if not input_tokens or not output_tokens:
            import tiktoken

            encoding = tiktoken.get_encoding("cl100k_base")
            if not input_tokens:
                input_text = "\n".join(str(getattr(message, "content", "")) for message in messages)
                input_tokens = len(encoding.encode(input_text))
            if not output_tokens:
                output_tokens = len(encoding.encode(str(getattr(response, "content", ""))))
            self._ledger["usageEstimated"] = True
        self._ledger["inputTokens"] += input_tokens
        self._ledger["outputTokens"] += output_tokens
        return response


async def main() -> None:
    if os.getenv("LANGGRAPH_ACCEPTANCE_LLM_ENABLED") != "1":
        raise SystemExit("Set LANGGRAPH_ACCEPTANCE_LLM_ENABLED=1 to authorize the small synthetic G0 model run.")
    from dataocean.iam_s1.schema import S1QueryExecuteRequest, S1ConnectionConfig
    from dataocean.iam_s1 import service as s1
    from dataocean.infra import llm
    from dataocean.core.config import get_settings
    from dataocean.rag import service as rag_service

    connection = _fixture_connection()
    questions = json.loads((FIXTURES / "g0_questions.json").read_text(encoding="utf-8"))
    ledger = {"calls": 0, "inputTokens": 0, "outputTokens": 0}
    original_get_model = llm.get_chat_model

    def test_model(*, model=None, temperature=None, max_retries=None):
        delegate = original_get_model(model=model, temperature=temperature, max_retries=0)
        return _RecordingChat(delegate, ledger)

    # The baseline must not touch the shared Milvus service. Return no vector hits;
    # the current S1 fallback path receives only the two reviewed fixture chunks.
    original_retrieve_schemas = rag_service.retrieve_schemas

    async def no_shared_milvus(request):
        return type("EmptyRetrieval", (), {"results": []})()

    llm.get_chat_model = test_model
    rag_service.retrieve_schemas = no_shared_milvus
    results = []
    try:
        for case in questions:
            started = time.perf_counter()
            task_id = f"g0-{case['id']}-{uuid.uuid4().hex[:8]}"
            snapshot = _snapshot(task_id)
            request = S1QueryExecuteRequest(
                protocolVersion="IAM-SIMPLE-1",
                taskId=task_id,
                userId=9001,
                datasourceId=701,
                activeMetadataSnapshotId=8801,
                permissionRevision=1,
                permissionSnapshot=snapshot,
                executionBindings=[],
                question=case["question"],
                connectionConfig=S1ConnectionConfig(**connection),
                conversationHistory=[],
                conversationSummary=None,
                ragChunks=_chunks(),
                fallbackChunks=[],
                glossaryTerms=[],
                fewShotExamples=[],
                ragBuildId="g0-fixture-build",
                ragSourceSnapshotId=8801,
                ragCollectionName="g0-fixture-collection",
                ragEmbeddingConfig={"providerId": "test", "model": "qwen-flash", "dimension": 1024},
            )
            answer = await s1.run_query(request)
            elapsed_ms = round((time.perf_counter() - started) * 1000)
            sql = answer.get("sql") or ""
            denied_refs = [
                item for item in case.get("mustNotReference", [])
                if item.lower() in sql.lower()
            ]
            passed = False
            if case["answerable"] and answer.get("status") == "COMPLETED":
                passed = _rows_match(answer.get("data") or [], case.get("expectedRows") or [])
            elif not case["answerable"]:
                passed = answer.get("status") != "COMPLETED" and not denied_refs
            results.append({
                "id": case["id"],
                "answerable": case["answerable"],
                "passed": passed,
                "status": answer.get("status"),
                "elapsedMs": elapsed_ms,
                "rowCount": answer.get("rowCount", 0),
                "usedTables": answer.get("usedTables", []),
                "usedColumns": answer.get("usedColumns", []),
                "sql": sql,
                "error": answer.get("error"),
                "forbiddenReference": denied_refs,
            })
    finally:
        llm.get_chat_model = original_get_model
        rag_service.retrieve_schemas = original_retrieve_schemas

    settings = get_settings()
    summary = {
        "baseline": "IAM-SIMPLE-1 before LangGraph loop",
        "fixture": "langgraph_fixture / datasource 701 / snapshot 8801",
        "providerModel": settings.qwen_model,
        "llmCalls": ledger["calls"],
        "inputTokens": ledger["inputTokens"],
        "outputTokens": ledger["outputTokens"],
        "usageEstimated": ledger.get("usageEstimated", False),
        "estimatedCostCny": round(
            ledger["inputTokens"] * 0.15 / 1_000_000
            + ledger["outputTokens"] * 1.5 / 1_000_000,
            6,
        ) if settings.qwen_model == "qwen-flash" else None,
        "pricingNote": (
            "Estimated at CNY 0.15/M input and 1.50/M output for qwen-flash, "
            "mainland endpoint, checked 2026-09-26; provider billing is authoritative."
        ) if settings.qwen_model == "qwen-flash" else "No price table configured for this model.",
        "successfulAnswerable": sum(1 for row in results if row["answerable"] and row["passed"]),
        "answerableCount": sum(1 for row in results if row["answerable"]),
        "correctRefusals": sum(1 for row in results if not row["answerable"] and row["passed"]),
        "refusalCount": sum(1 for row in results if not row["answerable"]),
        "securityViolations": sum(1 for row in results if row["forbiddenReference"]),
        "p50ElapsedMs": _percentile([row["elapsedMs"] for row in results], 0.50),
        "p95ElapsedMs": _percentile([row["elapsedMs"] for row in results], 0.95),
        "questions": results,
    }
    out = Path(os.getenv("LANGGRAPH_ACCEPTANCE_OUTPUT", str(REPO_ROOT / "output" / "langgraph-g0-baseline.json")))
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in summary.items() if k != "questions"}, ensure_ascii=False, indent=2))
    print(f"Details: {out}")


def _rows_match(actual: list[dict[str, Any]], expected: list[dict[str, Any]]) -> bool:
    from decimal import Decimal, InvalidOperation

    if len(actual) != len(expected):
        return False

    def norm(value: Any) -> tuple[str, str]:
        if isinstance(value, (int, float, Decimal)):
            try:
                return ("number", str(Decimal(str(value)).normalize()))
            except InvalidOperation:
                pass
        if isinstance(value, str):
            try:
                return ("number", str(Decimal(value).normalize()))
            except InvalidOperation:
                pass
        return ("text", str(value))

    unmatched = [sorted(norm(value) for value in row.values()) for row in actual]
    for expected_row in expected:
        expected_values = sorted(norm(value) for value in expected_row.values())
        try:
            unmatched.remove(expected_values)
        except ValueError:
            return False
    return not unmatched


def _percentile(values: list[int], quantile: float) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int((len(ordered) - 1) * quantile)))
    return ordered[index]


if __name__ == "__main__":
    asyncio.run(main())
