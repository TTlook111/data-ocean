"""用户友好错误消息映射

将技术错误映射为面向用户的中文友好提示。
所有面向用户的错误消息统一在此定义，避免散落在各模块中。
"""

from enum import Enum


class ErrorCode(str, Enum):
    """错误码枚举"""

    LLM_TIMEOUT = "LLM_TIMEOUT"
    LLM_RATE_LIMIT = "LLM_RATE_LIMIT"
    LLM_UNAVAILABLE = "LLM_UNAVAILABLE"
    MILVUS_UNAVAILABLE = "MILVUS_UNAVAILABLE"
    SQL_TIMEOUT = "SQL_TIMEOUT"
    SQL_SYNTAX = "SQL_SYNTAX"
    BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
    CANCELLED = "CANCELLED"
    CONNECTION_POOL_EXHAUSTED = "CONNECTION_POOL_EXHAUSTED"
    SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
    UNKNOWN = "UNKNOWN"


# 技术错误码 → 用户友好中文消息
ERROR_MESSAGES: dict[str, str] = {
    ErrorCode.LLM_TIMEOUT: "AI 服务繁忙，请稍后再试",
    ErrorCode.LLM_RATE_LIMIT: "AI 服务繁忙，请稍后再试",
    ErrorCode.LLM_UNAVAILABLE: "AI 服务暂时不可用，请稍后再试",
    ErrorCode.MILVUS_UNAVAILABLE: "知识库暂时不可用，已使用降级方案",
    ErrorCode.SQL_TIMEOUT: "查询超时，请缩小查询范围",
    ErrorCode.SQL_SYNTAX: "生成的查询语句有误，请换个问法",
    ErrorCode.BUDGET_EXHAUSTED: "处理时间超出限制，请简化问题",
    ErrorCode.CANCELLED: "查询已取消",
    ErrorCode.CONNECTION_POOL_EXHAUSTED: "系统繁忙，请稍后再试",
    ErrorCode.SERVICE_UNAVAILABLE: "服务暂时不可用，请稍后再试",
    ErrorCode.UNKNOWN: "服务暂时不可用，请稍后再试",
}


def get_user_message(error_code: str | ErrorCode) -> str:
    """根据错误码获取用户友好消息

    Args:
        error_code: 错误码（字符串或枚举）

    Returns:
        用户友好的中文错误提示
    """
    if isinstance(error_code, ErrorCode):
        error_code = error_code.value
    return ERROR_MESSAGES.get(error_code, ERROR_MESSAGES[ErrorCode.UNKNOWN])


def classify_exception(exc: Exception) -> str:
    """根据异常类型分类为错误码

    Args:
        exc: 异常实例

    Returns:
        对应的错误码字符串
    """
    from dataocean.core.exceptions import LLMException
    from dataocean.resilience.timeout_budget import BudgetExhaustedException

    error_msg = str(exc).lower()

    if isinstance(exc, BudgetExhaustedException):
        return ErrorCode.BUDGET_EXHAUSTED

    if isinstance(exc, LLMException):
        if "timeout" in error_msg or "超时" in error_msg:
            return ErrorCode.LLM_TIMEOUT
        if "rate" in error_msg or "限流" in error_msg:
            return ErrorCode.LLM_RATE_LIMIT
        return ErrorCode.LLM_UNAVAILABLE

    if "milvus" in error_msg or "向量" in error_msg:
        return ErrorCode.MILVUS_UNAVAILABLE

    if "timeout" in error_msg or "超时" in error_msg:
        return ErrorCode.SQL_TIMEOUT

    if "pool" in error_msg or "连接池" in error_msg:
        return ErrorCode.CONNECTION_POOL_EXHAUSTED

    if "cancel" in error_msg or "取消" in error_msg:
        return ErrorCode.CANCELLED

    return ErrorCode.UNKNOWN
