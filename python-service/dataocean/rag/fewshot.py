"""Few-shot Example 自动检索与自学习闭环

参考 Vanna AI 和 DAIL-SQL 的设计：
1. 用户确认正确的 SQL 自动归入 few-shot 检索库
2. SQL 生成时检索相似历史查询作为 few-shot examples
3. 持续提升 SQL 生成准确率

存储在 Redis 中，按数据源隔离，7 天过期。
"""

from __future__ import annotations

import hashlib
import json
import logging
from typing import Any

from dataocean.infra.memory import _get_redis, _safe_execute

logger = logging.getLogger(__name__)


async def store_successful_query(
    datasource_id: int,
    question: str,
    sql: str,
    tables: list[str],
    intent: dict | None = None,
) -> None:
    """存储成功的查询到 few-shot 检索库

    当用户确认查询结果正确时调用，形成正反馈循环。

    Args:
        datasource_id: 数据源 ID
        question: 用户问题
        sql: 生成的 SQL
        tables: 使用的表
        intent: 查询意图（可选）
    """
    redis = await _get_redis()
    if redis is None:
        return

    key = f"agent:fewshot:{datasource_id}:examples"
    example = {
        "question": question,
        "sql": sql,
        "tables": tables,
        "intent": intent or {},
        "hash": hashlib.sha256(question.encode()).hexdigest()[:16],
    }

    try:
        # 去重：相同问题不重复存储
        existing = await _safe_execute(redis.lrange(key, 0, -1)) or []
        for item in existing:
            try:
                parsed = json.loads(item)
                if parsed.get("hash") == example["hash"]:
                    return  # 已存在
            except (json.JSONDecodeError, TypeError):
                continue

        # 存储（最多保留 50 条）
        async with redis.pipeline(transaction=True) as pipe:
            pipe.lpush(key, json.dumps(example, ensure_ascii=False))
            pipe.ltrim(key, 0, 49)
            pipe.expire(key, 604800)  # 7 天
            await pipe.execute()

        logger.info("Few-shot 入库 datasource_id=%d question=%s", datasource_id, question[:50])
    except Exception as e:
        logger.debug("Few-shot 入库失败: %s", e)


async def retrieve_fewshot_examples(
    datasource_id: int,
    question: str,
    tables: list[str],
    limit: int = 3,
) -> list[dict]:
    """检索相似的历史成功查询作为 few-shot examples

    匹配策略：
    1. 优先匹配使用相同表的查询
    2. 其次匹配问题文本相似的查询（基于关键词重叠）

    Args:
        datasource_id: 数据源 ID
        question: 当前用户问题
        tables: 当前查询涉及的表
        limit: 最大返回数量

    Returns:
        匹配的 few-shot examples 列表
    """
    redis = await _get_redis()
    if redis is None:
        return []

    key = f"agent:fewshot:{datasource_id}:examples"
    data = await _safe_execute(redis.lrange(key, 0, -1))
    if not data:
        return []

    # 解析所有 examples
    examples = []
    for item in data:
        try:
            examples.append(json.loads(item))
        except (json.JSONDecodeError, TypeError):
            continue

    if not examples:
        return []

    # 计算匹配分数
    question_lower = question.lower()
    table_set = set(t.lower() for t in tables)
    scored = []

    for ex in examples:
        score = 0.0
        ex_tables = set(t.lower() for t in ex.get("tables", []))

        # 表匹配加分（权重最高）
        table_overlap = len(table_set & ex_tables)
        if table_overlap > 0:
            score += table_overlap * 0.5

        # 问题子串重叠率加分（适配中文，不依赖空格分词）
        ex_question = ex.get("question", "").lower()
        # 取两个问题的较短者长度，计算公共子串比例
        shorter_len = min(len(question_lower), len(ex_question))
        if shorter_len > 0:
            # 简单重叠检测：较长文本包含较短文本的比例
            shorter, longer = (question_lower, ex_question) if len(question_lower) <= len(ex_question) else (ex_question, question_lower)
            overlap_chars = sum(1 for c in shorter if c in longer)
            overlap_ratio = overlap_chars / shorter_len
            if overlap_ratio > 0.5:
                score += min(overlap_ratio * 0.4, 0.4)

        if score > 0:
            scored.append((score, ex))

    # 按分数排序，取 top N
    scored.sort(key=lambda x: x[0], reverse=True)
    return [ex for _, ex in scored[:limit]]


def format_fewshot_prompt(examples: list[dict]) -> str:
    """将 few-shot examples 格式化为 prompt 片段

    使用 XML 标签包裹示例，提升 LLM 对示例边界的识别准确率。

    Args:
        examples: few-shot examples 列表

    Returns:
        格式化的 prompt 文本
    """
    if not examples:
        return ""

    lines = ["以下是类似查询的成功 SQL 示例，供参考：\n"]
    for i, ex in enumerate(examples, 1):
        question = ex.get("question", "")
        sql = ex.get("sql", "")
        lines.append(f"<example-{i}>")
        lines.append(f"问题: {question}")
        lines.append(f"```sql")
        lines.append(sql)
        lines.append(f"```")
        lines.append(f"</example-{i}>")
        lines.append("")

    return "\n".join(lines)
