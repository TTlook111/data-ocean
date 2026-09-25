"""B3 IAM-SIMPLE-1 合同、Firewall、AST 和参数化记录条件测试。"""

from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from pydantic import ValidationError

from dataocean.iam_s1.firewall import build_model_context, filter_chunk, filter_glossary, filter_schema
from dataocean.iam_s1.schema import (
    S1Capabilities,
    S1Column,
    S1ConnectionConfig,
    S1ExecutionBinding,
    S1GrantSource,
    S1PermissionSnapshot,
    S1Predicate,
    S1Resource,
    S1QueryExecuteRequest,
    S1RagRetrieveRequest,
    S1RowCondition,
    S1SqlExecuteRequest,
    S1SqlValidateRequest,
)
from dataocean.iam_s1.sql_security import inject_row_conditions, validate_sql
from dataocean.iam_s1.service import execute_validated, retrieve, run_query, validate_request


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
    # region 只声明了 FILTER，星号展开不得把它带进结果集。
    assert result.used_columns == ["orders.id"]
    assert "region" not in result.sql.lower()
    assert not validate_sql("SELECT 1", current).passed
    assert "LIMIT 10000" in validate_sql("SELECT id FROM orders", current).sql.upper()
    assert not validate_sql("SELECT id FROM orders LIMIT 10001", current).passed
    assert not validate_sql("SELECT SLEEP(5) FROM orders", current).passed
    assert not validate_sql("SELECT id FROM customers", current).passed
    assert not validate_sql("SELECT id FROM orders WHERE secret = 1", current).passed


def test_star_expansion_requires_at_least_one_projectable_field():
    current = snapshot()
    for column in current.resources[0].columns:
        column.usage = ["FILTER"]
    assert not validate_sql("SELECT * FROM orders", current).passed
    assert not validate_sql("SELECT orders.* FROM orders", current).passed


def test_aggregate_without_column_source_is_aliased_and_marked_no_column_source():
    """COUNT(*) 这类不携带列数据的输出必须被显式标记，且列名由本服务决定。

    背景（B6 后的真实缺陷）：数据库会把未加别名的表达式原文当作列名（`COUNT(*)`），
    而 sqlglot 的 alias_or_name 返回 `*`；两者不一致时 Java 的来源完整性检查会拒绝
    整个结果（"结果来源不完整，请重新查询"）。同时它没有列来源，Java 把空来源一律
    视为来源缺失，也需要一个显式标记才放行。
    """
    current = snapshot()
    result = validate_sql("SELECT COUNT(*) FROM orders", current)

    assert result.passed
    # 列名由本服务决定，不再依赖数据库对未加别名表达式的命名
    assert "AS s1_c1" in result.sql
    assert result.source_trace == [{
        "outputColumn": "s1_c1",
        "sources": [],
        "expression": "COUNT(*) AS s1_c1",
        "sourceKind": "NO_COLUMN_SOURCE",
    }]


def test_only_bare_columns_and_existing_aliases_are_left_untouched():
    """只给「无别名且不是裸列」的投影补别名；裸列与已有别名保持原样。

    裸列的名字本来就和结果列名一致；已有别名是调用方（或模型）的显式选择，
    改写它会让返回给用户的列名变样。
    """
    current = snapshot()
    result = validate_sql("SELECT id, COUNT(*) AS cnt FROM orders GROUP BY id", current)

    assert result.passed
    assert "id" in result.sql and "AS cnt" in result.sql
    assert "s1_c1" not in result.sql
    assert [entry["outputColumn"] for entry in result.source_trace] == ["id", "cnt"]
    # 有列来源的输出不得带 NO_COLUMN_SOURCE 标记
    assert all("sourceKind" not in entry for entry in result.source_trace if entry["sources"])


