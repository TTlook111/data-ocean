"""校验 Java 服务身份及注入本服务的短期调用凭据。"""

from datetime import UTC, datetime, timedelta

import jwt
from fastapi import Header, HTTPException

from dataocean_ai.core.config import GovernanceSettings


def verify_java(authorization: str | None = Header(default=None)) -> None:
    """只接受固定签发者、接收方和签名算法的内部任务请求。"""
    public_file = GovernanceSettings.from_environment().java_public_key
    if not public_file:
        # 未配置可信身份时关闭内部能力。
        raise HTTPException(status_code=503, detail="SERVICE_NOT_CONFIGURED")
    if not authorization or not authorization.startswith("Bearer "):
        # 服务 JWT 不读 Cookie 也不接受浏览器用户身份。
        raise HTTPException(status_code=401, detail="INVALID_SERVICE_IDENTITY")
    token = authorization[7:]
    try:
        # 令牌头只用于核对预置信任版本，验签算法固定。
        if jwt.get_unverified_header(token).get("kid") != "dataocean-java":
            raise jwt.InvalidTokenError()
        jwt.decode(
            token,
            public_file.read_bytes(),
            algorithms=["RS256"],
            issuer="dataocean-java",
            audience="dataocean-python",
            subject="governance-dispatcher",
            options={"require": ["exp", "iat", "sub"]},
        )
    except (OSError, jwt.PyJWTError):
        # 认证错误不披露头、JWT 或私有路径。
        raise HTTPException(status_code=401, detail="INVALID_SERVICE_IDENTITY") from None


def worker_token() -> str:
    """按固定受众签发短期令牌，仅用于取用本次已登记任务的上下文。"""
    now = datetime.now(UTC)
    private_file = GovernanceSettings.from_environment().worker_private_key
    if private_file is None:
        # 直接调用执行入口时继续拒绝未配置身份。
        raise KeyError("AI_WORKER_PRIVATE_KEY")
    key = private_file.read_bytes()
    return jwt.encode(
        {
            "iss": "dataocean-python",
            "aud": "dataocean-java",
            "sub": "governance-worker",
            "iat": now,
            "exp": now + timedelta(minutes=5),
        },
        key,
        algorithm="RS256",
        headers={"kid": "dataocean-python"},
    )
