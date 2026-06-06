"""RAG chunking strategy.

Python owns RAG-specific splitting. Java sends the complete skills.md document,
stores the returned chunk snapshot for governance/rebuild visibility, and never
implements its own chunking rules.
"""

from __future__ import annotations

import logging
import re

from .schema import ChunkItem

logger = logging.getLogger(__name__)

MAX_CHUNK_TEXT_LENGTH = 8000

_SKIP_SECTION_KEYWORDS = ("文档来源", "document source")

_TABLE_NAME_PATTERNS = (
    re.compile(r"适用表\s*[:：]?\s*`?([a-zA-Z_][\w\-]*)`?", re.IGNORECASE),
    re.compile(r"涉及表\s*[:：]?\s*`?([a-zA-Z_][\w\-]*)`?", re.IGNORECASE),
    re.compile(r"表名\s*[:：]?\s*`?([a-zA-Z_][\w\-]*)`?", re.IGNORECASE),
    re.compile(r"^###\s+`?([a-zA-Z_][\w\-]*)`?\s*[—\-]", re.MULTILINE),
    re.compile(r"^###\s+`?([a-zA-Z_][\w\-]*)`?\s*↔", re.MULTILINE),
    re.compile(r"^###\s+`?([a-zA-Z_][\w\-]*)`?\.`?([a-zA-Z_][\w\-]*)`?", re.MULTILINE),
)


def chunk_tables(tables_metadata: list[dict]) -> list[ChunkItem]:
    """Build one TABLE_DESC chunk per table for schema-only fallback usage."""
    chunks: list[ChunkItem] = []
    for table in tables_metadata:
        table_name = table.get("table_name", "")
        table_comment = table.get("table_comment", "")
        columns = table.get("columns", [])

        column_texts = []
        for col in columns:
            col_name = col.get("column_name", "")
            col_type = col.get("column_type", "")
            col_comment = col.get("column_comment", "")
            column_texts.append(
                f"{col_name}({col_type}): {col_comment}" if col_comment else f"{col_name}({col_type})"
            )

        chunk_text = f"表 {table_name}"
        if table_comment:
            chunk_text += f" - {table_comment}"
        chunk_text += "\n字段: " + ", ".join(column_texts[:20])
        if len(column_texts) > 20:
            chunk_text += f" ... 共{len(column_texts)}个字段"

        chunks.append(
            ChunkItem(
                chunk_type="TABLE_DESC",
                chunk_text=chunk_text,
                related_table=table_name,
                governance_status=table.get("governance_status", "NORMAL"),
                review_status="APPROVED",
            )
        )

    return chunks


def chunk_skills_md(content: str) -> list[ChunkItem]:
    """Split skills.md into retrieval-oriented chunks.

    Strategy:
    1. Split by H2 (`##`) to identify major business sections.
    2. Skip non-retrieval sections such as document provenance.
    3. Split each section by H3 (`###`) so one table, metric, join path,
       field note, or query scene becomes one chunk.
    4. If a section has no H3 entries, keep the whole section as one chunk.
    """
    if not content or not content.strip():
        return []

    chunks: list[ChunkItem] = []
    for section in _split_by_h2(content):
        first_line = section.split("\n", 1)[0].strip()
        if _should_skip_section(first_line):
            continue

        section_chunk_type = _infer_chunk_type(first_line)
        for text in _split_by_h3(section):
            normalized = text.strip()
            if len(normalized) < 10:
                continue
            chunks.append(
                ChunkItem(
                    chunk_type=section_chunk_type,
                    chunk_text=normalized[:MAX_CHUNK_TEXT_LENGTH],
                    related_table=_extract_table_name(normalized),
                    related_column=_extract_column_name(normalized),
                    governance_status="NORMAL",
                    review_status="APPROVED",
                )
            )

    logger.info("skills.md chunking complete chunks=%d", len(chunks))
    return chunks


def _split_by_h2(content: str) -> list[str]:
    parts = re.split(r"\n(?=##\s+)", content.strip())
    return [part.strip() for part in parts if part.strip()]


def _split_by_h3(section: str) -> list[str]:
    parts = re.split(r"\n(?=###\s+)", section)
    if len(parts) <= 1:
        return [section]

    result: list[str] = []
    for part in parts:
        stripped = part.strip()
        if not stripped:
            continue
        if stripped.startswith("## ") and stripped.count("\n") <= 1:
            continue
        result.append(stripped)
    return result or [section]


def _should_skip_section(title: str) -> bool:
    lowered = title.lower()
    return any(keyword.lower() in lowered for keyword in _SKIP_SECTION_KEYWORDS)


def _extract_table_name(text: str) -> str:
    for pattern in _TABLE_NAME_PATTERNS:
        match = pattern.search(text)
        if not match:
            continue
        name = match.group(1).strip()
        if name and len(name) <= 64:
            return name
    return ""


def _extract_column_name(text: str) -> str:
    match = re.search(r"^###\s+`?[a-zA-Z_][\w\-]*`?\.`?([a-zA-Z_][\w\-]*)`?", text, re.MULTILINE)
    return match.group(1) if match else ""


def _infer_chunk_type(text: str) -> str:
    lower = text.lower()
    if any(keyword in lower for keyword in ("join", "关联", "join path")) or "↔" in text:
        return "JOIN_PATH"
    if any(keyword in lower for keyword in ("指标", "metric", "sql 表达式", "口径")):
        return "METRIC"
    if any(keyword in lower for keyword in ("防坑", "注意", "误用", "字段")):
        return "FIELD_NOTE"
    if any(keyword in lower for keyword in ("场景", "查询场景", "骨架", "scenario")):
        return "QUERY_SCENE"
    return "TABLE_DESC"