@pytest.mark.asyncio
async def test_sql_prompt_prefers_managed_template(monkeypatch):
    """S1 的 SQL 生成提示词必须优先走 Java 受管模板。

    这是"管理员能在 Prompt 策略页调整 SQL 生成行为"的前提。此前 S1 把提示词写死在
    Python 代码里，页面上改了没有任何效果。
    """
    from dataocean.iam_s1 import service as s1_service

    async def fake_managed(code, variables):
        assert code == "sql_generation"
        return "受管模板内容 " + variables["question"], 3

    monkeypatch.setattr(s1_service, "render_prompt_with_metadata", fake_managed)
    prompt = await s1_service.render_sql_prompt("有多少订单", {"schema": [], "rag": [], "glossary": [], "fewShot": [], "conversationHistory": [], "conversationSummary": {}})

    assert prompt.startswith("受管模板内容")
    assert "有多少订单" in prompt


@pytest.mark.asyncio
async def test_sql_prompt_falls_back_to_local_template(monkeypatch):
    """Java 不可用或模板缺失时退回本地模板，且问题与数据必须渲染进去。

    降级不能降成空提示词——那会让模型在没有 schema 的情况下凭空生成 SQL。
    """
    from dataocean.iam_s1 import service as s1_service

    async def broken_managed(code, variables):
        raise RuntimeError("Java 不可达")

    monkeypatch.setattr(s1_service, "render_prompt_with_metadata", broken_managed)
    schema = [{"tableName": "orders", "columns": [{"name": "id"}]}]
    prompt = await s1_service.render_sql_prompt("有多少订单", {"schema": schema, "rag": [], "glossary": [], "fewShot": [], "conversationHistory": [], "conversationSummary": {}})

    assert "有多少订单" in prompt
    assert "orders" in prompt
    assert "{{" not in prompt, "本地模板不得残留未渲染的占位符"
    # 聚合别名要求是修复"结果列名显示成 s1_c1"的根因手段，不能在降级模板里丢掉
    assert "AS" in prompt and "别名" in prompt


def test_field_usage_is_enforced_for_projection_filter_and_join():
    current = snapshot()
    # id: PROJECTION only, region: FILTER only
    assert validate_sql("SELECT id FROM orders", current).passed
    assert validate_sql("SELECT id FROM orders WHERE region = 'x'", current).passed
    # FILTER-only 字段不得被投影（否则字段值被直接暴露）
    assert not validate_sql("SELECT region FROM orders", current).passed
    assert not validate_sql("SELECT region AS r FROM orders", current).passed
    assert not validate_sql("SELECT UPPER(region) FROM orders", current).passed
    # PROJECTION-only 字段不得进入 WHERE / JOIN ON
    assert not validate_sql("SELECT id FROM orders WHERE id = 1", current).passed
    assert not validate_sql("SELECT a.id FROM orders a JOIN orders b ON a.id = b.id", current).passed
    # GROUP / ORDER / HAVING / FUNCTION 在 B3 合同中记录为暂缓细分，不拦截
    assert validate_sql("SELECT id FROM orders GROUP BY id", current).passed
    assert validate_sql("SELECT id FROM orders ORDER BY id", current).passed


def test_outer_limit_is_injected_even_when_inner_query_has_limit():
    current = snapshot()
    current.resources[0].columns[0].usage = ["PROJECTION", "FILTER"]
    cases = [
        "SELECT id FROM orders WHERE id IN (SELECT id FROM orders LIMIT 5)",
        "SELECT id FROM (SELECT id FROM orders LIMIT 5) a",
        "SELECT id FROM orders LIMIT 5 UNION SELECT id FROM orders",
        "WITH q AS (SELECT id FROM orders LIMIT 5) SELECT id FROM q",
    ]
    for sql in cases:
        result = validate_sql(sql, current)
        assert result.passed, result.violations
        assert result.sql.upper().rstrip().endswith("LIMIT 10000"), result.sql


def test_limit_rule_rejects_any_branch_over_the_cap():
    from dataocean.sandbox.rules import limit_rule

    assert not limit_rule.check("SELECT id FROM t LIMIT 50000 UNION SELECT id FROM t2 LIMIT 5").passed
    assert not limit_rule.check("SELECT id FROM t LIMIT 5 UNION SELECT id FROM t2 LIMIT 50000").passed
    assert not limit_rule.check("SELECT id FROM t WHERE id IN (SELECT id FROM t2 LIMIT 50000)").passed
    assert limit_rule.check("SELECT id FROM t LIMIT 5 UNION SELECT id FROM t2 LIMIT 10").passed


