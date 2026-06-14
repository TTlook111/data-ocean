"""问题改写节点

解析时间表达式、消解多轮指代、提取查询意图，
将用户原始问题改写为结构化查询。

支持 managed 模板优先 + 本地 .j2 降级模式。
"""

from __future__ import annotations

import logging
from datetime import date
from pathlib import Path

from dataocean.infra.llm import call_llm
from dataocean.infra.parsers import JsonBlockOutputParser
from dataocean.prompt.renderer import render_template_file
from dataocean.prompt.service import render_prompt_with_metadata

from ..prompt_tracking import record_prompt_version
from ..state import AgentState

logger = logging.getLogger(__name__)

_PROMPTS_DIR = Path(__file__).parent.parent / "prompts"
_QUERY_REWRITE_TEMPLATE = _PROMPTS_DIR / "query_rewrite.j2"
# 解析 LLM 返回的改写意图 JSON（不允许 null，无法解析则降级到原始问题）
_json_parser = JsonBlockOutputParser(allow_null=False)

# managed 模板编码（对应 Java 端 prompt_template.template_code）
_MANAGED_TEMPLATE_CODE = "intent_recognition"
# system prompt（managed 模板内容已包含完整指令，system prompt 简化即可）
_SYSTEM_PROMPT = "你是一个数据查询意图分析专家。请严格按照要求输出 JSON 格式。"


async def run_query_rewriter(state: AgentState) -> AgentState:
    """执行问题改写：术语扩展 → 填充模板 → 调用 Qwen → 解析 JSON → 写入 state

    优先从 Java 端获取 managed 模板，失败时降级到本地 .j2 文件。
    """
    question = state.get("question", "")
    task_id = state.get("task_id", "")
    conversation_history = state.get("conversation_history", [])
    glossary_terms = state.get("glossary_terms") or []

    # 术语扩展：用术语同义词扩展用户问题，提高 RAG 召回率
    glossary_hint = _build_glossary_hint(question, glossary_terms)
    if glossary_hint:
        logger.info("术语匹配 task_id=%s hint=%s", task_id, glossary_hint[:100])

    logger.info("问题改写 task_id=%s question=%s", task_id, question[:50])

    # 构造模板变量（managed 模板和本地模板共用）
    variables = {
        "current_date": date.today().isoformat(),
        "current_time": date.today().isoformat(),  # V24 模板用 current_time
        "question": question,
        "conversation_history": conversation_history,
        "context": conversation_history,  # V24 模板用 context
        "user_memory": None,
        "glossary_hint": glossary_hint,
    }

    # 优先使用 managed 模板，失败时降级到本地 .j2 文件
    prompt_version = 0
    try:
        prompt, prompt_version = await render_prompt_with_metadata(
            _MANAGED_TEMPLATE_CODE, variables
        )
        logger.info("使用 managed 模板 %s version=%d", _MANAGED_TEMPLATE_CODE, prompt_version)
    except Exception as e:
        logger.warning("managed 模板 %s 获取失败，降级到本地 .j2: %s", _MANAGED_TEMPLATE_CODE, e)
        prompt = render_template_file(
            _QUERY_REWRITE_TEMPLATE,
            current_date=date.today().isoformat(),
            question=question,
            conversation_history=conversation_history,
            user_memory=None,
        )

    state = record_prompt_version(state, "query_rewrite", prompt_version)

    try:
        response_text = await call_llm(
            system_prompt=_SYSTEM_PROMPT,
            user_prompt=prompt,
        )
        result = _json_parser.parse(response_text)
    except Exception as e:
        logger.warning("问题改写 LLM 解析失败 task_id=%s error=%s，使用原始问题", task_id, e)
        # 降级：直接使用原始问题
        return {
            "rewritten_query": question,
            "extracted_intent": {
                "dimensions": [],
                "metrics": [],
                "filters": [],
                "time_range": None,
                "sort": None,
                "is_ambiguous": False,
            },
            "current_node": "QUERY_REWRITER",
        }

    rewritten_query = result.get("rewritten_query", question)
    intent = result.get("intent", {})
    is_ambiguous = result.get("is_ambiguous", False)
    clarification_hint = result.get("clarification_hint")

    # 如果问题过于模糊且无法自动消解，终止流程
    if is_ambiguous and clarification_hint:
        return {
            "rewritten_query": rewritten_query,
            "extracted_intent": intent,
            "error_message": clarification_hint,
            "current_node": "QUERY_REWRITER",
        }

    logger.info("问题改写完成 task_id=%s rewritten=%s", task_id, rewritten_query[:80])

    return {
        "rewritten_query": rewritten_query,
        "extracted_intent": intent,
        "current_node": "QUERY_REWRITER",
    }


def _build_glossary_hint(question: str, glossary_terms: list[dict]) -> str:
    """用术语同义词匹配用户问题，生成术语提示。

    如果用户问题中包含术语名称或同义词，则将该术语的物理列名等信息
    作为提示附加到问题中，提高 RAG 召回率。

    Args:
        question: 用户原始问题
        glossary_terms: 术语列表，每个元素包含 name, synonyms, description 等

    Returns:
        术语提示字符串，无匹配时返回空字符串
    """
    if not glossary_terms:
        return ""

    question_lower = question.lower()
    matched_hints: list[str] = []

    for term in glossary_terms:
        term_name = term.get("name", "").lower()
        display_name = term.get("displayName", "").lower()
        synonyms_raw = term.get("synonyms") or "[]"
        description = term.get("description", "")

        # 解析同义词
        if isinstance(synonyms_raw, str):
            try:
                import json
                synonyms = [s.lower() for s in json.loads(synonyms_raw)]
            except (json.JSONDecodeError, TypeError):
                synonyms = []
        elif isinstance(synonyms_raw, list):
            synonyms = [str(s).lower() for s in synonyms_raw]
        else:
            synonyms = []

        # 检查是否匹配
        all_names = [term_name, display_name] + synonyms
        matched = any(name and name in question_lower for name in all_names)

        if matched:
            hint = f"术语「{term.get('displayName') or term.get('name')}」"
            if description:
                hint += f"：{description}"
            # 附加关联列信息（如果有 entity_metadata）
            metadata = term.get("entityMetadata")
            if metadata and isinstance(metadata, dict):
                related_columns = metadata.get("related_columns")
                if related_columns:
                    hint += f"，对应物理列：{related_columns}"
            matched_hints.append(hint)

    if not matched_hints:
        return ""

    return "用户问题涉及以下业务术语：" + "；".join(matched_hints)
    }
