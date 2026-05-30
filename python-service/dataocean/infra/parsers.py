"""LLM 输出解析器（基于 LangChain OutputParser 抽象）

替代各节点散落的手写正则解析，统一从 LLM 文本响应中提取结构化结果。

重要约束：不改变 prompt 要求的输出格式（SQL 仍输出 ```sql``` 代码块，
JSON 仍输出 ```json``` 代码块），因此 sql_generation / chart_generation 等
从 Java 拉取的 managed 模板无需改动，跨语言 prompt 契约保持不变。
本模块只是把「解析」这一步从手写正则换成可复用的 Parser 组件。
"""

from __future__ import annotations

import json
import re

from langchain_core.exceptions import OutputParserException
from langchain_core.output_parsers import BaseOutputParser


class SqlOutputParser(BaseOutputParser[dict]):
    """从 LLM 响应中提取 SQL 语句与口径说明。

    解析结果为 dict：{"sql": str, "explanation": str}。
    优先匹配 ```sql``` 代码块；降级匹配以 SELECT 开头的语句。
    """

    def parse(self, text: str) -> dict:
        return {"sql": self._extract_sql(text), "explanation": self._extract_explanation(text)}

    @staticmethod
    def _extract_sql(text: str) -> str:
        # 优先匹配 ```sql ... ``` 代码块
        match = re.search(r"```sql\s*\n?(.*?)\n?```", text, re.DOTALL | re.IGNORECASE)
        if match:
            return match.group(1).strip()
        # 降级：找以 SELECT 开头的语句
        match = re.search(r"(SELECT\s.+?)(?:;|\n\n|$)", text, re.DOTALL | re.IGNORECASE)
        if match:
            return match.group(1).strip().rstrip(";")
        return ""

    @staticmethod
    def _extract_explanation(text: str) -> str:
        # 取 ```sql``` 代码块之后的首行非空文本作为说明
        match = re.search(r"```\s*\n(.+)", text, re.DOTALL)
        if match:
            explanation = match.group(1).strip()
            for line in explanation.split("\n"):
                line = line.strip()
                if line and not line.startswith("```"):
                    return line
        return ""

    @property
    def _type(self) -> str:
        return "sql_output_parser"


class JsonBlockOutputParser(BaseOutputParser[dict | None]):
    """从 LLM 响应中提取 JSON 对象。

    支持 ```json``` 代码块、裸 JSON、以及裸 `null`（返回 None）。
    allow_null=True 时允许返回 None（如图表生成判定数据不适合作图）；
    allow_null=False 时无法解析则抛出 OutputParserException。
    """

    allow_null: bool = False

    def parse(self, text: str) -> dict | None:
        text = text.strip()
        if self.allow_null and text.lower() == "null":
            return None
        # 匹配 ```json ... ``` 代码块
        match = re.search(r"```(?:json)?\s*\n?(.*?)\n?```", text, re.DOTALL)
        if match:
            content = match.group(1).strip()
            if self.allow_null and content.lower() == "null":
                return None
            return json.loads(content)
        # 降级：裸 JSON
        if text.startswith("{"):
            return json.loads(text)
        if self.allow_null:
            return None
        raise OutputParserException("无法从 LLM 响应中提取 JSON")

    @property
    def _type(self) -> str:
        return "json_block_output_parser"


class LinesOutputParser(BaseOutputParser[list[str]]):
    """按行拆分 LLM 文本响应，去空行并截断到 max_items 条。"""

    max_items: int = 3

    def parse(self, text: str) -> list[str]:
        lines = [line.strip() for line in text.strip().split("\n") if line.strip()]
        return lines[: self.max_items]

    @property
    def _type(self) -> str:
        return "lines_output_parser"
