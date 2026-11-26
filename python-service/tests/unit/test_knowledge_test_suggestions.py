"""
验证标准题建议按独立节点调用，并拒绝模型捏造的预期数值字段。
"""

import json
from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError

from dataocean_ai.adapters import model_receipts
from dataocean_ai.knowledge import test_suggestions


def test_suggestions_send_confirmed_document_and_bind_own_receipt(monkeypatch):
    """
    内部 API 绑定标准题节点配置和确切任务号，模型只看到获准正文。
    """
    app = FastAPI()
    app.include_router(test_suggestions.router)
    app.dependency_overrides[test_suggestions.verify_java] = lambda: None
    candidate = {
        "question": "日末库存能跨日累计吗？",
        "coverage": "BOUNDARY",
        "mode": "EXPLANATION",
        "validationHint": "确认回答说明跨日限制",
        "sections": [0],
    }

    async def result(config, system, data, schema):
        """
        确认模型输入不包含密钥，章节顺序可被准确引用。
        """
        assert model_receipts._scope.get() == model_receipts.CallScope(
            "KNOWLEDGE", "question-suggestion", "201"
        )
        assert config.revision_id == "77"
        assert "不得假定样例" in system
        assert "单测密钥" not in data
        actual = json.loads(data)
        assert actual["document"]["sections"][0]["index"] == 0
        assert actual["existingQuestions"] == ["如何查日末库存？"]
        return schema.model_validate({"candidates": [candidate]}), None

    generate = AsyncMock(side_effect=result)
    monkeypatch.setattr(test_suggestions, "generate", generate)
    job = {
        "taskId": "201",
        "model": {
            "revisionId": "77",
            "protocol": "OPENAI_CHAT_COMPLETIONS",
            "endpoint": "https://fixture.invalid/v1",
            "modelName": "fixture",
            "timeoutSeconds": 12,
            "apiKey": "单测密钥",
        },
        "input": {
            "document": {
                "title": "日末库存",
                "purpose": "核对某日库存",
                "exclusions": "不能跨日累计",
                "questions": [],
                "sections": [{"title": "使用边界", "body": "只能看指定日期"}],
            },
            "existingQuestions": ["如何查日末库存？"],
        },
    }
    with TestClient(app) as client:
        # 真实请求处理器回包禁止包含提交时的私密输入。
        path = "/internal/v1/knowledge/test-suggestions"
        response = client.post(path, json=job)
        invalid = client.post(path, json={**job, "taskId": "0"})
    assert response.status_code == 200
    assert response.json() == {"candidates": [candidate]}
    assert invalid.status_code == 422
    assert "单测密钥" not in invalid.text
    generate.assert_awaited_once()
    with pytest.raises(ValidationError, match="expectedCount"):
        # 正式数值预期不能从生成回包进入题单。
        test_suggestions.SuggestionResult.model_validate(
            {"candidates": [{**candidate, "expectedCount": 7}]}
        )
