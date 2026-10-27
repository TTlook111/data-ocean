"""知识库草稿生成服务

基于固定元数据快照与审核来源，确定性组织业务知识文档。
AI 候选由独立业务/字段治理入口生成；完整结构清单不发送给模型。
"""

import asyncio
import html
import logging
import re


from .schema import (
    GenerateDraftRequest,
    GenerateDraftResponse,
)

logger = logging.getLogger(__name__)

_MAX_WARNINGS = 50
async def generate_draft(request: GenerateDraftRequest) -> GenerateDraftResponse:
    """从单一元数据快照确定性生成完整字段目录与有来源的知识事实。

    结构事实不交给模型改写。没有单一来源证明或未确认的释义只标为待确认，
    并通过 fact marker 保留每个 chunk 的来源与完整资源依赖。
    """
    _validate_snapshot_manifest(request)
    logger.info(
        "开始生成 skills.md 草稿 snapshot_id=%d datasource_id=%d",
        request.snapshot_id,
        request.datasource_id,
    )
    content, coverage, warnings = await asyncio.to_thread(_render_snapshot_document, request)

    logger.info(
        "skills.md 快照草稿生成完成 content_length=%d tables=%d columns=%d warnings=%d",
        len(content),
        coverage["tableCount"],
        coverage["columnCount"],
        len(warnings),
    )

    return GenerateDraftResponse(
        content=content,
        generation_source="SNAPSHOT_GENERATED",
        warnings=warnings,
        coverage=coverage,
    )


def _validate_snapshot_manifest(request: GenerateDraftRequest) -> None:
    """Fail closed if Java sends mixed-snapshot or incomplete structures."""
    if not request.tables_metadata:
        raise ValueError("所选快照没有可生成的表结构")
    table_ids: set[int] = set()
    table_names: set[str] = set()
    column_ids: set[int] = set()
    column_names: set[str] = set()
    for table in request.tables_metadata:
        if table.table_id is None or table.source_snapshot_id != request.snapshot_id:
            raise ValueError("表清单缺少稳定 ID 或不属于本次 snapshotId")
        if table.table_id in table_ids or table.table_name in table_names:
            raise ValueError("本次 snapshotId 的表 ID 或表名重复")
        table_ids.add(table.table_id)
        table_names.add(table.table_name)
        for column in table.columns:
            if column.column_id is None or column.source_snapshot_id != request.snapshot_id:
                raise ValueError("字段清单缺少稳定 ID 或不属于本次 snapshotId")
            key = f"{table.table_name}.{column.column_name}"
            if column.column_id in column_ids or key in column_names:
                raise ValueError("本次 snapshotId 的字段 ID 或限定字段名重复")
            column_ids.add(column.column_id)
            column_names.add(key)

    eligible = set()
    for table in request.tables_metadata:
        if (table.governance_status or "").upper() in {"BLOCKED", "DEPRECATED"}:
            continue
        eligible.add(f"table:{table.table_name}")
        eligible.update(f"column:{table.table_name}.{column.column_name}" for column in table.columns
                        if (column.governance_status or "").upper() not in {"BLOCKED", "DEPRECATED"})
    for fact in request.glossary_facts:
        deps = fact.get("dependencies")
        if (not isinstance(deps, list) or not deps or not set(deps).issubset(eligible)
                or not any(d.startswith("column:") for d in deps)
                or not isinstance(fact.get("term_id"), int) or fact["term_id"] <= 0
                or not isinstance(fact.get("review_id"), int) or fact["review_id"] <= 0
                or not re.fullmatch(r"[0-9a-f]{64}", str(fact.get("content_hash", "")))):
            raise ValueError("术语来源或依赖不属于本次合规快照")

    for fact in request.field_semantic_facts:
        source_ids = fact.get("sourceIds")
        dependencies = fact.get("dependencies")
        if (not isinstance(source_ids, list) or len(source_ids) != 4
                or any(not isinstance(value, int) or value <= 0 for value in source_ids)
                or not isinstance(dependencies, list) or not dependencies
                or any(not isinstance(dependency, str) for dependency in dependencies)
                or not set(dependencies).issubset(eligible)
                or not isinstance(fact.get("body"), str) or not fact["body"].strip()
                or fact.get("reviewStatus") != "APPROVED"
                or fact.get("governanceStatus") not in {"NORMAL", "RECOMMENDED", "SENSITIVE"}
                or not re.fullmatch(r"[0-9a-f]{64}", str(fact.get("contentHash", "")))
                or not re.fullmatch(r"[0-9a-f]{64}", str(fact.get("dependencyHash", "")))):
            raise ValueError("字段语义事实缺少精确审核来源、hash 或完整快照依赖")

    for fact in request.business_knowledge_facts:
        if (fact.get('factType') not in {'BUSINESS_DATASOURCE', 'BUSINESS_TABLE', 'BUSINESS_RELATION', 'BUSINESS_METRIC'}
                or fact.get('reviewStatus') != 'APPROVED'
                or not isinstance(fact.get('sourceIds'), list) or len(fact['sourceIds']) != 1
                or not isinstance(fact['sourceIds'][0], int) or fact['sourceIds'][0] <= 0
                or not isinstance(fact.get('dependencies'), list) or not fact['dependencies']
                or not set(fact['dependencies']).issubset(eligible)
                or not isinstance(fact.get('body'), str) or not fact['body'].strip()
                or not re.fullmatch(r'[0-9a-f]{64}', str(fact.get('contentHash', '')))
                or not re.fullmatch(r'[0-9a-f]{64}', str(fact.get('dependencyHash', '')))):
            raise ValueError('业务模型知识缺少审核、完整来源或内容摘要')

    for relation in request.foreign_keys:
        relation_snapshot = relation.get("snapshot_id", relation.get("source_snapshot_id"))
        if relation_snapshot is not None and int(relation_snapshot) != request.snapshot_id:
            raise ValueError("关系事实混入了其他 snapshotId")
    for fact in request.lineage_facts:
        if int(fact.get("bound_snapshot_id", -1)) != request.snapshot_id:
            raise ValueError("血缘事实未绑定到本次 snapshotId")


