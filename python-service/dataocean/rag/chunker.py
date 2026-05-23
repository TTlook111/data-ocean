"""RAG 分块策略

将元数据和 skills.md 内容切分为适合向量化的 chunks。
"""

import logging

from .schema import ChunkItem

logger = logging.getLogger(__name__)


def chunk_tables(tables_metadata: list[dict]) -> list[ChunkItem]:
    """表级分块：每张表生成一个 chunk

    chunk_text 格式：表名 + 注释 + 字段列表摘要
    """
    chunks = []
    for table in tables_metadata:
        table_name = table.get("table_name", "")
        table_comment = table.get("table_comment", "")
        columns = table.get("columns", [])

        # 组装 chunk 文本
        col_texts = []
        for col in columns:
            col_name = col.get("column_name", "")
            col_type = col.get("column_type", "")
            col_comment = col.get("column_comment", "")
            col_texts.append(
                f"{col_name}({col_type}): {col_comment}" if col_comment else f"{col_name}({col_type})"
            )

        chunk_text = f"表: {table_name}"
        if table_comment:
            chunk_text += f" — {table_comment}"
        chunk_text += "\n字段: " + ", ".join(col_texts[:20])  # 限制长度
        if len(col_texts) > 20:
            chunk_text += f" ... 共{len(col_texts)}个字段"

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
    """skills.md 按二级标题切分"""
    if not content:
        return []

    sections = content.split("\n## ")
    chunks = []
    for i, section in enumerate(sections):
        text = section.strip()
        if not text:
            continue
        if i > 0:
            text = "## " + text

        # 推断 chunk_type
        chunk_type = _infer_chunk_type(text)
        chunks.append(
            ChunkItem(
                chunk_type=chunk_type,
                chunk_text=text[:8000],  # Milvus VARCHAR 限制
                related_table="",
            )
        )

    return chunks


def _infer_chunk_type(text: str) -> str:
    """根据内容推断切片类型"""
    lower = text.lower()
    if "join" in lower or "关联" in lower:
        return "JOIN_PATH"
    elif "指标" in lower or "metric" in lower:
        return "METRIC"
    elif "防坑" in lower or "注意" in lower:
        return "FIELD_NOTE"
    return "TABLE_DESC"
