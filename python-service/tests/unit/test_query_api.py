"""验证问数接口、安全序列化、跨配置召回以及外部故障边界。"""

import asyncio
import json
from unittest.mock import AsyncMock, Mock

import pytest
from fastapi import FastAPI
from fastapi.encoders import jsonable_encoder
from fastapi.testclient import TestClient
from pydantic import ValidationError

from dataocean_ai.adapters import model_receipts
from dataocean_ai.adapters.chat import ModelFailure
from dataocean_ai.query import api
from dataocean_ai.query.journal import stable


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


def test_intent_uses_its_model_and_only_authorized_context(monkeypatch, http):
    """问题理解只发送原问题和同源历史，使用自己的模型修订。"""
    result = {
        "retrievalQuery": "按线路查询妥投量，不统计已支付订单",
        "conditions": [
            {
                "phrase": "不统计已支付订单",
                "value": "已支付",
                "field": None,
                "mode": "EXCLUDE",
                "source": None,
                "action": None,
            }
        ],
        "ambiguous": False,
    }
    generate = AsyncMock(return_value=(api.Intent.model_validate(result), None))
    monkeypatch.setattr(api, "generate", generate)
    context = {
        "question": "按线路呢？不统计已支付订单",
        "memory": {"recent": [{"question": "妥投量是多少？"}]},
    }
    job = {"model": model_config("OPENAI_CHAT_COMPLETIONS", "31"), **context}

    response = http.post("/internal/v1/query/intent", json=job)

    assert response.status_code == 200
    assert response.json() == result
    config, prompt, data, schema = generate.await_args.args
    assert config.revision_id == "31"
    assert json.loads(data) == {**context, "clauses": ["按线路呢", "不统计已支付订单"]}
    assert schema is api.Intent
    assert "保留否定语义" in prompt
    assert "排除用 EXCLUDE" in prompt
    assert http.post("/internal/v1/query/intent", json={**job, "evidence": []}).status_code == 422
    generate.assert_awaited_once()


def test_intent_receipt_reference_keeps_original_fingerprint():
    """回读身份不含凭据，仍匹配首次派发的检查点指纹。"""
    model = model_config("OPENAI_CHAT_COMPLETIONS", "31")
    dispatch = {"model": model, "question": "妥投量是多少？"}
    reference = {
        key: value for key, value in model.items() if key not in {"apiKey", "timeoutSeconds"}
    }
    lookup = {**dispatch, "model": reference}
    assert stable(jsonable_encoder(api.ContextJob.model_validate(dispatch))) == stable(
        jsonable_encoder(api.ContextJob.model_validate(lookup))
    )
    with pytest.raises(ModelFailure, match="MODEL_CONFIGURATION_UNAVAILABLE"):
        asyncio.run(api.understand_question(api.ContextJob.model_validate(lookup)))


def test_supplement_preserves_edit_and_full_list_context(monkeypatch, http):
    """补充理解允许明确编辑动作，列表否定保留在同一原句。"""
    question = "在原范围上再排除下列状态：\n已退款，已取消"
    result = {
        "retrievalQuery": "原范围排除退款和取消订单",
        "edits": [
            {
                "segment": 1,
                "value": "已退款",
                "mode": "EXCLUDE",
                "action": "APPEND",
            }
        ],
        "ambiguous": False,
    }
    generate = AsyncMock(return_value=(api.SupplementIntent.model_validate(result), None))
    monkeypatch.setattr(api, "generate", generate)
    response = http.post(
        "/internal/v1/query/intent",
        json={"model": model_config("OPENAI_CHAT_COMPLETIONS"), "question": question},
        headers={"x-query-step": "supplement-scope-clarified"},
    )
    assert response.status_code == 200
    assert response.json()["conditions"][0]["action"] == "APPEND"
    assert response.json()["conditions"][0]["through"] == "在原范围上再排除下列状态：\n已退款"
    assert response.json()["conditions"][0]["phrase"] == question
    assert response.json()["ambiguous"] is False
    _, prompt, data, _ = generate.await_args.args
    assert [item["segment"] for item in json.loads(data)["segments"]] == [1, 2]
    assert all(action in prompt for action in ("APPEND", "REPLACE", "REMOVE"))


