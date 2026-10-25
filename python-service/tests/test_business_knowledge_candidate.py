import pytest

@pytest.mark.asyncio
async def test_business_candidate_keeps_unknowns_and_never_approves(monkeypatch):
    from dataocean.knowledge.business import generate_candidate
    async def fake_call(**kwargs):
        assert 'sampleValues' not in kwargs['user_prompt']
        return '{"body":{"description":"订单业务"},"unresolvedItems":["业务粒度尚未确认"],"evidence":"字段元数据"}'
    monkeypatch.setattr('dataocean.knowledge.business.call_llm', fake_call)
    result = await generate_candidate({'kind': 'TABLE', 'title': '订单', 'schema': []})
    assert result['unresolvedItems'] == ['业务粒度尚未确认']
    assert 'status' not in result

@pytest.mark.asyncio
async def test_business_candidate_reports_billing_failure_without_business_clarification(monkeypatch):
    from dataocean.knowledge.router import business_candidate
    import json
    async def billing(_):raise RuntimeError('Arrearage')
    monkeypatch.setattr('dataocean.knowledge.business.generate_candidate',billing)
    response=await business_candidate({'kind':'TABLE'})
    assert response.status_code==502
    assert json.loads(response.body)['code']=='AI_BILLING_REQUIRED'
