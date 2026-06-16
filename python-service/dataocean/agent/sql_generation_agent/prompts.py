"""SQL Generator Agent Prompt 构造

构造 Agent 的 system prompt 和 user message，
不将全部 schema_context 无脑塞入 prompt，
让 Agent 通过工具按需读取，减少上下文膨胀。
"""

from __future__ import annotations

import json
from typing import Any

# ── System Prompt ─────────────────────────────────────────────

SYSTEM_PROMPT = """\
你是 DataOcean 的 MySQL SELECT SQL 生成 Agent。

## 约束规则
- 你只能生成单条 SELECT 查询。
- 你不能生成 INSERT、UPDATE、DELETE、DROP、ALTER、TRUNCATE、CREATE 等语句。
- 你不能执行 SQL，正式执行由后续 SQL_Executor 负责。
- 你必须优先使用工具读取已召回的 schema、Join Path、指标口径和字段防坑说明。
- 如果缺少 Join Path，不要编造 ON 条件。
- 如果用户问题无法基于已有 schema_context 回答，返回空 SQL 并说明原因。
- 正式安全校验由后续 SQL_Validator 负责，但你应尽量生成可通过校验的 SQL。

## 输出要求
- 返回结构化的 SQL 生成结果，包含 sql、explanation、confidence 和 assumptions。
- explanation 应说明选择了哪些表、使用了什么 Join、过滤条件和指标口径依据。
"""


def build_user_message(state: dict[str, Any]) -> str:
    """从 AgentState 构造 user message

    只放入问题、意图、对话历史等关键信息，
    schema 上下文由 Agent 通过工具按需读取。

    Args:
        state: 当前 AgentState 字典

    Returns:
        格式化的 user message 字符串
    """
    parts: list[str] = []

    # 基本问题信息
    question = state.get("question", "")
    parts.append(f"## 用户问题\n{question}")

    rewritten = state.get("rewritten_query", "")
    if rewritten and rewritten != question:
        parts.append(f"## 改写后的查询\n{rewritten}")

    # 查询意图
    intent = state.get("extracted_intent", {})
    if intent:
        parts.append(f"## 查询意图\n{json.dumps(intent, ensure_ascii=False)}")

    # 对话历史
    history = state.get("conversation_history", [])
    if history:
        history_text = "\n".join(
            f"- {msg.get('role', 'user')}: {msg.get('content', '')}"
            for msg in history[-3:]  # 最近 3 轮
        )
        parts.append(f"## 对话历史\n{history_text}")

    # 置信度摘要
    confidence = state.get("confidence_scores", {})
    if confidence:
        parts.append(f"## 字段置信度\n{json.dumps(confidence, ensure_ascii=False)}")

    # 重试信息
    retry_count = state.get("retry_count", 0)
    if retry_count > 0:
        parts.append(f"## 当前重试次数\n{retry_count}")
        previous_sql = state.get("generated_sql", "")
        if previous_sql:
            parts.append(f"## 上一轮 SQL\n```sql\n{previous_sql}\n```")
        error_message = state.get("error_message", "")
        if error_message:
            parts.append(f"## 上一轮错误\n{error_message}")

    parts.append(
        "请先使用工具获取需要的 schema 上下文、Join Path、指标口径和字段说明，"
        "然后生成最终的 SQL。"
    )

    return "\n\n".join(parts)