def _render_snapshot_document(request: GenerateDraftRequest) -> tuple[str, dict, list[str]]:
    table_ids: list[int] = []
    column_ids: list[int] = []
    unresolved_comments = 0
    warnings: list[str] = []
    lines = [
        f"# 数据源 {request.datasource_id} skills.md",
        "",
        "## 1. 文档来源",
        f"- datasourceId: `{request.datasource_id}`",
        f"- snapshotId: `{request.snapshot_id}`",
        "- 字段名称、类型、主键与治理状态由该快照清单确定；结构事实不由模型补写。",
        "",
        "## 2. 核心表与完整字段目录",
        "",
    ]

    for table in sorted(request.tables_metadata, key=lambda item: (item.table_name, item.table_id or 0)):
        table_ids.append(table.table_id)
        table_excluded = (table.governance_status or "").upper() in {"BLOCKED", "DEPRECATED"}
        table_dependencies = [f"table:{table.table_name}"]
        lines.extend([
            f"### `{_markdown_identifier(table.table_name)}`",
            _fact_marker(request.snapshot_id, "TABLE_EXCLUDED" if table_excluded else "TABLE_STRUCTURE", [table.table_id], table_dependencies,
                         "PENDING" if table_excluded else "APPROVED", table.governance_status or "DISCOVERED"),
            f"- Snapshot table ID: `{table.table_id}`",
            f"- Table type: `{_plain(table.table_type or 'UNKNOWN')}`",
            f"- Governance status: `{_plain(table.governance_status or 'DISCOVERED')}`",
            "- Business meaning: 待确认。",
        ])
        if table.table_comment and not table_excluded:
            unresolved_comments += 1
            warnings.append(f"表 {table.table_name} 的原始注释尚未经过业务释义审核")
            lines.extend([
                "#### 原始表注释（待业务确认）",
                _fact_marker(request.snapshot_id, "TABLE_COMMENT", [table.table_id], table_dependencies,
                             "PENDING", table.governance_status or "DISCOVERED"),
                f"> {_plain(table.table_comment)}",
            ])

        for column in sorted(table.columns, key=lambda item: (item.ordinal_position or 0, item.column_name)):
            column_ids.append(column.column_id)
            column_excluded = table_excluded or (column.governance_status or "").upper() in {"BLOCKED", "DEPRECATED"}
            column_dependencies = [
                f"table:{table.table_name}",
                f"column:{table.table_name}.{column.column_name}",
            ]
            lines.extend([
                "",
                f"#### `{_markdown_identifier(column.column_name)}`",
                _fact_marker(
                    request.snapshot_id,
                    "COLUMN_EXCLUDED" if column_excluded else "COLUMN_STRUCTURE",
                    [table.table_id, column.column_id],
                    column_dependencies,
                    "PENDING" if column_excluded else "APPROVED",
                    column.governance_status or "DISCOVERED",
                ),
                f"- Column ID: `{column.column_id}`",
                f"- Type: `{_plain(column.column_type or 'UNKNOWN')}`",
                f"- Primary key: `{str(bool(column.is_primary_key)).lower()}`",
                f"- Governance status: `{_plain(column.governance_status or 'DISCOVERED')}`",
                f"- Business meaning: {'待确认' if not column.column_comment else '原始注释待业务确认'}。",
            ])
            if column.column_comment and not column_excluded:
                unresolved_comments += 1
                warnings.append(f"字段 {table.table_name}.{column.column_name} 的原始注释尚未经过业务释义审核")
                lines.extend([
                    "##### 原始字段注释（待业务确认）",
                    _fact_marker(request.snapshot_id, "COLUMN_COMMENT", [table.table_id, column.column_id],
                                 column_dependencies, "PENDING", column.governance_status or "DISCOVERED"),
                    f"> {_plain(column.column_comment)}",
                ])
            else:
                warnings.append(f"字段 {table.table_name}.{column.column_name} 无可信业务释义，标为待确认")

        lines.append("")

    lines.extend(["## 3. Confirmed Join Paths", ""])
    confirmed_count = sum(fact["factType"] == "BUSINESS_RELATION" for fact in request.business_knowledge_facts)
    inferred: list[dict] = []
    known_columns = {
        f"{table.table_name}.{column.column_name}"
        for table in request.tables_metadata
        for column in table.columns
    }
    known_tables = {table.table_name for table in request.tables_metadata}
    eligible_resources = set()
    for table in request.tables_metadata:
        if (table.governance_status or "").upper() in {"BLOCKED", "DEPRECATED"}:
            continue
        eligible_resources.add(f"table:{table.table_name}")
        eligible_resources.update(f"column:{table.table_name}.{column.column_name}" for column in table.columns
                                  if (column.governance_status or "").upper() not in {"BLOCKED", "DEPRECATED"})
    for relation in sorted(request.foreign_keys, key=lambda item: str(item.get("relation_id", ""))):
        source_table = str(relation.get("source_table") or "")
        source_column = str(relation.get("source_column") or "")
        target_table = str(relation.get("target_table") or "")
        target_column = str(relation.get("target_column") or "")
        source_ref = f"{source_table}.{source_column}"
        target_ref = f"{target_table}.{target_column}"
        if source_table not in known_tables or target_table not in known_tables or source_ref not in known_columns or target_ref not in known_columns:
            raise ValueError("关系事实引用了本次快照不存在的表或字段")
        if not {f"table:{source_table}", f"table:{target_table}", f"column:{source_ref}", f"column:{target_ref}"}.issubset(eligible_resources):
            warnings.append("已排除涉及阻断或废弃资源的关系事实")
            continue
        inferred.append(relation)
    lines.extend(["原始结构关系仅作线索；业务关联以已审核复查的业务关系知识为准。", ""])

    lines.extend(["## 4. Unconfirmed relationship candidates", ""])
    for relation in inferred:
        source = f"{relation.get('source_table')}.{relation.get('source_column')}"
        target = f"{relation.get('target_table')}.{relation.get('target_column')}"
        lines.extend([
            f"### `{_markdown_identifier(source)}` → `{_markdown_identifier(target)}`",
            _fact_marker(request.snapshot_id, "JOIN_CANDIDATE", [relation.get("relation_id")], [f"table:{relation.get('source_table')}", f"table:{relation.get('target_table')}", f"column:{source}", f"column:{target}"], "PENDING"),
            f"- Condition: `{_markdown_identifier(source)} = {_markdown_identifier(target)}`（结构线索，业务用法待确认）",
            "- Status: 待审核；不能当作可执行 Join Path。",
            f"- Source relation ID: `{_plain(relation.get('relation_id', 'unknown'))}`; type: `{_plain(relation.get('relation_type', 'INFERRED'))}`; confidence: `{_plain(relation.get('confidence', 'unknown'))}`",
            "",
        ])
    if not inferred:
        lines.append("暂无待审核关系候选。")
        lines.append("")

    lines.extend(["## 5. Data lineage and field derivations", ""])
    confirmed_lineage_count = 0
    for fact in request.lineage_facts:
        review_status = str(fact.get("confirmation_status") or fact.get("review_status") or "PENDING").upper()
        binding_status = str(fact.get("binding_status") or "UNBOUND").upper()
        source_fqn = str(fact.get("source_fqn") or "")
        target_fqn = str(fact.get("target_fqn") or "")
        relation_type = str(fact.get("relation_type") or "LINEAGE").upper()
        dependencies = _lineage_dependencies(fact)
        if not dependencies or not set(dependencies).issubset(eligible_resources):
            warnings.append("已排除涉及不可用资源的血缘事实")
            continue
        approved = review_status in {"APPROVED", "CONFIRMED"} and binding_status == "BOUND"
        status = "APPROVED" if approved else "PENDING"
        if approved:
            confirmed_lineage_count += 1
        lines.extend([
            f"### `{_markdown_identifier(source_fqn)}` → `{_markdown_identifier(target_fqn)}`",
            _fact_marker(request.snapshot_id, relation_type, [fact.get("relationship_id")], dependencies, status,
                         source_fingerprint=fact.get("source_fingerprint")),
            f"- Relationship: `{relation_type}`; approval: `{review_status}`; binding: `{binding_status}`",
            f"- Provenance: relationship `{_plain(fact.get('relationship_id', 'unknown'))}`; original snapshot `{_plain(fact.get('source_snapshot_id', 'unknown'))}`; bound snapshot `{request.snapshot_id}`",
            f"- Meaning: {_plain(fact.get('description') or '待确认')}" + ("" if approved else "（待确认，不能作为 Join 条件）"),
            "- Lineage describes data flow or derivation; it does not define an executable Join condition.",
            "",
        ])
    if confirmed_lineage_count == 0:
        lines.append("暂无已确认且绑定到本快照的数据血缘或字段派生。")
        lines.append("")

    lines.extend([
        "## 6. 字段防坑指南",
        "",
        "字段释义缺少经确认的业务来源时保持待确认；禁用、废弃和敏感字段按快照治理状态标注。",
        "",
        "## 7. 指标口径",
        "",
        "暂无随本次快照输入的已审核指标口径。不得根据字段名臆造公式。",
        "",
        "## 8. 常见查询场景",
        "",
        "暂无随本次快照输入的已审核查询场景。不得根据字段名臆造场景。",
        "",
        "## 9. Governance and use restrictions",
        "",
        "完整结构目录包含快照中的所有表和字段。字段是否可查询由当前治理状态、IAM-SIMPLE-1 权限及执行时校验决定。",
    ])

    lines.extend(["", "## 10. 已审核业务术语", ""])
    for fact in request.glossary_facts:
        if not fact.get("dependencies") or not fact.get("content_hash") or not fact.get("body"):
            raise ValueError("术语事实缺少审核来源或字段依赖")
        marker = {
            "snapshotId": request.snapshot_id, "factType": "GLOSSARY_TERM",
            "sourceIds": [fact["term_id"], fact["review_id"]],
            "sourceHash": fact["content_hash"], "sourceRevision": fact["revision"],
            "dependencies": fact["dependencies"], "reviewStatus": "APPROVED", "governanceStatus": "NORMAL",
        }
        import json
        lines.extend([f"### 业务术语：{_plain(fact['name'])}",
                      "<!-- dataocean-fact: " + json.dumps(marker, ensure_ascii=False, separators=(",", ":")) + " -->",
                      str(fact["body"]), ""])
    if request.field_semantic_facts:
        lines.extend(["## 11. 已审核字段解释", ""])
    for fact in request.field_semantic_facts:
        marker = {
            "snapshotId": request.snapshot_id,
            "factType": "FIELD_SEMANTIC",
            "sourceIds": fact["sourceIds"],
            "dependencies": fact["dependencies"],
            "reviewStatus": "APPROVED",
            "governanceStatus": fact.get("governanceStatus", "NORMAL"),
            "contentHash": fact["contentHash"],
            "dependencyHash": fact["dependencyHash"],
        }
        import json
        lines.extend([f"### 字段解释：{_plain(str(fact.get('businessName') or '字段'))}",
                      "<!-- dataocean-fact: " + json.dumps(marker, ensure_ascii=False, separators=(",", ":")) + " -->",
                      fact["body"], ""])
    current_business_kind = None
    business_titles = {'BUSINESS_DATASOURCE': '数据源业务概览', 'BUSINESS_TABLE': '表职责与粒度',
                       'BUSINESS_RELATION': '表关系与聚合条件', 'BUSINESS_METRIC': '指标口径与使用场景'}
    business_order = {key: index for index, key in enumerate(business_titles)}
    for fact in sorted(request.business_knowledge_facts, key=lambda f: (business_order[f['factType']], f['sourceIds'][0])):
        if current_business_kind != fact['factType']:
            current_business_kind = fact['factType']
            lines.extend([f"## {12 + business_order[current_business_kind]}. {business_titles[current_business_kind]}", ''])
        import json
        marker = {key: fact[key] for key in ('factType', 'sourceIds', 'dependencies', 'reviewStatus', 'governanceStatus', 'contentHash', 'dependencyHash')}
        marker['snapshotId'] = request.snapshot_id
        lines.extend([f"### {_plain(fact['title'])}",
                      '<!-- dataocean-fact: ' + json.dumps(marker, ensure_ascii=False, separators=(',', ':')) + ' -->',
                      fact['body'], ''])
    for excluded in request.excluded_terms:
        warnings.append(f"术语 {excluded.get('name', '')} 未纳入：{excluded.get('reason', '来源不完整')}")
    content = "\n".join(lines).rstrip() + "\n"
    errors = _validate_generated_coverage(content, request)
    if errors:
        raise ValueError("快照字段覆盖校验失败：" + "；".join(errors))
    expected_tables = sorted(table_ids)
    expected_columns = sorted(column_ids)
    coverage = {
        "snapshotId": request.snapshot_id,
        "tableCount": len(expected_tables),
        "columnCount": len(expected_columns),
        "tableIds": expected_tables,
        "columnIds": expected_columns,
        "tableCoverage": 1.0,
        "columnCoverage": 1.0,
        "confirmedJoinCount": confirmed_count,
        "pendingRelationshipCount": len(inferred),
        "confirmedLineageCount": confirmed_lineage_count,
        "unresolvedDescriptionCount": unresolved_comments,
    }
    return content, coverage, list(dict.fromkeys(warnings))[:_MAX_WARNINGS]


