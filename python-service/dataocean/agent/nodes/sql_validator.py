"""SQL 安全校验节点

调用 009 模块的 AST 校验引擎和改写器，
对 AI 生成的 SQL 进行安全校验和权限注入。
"""

from __future__ import annotations

import logging

from dataocean.sandbox.validator import validate
from dataocean.sandbox.rewriter import rewrite

from .sql_executor import _extract_tables, _extract_columns  # Phase 1 #5: 复用表/列提取
from ..state import AgentState

logger = logging.getLogger(__name__)


# Phase 1 #5: SQL-to-Schema 幻觉检测辅助函数
def _detect_table_hallucination(sql: str, schema_context: list[dict]) -> set[str]:
    """检测 SQL 中是否使用了 schema_context 中不存在的表名（幻觉检测）。

    Args:
        sql: 生成的 SQL 语句
        schema_context: AgentState.schema_context（每项含 table_name, columns 等字段）

    Returns:
        set[str]: 不在 schema 中的表名（空集 = 无幻觉）
    """
    try:
        used_tables = _extract_tables(sql)
        available_tables = {item["table_name"] for item in schema_context if item.get("table_name")}
        return {t for t in used_tables if t not in available_tables}
    except Exception:
        # sqlglot 解析可能失败（语法错误的 SQL），不影响主校验流程
        return set()


async def run_sql_validator(state: AgentState) -> AgentState:
    """执行 SQL 安全校验：AST 规则链 + 权限改写"""
    generated_sql = state.get("generated_sql", "")
    task_id = state.get("task_id", "")
    permissions = state.get("user_permissions", {})

    logger.info("SQL 校验 task_id=%s sql=%s", task_id, generated_sql[:80])

    if not generated_sql.strip():
        return {
            "validation_result": {
                "valid": False,
                "rewritten_sql": None,
                "violations": ["SQL 为空"],
                "level": "REJECT",
            },
            "error_message": "生成的 SQL 为空",
            "current_node": "SQL_VALIDATOR",
        }

    # 提取权限信息
    allowed_tables = permissions.get("allowed_tables", permissions.get("allowedTables", []))
    table_scope_mode = permissions.get("table_scope_mode", permissions.get("tableScopeMode", "UNSPECIFIED"))

    # 第一步：AST 安全校验
    validation = validate(generated_sql, allowed_tables or None, table_scope_mode)

    # Phase 1 #5: SQL-to-Schema 幻觉检测（零额外 LLM 调用，纯 sqlglot AST 校验）
    # 检测 LLM 是否生成了不在 schema_context 中的表名
    schema_ctx = state.get("schema_context", [])
    if schema_ctx:
        hallucinated = _detect_table_hallucination(generated_sql, schema_ctx)
        if hallucinated:
            msg = f"幻觉检测：SQL 使用了不在 schema 中的表 {hallucinated}"
            logger.warning("SQL 幻觉 task_id=%s %s", task_id, msg)
            # 将幻觉信息附加到校验结果中，帮助后续自校正节点感知
            if validation.passed:
                return {
                    "validation_result": {
                        "valid": False, "rewritten_sql": None,
                        "violations": [msg], "level": "REJECT",
                    },
                    "error_message": msg,
                    "current_node": "SQL_VALIDATOR",
                }

    if not validation.passed:
        reasons = validation.reasons
        level = "DANGEROUS" if any("危险" in r for r in reasons) else "REJECT"
        logger.warning("SQL 校验不通过 task_id=%s reasons=%s", task_id, reasons)

        # 安全修复：普通校验失败时递增 retry_count，安全拒绝时不递增
        # DANGEROUS/REJECT 是安全拒绝，不应重试；普通失败可以重试
        retry_count = state.get("retry_count", 0)
        if level not in ("DANGEROUS", "REJECT"):
            retry_count += 1

        return {
            "validation_result": {
                "valid": False,
                "rewritten_sql": None,
                "violations": reasons,
                "level": level,
            },
            "error_message": f"SQL 安全校验不通过：{'; '.join(reasons)}",
            "retry_count": retry_count,
            "current_node": "SQL_VALIDATOR",
        }

    # 第二步：AST 改写（行过滤、列检查、LIMIT 注入）
    row_filters: dict[str, list[str]] = {}
    for rf in permissions.get("row_filters", permissions.get("rowFilters", [])):
        table = rf.get("table_name", rf.get("tableName", ""))
        condition = rf.get("condition", "")
        if table and condition:
            row_filters.setdefault(table, []).append(condition)

    denied_columns: dict[str, list[str]] = {}
    for col_ref in permissions.get("denied_columns", permissions.get("deniedColumns", [])):
        if "." in col_ref:
            table, col = col_ref.split(".", 1)
            denied_columns.setdefault(table, []).append(col)

    mask_columns: dict[str, list[str]] = {}
    mask_strategies: dict[str, str] = {}
    for mc in permissions.get("mask_columns", permissions.get("maskColumns", [])):
        table = mc.get("table_name", mc.get("tableName", ""))
        col = mc.get("column_name", mc.get("columnName", ""))
        mask_type = mc.get("mask_type", mc.get("maskType", ""))
        if table and col:
            mask_columns.setdefault(table, []).append(col)
            if mask_type:
                mask_strategies[f"{table.lower()}.{col.lower()}"] = mask_type

    rewrite_result = rewrite(
        sql=generated_sql,
        row_filters=row_filters or None,
        denied_columns=denied_columns or None,
        mask_columns=mask_columns or None,
        mask_strategies=mask_strategies or None,
    )

    if not rewrite_result.success:
        return {
            "validation_result": {
                "valid": False,
                "rewritten_sql": None,
                "violations": [rewrite_result.denied_reason],
                "level": "REJECT",
            },
            "error_message": rewrite_result.denied_reason,
            "current_node": "SQL_VALIDATOR",
        }

    logger.info("SQL 校验通过 task_id=%s rewritten=%s", task_id, rewrite_result.rewritten_sql[:80])

    return {
        "validation_result": {
            "valid": True,
            "rewritten_sql": rewrite_result.rewritten_sql,
            "violations": [],
            "level": "PASS",
            "masked_fields": rewrite_result.masked_fields,
        },
        "current_node": "SQL_VALIDATOR",
    }
