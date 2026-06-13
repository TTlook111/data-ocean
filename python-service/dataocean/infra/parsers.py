"""Reusable LangChain output parsers for LLM responses."""

from __future__ import annotations

import re
from typing import TypeVar

from langchain_core.exceptions import OutputParserException
from langchain_core.output_parsers import BaseOutputParser, JsonOutputParser, PydanticOutputParser
from pydantic import BaseModel


class SqlOutputParser(BaseOutputParser[dict]):
    """Extract a SQL statement and optional explanation from an LLM response."""

    def parse(self, text: str) -> dict:
        return {"sql": self._extract_sql(text), "explanation": self._extract_explanation(text)}

    @staticmethod
    def _extract_sql(text: str) -> str:
        match = re.search(r"```sql\s*\n?(.*?)\n?```", text, re.DOTALL | re.IGNORECASE)
        if match:
            sql = match.group(1).strip()
            # 去除末尾分号
            return sql.rstrip(";").strip()
        match = re.search(r"(SELECT\s.+?)(?:;|\n\n|$)", text, re.DOTALL | re.IGNORECASE)
        if match:
            return match.group(1).strip().rstrip(";")
        return ""

    @staticmethod
    def _extract_explanation(text: str) -> str:
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
    """Parse JSON from plain text or fenced markdown using LangChain's parser."""

    allow_null: bool = False

    def parse(self, text: str) -> dict | None:
        stripped = text.strip()
        if self.allow_null and stripped.lower() == "null":
            return None

        parser = JsonOutputParser()
        try:
            parsed = parser.parse(stripped)
        except Exception as exc:
            if self.allow_null:
                return None
            raise OutputParserException("Unable to parse JSON from LLM response") from exc

        if parsed is None and self.allow_null:
            return None
        if isinstance(parsed, dict):
            return parsed
        if self.allow_null and parsed is None:
            return None
        raise OutputParserException(f"Expected JSON object, got {type(parsed).__name__}")

    @property
    def _type(self) -> str:
        return "json_block_output_parser"


TModel = TypeVar("TModel", bound=BaseModel)


class PydanticJsonBlockOutputParser(BaseOutputParser[TModel | None]):
    """Parse JSON and validate it with a Pydantic model."""

    pydantic_object: type[TModel]
    allow_null: bool = False

    def parse(self, text: str) -> TModel | None:
        stripped = text.strip()
        if self.allow_null and stripped.lower() == "null":
            return None

        parser = PydanticOutputParser(pydantic_object=self.pydantic_object)
        try:
            return parser.parse(stripped)
        except Exception as exc:
            if self.allow_null:
                return None
            raise OutputParserException("Unable to parse Pydantic JSON from LLM response") from exc

    @property
    def _type(self) -> str:
        return "pydantic_json_block_output_parser"


class LinesOutputParser(BaseOutputParser[list[str]]):
    """Split non-empty response lines and truncate to max_items."""

    max_items: int = 3

    def parse(self, text: str) -> list[str]:
        lines = [line.strip() for line in text.strip().split("\n") if line.strip()]
        return lines[: self.max_items]

    @property
    def _type(self) -> str:
        return "lines_output_parser"
