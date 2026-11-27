"""验证资料裁剪原因、模型输入预算及确切引用的行为。"""

import base64
import json

from dataocean_ai.adapters.chat import CallReceipt
from dataocean_ai.governance.table_description import (
    MAX_PROMPT_BYTES,
    ModelAnswer,
    SourceContext,
    prepare,
    validate_answer,
)


def test_table_context_keeps_only_sent_evidence() -> None:
    """按序列化预算限制长中文资料，并记录超列和无法解析的附件。"""
    context = SourceContext.model_validate(
        {
            "objectName": "fixture",
            "sourceComment": "",
            "columns": [{"name": f"column_{i}", "type": "text", "comment": ""} for i in range(101)],
            "contentPaths": ["purpose"],
            "notes": [
                {"id": "1", "body": "退货不计入营业额。", "attachments": []},
                {
                    "id": "2",
                    "body": "",
                    "attachments": [
                        {
                            "id": "2",
                            "name": "source.pdf",
                            "data": base64.b64encode(b"%PDF-fixture").decode(),
                        }
                    ],
                },
                {"id": "3", "body": "长资料" * 8000, "attachments": []},
            ],
        }
    )
    state = prepare({"context": context})
    prompt = state["prompt"]
    assert len(prompt.encode("utf-8")) <= MAX_PROMPT_BYTES
    assert len(json.loads(prompt)["columns"]) <= 100
    assert set(state["limits"]) == {"COLUMN_LIMIT", "ATTACHMENT_UNAVAILABLE", "INPUT_BUDGET"}
    assert state["locators"]["E1"].noteId == "1"
    assert "2" not in state["used"]


def test_unannotated_table_does_not_accept_unsupported_business_fields() -> None:
    """只提供可检查的记录草稿，即使上游返回了未授权的业务断言。"""
    context = SourceContext.model_validate(
        {
            "objectName": "community.repairs",
            "sourceComment": "",
            "columns": [
                {"name": "site_id", "type": "bigint", "comment": None},
                {"name": "recorded_on", "type": "date", "comment": ""},
            ],
            "contentPaths": ["purpose", "rowGrain", "rowIdentity", "updatePolicy"],
            "notes": [],
        }
    )
    state = prepare({"context": context})
    assert json.loads(state["prompt"])["allowedFields"] == ["purpose", "rowGrain"]
    state["answer"] = ModelAnswer.model_validate(
        {
            "fields": [
                {"field": "rowGrain", "value": "每条记录保存站点与登记日期", "citations": []},
                {"field": "rowIdentity", "value": "同一站点当天唯一", "citations": []},
                {"field": "updatePolicy", "value": "每天更新", "citations": []},
            ]
        }
    )
    state["call"] = CallReceipt(
        revision_id="21",
        model_name="fixture",
        provider_call_id=None,
        elapsed_ms=10,
        input_tokens=None,
        output_tokens=None,
    )
    result = validate_answer(state)["result"]
    assert result.content == {"rowGrain": "每条记录保存站点与登记日期"}
