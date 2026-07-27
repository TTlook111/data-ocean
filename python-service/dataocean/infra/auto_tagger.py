"""标签自动推断器

基于列名模式自动推断 PII/业务域标签，
在元数据采集阶段为列打上标签候选，供管理员确认后生效。

属于 infra 中性层，只依赖 core.config。
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class TagCandidate:
    """标签推断候选结果"""
    tag_fqn: str
    confidence: float
    matched_pattern: str


# 标签推断规则：FQN → 正则模式列表
# 模式匹配列名（不区分大小写）
_TAG_PATTERNS: dict[str, list[str]] = {
    # PII 标签
    "PII.身份证号": [
        r"id_card", r"身份证", r"identity", r"sfz", r"zjhm",
    ],
    "PII.手机号": [
        r"phone", r"手机", r"电话", r"mobile", r"tel", r"sjhm",
    ],
    "PII.姓名": [
        r"(?:real|user|customer|member)_?name", r"姓名", r"真实名",
    ],
    "PII.邮箱": [
        r"email", r"邮箱", r"mail", r"dzyx",
    ],
    "PII.银行卡号": [
        r"bank_?card", r"银行卡", r"yhkh",
    ],
    "PII.地址": [
        r"address", r"地址", r"住址", r"dz",
    ],
    # 业务域标签
    "业务域.财务": [
        r"amount", r"金额", r"price", r"cost", r"revenue", r"income",
        r"expense", r"profit", r"fee", r"total", r"balance", r"je",
    ],
    "业务域.销售": [
        r"order", r"订单", r"sale", r"sell", r"customer", r"客户",
        r"product", r"商品", r"sku", r"dd",
    ],
    "业务域.人力": [
        r"employee", r"员工", r"staff", r"salary", r"工资", r"dept",
        r"部门", r"position", r"岗位", r"yg",
    ],
    "业务域.供应链": [
        r"supplier", r"供应商", r"inventory", r"库存", r"warehouse",
        r"物流", r"shipping", r"gys",
    ],
}

# 标签 FQN 到优先级（数值越大越优先，同列多标签时取高优先级）
_TAG_PRIORITY: dict[str, int] = {
    "PII.身份证号": 100,
    "PII.手机号": 90,
    "PII.姓名": 80,
    "PII.邮箱": 80,
    "PII.银行卡号": 95,
    "PII.地址": 70,
    "业务域.财务": 50,
    "业务域.销售": 50,
    "业务域.人力": 50,
    "业务域.供应链": 50,
}

# 预编译正则
_COMPILED_PATTERNS: dict[str, list[re.Pattern]] = {
    fqn: [re.compile(p, re.IGNORECASE) for p in patterns]
    for fqn, patterns in _TAG_PATTERNS.items()
}


def infer_tags(
    column_name: str,
    column_comment: str = "",
    data_type: str = "",
) -> list[TagCandidate]:
    """推断列的标签候选列表。

    Args:
        column_name: 列名
        column_comment: 列注释
        data_type: 数据类型

    Returns:
        匹配的标签候选列表，按置信度降序排列
    """
    text = f"{column_name} {column_comment}".lower()
    candidates: list[TagCandidate] = []

    for fqn, patterns in _COMPILED_PATTERNS.items():
        for pattern in patterns:
            if pattern.search(text):
                # 置信度：列名精确匹配 > 注释匹配
                if pattern.search(column_name.lower()):
                    confidence = 0.9
                else:
                    confidence = 0.6
                candidates.append(TagCandidate(
                    tag_fqn=fqn,
                    confidence=confidence,
                    matched_pattern=pattern.pattern,
                ))
                break  # 每个标签只匹配一次

    # 按置信度降序排列
    candidates.sort(key=lambda c: c.confidence, reverse=True)
    return candidates


def infer_tags_for_columns(
    columns: list[dict],
) -> dict[str, list[TagCandidate]]:
    """批量推断列标签。

    Args:
        columns: 列信息列表，每个元素包含 column_name, column_comment, data_type

    Returns:
        列名 → 标签候选列表的映射
    """
    result: dict[str, list[TagCandidate]] = {}
    for col in columns:
        name = col.get("column_name", "")
        comment = col.get("column_comment", "")
        dtype = col.get("data_type", "")
        tags = infer_tags(name, comment, dtype)
        if tags:
            result[name] = tags
    if result:
        logger.info("标签推断完成 匹配列数=%d 总标签数=%d", len(result), sum(len(v) for v in result.values()))
    return result


# Phase 3 #16: 基于采样值的 PII 检测正则
# 不同于 _TAG_PATTERNS（列名匹配），这些正则是匹配列中实际的**数据值**
_PII_SAMPLE_PATTERNS: dict[str, re.Pattern] = {
    "PII.手机号": re.compile(r'^1[3-9]\d{9}$'),
    "PII.邮箱": re.compile(r'^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$'),
    "PII.身份证号": re.compile(r'^\d{17}[\dXx]$'),
    "PII.银行卡号": re.compile(r'^\d{16,19}$'),
}


def detect_pii_from_samples(column_name: str, sample_values: list[str]) -> list[TagCandidate]:
    """基于采样数据值推断 PII 标签（Phase 3 #16）。

    与 infer_tags() 互补：infer_tags 基于列名模式匹配，本函数基于实际数据值匹配。
    两者结果应在调用方合并去重。

    Args:
        column_name: 列名（用于日志）
        sample_values: 采样值列表（来自 ColumnCollector 采集的 SELECT DISTINCT LIMIT 5 结果）

    Returns:
        匹配的标签候选列表，置信度固定为 0.85（基于实际值匹配 > 列名猜测）
    """
    candidates: list[TagCandidate] = []
    if not sample_values:
        return candidates

    for tag_fqn, pattern in _PII_SAMPLE_PATTERNS.items():
        matched_count = 0
        matched_pattern = pattern.pattern
        for value in sample_values[:20]:
            val_str = str(value).strip()
            if pattern.match(val_str):
                matched_count += 1

        # 至少 1 个采样值匹配才添加标签（防止单值误匹配）
        if matched_count > 0:
            # 多个采样值都匹配 → 置信度更高
            confidence = min(0.95, 0.85 + (matched_count - 1) * 0.05)
            candidates.append(TagCandidate(
                tag_fqn=tag_fqn,
                confidence=confidence,
                matched_pattern=matched_pattern,
            ))
            logger.info("采样值 PII 检测: %s → %s (匹配 %d/%d)",
                        column_name, tag_fqn, matched_count, min(20, len(sample_values)))

    return candidates
