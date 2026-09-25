"""/internal/* 内部令牌认证测试

这部分认证是 /internal/* 的唯一防线（这些接口能执行 SQL、写向量库、触发 Agent），
此前没有任何自动化覆盖。本文件覆盖两类行为：

1. 配置侧：INTERNAL_TOKEN 缺失或过短时，Settings 构造必须失败（进程拒绝启动），
   不允许静默退回到任何默认值。
2. 请求侧：/internal/* 缺令牌 / 错令牌返回 403，正确令牌放行；公开的 /health 不受影响。
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from dataocean.core.config import Settings, settings
from dataocean.main import app

# 受 verify_internal_token 保护、且不依赖 Milvus / Redis / LLM 的端点，
# 可用于断言"正确令牌 → 200"。
SAFE_PROTECTED_PATH = "/internal/query/health"

# 覆盖各 router 级依赖的 403 用例路径。认证依赖在端点函数体之前执行，
# 因此这些请求不会真正触碰外部依赖。
PROTECTED_PATHS = [
    "/internal/query/health",
    "/internal/rag/health",
    "/internal/sql/health",
    "/internal/prompts/some-template-code",
]


@pytest.fixture(scope="module")
def client() -> TestClient:
    """不带 lifespan 的测试客户端

    故意不使用 `with TestClient(app)`：那会触发 lifespan 并尝试连接
    Milvus / Redis 等外部服务。认证行为不依赖 lifespan。
    """
    return TestClient(app)


# --- 配置侧：启动校验 ---


def test_settings_rejects_missing_token(monkeypatch: pytest.MonkeyPatch) -> None:
    """未配置 INTERNAL_TOKEN 时必须构造失败，禁止静默使用默认值"""
    monkeypatch.delenv("INTERNAL_TOKEN", raising=False)
    with pytest.raises(ValidationError) as exc_info:
        Settings(_env_file=None)
    assert "INTERNAL_TOKEN" in str(exc_info.value)


def test_settings_rejects_blank_token(monkeypatch: pytest.MonkeyPatch) -> None:
    """INTERNAL_TOKEN 被设为空串或纯空白时同样必须失败"""
    monkeypatch.setenv("INTERNAL_TOKEN", "   ")
    with pytest.raises(ValidationError) as exc_info:
        Settings(_env_file=None)
    assert "INTERNAL_TOKEN" in str(exc_info.value)


def test_settings_rejects_short_token(monkeypatch: pytest.MonkeyPatch) -> None:
    """过短的令牌必须失败——短令牌在生产环境等于没有防线"""
    monkeypatch.setenv("INTERNAL_TOKEN", "too-short")
    with pytest.raises(ValidationError) as exc_info:
        Settings(_env_file=None)
    assert "32" in str(exc_info.value)


def test_settings_accepts_long_enough_token(monkeypatch: pytest.MonkeyPatch) -> None:
    """满足最小长度的令牌可以正常构造"""
    token = "x" * 32
    monkeypatch.setenv("INTERNAL_TOKEN", token)
    built = Settings(_env_file=None)
    assert built.internal_token == token


def test_repo_contains_no_usable_default_token() -> None:
    """仓库中不得存在可用的默认令牌（防止有人把默认值加回来）"""
    monkeypatch_free_settings = settings.internal_token
    assert monkeypatch_free_settings
    assert "dataocean-internal-default" not in monkeypatch_free_settings


# --- 请求侧：路由保护 ---


@pytest.mark.parametrize("path", PROTECTED_PATHS)
def test_missing_token_is_rejected(client: TestClient, path: str) -> None:
    """不带 X-Internal-Token 的请求必须被拒绝"""
    response = client.get(path)
    assert response.status_code == 403, f"{path} 在缺少令牌时返回了 {response.status_code}"


@pytest.mark.parametrize("path", PROTECTED_PATHS)
def test_wrong_token_is_rejected(client: TestClient, path: str) -> None:
    """令牌不匹配的请求必须被拒绝"""
    response = client.get(path, headers={"X-Internal-Token": "definitely-not-the-token"})
    assert response.status_code == 403, f"{path} 在令牌错误时返回了 {response.status_code}"


@pytest.mark.parametrize("path", PROTECTED_PATHS)
def test_non_ascii_token_is_rejected_with_403_not_500(client: TestClient, path: str) -> None:
    """含非 ASCII 字节的令牌必须返回 403，而不是因比较函数抛错变成 500

    HTTP 头值按 latin-1 解码，客户端完全可以发出字节 >= 0x80 的头，
    服务端拿到的就是含非 ASCII 码位的 str。hmac.compare_digest 对这类 str 会抛
    TypeError，若不先编码为 UTF-8 字节，403 就会退化成 500。

    这里必须用 bytes 发送：httpx 对 str 头值要求 ASCII，会直接拒绝。
    """
    non_ascii_header = "ÿ".encode("latin-1")  # 单个字节 0xFF
    response = client.get(path, headers={"X-Internal-Token": non_ascii_header})
    assert response.status_code == 403, f"{path} 含非 ASCII 令牌时返回了 {response.status_code}"


def test_valid_token_is_accepted(client: TestClient) -> None:
    """携带正确令牌的请求应被放行"""
    response = client.get(
        SAFE_PROTECTED_PATH,
        headers={"X-Internal-Token": settings.internal_token},
    )
    assert response.status_code == 200
    assert response.json()["status"] == "healthy"


# --- 覆盖性：防止下一个 router 再漏 ---


def test_every_internal_route_requires_the_token() -> None:
    """枚举 app 上全部 /internal 路由，断言每一个都挂了令牌校验依赖

    保护的写法是"每个 include_router 自己记得写 dependencies=[...]"，
    health router 就曾整条漏掉（/internal/health 无认证）。本用例是那道闸门：
    新增任何 /internal 路由却忘了挂依赖时，这里会直接失败。
    """
    from fastapi.routing import APIRoute

    from dataocean.infra.auth import verify_internal_token

    internal_routes = [
        route
        for route in app.routes
        if isinstance(route, APIRoute) and route.path.startswith("/internal")
    ]

    # 防止"扫不到就静默通过"：路由清单为空说明扫描逻辑或挂载方式变了，
    # 此时用例必须失败，而不是空跑绿灯。
    assert len(internal_routes) > 0, "未扫描到任何 /internal 路由，枚举逻辑已失效"

    unprotected = [
        route.path
        for route in internal_routes
        if verify_internal_token
        not in [getattr(dep, "dependency", dep) for dep in route.dependencies]
    ]

    assert unprotected == [], f"以下 /internal 路由缺少令牌校验依赖: {unprotected}"

    # 自检：上面的判定必须能区分"有依赖"和"没依赖"。
    # 拿公开的 /health 当反例，避免判定写错（例如恒为真）导致用例永远绿灯。
    public_health_route = next(
        route
        for route in app.routes
        if isinstance(route, APIRoute) and route.path == "/health"
    )
    assert verify_internal_token not in [
        getattr(dep, "dependency", dep) for dep in public_health_route.dependencies
    ], "自检失败：判定逻辑把没有依赖的 /health 也判成了有依赖"


# --- 已删除的未认证内部端点 ---


def test_internal_health_endpoint_no_longer_exists(client: TestClient) -> None:
    """/internal/health 已删除（曾无认证且泄漏底层异常原文），必须返回 404"""
    response = client.get("/internal/health")
    assert response.status_code == 404


# --- 公开端点不受影响 ---


def test_public_health_stays_unauthenticated(client: TestClient) -> None:
    """公开的 /health 必须保持无认证可用（Java 的 PythonHealthChecker 依赖它）"""
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
