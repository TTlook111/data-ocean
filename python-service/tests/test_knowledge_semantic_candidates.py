import json
import pytest
from dataocean.knowledge.semantic import SemanticRequest, supplement


def request():
    return SemanticRequest(datasourceId=1, snapshotId=88,
                           authorizedDependencies=["table:orders", "column:orders.id"],
                           schema=[{"column": "orders.id", "type": "BIGINT"}])


@pytest.mark.asyncio
async def test_returns_candidates_without_promoting_model_review_status(monkeypatch):
    async def model(**kwargs):
        assert "orders.id" in kwargs["user_prompt"]
        return json.dumps({"candidates": [{"text": "订单标识，具体业务口径待确认", "dependencies": ["table:orders", "column:orders.id"], "reviewStatus": "APPROVED"}]})
    monkeypatch.setattr("dataocean.knowledge.semantic.call_llm", model)
    result = await supplement(request())
    assert len(result["candidates"]) == 1
    assert set(result["candidates"][0]) == {"text", "dependencies"}


@pytest.mark.asyncio
@pytest.mark.parametrize("deps", [[], ["column:orders.id"], ["table:users", "column:users.secret"]])
async def test_rejects_missing_or_out_of_scope_dependencies(monkeypatch, deps):
    async def model(**_):
        return json.dumps({"candidates": [{"text": "候选", "dependencies": deps}]})
    monkeypatch.setattr("dataocean.knowledge.semantic.call_llm", model)
    with pytest.raises(ExceptionGroup):
        await supplement(request())


@pytest.mark.asyncio
async def test_provider_failure_is_propagated_without_fake_success(monkeypatch):
    async def model(**_):
        raise RuntimeError("provider unavailable")
    monkeypatch.setattr("dataocean.knowledge.semantic.call_llm", model)
    with pytest.raises(ExceptionGroup):
        await supplement(request())


@pytest.mark.asyncio
async def test_empty_scope_does_not_call_model(monkeypatch):
    async def model(**_):
        pytest.fail("empty scope must not consume a model call")
    monkeypatch.setattr("dataocean.knowledge.semantic.call_llm", model)
    with pytest.raises(ValueError):
        await supplement(SemanticRequest(datasourceId=1, snapshotId=88, authorizedDependencies=[]))


@pytest.mark.asyncio
@pytest.mark.parametrize("error,code", [
    (RuntimeError("Arrearage: private provider detail"), "AI_BILLING_REQUIRED"),
    (TimeoutError("private provider detail"), "AI_GENERATION_TIMEOUT"),
    (ValueError("private model output"), "AI_INVALID_OUTPUT"),
])
async def test_endpoint_exposes_safe_actionable_failures_from_batch_groups(monkeypatch, error, code):
    from dataocean.knowledge.router import supplement_semantics
    async def fail(_):
        raise ExceptionGroup("batch failed", [error])
    monkeypatch.setattr("dataocean.knowledge.router.supplement", fail)
    response = await supplement_semantics(request())
    assert response.status_code == 502
    assert json.loads(response.body)["code"] == code
    assert "private" not in response.body.decode()


@pytest.mark.asyncio
async def test_term_crossing_batch_boundary_is_sent_with_all_its_dependencies(monkeypatch):
    observed = []
    async def model(**kwargs):
        source = json.loads(kwargs["user_prompt"].split("\n", 1)[1])
        observed.extend(source["reviewedGlossary"])
        column = next(d for d in source["dependencies"] if d.startswith("column:"))
        return json.dumps({"candidates": [{"text": "字段释义待确认", "dependencies": ["table:orders", column]}]})
    monkeypatch.setattr("dataocean.knowledge.semantic.call_llm", model)
    fact = {"dependencies": ["table:orders", "column:orders.c39", "column:orders.c40"]}
    await supplement(SemanticRequest(datasourceId=1, snapshotId=88,
        authorizedDependencies=["table:orders"] + [f"column:orders.c{i}" for i in range(41)], glossaryFacts=[fact]))
    assert observed == [fact]