def test_contract_accepts_every_java_column_usage_value():
    for usage in ("PROJECTION", "FILTER", "JOIN", "ORDER", "GROUP", "HAVING", "FUNCTION", "SUBQUERY"):
        column = S1Column(name="id", columnId=1, usage=[usage], protectionLevel="NORMAL", maskPolicy=None,
                          dataType="INT", governanceStatus="NORMAL", sourceSnapshotId=88)
        assert column.usage == [usage]


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
    # 该查询把 orders.id 同时用在投影、JOIN ON、GROUP BY 和 ORDER BY 中；
    # B3 强制校验投影/过滤/连接位置，因此必须显式声明 JOIN。
    current.resources[0].columns[0].usage = ["PROJECTION", "JOIN"]
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


def test_glossary_drops_terms_bound_to_other_datasource_columns():
    current = snapshot()
    terms = [
        {"name": "订单", "columns": ["orders.id"]},
        {"name": "客户", "columns": ["customers.secret"]},
        {"name": "无绑定术语"},
    ]

    filtered = filter_glossary(terms, current)

    assert [term["name"] for term in filtered] == ["订单", "无绑定术语"]


def test_glossary_rejects_unnormalized_metadata_fqn_at_firewall_boundary():
    current = snapshot()

    assert filter_glossary([{"name": "订单", "columns": ["ds.db.orders.id"]}], current) == []


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
    current.resources[0].columns[0].usage = ["PROJECTION", "JOIN"]
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
    current.resources[0].columns[0].usage = ["PROJECTION", "JOIN"]
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


def test_inner_scope_alias_cannot_shadow_an_outer_table():
    """内层子查询重名别名不得让外层引用被解析到内层表。"""
    current = snapshot()
    current.resources.append(S1Resource(
        tableName="users",
        columns=[S1Column(name="region", columnId=20, usage=["PROJECTION"], protectionLevel="NORMAL",
                          maskPolicy=None, dataType="VARCHAR", governanceStatus="NORMAL", sourceSnapshotId=88)],
        grantSources=[S1GrantSource(grantId=2, sourceSummary="S1", grantSource="MANUAL",
                                    sourceReferenceId=None, explicitColumns=["region"], rowCondition=None)],
        sourceSnapshotId=88,
    ))
    # 内外层都用别名 u：外层 u.region 是 orders.region（只声明 FILTER，不可投影），
    # 不得因为内层 users.region（可投影）覆盖别名而放行。
    shadowed = validate_sql(
        "SELECT u.region FROM orders u WHERE u.region IN (SELECT u.region FROM users u)", current)
    assert not shadowed.passed, shadowed
    assert validate_sql("SELECT u.region FROM users u", current).passed


def test_cross_database_table_reference_is_rejected():
    current = snapshot()
    for sql in ("SELECT id FROM other_database.orders",
                "SELECT id FROM `other_database`.`orders`",
                "SELECT id FROM mysql.orders",
                "SELECT id FROM other_database.orders WHERE other_database.orders.id = 1"):
        result = validate_sql(sql, current)
        assert not result.passed, sql
        assert "跨库" in result.violations[0], result.violations
    assert validate_sql("SELECT id FROM orders", current).passed


def test_row_condition_is_injected_into_the_owning_scope_only():
    """派生表/CTE 的记录条件只注入到真正读取该表的作用域。"""
    current = snapshot(condition=True)
    bindings = [S1ExecutionBinding(reference="g1", valueType="STRING", value="华东")]
    derived = validate_request(S1SqlValidateRequest(
        protocolVersion="IAM-SIMPLE-1", taskId="task-1", userId=7, datasourceId=1,
        activeMetadataSnapshotId=88, permissionRevision=100, permissionSnapshot=current,
        executionBindings=bindings, sql="SELECT id FROM (SELECT id FROM orders) a"))
    assert derived.passed, derived.violations
    # 注入一次即可：外层并不存在 orders，重复注入会生成无法执行的 SQL。
    assert derived.sql.count("orders.region") == 1, derived.sql
    assert "where" not in derived.sql.split("AS a", 1)[1].lower(), derived.sql
    cte = validate_request(S1SqlValidateRequest(
        protocolVersion="IAM-SIMPLE-1", taskId="task-1", userId=7, datasourceId=1,
        activeMetadataSnapshotId=88, permissionRevision=100, permissionSnapshot=current,
        executionBindings=bindings, sql="WITH q AS (SELECT id FROM orders) SELECT id FROM q"))
    assert cte.passed, cte.violations
    assert cte.sql.count("orders.region") == 1, cte.sql


