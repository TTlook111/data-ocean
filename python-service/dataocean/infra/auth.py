"""内部接口认证模块

提供统一的 X-Internal-Token 验证依赖，用于保护 /internal/* 路由。
Java 调用 Python 内部接口时必须携带此 token。
"""

import hmac
import logging

from fastapi import Depends, HTTPException, status
from fastapi.security import APIKeyHeader

from dataocean.core.config import settings

logger = logging.getLogger(__name__)

# 令牌的单一来源是 core.config.Settings，此处不存在默认值：
# 未配置或长度不足 32 会在 Settings 构造阶段直接让进程启动失败。
INTERNAL_TOKEN = settings.internal_token

# 定义 API Key header 提取器
_internal_token_header = APIKeyHeader(
    name="X-Internal-Token",
    auto_error=False,
    description="内部服务间调用认证 token"
)


async def verify_internal_token(
    token: str | None = Depends(_internal_token_header),
) -> str:
    """验证内部调用 token

    Args:
        token: 从 X-Internal-Token header 提取的 token

    Returns:
        验证通过的 token

    Raises:
        HTTPException: token 缺失或不匹配时返回 403
    """
    if not token:
        logger.warning("内部接口访问被拒绝：缺少 X-Internal-Token header")
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="内部接口禁止外部访问：缺少认证 token"
        )

    # 常量时间比较。先编码为 UTF-8 字节：header 值可能含非 ASCII 字符，
    # 而 hmac.compare_digest 对含非 ASCII 的 str 会抛 TypeError（会变成 500 而非 403）。
    if not hmac.compare_digest(token.encode("utf-8"), INTERNAL_TOKEN.encode("utf-8")):
        logger.warning("内部接口访问被拒绝：token 不匹配")
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="内部接口禁止外部访问：token 无效"
        )

    return token
