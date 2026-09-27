"""知识库草稿生成服务

基于元数据快照调用 LLM 生成结构化的 skills.md 业务知识文档。
LLM 调用统一走 infra.llm，模板渲染统一走 prompt.renderer（LangChain PromptTemplate）。
"""

import asyncio
import html
import logging
from pathlib import Path
import re

from dataocean.infra.llm import call_llm
from dataocean.infra.parsers import JsonBlockOutputParser
from dataocean.prompt.renderer import render_template_file

from .schema import (
    BatchGenerateResponse,
    DomainDoc,
    DomainGroup,
    GenerateDraftRequest,
    GenerateDraftResponse,
)

logger = logging.getLogger(__name__)

PROMPTS_DIR = Path(__file__).parent / "prompts"
_SKILLS_MD_TEMPLATE = PROMPTS_DIR / "skills_md_template.j2"
_DOMAIN_ANALYSIS_TEMPLATE = PROMPTS_DIR / "domain_analysis.j2"

# skills.md 草稿生成的系统角色提示
_SYSTEM_PROMPT = "你是一个数据库文档专家，负责根据数据库元数据生成结构化的 skills.md 业务知识文档。"
_DOMAIN_ANALYSIS_PROMPT = "你是一个数据库架构分析专家。请严格按照要求输出 JSON 格式。"

_MAX_WARNINGS = 50
# 域文档彼此独立，但仍需限制并发，避免一次请求向 LLM 供应商发起过多调用。
_MAX_DOMAIN_GENERATION_CONCURRENCY = 4
_json_parser = JsonBlockOutputParser(allow_null=False)


