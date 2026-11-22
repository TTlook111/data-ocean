"""验证问数接口、安全序列化、跨配置召回以及外部故障边界。"""

import asyncio
import json
from unittest.mock import AsyncMock, Mock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from dataocean_ai.adapters.chat import ModelFailure
from dataocean_ai.query import api


def model_config(kind: str, revision: str = "21") -> dict:
    """在当前测试进程构造合成模型配置。"""
    return {
        "revisionId": revision,
        "protocol": kind,
        "endpoint": "https://provider.invalid/v1",
        "modelName": "model-fixture",
        "timeoutSeconds": 9,
        "apiKey": "secret-fixture-value",
    }


def index(binding: str = "11", revision: str = "21") -> dict:
    """向量索引都绑定各自的发布代次和摘要。"""
    return {
        "binding": binding,
        "candidate": "35",
        "revision": "44",
        "fingerprint": "a" * 64,
        "collection": f"industry_{binding}",
        "fragments": [{"ordinal": 0, "digest": "b" * 64}],
        "embedding": model_config("OPENAI_EMBEDDINGS", revision),
    }


@pytest.fixture
def http(monkeypatch):
    """以 HTTP 测契约，将持久化调用交给专门的真实 Redis 测试。"""
    app = FastAPI()
    app.include_router(api.router)

    async def run_step(_request, _job, invoke):
        """模拟当前请求已经获准登记，仍执行各实际接口逻辑。"""
        return await invoke()

    app.dependency_overrides[api.verify_java] = lambda: None
    monkeypatch.setattr(api, "journal", run_step)
    return TestClient(app)


def test_validation_does_not_echo_credentials_and_request_body(http):
    """内部输入不合法时只返回可识别错误码。"""
    response = http.post(
        "/internal/v1/query/model/plan",
        json={"model": model_config("OPENAI_EMBEDDINGS"), "question": "internal question"},
    )
    assert response.status_code == 422
    assert response.json() == {"code": "QUERY_INPUT_INVALID"}


@pytest.mark.parametrize("step", ["plan", "repair-parameter", "semantic", "output"])
def test_model_routes_use_only_fixed_scope(monkeypatch, http, step):
    """为工厂能耗检查规划和审核，服务凭据不会拼进模型正文。"""
    expected = (
        {"decision": "READY", "operation": "SUM"}
        if step in {"plan", "repair-parameter"}
        else {
            "decision": "ALLOW",
        }
    )
    schema = api.Plan if step in {"plan", "repair-parameter"} else api.Check
    generate = AsyncMock(return_value=(schema.model_validate(expected), None))
    monkeypatch.setattr(api, "generate", generate)
    field = {"table": "meter_daily", "column": "measured_on"}
    job = {
        "model": model_config("OPENAI_CHAT_COMPLETIONS"),
        "question": "比较车间能耗",
        "plan": {
            "decision": "READY",
            "time": {
                "field": field,
                "mode": "TREND",
                "start": "2026-11-16",
                "end": "2026-11-22",
                "defaulted": True,
            },
        },
        "evidence": [{"binding": "88", "text": "电表读数以 kWh 计"}],
    }
    response = http.post(f"/internal/v1/query/model/{step}", json=job)
    assert response.status_code == 200
    assert response.json()["decision"] == expected["decision"]
    _, system, data, result_type = generate.await_args.args
    assert result_type is schema
    assert "secret-fixture-value" not in data
    assert json.loads(data)["evidence"] == job["evidence"]
    if step in {"semantic", "output"}:
        # 独立审核按服务端确定的完整时间范围处理。
        assert "包含起止日" in json.loads(data)["question"]
    else:
        # 规划与普通审核使用不同结构，参数修复保留原含义。
        assert "READY 的形状" in system
    assert ("输入 plan 的值与字段实际类型不兼容" in system) == (step == "repair-parameter")


