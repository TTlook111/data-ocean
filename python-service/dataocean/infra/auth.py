"""内部接口认证模块

提供统一的 X-Internal-Token 验证依赖，用于保护 /internal/* 路由。
Java 调用 Python 内部接口时必须携带此 token。
"""

import logging
import os

from fastapi import Depends, HTTPException, status
from fastapi.security import APIKeyHeader

logger = logging.getLogger(__name__)

# 从环境变量读取内部 token，默认值仅用于开发环境
INTERNAL_TOKEN = os.getenv("INTERNAL_TOKEN", "dataocean-internal-default")

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

    if token != INTERNAL_TOKEN:
        logger.warning("内部接口访问被拒绝：token 不匹配")
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="内部接口禁止外部访问：token 无效"
        )

    return token
