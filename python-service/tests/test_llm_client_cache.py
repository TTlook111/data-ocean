from types import SimpleNamespace

import pytest

from dataocean.infra import llm


@pytest.mark.parametrize("first,second", [(2,0),(0,2)])
def test_client_cache_preserves_each_callers_retry_limit(first,second,monkeypatch):
    monkeypatch.setattr(llm,"_chat_cache",{})
    monkeypatch.setattr(llm,"get_settings",lambda:SimpleNamespace(
        qwen_model="test-model",llm_temperature=0.1,llm_max_retries=2,
        dashscope_base_url="https://model.invalid/v1",dashscope_api_key="synthetic-key",llm_timeout=30))
    monkeypatch.setattr(llm,"ChatOpenAI",lambda **kwargs:SimpleNamespace(**kwargs))
    a=llm.get_chat_model(max_retries=first,max_tokens=1024)
    b=llm.get_chat_model(max_retries=second,max_tokens=1024)
    assert a.max_retries==first and b.max_retries==second
    assert a is not b
    assert llm.get_chat_model(max_retries=second,max_tokens=1024) is b