def test_injection_never_fabricates_an_on_clause():
    """逗号/交叉连接没有自己的 ON，策略必须落到 WHERE，否则生成非法 SQL。"""
    current = snapshot(condition=True)
    rewritten, _ = inject_row_conditions(
        "SELECT o.id FROM orders o, orders x", current,
        [S1ExecutionBinding(reference="g1", valueType="STRING", value="华东")])
    assert rewritten.count(".region") == 2, rewritten  # 每个别名各注入一次
    assert " on " not in rewritten.lower(), rewritten
    assert validate_sql(rewritten, current, enforce_usage=False).passed


def set_operation_snapshot() -> S1PermissionSnapshot:
    """orders.id 普通字段 + users.phone 脱敏字段，用于集合运算位置对齐测试。"""
    return S1PermissionSnapshot(
        protocolVersion="IAM-SIMPLE-1", taskId="task-1", userId=7, datasourceId=1,
        activeMetadataSnapshotId=88, permissionRevision=100,
        calculatedAt="2026-09-18T10:00:00+08:00", nextEffectiveAt=None,
        resources=[
            S1Resource(
                tableName="orders",
                columns=[S1Column(name="id", columnId=1, usage=["PROJECTION"], protectionLevel="NORMAL",
                                  maskPolicy=None, dataType="INT", governanceStatus="NORMAL", sourceSnapshotId=88)],
                grantSources=[S1GrantSource(grantId=1, sourceSummary="S1", grantSource="MANUAL",
                                            sourceReferenceId=None, explicitColumns=["id"], rowCondition=None)],
                sourceSnapshotId=88,
            ),
            S1Resource(
                tableName="users",
                columns=[
                    S1Column(name="phone", columnId=2, usage=["PROJECTION"], protectionLevel="MASKED",
                             maskPolicy="PHONE", dataType="VARCHAR", governanceStatus="NORMAL", sourceSnapshotId=88),
                    S1Column(name="email", columnId=4, usage=["PROJECTION"], protectionLevel="MASKED",
                             maskPolicy="EMAIL", dataType="VARCHAR", governanceStatus="NORMAL", sourceSnapshotId=88),
                    S1Column(name="uid", columnId=3, usage=["PROJECTION"], protectionLevel="NORMAL",
                             maskPolicy=None, dataType="INT", governanceStatus="NORMAL", sourceSnapshotId=88),
                ],
                grantSources=[S1GrantSource(grantId=2, sourceSummary="S1", grantSource="MANUAL",
                                            sourceReferenceId=None, explicitColumns=["phone", "email", "uid"],
                                            rowCondition=None)],
                sourceSnapshotId=88,
            ),
        ],
        capabilities=S1Capabilities(query=True, viewSql=False, export=False),
    )


@pytest.mark.parametrize("keyword", ["UNION", "UNION ALL", "INTERSECT", "EXCEPT"])
def test_set_operation_shares_one_output_across_every_branch(keyword):
    """集合运算按列位置对齐：外层列名必须汇总所有分支的来源，脱敏才作用到位。"""
    current = set_operation_snapshot()
    result = validate_sql(
        f"SELECT q.x FROM (SELECT id AS x FROM orders {keyword} "
        f"SELECT phone AS y FROM users) q", current)
    assert result.passed, result.violations
    assert result.masked_fields.get("x") == "PHONE"
    assert result.used_columns == ["orders.id", "users.phone"]
    outer = [entry["sources"] for entry in result.source_trace if entry["outputColumn"] == "x"]
    assert ["orders.id", "users.phone"] in outer, outer


