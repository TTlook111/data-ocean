"""策略合同、真实消费者接线、预算与无敏感回执的回归。所有 HTTP/模型均隔离。"""
import json
import time
import asyncio
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock
from contextlib import asynccontextmanager

import httpx
import pytest

from dataocean.prompt import scenes
_REAL_RECORD = scenes.record_invocation


@pytest.mark.parametrize('kind', ['trailing_scalar', 'trailing_object', 'overflow_version'])
def test_policy_json_rejects_trailing_tokens_and_overflow_version(kind):
    valid = '{"formatVersion":1,"system":"role","user":"{{input}}"}'
    content = {'trailing_scalar': valid + ' true', 'trailing_object': valid + ' {}',
               'overflow_version': valid.replace(':1,', ':4294967297,')}[kind]
    with pytest.raises(ValueError):
        scenes.parse_policy('field_semantic', content)


@asynccontextmanager
async def running_receipts():
    from dataocean.prompt import receipts
    dispatcher = receipts.start_receipt_dispatcher()
    try:
        yield dispatcher
    finally:
        await receipts.close_receipt_dispatcher()


def test_dynamic_input_is_forbidden_in_system_role_even_when_all_variables_are_present():
    content = json.dumps({"formatVersion": 1, "system": "{{input}}", "user": "返回结果"})
    with pytest.raises(ValueError, match="system"):
        scenes.parse_policy("schema_linking", content)


@pytest.mark.asyncio
async def test_previously_approved_policy_with_system_variables_falls_back_safely(isolated):
    fetch, _ = isolated
    fetch.side_effect = None
    fetch.return_value = {"content": json.dumps({"formatVersion": 1, "system": "{{input}}", "user": "JSON"}), "versionNo": 6}
    result = await scenes.resolve_scene("schema_linking", {"input": "不可信业务文字"}, fixed_system="固定合同", fallback_user="原有输入")
    assert result.source == "BUILTIN" and result.fallback_reason == "INVALID_TEMPLATE"
    assert result.system == "固定合同" and "不可信业务文字" not in result.system


@pytest.mark.asyncio
async def test_slow_receipt_network_cannot_timeout_successful_model(monkeypatch, isolated):
    import asyncio
    fetch, _ = isolated
    fetch.side_effect = None
    fetch.return_value = {"content": "{{input}}", "versionNo": 2}
    monkeypatch.setattr(scenes, "record_invocation", _REAL_RECORD)
    async def delayed_network(request):
        await asyncio.sleep(0.2)
        delivered.append(request)
        return httpx.Response(200)
    delivered = []
    client_type = httpx.AsyncClient
    monkeypatch.setattr(scenes.httpx, "AsyncClient", lambda **kwargs: client_type(transport=httpx.MockTransport(delayed_network), **kwargs))
    model = AsyncMock(return_value="成功内容")
    async with running_receipts() as dispatcher:
        async with asyncio.timeout(0.05):
            result = await scenes.invoke_scene("field_semantic", {"input": "业务数据"}, fixed_system="固定合同", fallback_user="内置", invoke=model)
        assert result == "成功内容"
        await dispatcher.queue.join()
        assert len(delivered) == 1


@pytest.mark.asyncio
async def test_receipt_queue_is_bounded_and_shutdown_cancels_only_worker(monkeypatch, caplog):
    from dataocean.prompt import receipts
    entered, blocked = asyncio.Event(), asyncio.Event()
    async def hanging_network(request):
        entered.set()
        await blocked.wait()
        return httpx.Response(200)
    client_type = httpx.AsyncClient
    monkeypatch.setattr(receipts.httpx, "AsyncClient", lambda **kwargs: client_type(transport=httpx.MockTransport(hanging_network), **kwargs))
    dispatcher = receipts.ReceiptDispatcher(capacity=1)
    try:
        assert dispatcher.enqueue({"sceneCode": "field_semantic", "invocationId": "first"})
        await asyncio.wait_for(entered.wait(), 1)
        assert dispatcher.enqueue({"sceneCode": "field_semantic", "invocationId": "second"})
        assert not dispatcher.enqueue({"sceneCode": "field_semantic", "invocationId": "dropped"})
        assert dispatcher.queue.qsize() == 1
    finally:
        await dispatcher.close(drain_timeout=0.01)
    assert dispatcher.worker.done() and not dispatcher.accepting and dispatcher.queue.empty()
    assert not dispatcher.enqueue({"sceneCode": "field_semantic", "invocationId": "after-close"})
    await asyncio.wait_for(dispatcher.queue.join(), 0.1)
    assert "队列已满" in caplog.text and "排空超时" in caplog.text


