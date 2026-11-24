"""
验证正文入口使用独立调用记账，并保护模型配置和内部正文。
"""

import json
from unittest.mock import AsyncMock

from fastapi import FastAPI
from fastapi.testclient import TestClient

from dataocean_ai.adapters import model_receipts
from dataocean_ai.knowledge import draft_generation


def test_draft_binds_receipt_to_actual_task_and_revision(monkeypatch):
    """
    原章节只作比对资料，出站调用绑定正文模型与实际任务。
    """
    app = FastAPI()
    app.include_router(draft_generation.router)
    app.dependency_overrides[draft_generation.verify_java] = lambda: None
    change = {"sectionIndex": 0, "title": "库存范围", "body": "当前可用数量。", "basis": [1]}

    async def result(config, system, data, schema):
        """
        核对实际出站身份、确认修订和输出契约。
        """
        assert model_receipts._scope.get() == model_receipts.CallScope(
            "KNOWLEDGE", "document-write", "201"
        )
        assert config.revision_id == "61"
        assert "零起始序号" in system
        assert "单测密钥" not in data
        actual = json.loads(data)
        assert actual["original"] == [
            {"sectionIndex": 0, "title": "人工说明", "body": "手工正文。"}
        ]
        assert actual["brief"]["facts"][0]["fact"]["revisionId"] == "31"
        return schema.model_validate({"changes": [change]}), None

    generate = AsyncMock(side_effect=result)
    monkeypatch.setattr(draft_generation, "generate", generate)
    job = {
        "taskId": "201",
        "model": {
            "revisionId": "61",
            "protocol": "OPENAI_CHAT_COMPLETIONS",
            "endpoint": "https://fixture.invalid/v1",
            "modelName": "fixture",
            "timeoutSeconds": 12,
            "apiKey": "单测密钥",
        },
        "brief": {
            "title": "库存说明",
            "purpose": "当前库存",
            "questions": [],
            "exclusions": [],
            "gaps": [],
            "facts": [
                {
                    "number": 1,
                    "selected": True,
                    "fact": {
                        "target": {"objectId": "3", "kind": "TABLE", "targetId": "3"},
                        "revisionId": "31",
                        "label": "inventory",
                        "content": {"definition": "库存范围"},
                        "dependencies": [],
                    },
                }
            ],
        },
        "original": [{"title": "人工说明", "body": "手工正文。"}],
    }
    with TestClient(app) as client:
        # 经过真正的内部 HTTP 处理器核对异常不会泄露凭据。
        path = "/internal/v1/knowledge/document-drafts"
        response = client.post(path, json=job)
        invalid = client.post(path, json={**job, "taskId": "0"})
    assert response.status_code == 200
    assert response.json() == {"changes": [change]}
    assert invalid.status_code == 422
    assert "单测密钥" not in invalid.text
    generate.assert_awaited_once()