def test_model_and_explanation_error_contracts(monkeypatch, http, caplog):
    """审查用真实失败类型，解释只拿本次实际结果和问题。"""
    config = model_config("ANTHROPIC_MESSAGES")
    job = api.ModelJob.model_validate({"model": config, "question": "妥投量多少"})
    monkeypatch.setattr(api, "generate", AsyncMock(side_effect=ModelFailure("TIMEOUT")))
    with pytest.raises(ModelFailure, match="TIMEOUT"):
        asyncio.run(api.decide("plan", job))
    assert "妥投量多少" not in caplog.text
    generate = AsyncMock(return_value=(api.ExplanationText(text="实际 7 单"), None))
    monkeypatch.setattr(api, "generate", generate)
    result = {"columns": ["妥投量"], "rows": [["7"]]}
    response = http.post(
        "/internal/v1/query/explanation",
        json={"model": config, "question": "妥投量多少", "result": result},
    )
    assert response.json() == {"text": "实际 7 单"}
    _, _, data, shape = generate.await_args.args
    assert shape is api.ExplanationText
    assert json.loads(data) == {"question": "妥投量多少", "result": result}


def test_retrieval_reuses_only_same_embedding_revision(monkeypatch, http):
    """不同索引使用自己的模型修订和向量，不能跨代次互换。"""
    embed = AsyncMock(side_effect=[[[0.11]], [[0.22]]])
    monkeypatch.setattr(api, "embed", embed)

    def search(candidate, vector):
        """质检两个真实配置族的预期向量和不同评分顺序。"""
        assert vector == ([0.22] if candidate.binding == "13" else [0.11])
        score = {"11": 0.2, "12": 0.8, "13": 0.9}[candidate.binding]
        return [(score, api.Hit(binding=candidate.binding, ordinal=0, digest="b" * 64))]

    monkeypatch.setattr(api, "search", search)
    response = http.post(
        "/internal/v1/query/retrieve",
        json={
            "question": "能耗",
            "indexes": [index(), index("12"), index("13", "24")],
            "remainingSeconds": 10,
        },
    )
    assert response.status_code == 200
    assert [row["binding"] for row in response.json()] == ["12", "11", "13"]
    assert [args.args[0].revision_id for args in embed.await_args_list] == ["21", "24"]
    monkeypatch.setattr(api, "embed", AsyncMock(side_effect=TimeoutError))
    query = api.Retrieval.model_validate(
        {"remainingSeconds": 3, "question": "物流", "indexes": [index()]}
    )
    with pytest.raises(ModelFailure, match="TIMEOUT"):
        asyncio.run(api.recall(query))


def test_index_search_checks_generation_and_releases_connection(monkeypatch):
    """缺失配置、跨代次行和向量服务故障均不得生成虚假的匹配。"""
    selected = api.Index.model_validate(index())
    monkeypatch.delenv("AI_MILVUS_URI", raising=False)
    with pytest.raises(ModelFailure, match="INDEX_UNAVAILABLE"):
        api.search(selected, [0.11])
    monkeypatch.setenv("AI_MILVUS_URI", "http://127.0.0.1:19530")
    client = Mock()
    constructor = Mock(return_value=client)
    monkeypatch.setattr("pymilvus.MilvusClient", constructor)
    entity = {
        "candidate": "35",
        "revision": "44",
        "fingerprint": "a" * 64,
        "embedding": "21",
        "generation": "industry_11",
        "digest": "b" * 64,
    }
    client.search.return_value = [[{"id": 0, "distance": 0.81, "entity": entity}]]
    score, hit = api.search(selected, [0.11])[0]
    assert score == 0.81
    assert hit.binding == "11"
    assert client.search.call_args.kwargs["consistency_level"] == "Strong"
    for key in ("generation", "digest"):
        # 同一文档的其他发布代次也不能复用当前授权。
        client.search.return_value = [[{"id": 0, "distance": 1, "entity": entity | {key: "stale"}}]]
        with pytest.raises(ModelFailure, match="INDEX_MISMATCH"):
            api.search(selected, [0.11])
    client.search.side_effect = OSError("unavailable")
    with pytest.raises(ModelFailure, match="INDEX_UNAVAILABLE"):
        api.search(selected, [0.11])
    assert client.close.call_count == 4
