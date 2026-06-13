"""Token 预算控制模块

使用 tiktoken 计算 Token 数，按优先级裁剪超出预算的内容。
优先级（从高到低）：skills(1500) = schema(1500) > few-shot(800) > context(500) > confidence(200)
总预算：5000 Token（Qwen 模型上下文充足，skills 结构化后信息密度更高）

安全修复：变量名对齐实际传入的变量名。
- schema_context: RAG 召回的 schema 上下文（原 schema）
- skills: skills.md 知识库
- conversation_history: 对话历史（原 context）
- field_confidence: 字段置信度（原 confidence）
"""

import logging

logger = logging.getLogger(__name__)

# 各部分的 Token 预算分配
# 安全修复：变量名对齐实际传入的变量名
TOKEN_BUDGETS = {
    "schema_context": 1500,  # RAG 召回的 schema 上下文
    "skills": 1500,
    "few_shot": 800,
    "conversation_history": 500,  # 对话历史
    "field_confidence": 200,  # 字段置信度
}

TOTAL_BUDGET = 5000

# 优先级顺序（从低到高，裁剪时从最低优先级开始）
# 安全修复：变量名对齐实际传入的变量名
PRIORITY_ORDER = ["field_confidence", "conversation_history", "few_shot", "schema_context", "skills"]


def count_tokens(text: str) -> int:
    """计算文本的 Token 数（简化实现，按字符估算）

    生产环境应使用 tiktoken cl100k_base 编码。
    当前使用简化估算：中文约 1 字 = 1.5 token，英文约 4 字符 = 1 token。
    """
    if not text:
        return 0
    chinese_chars = sum(1 for c in text if '一' <= c <= '鿿')
    other_chars = len(text) - chinese_chars
    return int(chinese_chars * 1.5 + other_chars / 4)


def truncate_to_budget(text: str, max_tokens: int) -> str:
    """将文本截断到指定 Token 预算内"""
    if not text:
        return text
    current_tokens = count_tokens(text)
    if current_tokens <= max_tokens:
        return text
    # 按比例截断
    ratio = max_tokens / current_tokens
    cut_length = int(len(text) * ratio * 0.9)  # 留 10% 余量
    return text[:cut_length] + "\n... (已截断)"


def apply_token_budget(variables: dict[str, str]) -> dict[str, str]:
    """对变量内容应用 Token 预算控制

    如果所有变量的总 Token 数超过 TOTAL_BUDGET，
    从最低优先级的变量开始截断，直到总量回到预算内。

    Args:
        variables: 变量名 → 内容的字典

    Returns:
        截断后的变量字典
    """
    # 计算各变量的 Token 数
    token_counts = {}
    total_tokens = 0
    for key, value in variables.items():
        tokens = count_tokens(value) if value else 0
        token_counts[key] = tokens
        total_tokens += tokens

    if total_tokens <= TOTAL_BUDGET:
        return variables

    logger.warning(
        "Prompt 总 Token 数 %d 超出预算 %d，开始裁剪",
        total_tokens, TOTAL_BUDGET,
    )

    result = dict(variables)
    current_total = total_tokens

    # 从最低优先级开始裁剪，直到总量回到预算内
    for key in PRIORITY_ORDER:
        if current_total <= TOTAL_BUDGET:
            break
        if key not in result or not result[key]:
            continue
        current = token_counts.get(key, 0)
        budget = TOKEN_BUDGETS.get(key, 500)
        if current > budget:
            # 需要裁剪的量
            excess = current - budget
            result[key] = truncate_to_budget(result[key], budget)
            current_total -= excess
            logger.info("裁剪变量 %s: %d → %d tokens", key, current, budget)

    return result
