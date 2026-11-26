"""用合成模型响应验证模型接入协议与内部权限边界。"""

from pathlib import Path

import httpx
import httpx2
import pytest
from fastapi.testclient import TestClient

from dataocean_ai.api.app import create_app
from dataocean_ai.core.config import GovernanceSettings
from dataocean_ai.governance.security import verify_java


@pytest.mark.parametrize(
    ("protocol", "endpoint", "request_path"),
    [
        ("OPENAI_CHAT_COMPLETIONS", "https://model.example/v1", "/v1/chat/completions"),
        ("ANTHROPIC_MESSAGES", "https://model.example", "/v1/messages"),
        ("OPENAI_EMBEDDINGS", "https://model.example/v1", "/v1/embeddings"),
        ("COHERE_RERANK", "https://model.example/v2", "/v2/rerank"),
    ],
)
def test_connection_uses_protocol_adapter(
    monkeypatch: pytest.MonkeyPatch, protocol: str, endpoint: str, request_path: str
) -> None:
    """走与正式调用相同的 SDK，从合成服务验证消息格式和响应解析。"""
    transport_module = httpx2 if protocol == "ANTHROPIC_MESSAGES" else httpx
    base_client = transport_module.AsyncClient
    calls: list[str] = []

    def handle(request):
        """模拟提供方的真实协议响应，截断所有实际外发。"""
        calls.append(request.url.path)
        if protocol == "OPENAI_EMBEDDINGS":
            # 向量值符合 OpenAI 返回格式，可由现用 SDK 直接解析。
            return httpx.Response(200, json={"data": [{"index": 0, "embedding": [0.1, 0.6]}]})
        if protocol == "COHERE_RERANK":
            # 连接测试和真实调用都核对完整候选序号。
            return httpx.Response(
                200,
                json={
                    "results": [
                        {"index": 0, "relevance_score": 0.9},
                        {"index": 1, "relevance_score": 0.3},
                    ]
                },
            )
        if protocol == "OPENAI_CHAT_COMPLETIONS":
            # Chat Completions 返回 assistant 消息和已知用量。
            return httpx.Response(
                200,
                json={
                    "id": "fixture-call",
                    "object": "chat.completion",
                    "created": 1,
                    "model": "fixture",
                    "choices": [
                        {
                            "index": 0,
                            "message": {"role": "assistant", "content": '{"check":"ok"}'},
                            "finish_reason": "stop",
                        }
                    ],
                },
            )
        # Messages 返回文本块，结构化结果仍由正式适配器解析。
        return httpx2.Response(
            200,
            json={
                "id": "msg_fixture",
                "type": "message",
                "role": "assistant",
                "model": "fixture",
                "content": [{"type": "text", "text": '{"check":"ok"}'}],
                "stop_reason": "end_turn",
                "usage": {"input_tokens": 10, "output_tokens": 5},
            },
        )

    class SimulatedClient(base_client):
        """保留 SDK 所需的客户端类型，仅替换测试传输。"""

        def __init__(self, **kwargs):
            """挂载目标协议的内存响应。"""
            super().__init__(transport=transport_module.MockTransport(handle), **kwargs)

    def authenticated_java():
        """仅在合成协议测试中替代真实服务身份。"""

    monkeypatch.setattr(transport_module, "AsyncClient", SimulatedClient)
    if protocol in {"OPENAI_CHAT_COMPLETIONS", "OPENAI_EMBEDDINGS"}:
        # LangChain 会同时初始化同步 SDK。测试也为它固定内存传输，避免读取系统证书。
        sync_client = httpx.Client

        class SimulatedSyncClient(sync_client):
            """在无需网络的单测中承接 SDK 的同步客户端初始化。"""

            def __init__(self, **kwargs):
                """使用相同的合成响应，无外部地址访问。"""
                kwargs.setdefault("transport", httpx.MockTransport(handle))
                super().__init__(**kwargs)

        monkeypatch.setattr(httpx, "Client", SimulatedSyncClient)
    app = create_app()
    app.dependency_overrides[verify_java] = authenticated_java
    with TestClient(app) as api:
        # 真实内部路由使用当次配置选择适配器，凭据是测试字符串。
        response = api.post(
            "/internal/v1/models/connection-test",
            json={
                "revisionId": "1",
                "protocol": protocol,
                "endpoint": endpoint,
                "modelName": "fixture",
                # 给首次导入 SDK 和结构化模块预留时间，HTTP 仍由内存传输截获。
                "timeoutSeconds": 120,
                "apiKey": "fixture",
            },
        )
    assert response.status_code == 200
    assert calls == [request_path]
    assert response.json() == {"passed": True, "failureCode": None}


def test_unconfigured_identity_blocks_probe(monkeypatch: pytest.MonkeyPatch) -> None:
    """未配置机器身份时，在解析可能含秘密的请求体前停止。"""
    monkeypatch.delenv("AI_JAVA_PUBLIC_KEY", raising=False)
    monkeypatch.delenv("DATAOCEAN_AI_JAVA_PUBLIC_KEY_FILE", raising=False)
    with TestClient(create_app()) as api:
        # 缺少服务身份的调用不能取得探测能力。
        response = api.post("/internal/v1/models/connection-test", json={"apiKey": "fixture"})
    assert response.status_code == 503
    assert "fixture" not in response.text


def test_complete_startup_template(monkeypatch: pytest.MonkeyPatch) -> None:
    """模板可整份加载，涵盖启动参数和注明用途的基础设施示例。"""
    path = Path(__file__).resolve().parents[2] / ".env.example"
    entries = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        # 注释和空行不参与环境配置。
        if line and not line.startswith("#"):
            name, value = line.split("=", 1)
            entries[name] = value
            monkeypatch.setenv(name, value)
    assert set(entries) == {
        "AI_HOST",
        "AI_PORT",
        "AI_LOG_LEVEL",
        "AI_LOG_OUTPUT",
        "AI_QUEUE_URL",
        "AI_QUEUE_PREFIX",
        "AI_JAVA_PUBLIC_KEY",
        "AI_JAVA_URL",
        "AI_WORKER_PRIVATE_KEY",
        "AI_CHECKPOINT_URL",
        "AI_MILVUS_URI",
        "AI_MILVUS_TOKEN",
    }
    settings = GovernanceSettings.from_environment()
    assert settings.java_url and settings.redis_url
    assert settings.java_public_key and settings.worker_private_key