def test_supplement_retries_bad_format_and_binds_repeated_edits(monkeypatch, http):
    """格式错误后重提取一次，各次编辑按原话位置生成前缀。"""
    text = "改成只统计未支付，再撤销支付状态限制，再改成只统计未支付，最后排除已退款"
    replace = {"action": "REPLACE", "value": "未支付", "mode": "INCLUDE"}
    reply = api.SupplementIntent.model_validate({
        "retrievalQuery": "未支付且排除退款",
        "ambiguous": False,
        "edits": [
            {"segment": 3, **replace},
            {"segment": 1, **replace},
            {"segment": 2, "action": "REMOVE", "field": "支付状态"},
            {"segment": 4, "action": "APPEND", "value": "已退款", "mode": "EXCLUDE"},
        ],
    })
    generate = AsyncMock(side_effect=[ModelFailure("MODEL_FORMAT"), (reply, None)])
    monkeypatch.setattr(api, "generate", generate)
    response = http.post(
        "/internal/v1/query/intent",
        json={"model": model_config("OPENAI_CHAT_COMPLETIONS"), "question": text},
        headers={"x-query-step": "supplement-scope-clarified"},
    )
    assert response.status_code == 200
    terms = response.json()["conditions"]
    assert generate.await_count == 2
    assert [term["action"] for term in terms] == ["REPLACE", "REMOVE", "REPLACE", "APPEND"]
    assert terms[0]["through"] == "改成只统计未支付"
    assert terms[2]["through"] == "改成只统计未支付，再撤销支付状态限制，再改成只统计未支付"
    assert all(term["phrase"] == text for term in terms)


def test_history_summary_keeps_provenance_and_uses_own_scope(monkeypatch, http):
    """
    摘要只返回实际旧轮次来源，独立模型用量归属本次请求。
    """
    query_id = "c39611fa-8f66-44f3-aee3-fcdb964cea8f"
    old_id = "77c7aafb-c039-491c-a628-557d0a5ec09c"
    summary = {"requestIds": [old_id]}

    async def selected(config, prompt, data, schema):
        """
        向提供方发送已获准的旧计划及近期比较资料。
        """
        assert model_receipts._scope.get() == model_receipts.CallScope(
            "QUERY", "history-summary", query_id
        )
        assert config.revision_id == "75"
        assert "近期计划只用于核对" in prompt
        assert "secret-fixture-value" not in data
        assert json.loads(data)["memory"]["earlier"][0]["requestId"] == old_id
        return schema.model_validate(summary), None

    generate = AsyncMock(side_effect=selected)
    monkeypatch.setattr(api, "generate", generate)
    job = {
        "model": model_config("OPENAI_CHAT_COMPLETIONS", "75"),
        "question": "原口径上周呢？",
        "memory": {
            "timeAnchor": "2026-11-24T01:00:00Z",
            "recent": [],
            "earlier": [{"requestId": old_id, "plan": {"metric": "已确认指标"}}],
        },
    }
    response = http.post(
        "/internal/v1/query/history-summary", json=job, headers={"X-Query-Id": query_id}
    )
    assert response.status_code == 200
    assert response.json() == summary
    generate.assert_awaited_once()


