"""通用工具函数

提供错误处理、JSON 解析等通用功能。
"""

import json
from typing import Any, Optional


def extract_error(error: Exception, fallback: str = "未知错误") -> str:
    """从异常中提取用户友好的错误消息

    Args:
        error: 异常对象
        fallback: 默认错误消息

    Returns:
        用户友好的错误消息
    """
    if hasattr(error, 'response') and hasattr(error.response, 'data'):
        msg = error.response.data.get('message')
        if msg:
            return msg
    return str(error) or fallback


def safe_json_parse(text: str, default: Any = None) -> Any:
    """安全的 JSON 解析，失败返回默认值

    Args:
        text: JSON 字符串
        default: 解析失败时的默认值

    Returns:
        解析结果或默认值
    """
    try:
        return json.loads(text)
    except (json.JSONDecodeError, TypeError):
        return default


def truncate_string(text: str, max_length: int = 100, suffix: str = "...") -> str:
    """截断字符串到指定长度

    Args:
        text: 原始字符串
        max_length: 最大长度
        suffix: 截断后缀

    Returns:
        截断后的字符串
    """
    if len(text) <= max_length:
        return text
    return text[:max_length - len(suffix)] + suffix


def flatten_dict(d: dict, parent_key: str = '', sep: str = '.') -> dict:
    """展平嵌套字典

    Args:
        d: 嵌套字典
        parent_key: 父级键名
        sep: 分隔符

    Returns:
        展平后的字典
    """
    items = []
    for k, v in d.items():
        new_key = f"{parent_key}{sep}{k}" if parent_key else k
        if isinstance(v, dict):
            items.extend(flatten_dict(v, new_key, sep).items())
        else:
            items.append((new_key, v))
    return dict(items)
