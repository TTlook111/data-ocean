from __future__ import annotations

import pytest

from dataocean.knowledge.schema import GenerateDraftRequest
from dataocean.knowledge.service import generate_draft


@pytest.mark.asyncio
async def test_usage_confidence_does_not_change_document_or_coverage():
    request = _request()
    before = await generate_draft(request)
    for table in request.tables_metadata:
        for column in table.columns:
            column.confidence_score = 17
    after = await generate_draft(request)
    assert after.content == before.content
    assert after.coverage == before.coverage
    assert after.warnings == before.warnings


def test_retired_domain_analysis_route_is_not_exposed():
    from dataocean.knowledge.router import router

    assert not any(route.path == '/analyze-and-generate' for route in router.routes)


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


@pytest.mark.asyncio
async def test_reviewed_business_model_is_preserved_with_complete_dependencies():
    request = _request()
    request = request.model_copy(update={'business_knowledge_facts': [{
        'sourceIds': [991], 'factType': 'BUSINESS_RELATION', 'title': '订单商品关系',
        'body': '- description: 订单关联商品\n- cardinality: N:1\n- aggregationNotes: 按订单粒度统计',
        'contentHash': 'c'*64, 'dependencyHash': 'd'*64,
        'dependencies': ['table:sales_orders', 'column:sales_orders.product_id', 'table:products', 'column:products.product_id'],
        'reviewStatus': 'APPROVED', 'governanceStatus': 'NORMAL',
    }]})
    result = await generate_draft(request)
    assert '订单商品关系' in result.content
    assert 'BUSINESS_RELATION' in result.content
    from dataocean.rag.chunker import chunk_skills_md
    chunks = chunk_skills_md(result.content, expected_snapshot_id=request.snapshot_id)
    business = [c for c in chunks if c.fact_type == 'BUSINESS_RELATION']
    assert len(business) == 1
    assert business[0].fact_source_ids == ['991']
    assert set(business[0].resource_dependencies) == set(request.business_knowledge_facts[0]['dependencies'])


@pytest.mark.asyncio
async def test_physical_foreign_key_is_not_automatic_business_join_approval():
    request = _request()
    result = await generate_draft(request)
    import json,re
    markers = [json.loads(raw) for raw in re.findall(r'<!-- dataocean-fact: (.*?) -->', result.content)]
    relation = next(marker for marker in markers if marker['sourceIds']==[301])
    assert relation['factType'] == 'JOIN_CANDIDATE'
    assert relation['reviewStatus'] == 'PENDING'
    assert set(relation['dependencies']) == {'table:sales_orders','table:products','column:sales_orders.product_id','column:products.product_id'}


@pytest.mark.asyncio
async def test_snapshot_generation_never_sends_restricted_metadata_to_model(monkeypatch):
    from dataocean.knowledge import service
    async def forbidden_model(**kwargs):raise AssertionError('完整结构目录不能传给模型，受限字段只作管理覆盖')
    monkeypatch.setattr(service,'call_llm',forbidden_model, raising=False)
    result = await service.generate_draft(_request())
    assert 'COLUMN_EXCLUDED' in result.content


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
    assert result.coverage["pendingRelationshipCount"] == 2
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
