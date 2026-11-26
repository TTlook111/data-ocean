"""
覆盖与业务相同的排序传输、失败分型及模型记账。
"""

import asyncio
import json

import httpx
import pytest

from dataocean_ai.adapters import model_receipts
from dataocean_ai.adapters.chat import ModelConfiguration, ModelFailure
from dataocean_ai.adapters.model_receipts import model_call_scope
from dataocean_ai.adapters.reranking import rerank


@pytest.mark.parametrize("mode", ["rank", "duplicate", "mixed_error"])
@pytest.mark.parametrize(
    ("protocol", "endpoint", "path"),
    [
        ("COHERE_RERANK", "https://model.example/v2", "/v2/rerank"),
        ("JSON_RERANK", "https://model.example/compat/v1/reranks", "/compat/v1/reranks"),
    ],
)
def test_rerank_uses_complete_provider_order_and_records_real_usage(
    monkeypatch, mode, protocol, endpoint, path
):
    """
    真实适配器按原候选顺序发出，并区分候选格式故障和计费单位。
    """
    calls = []
    receipts = []

    async def record(call, action, payload):
        """
        捕获平台收到的允许诊断字段。
        """
        receipts.append((call, action, payload))
        return True

    def provider(request):
        """
        仅替换 HTTPS 网络回包，保留原客户端和事件钩子。
        """
        calls.append(request.url.path)
        assert request.headers["Authorization"] == "Bearer fixture-secret"
        assert json.loads(request.content) == {
            "model": "fixture",
            "query": "日末可售库存",
            "documents": ["实际在库", "可售库存"],
            "top_n": 2,
        }
        return httpx.Response(
            200,
            json={
                **(
                    {"meta": {"billed_units": {"input_tokens": 20, "search_units": 1}}}
                    if protocol == "COHERE_RERANK"
                    else {"usage": {"prompt_tokens": 20}}
                ),
                **({"error": "provider reported failure"} if mode == "mixed_error" else {}),
                "results": [
                    {"index": 1, "relevance_score": 0.95 if protocol == "COHERE_RERANK" else 9.5},
                    {"index": 1 if mode == "duplicate" else 0, "relevance_score": 0.12},
                ],
            },
        )

    base = httpx.AsyncClient

    def client(**options):
        """
        验证实际适配器不会使用本机代理或跟随重定向。
        """
        assert not options["trust_env"] and not options["follow_redirects"]
        return base(transport=httpx.MockTransport(provider), **options)

    monkeypatch.setattr(httpx, "AsyncClient", client)
    monkeypatch.setattr(model_receipts, "_write", record)
    config = ModelConfiguration.model_validate(
        {
            "revisionId": "19",
            "protocol": protocol,
            "endpoint": endpoint,
            "modelName": "fixture",
            "timeoutSeconds": 9,
            "apiKey": "fixture-secret",
        }
    )
    with model_call_scope("QUERY", "rag-rerank", "query-1"):
        if mode != "rank":
            # 不能因为 HTTP 200 而接受重复候选，故障也记入运行记录。
            with pytest.raises(ModelFailure, match="MODEL_FORMAT"):
                asyncio.run(rerank(config, "日末可售库存", ["实际在库", "可售库存"]))
        else:
            assert asyncio.run(rerank(config, "日末可售库存", ["实际在库", "可售库存"])) == [1, 0]

    assert calls == [path]
    assert [phase for _, phase, _ in receipts] == ["start", "finish"]
    assert receipts[0][2]["nodeCode"] == "rag-rerank"
    assert receipts[1][2]["failureCode"] == (None if mode == "rank" else "MODEL_FORMAT")
    assert receipts[1][2]["inputTokens"] == (None if mode == "mixed_error" else 20)
    assert receipts[1][2]["outputTokens"] is None
    assert "fixture-secret" not in str(receipts)
