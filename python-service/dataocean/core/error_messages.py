"""统一错误消息管理

所有用户可见的错误消息在此集中定义，避免技术细节泄露到前端。
"""

# LLM 相关
LLM_TIMEOUT = "AI 服务响应超时，请简化问题后重试"
LLM_UNAVAILABLE = "AI 服务暂时不可用，请稍后再试"
LLM_RATE_LIMITED = "AI 服务繁忙，请稍后再试"

# SQL 生成
SQL_GENERATION_FAILED = "SQL 生成失败，请换个问法试试"
SQL_VALIDATION_FAILED = "SQL 安全校验未通过，请换个问法试试"

# 数据库执行
DB_TIMEOUT = "查询执行超时，请缩小查询范围后重试"
DB_CONNECTION_FAILED = "数据库连接失败，请联系管理员"
DB_POOL_EXHAUSTED = "系统繁忙，连接池已满，请稍后重试"
DB_SYNTAX_ERROR = "SQL 语法错误，请换个问法试试"

# Schema 召回
SCHEMA_NOT_FOUND = "未找到相关数据表，请确认数据源已完成元数据治理和知识库发布"

# 系统
SYSTEM_INTERNAL_ERROR = "系统内部错误，请稍后再试"
BUDGET_EXHAUSTED = "处理时间超出限制，请简化问题"
TASK_CANCELLED = "查询已取消"

# 降级
MILVUS_DEGRADED = "知识库暂时不可用，已使用降级方案，召回精度可能降低"


def sanitize_error(exception: Exception) -> str:
    """将异常分类映射到用户友好的错误消息，避免技术细节泄露。

    优先判断 LLMException（最具体的异常类型），再按消息关键词分类。
    DB 相关关键词更精确（sqlalchemy/pymysql/operational_error），避免与 LLM connection 混淆。

    Args:
        exception: 原始异常

    Returns:
        用户友好的错误消息
    """
    from dataocean.core.exceptions import LLMException
    msg = str(exception).lower()

    # LLM 异常（最具体，优先判断）
    if isinstance(exception, LLMException):
        if "timeout" in msg or "timed out" in msg:
            return LLM_TIMEOUT
        if "rate" in msg or "429" in msg:
            return LLM_RATE_LIMITED
        return LLM_UNAVAILABLE

    # 数据库异常（用更精确的关键词，避免误匹配 LLM 的 connection error）
    if "pool" in msg or "poolsize" in msg:
        return DB_POOL_EXHAUSTED
    if "operationalerror" in msg or "pymysql" in msg or "sqlalchemy" in msg:
        if "timeout" in msg or "timed out" in msg:
            return DB_TIMEOUT
        return DB_CONNECTION_FAILED
    if "access denied" in msg or "unknown database" in msg:
        return DB_CONNECTION_FAILED

    # 通用超时
    if "timeout" in msg or "timed out" in msg:
        return DB_TIMEOUT

    return SYSTEM_INTERNAL_ERROR