@pytest.mark.asyncio
async def test_graph_settles_before_slow_receipt_network(monkeypatch, isolated):
    from dataocean.iam_s1 import graph
    graph._estimate_tokens("warm tokenizer outside deadline")
    fetch, _ = isolated
    fetch.side_effect = None
    fetch.return_value = {"content": "核对 {{input}}", "versionNo": 5}
    model = AsyncMock(return_value=('{}', {"input_tokens": 12, "output_tokens": 2}))
    reserve = AsyncMock(return_value={"allowed": True, "callsUsed": 1})
    settle = AsyncMock(return_value={"allowed": True, "callsUsed": 1, "estimatedCostCny": 0.01})
    received = []
    async def slow_network(request):
        received.append((json.loads(request.content), settle.await_count))
        await asyncio.sleep(0.2)
        return httpx.Response(200)
    client_type = httpx.AsyncClient
    monkeypatch.setattr(scenes.httpx, "AsyncClient", lambda **kwargs: client_type(transport=httpx.MockTransport(slow_network), **kwargs))
    monkeypatch.setattr(graph, "record_invocation", _REAL_RECORD)
    monkeypatch.setattr(graph, "call_llm_with_usage", model)
    monkeypatch.setattr(graph, "reserve_model_call", reserve)
    monkeypatch.setattr(graph, "settle_model_call", settle)
    monkeypatch.setattr(graph, "get_settings", lambda: SimpleNamespace(qwen_model="qwen-flash"))
    async with running_receipts() as dispatcher:
        state = {"taskId": "review-receipt", "deadlineEpochSeconds": time.time() + 0.15}
        async with asyncio.timeout(0.1):
            result, updated = await graph._budgeted_llm(state, "sql_semantic_check", "固定合同", "input json", "semantic:1")
        assert result == '{}' and updated["llmCalls"] == 1 and settle.await_count == 1
        await dispatcher.queue.join()
    assert received[0][1] == 1 and received[0][0]["versionNo"] == 5


@pytest.fixture
def isolated(monkeypatch):
    fetch = AsyncMock(side_effect=RuntimeError("isolated offline"))
    report = AsyncMock()
    monkeypatch.setattr(scenes, "fetch_template", fetch)
    monkeypatch.setattr(scenes, "record_invocation", report)
    return fetch, report


@pytest.mark.asyncio
@pytest.mark.parametrize("code", scenes.VARIABLES)
async def test_every_scene_renders_versioned_role_and_task_with_fixed_contract(code, isolated):
    fetch, _ = isolated
    variables = {name: "业务值 {{do_not_render}}" for name in scenes.VARIABLES[code]}
    user = "\n".join("{{" + name + "}}" for name in variables)
    fetch.side_effect = None
    fetch.return_value = {"content": json.dumps({"formatVersion": 1, "system": "新角色", "user": user}), "versionNo": 7}
    prompt = await scenes.resolve_scene(code, variables, fixed_system="固定输出合同", fallback_user="内置")
    assert prompt.version == 7 and prompt.source == "MANAGED"
    assert prompt.system.endswith("固定输出合同") and "新角色" in prompt.system
    assert "{{do_not_render}}" in prompt.user  # 业务值不会作为模板再次执行。
    assert prompt.fallback_reason == ""


@pytest.mark.asyncio
@pytest.mark.parametrize("status,reason", [(404, "MISSING"), (409, "DISABLED"), (403, "UNAVAILABLE"), (500, "UNAVAILABLE")])
async def test_missing_disabled_and_unavailable_have_explicit_builtin_receipts(status, reason, isolated):
    fetch, report = isolated
    request = httpx.Request("GET", "http://internal/prompts/field_semantic")
    response = httpx.Response(status, request=request)
    fetch.side_effect = httpx.HTTPStatusError("private provider response", request=request, response=response)
    invoke = AsyncMock(return_value="model answer")
    assert await scenes.invoke_scene("field_semantic", {"input": "secret business"},
        fixed_system="固定合同", fallback_user="内置 secret business", invoke=invoke) == "model answer"
    prompt = report.call_args.args[0]
    assert prompt.source == "BUILTIN" and prompt.version == 0 and prompt.fallback_reason == reason
    assert report.call_args.kwargs["outcome"] == "MODEL_RETURNED"


@pytest.mark.asyncio
@pytest.mark.parametrize("content", ["missing input", "{{unknown}}", "{{input.__class__}}", "{% if input %}{{input}}{% endif %}", '{"formatVersion":true,"system":"角色","user":"{{input}}"}'])
async def test_invalid_strategy_falls_back_without_weakening_fixed_contract(content, isolated):
    fetch, _ = isolated
    fetch.side_effect = None
    fetch.return_value = {"content": content, "versionNo": 2}
    prompt = await scenes.resolve_scene("field_semantic", {"input": {}}, fixed_system="固定合同", fallback_user="原有安全内容")
    assert prompt.source == "BUILTIN" and prompt.fallback_reason == "INVALID_TEMPLATE"
    assert prompt.system == "固定合同" and prompt.user == "原有安全内容"


@pytest.mark.asyncio
async def test_programmer_variable_error_is_not_hidden_by_fallback(isolated):
    with pytest.raises(ValueError, match="variables"):
        await scenes.resolve_scene("field_semantic", {"unexpected": "x"}, fixed_system="", fallback_user="")
    isolated[0].assert_not_called()


