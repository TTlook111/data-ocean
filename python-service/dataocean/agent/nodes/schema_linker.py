"""Schema Linking 节点

参考 MAC-SQL 和 CHESS 的 Selector Agent 设计：
在 RAG 检索后、SQL 生成前，通过 LLM 过滤无关表/列，减少 context 噪声。

这可以显著提升 SQL 生成准确率，尤其在 schema 较大时效果明显。
"""

from __future__ import annotations

import json
import logging

from dataocean.infra.llm import call_llm
from dataocean.infra.parsers import JsonBlockOutputParser

from ..state import AgentState

logger = logging.getLogger(__name__)

_json_parser = JsonBlockOutputParser(allow_null=False)


async def run_schema_linker(state: AgentState) -> AgentState:
    """Schema Linking：过滤无关表/列，精简 SQL 生成的输入 context

    流程：
    1. 获取 RAG 召回的 schema context
    2. 调用 LLM 分析问题与 schema 的关联
    3. 过滤掉无关的表和列
    4. 返回精简后的 schema context
    """
    task_id = state.get("task_id", "")
    question = state.get("rewritten_query", "") or state.get("question", "")
    schema_context = state.get("schema_context", [])

    if not schema_context:
        return {"current_node": "SCHEMA_LINKER"}

    # Phase 1 #4: 阈值从 3 提高到 8（Death of Schema Linking, arXiv:2408.07702）
    # 50 张表内现代 LLM 处理无关列能力强，过早裁剪可能误删有用列
    if len(schema_context) <= 8:
        logger.info("Schema Linking 跳过（表数 <= 8）task_id=%s", task_id)
        return {"current_node": "SCHEMA_LINKER"}

    logger.info("Schema Linking 开始 task_id=%s tables=%d", task_id, len(schema_context))

    try:
        pruned = await _prune_schema(question, schema_context)
        if pruned and len(pruned) < len(schema_context):
            logger.info("Schema Linking 完成 task_id=%s %d → %d tables",
                        task_id, len(schema_context), len(pruned))
            return {"schema_context": pruned, "current_node": "SCHEMA_LINKER"}
        else:
            logger.info("Schema Linking 未减少表数，保持原样 task_id=%s", task_id)
            return {"current_node": "SCHEMA_LINKER"}
    except Exception as e:
        logger.warning("Schema Linking 失败，保持原样 task_id=%s error=%s", task_id, e)
        return {"current_node": "SCHEMA_LINKER"}


async def _prune_schema(question: str, schema_context: list[dict]) -> list[dict]:
    """调用 LLM 分析问题与 schema 的关联，返回精简后的 schema

    Args:
        question: 用户问题
        schema_context: RAG 召回的 schema 列表

    Returns:
        精简后的 schema 列表
    """
    # 构建 schema 摘要（只包含表名和关键列）
    schema_summary = _build_schema_summary(schema_context)

    # Phase 2 #7: 列级 Schema Linking — 扩展 prompt 同时返回相关表和列
    prompt = (
        f"用户问题：[QUERY_START]{question}[QUERY_END]\n\n"
        f"以下是数据库的表结构信息（含列名和置信度等级）：\n{schema_summary}\n\n"
        f"请分析这个问题需要用到哪些表和列，哪些是无关的。\n"
        f"返回 JSON 格式：{{\"relevant_tables\": [\"表名1\"], "
        f"\"relevant_columns\": {{\"表名1\": [\"列名a\", \"列名b\"]}}, \"reason\": \"原因\"}}\n"
        f"只返回 JSON，不要其他内容。"
    )

    try:
        response = await call_llm(
            system_prompt="你是 Schema Linking 专家。分析用户问题需要哪些数据库表和列，过滤无关表和列。",
            user_prompt=prompt,
            temperature=0.1,
        )
        result = _json_parser.parse(response)
        relevant_tables = set(t.lower() for t in result.get("relevant_tables", []))
        relevant_columns = {
            str(table_name).lower(): columns
            for table_name, columns in result.get("relevant_columns", {}).items()
        }

        if not relevant_tables:
            return schema_context

        # Phase 2 #7: 过滤表 + 列
        pruned = []
        for schema in schema_context:
            schema_tables = _schema_table_names(schema)
            if schema_tables & relevant_tables:
                item = dict(schema)  # 浅拷贝保留原字段
                # 如果有列级裁剪结果，过滤 columns
                tbl_cols_lower = {
                    str(column).lower().rsplit(".", 1)[-1]
                    for table_name in schema_tables
                    for column in relevant_columns.get(table_name, [])
                }
                if tbl_cols_lower and schema.get("columns"):
                    item["columns"] = [
                        c for c in schema["columns"]
                        if c.get("name", "").lower().rsplit(".", 1)[-1] in tbl_cols_lower
                    ]
                pruned.append(item)

        # 至少保留一个表
        return pruned if pruned else schema_context[:1]
    except Exception as e:
        logger.warning("Schema Linking LLM 调用失败: %s", e)
        return schema_context


