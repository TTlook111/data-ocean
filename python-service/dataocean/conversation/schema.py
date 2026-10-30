"""会话长期上下文摘要的请求/响应模型。

Java 负责完整消息与摘要的持久化；本模块只接收消息增量，不保存 conversationId。
"""

from __future__ import annotations

from pydantic import AliasChoices, BaseModel, Field


class ConversationSummaryRequest(BaseModel):
    """Java 发送给 Python 的摘要增量请求，不包含会话 ID。"""

    messages: list[dict] = Field(default_factory=list)
    previous_summary: dict = Field(
        default_factory=dict,
        validation_alias=AliasChoices("previous_summary", "previousSummary"),
    )
    current_date: str | None = Field(
        default=None,
        validation_alias=AliasChoices("current_date", "currentDate"),
    )


class ConversationSummaryResponse(BaseModel):
    """结构化会话摘要响应。"""

    summary: dict
