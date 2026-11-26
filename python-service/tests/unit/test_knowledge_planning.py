"""验证独立规划节点的真实内部入口和可外发字段。"""

import json
from unittest.mock import AsyncMock

from fastapi import FastAPI
from fastapi.testclient import TestClient

from dataocean_ai.adapters import model_receipts
from dataocean_ai.knowledge import planning


def test_document_plan_uses_its_model_and_approved_facts(monkeypatch):
    """规划只向模型提供当前源资料，并绑定知识规划回执归属。"""
    app = FastAPI()
    app.include_router(planning.router)
    app.dependency_overrides[planning.verify_java] = lambda: None
    candidate = {
        "title": "库存口径",
        "purpose": "按已确认库存说明解释数量",
        "questions": ["如何核对可售库存？"],
        "exclusions": [],
        "gaps": [],
        "basis": [1],
    }

    async def response(config, system, data, schema):
        """检查实际出站业务身份和模型输入，并返回受控建议。"""
        assert model_receipts._scope.get() == model_receipts.CallScope(
            "KNOWLEDGE", "document-plan", "201"
        )
        assert config.revision_id == "61"
        assert schema is planning.PlanResult
        assert "配置占位秘密" not in data
        assert "只建议文档范围" in system
        actual = json.loads(data)
        assert actual["facts"][0]["fact"]["revisionId"] == "31"
        assert actual["sourceName"] == "验收库存源"
        assert "configuration" not in actual
        return schema.model_validate({"documents": [candidate]}), None

    generate = AsyncMock(side_effect=response)
    monkeypatch.setattr(planning, "generate", generate)
    job = {
        "taskId": "201",
        "model": {
            "revisionId": "61",
            "protocol": "OPENAI_CHAT_COMPLETIONS",
            "endpoint": "https://planning.invalid/v1",
            "modelName": "fixture",
            "timeoutSeconds": 12,
            "apiKey": "配置占位秘密",
        },
        "planning": {
            "configuration": 7,
            "sourceName": "验收库存源",
            "goal": "",
            "facts": [
                {
                    "number": 1,
                    "fact": {
                        "target": {"objectId": "3", "kind": "TABLE", "targetId": "3"},
                        "revisionId": "31",
                        "label": "inventory",
                        "content": {"definition": "已确认库存范围"},
                        "dependencies": [],
                    },
                }
            ],
            "existingDocuments": [],
        },
    }
    with TestClient(app) as client:
        actual = client.post("/internal/v1/knowledge/document-plans", json=job)
        invalid = client.post("/internal/v1/knowledge/document-plans", json={**job, "taskId": "0"})
    assert actual.status_code == 200
    assert actual.json()["documents"] == [{**candidate, "existingDocumentId": None}]
    assert invalid.status_code == 422
    assert "配置占位秘密" not in invalid.text
    generate.assert_awaited_once()
