"""验证字段 Agent 取证与逐轮资格核对。"""

import asyncio
import json
from contextlib import asynccontextmanager

import pytest
from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
from langchain_core.messages import AIMessage

from dataocean_ai.adapters.chat import ChatConfiguration, ModelFailure
from dataocean_ai.governance import field_agent
from dataocean_ai.governance.table_description import ModelAnswer


class FixtureModel(FakeMessagesListChatModel):
    """以预设模型回复驱动正式 Agent 的工具和结果节点。"""

    def bind_tools(self, tools, **kwargs):
        """由固定的测试回复决定工具调用。"""
        return self


@pytest.mark.parametrize("revoked", [False, True])
def test_agent_reads_current_task_evidence_and_rechecks(monkeypatch, revoked: bool) -> None:
    """模型要求读资料时取证，第二轮之前撤权则阻止生成。"""
    calls = 0
    model = FixtureModel(
        responses=[
            AIMessage(
                content="",
                tool_calls=[
                    {"name": "read_business_notes", "args": {}, "id": "read"},
                ],
            ),
            AIMessage(
                content="",
                tool_calls=[
                    {
                        "name": "ModelAnswer",
                        "args": {
                            "fields": [
                                {"field": "meaning", "value": "销售订单状态", "citations": ["E1"]}
                            ]
                        },
                        "id": "answer",
                    },
                ],
            ),
        ]
    )

    @asynccontextmanager
    async def session(_config):
        """用合成模型替换提供方网络，保留 Agent 的执行循环。"""
        yield model

    async def recheck():
        """模拟第二次发送前的平台复查结果。"""
        nonlocal calls
        calls += 1
        if revoked and calls == 2:
            # 任务失效时不能继续发送工具回执。
            raise ModelFailure("STALE_INPUT")

    monkeypatch.setattr(field_agent, "model_session", session)
    settings = ChatConfiguration.model_validate(
        {
            "revisionId": "17",
            "protocol": "OPENAI_CHAT_COMPLETIONS",
            "endpoint": "https://model.example.invalid",
            "modelName": "test",
            "timeoutSeconds": 15,
            "apiKey": "fixture-only",
        }
    )
    prompt = json.dumps(
        {
            "targetColumn": {"name": "status"},
            "allowedFields": ["meaning"],
            "columns": [],
            "sourceExcerpts": [{"id": "E1", "text": "status 表示销售订单状态"}],
        }
    )
    operation = field_agent.explain_field(settings, prompt, ModelAnswer, recheck)
    if revoked:
        # 确认第二次调用没有因为工具选择而绕过权限。
        with pytest.raises(ModelFailure, match="STALE_INPUT"):
            asyncio.run(operation)
        assert model.i == 1
    else:
        # 只有真实取用的备注和固定修订才进入回执。
        answer, receipt, read_notes = asyncio.run(operation)
        assert receipt.revision_id == "17"
        assert read_notes
        assert answer.fields[0].citations == ["E1"]
    assert calls == 2
