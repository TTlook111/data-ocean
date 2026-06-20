"""RAG chunking strategy backed by LangChain text splitters.

Python owns RAG-specific splitting. Java sends the complete skills.md document,
stores the returned chunk snapshot for governance/rebuild visibility, and never
implements its own chunking rules.
"""

from __future__ import annotations

import logging
import re

from langchain_text_splitters import (
    MarkdownHeaderTextSplitter,
    RecursiveCharacterTextSplitter,
)

from .schema import ChunkItem

logger = logging.getLogger(__name__)

MAX_CHUNK_TEXT_LENGTH = 8000
LONG_CHUNK_SIZE = 3000
LONG_CHUNK_OVERLAP = 200
MIN_CHUNK_TEXT_LENGTH = 10

_MARKDOWN_SPLITTER = MarkdownHeaderTextSplitter(
    headers_to_split_on=[
        ("##", "section"),
        ("###", "heading"),
    ],
    strip_headers=False,
)

_LONG_CHUNK_SPLITTER = RecursiveCharacterTextSplitter(
    chunk_size=LONG_CHUNK_SIZE,
    chunk_overlap=LONG_CHUNK_OVERLAP,
    separators=["\n\n", "\n", "。", ".", " ", ""],
)

_SKIP_SECTION_KEYWORDS = (
    "document source",
    "\u6587\u6863\u6765\u6e90",
)

_TABLE_NAME_PATTERNS = (
    re.compile(r"\u9002\u7528\u8868\s*[:\uff1a]?\s*`?([a-zA-Z_][\w\-]*)`?", re.IGNORECASE),
    re.compile(r"\u6d89\u53ca\u8868\s*[:\uff1a]?\s*`?([a-zA-Z_][\w\-]*)`?", re.IGNORECASE),
    re.compile(r"\u8868\u540d\s*[:\uff1a]?\s*`?([a-zA-Z_][\w\-]*)`?", re.IGNORECASE),
    re.compile(r"^###\s+`?([a-zA-Z_][\w\-]*)`?\s*[\u2014\-]", re.MULTILINE),
    re.compile(r"^###\s+`?([a-zA-Z_][\w\-]*)`?\s*[\u2194\u2192]", re.MULTILINE),
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

        chunk_text = f"\u8868 {table_name}"
        if table_comment:
            chunk_text += f" - {table_comment}"
        chunk_text += "\n\u5b57\u6bb5: " + ", ".join(column_texts[:20])
        if len(column_texts) > 20:
            chunk_text += f" ... \u5171{len(column_texts)}\u4e2a\u5b57\u6bb5"

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

    LangChain handles Markdown header splitting and recursive long-text
    splitting. DataOcean keeps the domain mapping from header metadata to
    chunk_type and table/column metadata.
    """
    if not content or not content.strip():
        return []

    chunks: list[ChunkItem] = []
    for document in _MARKDOWN_SPLITTER.split_text(content):
        section = str(document.metadata.get("section", ""))
        heading = str(document.metadata.get("heading", ""))
        if _should_skip_section(section or heading):
            continue

        chunk_type = _infer_chunk_type(section, heading, document.page_content)
        for text in _split_long_chunk(document.page_content):
            normalized = text.strip()
            if len(normalized) < MIN_CHUNK_TEXT_LENGTH:
                continue
            chunks.append(
                ChunkItem(
                    chunk_type=chunk_type,
                    chunk_text=normalized[:MAX_CHUNK_TEXT_LENGTH],
                    related_table=_extract_table_name(normalized, heading),
                    related_column=_extract_column_name(normalized, heading),
                    governance_status="NORMAL",
                    review_status="APPROVED",
                )
            )

    logger.info("skills.md chunking complete chunks=%d splitter=langchain", len(chunks))
    return chunks


def _split_long_chunk(text: str) -> list[str]:
    """拆分超长 chunk

    使用 RecursiveCharacterTextSplitter 按段落、句子等边界拆分，
    避免单个 chunk 过大影响检索精度。
    """
    if len(text) <= MAX_CHUNK_TEXT_LENGTH:
        return [text]
    return _LONG_CHUNK_SPLITTER.split_text(text)


def _should_skip_section(title: str) -> bool:
    """判断是否跳过该章节

    跳过"文档来源"等非业务内容章节。
    """
    lowered = title.lower()
    return any(keyword.lower() in lowered for keyword in _SKIP_SECTION_KEYWORDS)


def _extract_table_name(text: str, heading: str = "") -> str:
    """从文本中提取表名

    使用预编译的正则模式匹配表名，优先从标题中提取，
    标题未匹配则从正文中提取。
    """
    for candidate in (heading, text):
        for pattern in _TABLE_NAME_PATTERNS:
            match = pattern.search(candidate)
            if not match:
                continue
            name = match.group(1).strip()
            if name and len(name) <= 64:
                return name
    return ""


def _extract_column_name(text: str, heading: str = "") -> str:
    for candidate in (heading, text):
        match = re.search(
            r"^###?\s+`?[a-zA-Z_][\w\-]*`?\.`?([a-zA-Z_][\w\-]*)`?",
            candidate,
            re.MULTILINE,
        )
        if match:
            return match.group(1)
    return ""


def _infer_chunk_type(section: str, heading: str, text: str) -> str:
    header_text = f"{section}\n{heading}".lower()
    body_text = text.lower()
    if any(keyword in header_text for keyword in ("join", "join path", "\u5173\u8054", "\u2194", "\u2192")):
        return "JOIN_PATH"
    if any(keyword in header_text for keyword in ("metric", "\u6307\u6807", "\u53e3\u5f84")):
        return "METRIC"
    if any(keyword in header_text for keyword in ("field note", "\u9632\u5751", "\u6ce8\u610f", "\u8bef\u7528")):
        return "FIELD_NOTE"
    if any(keyword in header_text for keyword in ("scenario", "\u573a\u666f", "\u67e5\u8be2\u573a\u666f", "\u9aa8\u67b6")):
        return "QUERY_SCENE"
    if any(keyword in body_text for keyword in ("join", "join path", "\u5173\u8054\u6761\u4ef6", "\u2194", "\u2192")):
        return "JOIN_PATH"
    if any(keyword in body_text for keyword in ("metric", "sql \u8868\u8fbe\u5f0f", "\u6307\u6807\u540d\u79f0")):
        return "METRIC"
    return "TABLE_DESC"