def test_top_level_set_operation_masks_the_shared_output():
    """顶层直接是集合运算时，结果列同样必须记录所有分支来源。"""
    current = set_operation_snapshot()
    result = validate_sql("SELECT id AS x FROM orders UNION ALL SELECT phone AS y FROM users", current)
    assert result.passed, result.violations
    assert result.masked_fields.get("x") == "PHONE"


def test_cte_and_nested_set_operations_aggregate_all_branches():
    current = set_operation_snapshot()
    cte = validate_sql("WITH q AS (SELECT id AS x FROM orders UNION "
                       "SELECT phone AS y FROM users) SELECT q.x FROM q", current)
    assert cte.passed, cte.violations
    assert cte.masked_fields.get("x") == "PHONE"
    nested = validate_sql("SELECT q.x FROM (SELECT id AS x FROM orders UNION SELECT uid AS z FROM users "
                          "UNION ALL SELECT phone AS w FROM users) q", current)
    assert nested.passed, nested.violations
    assert nested.masked_fields.get("x") == "PHONE"
    assert nested.used_columns == ["orders.id", "users.phone", "users.uid"]


def test_masked_source_in_the_first_branch_is_not_dropped():
    current = set_operation_snapshot()
    result = validate_sql("SELECT q.x FROM (SELECT phone AS x FROM users UNION ALL "
                          "SELECT id AS y FROM orders) q", current)
    assert result.passed, result.violations
    assert result.masked_fields.get("x") == "PHONE"


def test_set_operation_position_reference_cannot_bypass_masking_rule():
    """按集合运算位置引用脱敏字段仍受"只能直接投影"约束。"""
    current = set_operation_snapshot()
    result = validate_sql(
        "SELECT id AS x FROM orders UNION ALL SELECT phone AS y FROM users ORDER BY x", current)
    assert not result.passed
    assert "脱敏" in result.violations[0], result.violations


@pytest.mark.parametrize("sql", [
    "SELECT q.x FROM (SELECT id AS x FROM orders UNION ALL SELECT phone AS y FROM users "
    "UNION ALL SELECT email AS z FROM users) q",
    "SELECT q.x FROM (SELECT id AS x FROM orders UNION ALL SELECT email AS z FROM users "
    "UNION ALL SELECT phone AS y FROM users) q",
    "SELECT q.x, r.x FROM (SELECT phone AS x FROM users) q, (SELECT email AS x FROM users) r",
])
def test_one_output_column_cannot_carry_two_mask_policies(sql):
    """同一输出列汇入两种脱敏策略时按列名无法同时生效，必须拒绝而不是静默覆盖。"""
    current = set_operation_snapshot()
    result = validate_sql(sql, current)
    assert not result.passed
    assert "脱敏策略" in result.violations[0], result.violations


def test_identical_mask_policy_across_branches_is_allowed():
    current = set_operation_snapshot()
    result = validate_sql("SELECT q.x FROM (SELECT phone AS x FROM users UNION ALL "
                          "SELECT phone AS y FROM users) q", current)
    assert result.passed, result.violations
    assert result.masked_fields.get("x") == "PHONE"


@pytest.mark.parametrize("sql", [
    "SELECT id AS x FROM orders UNION SELECT phone AS y, uid AS z FROM users",
    "SELECT id AS x, uid AS p FROM orders JOIN users ON 1 = 1 UNION SELECT phone AS y FROM users",
])
def test_set_operation_branch_column_count_mismatch_is_rejected(sql):
    """两个方向的列数不一致都必须拒绝，不能只拦"后续分支列更少"。"""
    current = set_operation_snapshot()
    result = validate_sql(sql, current)
    assert not result.passed
    assert "列数不一致" in result.violations[0], result.violations


def test_output_column_name_case_cannot_split_mask_policies():
    """Java 最终按小写列名匹配脱敏映射，大小写变体不得绕过冲突检查。"""
    current = set_operation_snapshot()
    mixed = validate_sql("SELECT phone AS X, email AS x FROM users", current)
    assert not mixed.passed
    assert "脱敏策略" in mixed.violations[0], mixed.violations
    # 同一策略的大小写变体之间没有冲突，仍然允许
    same = validate_sql("SELECT phone AS X, phone AS x FROM users", current)
    assert same.passed, same.violations


