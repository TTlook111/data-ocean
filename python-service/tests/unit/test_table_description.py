"""验证资料裁剪原因、模型输入预算及确切引用的行为。"""

import base64
import json

from dataocean_ai.governance.table_description import MAX_PROMPT_BYTES, SourceContext, prepare


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