def test_rerank_node_uses_its_own_receipt_and_complete_order(monkeypatch, http):
    """
    同一候选集合使用重排配置外发，续查身份不依赖凭据。
    """
    query_id = "c39611fa-8f66-44f3-aee3-fcdb964cea8f"

    async def rank(config, query, documents):
        """
        检查独立模型归属而不发送网络请求。
        """
        assert model_receipts._scope.get() == model_receipts.CallScope(
            "QUERY", "rag-rerank", query_id
        )
        assert config.revision_id == "61" and config.protocol == "COHERE_RERANK"
        assert query == "日末可售库存" and documents == ["在库", "可售"]
        return [1, 0]

    transport = AsyncMock(side_effect=rank)
    monkeypatch.setattr(api, "rerank", transport)
    model = model_config("COHERE_RERANK", "61")
    job = {"model": model, "query": "日末可售库存", "documents": ["在库", "可售"]}
    response = http.post("/internal/v1/query/rerank", json=job, headers={"X-Query-Id": query_id})
    assert response.status_code == 200 and response.json() == {"order": [1, 0]}
    reference = {
        key: value for key, value in model.items() if key not in {"apiKey", "timeoutSeconds"}
    }
    assert stable(jsonable_encoder(api.RerankJob.model_validate(job))) == stable(
        jsonable_encoder(api.RerankJob.model_validate({**job, "model": reference}))
    )
    wrong = http.post(
        "/internal/v1/query/rerank", json={**job, "model": model_config("OPENAI_EMBEDDINGS")}
    )
    assert wrong.status_code == 422 and "secret-fixture-value" not in wrong.text
    transport.assert_awaited_once()


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
        "evidence": [
            {
                "binding": "88",
                "text": "旧说明使用默认计量方式",
                "metrics": [
                    {
                        "name": "能耗合计",
                        "rule": "SUM(meter_daily.reading)",
                        "metricRules": {
                            "dateMode": "BUSINESS_DAY",
                            "dateField": field,
                            "fixedFilters": [
                                {
                                    "field": {"table": "meter_daily", "column": "state"},
                                    "value": "CONFIRMED",
                                }
                            ],
                            "unitMode": "COLUMN",
                            "unitField": {"table": "meter_daily", "column": "unit"},
                        },
                    }
                ],
            }
        ],
    }
    response = http.post(f"/internal/v1/query/model/{step}", json=job)
    assert response.status_code == 200
    assert response.json()["decision"] == expected["decision"]
    _, system, data, result_type = generate.await_args.args
    assert result_type is schema
    assert "secret-fixture-value" not in data
    assert json.loads(data)["evidence"] == job["evidence"]
    if step in {"plan", "semantic"}:
        # 规划和语义复核以确认规则核对口径，也能看到需要核验的实际列。
        assert "以确认规则为准" in system or "以 metricRules 确认" in system
    if step in {"semantic", "output"}:
        # 独立审核按服务端确定的完整时间范围处理。
        assert "包含起止日" in json.loads(data)["question"]
    else:
        # 规划与普通审核使用不同结构，参数修复保留原含义。
        assert "READY 的形状" in system
    assert ("输入 plan 的值与字段实际类型不兼容" in system) == (step == "repair-parameter")


def test_semantic_scope_receives_exclusions_without_changing_legacy_shape(monkeypatch, http):
    """复核节点保留范围方向和排除理由，旧请求的空字段不进入回执指纹。"""
    model = model_config("OPENAI_CHAT_COMPLETIONS")
    field = {"table": "orders", "column": "payment_state"}
    condition = {"field": field, "value": "PAID"}
    old = {
        "model": model,
        "question": "查订单金额",
        "plan": {"decision": "READY", "filters": [condition]},
    }
    legacy = jsonable_encoder(api.ModelJob.model_validate(old))
    assert legacy["plan"]["filters"] == [condition]
    assert "requestedTerms" not in legacy and "selection" not in legacy

    user = {**condition, "exclude": True}
    selection = {
        "terms": [{"phrase": "不统计已支付", "value": "已支付", "mode": "EXCLUDE"}],
        "conditions": [user],
        "excluded": [
            {
                "binding": "6",
                "ordinal": 0,
                "reason": "USER_CONDITION_CONFLICT",
                "fixedFilters": [condition],
                "comparison": {"requested": user, "equal": True},
                "evidence": {
                    "snippet": {"binding": "6", "text": "仅统计已支付"},
                    "columns": [{"field": field, "object": "1", "column": "15", "revision": "3"}],
                    "actualObjects": [],
                    "boundDates": [],
                    "joinKeys": [[field, {"table": "payments", "column": "state"}]],
                },
            },
            {
                "binding": "8",
                "ordinal": 0,
                "reason": "RELATION_NOT_APPLICABLE",
                "fixedFilters": [],
                "evidence": {
                    "snippet": {"binding": "8", "text": "订单和区域关联"},
                    "columns": [],
                    "actualObjects": [],
                    "boundDates": [],
                    "joinKeys": [],
                    "joinAssessment": {
                        "reason": "RELATION_NOT_APPLICABLE",
                        "requiredTables": ["orders", "regions"],
                        "dateField": {"table": "orders", "column": "business_day"},
                        "dateBounds": ["2026-11-25"],
                        "relations": [
                            {
                                "revision": "12",
                                "keys": [[
                                    {"table": "outlets", "column": "region_id"},
                                    {"table": "regions", "column": "id"},
                                ]],
                                "rule": "多对一",
                                "usage": "仅适用于 2026-11-26 起",
                                "applicable": False,
                            }
                        ],
                    },
                },
            },
        ],
    }
    generate = AsyncMock(return_value=(api.Check(decision="ALLOW"), None))
    monkeypatch.setattr(api, "generate", generate)
    job = {
        **old,
        "question": "查订单金额，不统计已支付",
        "plan": {"decision": "READY", "binding": "7", "filters": [user]},
        "selection": selection,
    }
    response = http.post("/internal/v1/query/model/semantic", json=job)
    assert response.status_code == 200
    _, prompt, data, _ = generate.await_args.args
    assert json.loads(data)["selection"] == selection
    assert json.loads(data)["plan"]["filters"] == [user]
    assert "理由是否足以排除" in prompt
    assert "joinKeys" in prompt
    assert "joinAssessment" in prompt


