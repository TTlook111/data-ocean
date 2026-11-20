"""验证候选发现回写的实际资料与限定的目录。"""

import json

from dataocean_ai.adapters.chat import CallReceipt
from dataocean_ai.governance.discovery import DiscoveryAnswer
from dataocean_ai.governance.table_description import SourceContext, prepare, validate_answer


def test_discovery_returns_located_evidence_without_transient_identifiers() -> None:
    """候选只保存当前发送的资料实际位置，忽略未知标号。"""
    context = SourceContext.model_validate(
        {
            "objectName": "products",
            "sourceComment": None,
            "columns": [],
            "contentPaths": [],
            "notes": [{"id": "21", "body": "在册商品按唯一商品编号计数。", "attachments": []}],
            "discovery": {
                "kind": "METRIC",
                "anchor": "1",
                "tables": [
                    {
                        "id": "1",
                        "name": "products",
                        "fields": [
                            {
                                "id": "11",
                                "name": "id",
                                "type": "bigint",
                                "comment": None,
                                "primary": True,
                            }
                        ],
                        "foreignKeys": [],
                    }
                ],
            },
        }
    )
    state = prepare({"context": context})
    assert json.loads(state["prompt"])["scope"]["anchor"] == "1"
    state["discovered"] = DiscoveryAnswer.model_validate(
        {
            "candidates": [
                {
                    "name": "在册数",
                    "meaning": "在册商品",
                    "rule": "COUNT(DISTINCT products.id)",
                    "usage": "件",
                    "pairs": [],
                    "fields": ["11"],
                    "citations": ["E1", "E99"],
                }
            ]
        }
    )
    state["call"] = CallReceipt(
        revision_id="2",
        model_name=None,
        provider_call_id=None,
        elapsed_ms=1,
        input_tokens=None,
        output_tokens=None,
    )
    result = validate_answer(state)["result"].model_dump(by_alias=True)
    assert result["usedNoteIds"] == ["21"]
    assert "citations" not in result["candidates"][0]
    assert [ref["noteId"] for ref in result["candidates"][0]["evidence"]] == ["21"]