def test_set_operation_branch_output_without_source_is_rejected():
    """常量等无来源的分支输出必须在 AST 阶段拒绝，而不是执行后再失败。"""
    current = set_operation_snapshot()
    result = validate_sql("SELECT q.x FROM (SELECT id AS x FROM orders UNION ALL "
                          "SELECT 'fixed' AS y FROM users) q", current)
    assert not result.passed
    assert "可追踪字段来源" in result.violations[0], result.violations


def test_derived_and_cte_aliases_resolve_to_physical_sources():
    """a.id / q.id 必须能追踪到物理来源，而不是直接拒绝。"""
    current = snapshot()
    for sql in ("SELECT a.id FROM (SELECT id FROM orders) a",
                "WITH q AS (SELECT id FROM orders) SELECT q.id FROM q"):
        result = validate_sql(sql, current)
        assert result.passed, (sql, result.violations)
        assert result.used_columns == ["orders.id"], result.used_columns
    # 追踪不到时仍然拒绝
    assert not validate_sql("SELECT a.id FROM (SELECT region FROM orders) a", current).passed
    assert not validate_sql("SELECT a.missing FROM (SELECT id FROM orders) a", current).passed


def test_server_injected_row_condition_is_exempt_from_field_usage():
    """服务端注入的策略谓词位置由连接语义决定，不受请求方 usage 声明约束。"""
    current = snapshot(condition=True)
    current.resources[0].columns[0].usage = ["PROJECTION", "JOIN"]
    validation = validate_request(S1SqlValidateRequest(
        protocolVersion="IAM-SIMPLE-1", taskId="task-1", userId=7, datasourceId=1,
        activeMetadataSnapshotId=88, permissionRevision=100, permissionSnapshot=current,
        executionBindings=[S1ExecutionBinding(reference="g1", valueType="STRING", value="华东")],
        sql="SELECT o.id FROM orders o LEFT JOIN orders x ON o.id = x.id",
    ))
    assert validation.passed, validation.violations
    # region 只声明 FILTER，却被注入到 JOIN ON 上；复测必须因为这是服务端谓词而放行，
    # 但按 usage 强制的单次校验仍会拒绝它，证明豁免范围是精确的。
    assert not validate_sql(validation.sql, current).passed
    assert validate_sql(validation.sql, current, enforce_usage=False).passed


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


@pytest.mark.asyncio
async def test_s1_query_preserves_chart_generation_result():
    from dataocean.chart.service import ChartResult

    current = snapshot()
    current.taskId = "task-chart"
    request = S1QueryExecuteRequest(
        protocolVersion="IAM-SIMPLE-1", taskId="task-chart", userId=7, datasourceId=1,
        activeMetadataSnapshotId=88, permissionRevision=100, permissionSnapshot=current,
        executionBindings=[], question="统计订单数量", connectionConfig=S1ConnectionConfig(
            host="localhost", port=3306, database="db", username="u", password="p"),
        conversationHistory=[], conversationSummary=None, ragChunks=[], fallbackChunks=[],
        glossaryTerms=[], fewShotExamples=[],
    )
    with patch("dataocean.iam_s1.service.retrieve", new=AsyncMock(return_value=[])), \
            patch("dataocean.iam_s1.service.call_llm", new=AsyncMock(return_value="SELECT id FROM orders")), \
            patch("dataocean.iam_s1.service.execute_validated", new=AsyncMock(return_value={
                "success": True, "data": [{"id": 1}], "columns": [{"name": "id", "type": "INT"}],
                "rowCount": 1, "trace": {"sourceTrace": [], "usedTables": ["orders"], "usedColumns": ["orders.id"]},
            })), \
            patch("dataocean.chart.service.generate_chart", new=AsyncMock(return_value=ChartResult(
                chart_type="bar", echarts_option={"series": [{"type": "bar", "data": [1]}]}))):
        result = await run_query(request)

    assert result["status"] == "COMPLETED"
    assert result["chartConfig"]["series"][0]["type"] == "bar"
