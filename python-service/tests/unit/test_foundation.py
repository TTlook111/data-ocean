"""验证正式包的公开响应和运行配置，不以此替代后续 AI 业务验收。"""

from importlib.metadata import version
from uuid import UUID

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from dataocean_ai.api.app import create_app
from dataocean_ai.core.config import Settings


def test_health_and_unimplemented_business_boundary() -> None:
    """健康检查如实描述进程，尚未实现的旧业务路径不能意外进入正式应用。"""
    with TestClient(create_app()) as client:
        response = client.get("/health")
        assert response.status_code == 200
        assert response.headers["cache-control"] == "no-store"
        assert str(UUID(response.headers["x-trace-id"])) == response.headers["x-trace-id"]
        assert response.json() == {
            "code": 200,
            "message": "成功",
            "data": {"status": "UP", "service": "dataocean-ai", "version": version("dataocean-ai")},
        }
        assert client.post("/internal/v1/tasks").json()["code"] == 404001
        assert client.post("/health").json()["code"] == 405001
        trace = "00000000-0000-4000-8000-000000000001"
        assert client.get("/health", headers={"X-Trace-Id": trace}).headers["x-trace-id"] == trace
        assert (
            client.get("/health", headers={"X-Trace-Id": "invalid"}).headers["x-trace-id"]
            != "invalid"
        )


def test_config_reads_declared_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    """真实环境变量应经过同一类型转换，而不是绕过启动校验。"""
    monkeypatch.setenv("DATAOCEAN_AI_PORT", "18091")
    monkeypatch.setenv("DATAOCEAN_AI_LOG_LEVEL", "warning")
    settings = Settings.from_environment()
    assert settings.port == 18091
    assert settings.log_level == "WARNING"


def test_invalid_listening_port_stops_startup(monkeypatch: pytest.MonkeyPatch) -> None:
    """无效端口明确拒绝，不静默切换到另一套服务地址。"""
    monkeypatch.setenv("DATAOCEAN_AI_PORT", "0")
    with pytest.raises(ValidationError):
        Settings.from_environment()