def _build_schema_summary(schema_context: list[dict]) -> str:
    """构建 schema 摘要文本，用于 LLM 分析。

    Phase 2 #9: 含置信度等级标注（H/M/L）
    Phase 3: 含表间关系信息（FOREIGN_KEY → JOIN 推荐、LINEAGE → 表关联、DERIVED_FROM → 字段派生）
    """
    lines = []
    for schema in schema_context:
        table_name = schema.get("table_name", "")
        related_tables = schema.get("related_tables") or []
        related_columns = schema.get("related_columns") or []
        chunk_type = schema.get("chunk_type", "")
        chunk_text = schema.get("chunk_text", "")[:200]
        columns = schema.get("columns", [])

        line = f"- 表 {table_name}（类型: {chunk_type}）"
        if related_tables:
            line += f"，关联表: {', '.join(str(name) for name in related_tables)}"
        if columns:
            col_names = []
            for c in columns:
                name = c.get("name", "") if isinstance(c, dict) else str(c)
                trust = c.get("trust_score", 0) if isinstance(c, dict) else 0
                level = "H" if trust >= 70 else ("M" if trust >= 40 else "L")
                col_names.append(f"{name}[{level}]")
            line += f"，字段: {', '.join(col_names)}"
        elif related_columns:
            line += f"，关联字段: {', '.join(str(name) for name in related_columns)}"
        if chunk_text:
            line += f"\n  摘要: {chunk_text}"

        # Phase 3: 表间关系信息
        foreign_keys: list = schema.get("foreign_keys", [])
        lineages: list = schema.get("lineages", [])
        derived_froms: list = schema.get("derived_froms", [])

        if foreign_keys:
            fk_lines = []
            for fk in foreign_keys[:5]:  # 最多展示 5 条外键
                target = fk.get("targetEntity", {})
                rel_meta = fk.get("relationMetadata", "")
                fk_lines.append(f"    → JOIN {target.get('name', '?')} (外键: {_extract_rel_desc(rel_meta)})")
            line += "\n  外键关系:\n" + "\n".join(fk_lines)

        if lineages:
            lin_lines = []
            for lin in lineages[:3]:
                src = lin.get("sourceEntity", {})
                tgt = lin.get("targetEntity", {})
                src_name = src.get("name", "?") if src else "?"
                tgt_name = tgt.get("name", "?") if tgt else "?"
                lin_lines.append(f"    → 血缘: {src_name} → {tgt_name}")
            line += "\n  血缘关系:\n" + "\n".join(lin_lines)

        if derived_froms:
            der_lines = []
            for d in derived_froms[:5]:
                src = d.get("sourceEntity", {})
                tgt = d.get("targetEntity", {})
                src_name = src.get("name", "?") if src else "?"
                tgt_name = tgt.get("name", "?") if tgt else "?"
                rel_meta = d.get("relationMetadata", "")
                expr = _extract_rel_meta_field(rel_meta, "expression")
                expr_str = f" = {expr}" if expr else ""
                der_lines.append(f"    → 派生: {src_name} → {tgt_name}{expr_str}")
            line += "\n  列派生关系:\n" + "\n".join(der_lines)

        lines.append(line)

    return "\n".join(lines)


def _schema_table_names(schema: dict) -> set[str]:
    """返回 schema 条目涉及的所有表，兼容旧的单表 metadata。"""
    values = [schema.get("table_name"), *(schema.get("related_tables") or [])]
    return {str(value).lower() for value in values if value}


def _extract_rel_desc(relation_metadata: str) -> str:
    """从 relation_metadata JSON 中提取简短描述"""
    return _extract_rel_meta_field(relation_metadata, "column_name") or "关联"


def _extract_rel_meta_field(meta_str: str, field: str) -> str | None:
    """从 relation_metadata JSON 字符串中提取指定字段值"""
    if not meta_str:
        return None
    try:
        import json as _json
        meta = _json.loads(meta_str)
        return meta.get(field)
    except Exception:
        return None
