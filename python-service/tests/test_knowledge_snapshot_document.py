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
                "target_fqn": "sales.monthly_sales",
                "source_snapshot_id": 8800,
                "bound_snapshot_id": 8801,
                "binding_status": "BOUND",
                "confirmation_status": "CONFIRMED",
                "relation_type": "LINEAGE",
                "description": "ETL 输出",
                "dependencies": ["table:sales_orders", "table:monthly_sales"],
            }
        ],
    )


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
    assert result.content.count('"factType":"COLUMN_STRUCTURE"') == 5
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