def _fact_marker(
    snapshot_id: int,
    fact_type: str,
    source_ids: list,
    dependencies: list[str],
    status: str,
    governance_status: str = "NORMAL",
    source_fingerprint: str | None = None,
) -> str:
    marker = {
        "snapshotId": snapshot_id,
        "factType": fact_type,
        "sourceIds": [item for item in source_ids if item is not None],
        "dependencies": list(dict.fromkeys(dependencies)),
        "reviewStatus": status,
        "governanceStatus": governance_status,
    }
    import json

    if source_fingerprint:
        marker["sourceFingerprint"] = source_fingerprint
    return "<!-- dataocean-fact: " + json.dumps(marker, ensure_ascii=False, separators=(",", ":")) + " -->"


def _validate_generated_coverage(content: str, request: GenerateDraftRequest) -> list[str]:
    """Prove the rendered document has exactly one structural marker per snapshot object."""
    import json

    table_ids: list[int] = []
    column_ids: list[int] = []
    pattern = re.compile(r"<!-- dataocean-fact: (\{.*?\}) -->")
    for raw in pattern.findall(content):
        try:
            marker = json.loads(raw)
        except ValueError:
            return ["fact marker JSON 无效"]
        fact_type = marker.get("factType")
        source_ids = marker.get("sourceIds") or []
        if fact_type in {"TABLE_STRUCTURE", "TABLE_EXCLUDED"}:
            table_ids.extend(int(item) for item in source_ids)
        elif fact_type in {"COLUMN_STRUCTURE", "COLUMN_EXCLUDED"}:
            if len(source_ids) != 2:
                return ["字段结构 marker 必须包含表和字段 ID"]
            column_ids.append(int(source_ids[1]))
    expected_table_ids = [table.table_id for table in request.tables_metadata]
    expected_column_ids = [column.column_id for table in request.tables_metadata for column in table.columns]
    errors = []
    if sorted(table_ids) != sorted(expected_table_ids):
        errors.append("表结构 ID 有遗漏、重复或额外值")
    if sorted(column_ids) != sorted(expected_column_ids):
        errors.append("字段结构 ID 有遗漏、重复或额外值")
    return errors


