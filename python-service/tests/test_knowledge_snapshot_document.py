from __future__ import annotations

import pytest

from dataocean.knowledge.schema import GenerateDraftRequest
from dataocean.knowledge.service import generate_draft


def _request() -> GenerateDraftRequest:
    return GenerateDraftRequest(
        snapshot_id=8801,
        datasource_id=701,
        tables_metadata=[
            {
                "table_id": 10,
                "source_snapshot_id": 8801,
                "table_name": "sales_orders",
                "table_type": "TABLE",
                "table_comment": "订单事实表",
                "governance_status": "NORMAL",
                "columns": [
                    {
                        "column_id": 101,
                        "source_snapshot_id": 8801,
                        "column_name": "order_id",
                        "column_type": "BIGINT",
                        "is_primary_key": True,
                        "ordinal_position": 1,
                        "governance_status": "NORMAL",
                    },
                    {
                        "column_id": 102,
                        "source_snapshot_id": 8801,
                        "column_name": "product_id",
                        "column_type": "INT",
                        "column_comment": "商品标识",
                        "ordinal_position": 2,
                        "governance_status": "NORMAL",
                    },
                    {
                        "column_id": 103,
                        "source_snapshot_id": 8801,
                        "column_name": "secret_note",
                        "column_type": "VARCHAR(80)",
                        "governance_status": "BLOCKED",
                    },
                ],
            },
            {
                "table_id": 20,
                "source_snapshot_id": 8801,
                "table_name": "products",
                "table_type": "TABLE",
                "governance_status": "NORMAL",
                "columns": [
                    {
                        "column_id": 201,
                        "source_snapshot_id": 8801,
                        "column_name": "product_id",
                        "column_type": "INT",
                        "governance_status": "NORMAL",
                    },
                    {
                        "column_id": 202,
                        "source_snapshot_id": 8801,
                        "column_name": "category",
                        "column_type": "VARCHAR(40)",
                        "governance_status": "NORMAL",
                    },
                ],
            },
        ],
        foreign_keys=[
            {
                "relation_id": 301,
                "snapshot_id": 8801,
                "source_table": "sales_orders",
                "source_column": "product_id",
                "target_table": "products",
                "target_column": "product_id",
                "relation_type": "FK",
                "review_status": "CONFIRMED",
                "confidence": 1,
            },
            {
                "relation_id": 302,
                "snapshot_id": 8801,
                "source_table": "sales_orders",
                "source_column": "order_id",
                "target_table": "products",
                "target_column": "product_id",
                "relation_type": "INFERRED",
                "review_status": "PENDING",
                "confidence": 0.4,
            },
        ],
        lineage_facts=[
            {
                "relationship_id": 401,
                "source_fqn": "sales.sales_orders",
                "target_fqn": "sales.products",
                "source_snapshot_id": 8800,
                "bound_snapshot_id": 8801,
                "binding_status": "BOUND",
                "confirmation_status": "CONFIRMED",
                "relation_type": "LINEAGE",
                "description": "ETL 输出",
                "dependencies": ["table:sales_orders", "table:products"],
            }
        ],
    )


def _field_fact():
    return {
        "sourceIds": [71, 72, 73, 74],
        "dependencies": ["table:sales_orders", "column:sales_orders.order_id"],
        "reviewStatus": "APPROVED",
        "governanceStatus": "NORMAL",
        "contentHash": "a" * 64,
        "dependencyHash": "b" * 64,
        "businessName": "订单编号",
        "body": "- 业务名称: 订单编号\n- 字段含义: 订单的稳定标识\n- 业务依据: 订单主数据定义",
    }