@pytest.mark.asyncio
async def test_field_candidate_really_uses_managed_strategy_and_keeps_output_validation(monkeypatch, isolated):
    from dataocean.knowledge import semantic
    fetch, report = isolated
    fetch.side_effect = None
    fetch.return_value = {"content": "使用新的业务表达方式 {{input}}", "versionNo": 3}
    model = AsyncMock(return_value=json.dumps({"businessName": "客户编号", "meaning": "关联客户", "evidence": "字段来源", "unresolvedItems": []}))
    monkeypatch.setattr(semantic, "call_llm", model)
    request = semantic.FieldSemanticRequest(datasourceId=1, snapshotId=1, field={"tableName": "orders", "columnName": "customer_id", "dataType": "BIGINT"})
    result = await semantic.generate_field_candidate(request)
    assert result["candidateStatus"] == "PENDING_REVIEW"
    assert "新的业务表达方式" in model.call_args.kwargs["user_prompt"]
    assert "不得给出审核结论" in model.call_args.kwargs["system_prompt"]
    model.return_value = '{"businessName":123}'
    with pytest.raises(ValueError):
        await semantic.generate_field_candidate(request)
    assert report.call_args.kwargs["outcome"] == "MODEL_RETURNED"  # 不把模型返回伪造成格式通过。


@pytest.mark.asyncio
async def test_graph_budget_and_receipt_reference_same_managed_call(monkeypatch, isolated):
    from dataocean.iam_s1 import graph
    fetch, _ = isolated
    fetch.side_effect = None
    fetch.return_value = {"content": "新的核对策略 {{input}}", "versionNo": 5}
    report = AsyncMock()
    model = AsyncMock(return_value=('{}', {"input_tokens": 12, "output_tokens": 2}))
    reserve = AsyncMock(return_value={"allowed": True, "callsUsed": 1})
    settle = AsyncMock(return_value={"allowed": True, "callsUsed": 1, "estimatedCostCny": 0.01})
    monkeypatch.setattr(graph, "record_invocation", report)
    monkeypatch.setattr(graph, "call_llm_with_usage", model)
    monkeypatch.setattr(graph, "reserve_model_call", reserve)
    monkeypatch.setattr(graph, "settle_model_call", settle)
    monkeypatch.setattr(graph, "get_settings", lambda: SimpleNamespace(qwen_model="qwen-flash"))
    state = {"taskId": "prompt-test", "deadlineEpochSeconds": time.time() + 30}
    result, updated = await graph._budgeted_llm(state, "sql_semantic_check", "固定合同", "input json", "semantic:1")
    assert result == '{}' and updated["llmCalls"] == 1
    assert model.call_args.args[1] == "新的核对策略 input json"
    assert reserve.await_count == settle.await_count == report.await_count == 1
    assert report.call_args.args[0].version == 5
    assert report.call_args.kwargs["task_id"] == state["taskId"]


@pytest.mark.asyncio
async def test_receipt_failure_never_replaces_provider_failure(monkeypatch, isolated):
    fetch, _ = isolated
    fetch.side_effect = None
    fetch.return_value = {"content": "{{input}}", "versionNo": 2}
    # 用真实回执函数及隔离 HTTP transport 验证不上传原始业务数据。
    monkeypatch.setattr(scenes, "record_invocation", _REAL_RECORD)
    captured = []
    def handler(request):
        captured.append(json.loads(request.content))
        return httpx.Response(503)
    client_type = httpx.AsyncClient
    monkeypatch.setattr(scenes.httpx, "AsyncClient", lambda **kwargs: client_type(transport=httpx.MockTransport(handler), **kwargs))
    invoke = AsyncMock(side_effect=RuntimeError("model failed"))
    async with running_receipts() as dispatcher:
        with pytest.raises(RuntimeError, match="model failed"):
            await scenes.invoke_scene("field_semantic", {"input": "private secret"}, fixed_system="fixed", fallback_user="fallback", invoke=invoke)
        await dispatcher.queue.join()
    assert captured[0]["outcome"] == "MODEL_FAILED"
    assert "private secret" not in json.dumps(captured)
    assert set(captured[0]) == {"invocationId", "taskId", "sceneCode", "versionNo", "source", "contractVersion", "contentHash", "fallbackReason", "outcome", "durationMs"}


def test_java_and_python_scene_contracts_stay_in_sync():
    # 跨语言合同漂移会导致静默回退；校验真实 Java 注册表而不是另写测试常量。
    source = (Path(__file__).parents[2] / "backend/DataOcean/src/main/java/com/dataocean/module/prompt/service/PromptSceneRegistry.java").read_text(encoding="utf-8")
    for code, variables in scenes.VARIABLES.items():
        line = next(line for line in source.splitlines() if f'scene("{code}"' in line)
        declared = line.split('List.of(', 1)[1].split(')', 1)[0]
        assert tuple(json.loads('[' + declared + ']')) == variables
