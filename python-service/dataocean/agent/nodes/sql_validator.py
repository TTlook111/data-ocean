"""SQL 安全校验节点

调用 009 模块的 SQL 安全校验逻辑。
当 009 模块完整实现后，此节点将调用其内部函数进行 AST 校验。
当前阶段使用基础的关键词检查作为临时方案。
"""

from __future__ import annotations

import logging
import re

from ..state import AgentState

logger = logging.getLogger(__name__)

# 禁止的 SQL 关键词（非 SELECT 操作）
_FORBIDDEN_KEYWORDS = re.compile(
    r"\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|EXEC|EXECUTE)\b",
    re.IGNORECASE,
)

# 禁止的危险函数
_DANGEROUS_FUNCTIONS = re.compile(
    r"\b(SLEEP|BENCHMARK|LOAD_FILE|INTO\s+OUTFILE|INTO\s+DUMPFILE)\b",
    re.IGNORECASE,
)


async def run_sql_validator(state: AgentState) -> AgentState:
    """执行 SQL 安全校验"""
    generated_sql = state.get("generated_sql", "")
    task_id = state.get("task_id", "")

    logger.info("SQL 校验 task_id=%s sql=%s", task_id, generated_sql[:80])

    if not generated_sql.strip():
        return {
            **state,
            "validation_result": {
                "valid": False,
                "rewritten_sql": None,
                "violations": ["SQL 为空"],
                "level": "REJECT",
            },
            "error_message": "生成的 SQL 为空",
            "current_node": "SQL_VALIDATOR",
        }

    violations = []

    # 检查是否为 SELECT 语句
    stripped = generated_sql.strip().upper()
    if not stripped.startswith("SELECT"):
        violations.append("仅允许 SELECT 查询")

    # 检查禁止的关键词
    forbidden_match = _FORBIDDEN_KEYWORDS.search(generated_sql)
    if forbidden_match:
        violations.append(f"包含禁止的操作：{forbidden_match.group()}")

    # 检查危险函数
    dangerous_match = _DANGEROUS_FUNCTIONS.search(generated_sql)
    if dangerous_match:
        violations.append(f"包含危险函数：{dangerous_match.group()}")

    if violations:
        level = "DANGEROUS" if dangerous_match else "REJECT"
        logger.warning("SQL 校验不通过 task_id=%s violations=%s", task_id, violations)
        return {
            **state,
            "validation_result": {
                "valid": False,
                "rewritten_sql": None,
                "violations": violations,
                "level": level,
            },
            "error_message": f"SQL 安全校验不通过：{'; '.join(violations)}",
            "current_node": "SQL_VALIDATOR",
        }

    logger.info("SQL 校验通过 task_id=%s", task_id)

    return {
        **state,
        "validation_result": {
            "valid": True,
            "rewritten_sql": generated_sql,
            "violations": [],
            "level": "PASS",
        },
        "current_node": "SQL_VALIDATOR",
    }