@pytest.mark.asyncio
async def test_snapshot_document_covers_every_table_and_field_with_provenance():
    result = await generate_draft(_request())

    assert result.coverage["tableIds"] == [10, 20]
    assert result.coverage["columnIds"] == [101, 102, 103, 201, 202]
    assert result.coverage["tableCoverage"] == 1.0
    assert result.coverage["columnCoverage"] == 1.0
    assert result.coverage["snapshotId"] == 8801
    assert result.generation_source == "SNAPSHOT_GENERATED"
    assert "#### `secret_note`" in result.content
    assert "`BLOCKED`" in result.content
    assert "Business meaning: 待确认" in result.content
    assert result.content.count('"factType":"COLUMN_STRUCTURE"') == 4
    assert result.content.count('"factType":"COLUMN_EXCLUDED"') == 1
    assert "sales_orders.product_id = products.product_id" in result.content
    confirmed_joins = result.content.split("## 3. Confirmed Join Paths", 1)[1].split(
        "## 4. Unconfirmed relationship candidates", 1
    )[0]
    assert "sales_orders.order_id" not in confirmed_joins
    assert '"factType":"JOIN_CANDIDATE"' in result.content
    assert "## 5. Data lineage and field derivations" in result.content
    assert "Lineage describes data flow" in result.content
    assert result.coverage["confirmedLineageCount"] == 1
    assert result.coverage["pendingRelationshipCount"] == 1
    assert any("secret_note" in warning for warning in result.warnings)


@pytest.mark.asyncio
async def test_generation_rejects_mixed_snapshot_or_invented_relationship_resources():
    request = _request()
    request.tables_metadata[0].columns[0].source_snapshot_id = 8800
    with pytest.raises(ValueError, match="snapshotId"):
        await generate_draft(request)

    request = _request()
    request.foreign_keys[0]["target_column"] = "nonexistent"
    with pytest.raises(ValueError, match="不存在"):
        await generate_draft(request)


@pytest.mark.asyncio
async def test_generation_rejects_lineage_not_rebound_to_the_requested_snapshot():
    request = _request()
    request.lineage_facts[0]["bound_snapshot_id"] = 8800
    with pytest.raises(ValueError, match="未绑定"):
        await generate_draft(request)


@pytest.mark.asyncio
async def test_out_of_snapshot_lineage_is_not_generation_evidence():
    request = _request()
    request.lineage_facts[0]["target_fqn"] = "sales.monthly_sales"
    request.lineage_facts[0]["dependencies"] = ["table:sales_orders", "table:monthly_sales"]
    result = await generate_draft(request)
    assert result.coverage["confirmedLineageCount"] == 0
    assert "monthly_sales" not in result.content
    assert any("血缘" in warning for warning in result.warnings)


@pytest.mark.asyncio
async def test_field_semantic_facts_are_rendered_with_exact_source_markers():
    request = _request()
    request.field_semantic_facts = [_field_fact()]
    result = await generate_draft(request)
    assert '"factType":"FIELD_SEMANTIC"' in result.content
    assert '"sourceIds":[71,72,73,74]' in result.content
    assert "- 业务名称: 订单编号" in result.content


@pytest.mark.asyncio
async def test_field_semantic_facts_outside_eligible_snapshot_are_rejected():
    request = _request()
    fact = _field_fact()
    fact["dependencies"] = ["table:sales_orders", "column:sales_orders.secret_note"]
    request.field_semantic_facts = [fact]
    with pytest.raises(ValueError, match="字段语义事实"):
        await generate_draft(request)


def test_field_semantic_marker_is_preserved_as_a_retrievable_field_fact():
    import json
    from dataocean.rag.chunker import chunk_skills_md

    marker = {
        "snapshotId": 88,
        "factType": "FIELD_SEMANTIC",
        "sourceIds": [71, 72, 73, 74],
        "dependencies": ["table:orders", "column:orders.pay_amt"],
        "reviewStatus": "APPROVED",
        "governanceStatus": "NORMAL",
        "contentHash": "a" * 64,
        "dependencyHash": "b" * 64,
    }
    content = (
        "# Knowledge\n\n## 11. Field semantics\n\n### Order payment amount\n"
        "<!-- dataocean-fact: " + json.dumps(marker, separators=(",", ":")) + " -->\n"
        "- Business name: Order payment amount\n"
        "- Meaning: The actual amount paid for this order, based on the reviewed business definition.\n"
        "- Evidence: Reviewed order accounting policy, revision 4."
    )
    chunks = chunk_skills_md(content, expected_snapshot_id=88)
    assert len(chunks) == 1
    assert chunks[0].fact_type == "FIELD_SEMANTIC"
    assert chunks[0].fact_source_ids == ["71", "72", "73", "74"]
    assert chunks[0].resource_dependencies == marker["dependencies"]
