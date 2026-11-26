"""以实际 HTTP 事件验证单次模型请求的记录、拒绝及用量口径。"""

import asyncio
from types import SimpleNamespace

import httpx
import httpx2
import pytest

from dataocean_ai.adapters import model_receipts
from dataocean_ai.adapters.model_receipts import (
    ModelRecordingError,
    model_call_hooks,
    model_call_scope,
)


@pytest.mark.parametrize(
    ("library", "protocol", "body", "input_tokens", "output_tokens"),
    [
        (httpx, "OPENAI_CHAT_COMPLETIONS", {"prompt_tokens": 12, "completion_tokens": 4}, 12, 4),
        (
            httpx2,
            "ANTHROPIC_MESSAGES",
            {
                "input_tokens": 12,
                "output_tokens": 4,
                "cache_read_input_tokens": 7,
                "cache_creation_input_tokens": 1,
            },
            20,
            4,
        ),
        (httpx, "OPENAI_EMBEDDINGS", {"total_tokens": 9}, 9, 0),
    ],
)
def test_each_provider_request_preserves_only_allowed_metadata(
    monkeypatch, library, protocol, body, input_tokens, output_tokens
):
    """每轮请求写独立回执，调用内容和鉴权头不进入平台诊断。"""
    writes = []

    async def write(identity, action, payload):
        """捕获诊断元数据，替代本机平台网络边界。"""
        writes.append((identity, action, payload))
        return True

    def provider(request):
        """模拟协议响应，应用仍可正常读取原始 JSON。"""
        assert writes[-1][1] == "start"
        return library.Response(200, json={"model": "provider-model", "usage": body})

    monkeypatch.setattr(model_receipts, "_write", write)
    config = SimpleNamespace(revision_id="8", protocol=protocol)

    async def request_twice():
        """用同一业务归属发出两个请求。"""
        with model_call_scope("QUERY", "sql-generate", "query-1"):
            async with library.AsyncClient(
                transport=library.MockTransport(provider), event_hooks=model_call_hooks(config)
            ) as client:
                for _ in range(2):
                    # 同一业务范围内的两次外发仍是两个调用。
                    response = await client.post(
                        "https://provider.invalid/model", json={"question": "private"}
                    )
                    assert response.json()["model"] == "provider-model"

    asyncio.run(request_twice())

    assert [item[1] for item in writes] == ["start", "finish", "start", "finish"]
    assert writes[0][0] == writes[1][0] != writes[2][0] == writes[3][0]
    for _, action, payload in writes:
        if action == "start":
            # 调用归属及配置修订均固定于实际任务。
            assert payload == {
                "module": "QUERY",
                "originId": "query-1",
                "nodeCode": "sql-generate",
                "revisionId": "8",
            }
        else:
            assert payload.keys() == {
                "failureCode",
                "modelName",
                "elapsedMs",
                "inputTokens",
                "outputTokens",
            }
            assert payload["inputTokens"] == input_tokens
            assert payload["outputTokens"] == output_tokens
            assert payload["modelName"] == "provider-model"
            assert payload["failureCode"] is None
    assert model_call_hooks(config) is None


def test_no_provider_request_when_registration_failed(monkeypatch):
    """开始回执不可用时，在外发边界阻止产生未记录的请求。"""

    async def unavailable(*_):
        """模拟平台写入临时失败。"""
        return False

    def provider(_):
        """任何触达提供方都应令用例失败。"""
        pytest.fail("未登记即发出了请求")

    monkeypatch.setattr(model_receipts, "_write", unavailable)
    config = SimpleNamespace(revision_id="8", protocol="OPENAI_EMBEDDINGS")

    async def send():
        """模拟正式知识节点开始向量化。"""
        with model_call_scope("KNOWLEDGE", "rag-embedding", "build-1"):
            async with httpx.AsyncClient(
                transport=httpx.MockTransport(provider), event_hooks=model_call_hooks(config)
            ) as client:
                with pytest.raises(ModelRecordingError):
                    # 前置失败不得透传任何请求内容。
                    await client.post("https://provider.invalid/embeddings")

    asyncio.run(send())