def test_model_and_explanation_error_contracts(monkeypatch, http, caplog):
    """审查仍返回真实失败类型，说明模型只收到本次可披露快照。"""
    config = model_config("ANTHROPIC_MESSAGES")
    job = api.ModelJob.model_validate({"model": config, "question": "妥投量多少"})
    monkeypatch.setattr(api, "generate", AsyncMock(side_effect=ModelFailure("TIMEOUT")))
    with pytest.raises(ModelFailure, match="TIMEOUT"):
        asyncio.run(api.decide("plan", job))
    assert "妥投量多少" not in caplog.text
    generate = AsyncMock(return_value=(api.ExplanationSelection(factIds=[1]), None))
    monkeypatch.setattr(api, "generate", generate)
    facts = ["本次查询的妥投量为 7 单。"]
    response = http.post(
        "/internal/v1/query/explanation",
        json={"model": config, "scope": "末轮确认范围", "facts": facts},
    )
    assert response.json() == {"factIds": [1]}
    _, _, data, shape = generate.await_args.args
    assert shape is api.ExplanationSelection
    assert json.loads(data) == {"scope": "末轮确认范围", "facts": facts}


@pytest.mark.parametrize("wrong_text", ["已支付订单金额为 40 元", "未支付订单金额为 40 万元"])
def test_explanation_selection_rejects_model_authored_facts(wrong_text):
    """错口径与错单位的模型文字不能进入编号回执。"""
    with pytest.raises(ValidationError):
        api.ExplanationSelection.model_validate({"factIds": [1], "text": wrong_text})


@pytest.mark.parametrize("fact_ids", [[0], [2], [1, 1]])
def test_explanation_rejects_unknown_or_duplicate_fact_ids(monkeypatch, fact_ids):
    """只接受当前快照中的唯一合法引用。"""
    monkeypatch.setattr(api, "generate", AsyncMock(return_value=(
        api.ExplanationSelection(factIds=fact_ids), None
    )))
    job = api.ExplanationJob.model_validate({
        "model": model_config("ANTHROPIC_MESSAGES"),
        "facts": ["本次查询的订单金额为 40.00 元。"],
    })
    with pytest.raises(ModelFailure, match="MODEL_FORMAT"):
        asyncio.run(api.explain_result(job))


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


def test_retrieval_cancels_sibling_embedding_before_returning_failure(monkeypatch):
    """
    一个模型失败后，仍在等待的同批模型须先完成取消清理。
    """
    query = api.Retrieval.model_validate(
        {"remainingSeconds": 3, "question": "物流", "indexes": [index(), index("12", "24")]}
    )
    search = Mock()
    monkeypatch.setattr(api, "search", search)

    async def scenario():
        """
        让失败发生在另一个向量化请求启动后，检查返回时的清理事实。
        """
        started = asyncio.Event()
        cleaned = asyncio.Event()

        async def embed(model, _texts):
            """
            第一份配置在另一路挂起后失败，另一路只能通过取消结束。
            """
            if model.revision_id == "21":
                # 先保证另一个请求已经启动。
                await started.wait()
                raise ModelFailure("MODEL_AUTH")
            started.set()
            try:
                # 跟真实等待的 HTTP 调用一样，在取消前不返回。
                await asyncio.Future()
            finally:
                # 以清理事件记录连接释放时机。
                cleaned.set()

        monkeypatch.setattr(api, "embed", embed)
        with pytest.raises(ModelFailure, match="MODEL_AUTH"):
            await api.recall(query)
        assert cleaned.is_set()

    asyncio.run(scenario())
    search.assert_not_called()


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
