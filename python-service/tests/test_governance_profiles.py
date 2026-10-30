import pytest

@pytest.mark.asyncio
async def test_dynamic_profile_read_requires_exact_field_set_and_no_old_score_fallback(monkeypatch):
    from dataocean.iam_s1.governance import read_profiles
    async def fake_post(path, payload):
        assert payload['resources'] == [{'tableName': 'orders', 'columns': ['id','paid_amount']}]
        return {'profiles': [{'fieldName':'orders.id'}, {'fieldName':'orders.paid_amount','score':None,'evidenceCount':0}]}
    monkeypatch.setattr('dataocean.iam_s1.governance._post',fake_post)
    result = await read_profiles('task-1',[{'tableName':'orders','columns':['id','paid_amount']}])
    assert result[1]['score'] is None

@pytest.mark.asyncio
async def test_missing_profile_is_service_failure(monkeypatch):
    from dataocean.iam_s1.governance import read_profiles
    from dataocean.iam_s1.java_gateway import JavaAttemptError
    async def incomplete(*args):return {'profiles':[]}
    monkeypatch.setattr('dataocean.iam_s1.governance._post',incomplete)
    with pytest.raises(JavaAttemptError):
        await read_profiles('task-1',[{'tableName':'orders','columns':['id']}])