def _check_missing_comments(tables: list) -> list[str]:
    """检查无注释的表和字段，生成警告列表（最多 _MAX_WARNINGS 条）"""
    warnings = []
    for table in tables:
        if len(warnings) >= _MAX_WARNINGS:
            remaining = sum(1 for t in tables for c in t.columns if not c.column_comment) - len(warnings)
            if remaining > 0:
                warnings.append(f"...及其他 {remaining} 个字段无注释")
            break
        if not table.table_comment:
            warnings.append(
                f"表 {table.table_name} 无注释，AI 已基于字段推测用途（待人工确认）"
            )
        for col in table.columns:
            if len(warnings) >= _MAX_WARNINGS:
                break
            if not col.column_comment:
                warnings.append(
                    f"字段 {table.table_name}.{col.column_name} 无注释（待人工确认）"
                )
    return warnings


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
    content, coverage, warnings = _render_snapshot_document(request)

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
        table_dependencies = [f"table:{table.table_name}"]
        lines.extend([
            f"### `{_markdown_identifier(table.table_name)}`",
            _fact_marker(request.snapshot_id, "TABLE_STRUCTURE", [table.table_id], table_dependencies,
                         "APPROVED", table.governance_status or "DISCOVERED"),
            f"- Snapshot table ID: `{table.table_id}`",
            f"- Table type: `{_plain(table.table_type or 'UNKNOWN')}`",
            f"- Governance status: `{_plain(table.governance_status or 'DISCOVERED')}`",
            "- Business meaning: 待确认。",
        ])
        if table.table_comment:
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
            column_dependencies = [
                f"table:{table.table_name}",
                f"column:{table.table_name}.{column.column_name}",
            ]
            lines.extend([
                "",
                f"#### `{_markdown_identifier(column.column_name)}`",
                _fact_marker(
                    request.snapshot_id,
                    "COLUMN_STRUCTURE",
                    [table.table_id, column.column_id],
                    column_dependencies,
                    "APPROVED",
                    column.governance_status or "DISCOVERED",
                ),
                f"- Column ID: `{column.column_id}`",
                f"- Type: `{_plain(column.column_type or 'UNKNOWN')}`",
                f"- Primary key: `{str(bool(column.is_primary_key)).lower()}`",
                f"- Governance status: `{_plain(column.governance_status or 'DISCOVERED')}`",
                f"- Business meaning: {'待确认' if not column.column_comment else '原始注释待业务确认'}。",
            ])
            if column.column_comment:
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
    confirmed_count = 0
    inferred: list[dict] = []
    known_columns = {
        f"{table.table_name}.{column.column_name}"
        for table in request.tables_metadata
        for column in table.columns
    }
    known_tables = {table.table_name for table in request.tables_metadata}
    for relation in sorted(request.foreign_keys, key=lambda item: str(item.get("relation_id", ""))):
        source_table = str(relation.get("source_table") or "")
        source_column = str(relation.get("source_column") or "")
        target_table = str(relation.get("target_table") or "")
        target_column = str(relation.get("target_column") or "")
        source_ref = f"{source_table}.{source_column}"
        target_ref = f"{target_table}.{target_column}"
        if source_table not in known_tables or target_table not in known_tables or source_ref not in known_columns or target_ref not in known_columns:
            raise ValueError("关系事实引用了本次快照不存在的表或字段")
        relation_type = str(relation.get("relation_type") or "FK").upper()
        review_status = str(relation.get("review_status") or "PENDING").upper()
        approved = relation_type == "FK" or (
            relation_type == "MANUAL" and review_status in {"APPROVED", "CONFIRMED"}
        )
        if not approved:
            inferred.append(relation)
            continue
        confirmed_count += 1
        dependencies = [f"table:{source_table}", f"table:{target_table}", f"column:{source_ref}", f"column:{target_ref}"]
        lines.extend([
            f"### `{_markdown_identifier(source_table)}.{_markdown_identifier(source_column)}` → `{_markdown_identifier(target_table)}.{_markdown_identifier(target_column)}`",
            _fact_marker(request.snapshot_id, "JOIN_PATH", [relation.get("relation_id")], dependencies, "APPROVED"),
            f"- Condition: `{_markdown_identifier(source_table)}.{_markdown_identifier(source_column)} = {_markdown_identifier(target_table)}.{_markdown_identifier(target_column)}`",
            f"- Relation type: `{relation_type}`; review status: `{review_status}`",
            f"- Source relation ID: `{_plain(relation.get('relation_id', 'unknown'))}`; source snapshot: `{request.snapshot_id}`",
            "",
        ])
    if confirmed_count == 0:
        lines.append("暂无本快照已确认的可执行 Join 条件。")
        lines.append("")

    lines.extend(["## 4. Unconfirmed relationship candidates", ""])
    for relation in inferred:
        source = f"{relation.get('source_table')}.{relation.get('source_column')}"
        target = f"{relation.get('target_table')}.{relation.get('target_column')}"
        lines.extend([
            f"### `{_markdown_identifier(source)}` → `{_markdown_identifier(target)}`",
            _fact_marker(request.snapshot_id, "JOIN_CANDIDATE", [relation.get("relation_id")], [f"column:{source}", f"column:{target}"], "PENDING"),
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
        approved = review_status in {"APPROVED", "CONFIRMED"} and binding_status == "BOUND"
        status = "APPROVED" if approved else "PENDING"
        if approved:
            confirmed_lineage_count += 1
        lines.extend([
            f"### `{_markdown_identifier(source_fqn)}` → `{_markdown_identifier(target_fqn)}`",
            _fact_marker(request.snapshot_id, relation_type, [fact.get("relationship_id")], dependencies, status),
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
        if fact_type == "TABLE_STRUCTURE":
            table_ids.extend(int(item) for item in source_ids)
        elif fact_type == "COLUMN_STRUCTURE":
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
    dependencies = fact.get("dependencies")
    if isinstance(dependencies, list):
        return [str(item) for item in dependencies]
    result = []
    for side in ("source_fqn", "target_fqn"):
        fqn = str(fact.get(side) or "")
        if fqn.count(".") >= 2:
            parts = fqn.split(".")
            result.extend([f"table:{parts[-2]}", f"column:{parts[-2]}.{parts[-1]}"])
        elif fqn:
            result.append(f"table:{fqn.split('.')[-1]}")
    return result


def _markdown_identifier(value: str) -> str:
    return _plain(value).replace("`", "\\`")


def _plain(value: object) -> str:
    text = "" if value is None else str(value)
    text = re.sub(r"[\r\n\t]+", " ", text)
    return html.escape(text, quote=False).strip()


async def analyze_and_generate(request: GenerateDraftRequest) -> BatchGenerateResponse:
    """AI 自动分析业务域并批量生成 skills.md

    流程：
    1. 用 LLM 分析表结构，识别业务域分组
    2. 对每个域，用 LLM 生成独立的 skills.md
    3. 返回所有生成的文档

    Args:
        request: 包含快照元数据的请求对象

    Returns:
        批量生成的文档列表
    """
    logger.info(
        "开始域分析+批量生成 snapshot_id=%d datasource_id=%d tables=%d",
        request.snapshot_id,
        request.datasource_id,
        len(request.tables_metadata),
    )

    # Step 1: 域分析
    domains = await _analyze_domains(request)
    logger.info("域分析完成，识别出 %d 个业务域", len(domains))

    # Step 2: 域文档之间没有依赖关系，受控并发生成并保持返回顺序。
    semaphore = asyncio.Semaphore(min(_MAX_DOMAIN_GENERATION_CONCURRENCY, len(domains)))

    async def generate_domain_doc(domain: DomainGroup) -> DomainDoc:
        async with semaphore:
            logger.info("生成域文档: %s (表: %s)", domain.domain_name, ", ".join(domain.table_names))
            return await _generate_domain_doc(request, domain)

    # TaskGroup 在任一域失败时会取消其余域任务，避免请求已经失败后仍继续消耗 LLM 配额。
    async with asyncio.TaskGroup() as task_group:
        tasks = [task_group.create_task(generate_domain_doc(domain)) for domain in domains]
    docs = [task.result() for task in tasks]

    logger.info("批量生成完成，共 %d 份文档", len(docs))

    return BatchGenerateResponse(docs=docs, total_domains=len(docs))


async def _analyze_domains(request: GenerateDraftRequest) -> list[DomainGroup]:
    """用 LLM 分析表结构，识别业务域分组"""
    prompt = render_template_file(
        _DOMAIN_ANALYSIS_TEMPLATE,
        tables=request.tables_metadata,
        foreign_keys=request.foreign_keys,
    )

    response_text = await call_llm(
        system_prompt=_DOMAIN_ANALYSIS_PROMPT,
        user_prompt=prompt,
    )

    result = _json_parser.parse(response_text)
    domains_data = result.get("domains", [])

    if not domains_data:
        # 降级：所有表归为一个域
        logger.warning("域分析返回空结果，降级为单域模式")
        return [DomainGroup(
            domain_name="全部数据表",
            table_names=[t.table_name for t in request.tables_metadata],
            reason="AI 域分析失败，合并为单个域",
        )]

    domains = [DomainGroup(**d) for d in domains_data]

    # 校验：确保所有表都被覆盖
    all_tables = {t.table_name for t in request.tables_metadata}
    covered_tables = {t for d in domains for t in d.table_names}
    missing = all_tables - covered_tables
    if missing:
        # 把遗漏的表追加到最后一个域
        logger.warning("域分析遗漏了 %d 张表，追加到最后一个域", len(missing))
        domains[-1].table_names.extend(missing)

    return domains


async def _generate_domain_doc(
    request: GenerateDraftRequest, domain: DomainGroup
) -> DomainDoc:
    """为单个业务域生成 skills.md"""
    # 过滤出该域的表元数据
    table_set = set(domain.table_names)
    domain_tables = [t for t in request.tables_metadata if t.table_name in table_set]
    domain_fks = [
        fk for fk in request.foreign_keys
        if fk.get("source_table") in table_set or fk.get("target_table") in table_set
    ]

    # 渲染 Prompt 并调用 LLM
    prompt = render_template_file(
        _SKILLS_MD_TEMPLATE,
        tables=domain_tables,
        foreign_keys=domain_fks,
        indexes=request.indexes,
    )

    content = await call_llm(system_prompt=_SYSTEM_PROMPT, user_prompt=prompt)

    # 检查无注释字段
    warnings = _check_missing_comments(domain_tables)

    return DomainDoc(
        title=f"{domain.domain_name} skills.md",
        content=content,
        table_names=domain.table_names,
        warnings=warnings,
    )
