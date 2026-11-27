"""验证反馈相似入口的范围输入、向量排序和故障边界。"""

from unittest.mock import AsyncMock

from fastapi.testclient import TestClient

from dataocean_ai.adapters.chat import ModelFailure
from dataocean_ai.api.app import create_app
from dataocean_ai.feedback import similarity


def test_similarity_ranks_only_provided_candidates_and_hides_inputs(monkeypatch):
    """语义排序仅返回本轮候选位置，无权限和异常不披露原文。"""
    app = create_app()
    client = TestClient(app)
    request = {
        "issueId": "91",
        "model": {
            "revisionId": "32",
            "protocol": "OPENAI_EMBEDDINGS",
            "endpoint": "https://example.invalid/v1",
            "modelName": "embedding-fixture",
            "timeoutSeconds": 7,
            "apiKey": "fixture-private-secret",
        },
        "question": "二号站点上月接诊量",
        "candidates": ["本月保养工单", "二号站点上月接诊人数", "上月门诊服务次数"],
    }
    route = "/internal/v1/feedback/similarity"
    embed = AsyncMock(return_value=[[1, 0], [0, 1], [1, 0], [0.8, 0.6]])
    monkeypatch.setattr(similarity, "embed", embed)
    monkeypatch.setenv("AI_JAVA_PUBLIC_KEY", "unused-public-key.pem")
    assert client.post(route, json=request).status_code == 401
    embed.assert_not_awaited()

    def authorized_service():
        """后续分支模拟已经通过内部服务 JWT 核验的 Java 请求。"""

    app.dependency_overrides[similarity.verify_java] = authorized_service
    response = client.post(route, json=request)
    assert response.status_code == 200
    assert response.json() == {"order": [1, 2]}
    assert embed.await_args.args[1] == [request["question"], *request["candidates"]]

    malformed = {**request, "candidates": []}
    rejected = client.post(route, json=malformed)
    assert rejected.status_code == 422
    assert "fixture-private-secret" not in rejected.text
    assert request["question"] not in rejected.text
    embed.assert_awaited_once()

    embed.side_effect = ModelFailure("MODEL_RESULT_UNKNOWN")
    assert client.post(route, json=request).status_code == 503
    assert embed.await_count == 2