def _lineage_dependencies(fact: dict) -> list[str]:
    """完整依赖必须与真实端点一致；字段派生不能退化为纯表事实。"""
    kind = str(fact.get("relation_type") or "LINEAGE").upper()
    expected_size = {"LINEAGE": 3, "DERIVED_FROM": 4}.get(kind)
    endpoints = [str(fact.get(side) or "").lower().split(".") for side in ("source_fqn", "target_fqn")]
    if expected_size is None or any(len(parts) != expected_size or any(not p or p != p.strip() for p in parts) for parts in endpoints):
        return []
    if endpoints[0][:2] != endpoints[1][:2]:
        return []
    result = []
    for parts in endpoints:
        result.append(f"table:{parts[2]}")
        if expected_size == 4:
            result.append(f"column:{parts[2]}.{parts[3]}")
    result = list(dict.fromkeys(result))
    declared = fact.get("dependencies")
    if declared is not None and (not isinstance(declared, list) or any(not isinstance(item, str) for item in declared) or set(declared) != set(result)):
        return []
    return result


def _markdown_identifier(value: str) -> str:
    return _plain(value).replace("`", "\\`")


def _plain(value: object) -> str:
    text = "" if value is None else str(value)
    text = re.sub(r"[\r\n\t]+", " ", text)
    return html.escape(text, quote=False).strip()
